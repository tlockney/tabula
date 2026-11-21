"""Screen light (plan 5.5): on/off is the panel, brightness is the panel level."""

from __future__ import annotations

from typing import Any

from homeassistant.components.light import ATTR_BRIGHTNESS, ColorMode, LightEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

SCREEN = TabulaEntityDescription(
    key="screen",
    translation_key="screen",
    capabilities=frozenset({"screen", "brightness"}),
    state_keys=frozenset({"screen_on", "brightness"}),
)


def to_ha_brightness(percent: int) -> int:
    """Device percent (0-100) to HA brightness (0-255)."""
    return round(percent * 255 / 100)


def to_device_brightness(value: int) -> int:
    """HA brightness (0-255) to device percent (0-100)."""
    return round(value * 100 / 255)


class TabulaScreenLight(TabulaEntity, LightEntity):
    """The panel. Always reports the actual level (plan 5.5, brightness ownership)."""

    @property
    def _dimmable(self) -> bool:
        return "brightness" in self.capabilities

    @property
    def supported_color_modes(self) -> set[ColorMode]:
        return {ColorMode.BRIGHTNESS} if self._dimmable else {ColorMode.ONOFF}

    @property
    def color_mode(self) -> ColorMode:
        return ColorMode.BRIGHTNESS if self._dimmable else ColorMode.ONOFF

    @property
    def is_on(self) -> bool | None:
        if (on := self.device_state.get("screen_on")) is not None:
            return bool(on)
        if (level := self.device_state.get("brightness")) is not None:
            return level > 0
        return None

    @property
    def brightness(self) -> int | None:
        if not self._dimmable or (level := self.device_state.get("brightness")) is None:
            return None
        return to_ha_brightness(level)

    async def async_turn_on(self, **kwargs: Any) -> None:
        args: dict[str, Any] = {"on": True}
        if ATTR_BRIGHTNESS in kwargs:
            args["brightness"] = to_device_brightness(kwargs[ATTR_BRIGHTNESS])
        await self.async_command("screen", args)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self.async_command("screen", {"on": False})


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(hass, hub, async_add_entities, [SCREEN], TabulaScreenLight)
    )
