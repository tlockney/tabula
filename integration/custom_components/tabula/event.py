"""Event entity (plan 5.7, P7.1): the last accepted wake-word detection.

HA's event entity records a timestamped occurrence rather than a value, which is
what a wake word is: something that happened, with a phrase and a score. The
``tabula_wake_word`` bus event carries the same facts for automations that would
rather trigger on the bus (ADR 0030 item 4), so the two surfaces must stay in
step - both are fed from the one TLP event.
"""

from __future__ import annotations

from typing import Any, ClassVar

from homeassistant.components.event import EventEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import DeviceRecord, TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

WAKE_WORD = TabulaEntityDescription(
    key="wake_word",
    capabilities=frozenset({"wake_word"}),
)


class TabulaWakeWordEvent(TabulaEntity, EventEntity):
    """Fires ``detected`` each time the tablet reports the wake phrase."""

    _attr_event_types: ClassVar[list[str]] = ["detected"]
    # Set on the first detection; None renders as no attributes at all.
    _attributes: dict[str, Any] | None = None

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        # The entity's state is the timestamp; the phrase and the probability
        # that came with that detection are the payload worth keeping.
        return self._attributes

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(self.hass.bus.async_listen("tabula_wake_word", self._handle_detection))

    @callback
    def _handle_detection(self, event: Event) -> None:
        """Accept only this device's detections; other tablets fire the same type.

        The payload carries both identifiers: ``device_id`` is the HA registry
        id automations filter by, ``tabula_device_id`` the TLP one this entity
        knows itself by.
        """
        if event.data.get("tabula_device_id") != self.device_id:
            return
        self._attributes = {
            key: event.data[key] for key in ("phrase", "probability") if key in event.data
        }
        self._trigger_event("detected", self._attributes)
        self.async_write_ha_state()


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None

    def factory(hub: TabulaHub, record: DeviceRecord, description: Any) -> TabulaEntity:
        return TabulaWakeWordEvent(hub, record, description)

    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, [WAKE_WORD], factory))
