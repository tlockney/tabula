"""Screenshots (P3.13): the upload view, and the capture round trip.

The view tests drive the real HTTP surface against a paired device; the pending
command is a real `screenshot` command the hub sent (its result arrives only
when the test settles it, exactly like a device that has not answered yet).
The round trip runs the mock device end to end (PROTOCOL.md 4.5, 5): the
capture button sends the command, the mock captures, uploads first and only
then answers, the image entity serves the upload, the timestamp sensor moves,
and a capture with the screen off drops back to the placeholder (F-MGT-07).
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator
from dataclasses import dataclass
from typing import Any

import aiohttp
import pytest
from custom_components.tabula import DeviceSession, TabulaHub
from custom_components.tabula.const import DOMAIN, SCREENSHOT_MAX_BYTES, SIGNAL_CAPABILITIES
from custom_components.tabula.screenshots import PLACEHOLDER
from helpers import Paired, pair_device, uid
from homeassistant.components.image import ImageEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.dispatcher import async_dispatcher_send
from mockdevice.client import TLPClient
from mockdevice.constants import SCREENSHOT_JPEG
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.pairing import refresh_access_token
from mockdevice.store import DeviceStore

UPLOAD = "/api/tabula/screenshot"


def entity_id(hass: HomeAssistant, platform: str, paired: Paired, key: str) -> str:
    eid = er.async_get(hass).async_get_entity_id(platform, DOMAIN, f"{paired.device_id}_{key}")
    assert eid is not None, (platform, key)
    return eid


async def _device_token(session: aiohttp.ClientSession, server: str, paired: Paired) -> str:
    access = await refresh_access_token(session, server, paired.refresh_token)
    return access.token


class _Conn:
    """The minimum of an HA ActiveConnection the hub touches."""

    def __init__(self) -> None:
        self.sent: list[tuple[int, dict[str, Any]]] = []

    def send_event(self, msg_id: int, event: dict[str, Any]) -> None:
        self.sent.append((msg_id, event))


@dataclass
class Pending:
    """A screenshot command the hub has sent but no result has answered."""

    command_id: str
    task: asyncio.Task

    def settle_ok(self, hub: TabulaHub, session: DeviceSession) -> None:
        hub.settle(session, {"command_id": self.command_id, "ok": True})


class Registered:
    """A paired device with a current subscribed session, but no WebSocket.

    The hub believes the device is connected, so `async_command` sends; the
    frames land in `_Conn.sent` instead of a socket, and the test settles the
    pending command by hand.
    """

    def __init__(self, hub: TabulaHub, paired: Paired) -> None:
        record = hub.devices[paired.device_id]
        self.record = record
        self.session = DeviceSession(connection=_Conn(), device=record, registered=True)
        self.session.subscription_id = 7
        record.current = self.session
        record.capabilities = [*record.capabilities, "screenshot"]

    async def screenshot(self, hub: TabulaHub) -> Pending:
        task = asyncio.create_task(hub.async_command(self.record, "screenshot", {}))
        await asyncio.sleep(0)  # the pending command is registered synchronously
        assert self.session.connection.sent, "the command went out"
        _, event = self.session.connection.sent[-1]
        return Pending(event["command_id"], task)


# --- the upload view (PROTOCOL.md 5) ---------------------------------------


async def test_upload_requires_authentication(server, session):
    async with session.post(f"{server}{UPLOAD}", data=b"x") as resp:
        assert resp.status == 401


async def test_upload_rejected_for_non_device_user(hass_setup, server, hub, session):
    """Any authenticated HA user that is not a Tabula device user: 403."""
    user = await hass_setup.auth.async_create_user(name="not a tablet")
    refresh = await hass_setup.auth.async_create_refresh_token(user, client_id="test-client")
    token = hass_setup.auth.async_create_access_token(refresh, None)
    async with session.post(
        f"{server}{UPLOAD}",
        data=b"x",
        headers={"Authorization": f"Bearer {token}"},
    ) as resp:
        assert resp.status == 403
        assert (await resp.json())["error"] == "forbidden"


async def test_upload_without_pending_command(hass_setup, server, hub, session, paired):
    token = await _device_token(session, server, paired)
    async with session.post(
        f"{server}{UPLOAD}",
        data=SCREENSHOT_JPEG,
        headers={
            "Authorization": f"Bearer {token}",
            "X-Tabula-Command-Id": "never-sent-0001",
            "Content-Type": "image/jpeg",
        },
    ) as resp:
        assert resp.status == 409
        assert (await resp.json())["error"] == "no_pending_screenshot"


async def test_upload_requires_jpeg_content_type(hass_setup, server, hub, session, paired):
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    registered = Registered(hub_runtime, paired)
    pending = await registered.screenshot(hub_runtime)
    token = await _device_token(session, server, paired)
    async with session.post(
        f"{server}{UPLOAD}",
        data=SCREENSHOT_JPEG,
        headers={
            "Authorization": f"Bearer {token}",
            "X-Tabula-Command-Id": pending.command_id,
            "Content-Type": "application/json",
        },
    ) as resp:
        assert resp.status == 400
        assert (await resp.json())["error"] == "invalid_request"


async def test_upload_refuses_more_than_the_cap(hass_setup, server, hub, session, paired):
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    registered = Registered(hub_runtime, paired)
    pending = await registered.screenshot(hub_runtime)
    token = await _device_token(session, server, paired)
    async with session.post(
        f"{server}{UPLOAD}",
        data=b"x" * (SCREENSHOT_MAX_BYTES + 1),
        headers={
            "Authorization": f"Bearer {token}",
            "X-Tabula-Command-Id": pending.command_id,
            "Content-Type": "image/jpeg",
        },
    ) as resp:
        assert resp.status == 413
        assert (await resp.json())["error"] == "too_large"


async def test_upload_stores_and_answers(hass_setup, server, hub, session, paired):
    """A pending command accepts the upload: stored, schema-valid answer, marker set."""
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    registered = Registered(hub_runtime, paired)
    pending = await registered.screenshot(hub_runtime)
    record = hub_runtime.devices[paired.device_id]
    assert record.screenshot_delivered is False

    token = await _device_token(session, server, paired)
    async with session.post(
        f"{server}{UPLOAD}",
        data=SCREENSHOT_JPEG,
        headers={
            "Authorization": f"Bearer {token}",
            "X-Tabula-Command-Id": pending.command_id,
            "Content-Type": "image/jpeg",
        },
    ) as resp:
        assert resp.status == 200
        body = await resp.json()
    hub_runtime.tlp.validate("http_screenshot_response", body)
    assert body == {"status": "stored", "bytes": len(SCREENSHOT_JPEG)}
    assert record.screenshot_delivered is True
    assert hub_runtime.screenshots.image(paired.device_id) == SCREENSHOT_JPEG

    # The command still settles normally afterwards (upload first, then result).
    pending.settle_ok(hub_runtime, registered.session)
    assert await pending.task == {}


# --- the capture flow (button -> command -> upload -> entities) --------------


@dataclass
class Tablet:
    """A paired mock tablet, registered and subscribed through the TLPClient."""

    paired: Paired
    device: MockDevice
    client: TLPClient


async def _run(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


@pytest.fixture
async def tablet(hass_setup, server, hub, session, tmp_path) -> AsyncGenerator[Tablet]:
    paired = await pair_device(hass_setup, server, uid())
    store = DeviceStore(
        tmp_path / "device.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(capabilities=ALL_CAPABILITIES, base_url=server)
    client = TLPClient(server, store, device)
    task = asyncio.create_task(_run(client))
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None

    async def wait_until(predicate, timeout: float = 5.0) -> None:
        async with asyncio.timeout(timeout):
            while not predicate():
                await asyncio.sleep(0.01)

    await wait_until(
        lambda: (
            (r := hub_runtime.devices.get(paired.device_id)) is not None
            and r.current is not None
            and r.current.subscription_id is not None
        )
    )
    await hass_setup.async_block_till_done()
    try:
        yield Tablet(paired, device, client)
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def _image_entity(hass: HomeAssistant, paired: Paired) -> ImageEntity:
    eid = entity_id(hass, "image", paired, "screenshot")
    component = hass.data["entity_components"]["image"]
    entity = component.get_entity(eid)
    assert isinstance(entity, ImageEntity), eid
    return entity


async def test_capture_button_round_trip(hass_setup, tablet):
    """Button -> command -> capture -> upload -> result; entities follow (P3.13)."""
    hass, paired, device = hass_setup, tablet.paired, tablet.device
    hub = TabulaHub.get(hass)
    assert hub is not None

    button = entity_id(hass, "button", paired, "capture_screenshot")
    sensor = entity_id(hass, "sensor", paired, "last_capture")

    await hass.services.async_call("button", "press", {"entity_id": button}, blocking=True)
    await hass.async_block_till_done()

    # The upload arrived and was stored; the marker said the screen was on.
    record = hub.devices[paired.device_id]
    assert record.screenshot_delivered is True
    assert hub.screenshots.image(paired.device_id) == SCREENSHOT_JPEG

    # The timestamp sensor moved (F-HA-12)...
    state = hass.states.get(sensor)
    assert state is not None and state.state != "unknown"

    # ...the image entity serves the capture, with the same timestamp...
    image = await _image_entity(hass, paired)
    assert await image.async_image() == SCREENSHOT_JPEG
    assert image.image_last_updated is not None

    # ...and an ordinary fetch does not move the sensor (the packet's rule).
    await image.async_image()
    await hass.async_block_till_done()
    assert hass.states.get(sensor).state == state.state

    # A second capture overwrites the one stored image in place (item 3).
    await hass.services.async_call("button", "press", {"entity_id": button}, blocking=True)
    assert hub.screenshots.image(paired.device_id) == SCREENSHOT_JPEG


async def test_screen_off_serves_the_placeholder(hass_setup, tablet):
    """Screen off at command time: ok, no upload, placeholder (F-MGT-07)."""
    hass, paired, device = hass_setup, tablet.paired, tablet.device
    hub = TabulaHub.get(hass)
    assert hub is not None

    # A first capture with the screen on, so there is an image to drop.
    button = entity_id(hass, "button", paired, "capture_screenshot")
    await hass.services.async_call("button", "press", {"entity_id": button}, blocking=True)
    assert hub.screenshots.image(paired.device_id) == SCREENSHOT_JPEG

    device.update(screen_on=False)
    await hass.async_block_till_done()
    await hass.services.async_call("button", "press", {"entity_id": button}, blocking=True)

    # The command answered ok with no upload: the marker never set, the stored
    # image was dropped, and the entity serves the placeholder.
    record = hub.devices[paired.device_id]
    assert record.screenshot_delivered is False
    assert hub.screenshots.image(paired.device_id) is None
    image = await _image_entity(hass, paired)
    assert await image.async_image() == PLACEHOLDER
    # The timestamp still moved: a capture ran, it just found the screen off.
    sensor = entity_id(hass, "sensor", paired, "last_capture")
    assert hass.states.get(sensor).state != "unknown"


async def test_last_capture_survives_a_reload(hass_setup, tablet, hub):
    """The timestamp is persisted in the entry, so a reload keeps it (F-HA-12)."""
    hass, paired = hass_setup, tablet.paired
    button = entity_id(hass, "button", paired, "capture_screenshot")
    await hass.services.async_call("button", "press", {"entity_id": button}, blocking=True)
    sensor = entity_id(hass, "sensor", paired, "last_capture")
    before = hass.states.get(sensor).state
    assert before != "unknown"

    assert await hass.config_entries.async_reload(hub.entry_id)
    hub_runtime = TabulaHub.get(hass)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].last_capture is not None
    # A hub reload does not cut the device's socket (PROTOCOL.md 4.9), so the
    # app would reconnect on its next restart: drop the client's connection
    # and let the mock re-register on the reloaded hub.
    conn = tablet.client._conn
    if conn is not None:
        await conn.ws.close()
    async with asyncio.timeout(10):
        while True:
            record = hub_runtime.devices[paired.device_id]
            if record.current is not None:
                await hass.async_block_till_done()
                break
            await asyncio.sleep(0.05)
    # The reloaded sensor reports the persisted value.
    sensor = entity_id(hass, "sensor", paired, "last_capture")
    assert hass.states.get(sensor).state == before


async def test_entities_need_the_capability(hass_setup, server, hub, session):
    """No screenshot capability: no capture button, image or timestamp sensor."""
    hass = hass_setup
    paired = await pair_device(hass, server, uid())
    hub_runtime = TabulaHub.get(hass)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    record.capabilities = [c for c in record.capabilities if c != "screenshot"]
    async_dispatcher_send(hass, SIGNAL_CAPABILITIES, record)
    await hass.async_block_till_done()

    registry = er.async_get(hass)
    assert (
        registry.async_get_entity_id("button", DOMAIN, f"{paired.device_id}_capture_screenshot")
        is None
    )
    assert registry.async_get_entity_id("image", DOMAIN, f"{paired.device_id}_screenshot") is None
    assert registry.async_get_entity_id("sensor", DOMAIN, f"{paired.device_id}_last_capture") is None
