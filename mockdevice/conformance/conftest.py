"""Conformance target: a real TLP server from the environment, else the in-process test server.

Against a real Home Assistant:

    TABULA_HA_URL=http://ha:8123 TABULA_STORE=~/.config/tabula-mockdevice/device.json \\
        uv run pytest mockdevice/conformance

The store must belong to a device already paired with that HA (`tabula-mockdevice pair`).
Use a dedicated test device: the suite registers it, sends state and opens extra connections.
"""

from __future__ import annotations

import asyncio
import os
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path

import aiohttp
import pytest
from mockdevice.pairing import refresh_access_token
from mockdevice.store import DeviceStore
from mockdevice.testserver import TestServer

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


@pytest.fixture(autouse=True)
def _real_sockets(socket_enabled):
    """These suites exercise real sockets; PHACC's plugin disables them otherwise."""


@dataclass
class Target:
    url: str
    device_id: str
    refresh_token: str
    local: bool


@pytest.fixture
async def target(tmp_path) -> AsyncIterator[Target]:
    url = os.environ.get("TABULA_HA_URL")
    if url:
        store_path = os.environ.get("TABULA_STORE")
        if not store_path:
            pytest.fail("TABULA_HA_URL needs TABULA_STORE (a paired mock device file)")
        store = DeviceStore.load(Path(store_path).expanduser())
        assert store.refresh_token, f"{store_path} is not paired"
        yield Target(url.rstrip("/"), store.device_id, store.refresh_token, local=False)
        return
    async with TestServer() as server:
        store = DeviceStore.load(tmp_path / "device.json")
        record = server.add_device(store.device_id)
        yield Target(server.url, store.device_id, record.refresh_token, local=True)


@pytest.fixture
async def http() -> AsyncIterator[aiohttp.ClientSession]:
    async with aiohttp.ClientSession() as session:
        yield session


@pytest.fixture
async def token(target: Target, http: aiohttp.ClientSession) -> str:
    return (await refresh_access_token(http, target.url, target.refresh_token)).token
