"""The Tabularium: the panel's static bundle and its sidebar registration (P5.1).

The bundle is built from panel/ by `make panel` and staged into this directory
(`integration/custom_components/tabula/panel/panel.js`, gitignored), because HA
only serves files inside the integration's own directory. Without a bundle the
integration loads normally and skips the panel: a dev checkout that has not run
`make panel` should not break the hub.

The panel is admin-only (require_admin=True) and its data comes from the
admin-only `tabula/panel_*` WebSocket commands (panel_api.py).
"""

from __future__ import annotations

import hashlib
import logging
from pathlib import Path

from homeassistant.components import frontend, panel_custom
from homeassistant.components.http import StaticPathConfig
from homeassistant.core import HomeAssistant, callback
from homeassistant.loader import async_get_integration

from .const import (
    DATA_PANEL_REGISTERED,
    DATA_PANEL_STATIC_PATH,
    DOMAIN,
    PANEL_ICON,
    PANEL_STATIC_URL_PATH,
    PANEL_TITLE,
    PANEL_URL_PATH,
    PANEL_WEBCOMPONENT,
)

_LOGGER = logging.getLogger(__name__)

# Where `make panel` stages the build (build/panel/panel.js).
BUNDLE_DIR = Path(__file__).parent / "panel"
BUNDLE_FILE = "panel.js"
# The version in the URL busts the browser cache after an upgrade without
# touching the static path's caching headers.
MODULE_URL = f"{PANEL_STATIC_URL_PATH}/{BUNDLE_FILE}"


def bundle_digest() -> str:
    """A short content hash of the staged bundle (blocking: run in the executor).

    The module URL carries it, so a new bundle is a new URL even when the
    integration's version string has not changed, and browsers and HA's
    service worker never keep serving a stale panel.
    """
    return hashlib.sha256((BUNDLE_DIR / BUNDLE_FILE).read_bytes()).hexdigest()[:12]


def bundle_present() -> bool:
    """True when the built bundle has been staged next to the integration."""
    return (BUNDLE_DIR / BUNDLE_FILE).is_file()


async def async_register_panel(hass: HomeAssistant) -> bool:
    """Serve the bundle and add the sidebar entry; returns False if there is none.

    Idempotent per HA run: the static path can only be registered before the
    router freezes, and HA rejects a second panel on the same URL path, so a
    reload re-uses the registration instead of repeating it.
    """
    if not bundle_present():
        _LOGGER.warning(
            "Tabularium bundle missing (%s); run `make panel` and restart. "
            "The integration continues without the panel.",
            BUNDLE_DIR / BUNDLE_FILE,
        )
        return False
    if hass.data.get(DATA_PANEL_REGISTERED):
        return True

    if not hass.data.get(DATA_PANEL_STATIC_PATH):
        # The router freezes after startup, so this half is once per HA run and
        # outlives the hub; only the sidebar entry follows the entry.
        await hass.http.async_register_static_paths(
            # cache_headers=False: the folder's contents change on upgrade, and
            # the module URL carries the version.
            [StaticPathConfig(PANEL_STATIC_URL_PATH, str(BUNDLE_DIR), False)]
        )
        hass.data[DATA_PANEL_STATIC_PATH] = True

    integration = await async_get_integration(hass, DOMAIN)
    digest = await hass.async_add_executor_job(bundle_digest)
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path=PANEL_URL_PATH,
        webcomponent_name=PANEL_WEBCOMPONENT,
        sidebar_title=PANEL_TITLE,
        sidebar_icon=PANEL_ICON,
        module_url=f"{MODULE_URL}?v={integration.version}-{digest}",
        embed_iframe=False,
        require_admin=True,
    )
    hass.data[DATA_PANEL_REGISTERED] = True
    return True


@callback
def async_remove_panel(hass: HomeAssistant) -> None:
    """Drop the sidebar entry when the hub unloads; the next load adds it back."""
    if not hass.data.pop(DATA_PANEL_REGISTERED, False):
        return
    frontend.async_remove_panel(hass, PANEL_URL_PATH, warn_if_unknown=False)
