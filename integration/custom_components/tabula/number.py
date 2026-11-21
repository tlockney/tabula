"""Screensaver brightness (plan 5.5). Volume lives on the media player."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform
from .settings import async_set_setting

SCREENSAVER_BRIGHTNESS = TabulaEntityDescription(
    key="screensaver_brightness",
    translation_key="screensaver_brightness",
    entity_category=EntityCategory.CONFIG,
    capabilities=frozenset({"screensaver"}),
    state_keys=frozenset({"screensaver_brightness"}),
)


class TabulaScreensaverBrightness(TabulaEntity, NumberEntity):
    """0-100 %, the protocol's range for screensaver_brightness (PROTOCOL.md 4.4)."""

    _attr_native_min_value = 0
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_native_unit_of_measurement = PERCENTAGE
    _attr_mode = NumberMode.SLIDER

    @property
    def native_value(self) -> float | None:
        return self.device_state.get("screensaver_brightness")

    async def async_set_native_value(self, value: float) -> None:
        await async_set_setting(self.hub, self.device_id, "screensaver_brightness", round(value))


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(
            hass, hub, async_add_entities, [SCREENSAVER_BRIGHTNESS], TabulaScreensaverBrightness
        )
    )
