"""The Tabularium: registration, the static bundle and the panel WebSocket (P5.1).

Registration drives the real frontend and panel_custom components, so the
assertions read HA's own panel registry rather than a mock of it.
"""

from __future__ import annotations

import logging

import pytest
from custom_components.tabula import panel
from custom_components.tabula.const import (
    DATA_PANEL_REGISTERED,
    DOMAIN,
    PANEL_ICON,
    PANEL_STATIC_URL_PATH,
    PANEL_TITLE,
    PANEL_URL_PATH,
    PANEL_WEBCOMPONENT,
    PROTOCOL_VERSION,
    SCHEMA_VERSION,
)
from helpers import ws_connect
from homeassistant.auth.const import GROUP_ID_ADMIN
from homeassistant.components import frontend

BUNDLE = "// Tabula panel bundle\n"


@pytest.fixture
def bundle(monkeypatch, tmp_path):
    """A staged bundle, in place before the hub entry loads (router is open then)."""
    root = tmp_path / "panel"
    root.mkdir()
    (root / panel.BUNDLE_FILE).write_text(BUNDLE)
    monkeypatch.setattr(panel, "BUNDLE_DIR", root)
    return root


def panel_config(hass_setup):
    """The registered panel, or None."""
    return (hass_setup.data.get(frontend.DATA_PANELS) or {}).get(PANEL_URL_PATH)


def static_paths(hass_setup) -> list[str]:
    """The static paths registered for the bundle, one entry per registration."""
    return sorted(
        {
            (route.resource.canonical or "").split("?")[0]
            for resource in hass_setup.http.app.router.resources()
            for route in resource
            if (route.resource.canonical or "").startswith(PANEL_STATIC_URL_PATH)
        }
    )


async def test_registers_the_panel_once_with_the_expected_arguments(hass_setup, bundle, hub):
    """setup_entry registers the panel; a second registration is a no-op."""
    registered = panel_config(hass_setup)
    assert registered is not None
    assert registered.require_admin is True
    assert registered.sidebar_title == PANEL_TITLE
    assert registered.sidebar_icon == PANEL_ICON
    assert registered.component_name == "custom"
    custom = (registered.config or {})["_panel_custom"]
    assert custom["name"] == PANEL_WEBCOMPONENT
    assert custom["embed_iframe"] is False
    # The manifest version, so an upgrade busts the browser cache.
    assert custom["module_url"] == f"{PANEL_STATIC_URL_PATH}/panel.js?v=0.1.0"
    # The static path serves the same directory the bundle is read from.
    assert static_paths(hass_setup) == [PANEL_STATIC_URL_PATH]

    # Idempotent: frontend rejects a second panel on the same URL path.
    assert await panel.async_register_panel(hass_setup) is True
    assert panel_config(hass_setup) is registered


async def test_static_path_serves_the_staged_bundle(hass_setup, bundle, hub, server, session):
    """The bundle is reachable at the URL the panel's module_url names."""
    url = f"{server}{PANEL_STATIC_URL_PATH}/panel.js"
    async with session.get(url) as response:
        assert response.status == 200
        assert await response.text() == BUNDLE


async def test_skips_registration_with_a_warning_when_the_bundle_is_absent(
    hass_setup, hub, tmp_path, monkeypatch, caplog
):
    """A checkout that has not run `make panel` still loads the hub."""
    assert panel_config(hass_setup) is None
    empty = tmp_path / "missing"
    empty.mkdir()
    monkeypatch.setattr(panel, "BUNDLE_DIR", empty)
    hass_setup.data.pop(DATA_PANEL_REGISTERED, None)

    with caplog.at_level(logging.WARNING, logger=panel.__name__):
        assert await panel.async_register_panel(hass_setup) is False

    assert panel_config(hass_setup) is None
    assert "make panel" in caplog.text
    assert static_paths(hass_setup) == []


async def test_unload_removes_the_panel_and_keeps_the_static_path(hass_setup, bundle, hub):
    """The sidebar entry follows the hub; the frozen HTTP route stays."""
    assert panel_config(hass_setup) is not None

    assert await hass_setup.config_entries.async_unload(hub.entry_id)
    await hass_setup.async_block_till_done()

    assert panel_config(hass_setup) is None
    assert DATA_PANEL_REGISTERED not in hass_setup.data
    # Reloading the hub brings the panel back.
    assert await hass_setup.config_entries.async_setup(hub.entry_id)
    await hass_setup.async_block_till_done()
    assert panel_config(hass_setup) is not None
    assert static_paths(hass_setup) == [PANEL_STATIC_URL_PATH], "static path re-registered"


async def test_panel_info_for_an_admin(hass_setup, server, hub, paired, session):
    """An admin sees the versions and the paired device count."""
    user = await hass_setup.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    try:
        result = await client.reply({"id": 1, "type": "tabula/panel_info"})
    finally:
        await client.close()

    assert result["success"] is True, result
    info = result["result"]
    assert info["version"] == "0.1.0"
    assert info["protocol_version"] == PROTOCOL_VERSION
    assert info["schema_version"] == SCHEMA_VERSION
    assert info["devices"] == 1


async def test_panel_info_is_refused_for_a_non_admin(hass_setup, server, hub, session):
    """A regular user is refused: the panel is a view over admin authority."""
    user = await hass_setup.auth.async_create_system_user("Not an admin")
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    try:
        result = await client.reply({"id": 1, "type": "tabula/panel_info"})
    finally:
        await client.close()

    assert result["success"] is False, result
    assert result["error"]["code"] == "unauthorized"


async def test_panel_info_rejects_unknown_keys(hass_setup, server, hub, session):
    """Each command validates its own message shape."""
    user = await hass_setup.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    try:
        result = await client.reply({"id": 1, "type": "tabula/panel_info", "device_id": DOMAIN})
    finally:
        await client.close()

    assert result["success"] is False, result
    assert result["error"]["code"] == "invalid_format"
