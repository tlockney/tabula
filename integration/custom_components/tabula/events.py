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

_LOGGER = logging.getLogger(__name__)

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
