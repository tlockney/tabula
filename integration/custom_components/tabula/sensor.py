"""Sensors (plan 5.5): battery, connectivity, current page, next screensaver, app version."""

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
from homeassistant.const import PERCENTAGE, SIGNAL_STRENGTH_DECIBELS_MILLIWATT, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform


def _timestamp(value: Any) -> datetime | None:
    return dt_util.parse_datetime(value) if isinstance(value, str) else None


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
)


class TabulaSensor(TabulaEntity, SensorEntity):
    entity_description: TabulaSensorDescription

    @property
    def native_value(self) -> Any:
        value = self.device_state.get(self.entity_description.state_key)
        return None if value is None else self.entity_description.convert(value)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, SENSORS, TabulaSensor))
