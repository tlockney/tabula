"""Binary sensors (plan 5.5): charging, the tablet's link to HA (FEATURES F-HA-09),
and whether the wake word is listening (P7.1)."""

from __future__ import annotations

from typing import Any

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import DeviceRecord, TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

CHARGING = TabulaEntityDescription(
    key="charging",
    device_class=BinarySensorDeviceClass.BATTERY_CHARGING,
    capabilities=frozenset({"battery"}),
    state_keys=frozenset({"charging"}),
)


CONNECTED = TabulaEntityDescription(
    key="connected",
    device_class=BinarySensorDeviceClass.CONNECTIVITY,
    entity_category=EntityCategory.DIAGNOSTIC,
)

# On while the microphone is open and the frontend is computing features: the
# setting is on, RECORD_AUDIO is held, the service is running and playback is
# not suspending detection (PROTOCOL 4.4, ADR 0030). Diagnostic because it
# describes the integration's capture rather than the room, and because it is
# the honest answer to "is it listening?" when Fire OS has taken the permission
# away (F-KSK-21, ADR 0029 item 7).
WAKE_WORD_ACTIVE = TabulaEntityDescription(
    key="wake_word_active",
    device_class=BinarySensorDeviceClass.RUNNING,
    entity_category=EntityCategory.DIAGNOSTIC,
    capabilities=frozenset({"wake_word"}),
    state_keys=frozenset({"wake_word_active"}),
)


class TabulaConnected(TabulaEntity, BinarySensorEntity):
    """On while the tablet holds a registered connection.

    HA computes this itself, so it stays available and reads off when the link
    drops, while every other entity of the device goes unavailable.
    """

    @property
    def available(self) -> bool:
        return True

    @property
    def is_on(self) -> bool:
        return self.connected


class TabulaCharging(TabulaEntity, BinarySensorEntity):
    @property
    def is_on(self) -> bool | None:
        value = self.device_state.get("charging")
        return None if value is None else bool(value)


class TabulaWakeWordActive(TabulaEntity, BinarySensorEntity):
    @property
    def is_on(self) -> bool | None:
        value = self.device_state.get("wake_word_active")
        return None if value is None else bool(value)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None

    def factory(hub: TabulaHub, record: DeviceRecord, description: Any) -> TabulaEntity:
        if description is CONNECTED:
            cls = TabulaConnected
        elif description is WAKE_WORD_ACTIVE:
            cls = TabulaWakeWordActive
        else:
            cls = TabulaCharging
        return cls(hub, record, description)

    entry.async_on_unload(
        setup_platform(
            hass, hub, async_add_entities, [CHARGING, CONNECTED, WAKE_WORD_ACTIVE], factory
        )
    )
