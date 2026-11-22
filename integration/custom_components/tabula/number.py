"""Screensaver brightness (plan 5.5), the idle timeout setting (P3.2, F-HA-11)
and the return-to-start delay (P3.15, F-DSH-15), and the chime volume setting
(F-NTF-09, ADR 0026). Media volume lives on the media player."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, EntityCategory, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, TabulaSettingEntity, setup_platform

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
        await self.async_set_setting("screensaver_brightness", round(value))


IDLE_TIMEOUT = TabulaEntityDescription(
    key="screensaver_idle_timeout",
    translation_key="screensaver_idle_timeout",
    entity_category=EntityCategory.CONFIG,
    setting="screensaver_idle_timeout",  # F-SSV-01; a new value restarts the countdown
)


class TabulaSettingsNumber(TabulaSettingEntity, NumberEntity):
    """A settings integer leaf in seconds; bounds come from the schema (ADR 0002)."""

    _attr_native_step = 1
    _attr_native_unit_of_measurement = UnitOfTime.SECONDS
    _attr_mode = NumberMode.BOX

    def __init__(self, hub: TabulaHub, record, description: TabulaEntityDescription) -> None:
        super().__init__(hub, record, description)
        assert description.setting is not None
        field_ = hub.settings.schema.field(description.setting)
        self._attr_native_min_value = field_.minimum
        self._attr_native_max_value = field_.maximum

    @property
    def native_value(self) -> float | None:
        return self.setting_value

    async def async_set_native_value(self, value: float) -> None:
        await self.async_write_setting(round(value))


RETURN_HOME_AFTER = TabulaEntityDescription(
    key="return_home_after",
    translation_key="return_home_after",
    entity_category=EntityCategory.CONFIG,
    setting="dashboard_return_home_after",  # F-DSH-15; 0 never returns
)


CHIME_VOLUME = TabulaEntityDescription(
    key="chime_volume",
    translation_key="chime_volume",
    entity_category=EntityCategory.CONFIG,
    setting="audio_chime_volume",  # F-NTF-09; a share of the media volume (ADR 0026)
)


class TabulaChimeVolume(TabulaSettingEntity, NumberEntity):
    """Card chime loudness, percent of the media volume (F-NTF-09, ADR 0026).

    The chime plays on the media stream, so 100 is as loud as TTS; bounds
    come from the schema (ADR 0002).
    """

    _attr_native_step = 1
    _attr_native_unit_of_measurement = PERCENTAGE
    _attr_mode = NumberMode.SLIDER

    def __init__(self, hub: TabulaHub, record, description: TabulaEntityDescription) -> None:
        super().__init__(hub, record, description)
        field_ = hub.settings.schema.field("audio_chime_volume")
        self._attr_native_min_value = field_.minimum
        self._attr_native_max_value = field_.maximum

    @property
    def native_value(self) -> float | None:
        return self.setting_value

    async def async_set_native_value(self, value: float) -> None:
        await self.async_write_setting(round(value))


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
    entry.async_on_unload(
        setup_platform(
            hass, hub, async_add_entities, [IDLE_TIMEOUT, RETURN_HOME_AFTER], TabulaSettingsNumber
        )
    )
    entry.async_on_unload(
        setup_platform(hass, hub, async_add_entities, [CHIME_VOLUME], TabulaChimeVolume)
    )
