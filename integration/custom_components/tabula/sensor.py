"""Sensors (plan 5.5): battery, connectivity, current page, next screensaver, app version, illuminance."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import LIGHT_LUX, PERCENTAGE, SIGNAL_STRENGTH_DECIBELS_MILLIWATT, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform


def _timestamp(value: Any) -> datetime | None:
    """RFC 3339 to an aware datetime; a value without an offset reads as unknown."""
    parsed = dt_util.parse_datetime(value) if isinstance(value, str) else None
    return parsed if parsed is not None and parsed.tzinfo is not None else None


@dataclass(frozen=True, kw_only=True)
class TabulaSensorDescription(TabulaEntityDescription, SensorEntityDescription):
    """``state_key`` holds the value; ``convert`` adapts it for HA."""

    state_key: str
    convert: Callable[[Any], Any] = lambda value: value


SENSORS = (
    TabulaSensorDescription(
        key="battery",
        device_class=SensorDeviceClass.BATTERY,
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        capabilities=frozenset({"battery"}),
        state_keys=frozenset({"battery_level"}),
        state_key="battery_level",
    ),
    TabulaSensorDescription(
        key="next_screensaver",
        translation_key="next_screensaver",
        device_class=SensorDeviceClass.TIMESTAMP,
        capabilities=frozenset({"screensaver"}),
        state_keys=frozenset({"next_screensaver"}),
        state_key="next_screensaver",
        convert=_timestamp,
    ),
    TabulaSensorDescription(
        key="current_page",
        translation_key="current_page",
        capabilities=frozenset({"navigation"}),
        state_keys=frozenset({"current_page"}),
        state_key="current_page",
    ),
    TabulaSensorDescription(
        key="network",
        translation_key="network",
        device_class=SensorDeviceClass.ENUM,
        options=["wifi", "ethernet", "none"],  # PROTOCOL.md 4.4 network values
        entity_category=EntityCategory.DIAGNOSTIC,
        state_keys=frozenset({"network"}),
        state_key="network",
    ),
    TabulaSensorDescription(
        key="wifi_signal",
        translation_key="wifi_signal",
        device_class=SensorDeviceClass.SIGNAL_STRENGTH,
        native_unit_of_measurement=SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
        state_class=SensorStateClass.MEASUREMENT,
        entity_category=EntityCategory.DIAGNOSTIC,
        entity_registry_enabled_default=False,
        state_keys=frozenset({"wifi_rssi"}),
        state_key="wifi_rssi",
    ),
    TabulaSensorDescription(
        key="app_version",
        translation_key="app_version",
        entity_category=EntityCategory.DIAGNOSTIC,
        state_keys=frozenset({"app_version"}),
        state_key="app_version",
    ),
    TabulaSensorDescription(
        key="illuminance",
        translation_key="illuminance",
        device_class=SensorDeviceClass.ILLUMINANCE,
        native_unit_of_measurement=LIGHT_LUX,
        state_class=SensorStateClass.MEASUREMENT,
        # Not diagnostic (ADR 0019): the room's light level, for automations.
        capabilities=frozenset({"illuminance"}),
        state_keys=frozenset({"illuminance"}),
        state_key="illuminance",
    ),
    # The last capture started by the capture button (F-HA-12, P3.13): not a
    # device state key - it lives on the record, persisted, and only the
    # capture flow updates it (untouched by ordinary image fetches).
    TabulaSensorDescription(
        key="last_capture",
        translation_key="last_capture",
        device_class=SensorDeviceClass.TIMESTAMP,
        entity_category=EntityCategory.DIAGNOSTIC,
        capabilities=frozenset({"screenshot"}),
        state_keys=frozenset({"last_capture"}),
        state_key="last_capture",
        convert=_timestamp,
    ),
)


class TabulaSensor(TabulaEntity, SensorEntity):
    entity_description: TabulaSensorDescription

    @property
    def native_value(self) -> Any:
        value = self.device_state.get(self.entity_description.state_key)
        return None if value is None else self.entity_description.convert(value)


class TabulaCaptureSensor(TabulaEntity, SensorEntity):
    """The last capture's timestamp, read from the record (not device state)."""

    entity_description: TabulaSensorDescription

    @property
    def native_value(self) -> datetime | None:
        record = self.record
        if record is None or record.last_capture is None:
            return None
        return _timestamp(record.last_capture)


def _factory(hub: TabulaHub, record: Any, description: TabulaSensorDescription) -> TabulaEntity:
    if description.key == "last_capture":
        return TabulaCaptureSensor(hub, record, description)
    return TabulaSensor(hub, record, description)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, SENSORS, _factory))
