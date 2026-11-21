"""Screenshots (packet P3.13, PROTOCOL.md 5): the upload view and the store.

A device captures on the ``screenshot`` command, then POSTs the JPEG to
``/api/tabula/screenshot`` with its device user's bearer token and
``X-Tabula-Command-Id`` naming the pending command, and only then sends the
command's ``tabula/result`` (the mock and the app both follow this order).

The view resolves the device from the token (no device ID in the path), accepts
the upload only while that command ID names a still-pending ``screenshot``
command on the device's current connection (``409 no_pending_screenshot``
otherwise, PROTOCOL.md 6), and stores the image in the media dir - one file per
device, never the config store. The image entity (image.py) serves the stored
image, or the bundled placeholder when there is none (F-MGT-07, ADR 0014).
"""

from __future__ import annotations

import logging
from pathlib import Path

from aiohttp import web
from homeassistant.components.http import KEY_HASS_USER, HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.http import HTTPStatus

from .const import DOMAIN, SCREENSHOT_MAX_BYTES, SIGNAL_SCREENSHOT
from .pairing import _http_error, _hub, _not_configured

_LOGGER = logging.getLogger(__name__)

# F-MGT-07, ADR 0014: what the image entity serves with the screen off - a
# designed static image (loaded once), not a black JPEG frame. JPEG-encoded so
# the image entity's content type is honest for both stored and placeholder.
PLACEHOLDER = (Path(__file__).parent / "screenshot_placeholder.jpg").read_bytes()


class ScreenshotStore:
    """The latest capture per device, in the media dir (packet P3.13 item 3).

    One JPEG per device, overwritten in place, so a device's storage is
    bounded at ``SCREENSHOT_MAX_BYTES``. A capture with the screen off removes
    it, so the entity serves the placeholder again. File IO runs in the
    executor; ``image()`` is already called there by the image entity.
    """

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass
        self._dir = Path(hass.config.path("media", DOMAIN))

    def _path(self, device_id: str) -> Path:
        return self._dir / f"{device_id}.jpg"

    async def store(self, device_id: str, data: bytes) -> None:
        """Write the latest capture for this device."""
        await self._hass.async_add_executor_job(self._store, device_id, data)

    def _store(self, device_id: str, data: bytes) -> None:
        self._dir.mkdir(parents=True, exist_ok=True)
        self._path(device_id).write_bytes(data)

    async def remove(self, device_id: str) -> None:
        """Drop the stored capture (the screen was off, F-MGT-07)."""
        await self._hass.async_add_executor_job(self._remove, device_id)

    def _remove(self, device_id: str) -> None:
        self._path(device_id).unlink(missing_ok=True)

    def image(self, device_id: str) -> bytes | None:
        """The stored capture, or None when there is none (run in executor)."""
        try:
            return self._path(device_id).read_bytes()
        except FileNotFoundError:
            return None


class TabulaScreenshotView(HomeAssistantView):
    """POST /api/tabula/screenshot: a device's screenshot upload (PROTOCOL 5)."""

    url = "/api/tabula/screenshot"
    name = "api:tabula:screenshot"
    # requires_auth stays True (the default): the device user's bearer token.

    async def post(self, request: web.Request) -> web.Response:
        hub = _hub(request)
        if hub is None:
            return _not_configured()
        tlp = hub.tlp
        record = hub.device_for_user(request[KEY_HASS_USER])
        if record is None:
            return _http_error(tlp, HTTPStatus.FORBIDDEN, "forbidden", "Not a Tabula device user")
        command_id = request.headers.get("X-Tabula-Command-Id", "")
        pending = hub.result_futures.get((record.device_id, command_id))
        if (
            pending is None
            or pending.command != "screenshot"
            or pending.session is not record.current
        ):
            return _http_error(
                tlp,
                HTTPStatus.CONFLICT,
                "no_pending_screenshot",
                f"No pending screenshot command for {command_id!r}",
            )
        if request.headers.get("Content-Type") != "image/jpeg":
            return _http_error(
                tlp,
                HTTPStatus.BAD_REQUEST,
                "invalid_request",
                "Content-Type must be image/jpeg",
            )
        # Read the body in bounded chunks until EOF or one byte past the cap:
        # a single read() may return short, and a body over the cap must be
        # refused without ever buffering it whole.
        remaining = SCREENSHOT_MAX_BYTES + 1
        chunks: list[bytes] = []
        while remaining > 0:
            chunk = await request.content.read(min(remaining, 64 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        data = b"".join(chunks)
        if len(data) > SCREENSHOT_MAX_BYTES:
            return _http_error(
                tlp,
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "too_large",
                f"Screenshot exceeds SCREENSHOT_MAX_BYTES ({SCREENSHOT_MAX_BYTES} bytes)",
            )
        if not data:
            return _http_error(
                tlp, HTTPStatus.BAD_REQUEST, "invalid_request", "The upload was empty"
            )
        await hub.screenshots.store(record.device_id, data)
        # The capture flow (TabulaHub.async_capture) reads this marker when the
        # command's result lands: an upload before the result means the screen
        # was on; no upload means it was off and the placeholder is served.
        record.screenshot_delivered = True
        async_dispatcher_send(hub.hass, SIGNAL_SCREENSHOT.format(record.device_id))
        _LOGGER.debug("screenshot stored for %s (%d bytes)", record.device_id, len(data))
        body = {"status": "stored", "bytes": len(data)}
        # Strict sender: a body we would refuse ourselves is a bug.
        tlp.validate("http_screenshot_response", body)
        return web.json_response(body)


def register_views(hass: HomeAssistant) -> None:
    """Register the screenshot upload view (survives hub reloads, PROTOCOL 5)."""
    hass.http.register_view(TabulaScreenshotView())


__all__ = ["PLACEHOLDER", "ScreenshotStore", "TabulaScreenshotView", "register_views"]
