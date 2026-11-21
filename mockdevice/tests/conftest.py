from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator

import aiohttp
import pytest
from mockdevice.client import Backoff, TLPClient
from mockdevice.device import MockDevice
from mockdevice.store import DeviceStore
from mockdevice.testserver import TestServer


@pytest.fixture
async def server() -> AsyncIterator[TestServer]:
    async with TestServer(poll_interval=1) as srv:
        yield srv
    srv.assert_clean()


@pytest.fixture
async def http() -> AsyncIterator[aiohttp.ClientSession]:
    async with aiohttp.ClientSession() as session:
        yield session


@pytest.fixture
def store(tmp_path, server: TestServer) -> DeviceStore:
    """A device already paired with `server`."""
    store = DeviceStore.load(tmp_path / "device.json")
    store.refresh_token = server.add_device(store.device_id).refresh_token
    store.save()
    return store


class Running:
    """A TLPClient running in the background."""

    def __init__(self, client: TLPClient, task: asyncio.Task) -> None:
        self.client, self.task = client, task

    async def stop(self) -> None:
        self.client.stop()
        with contextlib.suppress(Exception):
            await asyncio.wait_for(self.task, 5)


@pytest.fixture
async def start(server: TestServer, http: aiohttp.ClientSession):
    running: list[Running] = []

    async def _start(store: DeviceStore, device: MockDevice | None = None, **kw) -> Running:
        device = device or MockDevice(base_url=server.url)
        kw.setdefault("backoff", Backoff(base=0.05, cap=0.2))
        client = TLPClient(server.url, store, device, **kw)
        r = Running(client, asyncio.create_task(client.run(http)))
        running.append(r)
        return r

    yield _start
    for r in running:
        await r.stop()
