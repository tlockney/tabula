"""The options flow: a schema-generated settings editor (plan 5.6, F-MGT-03).

Step one picks a paired tablet, step two is a flat form generated from
schema/settings.schema.json. Submitting stores the next revision, renames the
device when identity.name changed, and pushes to a connected device. The flow
never changes the entry's options - settings live in the SettingsStore.
"""

from __future__ import annotations

import pytest
import voluptuous as vol

from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from custom_components.tabula.settings import NO_DEFAULT, SettingsSchema
from helpers import Paired, frame_fixture, ws_connect
from homeassistant.data_entry_flow import InvalidData
from homeassistant.helpers import device_registry as dr


def form_values(schema: SettingsSchema, current: dict | None = None, **overrides) -> dict:
    """What the frontend submits: every field's shown value, plus overrides.

    Leaves without a default are submitted blank - that is the frontend's
    rendering of "unset".
    """
    values = {}
    for field in schema.editable_fields():
        shown = "" if field.default is NO_DEFAULT else field.default
        values[field.key] = overrides.pop(field.key, shown)
    assert not overrides, f"unknown fields: {overrides}"
    return values


def form_keys(result: dict) -> list[str]:
    """The flat field keys of a shown form, in order."""
    return [marker.schema for marker in result["data_schema"].schema]


async def test_options_aborts_without_devices(hass_setup, hub):
    """No paired tablets: nothing to edit."""
    result = await hass_setup.config_entries.options.async_init(hub.entry_id)
    assert result["type"] == "abort"
    assert result["reason"] == "no_devices"


async def test_options_form_is_generated_from_the_schema(
    hass_setup, server, hub, session, paired
):
    """One field per editable leaf, schema order, schema defaults."""
    result = await hass_setup.config_entries.options.async_init(hub.entry_id)
    assert result["type"] == "form"
    assert result["step_id"] == "init"

    result = await hass_setup.config_entries.options.async_configure(
        result["flow_id"], {"device_id": paired.ha_device_id}
    )
    assert result["type"] == "form"
    assert result["step_id"] == "device"

    schema = SettingsSchema()
    assert form_keys(result) == [f.key for f in schema.editable_fields()]
    markers = {marker.schema: marker for marker in result["data_schema"].schema}
    unset = vol.Optional("_probe").default  # voluptuous's "no default" sentinel
    for field in schema.editable_fields():
        if field.default is NO_DEFAULT:
            # identity and connection have no default: suggested empty, not set
            assert markers[field.key].default is unset
        else:
            # this voluptuous wraps marker defaults in a lambda
            assert markers[field.key].default() == field.default
    # the description placeholders name the device
    assert result["description_placeholders"]["name"]


async def test_options_submit_saves_renames_and_preserves_options(
    hass_setup, server, hub, session, paired, hass_storage
):
    """Submit stores revision 1, applies identity.name, leaves options alone."""
    options_before = {**hub.options}

    result = await hass_setup.config_entries.options.async_init(hub.entry_id)
    result = await hass_setup.config_entries.options.async_configure(
        result["flow_id"], {"device_id": paired.ha_device_id}
    )
    schema = SettingsSchema()
    values = form_values(schema, screen_brightness=23, identity_name="Kitchen")
    result = await hass_setup.config_entries.options.async_configure(
        result["flow_id"], values
    )
    assert result["type"] == "create_entry", result
    await hass_setup.async_block_till_done()

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.revision == 1
    assert stored.settings["screen"]["brightness"] == 23
    assert stored.settings["identity"]["name"] == "Kitchen"
    assert stored.settings["screensaver"]["mode"] == "clock"

    # identity.name is the device's display name (F-MGT-03)
    entry = dr.async_get(hass_setup).async_get(paired.ha_device_id)
    assert entry is not None
    assert entry.name == "Kitchen"
    # the entry's options are untouched: settings live in the store
    assert hub.options == options_before


async def test_options_edit_after_save_bumps_the_revision(
    hass_setup, server, hub, session, paired, hass_storage
):
    """A second submit with one changed key is revision 2, not a new document."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": paired.ha_device_id}
    )
    schema = SettingsSchema()
    await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, screen_brightness=23)
    )

    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": paired.ha_device_id}
    )
    # the stored value is suggested back, not the schema default
    markers = {m.schema: m for m in flow["data_schema"].schema}
    assert markers["screen_brightness"].default() == 23

    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, screen_brightness=24)
    )
    assert result["type"] == "create_entry", result

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.revision == 2
    assert stored.settings["screen"]["brightness"] == 24


async def test_options_submit_pushes_to_a_connected_device(
    hass_setup, server, hub, session, paired, hass_storage
):
    """A connected device with an older revision gets the new document."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))
    assert reply["success"], reply
    subscribed = await client.reply(frame_fixture("subscribe", id=3))
    assert subscribed["success"], subscribed

    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": paired.ha_device_id}
    )
    schema = SettingsSchema()
    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, screen_brightness=31)
    )
    assert result["type"] == "create_entry", result

    # the push lands on the open subscription as a set_settings event frame
    pushed = None
    for _ in range(100):
        try:
            frame = await client.recv(timeout=0.1)
        except TimeoutError:
            await hass_setup.async_block_till_done()
            continue
        if frame.get("type") == "event":
            pushed = frame["event"]
            break
    await client.close()
    assert pushed is not None, "no set_settings event arrived"
    assert pushed["command"] == "set_settings"
    assert pushed["args"]["revision"] == 1
    assert pushed["args"]["schema_version"] == 1
    assert pushed["args"]["settings"]["screen"]["brightness"] == 31


async def test_options_invalid_input_reshows_the_form(
    hass_setup, server, hub, session, paired
):
    """Out-of-range values never reach the flow; nothing is stored.

    The frontend enforces bounds while typing; the vol schema is a hard guard,
    so a bad value raises InvalidData from the flow manager rather than
    reshowing. The JSON re-validation inside the flow covers what vol cannot
    express and reshow with field errors instead (next test).
    """
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": paired.ha_device_id}
    )
    schema = SettingsSchema()
    with pytest.raises(InvalidData):
        await hass_setup.config_entries.options.async_configure(
            flow["flow_id"], form_values(schema, screen_brightness=500)
        )

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert runtime.settings.get(paired.device_id) is None


async def test_options_schema_rejection_reshows_with_field_errors(
    hass_setup, server, hub, session, paired
):
    """What vol cannot express, the JSON schema rejects and the form reshow.

    A malformed ha_url passes the vol string validator; the JSON schema
    refuses it and the flow maps the problem onto the offending field.
    """
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": paired.ha_device_id}
    )
    schema = SettingsSchema()
    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, connection_ha_url="not a url")
    )
    assert result["type"] == "form", result
    assert result["step_id"] == "device"
    assert result["errors"].get("connection_ha_url") == "invalid_settings", result["errors"]

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert runtime.settings.get(paired.device_id) is None


async def test_options_unknown_device_aborts(hass_setup, server, hub, session, paired):
    """A device id the hub does not know aborts the flow."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": "0f0f0f0f-0f0f-0f0f-0f0f-0f0f0f0f0f0f"}
    )
    assert result["type"] == "abort"
    assert result["reason"] == "unknown_device"


async def test_options_hub_not_loaded_aborts(hass_setup, server, hub, session, paired):
    """The editor depends on the running hub; without it the flow aborts."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    removed = hass_setup.data.pop(DOMAIN)
    try:
        result = await hass_setup.config_entries.options.async_configure(
            flow["flow_id"], {"device_id": paired.ha_device_id}
        )
        assert result["type"] == "abort"
        assert result["reason"] == "hub_not_loaded"
    finally:
        hass_setup.data[DOMAIN] = removed
