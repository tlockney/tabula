"""On-screen notifications (plan 5.5): notify.send_message targets this entity."""

from __future__ import annotations

from typing import Any

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

NOTIFICATIONS = TabulaEntityDescription(
    key="notifications",
    translation_key="notifications",
    capabilities=frozenset({"notifications"}),
)


class TabulaNotify(TabulaEntity, NotifyEntity):
    _attr_supported_features = NotifyEntityFeature.TITLE

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        args: dict[str, Any] = {"message": message}
        if title is not None:
            args["title"] = title
        await self.async_command("notify", args)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(hass, hub, async_add_entities, [NOTIFICATIONS], TabulaNotify)
    )
