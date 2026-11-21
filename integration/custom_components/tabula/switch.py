"""Switches (plan 5.5): screensaver active, keep screen on, adaptive brightness."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform


@dataclass(frozen=True, kw_only=True)
class TabulaSwitchDescription(TabulaEntityDescription):
    """``state_key`` holds the switch value; ``settings_key`` routes writes."""

    state_key: str
    settings_key: str | None = None


SWITCHES = (
    TabulaSwitchDescription(
        key="screensaver",
        translation_key="screensaver",
        capabilities=frozenset({"screensaver"}),
        state_keys=frozenset({"screensaver_active"}),
        state_key="screensaver_active",
    ),
    TabulaSwitchDescription(
        key="keep_screen_on",
        translation_key="keep_screen_on",
        entity_category=EntityCategory.CONFIG,
        capabilities=frozenset({"screen"}),
        state_keys=frozenset({"keep_screen_on"}),
        state_key="keep_screen_on",
        settings_key="screen_keep_screen_on",
    ),
    TabulaSwitchDescription(
        key="adaptive_brightness",
        translation_key="adaptive_brightness",
        entity_category=EntityCategory.CONFIG,
        capabilities=frozenset({"adaptive_brightness"}),
        state_keys=frozenset({"adaptive_brightness"}),
        state_key="adaptive_brightness",
        settings_key="screen_adaptive_brightness",
    ),
)


class TabulaSwitch(TabulaEntity, SwitchEntity):
    entity_description: TabulaSwitchDescription

    @property
    def is_on(self) -> bool | None:
        value = self.device_state.get(self.entity_description.state_key)
        return None if value is None else bool(value)

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._set(True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._set(False)

    async def _set(self, value: bool) -> None:
        if self.entity_description.key == "screensaver":
            await self.async_command("screensaver", {"active": value})
        else:
            # keep_screen_on and adaptive_brightness are settings (plan 5.6):
            # the write goes through the HA-owned store, which bumps the
            # revision and pushes; a command would bypass HA's copy.
            assert self.entity_description.settings_key is not None
            await self.async_set_setting(self.entity_description.settings_key, value)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, SWITCHES, TabulaSwitch))
