"""Intercom: the call directory, the signaling relay and the call timers (P9.2).

HA is the directory and the signaling path for intercom (ADR 0025 decisions 4
and 5). Devices find each other here, HA copies offers, answers and candidates
between the two peers of a call without reading them, and HA owns the timers
that end a call. Audio never passes through HA (ADR 0025 decision 3). Only
devices of this hub can call each other, because only this hub's devices are
resolved.

One record per call: the id (chosen by whoever starts it), the caller, the
callees, the mode, the state and when it started. That record is HA's own view;
the `intercom_state` a tablet reports feeds its sensor and never decides whether
a call is alive.

The four production guards of ADR 0028 are enforced here, on the HA side:

1. a call exists only because a service call, an automation or a device's own
   screen asked for one, never because a device is online;
2. every call ends by itself at `INTERCOM_CALL_LIMIT_S`;
3. ending a call reaches every device in it, in every state;
4. a call is never resumed: a device that reconnects or re-registers mid-call
   ends it for the other side, and a stale non-idle state is cleared.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import TYPE_CHECKING, Any

from homeassistant.core import CoreState, callback
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.event import async_call_later
from homeassistant.util import dt as dt_util
from homeassistant.util.ulid import ulid_now

from .const import (
    CAP_CALLER,
    CAP_LISTEN,
    CAP_TALK,
    DOMAIN,
    INTERCOM_BROADCAST_MAX_TARGETS,
    INTERCOM_CALL_LIMIT_S,
    INTERCOM_CONNECT_TIMEOUT_S,
    INTERCOM_RING_LIMIT_S,
)
from .events import (
    INTERCOM_ANSWERED,
    INTERCOM_ENDED,
    INTERCOM_MISSED,
    INTERCOM_STARTED,
    fire_intercom_event,
)
from .exceptions import DeviceCommandError

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant

    from . import DeviceRecord, TabulaHub

_LOGGER = logging.getLogger(__name__)

# The call's own state (not the device's `intercom_state` key, which mirrors it
# per device): a two-way call rings, then connects, then is up; a broadcast is
# one-way from the start and never rings.
STATE_RINGING = "ringing"
STATE_CONNECTING = "connecting"
STATE_IN_CALL = "in_call"
STATE_BROADCASTING = "broadcasting"

MODE_CALL = "call"
MODE_BROADCAST = "broadcast"

# A device takes part in a call if it can hear, and starts a two-way call only
# if it can also speak (PROTOCOL.md 4.5, ADR 0025 decision 7). The capabilities
# themselves live in const.py, where the command gate reads them.

# Timers a call can have running, keyed with the call id.
TIMER_RING = "ring"
TIMER_CONNECT = "connect"
TIMER_LIMIT = "limit"


def _label(record: DeviceRecord) -> str:
    """A device's name for a message, or the short id when it has none."""
    return record.name or record.device_id[:8]


def _invalid(key: str, **placeholders: str) -> ServiceValidationError:
    return ServiceValidationError(
        translation_domain=DOMAIN,
        translation_key=key,
        translation_placeholders=placeholders,
    )


@dataclass
class IntercomCall:
    """One call, from the moment it starts until it ends."""

    call_id: str
    mode: str
    caller: str  # Tabula device id
    callees: list[str]  # Tabula device ids, in the order they were rung
    state: str
    started: datetime
    caller_ha_id: str
    callee_ha_ids: list[str] = field(default_factory=list)
    answered_by: str | None = None
    # False until the caller has been given the call (ADR 0037 decision 5):
    # nothing is relayed to any device before then, and a caller's early
    # signals wait here. The hold ends with the `intercom_start` result.
    ready: bool = False
    pending_signals: list[tuple[DeviceRecord, dict[str, Any]]] = field(default_factory=list)

    @property
    def devices(self) -> list[str]:
        """Every device in the call, the caller first."""
        return [self.caller, *self.callees]


class IntercomManager:
    """Calls of one hub: start them, relay for them, end them, time them out."""

    def __init__(self, hub: TabulaHub) -> None:
        self.hub = hub
        self.hass: HomeAssistant = hub.hass
        # call_id -> the call; only calls that have not ended are here.
        self.calls: dict[str, IntercomCall] = {}
        # (call_id, timer name) -> the cancel handle.
        self._timers: dict[tuple[str, str], Callable[[], None]] = {}
        # device_id -> the call id to end on its next subscription (None when
        # HA never had the call). Guard 4: a restart does not resume a call.
        self._pending_reset: dict[str, str | None] = {}

    # --- lookups -----------------------------------------------------------

    def call_for_device(self, device_id: str) -> IntercomCall | None:
        """The call that device is in, if any."""
        return next((c for c in self.calls.values() if device_id in c.devices), None)

    # --- starting ----------------------------------------------------------

    async def async_start_call(
        self,
        caller: DeviceRecord,
        callees: list[DeviceRecord] | None,
        mode: str,
        *,
        call_id: str | None = None,
    ) -> IntercomCall:
        """Start a call from `caller` to `callees` and ring them.

        `callees` is the resolved target set; None means "every device that can
        hear, except the caller", which only a broadcast may ask for. Targets
        that cannot hear, that are already in a call, or (for a broadcast) that
        share the caller's area are left out; a two-way call to a device in the
        caller's own area is refused, because two speakers and two microphones
        in one room feed back (ADR 0025 consequences).

        Every failure raises: a service call that rang nobody must not look
        like a call. `call_id` comes from the device for a call it started
        from its own screen, and is minted here otherwise (PROTOCOL.md 4.5).
        """
        if mode not in (MODE_CALL, MODE_BROADCAST):
            raise _invalid("intercom_bad_mode", mode=mode)
        # Whose id this is decides whether the caller needs `intercom_start`:
        # the integration mints it, or the device already knows its own call.
        started_by_integration = call_id is None
        self._check_caller(caller, mode, started_by_integration=started_by_integration)
        if self.call_for_device(caller.device_id) is not None:
            raise _invalid("intercom_caller_busy", device=_label(caller))
        targets = self._select_targets(caller, callees, mode)
        call_id = call_id or ulid_now()
        if call_id in self.calls:
            # A device chooses the id of a call it starts; one still in use is
            # refused rather than overwriting a live call's record, which would
            # strand its tablets and its ended event (P9.2 review).
            raise _invalid("intercom_call_id_in_use", call_id=call_id)
        call = IntercomCall(
            call_id=call_id,
            mode=mode,
            caller=caller.device_id,
            callees=[r.device_id for r in targets],
            state=STATE_RINGING if mode == MODE_CALL else STATE_BROADCASTING,
            started=dt_util.utcnow(),
            caller_ha_id=caller.ha_device_id,
            callee_ha_ids=[r.ha_device_id for r in targets],
        )
        self.calls[call_id] = call
        self._start_timer(call, TIMER_LIMIT, INTERCOM_CALL_LIMIT_S, self._on_limit)
        if mode == MODE_CALL:
            self._start_timer(call, TIMER_RING, INTERCOM_RING_LIMIT_S, self._on_ring_limit)
        # The call exists from here on, so an automation always sees a start
        # and exactly one terminal event, even when the ring itself fails.
        fire_intercom_event(self.hass, INTERCOM_STARTED, call)
        args = {
            "call_id": call_id,
            "caller_device_id": caller.device_id,
            "caller_name": _label(caller)[:64],
            "mode": mode,
        }
        results = await asyncio.gather(
            *(self.hub.async_command(target, "intercom_ring", args) for target in targets),
            return_exceptions=True,
        )
        call = await self._settle_rings(call, targets, results)
        await self._async_prepare_caller(call, caller, started_by_integration)
        return call

    async def _settle_rings(
        self, call: IntercomCall, targets: list[DeviceRecord], results: list[Any]
    ) -> IntercomCall:
        """Drop the callees whose ring failed, then end the call if none is left.

        A device that says it is busy is dropped, as PROTOCOL.md 4.5 says: the
        caller's side then ends with reason `busy`. Any other failure ends the
        call for everyone and is raised, so nothing fails silently (F-HA-14).
        """
        busy: list[str] = []
        failure: BaseException | None = None
        for target, result in zip(targets, results, strict=True):
            if not isinstance(result, BaseException):
                continue
            if isinstance(result, DeviceCommandError) and result.code == "busy":
                busy.append(_label(target))
                call.callees.remove(target.device_id)
                call.callee_ha_ids.remove(target.ha_device_id)
                continue
            failure = failure or result
        if not call.callees:
            await self._async_end(call, "busy")
            if failure is not None:
                raise failure
            raise _invalid("intercom_busy", devices=", ".join(busy))
        if failure is not None:
            await self._async_end(call, "error")
            raise failure
        if busy:
            _LOGGER.info("Intercom: %s busy, left out of %s", ", ".join(busy), call.call_id)
        return call

    async def _async_prepare_caller(
        self, call: IntercomCall, caller: DeviceRecord, started_by_integration: bool
    ) -> None:
        """Give the caller its own call: the id and the settled peer list (ADR 0037).

        The frame is needed whenever the integration started the call or the
        mode is a broadcast (`_needs_start_frame`); a call a device started
        that named its one target needs nothing, because that device already
        holds the id. A NACK to it means there is no caller side, so the call
        ends and the failure is raised (decision 9) — never a ringing callee
        with nothing to connect to.
        """
        if self._needs_start_frame(call.mode, started_by_integration):
            peers = [
                {"device_id": device_id, "name": _label(record)[:64]}
                for device_id in call.callees
                if (record := self.hub.devices.get(device_id)) is not None
            ]
            try:
                await self.hub.async_command(
                    caller,
                    "intercom_start",
                    {"call_id": call.call_id, "mode": call.mode, "peers": peers},
                )
            except DeviceCommandError as err:
                busy = err.code == "busy"
                await self._async_end(call, "busy" if busy else "error")
                if busy:
                    raise _invalid("intercom_busy", devices=_label(caller)) from err
                raise _invalid("intercom_start_failed", device=_label(caller)) from err
            except HomeAssistantError as err:
                await self._async_end(call, "error")
                raise _invalid("intercom_start_failed", device=_label(caller)) from err
        await self._async_flush_pending(call)

    @staticmethod
    def _needs_start_frame(mode: str, started_by_integration: bool) -> bool:
        """Whether the caller must be sent `intercom_start` (ADR 0037 decisions 2 and 8).

        It is needed whenever the integration started the call — only that
        frame tells the device the id — or the mode is a broadcast, because a
        device that asked for a room or an area named a target set it cannot
        enumerate. It is not needed only for a call a device started from its
        own screen that named its one target, which that device already holds.
        """
        return started_by_integration or mode == MODE_BROADCAST

    def _check_caller(
        self, caller: DeviceRecord, mode: str, *, started_by_integration: bool
    ) -> None:
        """The caller must be able to take part, to be reached, and to be prepared.

        A caller of any call declares both `intercom_listen` and
        `intercom_talk`: a caller that cannot receive `intercom_signal` has no
        return path for the answer and the candidates (ADR 0037 decision 2), so
        this is a conjunction here and never a second member of the any-of
        gate in `COMMAND_CAPABILITIES`.
        """
        if not {CAP_LISTEN, CAP_TALK} <= set(caller.capabilities):
            raise _invalid("intercom_caller_cannot_call", device=_label(caller))
        if (
            self._needs_start_frame(mode, started_by_integration)
            and CAP_CALLER not in caller.capabilities
        ):
            raise _invalid("intercom_caller_unsupported", device=_label(caller))
        if not caller.connected:
            raise _invalid("intercom_caller_offline", device=_label(caller))

    def _select_targets(
        self,
        caller: DeviceRecord,
        callees: list[DeviceRecord] | None,
        mode: str,
    ) -> list[DeviceRecord]:
        """The devices that will actually be rung (PROTOCOL.md 4.5, ADR 0025)."""
        if callees is None:
            if mode != MODE_BROADCAST:
                raise _invalid("intercom_no_target_devices")
            candidates = [
                r
                for r in self.hub.devices.values()
                if CAP_LISTEN in r.capabilities and r.device_id != caller.device_id
            ]
        else:
            candidates = [r for r in callees if r.device_id != caller.device_id]
        capable = [r for r in candidates if CAP_LISTEN in r.capabilities]
        caller_area = self._area(caller)
        same_area = [r for r in capable if caller_area is not None and self._area(r) == caller_area]
        if same_area and mode == MODE_CALL:
            # Two devices in one room feed back (ADR 0025 consequences, ADR 0028).
            raise _invalid("intercom_same_area", device=_label(same_area[0]))
        free = [
            r for r in capable if r not in same_area and self.call_for_device(r.device_id) is None
        ]
        if not free:
            if capable:
                raise _invalid("intercom_busy", devices=", ".join(_label(r) for r in capable))
            raise _invalid("intercom_no_capable_targets", caller=_label(caller))
        if mode == MODE_BROADCAST and len(free) > INTERCOM_BROADCAST_MAX_TARGETS:
            # A broadcast that quietly lost a receiver is the failure nobody
            # sees, so an oversize one is refused and never trimmed (ADR 0037
            # decision 7).
            raise _invalid(
                "intercom_broadcast_too_many",
                devices=", ".join(_label(r) for r in free),
                max=str(INTERCOM_BROADCAST_MAX_TARGETS),
            )
        if mode == MODE_CALL and len(free) != 1:
            # A two-way call has two peers (ADR 0025 decision 1); a target that
            # resolves to several devices is ambiguous, and rings all of them.
            raise _invalid("intercom_one_callee", devices=", ".join(_label(r) for r in free))
        return free

    def _area(self, record: DeviceRecord) -> str | None:
        """The HA area this tablet is assigned to, if any."""
        entry = dr.async_get(self.hass).async_get(record.ha_device_id)
        return entry.area_id if entry is not None else None

    # --- device events -----------------------------------------------------

    @callback
    def handle_event(self, record: DeviceRecord, event: str, data: dict[str, Any]) -> None:
        """Route one intercom event from a device (PROTOCOL.md 4.7).

        The handlers send commands, so each runs as a task; the raw event is
        still relayed to the bus by `events.relay_event` either way.
        """
        handler = {
            "intercom_answer": self._async_answered,
            "intercom_decline": self._async_declined,
            "intercom_signal": self._async_signal,
            "intercom_ended": self._async_ended_by_device,
            "intercom_start_request": self._async_start_request,
        }.get(event)
        if handler is None:
            return
        self.hass.async_create_task(handler(record, data))

    async def _async_answered(self, record: DeviceRecord, data: dict[str, Any]) -> None:
        """A ringing device answered: the call is up once the peers exchange."""
        call = self.calls.get(data["call_id"])
        if call is None or record.device_id not in call.callees:
            _LOGGER.debug("Intercom: answer for %s from a device not in it", data["call_id"])
            return
        if call.state != STATE_RINGING:
            return
        call.state = STATE_CONNECTING
        call.answered_by = record.device_id
        self._cancel_timer(call, TIMER_RING)
        self._start_timer(call, TIMER_CONNECT, INTERCOM_CONNECT_TIMEOUT_S, self._on_connect_timeout)
        fire_intercom_event(
            self.hass,
            INTERCOM_ANSWERED,
            call,
            answered_by=record.ha_device_id,
        )

    async def _async_declined(self, record: DeviceRecord, data: dict[str, Any]) -> None:
        """A callee declined: the call ends for both sides, saying why."""
        call = self.calls.get(data["call_id"])
        if call is None or record.device_id not in call.callees:
            return
        await self._async_end(call, "decline")

    async def _async_signal(self, record: DeviceRecord, data: dict[str, Any]) -> None:
        """Route one offer, answer or candidate to the one peer that owns it.

        Nothing is relayed to a device before it has the call (ADR 0037
        decision 5): a caller's signal that arrives while the call is still
        being prepared waits on the record and is flushed when it is ready.
        Only the caller can arrive early — a receiver has no answer to send
        before it has the caller's offer.
        """
        call = self.calls.get(data["call_id"])
        if call is None or record.device_id not in call.devices:
            _LOGGER.debug("Intercom: signal for %s from a device not in it", data["call_id"])
            return
        if not call.ready:
            call.pending_signals.append((record, data))
            return
        await self._async_route_signal(call, record, data)

    async def _async_flush_pending(self, call: IntercomCall) -> None:
        """Relay the caller's held signals, in the order they arrived.

        `ready` stays false until the queue is empty, so a signal that arrives
        while a relay is in flight joins the queue behind the ones it must not
        overtake - a candidate can never pass the offer it belongs to (review
        of #177, note 4). The loop cannot run long: the hold is bounded by the
        `COMMAND_TIMEOUT` on the `intercom_start` result.
        """
        while call.pending_signals:
            pending, call.pending_signals = call.pending_signals, []
            for record, data in pending:
                if self.calls.get(call.call_id) is not call:
                    return
                await self._async_route_signal(call, record, data)
        call.ready = True

    async def _async_route_signal(
        self, call: IntercomCall, sender: DeviceRecord, data: dict[str, Any]
    ) -> None:
        """Relay by peer, star-shaped at the caller (ADR 0037 decision 4).

        A caller's signal goes to the one peer it names, or to the other
        device in a two-device call. A callee's signal goes to the caller
        alone, labelled with the sender's own id when the caller declared
        `intercom_caller` and the call has more than two devices — a receiver
        never sends that member, and this is where it comes from.
        """
        if call.state == STATE_CONNECTING:
            # The peers are talking to each other now; the connect timeout is
            # about silence, not about a state the devices report.
            call.state = STATE_IN_CALL
            self._cancel_timer(call, TIMER_CONNECT)
        args = {
            "call_id": call.call_id,
            "kind": data["kind"],
            "payload": data["payload"],
        }
        if sender.device_id == call.caller:
            if len(call.callees) == 1:
                peers = list(call.callees)
            else:
                peer = data.get("peer_device_id")
                if peer is None or peer not in call.callees:
                    # An event has no result to NACK: log it, drop it, and let
                    # the call live (decision 4).
                    _LOGGER.warning(
                        "Intercom: %s's %s for %s names no receiver of that call; dropped",
                        _label(sender),
                        data["kind"],
                        call.call_id,
                    )
                    return
                peers = [peer]
        else:
            peers = [call.caller]
            caller_record = self.hub.devices.get(call.caller)
            if (
                caller_record is not None
                and CAP_CALLER in caller_record.capabilities
                and len(call.devices) > 2
            ):
                args["peer_device_id"] = sender.device_id
        await self._async_relay(call, peers, args)

    async def _async_relay(
        self, call: IntercomCall, peer_ids: list[str], args: dict[str, Any]
    ) -> None:
        peers = [record for device_id in peer_ids if (record := self.hub.devices.get(device_id))]
        results = await asyncio.gather(
            *(
                self.hub.async_command(peer, "intercom_signal", args)
                for peer in peers
                if peer is not None and peer.connected
            ),
            return_exceptions=True,
        )
        for result in results:
            if isinstance(result, BaseException):
                _LOGGER.warning(
                    "Intercom: could not relay a %s for %s: %s",
                    args["kind"],
                    call.call_id,
                    result,
                )

    async def _async_ended_by_device(self, record: DeviceRecord, data: dict[str, Any]) -> None:
        """A device ended the call on its own (limit, disconnect): tell the rest."""
        call = self.calls.get(data["call_id"])
        if call is None or record.device_id not in call.devices:
            return
        await self._async_end(call, data["reason"], except_device=record.device_id)

    async def _async_start_request(self, record: DeviceRecord, data: dict[str, Any]) -> None:
        """A device's own screen asked to start a call (ADR 0025 decision 6).

        Resolving the target is the same code path the service uses; only the
        target arrives as ids instead of a HA target. Starting a call means
        capturing one, so the device must declare the microphone capability
        (PROTOCOL.md 4.5). Every refusal ends what the device already started
        (ADR 0037 decision 10): it has minted the id and entered the call
        before this handler sees the request, and an event has no result to
        NACK, so `intercom_end` reason `command` is the only thing that stops
        it sitting in a call nothing carries.
        """
        call_id = data["call_id"]
        if CAP_TALK not in record.capabilities:
            await self._async_refuse_started(record, call_id, f"it does not declare {CAP_TALK}")
            return
        callees = self._resolve_event_target(data)
        if data["mode"] == MODE_CALL and not callees:
            await self._async_refuse_started(record, call_id, "its target is not a known tablet")
            return
        try:
            await self.async_start_call(record, callees, data["mode"], call_id=call_id)
        except (HomeAssistantError, ServiceValidationError) as err:
            await self._async_refuse_started(record, call_id, str(err))

    async def _async_refuse_started(self, record: DeviceRecord, call_id: str, why: str) -> None:
        """End the call a device started that the integration refused.

        The device is in the call it minted, so it gets `intercom_end` on that
        id; a device that is not in it answers `ok` and nothing happens. The
        end is sent before the refusal is logged, so the log is the last word
        and not a substitute for stopping the tablet.
        """
        if record.connected:
            try:
                await self.hub.async_command(
                    record, "intercom_end", {"call_id": call_id, "reason": "command"}
                )
            except HomeAssistantError as err:
                _LOGGER.warning(
                    "Intercom: %s did not take the end of %s: %s", _label(record), call_id, err
                )
        _LOGGER.warning("Intercom: %s could not start %s: %s", _label(record), call_id, why)

    def _resolve_event_target(self, data: dict[str, Any]) -> list[DeviceRecord] | None:
        """The devices an `intercom_start_request` names (PROTOCOL.md 4.7).

        A call names one HA device or one area; a broadcast may name neither,
        which means every device that can hear.
        """
        if (device_id := data.get("target_device_id")) is not None:
            record = self.hub.record_for_ha_device(device_id)
            return [record] if record is not None else []
        if (area_id := data.get("target_area_id")) is not None:
            registry = dr.async_get(self.hass)
            return [
                record
                for record in self.hub.devices.values()
                if (entry := registry.async_get(record.ha_device_id)) is not None
                and entry.area_id == area_id
            ]
        return None

    # --- timers ------------------------------------------------------------

    def _start_timer(
        self,
        call: IntercomCall,
        name: str,
        delay: float,
        action: Callable[[str], None],
    ) -> None:
        """Run `action` for this call after `delay`.

        The job is a `@callback` function, not a lambda: HA inspects the target
        and runs anything that is not a callback in an executor thread, where
        `hass.async_create_task` is not allowed. A callback keeps the timer, and
        the work it schedules, in the event loop.
        """
        self._cancel_timer(call, name)
        call_id = call.call_id

        @callback
        def _fire(_now: datetime) -> None:
            self._timers.pop((call_id, name), None)
            action(call_id)

        self._timers[(call_id, name)] = async_call_later(self.hass, delay, _fire)

    def _cancel_timer(self, call: IntercomCall, name: str) -> None:
        if (cancel := self._timers.pop((call.call_id, name), None)) is not None:
            cancel()

    def _cancel_timers(self, call: IntercomCall) -> None:
        for key in [key for key in self._timers if key[0] == call.call_id]:
            self._timers.pop(key)()

    @callback
    def _on_ring_limit(self, call_id: str) -> None:
        """Nobody answered within the ring limit: a missed call (ADR 0028)."""
        self._timers.pop((call_id, TIMER_RING), None)
        call = self.calls.get(call_id)
        if call is not None and call.state == STATE_RINGING:
            self._end_soon(call, "timeout", missed=True)

    @callback
    def _on_connect_timeout(self, call_id: str) -> None:
        """Answered, but the peers never got as far as signing in."""
        self._timers.pop((call_id, TIMER_CONNECT), None)
        call = self.calls.get(call_id)
        if call is not None and call.state == STATE_CONNECTING:
            self._end_soon(call, "timeout")

    @callback
    def _on_limit(self, call_id: str) -> None:
        """Guard 2: every call ends at the hard limit, however it is going."""
        self._timers.pop((call_id, TIMER_LIMIT), None)
        call = self.calls.get(call_id)
        if call is not None:
            self._end_soon(call, "limit")

    # --- ending ------------------------------------------------------------

    @callback
    def _end_soon(
        self,
        call: IntercomCall,
        reason: str,
        *,
        except_device: str | None = None,
        missed: bool = False,
    ) -> None:
        """End a call from a callback (a timer, a socket closing, a register).

        Every caller of this runs in the event loop (`@callback` throughout), so
        the end work is scheduled with `hass.async_create_task`, which is where
        HA expects it. A timer that lands while HA is stopping or after it
        stopped has nowhere to send a command, so the call is dropped instead.
        """
        if self.hass.state is not CoreState.running:
            if self.calls.pop(call.call_id, None) is not None:
                self._cancel_timers(call)
            return
        self.hass.async_create_task(
            self._async_end(call, reason, except_device=except_device, missed=missed)
        )

    async def async_end_call(self, call_id: str) -> IntercomCall:
        """End a call by id, wherever its devices are (the service's path)."""
        call = self.calls.get(call_id)
        if call is None:
            raise _invalid("intercom_unknown_call", call_id=call_id)
        await self._async_end(call, "command")
        return call

    async def async_end_device_calls(self, records: list[DeviceRecord]) -> None:
        """End whatever call the targeted devices are in (the service's path)."""
        calls = [c for r in records if (c := self.call_for_device(r.device_id)) is not None]
        if not calls:
            raise _invalid("intercom_not_in_call", devices=", ".join(_label(r) for r in records))
        for call in calls:
            await self._async_end(call, "command")

    async def _async_end(
        self,
        call: IntercomCall,
        reason: str,
        *,
        except_device: str | None = None,
        missed: bool = False,
    ) -> None:
        """End a call: send `intercom_end` to every device in it, then say so.

        Guard 3: every device in the call, in every state — a ringing, a
        connecting and a live call are all ended the same way. A device that
        cannot be reached is logged, not raised: the call is over either way.
        `missed` makes it the missed event instead of the ended one, so an
        automation listening for missed calls fires once.
        """
        if self.calls.pop(call.call_id, None) is None:
            return
        self._cancel_timers(call)
        call.pending_signals.clear()
        targets = [
            record
            for device_id in call.devices
            if device_id != except_device
            and (record := self.hub.devices.get(device_id)) is not None
            and record.connected
        ]
        results = await asyncio.gather(
            *(
                self.hub.async_command(
                    record,
                    "intercom_end",
                    {"call_id": call.call_id, "reason": reason},
                )
                for record in targets
            ),
            return_exceptions=True,
        )
        for record, result in zip(targets, results, strict=True):
            if isinstance(result, BaseException):
                _LOGGER.warning(
                    "Intercom: %s did not take the end of %s: %s",
                    _label(record),
                    call.call_id,
                    result,
                )
        fire_intercom_event(
            self.hass,
            INTERCOM_MISSED if missed else INTERCOM_ENDED,
            call,
            reason=reason,
        )

    # --- connection lifecycle (guard 4) ------------------------------------

    @callback
    def device_offline(self, device_id: str) -> None:
        """A device in a call dropped: end it for the other side, do not wait."""
        call = self.call_for_device(device_id)
        if call is not None:
            self._end_soon(call, "disconnect")

    @callback
    def device_rejoined(self, record: DeviceRecord) -> None:
        """A device registered again: nothing resumes (ADR 0028 guard 4).

        A call it was in ends for the other side, and a non-idle
        `intercom_state` in the new registration is answered with `intercom_end`
        once the subscription opens (there is nowhere to send a command before
        that). A stale state HA has no call for cannot be cleared: `intercom_end`
        names a call id and the device only acts on the one it is in.
        """
        call = self.call_for_device(record.device_id)
        if call is not None:
            self._end_soon(call, "disconnect", except_device=record.device_id)
        reported = record.state.get("intercom_state") or "idle"
        if reported != "idle":
            self._pending_reset[record.device_id] = call.call_id if call is not None else None
            if call is None:
                _LOGGER.warning(
                    "Intercom: %s registered while reporting %s, and HA has no call to end",
                    _label(record),
                    reported,
                )

    async def async_after_subscribe(self, record: DeviceRecord) -> None:
        """Send the deferred `intercom_end` for a device that rejoined mid-call."""
        if record.device_id not in self._pending_reset:
            return
        call_id = self._pending_reset.pop(record.device_id)
        if call_id is None:
            return
        try:
            await self.hub.async_command(
                record, "intercom_end", {"call_id": call_id, "reason": "disconnect"}
            )
        except HomeAssistantError as err:
            _LOGGER.warning(
                "Intercom: could not clear the stale call state on %s: %s",
                _label(record),
                err,
            )

    @callback
    def shutdown(self) -> None:
        """The hub is unloading: stop the timers, drop the calls, send nothing."""
        for cancel in self._timers.values():
            cancel()
        self._timers.clear()
        self.calls.clear()
        self._pending_reset.clear()
