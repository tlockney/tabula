"""Pairing and claim views (docs/PROTOCOL.md section 2, ADR 0001 items 1-2).

Both views are unauthenticated, rate-limited per source IP and cap-bounded, exactly as
docs/PROTOCOL.md 2.2 and ADR 0002 say. Every response body is a schema frame:
inbound bodies are validated leniently (strict senders, tolerant receivers), outbound
bodies strictly. A confirmed device's system user and HA device are created by the hub
when the user confirms the code in the discovery flow; the claim view hands out the
refresh token exactly once.
"""

from __future__ import annotations

import hashlib
import json
import math
import secrets
import time
from collections import deque
from dataclasses import dataclass
from typing import Any

from aiohttp import web
from homeassistant.components.http import KEY_HASS, HomeAssistantView
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.http import HTTPStatus

from .const import (
    CLAIM_POLL_INTERVAL,
    DOMAIN,
    PAIR_MAX_BODY,
    PAIR_PENDING_CAP,
    PAIR_PENDING_TTL,
    PROTOCOL_VERSION,
    SUPPORTED_PROTOCOL_VERSIONS,
)
from .tlp import TLPValidator

# --- pending store and rate limiting -----------------------------------------------


@dataclass
class PendingPairing:
    """One pairing attempt, waiting for the user to confirm the code."""

    device_id: str
    code: str
    nonce_hash: str
    name: str | None = None
    model: str | None = None
    expires_at: float = 0.0
    attempts: int = 0
    confirmed_token: str | None = None
    confirmed_ha_device_id: str | None = None

    def expired(self, now: float) -> bool:
        return now >= self.expires_at


class PendingPairings:
    """TTL'd, cap-bounded store of pending pairings (PAIR_PENDING_TTL, PAIR_PENDING_CAP)."""

    def __init__(self, clock: Any = time.monotonic, on_drop: Any = None) -> None:
        self._clock = clock
        self._on_drop = on_drop
        self._entries: dict[str, PendingPairing] = {}

    def sweep(self) -> None:
        """Drop expired entries; also the cap enforcement point.

        ``on_drop`` (set by the hub) runs for every dropped entry: a confirmed
        but unclaimed attempt leaves a live credential behind (ADR 0001 item 4).
        """
        now = self._clock()
        for device_id in [d for d, p in self._entries.items() if p.expired(now)]:
            pending = self._entries.pop(device_id)
            if self._on_drop is not None:
                self._on_drop(pending)

    def get(self, device_id: str) -> PendingPairing | None:
        self.sweep()
        return self._entries.get(device_id)

    def all(self) -> list[PendingPairing]:
        self.sweep()
        return list(self._entries.values())

    def delete(self, device_id: str) -> None:
        self._entries.pop(device_id, None)

    def add(
        self, device_id: str, code: str, nonce_hash: str, name: str | None, model: str | None
    ) -> str:
        """Add or refresh a pending entry.

        Returns "created", "retry" (same nonce again: a device retry), "conflict"
        (a different pairing attempt for the same device is already pending) or
        "cap" (too many pending devices).
        """
        self.sweep()
        if (existing := self._entries.get(device_id)) is not None:
            if existing.nonce_hash == nonce_hash:
                return "retry"
            return "conflict"
        if len(self._entries) >= PAIR_PENDING_CAP:
            return "cap"
        self._entries[device_id] = PendingPairing(
            device_id=device_id,
            code=code,
            nonce_hash=nonce_hash,
            name=name,
            model=model,
            expires_at=self._clock() + PAIR_PENDING_TTL,
        )
        return "created"


class RateLimiter:
    """Per-key sliding window limiter (PAIR_RATE_LIMIT / CLAIM_RATE_LIMIT, ADR 0002).

    HA 2026.2 no longer ships a reusable per-view rate limiter middleware, so this is
    the standard sliding-window implementation, keyed by source IP.
    """

    def __init__(self, limit: int, window: float, clock: Any = time.monotonic) -> None:
        self.limit = limit
        self.window = window
        self._clock = clock
        self._hits: dict[str, deque[float]] = {}

    def check(self, key: str) -> float | None:
        """Allow one request; return seconds to wait, or None when allowed."""
        now = self._clock()
        hits = self._hits.setdefault(key, deque())
        while hits and now - hits[0] >= self.window:
            hits.popleft()
        if len(hits) >= self.limit:
            return self.window - (now - hits[0])
        hits.append(now)
        if len(self._hits) > 1024:  # prune idle keys so the dict cannot grow unbounded
            for k in [k for k, v in self._hits.items() if not v or now - v[-1] >= self.window]:
                del self._hits[k]
        return None


# --- views --------------------------------------------------------------------------


def _hub(request: web.Request) -> Any | None:
    """The live hub runtime, or None while the Tabula entry is not loaded."""
    hass: HomeAssistant = request.app[KEY_HASS]
    return hass.data.get(DOMAIN)


def _not_configured() -> web.Response:
    """Plain 404 when the hub is not loaded.

    The TLP http_error schema has no code for "integration not set up", so this is
    plain HTTP semantics: the pairing endpoint does not exist until Tabula is.
    """
    return web.Response(status=HTTPStatus.NOT_FOUND, text="404: Not Found")


def _http_error(
    tlp: TLPValidator,
    status: HTTPStatus,
    code: str,
    message: str | None = None,
    retry_after: int | None = None,
) -> web.Response:
    body: dict[str, Any] = {"error": code}
    if message is not None:
        body["message"] = message[:500]
    if retry_after is not None:
        body["retry_after"] = max(1, retry_after)
    tlp.validate("http_error", body)
    return web.json_response(body, status=status)


async def _read_body(request: web.Request) -> dict[str, Any] | None:
    """Read a JSON object of at most PAIR_MAX_BODY bytes (oversized → 400).

    ``content.read(n)`` returns whatever has arrived so far, not the whole
    body, so read in a loop until EOF and stop once the limit is exceeded.
    """
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await request.content.read(8192)
        if not chunk:
            break
        total += len(chunk)
        if total > PAIR_MAX_BODY:
            return None
        chunks.append(chunk)
    raw = b"".join(chunks)
    try:
        body = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        return None
    return body if isinstance(body, dict) else None


class TabulaPairView(HomeAssistantView):
    """POST /api/tabula/pair: start or retry a pairing (PROTOCOL.md 2.2)."""

    url = "/api/tabula/pair"
    name = "api:tabula:pair"
    requires_auth = False

    async def post(self, request: web.Request) -> web.Response:
        hub = _hub(request)
        if hub is None:
            return _not_configured()
        tlp = hub.tlp
        ip = request.remote or "unknown"
        if (wait := hub.pair_limiter.check(ip)) is not None:
            return _http_error(
                tlp,
                HTTPStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many pairing requests",
                math.ceil(wait),
            )
        if (body := await _read_body(request)) is None:
            return _http_error(
                tlp,
                HTTPStatus.BAD_REQUEST,
                "invalid_request",
                "Expected a JSON object of at most 4096 bytes",
            )
        # The protocol version is read before the rest of the body is validated
        # (PROTOCOL.md 2.2): a newer major gets unsupported_protocol, not invalid_request.
        version = body.get("protocol_version")
        if isinstance(version, int) and version not in SUPPORTED_PROTOCOL_VERSIONS:
            return _http_error(
                tlp,
                HTTPStatus.BAD_REQUEST,
                "unsupported_protocol",
                f"Protocol {version} not supported; this integration supports {PROTOCOL_VERSION}",
            )
        if problems := tlp.problems("http_pair_request", body):
            return _http_error(tlp, HTTPStatus.BAD_REQUEST, "invalid_request", problems[0][:500])
        status = hub.pending.add(
            body["device_id"], body["code"], body["nonce_hash"], body.get("name"), body.get("model")
        )
        if status == "conflict":
            return _http_error(
                tlp,
                HTTPStatus.CONFLICT,
                "already_pending",
                "A pairing attempt for this device is pending",
                retry_after=int(PAIR_PENDING_TTL),
            )
        if status == "cap":
            return _http_error(
                tlp,
                HTTPStatus.SERVICE_UNAVAILABLE,
                "too_many_pending",
                "Too many devices are pairing right now",
                retry_after=int(PAIR_PENDING_TTL),
            )
        if status == "created":
            hub.start_discovery_flow(body)
        response = {
            "status": "pending",
            "expires_in": int(PAIR_PENDING_TTL),
            "poll_interval": int(CLAIM_POLL_INTERVAL),
        }
        tlp.validate("http_pair_response", response)
        return web.json_response(response, status=HTTPStatus.ACCEPTED)


class TabulaClaimView(HomeAssistantView):
    """POST /api/tabula/pair/claim: poll until the user confirms (PROTOCOL.md 2.3)."""

    url = "/api/tabula/pair/claim"
    name = "api:tabula:claim"
    requires_auth = False

    async def post(self, request: web.Request) -> web.Response:
        hub = _hub(request)
        if hub is None:
            return _not_configured()
        tlp = hub.tlp
        ip = request.remote or "unknown"
        if (wait := hub.claim_limiter.check(ip)) is not None:
            return _http_error(
                tlp,
                HTTPStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many claim requests",
                math.ceil(wait),
            )
        if (body := await _read_body(request)) is None:
            return _http_error(
                tlp,
                HTTPStatus.BAD_REQUEST,
                "invalid_request",
                "Expected a JSON object of at most 4096 bytes",
            )
        if problems := tlp.problems("http_claim_request", body):
            return _http_error(tlp, HTTPStatus.BAD_REQUEST, "invalid_request", problems[0][:500])
        pending = hub.pending.get(body["device_id"])
        if pending is None:
            # Unknown, expired, consumed or burnt-out: terminal, tell the device to re-pair
            # (ADR 0001 item 1 consequences).
            return _http_error(
                tlp, HTTPStatus.NOT_FOUND, "expired", "No pending pairing for this device"
            )
        digest = hashlib.sha256(body["nonce"].encode("ascii")).hexdigest()
        if not secrets.compare_digest(digest, pending.nonce_hash):
            return _http_error(
                tlp,
                HTTPStatus.FORBIDDEN,
                "bad_nonce",
                "The nonce does not match this pairing attempt",
            )
        if pending.confirmed_token is None:
            response = {"status": "pending"}
            tlp.validate("http_claim_pending", response)
            return web.json_response(response, status=HTTPStatus.ACCEPTED)
        # One-shot: the entry is consumed by the claim (PROTOCOL.md 2.3).
        hub.pending.delete(body["device_id"])
        response = {
            "status": "paired",
            "credential": {
                "type": "refresh_token",
                "refresh_token": pending.confirmed_token,
                "token_url": "/auth/token",
            },
            "ha_device_id": pending.confirmed_ha_device_id,
        }
        tlp.validate("http_claim_response", response)
        return web.json_response(response, status=HTTPStatus.OK)


@callback
def register_views(hass: HomeAssistant) -> None:
    """Register the unauthenticated pair and claim views once per HA instance.

    Called from the component's async_setup, before HA starts its HTTP server
    (aiohttp freezes the router once it is running). The views find the live hub
    through hass.data on every request, so they survive hub reloads.
    """
    hass.http.register_view(TabulaPairView())
    hass.http.register_view(TabulaClaimView())
