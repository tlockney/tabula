"""Relay TLP device events to the HA event bus (plan 5.7, PROTOCOL.md 4.7).

ws_api accepts a ``tabula/event`` frame only when its name is in the v1 event
enum, so the mapping below always hits. Each TLP event becomes one HA bus
event; members of the event's ``data`` spread into the payload so an
automation reads ``trigger.event.data.tag`` directly, and ``type`` carries the
specific event name (shown vs dismissed vs action) for the grouped types.
Both device identifiers ride along (plan 5.7): ``device_id``, the HA device
registry ID automations filter by, and ``tabula_device_id``.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from homeassistant.core import callback

if TYPE_CHECKING:
    from homeassistant.core import HomeAssistant

    from . import DeviceRecord
    from .intercom import IntercomCall

_LOGGER = logging.getLogger(__name__)

# Intercom call lifecycle (P9.2, ADR 0025 decision 5). These are HA's own
# events about a call as a whole, not the device events `BUS_EVENT_TYPES`
# forwards: one fires when a call starts, one when it is answered, one when it
# is missed (nobody answered within the ring limit) and one when it ends for
# any other reason. An automation follows a call through them.
INTERCOM_STARTED = "tabula_intercom_started"
INTERCOM_ANSWERED = "tabula_intercom_answered"
INTERCOM_MISSED = "tabula_intercom_missed"
INTERCOM_ENDED = "tabula_intercom_ended"

INTERCOM_EVENTS = (INTERCOM_STARTED, INTERCOM_ANSWERED, INTERCOM_MISSED, INTERCOM_ENDED)

# TLP event name -> HA bus event type (plan 5.7). tabula_alarm and tabula_timer
# arrive with the events that introduce them; v1 has no such TLP events.
BUS_EVENT_TYPES: dict[str, str] = {
    "notification_shown": "tabula_notification",
    "notification_dismissed": "tabula_notification",
    "notification_action": "tabula_notification",
    "screensaver_started": "tabula_screensaver",
    "screensaver_stopped": "tabula_screensaver",
    "media_finished": "tabula_media",
    "page_changed": "tabula_page",
    "wake_word_detected": "tabula_wake_word",
    # P7.2: a voice turn opened or closed; the audio itself is binary (4.10).
    "voice_turn_started": "tabula_voice",
    "voice_turn_ended": "tabula_voice",
    # Intercom (P9.1): the signaling events reach the bus so an automation can
    # follow a call; the services and entities that start one arrive with P9.2.
    "intercom_answer": "tabula_intercom",
    "intercom_decline": "tabula_intercom",
    "intercom_signal": "tabula_intercom",
    "intercom_ended": "tabula_intercom",
    "intercom_start_request": "tabula_intercom",
}


@callback
def relay_event(
    hass: HomeAssistant, record: DeviceRecord, event: str, data: dict[str, Any]
) -> None:
    """Fire the bus event for one accepted TLP device event."""
    # ws_api rejects events outside the enum before this runs (4.0); a miss
    # here is a programming error, so it fails loudly instead of inventing a type.
    bus_type = BUS_EVENT_TYPES[event]
    payload: dict[str, Any] = {
        **data,
        "type": event,
        "device_id": record.ha_device_id,
        "tabula_device_id": record.device_id,
    }
    hass.bus.async_fire(bus_type, payload)
    _LOGGER.debug("relayed %s from %s as %s", event, record.device_id[:8], bus_type)


@callback
def fire_intercom_event(
    hass: HomeAssistant,
    event_type: str,
    call: IntercomCall,
    *,
    reason: str | None = None,
    answered_by: str | None = None,
) -> None:
    """Fire one call-lifecycle event for a whole call (P9.2).

    The payload names both id spaces, the way the relayed device events do:
    ``caller`` and ``callees`` are HA device registry ids (what an automation
    targets with a device trigger), ``tabula_caller`` and ``tabula_callees`` are
    the tablets' own TLP ids. ``reason`` rides on missed and ended, and
    ``answered_by`` on answered; nothing else is added.
    """
    assert event_type in INTERCOM_EVENTS, event_type
    payload: dict[str, Any] = {
        "call_id": call.call_id,
        "mode": call.mode,
        "caller": call.caller_ha_id,
        "callees": list(call.callee_ha_ids),
        "tabula_caller": call.caller,
        "tabula_callees": list(call.callees),
    }
    if reason is not None:
        payload["reason"] = reason
    if answered_by is not None:
        payload["answered_by"] = answered_by
    hass.bus.async_fire(event_type, payload)
    _LOGGER.debug("intercom %s for call %s", event_type, call.call_id)
