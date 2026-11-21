"""The screenshot image entity (packet P3.13, F-HA-12, plan 5.5).

Created when the device declares the ``screenshot`` capability. Serves the
latest capture the upload view stored (PROTOCOL.md 5) - or the bundled
placeholder while there is none or the last capture found the screen off
(F-MGT-07, ADR 0014). The state is the last capture's timestamp (the record's
``last_capture``, the same value the timestamp sensor shows); it moves only
when the capture button runs a capture, never on an ordinary image fetch.
"""

from __future__ import annotations

from datetime import datetime

from homeassistant.components.image import ImageEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from . import TabulaHub
from .const import SIGNAL_SCREENSHOT
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform
from .screenshots import PLACEHOLDER

IMAGES = (
    TabulaEntityDescription(
        key="screenshot",
        translation_key="screenshot",
        capabilities=frozenset({"screenshot"}),
    ),
)


class TabulaImage(TabulaEntity, ImageEntity):
    """The device's screen, as captured by the last capture."""

    entity_description: TabulaEntityDescription
    # The stored captures and the placeholder are JPEG, so the entity's content
    # type is honest for both (the placeholder asset is JPEG-encoded, ADR 0014).
    _attr_content_type = "image/jpeg"

    def __init__(
        self, hub: TabulaHub, record, description: TabulaEntityDescription
    ) -> None:
        # ImageEntity.__init__ sets up the image proxy client and its access
        # tokens; TabulaEntity.__init__ sets the entity's identity.
        ImageEntity.__init__(self, hub.hass)
        TabulaEntity.__init__(self, hub, record, description)

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                SIGNAL_SCREENSHOT.format(self.device_id),
                self._capture_landed,
            )
        )
        self._attr_image_last_updated = self._last_capture()

    @callback
    def _last_capture(self) -> datetime | None:
        record = self.record
        if record is None or record.last_capture is None:
            return None
        return dt_util.parse_datetime(record.last_capture)

    @callback
    def _capture_landed(self) -> None:
        """A capture stored an image, or the stored one was dropped."""
        self._attr_image_last_updated = self._last_capture()
        self.async_write_ha_state()

    def image(self) -> bytes | None:
        """The stored capture, or the placeholder when there is none.

        Called in the executor by the image entity framework.
        """
        record = self.record
        if record is None:
            return None
        stored = self.hub.screenshots.image(self.device_id)
        return stored if stored is not None else PLACEHOLDER


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, IMAGES, TabulaImage))
