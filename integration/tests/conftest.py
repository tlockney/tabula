"""Pytest fixtures: Home Assistant with live HTTP/WS, the hub, and paired devices."""

from __future__ import annotations

from collections.abc import AsyncGenerator

import aiohttp
import pytest
from aiohttp import web
from custom_components.tabula.const import DOMAIN, HUB_UNIQUE_ID
from helpers import Paired, pair_device, uid
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry


@pytest.fixture(name="hass_setup")
async def hass_setup_fixture(hass, enable_custom_integrations):
    """Home Assistant with the real auth, http and websocket_api apps."""
    assert await async_setup_component(hass, "http", {})
    assert await async_setup_component(hass, "auth", {})
    assert await async_setup_component(hass, "websocket_api", {})
    return hass


@pytest.fixture(name="integration")
async def integration_fixture(hass_setup):
    """The tabula component set up: WS commands and pair/claim views registered.

    Views must be registered before the test HTTP server starts (aiohttp freezes
    the router once it runs) - exactly what HA's own startup order guarantees.
    """
    assert await async_setup_component(hass_setup, DOMAIN, {})
    return hass_setup


@pytest.fixture
async def server(integration, socket_enabled) -> AsyncGenerator[str]:
    """hass.http.app served on a real local TCP port; yields the base URL."""
    runner = web.AppRunner(integration.http.app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    assert site._server is not None
    port = site._server.sockets[0].getsockname()[1]
    try:
        yield f"http://127.0.0.1:{port}"
    finally:
        await runner.cleanup()


@pytest.fixture
async def hub(hass_setup) -> MockConfigEntry:
    """The hub config entry, loaded."""
    entry = MockConfigEntry(domain=DOMAIN, data={}, unique_id=HUB_UNIQUE_ID)
    entry.add_to_hass(hass_setup)
    assert await hass_setup.config_entries.async_setup(entry.entry_id)
    await hass_setup.async_block_till_done()
    return entry


@pytest.fixture
async def session() -> AsyncGenerator[aiohttp.ClientSession]:
    async with aiohttp.ClientSession() as client:
        yield client


@pytest.fixture
async def paired(hass_setup, server, hub, session) -> Paired:
    """One tablet paired through the real pair, confirm and claim path."""
    return await pair_device(hass_setup, server, uid())
