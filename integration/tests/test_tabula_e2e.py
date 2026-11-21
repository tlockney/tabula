"""End to end: the real mock device binaries against the real integration.

The pairing CLI runs as a subprocess against the live test server, the TLPClient
library registers a device and streams state, and the mock conformance suite is
replayed against the integration through a paired store (PROTOCOL.md sections 2-4).
"""

from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import aiohttp
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from helpers import pair_device, uid
from mockdevice.client import TLPClient
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.store import DeviceStore

REPO_ROOT = Path(__file__).resolve().parents[2]


async def test_cli_pair_end_to_end(hass_setup, server, hub, tmp_path):
    """`tabula-mockdevice pair` pairs through the real views and saves the store."""
    store_path = tmp_path / "device.json"
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-m",
        "mockdevice.cli",
        "--ha",
        server,
        "--store",
        str(store_path),
        "pair",
        "--name",
        "Kitchen Tablet",
        "--model",
        "KFTRPWI",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env={**os.environ, "PYTHONUNBUFFERED": "1"},
    )
    assert process.stdout is not None

    # The CLI prints the code it shows; type it into the discovery flow.
    code = None
    try:
        while True:
            line = await asyncio.wait_for(process.stdout.readline(), 30)
            if not line:
                raise AssertionError(f"CLI exited before showing a code: rc={await process.wait()}")
            text = line.decode()
            if text.startswith("Pairing code:"):
                code = text.split(":", 1)[1].strip()
                break
        assert code is not None

        flows = [
            f
            for f in hass_setup.config_entries.flow.async_progress_by_handler(DOMAIN)
            if f["context"].get("device_id")
        ]
        result = await hass_setup.config_entries.flow.async_configure(
            flows[0]["flow_id"], {"code": code}
        )
        await hass_setup.async_block_till_done()
        assert result["type"] == "abort" and result["reason"] == "paired"

        stdout, _ = await asyncio.wait_for(process.communicate(), 30)
        assert process.returncode == 0, stdout.decode()
        assert b"Paired." in stdout
    finally:
        if process.returncode is None:
            process.kill()

    store = DeviceStore.load(store_path)
    assert store.refresh_token
    assert store.ha_device_id
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert store.device_id in hub_runtime.devices


async def test_client_registers_and_streams_state(hass_setup, server, hub, tmp_path):
    """The TLPClient library registers, subscribes and streams a state delta."""
    paired = await pair_device(hass_setup, server, uid())
    store = DeviceStore(
        tmp_path / "device.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(capabilities=ALL_CAPABILITIES, base_url=server)
    registered = asyncio.Event()

    client = TLPClient(
        server,
        store,
        device,
        on_registered=lambda result: registered.set(),
    )
    task = asyncio.create_task(_run_client(client))
    try:
        await asyncio.wait_for(registered.wait(), 10)
        hub_runtime = TabulaHub.get(hass_setup)
        assert hub_runtime is not None
        record = hub_runtime.devices[paired.device_id]
        assert record.current is not None

        device.update(screen_on=False, brightness=7)
        for _ in range(100):
            if record.state.get("brightness") == 7:
                break
            await asyncio.sleep(0.05)
        assert record.state["screen_on"] is False
        assert record.state["brightness"] == 7
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def _run_client(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


async def test_conformance_suite_against_integration(hass_setup, server, hub, tmp_path):
    """The mock conformance suite passes against this integration, over the wire."""
    paired = await pair_device(hass_setup, server, uid())
    store_path = tmp_path / "conformance.json"
    DeviceStore(
        store_path,
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    ).save()

    env = {
        **os.environ,
        "TABULA_HA_URL": server,
        "TABULA_STORE": str(store_path),
    }
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-m",
        "pytest",
        "-q",
        "-p",
        "no:cacheprovider",
        "mockdevice/conformance",
        cwd=REPO_ROOT,
        env=env,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    stdout, _ = await asyncio.wait_for(process.communicate(), 300)
    text = stdout.decode()
    assert process.returncode == 0, text[-4000:]
