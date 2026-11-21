"""Pairing and claim (docs/PROTOCOL.md section 2) and token refresh (section 3)."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import secrets
import time
from dataclasses import dataclass

import aiohttp

from mockdevice.codec import Codec, FrameError
from mockdevice.constants import (
    CLAIM_POLL_INTERVAL,
    NONCE_BYTES,
    PAIR_CODE_DIGITS,
    PROTOCOL_VERSION,
    TOKEN_REFRESH_MARGIN,
)


class PairingError(Exception):
    """Pairing failed; `code` is the http_error code or a local reason."""

    def __init__(self, code: str, message: str = "") -> None:
        self.code = code
        super().__init__(f"{code}: {message}" if message else code)


class CredentialRevoked(Exception):
    """/auth/token answered invalid_grant or 403: stop and pair again."""


def new_nonce() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(NONCE_BYTES)).rstrip(b"=").decode()


def new_code() -> str:
    return "".join(secrets.choice("0123456789") for _ in range(PAIR_CODE_DIGITS))


def nonce_hash(nonce: str) -> str:
    return hashlib.sha256(nonce.encode("ascii")).hexdigest()


@dataclass
class PairingAttempt:
    device_id: str
    code: str
    nonce: str


async def _json(resp: aiohttp.ClientResponse) -> dict:
    try:
        return await resp.json(content_type=None)
    except ValueError:
        return {}


async def request_pairing(
    session: aiohttp.ClientSession,
    codec: Codec,
    ha_url: str,
    device_id: str,
    name: str | None = None,
    model: str | None = None,
) -> tuple[PairingAttempt, dict]:
    attempt = PairingAttempt(device_id=device_id, code=new_code(), nonce=new_nonce())
    body = {
        "device_id": device_id,
        "code": attempt.code,
        "nonce_hash": nonce_hash(attempt.nonce),
        "protocol_version": PROTOCOL_VERSION,
    }
    if name:
        body["name"] = name
    if model:
        body["model"] = model
    codec.validate("http_pair_request", body)
    async with session.post(f"{ha_url}/api/tabula/pair", json=body) as resp:
        data = await _json(resp)
        if resp.status == 202:
            return attempt, codec.validate("http_pair_response", data, lenient=True)
        _raise_http_error(codec, resp.status, data)
    raise AssertionError("unreachable")


def _raise_http_error(codec: Codec, status: int, data: dict) -> None:
    try:
        codec.validate("http_error", data, lenient=True)
        raise PairingError(data["error"], data.get("message", f"HTTP {status}"))
    except FrameError as e:
        raise PairingError("protocol_error", f"HTTP {status}: {e}") from e


async def claim(
    session: aiohttp.ClientSession,
    codec: Codec,
    ha_url: str,
    attempt: PairingAttempt,
    poll_interval: float = CLAIM_POLL_INTERVAL,
    timeout: float | None = None,
) -> dict:
    """Poll the claim view until paired; returns the http_claim_response frame."""
    body = codec.validate(
        "http_claim_request", {"device_id": attempt.device_id, "nonce": attempt.nonce}
    )
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        async with session.post(f"{ha_url}/api/tabula/pair/claim", json=body) as resp:
            data = await _json(resp)
            if resp.status == 200:
                return codec.validate("http_claim_response", data, lenient=True)
            if resp.status == 202:
                codec.validate("http_claim_pending", data, lenient=True)
            else:
                _raise_http_error(codec, resp.status, data)
        if deadline is not None and time.monotonic() >= deadline:
            raise PairingError("timeout", "user did not confirm in time")
        await asyncio.sleep(poll_interval)


@dataclass
class AccessToken:
    token: str
    expires_at: float

    def fresh(self) -> bool:
        return time.monotonic() < self.expires_at - TOKEN_REFRESH_MARGIN


async def refresh_access_token(
    session: aiohttp.ClientSession, ha_url: str, refresh_token: str
) -> AccessToken:
    """Trade the refresh token for an access token (no client_id; ADR 0001)."""
    form = {"grant_type": "refresh_token", "refresh_token": refresh_token}
    async with session.post(f"{ha_url}/auth/token", data=form) as resp:
        data = await _json(resp)
        if resp.status == 200:
            return AccessToken(data["access_token"], time.monotonic() + data["expires_in"])
        if resp.status == 403 or data.get("error") == "invalid_grant":
            raise CredentialRevoked(data.get("error", str(resp.status)))
        raise ConnectionError(f"/auth/token: HTTP {resp.status} {data}")
