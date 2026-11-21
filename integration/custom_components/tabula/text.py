"""Text entities: the clock background image source (P3.2, F-SSV-09) and the
start page path (P3.15, F-DSH-02)."""

from __future__ import annotations

from homeassistant.components.text import TextEntity, TextMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntityDescription, TabulaSettingEntity, setup_platform

CLOCK_BACKGROUND = TabulaEntityDescription(
    key="clock_background",
    translation_key="clock_background",
    entity_category=EntityCategory.CONFIG,
    setting="screensaver_clock_background_source",
)

START_PATH = TabulaEntityDescription(
    key="start_path",
    translation_key="start_path",
    entity_category=EntityCategory.CONFIG,
    setting="dashboard_start_path",  # a frontend path until the catalog (ADR 0021)
)


class TabulaTextSetting(
    TabulaSettingEntity,
    TextEntity,
):
    """A settings text leaf; its length bound comes from the schema.

    Every write is a new revision and a push, identical value or not
    (SettingsStore.async_save always bumps), which is what the clock
    background's image refresh relies on (ADR 0015 item 6).
    """

    _attr_mode = TextMode.TEXT
    _attr_native_min = 0
    _attr_pattern = None

    def __init__(self, hub: TabulaHub, record, description: TabulaEntityDescription) -> None:
        super().__init__(hub, record, description)
        assert description.setting is not None
        field_ = hub.settings.schema.field(description.setting)
        self._attr_native_max = field_.max_length

    @property
    def native_value(self) -> str | None:
        return self.setting_value

    async def async_set_value(self, value: str) -> None:
        await self.async_write_setting(value)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(
            hass, hub, async_add_entities, [CLOCK_BACKGROUND, START_PATH], TabulaTextSetting
        )
    )
