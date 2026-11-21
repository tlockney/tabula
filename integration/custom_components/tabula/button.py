"""Buttons (plan 5.5): restart app, reload dashboard, clear cache. No capability needed."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.button import ButtonDeviceClass, ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform


@dataclass(frozen=True, kw_only=True)
class TabulaButtonDescription(TabulaEntityDescription):
    """``command`` is the TLP command the button sends with empty args."""

    command: str


BUTTONS = (
    TabulaButtonDescription(key="reload", translation_key="reload", command="reload"),
    TabulaButtonDescription(
        key="restart_app",
        translation_key="restart_app",
        device_class=ButtonDeviceClass.RESTART,
        entity_category=EntityCategory.CONFIG,
        command="restart_app",
    ),
    TabulaButtonDescription(
        key="clear_cache",
        translation_key="clear_cache",
        entity_category=EntityCategory.CONFIG,
        command="clear_cache",
    ),
)


class TabulaButton(TabulaEntity, ButtonEntity):
    entity_description: TabulaButtonDescription

    async def async_press(self) -> None:
        await self.async_command(self.entity_description.command, {})


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, BUTTONS, TabulaButton))
