"""Buttons (plan 5.5): restart app, reload dashboard, clear cache, go to the
start page, capture screenshot."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.button import ButtonDeviceClass, ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .const import DOMAIN
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform


@dataclass(frozen=True, kw_only=True)
class TabulaButtonDescription(TabulaEntityDescription):
    """``command`` is the TLP command the button sends with empty args.

    ``capture`` marks the capture button (P3.13): it does not just send the
    command - the hub's capture flow (TabulaHub.async_capture) also records
    the timestamp and decides stored image versus placeholder.
    """

    command: str
    capture: bool = False


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
    TabulaButtonDescription(
        key="go_home",
        translation_key="go_home",
        command="go_home",
        capabilities=frozenset({"go_home"}),
    ),
    TabulaButtonDescription(
        key="capture_screenshot",
        translation_key="capture_screenshot",
        command="screenshot",
        capture=True,
        capabilities=frozenset({"screenshot"}),
    ),
)


class TabulaButton(TabulaEntity, ButtonEntity):
    entity_description: TabulaButtonDescription

    async def async_press(self) -> None:
        if self.entity_description.capture:
            await self.async_capture()
            return
        await self.async_command(self.entity_description.command, {})

    async def async_capture(self) -> None:
        """Press the capture button (F-HA-12): capture, then record the outcome."""
        record = self.record
        if record is None:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="command_offline",
                translation_placeholders={"command": "screenshot"},
            )
        await self.hub.async_capture(record)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, BUTTONS, TabulaButton))
