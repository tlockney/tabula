"""Shared test helpers: pairing driver, claim, and an authenticating WS client.

Everything here drives the real HTTP and WebSocket surfaces - no mocks of the
integration itself. The mock device's own modules are reused so the wire format
is produced by the same code the conformance suite exercises.
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

import aiohttp
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from homeassistant.core import HomeAssistant
from mockdevice.codec import Codec
from mockdevice.pairing import (
    PairingAttempt,
    claim,
    refresh_access_token,
    request_pairing,
)

CODEC = Codec()
FIXTURES = Path(__file__).resolve().parents[2] / "schema" / "fixtures" / "tlp" / "v1"


def uid() -> str:
    """A schema-valid device id: a random UUID v4, like the Android app's."""
    return str(uuid4())


def frame_fixture(name: str, **overrides: object) -> dict:
    """A golden TLP fixture, ready to send over the wire, with fields overridden."""
    frame = json.loads((FIXTURES / f"{name}.json").read_text())
    frame.update(overrides)
    return frame


@dataclass
class Paired:
    """Result of a completed pairing."""

    device_id: str
    refresh_token: str
    ha_device_id: str


async def post_pair(
    session: aiohttp.ClientSession,
    url: str,
    device_id: str,
    name: str = "Office Kiosk",
    model: str = "KFTRPWI",
) -> tuple[PairingAttempt, dict]:
    """The device side of pairing: POST /api/tabula/pair."""
    return await request_pairing(session, CODEC, url, device_id, name=name, model=model)


async def post_claim(
    session: aiohttp.ClientSession, url: str, attempt: PairingAttempt, response: dict
) -> dict:
    """The device side of claiming: poll POST /api/tabula/pair/claim."""
    return await claim(session, CODEC, url, attempt, response["poll_interval"], timeout=5.0)


async def wait_for_flow(hass: HomeAssistant, device_id: str, timeout: float = 5.0) -> str:
    """Wait for the discovery flow the pair view created for this device."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        flows = [
            f
            for f in hass.config_entries.flow.async_progress_by_handler(DOMAIN)
            if f["context"].get("device_id") == device_id
        ]
        if flows:
            return flows[0]["flow_id"]
        await asyncio.sleep(0.01)
    raise AssertionError(f"no discovery flow appeared for {device_id}")


async def confirm_code(hass: HomeAssistant, device_id: str, code: str) -> dict:
    """The user side: type the code into the pending discovery flow."""
    flow_id = await wait_for_flow(hass, device_id)
    result = await hass.config_entries.flow.async_configure(flow_id, {"code": code})
    await hass.async_block_till_done()
    return result


async def pair_device(
    hass: HomeAssistant,
    url: str,
    device_id: str,
    *,
    code: str | None = None,
    name: str = "Office Kiosk",
    model: str = "KFTRPWI",
) -> Paired:
    """Full happy-path pairing over the real HTTP surface.

    With ``code`` set, that code is typed instead of the correct one.
    """
    async with aiohttp.ClientSession() as session:
        attempt, response = await post_pair(session, url, device_id, name=name, model=model)
        typed = code if code is not None else attempt.code
        result = await confirm_code(hass, device_id, typed)
        assert result["type"] == "abort", result
        assert result["reason"] == "paired", result
        claimed = await post_claim(session, url, attempt, response)
    hub = TabulaHub.get(hass)
    assert hub is not None
    assert hub.pending.get(device_id) is None, "claim must consume the pending entry"
    return Paired(
        device_id=device_id,
        refresh_token=claimed["credential"]["refresh_token"],
        ha_device_id=claimed["ha_device_id"],
    )


class WS:
    """Authenticating WebSocket client, in the same spirit as the mock's."""

    def __init__(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        self.ws = ws
        self.next_id = 1

    async def hello(self, token: str) -> None:
        msg = await self.ws.receive_json()
        assert msg["type"] == "auth_required", msg
        await self.ws.send_json({"type": "auth", "access_token": token})
        msg = await self.ws.receive_json()
        assert msg["type"] == "auth_ok", msg

    async def send(self, frame: dict) -> None:
        await self.ws.send_json(frame)

    async def recv(self, timeout: float = 5.0) -> dict:
        """Next text message; fails on ws-level errors or close."""
        msg = await asyncio.wait_for(self.ws.receive(), timeout)
        if msg.type != aiohttp.WSMsgType.TEXT:
            raise AssertionError(f"ws closed: {msg}")
        return msg.json()

    async def reply(self, frame: dict) -> dict:
        """Send a frame, return the next non-event reply (skips subscription events)."""
        await self.send(frame)
        while True:
            got = await self.recv()
            if not got["type"].startswith("event"):
                return got

    async def close(self) -> None:
        await self.ws.close()


async def ws_connect(session: aiohttp.ClientSession, url: str, refresh_token: str) -> WS:
    """Authenticate over the real auth endpoints and open a tabula websocket."""
    access = await refresh_access_token(session, url, refresh_token)
    ws = await session.ws_connect(f"{url}/api/websocket", max_msg_size=0)
    client = WS(ws)
    await client.hello(access.token)
    return client
