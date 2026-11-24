"""Pytest fixtures: Home Assistant with live HTTP/WS, the hub, and paired devices."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator

import aiohttp
import pytest
from aiohttp import web
from custom_components.tabula.const import DOMAIN, HUB_UNIQUE_ID
from helpers import Paired, pair_device, uid
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

# Opening any aiohttp ClientSession creates an async DNS resolver; with aiodns
# (a homeassistant dependency) that is a pycares channel, which starts a
# daemon thread named "_run_safe_shutdown_loop" that lives for the rest of the
# process. The phacc pin (0.13.316) exempts exactly that thread from its
# verify_cleanup teardown; the floor harness (phacc 0.13.201, HA 2025.1)
# predates the exemption, so there the first test to open a client session
# would fail teardown for a thread that exists on every harness (ADR 0001
# item 8). Starting it once here, before any test's thread snapshot, keeps
# the teardown assertion meaningful on every leg. The resolver is kept
# referenced on purpose: it owns the thread.
_PRESERVED_RESOLVER: aiohttp.resolver.AsyncResolver | None = None


async def _start_aiohttp_resolver_thread() -> None:
    global _PRESERVED_RESOLVER
    _PRESERVED_RESOLVER = aiohttp.resolver.AsyncResolver()


asyncio.run(_start_aiohttp_resolver_thread())


@pytest.fixture(name="hass_setup")
async def hass_setup_fixture(hass, enable_custom_integrations):
    """Home Assistant with the real auth, http and websocket_api apps.

    The core "homeassistant" component is set up too: the conversation component
    (reached through the assist_satellite platform, P7.3) reads its
    exposed-entity registry.
    """
    assert await async_setup_component(hass, "homeassistant", {})
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
    # Entity platforms whose components register HTTP views (media_player,
    # notify via repairs, and image via the image proxy) must also be set up
    # before the server starts. The same holds for the components the
    # assist_satellite platform (P7.3) pulls in: intent, stt and tts each
    # register a view, so a platform forwarding that reached them after the
    # router froze would fail to set the satellite up at all.
    for component in (
        "media_player",
        "notify",
        "image",
        "assist_satellite",
        "assist_pipeline",
        "conversation",
        "intent",
        "stt",
        "tts",
        "ffmpeg",
        "wake_word",
    ):
        assert await async_setup_component(hass_setup, component, {})
    return hass_setup


@pytest.fixture
async def server(integration, socket_enabled) -> AsyncGenerator[str]:
    """hass.http.app served on a real local TCP port; yields the base URL."""
    # aiohttp freezes the router when the runner starts, but HA's own
    # HomeAssistantHTTP.start() replaces the freeze with a no-op because
    # components are discovered after boot and still register routes. The panel's
    # static path is registered from async_setup_entry, so the test server does
    # what start() does - otherwise every test that starts the server before
    # loading the hub fails on a frozen router.
    integration.http.app._router.freeze = lambda: None
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
