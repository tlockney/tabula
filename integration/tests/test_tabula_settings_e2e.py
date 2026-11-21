"""Settings push and event relay end to end, against the real mock device.

The TLPClient library plays the Android app: it registers over the paired
credential, subscribes, and answers set_settings like the real device (the
conformance suite exercises the same code). HA's side must reconcile revision
drift at subscription (PROTOCOL.md 4.2), push options-flow saves, and relay
device events onto the HA bus (plan 5.5).
"""

from __future__ import annotations

import asyncio

import aiohttp
from custom_components.tabula import TabulaHub
from homeassistant.core import Event, callback
from homeassistant.helpers import entity_registry as er
from mockdevice.client import TLPClient
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.store import DeviceStore


async def _run_client(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


async def _connect(
    hass_setup,
    server,
    paired,
    tmp_path,
    settings_revision: int = 0,
) -> tuple[MockDevice, TLPClient, asyncio.Task]:
    """A registered, subscribed mock device connected to the live hub."""
    store = DeviceStore(
        tmp_path / "device.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(
        capabilities=ALL_CAPABILITIES,
        base_url=server,
        settings_revision=settings_revision,
    )
    registered = asyncio.Event()
    client = TLPClient(
        server,
        store,
        device,
        on_registered=lambda result: registered.set(),
    )
    task = asyncio.create_task(_run_client(client))
    await asyncio.wait_for(registered.wait(), 10)

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record = runtime.devices[paired.device_id]
    assert record.current is not None
    for _ in range(250):  # the push reconciles once the subscription opens
        if record.current.subscription_id is not None:
            break
        await asyncio.sleep(0.02)
    assert record.current.subscription_id is not None
    return device, client, task


def _listen(hass, name: str) -> list[Event]:
    seen: list[Event] = []

    # @callback: runs inside async_fire; a plain callable (seen.append) would
    # run in the executor and race the assertions.
    @callback
    def catch(event: Event) -> None:
        seen.append(event)

    hass.bus.async_listen(name, catch)
    return seen


async def _until(predicate, timeout: float = 10.0) -> None:
    """Wait for a condition another task settles (the device's result frame
    reaches HA after the device has applied the document)."""
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def test_entity_write_goes_through_the_settings_store(
    hass_setup, server, hub, paired, tmp_path, hass_storage
):
    """A settings-mapped entity action writes the store, not a bare command.

    Toggling the keep-screen-on switch saves the settings document (revision
    bump), and the push reconciles with the tablet exactly like an
    options-flow edit (F-MGT-01: HA owns every setting).
    """
    device, client, task = await _connect(hass_setup, server, paired, tmp_path)
    eid = er.async_get(hass_setup).async_get_entity_id(
        "switch", "tabula", f"{paired.device_id}_keep_screen_on"
    )
    assert eid is not None
    await hass_setup.services.async_call("switch", "turn_on", {"entity_id": eid}, blocking=True)

    try:
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        record = runtime.devices[paired.device_id]
        # settle: the device's settings and HA's record of the reported
        # revision arrive in separate round trips; wait for both
        for _ in range(200):
            current = record.current
            if (
                device.settings.get("screen", {}).get("keep_screen_on") is True
                and current is not None
                and current.reported_settings_revision == 1
            ):
                break
            await asyncio.sleep(0.05)
        assert device.settings["screen"]["keep_screen_on"] is True

        stored = runtime.settings.get(paired.device_id)
        assert stored is not None
        assert stored.revision == 1
        record = runtime.devices[paired.device_id]
        assert record.current is not None
        await _until(lambda: record.current.reported_settings_revision == 1)
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def test_options_flow_save_reaches_the_device(
    hass_setup, server, hub, paired, tmp_path, hass_storage
):
    """An options-flow save pushes the next revision; the device ACKs it."""
    device, client, task = await _connect(hass_setup, server, paired, tmp_path)

    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": paired.ha_device_id}
    )
    from custom_components.tabula.settings import NO_DEFAULT, SettingsSchema

    values = {
        f.key: ("" if f.default is NO_DEFAULT else f.default)
        for f in SettingsSchema().editable_fields()
    }
    values["screen_brightness"] = 23
    values["identity_name"] = "Kitchen Tablet"
    result = await hass_setup.config_entries.options.async_configure(flow["flow_id"], values)
    assert result["type"] == "create_entry", result

    try:
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        record = runtime.devices[paired.device_id]
        # settle: the device ACK is a separate round trip from the settings
        # landing; wait for the reported revision before asserting it
        for _ in range(200):
            current = record.current
            if (
                device.settings.get("screen", {}).get("brightness") == 23
                and current is not None
                and current.reported_settings_revision == 1
            ):
                break
            await asyncio.sleep(0.05)
        assert device.settings["screen"]["brightness"] == 23
        assert device.settings["identity"]["name"] == "Kitchen Tablet"
        assert device.settings_revision == 1

        stored = runtime.settings.get(paired.device_id)
        assert stored is not None
        assert stored.revision == 1
        # HA knows the device applied it: no push is pending, no drift. The
        # ACK reaches HA after the device applied it, so wait for it.
        record = runtime.devices[paired.device_id]
        assert record.current is not None
        await _until(lambda: record.current.reported_settings_revision == 1)

        # the device persisted the document, like the Android app would (it
        # saves after sending the result)
        await _until(lambda: DeviceStore.load(tmp_path / "device.json").settings_revision == 1)
        saved = DeviceStore.load(tmp_path / "device.json")
        assert saved.settings["screen"]["brightness"] == 23
        assert saved.settings_revision == 1
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def test_subscription_reconciles_revision_drift(
    hass_setup, server, hub, paired, tmp_path, hass_storage
):
    """A device behind HA's revision converges when it subscribes (4.2)."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = await runtime.settings.async_save(paired.device_id, {"screen": {"brightness": 77}})
    assert stored.revision == 1

    device, client, task = await _connect(hass_setup, server, paired, tmp_path, settings_revision=0)
    try:
        for _ in range(200):
            if device.settings_revision == 1:
                break
            await asyncio.sleep(0.05)
        assert device.settings_revision == 1
        assert device.settings["screen"]["brightness"] == 77
        record = runtime.devices[paired.device_id]
        assert record.current is not None
        await _until(lambda: record.current.reported_settings_revision == 1)
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def test_device_ahead_of_ha_still_converges(
    hass_setup, server, hub, paired, tmp_path, hass_storage
):
    """HA is authoritative: a device on a higher revision gets HA's copy (4.5)."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    await runtime.settings.async_save(paired.device_id, {"screen": {"brightness": 55}})

    device, client, task = await _connect(hass_setup, server, paired, tmp_path, settings_revision=7)
    try:
        for _ in range(200):
            if device.settings_revision == 1:
                break
            await asyncio.sleep(0.05)
        assert device.settings_revision == 1
        assert device.settings["screen"]["brightness"] == 55
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def test_no_push_when_the_device_is_current(
    hass_setup, server, hub, paired, tmp_path, hass_storage
):
    """Equal revisions mean silence: the device keeps its own settings."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    await runtime.settings.async_save(paired.device_id, {"screen": {"brightness": 88}})

    device, client, task = await _connect(hass_setup, server, paired, tmp_path, settings_revision=1)
    try:
        await asyncio.sleep(0.5)
        await hass_setup.async_block_till_done()
        # the device was never handed a document: its settings stay its own
        assert device.settings == {}
        assert device.settings_revision == 1
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def test_device_events_reach_the_ha_bus(hass_setup, server, hub, paired, tmp_path):
    """A real device's events ride the subscription onto the HA bus (plan 5.5)."""
    device, client, task = await _connect(hass_setup, server, paired, tmp_path)
    notifications = _listen(hass_setup, "tabula_notification")
    screensavers = _listen(hass_setup, "tabula_screensaver")

    try:
        device.on_event("notification_dismissed", {"tag": "door", "reason": "touch"})
        device.on_event("screensaver_started", {"mode": "clock", "trigger": "idle"})

        for _ in range(200):
            if len(notifications) and len(screensavers):
                break
            await asyncio.sleep(0.05)

        assert len(notifications) == 1
        event = notifications[0]
        assert event.data["type"] == "notification_dismissed"
        assert event.data["tag"] == "door"
        assert event.data["device_id"] == paired.ha_device_id
        assert event.data["tabula_device_id"] == paired.device_id

        assert len(screensavers) == 1
        event = screensavers[0]
        assert event.data["type"] == "screensaver_started"
        assert event.data["mode"] == "clock"
        assert event.data["trigger"] == "idle"
        assert event.data["device_id"] == paired.ha_device_id
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)
