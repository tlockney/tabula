"""Credential lifecycle: creation, binding, revocation and orphan sweeping.

Covers the packet's Done-when items: a device's credential cannot act for
another device, deleting the device revokes its credential, entry removal
revokes everything, and setup sweeps orphaned credentials (ADR 0001 items 2-4).
"""

from __future__ import annotations

import pytest
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN, HUB_UNIQUE_ID, LOCAL_ONLY
from helpers import Paired, frame_fixture, pair_device, uid, ws_connect
from homeassistant.config_entries import SOURCE_USER
from homeassistant.helpers import device_registry as dr
from mockdevice.pairing import CredentialRevoked

STATE_DELTA = {"brightness": 42}


@pytest.fixture
def expected_lingering_timers(request: pytest.FixtureRequest) -> bool:
    """HA 2026.10+ (aiohttp 3.14) can leave the websocket heartbeat timer
    scheduled when auth revocation closes a live connection: aiohttp 3.14
    stopped cancelling prior heartbeat handles to cut TimerHandle churn, and
    the harness teardown predates an exemption for it. The socket belongs to
    HA's websocket_api - tabula only calls the auth API - so for this one
    test the harness downgrades the leftover timer to a warning. Every other
    test keeps the strict check (ADR 0001 item 8)."""
    return request.node.name == "test_delete_device_revokes_user_and_token"


async def revoke_and_expect_dead(hass_setup, server, session, refresh_token: str) -> None:
    """A revoked credential no longer refreshes: CredentialRevoked."""
    with pytest.raises(CredentialRevoked):
        from mockdevice.pairing import refresh_access_token

        await refresh_access_token(session, server, refresh_token)


# --- the hub config flow --------------------------------------------------------------


async def test_hub_flow_creates_singleton(hass_setup, hub):
    """The user flow created the entry; a second flow aborts already_configured."""
    assert hub.domain == DOMAIN
    assert hub.unique_id == HUB_UNIQUE_ID
    second = await hass_setup.config_entries.flow.async_init(
        DOMAIN, context={"source": SOURCE_USER}
    )
    assert second["type"] == "abort"
    assert second["reason"] == "already_configured"


# --- credential binding ---------------------------------------------------------------


async def test_credential_cannot_act_for_another_device(
    hass_setup, server, hub, session, paired: Paired
):
    """Device A's credential cannot register as device B (PROTOCOL.md 4.3)."""
    other = await pair_device(hass_setup, server, uid())
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=other.device_id))
    await client.close()
    assert reply["success"] is False
    assert reply["error"]["code"] == "unknown_device"
    # Neither device's record changed.
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].current is None
    assert hub_runtime.devices[other.device_id].current is None


async def test_revoked_credential_is_dead(hass_setup, server, hub, session, paired: Paired):
    """After deletion the refresh token no longer works anywhere."""
    registry = dr.async_get(hass_setup)
    registry.async_remove_device(paired.ha_device_id)
    await hass_setup.async_block_till_done()
    await revoke_and_expect_dead(hass_setup, server, session, paired.refresh_token)

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert paired.device_id not in hub_runtime.devices


async def test_delete_device_revokes_user_and_token(
    hass_setup, server, hub, session, paired: Paired
):
    """Removing the HA device removes its user and closes the live socket."""
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    user_id = record.user_id

    client = await ws_connect(session, server, paired.refresh_token)
    await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))

    registry = dr.async_get(hass_setup)
    registry.async_remove_device(paired.ha_device_id)
    await hass_setup.async_block_till_done()
    await hass_setup.async_block_till_done()

    assert paired.device_id not in hub_runtime.devices
    assert await hass_setup.auth.async_get_user(user_id) is None
    # The live socket was closed by the token revocation.
    msg = await client.ws.receive(timeout=5)
    assert msg.type.name != "TEXT"
    await revoke_and_expect_dead(hass_setup, server, session, paired.refresh_token)


async def test_repair_pairing_revokes_old_credential(hass_setup, server, hub, session):
    """A device that re-pairs gets fresh credentials; the old ones die."""
    device_id = uid()
    first = await pair_device(hass_setup, server, device_id)
    second = await pair_device(hass_setup, server, device_id)
    assert second.refresh_token != first.refresh_token
    await revoke_and_expect_dead(hass_setup, server, session, first.refresh_token)

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert len(hub_runtime.devices) == 1
    client = await ws_connect(session, server, second.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=device_id))
    await client.close()
    assert reply["success"], reply


# --- entry lifecycle ---------------------------------------------------------------


async def test_entry_removal_revokes_all_devices(hass_setup, server, hub, session):
    """Removing the hub entry revokes every device user it owned."""
    first = await pair_device(hass_setup, server, uid())
    second = await pair_device(hass_setup, server, uid())
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    user_ids = {r.user_id for r in hub_runtime.devices.values()}

    await hass_setup.config_entries.async_remove(hub.entry_id)
    await hass_setup.async_block_till_done()

    for user_id in user_ids:
        assert await hass_setup.auth.async_get_user(user_id) is None
    await revoke_and_expect_dead(hass_setup, server, session, first.refresh_token)
    await revoke_and_expect_dead(hass_setup, server, session, second.refresh_token)


async def test_reload_rebuilds_device_records(hass_setup, server, hub, session, paired: Paired):
    """Unload + setup reloads device records from entry data; the device registers."""
    assert await hass_setup.config_entries.async_unload(hub.entry_id)
    assert TabulaHub.get(hass_setup) is None
    assert await hass_setup.config_entries.async_setup(hub.entry_id)
    await hass_setup.async_block_till_done()

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    assert record.ha_device_id == paired.ha_device_id
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))
    await client.close()
    assert reply["success"], reply


async def test_setup_sweeps_orphaned_credentials(hass_setup, server, hub, session, paired: Paired):
    """A stored credential whose HA device vanished is revoked at setup."""
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    user_id = record.user_id

    await hass_setup.config_entries.async_unload(hub.entry_id)
    stored = {
        d: {**info, "ha_device_id": "no-such-device"} for d, info in hub.data["devices"].items()
    }
    hass_setup.config_entries.async_update_entry(hub, data={"devices": stored})
    assert await hass_setup.config_entries.async_setup(hub.entry_id)
    await hass_setup.async_block_till_done()

    assert await hass_setup.auth.async_get_user(user_id) is None
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert paired.device_id not in hub_runtime.devices


# --- device users ---------------------------------------------------------------


async def test_device_users_are_local_by_default(hass_setup, server, hub, session, paired: Paired):
    """Device users are in the user group and local_only unless the hub opts out."""
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    user = await hass_setup.auth.async_get_user(hub_runtime.devices[paired.device_id].user_id)
    assert user is not None
    assert user.local_only is True
    from homeassistant.auth.const import GROUP_ID_USER

    assert user.groups[0].id == GROUP_ID_USER


async def test_local_only_option(hass_setup, server, session, paired: Paired):
    """With local_only=False the device user is reachable remotely."""
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    hass_setup.config_entries.async_update_entry(hub_runtime.entry, options={LOCAL_ONLY: False})
    other = await pair_device(hass_setup, server, uid())
    user = await hass_setup.auth.async_get_user(hub_runtime.devices[other.device_id].user_id)
    assert user is not None
    assert user.local_only is False
