"""Screensaver mode select (plan 5.5). Options are the protocol's mode values."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

SCREENSAVER_MODE = TabulaEntityDescription(
    key="screensaver_mode",
    translation_key="screensaver_mode",
    entity_category=EntityCategory.CONFIG,
    capabilities=frozenset({"screensaver"}),
    state_keys=frozenset({"screensaver_mode"}),
)


class TabulaScreensaverMode(TabulaEntity, SelectEntity):
    @property
    def options(self) -> list[str]:
        """The schema's screensaver_mode enum (PROTOCOL.md 4.4)."""
        return self.hub.tlp.enum_values("state", "screensaver_mode")

    @property
    def current_option(self) -> str | None:
        return self.device_state.get("screensaver_mode")

    async def async_select_option(self, option: str) -> None:
        await self.async_set_setting("screensaver_mode", option)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(hass, hub, async_add_entities, [SCREENSAVER_MODE], TabulaScreensaverMode)
    )
