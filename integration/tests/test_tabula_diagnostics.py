"""Diagnostics carry device and pairing state, and never credentials."""

from __future__ import annotations

import json

from custom_components.tabula.diagnostics import (
    async_get_config_entry_diagnostics,
    async_get_device_diagnostics,
)
from helpers import Paired, frame_fixture, post_pair, uid, wait_for_flow, ws_connect
from homeassistant.config_entries import ConfigEntry
from homeassistant.helpers import device_registry as dr


async def test_entry_diagnostics_describe_devices(hass_setup, server, hub, session, paired: Paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))

    entry: ConfigEntry = hub
    diagnostics = await async_get_config_entry_diagnostics(hass_setup, entry)
    assert diagnostics["devices"][0]["device_id"] == paired.device_id
    assert diagnostics["devices"][0]["state"]["screen_on"] is not None
    assert diagnostics["devices"][0]["connected"] is True

    await client.close()


async def test_diagnostics_contain_no_credentials(hass_setup, server, hub, session, paired: Paired):
    """Pairing code, nonce and refresh token never appear in diagnostics."""
    attempt, _ = await post_pair(session, server, uid())
    await wait_for_flow(hass_setup, attempt.device_id)

    entry: ConfigEntry = hub
    diagnostics = await async_get_config_entry_diagnostics(hass_setup, entry)
    text = json.dumps(diagnostics, default=str)

    assert attempt.code not in text
    assert attempt.nonce not in text
    assert paired.refresh_token not in text
    pending = diagnostics["pending_pairings"][0]
    assert pending == {"device_id": attempt.device_id, "attempts": 0, "confirmed": False}


async def test_device_diagnostics(hass_setup, server, hub, session, paired: Paired):
    registry = dr.async_get(hass_setup)
    device = registry.async_get(paired.ha_device_id)
    entry: ConfigEntry = hub
    diagnostics = await async_get_device_diagnostics(hass_setup, entry, device)
    assert diagnostics["device"]["device_id"] == paired.device_id
    assert paired.refresh_token not in json.dumps(diagnostics, default=str)


async def test_diagnostics_without_hub(hass_setup, hub):
    entry: ConfigEntry = hub
    hass_setup.data.pop("tabula")
    diagnostics = await async_get_config_entry_diagnostics(hass_setup, entry)
    assert diagnostics == {"hub": "not loaded"}
