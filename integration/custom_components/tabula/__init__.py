"""Tabula: a Home Assistant hub that wall-panel tablets pair to over TLP.

The hub is one config entry found by unique_id (ADR 0001 item 1). Paired tablets are
HA devices under it, each with an integration-created system user and system refresh
token (ADR 0001 item 2). This module owns the runtime: device records, session
bookkeeping, view and listener registration, and the credential lifecycle - revoke
tokens, then the user (ADR 0001 item 3), sweep orphans at setup (item 4).
"""

from __future__ import annotations

import asyncio
import logging
from collections import deque
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.config_entries import SOURCE_INTEGRATION_DISCOVERY, ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers.typing import ConfigType

from . import pairing
from .const import (
    CLAIM_RATE_LIMIT,
    DOMAIN,
    LOCAL_ONLY,
    PAIR_RATE_LIMIT,
    PAIR_RATE_WINDOW,
    PROTOCOL_VERSION,
    USER_NAME_PREFIX,
)
from .pairing import (
    PendingPairings,
    RateLimiter,
)
from .tlp import TLPValidator

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = []  # entities arrive in P1.3
UNSUPPORTED_PROTOCOL_ISSUE = "unsupported_protocol_{}"


def _utc_now() -> str:
    """RFC 3339 UTC second the schema's date-time format (and fixtures) use."""
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


async def revoke_user(hass: HomeAssistant, user_id: str | None) -> None:
    """Tokens first, then the user (ADR 0001 item 3); removing a token closes the
    device's live WebSocket (HA registers a revoke callback per connection)."""
    if not user_id:
        return
    user = await hass.auth.async_get_user(user_id)
    if user is None:
        return
    for token in list(user.refresh_tokens.values()):
        hass.auth.async_remove_refresh_token(token)
    await hass.auth.async_remove_user(user)


@dataclass
class DeviceSession:
    """Per-WebSocket-connection state (PROTOCOL.md 4.9)."""

    connection: Any
    device: DeviceRecord | None = None
    registered: bool = False
    superseded: bool = False
    subscription_id: int | None = None


@dataclass
class DeviceRecord:
    """A paired tablet."""

    device_id: str
    user_id: str
    refresh_token_id: str
    ha_device_id: str
    name: str | None = None
    model: str | None = None
    manufacturer: str | None = None
    os_version: str | None = None
    app_version: str | None = None
    protocol_version: int | None = None
    capabilities: list[str] = field(default_factory=list)
    state: dict[str, Any] = field(default_factory=dict)
    events: deque = field(default_factory=lambda: deque(maxlen=50))
    logs: deque = field(default_factory=lambda: deque(maxlen=200))
    current: DeviceSession | None = None

    def update_from_register(self, frame: dict[str, Any], known: frozenset[str]) -> None:
        """Adopt the register frame's identity, state (replaced) and capabilities."""
        self.manufacturer = frame.get("manufacturer")
        self.model = frame.get("model")
        self.os_version = frame.get("os_version")
        self.app_version = frame.get("app_version")
        self.protocol_version = frame.get("protocol_version")
        self.state = dict(frame["state"])
        self.capabilities = [c for c in frame["capabilities"] if c in known]

    def to_storage(self) -> dict[str, Any]:
        return {
            "device_id": self.device_id,
            "user_id": self.user_id,
            "refresh_token_id": self.refresh_token_id,
            "ha_device_id": self.ha_device_id,
            "name": self.name,
            "model": self.model,
        }


class TabulaHub:
    """Runtime state for the hub entry; one instance while the entry is loaded."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.tlp = TLPValidator()
        self.devices: dict[str, DeviceRecord] = {}
        self.by_user: dict[str, str] = {}
        # Strong refs, keyed by the connection object; entries are removed when the
        # subscription's cleanup runs (socket close) and when a newer registration
        # replaces an unsubscribed session. ActiveConnection has __slots__ and no
        # __weakref__, so a WeakKeyDictionary is not possible here.
        self.sessions: dict[Any, DeviceSession] = {}
        self.pending = PendingPairings()
        self.pair_limiter = RateLimiter(PAIR_RATE_LIMIT, PAIR_RATE_WINDOW)
        self.claim_limiter = RateLimiter(CLAIM_RATE_LIMIT, PAIR_RATE_WINDOW)
        # (device_id, command_id) -> Future; the P1.4 command path resolves these.
        self.result_futures: dict[tuple[str, str], asyncio.Future] = {}
        self._unsub: list[Any] = []

    # --- lookups -----------------------------------------------------------

    @classmethod
    def get(cls, hass: HomeAssistant) -> TabulaHub | None:
        return hass.data.get(DOMAIN)

    def device_for_user(self, user: Any) -> DeviceRecord | None:
        """Connection binding (PROTOCOL.md 4.3): user -> one device, or None."""
        if user is None:
            return None
        device_id = self.by_user.get(user.id)
        return self.devices.get(device_id) if device_id is not None else None

    # --- lifecycle ---------------------------------------------------------

    def load_records(self) -> None:
        """Rebuild device records from entry.data (written by create/revoke)."""
        stored: dict[str, Any] = self.entry.data.get("devices", {})
        for info in stored.values():
            record = DeviceRecord(
                device_id=info["device_id"],
                user_id=info["user_id"],
                refresh_token_id=info["refresh_token_id"],
                ha_device_id=info["ha_device_id"],
                name=info.get("name"),
                model=info.get("model"),
            )
            self.devices[record.device_id] = record
            self.by_user[record.user_id] = record.device_id

    async def sweep_orphans(self) -> None:
        """Remove stored credentials whose HA device no longer exists (ADR 0001 item 4)."""
        registry = dr.async_get(self.hass)
        for record in list(self.devices.values()):
            if registry.async_get(record.ha_device_id) is not None:
                continue
            _LOGGER.info("Sweeping orphaned credential for device %s", record.device_id)
            await self.revoke_device(record)

    async def create_device(
        self, *, device_id: str, name: str | None, model: str | None
    ) -> tuple[str, str]:
        """Pair a device: system user + system refresh token + HA device (ADR 0001 item 2).

        Returns (refresh_token, ha_device_id). local_only follows the hub option
        (default True: wall tablets sit on the LAN).
        """
        if (old := self.devices.get(device_id)) is not None:
            # A re-pair of a device that still has credentials: revoke them first.
            await self.revoke_device(old)
        local_only = self.entry.options.get(LOCAL_ONLY, True)
        user = await self.hass.auth.async_create_system_user(
            name=f"{USER_NAME_PREFIX} {device_id[:8]}",
            group_ids=[GROUP_ID_USER],
            local_only=local_only,
        )
        token = await self.hass.auth.async_create_refresh_token(user)
        registry = dr.async_get(self.hass)
        device_entry = registry.async_get_or_create(
            config_entry_id=self.entry.entry_id,
            identifiers={(DOMAIN, device_id)},
            name=name or f"{USER_NAME_PREFIX} {device_id[:8]}",
            model=model,
        )
        record = DeviceRecord(
            device_id=device_id,
            user_id=user.id,
            refresh_token_id=token.id,
            ha_device_id=device_entry.id,
            name=name,
            model=model,
        )
        self.devices[device_id] = record
        self.by_user[user.id] = device_id
        self._persist()
        return token.token, device_entry.id

    async def revoke_device(self, record: DeviceRecord) -> None:
        """Tokens first, then the user (ADR 0001 item 3); removing a token closes the
        device's live WebSocket (HA registers a revoke callback per connection)."""
        await self._revoke_user(record.user_id)
        self.devices.pop(record.device_id, None)
        self.by_user.pop(record.user_id, None)
        ir.async_delete_issue(
            self.hass, DOMAIN, UNSUPPORTED_PROTOCOL_ISSUE.format(record.device_id)
        )
        self._persist()

    async def _revoke_user(self, user_id: str) -> None:
        await revoke_user(self.hass, user_id)

    async def revoke_all(self) -> None:
        """async_remove_entry: every device user the entry owned goes away."""
        for record in list(self.devices.values()):
            await self.revoke_device(record)

    @callback
    def _persist(self) -> None:
        data = {"devices": {r.device_id: r.to_storage() for r in self.devices.values()}}
        if data != self.entry.data:
            self.hass.config_entries.async_update_entry(self.entry, data=data)

    # --- registration and session bookkeeping -------------------------------

    @callback
    def start_discovery_flow(self, pair_request: dict[str, Any]) -> None:
        """A pending pairing gets an integration_discovery flow (ADR 0001 item 1)."""
        device_id = pair_request["device_id"]
        for flow in self.hass.config_entries.flow.async_progress_by_handler(DOMAIN):
            if flow["context"].get("device_id") == device_id:
                return  # already waiting for the user
        self.hass.async_create_task(
            self._async_create_flow(
                {
                    "device_id": device_id,
                    "nonce_hash": pair_request["nonce_hash"],
                    "name": pair_request.get("name"),
                    "model": pair_request.get("model"),
                }
            )
        )

    async def _async_create_flow(self, data: dict[str, Any]) -> None:
        from homeassistant.helpers import discovery_flow

        discovery_flow.async_create_flow(
            self.hass,
            DOMAIN,
            {"source": SOURCE_INTEGRATION_DISCOVERY, "device_id": data["device_id"]},
            data,
        )

    @callback
    def mark_unsupported_protocol(self, record: DeviceRecord, version: int) -> None:
        """A repair issue per device until it registers with a supported protocol."""
        ir.async_create_issue(
            self.hass,
            DOMAIN,
            UNSUPPORTED_PROTOCOL_ISSUE.format(record.device_id),
            is_fixable=False,
            severity=ir.IssueSeverity.WARNING,
            translation_key="unsupported_protocol",
            translation_placeholders={
                "device": record.name or record.device_id[:8],
                "protocol_version": str(version),
                "supported": str(PROTOCOL_VERSION),
            },
        )

    @callback
    def accept_registration(
        self, connection: Any, record: DeviceRecord, frame: dict[str, Any]
    ) -> dict[str, Any]:
        """Adopt a register frame; newest connection wins (PROTOCOL.md 4.9)."""
        session = self.sessions.get(connection)
        if session is None:
            session = DeviceSession(connection=connection)
            self.sessions[connection] = session
        session.device = record
        if record.current is not None and record.current is not session:
            record.current.superseded = True
        record.current = session
        session.registered = True
        # Superseded sessions stay in place: every frame they send answers
        # "superseded" until the socket closes (PROTOCOL.md 4.9). The close
        # itself runs the cleanup registered under the register's msg id, so
        # register-only sockets do not leak either.
        connection.subscriptions[frame["id"]] = self._session_cleanup(session)
        ir.async_delete_issue(
            self.hass, DOMAIN, UNSUPPORTED_PROTOCOL_ISSUE.format(record.device_id)
        )
        record.update_from_register(frame, self.tlp.known_capabilities)
        self._update_device_registry(record)
        result = {
            "ha_device_id": record.ha_device_id,
            "protocol_version": PROTOCOL_VERSION,
            "settings_revision": 0,  # HA has no settings store until P1.5
            "server_time": _utc_now(),
            "capabilities": record.capabilities,
        }
        self.tlp.validate(
            "register_result",
            {"id": frame["id"], "type": "result", "success": True, "result": result},
        )
        return result

    @callback
    def _update_device_registry(self, record: DeviceRecord) -> None:
        registry = dr.async_get(self.hass)
        entry = registry.async_get(record.ha_device_id)
        if entry is None:
            return
        registry.async_update_device(
            entry.id,
            manufacturer=record.manufacturer,
            model=record.model or entry.model,
            sw_version=record.app_version,
        )

    @callback
    def _session_cleanup(self, session: DeviceSession) -> Any:
        """Detach a session; HA runs this when the socket closes (msg-id keyed)."""

        def _cleanup() -> None:
            record = session.device
            if record is not None and record.current is session:
                record.current = None
            self.sessions.pop(session.connection, None)
            session.subscription_id = None

        return _cleanup

    @callback
    def subscribe(self, session: DeviceSession, msg_id: int) -> None:
        """Record the subscription; its cleanup clears session state on socket close."""
        session.subscription_id = msg_id
        session.connection.subscriptions[msg_id] = self._session_cleanup(session)

    # --- views and listeners -------------------------------------------------

    @callback
    def listen_device_registry(self) -> None:
        """Removing the HA device removes its credentials (test: delete the device)."""

        @callback
        def _on_device_event(event) -> None:
            if event.data.get("action") != "remove":
                return
            removed = event.data.get("device_id")
            record = next((r for r in self.devices.values() if r.ha_device_id == removed), None)
            if record is not None:
                self.hass.async_create_task(self.revoke_device(record))

        self._unsub.append(
            self.hass.bus.async_listen(dr.EVENT_DEVICE_REGISTRY_UPDATED, _on_device_event)
        )

    @callback
    def teardown(self) -> None:
        for unsub in self._unsub:
            unsub()
        self._unsub.clear()


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the tabula/* WebSocket commands and the pair/claim views once.

    aiohttp freezes the router once HA's HTTP server is running, so views are
    registered here at component setup; they find the live hub per request and
    survive hub reloads (pairing.register_views).
    """
    from . import ws_api

    ws_api.async_register(hass)
    pairing.register_views(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up the hub runtime for this (the only) Tabula entry."""
    hub = TabulaHub(hass, entry)
    hass.data[DOMAIN] = hub
    hub.load_records()
    await hub.sweep_orphans()
    hub.listen_device_registry()
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload the hub: views and listeners go away; credentials stay for a reload."""
    hub = hass.data.pop(DOMAIN, None)
    if hub is not None:
        hub.teardown()
    return True


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Entry removal revokes every device user (ADR 0001 items 3-4)."""
    hub = TabulaHub.get(hass)
    if hub is not None:
        await hub.revoke_all()
        return
    # The hub was not loaded: revoke straight from entry.data.
    for info in entry.data.get("devices", {}).values():
        await revoke_user(hass, info.get("user_id"))
