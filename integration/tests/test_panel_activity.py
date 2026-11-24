"""The Activity tab's entity ids in `tabula/panel_devices` (P5.5).

The panel reads the entity registry only through P5.2's payload, so the suffixes
the tab matches (`_capture_screenshot`, `_last_capture`, the `image` platform)
have to be the ids the integration actually creates. A device without the
screenshot capability must carry none of them: the tab renders the capability
note instead of a control HA would refuse.
"""

from __future__ import annotations

from typing import Any

from helpers import WS, Paired, frame_fixture, ws_connect
from homeassistant.auth.const import GROUP_ID_ADMIN
from homeassistant.core import HomeAssistant

COMMAND = {"type": "tabula/panel_devices"}

# Every capability that gates an Activity tab entity, plus the ones the default
# register fixture already declares.
SCREENSHOT_CAPABILITIES = ["screen", "screenshot", "go_home"]


async def admin_client(hass: HomeAssistant, server: str, session) -> WS:
    """A WebSocket client authenticated as an admin (the panel's audience)."""
    user = await hass.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass.auth.async_create_refresh_token(user)
    return await ws_connect(session, server, refresh.token)


async def register(
    hass: HomeAssistant, session, server: str, paired: Paired, **overrides: Any
) -> WS:
    """Register the paired device with the given frame overrides, socket kept open."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(
        frame_fixture("register", id=2, device_id=paired.device_id, **overrides)
    )
    assert reply["success"], reply
    await hass.async_block_till_done()
    return client


async def snapshot(hass: HomeAssistant, session, server: str) -> dict[str, Any]:
    """The one device entry `tabula/panel_devices` returns."""
    admin = await admin_client(hass, server, session)
    try:
        result = await admin.reply({"id": 1, **COMMAND})
    finally:
        await admin.close()
    assert result["success"] is True, result
    devices = result["result"]
    assert len(devices) == 1, devices
    return devices[0]


def ids(entry: dict[str, Any], platform: str) -> list[str]:
    """The entity ids the payload groups under one platform."""
    return [entity["entity_id"] for entity in entry["entities"].get(platform, [])]


def names(entry: dict[str, Any], platform: str) -> dict[str, str]:
    """Entity id -> the payload's name for it."""
    return {entity["entity_id"]: entity["name"] for entity in entry["entities"].get(platform, [])}


async def test_capability_limited_device_carries_no_screenshot_ids(
    hass_setup, server, hub, session, paired
):
    """No screenshot capability: no image, no capture button, no capture sensor."""
    device = await register(hass_setup, session, server, paired)
    entry = await snapshot(hass_setup, session, server)
    await device.close()

    assert "image" not in entry["entities"]
    assert not any(eid.endswith("_capture_screenshot") for eid in ids(entry, "button"))
    assert not any(eid.endswith("_last_capture") for eid in ids(entry, "sensor"))
    # The ungated command buttons are there, so the tab still has commands.
    assert any(eid.endswith("_reload_dashboard") for eid in ids(entry, "button"))
    assert any(eid.endswith("_restart_app") for eid in ids(entry, "button"))
    assert any(eid.endswith("_clear_cache") for eid in ids(entry, "button"))
    # go_home is capability-gated, like the capture button.
    assert not any(eid.endswith("_go_to_start_page") for eid in ids(entry, "button"))


async def test_screenshot_device_carries_the_ids_the_tab_reads(
    hass_setup, server, hub, session, paired
):
    """With the capability, the ids the tab matches exist and are named."""
    device = await register(
        hass_setup,
        session,
        server,
        paired,
        capabilities=SCREENSHOT_CAPABILITIES,
    )
    entry = await snapshot(hass_setup, session, server)
    await device.close()

    images = ids(entry, "image")
    assert len(images) == 1
    assert images[0].endswith("_screenshot")

    buttons = names(entry, "button")
    capture = [eid for eid in buttons if eid.endswith("_capture_screenshot")]
    assert len(capture) == 1
    assert buttons[capture[0]] == "Capture screenshot"
    start_page = [eid for eid in buttons if eid.endswith("_go_to_start_page")]
    assert len(start_page) == 1
    assert buttons[start_page[0]] == "Go to start page"

    sensors = names(entry, "sensor")
    last_capture = [eid for eid in sensors if eid.endswith("_last_capture")]
    assert len(last_capture) == 1
    assert sensors[last_capture[0]] == "Last capture"

    # The capabilities the panel uses to explain an absent control travel too.
    assert "screenshot" in entry["capabilities"]

    # Before the first capture both entities say "unknown", which is the tab's
    # not-captured-yet state; the payload carries that live state, not a guess.
    image_state = entry["entities"]["image"][0]["state"]
    assert image_state in {"unknown", "unavailable"}
    assert sensors[last_capture[0]] == "Last capture"
