"""Tabula: a Home Assistant hub that wall-panel tablets pair to over TLP.

The hub is one config entry found by unique_id (ADR 0001 item 1). Paired tablets are
HA devices under it, each with an integration-created system user and system refresh
token (ADR 0001 item 2). This module owns the runtime: device records, session
bookkeeping, view and listener registration, and the credential lifecycle - revoke
tokens, then the user (ADR 0001 item 3), sweep orphans at setup (item 4).
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections import deque
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.config_entries import SOURCE_INTEGRATION_DISCOVERY, ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_call_later
from homeassistant.helpers.typing import ConfigType
from homeassistant.util.ulid import ulid_now

from . import pairing, panel
from .const import (
    CLAIM_RATE_LIMIT,
    COMMAND_CAPABILITIES,
    COMMAND_TIMEOUT,
    DOMAIN,
    FRAME_MAX_BYTES,
    LOCAL_ONLY,
    LOG_BUFFER_LINES,
    PAIR_PENDING_TTL,
    PAIR_RATE_LIMIT,
    PAIR_RATE_WINDOW,
    PROTOCOL_VERSION,
    SCREENSHOT_TIMEOUT,
    SIGNAL_CAPABILITIES,
    SIGNAL_DEVICE_UPDATE,
    SIGNAL_SCREENSHOT,
    USER_NAME_PREFIX,
)
from .exceptions import DeviceCommandError
from .intents import IntercomVoiceIntents
from .intercom import IntercomManager
from .pairing import (
    PendingPairing,
    PendingPairings,
    RateLimiter,
)
from .profiles import ProfileStore
from .screenshots import ScreenshotStore
from .settings import SettingsStore
from .tlp import TLPValidator

_LOGGER = logging.getLogger(__name__)

# Phase 1 platforms (plan 5.5); entities are created from declared capabilities.
PLATFORMS: list[Platform] = [
    Platform.ASSIST_SATELLITE,
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.EVENT,
    Platform.IMAGE,
    Platform.LIGHT,
    Platform.MEDIA_PLAYER,
    Platform.NOTIFY,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
    Platform.TEXT,
]
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


@callback
def _close_device_socket(connection: Any) -> None:
    """Force-close a live device WebSocket (websocket_api's own cancel handle).

    websocket_api keeps one cancel handle per connection and uses it when auth
    revocation cuts a socket. From HA 2026.11 the handle is stored on the
    connection; in 2025.1-2026.10 the connection's send_message callable is
    bound to websocket_api's WebSocketHandler (http.py), whose `_cancel`
    cancels both of its tasks and closes the socket (ADR 0001 item 8,
    ADR 0019).
    """
    cancel = getattr(connection, "_cancel_ws", None)
    if cancel is None:
        owner = getattr(getattr(connection, "send_message", None), "__self__", None)
        cancel = getattr(owner, "_cancel_ws", None)
        if cancel is None:
            cancel = getattr(owner, "_cancel", None)
    if cancel is not None:
        cancel()
    else:
        # If a future HA moves both handles, #97 comes back silently; keep
        # the regression visible in the log.
        _LOGGER.warning(
            "No cancel handle on a device connection; its socket may stay open after unload (#97)"
        )


@dataclass
class DeviceSession:
    """Per-WebSocket-connection state (PROTOCOL.md 4.9)."""

    connection: Any
    device: DeviceRecord | None = None
    registered: bool = False
    superseded: bool = False
    subscription_id: int | None = None
    # The settings revision the device reported at registration (PROTOCOL.md
    # 4.2); compared against HA's stored revision when the subscription opens.
    reported_settings_revision: int | None = None


@dataclass
class PendingCommand:
    """A command sent on one connection, waiting for its tabula/result (4.6)."""

    session: DeviceSession
    command: str
    future: asyncio.Future


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
    schema_version: int | None = None
    capabilities: list[str] = field(default_factory=list)
    state: dict[str, Any] = field(default_factory=dict)
    events: deque = field(default_factory=lambda: deque(maxlen=50))
    # P5.4: the same bound the device holds (LOG_BUFFER_LINES, ADR 0002), newest
    # last, in arrival order. Memory only: it is never written to HA's log.
    logs: deque = field(default_factory=lambda: deque(maxlen=LOG_BUFFER_LINES))
    current: DeviceSession | None = None
    # P3.13: when the last button-started capture completed (RFC 3339),
    # persisted so the timestamp sensor survives an HA restart (F-HA-12).
    last_capture: str | None = None
    # P5.2: when a registered session last ended (RFC 3339), persisted so an
    # offline device shows when it was last connected across an HA restart.
    # Not a state key and not a setting: the device never reports it.
    last_seen: str | None = None
    # P5.6: the settings revision the device last reported, kept when its
    # session ends so an offline member can still be compared with HA's copy
    # (ADR 0035 item 7: drift is revision-based, and an offline device has no
    # session to ask). Persisted like last_seen.
    last_settings_revision: int | None = None

    @property
    def connected(self) -> bool:
        """A registered, current connection exists (PROTOCOL.md 4.9)."""
        return self.current is not None

    def update_from_register(self, frame: dict[str, Any], known: frozenset[str]) -> None:
        """Adopt the register frame's identity, state (replaced) and capabilities."""
        self.manufacturer = frame.get("manufacturer")
        self.model = frame.get("model")
        self.os_version = frame.get("os_version")
        self.app_version = frame.get("app_version")
        self.protocol_version = frame.get("protocol_version")
        self.schema_version = frame.get("schema_version")
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
            # Kept so entities exist (unavailable) after an HA restart, before
            # the tablet reconnects; nothing is removed on disconnect.
            "capabilities": list(self.capabilities),
            # Kept for the same reason: settings entities are gated by the
            # settings schema the device speaks (P3.2, ADR 0015).
            "schema_version": self.schema_version,
            # The last capture started by the capture button (F-HA-12).
            "last_capture": self.last_capture,
            # When the device was last connected (P5.2); the Tabularium shows
            # it for an offline device.
            "last_seen": self.last_seen,
            # The settings revision it last reported (P5.6): drift needs it
            # while the device is offline.
            "last_settings_revision": self.last_settings_revision,
        }


class TabulaHub:
    """Runtime state for the hub entry; one instance while the entry is loaded."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.tlp = TLPValidator()
        self.devices: dict[str, DeviceRecord] = {}
        self.by_user: dict[str, str] = {}
        # Intercom calls, the signaling relay and the call timers (P9.2).
        self.intercom = IntercomManager(self)
        # Voice-started calls: the intents, their gate and their sentences (P9.6).
        self.voice = IntercomVoiceIntents(self)
        # Per-device settings documents and revisions (settings.py, plan 5.6).
        self.settings = SettingsStore(hass)
        # Named profiles, assignments and per-device overrides (P5.6, ADR 0035).
        self.profiles = ProfileStore(self)
        # The panel's log streams and the device subscriptions behind them (P5.4).
        # Imported here: logs.py reads this module's DeviceRecord and TabulaHub.
        from .logs import LogStreams

        self.logs = LogStreams(self)
        # The latest screenshot per device, outside HA's media dir (P3.13, PROTOCOL 5).
        self.screenshots = ScreenshotStore(hass)
        # Strong refs, keyed by the connection object; entries are removed when the
        # subscription's cleanup runs (socket close) and when a newer registration
        # replaces an unsubscribed session. ActiveConnection has __slots__ and no
        # __weakref__, so a WeakKeyDictionary is not possible here.
        self.sessions: dict[Any, DeviceSession] = {}
        self.pending = PendingPairings(on_drop=self._drop_pending)
        self.pair_limiter = RateLimiter(PAIR_RATE_LIMIT, PAIR_RATE_WINDOW)
        self.claim_limiter = RateLimiter(CLAIM_RATE_LIMIT, PAIR_RATE_WINDOW)
        # (device_id, command_id) -> the command waiting for its result. A result
        # settles it only when it arrives on the connection the command went out on.
        self.result_futures: dict[tuple[str, str], PendingCommand] = {}
        # Screenshot command ids whose upload landed (upload view -> async_capture,
        # P3.13). Per command, not per device, so overlapping captures cannot
        # read each other's uploads (review).
        self._screenshot_uploads: set[str] = set()
        self._unsub: list[Any] = []
        # device_id -> cancel callback of the pending entry's expiry timer.
        self._pending_timers: dict[str, Any] = {}

    def note_screenshot_upload(self, command_id: str) -> None:
        """An upload landed for a still-pending screenshot command (view)."""
        self._screenshot_uploads.add(command_id)

    def screenshot_uploaded(self, command_id: str) -> bool:
        """Whether that capture's upload landed (consumed by async_capture)."""
        return command_id in self._screenshot_uploads

    def _consume_screenshot_upload(self, command_id: str) -> bool:
        """Read and drop the marker once its capture settles (bounded set)."""
        delivered = command_id in self._screenshot_uploads
        self._screenshot_uploads.discard(command_id)
        return delivered

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

    def record_for_ha_device(self, ha_device_id: str | None) -> DeviceRecord | None:
        """The paired tablet behind an HA device registry id (services, intercom)."""
        if not ha_device_id:
            return None
        return next((r for r in self.devices.values() if r.ha_device_id == ha_device_id), None)

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
                capabilities=list(info.get("capabilities", [])),
                schema_version=info.get("schema_version"),
                last_capture=info.get("last_capture"),
                last_seen=info.get("last_seen"),
                last_settings_revision=info.get("last_settings_revision"),
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
        await self.settings.async_remove(record.device_id)
        await self.profiles.async_remove_device(record.device_id)
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
    def _drop_pending(self, pending: PendingPairing) -> None:
        """The TTL sweep expired a pairing attempt (ADR 0001 item 4).

        A confirmed attempt that was never claimed leaves a live refresh token
        nobody holds: schedule revocation of its user and HA device.
        """
        if pending.confirmed_token is None:
            return
        self.hass.async_create_task(self.revoke_unclaimed(pending))

    async def revoke_unclaimed(self, pending: PendingPairing) -> None:
        """A confirmed-but-unclaimed attempt must leave nothing behind.

        Idempotent, and it only touches the record this attempt created: a
        later successful pairing of the same device has another token.
        """
        record = self.devices.get(pending.device_id)
        token = (
            self.hass.auth.async_get_refresh_token_by_token(pending.confirmed_token)
            if pending.confirmed_token is not None
            else None
        )
        if record is None or token is None or record.refresh_token_id != token.id:
            return
        # Revoking first pops the record, so the device-registry remove event
        # below finds no record and does not revoke a second time.
        await self.revoke_device(record)
        registry = dr.async_get(self.hass)
        if registry.async_get(record.ha_device_id) is not None:
            registry.async_remove_device(record.ha_device_id)

    @callback
    def schedule_pending_expiry(self, device_id: str) -> None:
        """Expire a new pending entry on time, even if nothing touches the store.

        The store otherwise only sweeps on access, so a tablet that goes dark
        after the user confirmed would keep its unclaimed credential until the
        next pairing activity.
        """
        entry = self.pending.get(device_id)
        if entry is None:
            return
        if (cancel := self._pending_timers.pop(device_id, None)) is not None:
            cancel()

        @callback
        def _expire(_now: Any) -> None:
            self._pending_timers.pop(device_id, None)
            self.pending.expire(entry)

        self._pending_timers[device_id] = async_call_later(self.hass, PAIR_PENDING_TTL, _expire)

    @callback
    def _persist(self) -> None:
        data = {"devices": {r.device_id: r.to_storage() for r in self.devices.values()}}
        if data != self.entry.data:
            self.hass.config_entries.async_update_entry(self.entry, data=data)

    # --- registration and session bookkeeping -------------------------------

    @callback
    def start_discovery_flow(self, pair_request: dict[str, Any]) -> None:
        """A pending pairing gets an integration_discovery flow (ADR 0001 item 1).

        Reached only for a freshly created entry, so a flow still in progress
        for this device belongs to an expired attempt: it would answer the new
        code with "expired" and block re-pairing for the whole TTL. Abort it.
        """
        device_id = pair_request["device_id"]
        for flow in self.hass.config_entries.flow.async_progress_by_handler(DOMAIN):
            if flow["context"].get("device_id") == device_id:
                self.hass.config_entries.flow.async_abort(flow["flow_id"])
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
        session.reported_settings_revision = frame["settings_revision"]
        record.last_settings_revision = frame["settings_revision"]
        # Superseded sessions stay in place: every frame they send answers
        # "superseded" until the socket closes (PROTOCOL.md 4.9). The close
        # itself runs the cleanup registered under the register's msg id, so
        # register-only sockets do not leak either.
        connection.subscriptions[frame["id"]] = self._session_cleanup(session)
        ir.async_delete_issue(
            self.hass, DOMAIN, UNSUPPORTED_PROTOCOL_ISSUE.format(record.device_id)
        )
        previous = (set(record.capabilities), record.schema_version)
        record.update_from_register(frame, self.tlp.known_capabilities)
        # A device that re-registers mid-call does not resume it: the call ends
        # for the other side, and a stale non-idle state is cleared once this
        # connection subscribes (ADR 0028 guard 4, P9.2).
        self.intercom.device_rejoined(record)
        if session.subscription_id is not None:
            # A re-register on an open subscription can take the deferred
            # intercom_end now; waiting for another subscribe would leave the
            # reset queued for the life of the hub (P9.2 review).
            self.hass.async_create_task(self.intercom.async_after_subscribe(record))
        self._update_device_registry(record)
        if (set(record.capabilities), record.schema_version) != previous:
            self._persist()
        # Every registration syncs entities (idempotent): a capability gained
        # or dropped, a re-paired device, or a first register with no
        # capabilities, which still gets its always-present entities.
        async_dispatcher_send(self.hass, SIGNAL_CAPABILITIES, record)
        self.notify_update(record)
        result = {
            "ha_device_id": record.ha_device_id,
            "protocol_version": PROTOCOL_VERSION,
            "settings_revision": self.settings.revision(record.device_id),
            "server_time": _utc_now(),
            "capabilities": record.capabilities,
        }
        self.tlp.validate(
            "register_result",
            {"id": frame["id"], "type": "result", "success": True, "result": result},
        )
        return result

    @callback
    def apply_identity(self, record: DeviceRecord) -> None:
        """identity.name in the stored settings is the device's display name
        (F-MGT-03, set at pairing or from the settings editor)."""
        stored = self.settings.get(record.device_id)
        name = (stored.settings.get("identity") or {}).get("name") if stored else None
        if not name or name == record.name:
            return
        record.name = name
        registry = dr.async_get(self.hass)
        entry = registry.async_get(record.ha_device_id)
        if entry is not None and entry.name != name:
            registry.async_update_device(entry.id, name=name)
        self._persist()

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
                # P5.2: a registered session ended here, so this is when the
                # device was last connected. Persisted with the record: an
                # offline device shows it across an HA restart (F-MGT-06 leaves
                # all other health to diagnostics).
                record.last_seen = _utc_now()
                if session.reported_settings_revision is not None:
                    record.last_settings_revision = session.reported_settings_revision
                self._persist()
                # A device that drops out of a call ends it for the other side
                # instead of leaving someone talking to nobody (P9.2, guard 4).
                self.intercom.device_offline(record.device_id)
                # No log subscription survives a connection (PROTOCOL 4.8, P5.4).
                self.logs.device_offline(record)
                self.notify_update(record)
            self.sessions.pop(session.connection, None)
            session.subscription_id = None
            # Nothing can answer on a closed socket: fail its commands now
            # rather than at their timeout.
            for pending in self.result_futures.values():
                if pending.session is session and not pending.future.done():
                    pending.future.set_exception(
                        _command_error("command_disconnected", pending.command)
                    )

        return _cleanup

    @callback
    def notify_update(self, record: DeviceRecord, keys: set[str] | None = None) -> None:
        """Tell the device's entities to refresh.

        ``keys`` names the state keys that changed; None means availability or
        the whole state changed. Each entity writes its state at most once per
        call, so one state frame is one coalesced update per entity.
        """
        async_dispatcher_send(self.hass, SIGNAL_DEVICE_UPDATE.format(record.device_id), keys)

    @callback
    def apply_state(self, record: DeviceRecord, delta: dict[str, Any]) -> None:
        """Merge a state delta and push it to the affected entities."""
        record.state.update(delta)
        self.notify_update(record, set(delta))

    @callback
    def subscribe(self, session: DeviceSession, msg_id: int) -> None:
        """Record the subscription; its cleanup clears session state on socket close."""
        session.subscription_id = msg_id
        session.connection.subscriptions[msg_id] = self._session_cleanup(session)

    # --- commands (PROTOCOL.md 4.5, 4.6) ------------------------------------

    @callback
    def settle(self, session: DeviceSession, frame: dict[str, Any]) -> None:
        """A tabula/result for a pending command on this connection settles it.

        Unknown, already answered, or other-connection results are ignored (4.6).
        """
        assert session.device is not None
        pending = self.result_futures.get((session.device.device_id, frame["command_id"]))
        if pending is None or pending.session is not session or pending.future.done():
            return
        pending.future.set_result(frame)

    async def async_command(
        self,
        record: DeviceRecord,
        command: str,
        args: dict[str, Any],
        command_id: str | None = None,
    ) -> dict[str, Any]:
        """Send one command and wait for its result; returns the result's data.

        Raises HomeAssistantError when the device is offline, did not declare the
        command's capability, answers with an error (NACK), or does not answer
        within COMMAND_TIMEOUT (SCREENSHOT_TIMEOUT for screenshot).

        ``command_id`` lets the caller own the id (the capture flow keys its
        upload marker by it); a fresh one is minted when it is None.
        """
        caps = COMMAND_CAPABILITIES[command]
        if caps and not caps & set(record.capabilities):
            raise _command_error("command_unsupported", command)
        session = record.current
        if session is None or session.subscription_id is None:
            raise _command_error("command_offline", command)
        command_id = command_id or ulid_now()
        event = {"command_id": command_id, "command": command, "args": args}
        frame = {"id": session.subscription_id, "type": "event", "event": event}
        # Strict sender (PROTOCOL.md 4.0): a frame we would send invalid is a bug
        # in the caller's arguments, reported as such.
        if problems := self.tlp.problems(f"command_{command}", frame, lenient=False):
            # Name only the failing member: the schema message quotes the
            # value, which may hold credentials (a URL with userinfo).
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="command_invalid",
                translation_placeholders={
                    "command": command,
                    "problem": problems[0].split(":", 1)[0][:200],
                },
            )
        # The size cap binds senders too (PROTOCOL.md 4, "Size").
        if len(json.dumps(frame).encode()) > FRAME_MAX_BYTES:
            raise _command_error("command_too_large", command)
        key = (record.device_id, command_id)
        future: asyncio.Future = self.hass.loop.create_future()
        self.result_futures[key] = PendingCommand(session, command, future)
        timeout = SCREENSHOT_TIMEOUT if command == "screenshot" else COMMAND_TIMEOUT
        try:
            session.connection.send_event(session.subscription_id, event)
            async with asyncio.timeout(timeout):
                result = await future
        except TimeoutError:
            raise _command_error("command_timeout", command) from None
        finally:
            self.result_futures.pop(key, None)
        if not result["ok"]:
            error = result["error"]
            raise DeviceCommandError(
                code=str(error.get("code")),
                translation_domain=DOMAIN,
                translation_key="command_failed",
                translation_placeholders={
                    "command": command,
                    "code": str(error.get("code")),
                    "message": str(error.get("message", ""))[:200],
                },
            )
        return result.get("data") or {}

    async def async_capture(self, record: DeviceRecord) -> None:
        """Run one screenshot command and record what it captured (P3.13).

        The capture button calls this (F-HA-12). ``started`` is when the
        capture began; it becomes the record's ``last_capture`` - persisted,
        pushed to the timestamp sensor, and untouched by any other path - only
        when the command succeeds, so a failed capture does not move it. The
        upload view's marker tells screen-on from screen-off, keyed by THIS
        capture's command id so overlapping captures cannot read each other's
        uploads (review): no upload for this command means the screen was off,
        the stored image is dropped and the image entity serves the placeholder
        instead (F-MGT-07, ADR 0014).
        """
        command_id = ulid_now()
        started = _utc_now()
        try:
            await self.async_command(record, "screenshot", {}, command_id=command_id)
            delivered = self._consume_screenshot_upload(command_id)
        finally:
            # A failed, timed-out or NACKed capture must not leak its marker.
            self._screenshot_uploads.discard(command_id)
        record.last_capture = started
        self._persist()
        if not delivered:
            await self.screenshots.remove(record.device_id)
        self.notify_update(record, {"last_capture"})
        async_dispatcher_send(self.hass, SIGNAL_SCREENSHOT.format(record.device_id))

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
        """Close every device socket (#97, ADR 0019), then drop listeners and timers."""
        self.intercom.shutdown()
        self.voice.shutdown()
        self.logs.shutdown()
        for session in list(self.sessions.values()):
            _close_device_socket(session.connection)
        for unsub in self._unsub:
            unsub()
        self._unsub.clear()
        for cancel in self._pending_timers.values():
            cancel()
        self._pending_timers.clear()


def _command_error(key: str, command: str) -> DeviceCommandError:
    return DeviceCommandError(
        translation_domain=DOMAIN,
        translation_key=key,
        translation_placeholders={"command": command},
    )


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the tabula/* WebSocket commands, tabula.* services and HTTP views once.

    aiohttp freezes the router once HA's HTTP server is running, so views are
    registered here at component setup; they find the live hub per request and
    survive hub reloads (pairing.register_views, screenshots.register_views).
    """
    from . import panel_api, screenshots, services, ws_api

    ws_api.async_register(hass)
    panel_api.async_register(hass)
    services.async_register(hass)
    pairing.register_views(hass)
    screenshots.register_views(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up the hub runtime for this (the only) Tabula entry."""
    hub = TabulaHub(hass, entry)
    hass.data[DOMAIN] = hub
    hub.load_records()
    await hub.settings.async_load()
    await hub.profiles.async_load()
    await hub.sweep_orphans()
    hub.listen_device_registry()
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    # The intercom intents follow the tablets' capabilities, and the sentences
    # they need are written before anything can ask for them (P9.6).
    await hub.voice.async_setup()
    # The Tabularium is a view over the hub, so it exists while the hub does
    # (P5.1); a missing bundle logs and leaves the hub usable.
    await panel.async_register_panel(hass)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload the hub: every device socket is closed, so the devices reconnect
    through their backoff (#97, ADR 0019); views and listeners go away;
    credentials stay for a reload."""
    if not await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        return False
    hub = hass.data.get(DOMAIN)
    if hub is not None:
        # Drop the intents and their sentence file before the hub goes (P9.6):
        # a removed hub must not leave "call the kitchen" matching nothing.
        await hub.voice.async_shutdown()
    panel.async_remove_panel(hass)
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
