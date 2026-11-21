"""Entity base and capability gating for the Phase 1 platforms (plan 5.5).

Each entity belongs to one paired tablet and reads the device's state keys
(docs/PROTOCOL.md 4.4). An entity is created only when the device declared the
capability that covers it, and is available only while the device has a
current connection. Nothing is removed on disconnect; an entity whose capability
the device stops declaring at registration is removed (FEATURES F-HA-02: no
permanently unavailable placeholders).

State key to capability mapping (the protocol leaves state keys without a
capability "covered by none", so those entities always exist):

| Capability | State keys | Entities |
| --- | --- | --- |
| screen | screen_on, keep_screen_on | light (on/off), keep-screen-on switch |
| brightness | brightness | light (brightness) |
| adaptive_brightness | adaptive_brightness | adaptive-brightness switch |
| screensaver | screensaver_active, _mode, _brightness; next_screensaver | switch, select,
|  |  | number, sensor |
| battery | battery_level, charging | sensor, binary_sensor |
| audio | volume, muted, media_state | media_player |
| notifications | (none) | notify |
| navigation | current_page | sensor |
| none | network, wifi_rssi, app_version | sensors; reload, restart and clear-cache buttons |
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Any

from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_platform
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity, EntityDescription
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import DeviceRecord, TabulaHub
from .const import DOMAIN, SIGNAL_CAPABILITIES, SIGNAL_DEVICE_UPDATE


@dataclass(frozen=True, kw_only=True)
class TabulaEntityDescription(EntityDescription):
    """A Tabula entity: the capability that gates it and the state keys it reads.

    ``key`` doubles as the unique-id suffix. ``capabilities`` is a set: the
    entity exists when the device declared any of them; empty means always.
    ``state_keys`` are the keys whose change refreshes the entity.
    """

    capabilities: frozenset[str] = frozenset()
    state_keys: frozenset[str] = frozenset()


class TabulaEntity(Entity):
    """Base for every Tabula entity: device info, availability, push updates."""

    _attr_has_entity_name = True
    _attr_should_poll = False
    entity_description: TabulaEntityDescription

    def __init__(
        self, hub: TabulaHub, record: DeviceRecord, description: TabulaEntityDescription
    ) -> None:
        self.hub = hub
        self.device_id = record.device_id
        self.entity_description = description
        self._attr_unique_id = f"{record.device_id}_{description.key}"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, record.device_id)})
        # Set by setup_platform: forget this entity when it is removed.
        self.on_removed: Callable[[], None] | None = None

    @property
    def record(self) -> DeviceRecord | None:
        """The device's current record, looked up each time.

        Re-pairing replaces the record object for the same device_id, so an
        entity never keeps one.
        """
        return self.hub.devices.get(self.device_id)

    @property
    def capabilities(self) -> set[str]:
        record = self.record
        return set(record.capabilities) if record is not None else set()

    @property
    def device_state(self) -> dict[str, Any]:
        """The tablet's last known state keys (empty while unpaired)."""
        record = self.record
        return record.state if record is not None else {}

    @property
    def connected(self) -> bool:
        record = self.record
        return record is not None and record.connected

    @property
    def available(self) -> bool:
        """Connected, and the capability is still declared on this connection."""
        caps = self.entity_description.capabilities
        if not self.connected:
            return False
        return not caps or bool(caps & self.capabilities)

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                SIGNAL_DEVICE_UPDATE.format(self.device_id),
                self._handle_update,
            )
        )

    async def async_will_remove_from_hass(self) -> None:
        if self.on_removed is not None:
            self.on_removed()

    @callback
    def _handle_update(self, keys: set[str] | None) -> None:
        """One write per entity per frame, and only when one of its keys changed."""
        if keys is None or keys & self.entity_description.state_keys:
            self.async_write_ha_state()

    async def async_command(self, command: str, args: dict[str, Any]) -> dict[str, Any]:
        """Send a command to the tablet and wait for its result (PROTOCOL.md 4.5)."""
        record = self.record
        if record is None:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="command_offline",
                translation_placeholders={"command": command},
            )
        return await self.hub.async_command(record, command, args)

    async def async_set_setting(self, settings_key: str, value: Any) -> None:
        """Change one device setting through HA's settings store (plan 5.6).

        The store bumps the revision and pushes the whole document; an offline
        tablet is reconciled when it next registers. ``settings_key`` is the
        schema's flat key (settings.SettingsField.key).
        """
        from .settings import SettingsError, async_set_setting

        try:
            await async_set_setting(self.hub, self.device_id, settings_key, value)
        except SettingsError as e:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="setting_invalid",
                translation_placeholders={"setting": settings_key, "problem": str(e)[:200]},
            ) from e


def declared(record: DeviceRecord, description: TabulaEntityDescription) -> bool:
    """The device declared a capability that covers this entity (or none is needed)."""
    caps = description.capabilities
    return not caps or bool(caps & set(record.capabilities))


def setup_platform(
    hass: HomeAssistant,
    hub: TabulaHub,
    async_add_entities: AddEntitiesCallback,
    descriptions: Iterable[TabulaEntityDescription],
    factory: Callable[[TabulaHub, DeviceRecord, Any], TabulaEntity],
) -> Callable[[], None]:
    """Add entities for every paired device now; follow capability changes later.

    A capability gained at registration adds its entities; one dropped removes
    them from the entity registry (FEATURES F-HA-02).

    Returns the dispatcher unsubscribe; the caller registers it on the entry.
    """
    descriptions = tuple(descriptions)
    # unique_id -> live entity. An entry goes when its entity is removed (a
    # dropped capability, a deleted device), so a later re-pair adds it again.
    added: dict[str, TabulaEntity] = {}
    # Only valid during platform setup, so read it now for later removals.
    platform = entity_platform.async_get_current_platform().domain

    def _forget(unique_id: str, entity: TabulaEntity) -> Callable[[], None]:
        def _remove() -> None:
            if added.get(unique_id) is entity:
                del added[unique_id]

        return _remove

    @callback
    def _sync(record: DeviceRecord) -> None:
        new = []
        registry = er.async_get(hass)
        for description in descriptions:
            unique_id = f"{record.device_id}_{description.key}"
            if declared(record, description):
                if unique_id not in added:
                    entity = factory(hub, record, description)
                    entity.on_removed = _forget(unique_id, entity)
                    added[unique_id] = entity
                    new.append(entity)
                continue
            if entity_id := registry.async_get_entity_id(platform, DOMAIN, unique_id):
                registry.async_remove(entity_id)
        if new:
            async_add_entities(new)

    for record in list(hub.devices.values()):
        _sync(record)
    return async_dispatcher_connect(hass, SIGNAL_CAPABILITIES, _sync)
