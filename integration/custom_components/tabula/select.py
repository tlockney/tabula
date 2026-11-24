"""Screensaver mode select (plan 5.5) and intercom auto-answer (P9.5).

Options are the protocol's mode values, and the schema's enum for
auto-answer (ADR 0025 decision 1: off, listed or all).
"""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .const import DOMAIN
from .entity import (
    TabulaEntity,
    TabulaEntityDescription,
    TabulaSettingEntity,
    setup_platform,
)

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


INTERCOM_AUTO_ANSWER = TabulaEntityDescription(
    key="intercom_auto_answer",
    translation_key="intercom_auto_answer",
    entity_category=EntityCategory.CONFIG,
    setting="intercom_auto_answer",  # ADR 0025 decision 1; off unless the household opts in
)


class TabulaIntercomAutoAnswer(TabulaSettingEntity, SelectEntity):
    """Which calls this device answers with no tap (P9.5, ADR 0025 decision 1).

    ``listed`` needs callers to list, so it is refused while
    ``intercom_auto_answer_from`` is empty - the device would ring anyway, and
    a setting that reads as "only these" while naming none is a trap. The
    options come from the schema's enum, in the order the schema declares.
    """

    @property
    def options(self) -> list[str]:
        field_ = self.hub.settings.schema.field("intercom_auto_answer")
        return list(field_.enum or ())

    @property
    def current_option(self) -> str | None:
        return self.setting_value

    async def async_select_option(self, option: str) -> None:
        if option == "listed" and not self.hub.settings.value(
            self.device_id, "intercom_auto_answer_from"
        ):
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="intercom_auto_answer_needs_callers",
            )
        await self.async_write_setting(option)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(hass, hub, async_add_entities, [SCREENSAVER_MODE], TabulaScreensaverMode)
    )
    entry.async_on_unload(
        setup_platform(
            hass, hub, async_add_entities, [INTERCOM_AUTO_ANSWER], TabulaIntercomAutoAnswer
        )
    )
