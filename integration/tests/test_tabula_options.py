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
from custom_components.tabula.options_flow import array_to_text
from custom_components.tabula.settings import NO_DEFAULT, SettingsSchema
from helpers import frame_fixture, ws_connect
from homeassistant.data_entry_flow import InvalidData
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.selector import TextSelector


def form_values(schema: SettingsSchema, current: dict | None = None, **overrides) -> dict:
    """What the frontend submits: every field's shown value, plus overrides.

    Leaves without a default are submitted blank - that is the frontend's
    rendering of "unset". List leaves (settings v2) submit their shown editor
    text; a raw list is also accepted by the flow's parser.
    """
    values = {}
    for field in schema.editable_fields():
        if field.type == "array":
            shown = array_to_text(field, field.default)
        else:
            shown = "" if field.default is NO_DEFAULT else field.default
        values[field.key] = overrides.pop(field.key, shown)
    assert not overrides, f"unknown fields: {overrides}"
    return values


def form_keys(result: dict) -> list[str]:
    """The flat field keys of a shown form, in order."""
    return [marker.schema for marker in result["data_schema"].schema]


async def open_device_form(hass_setup, entry_id: str, ha_device_id: str) -> dict:
    """Open the options menu and pick a tablet, as the UI does (P9.6's menu)."""
    flow = await hass_setup.config_entries.options.async_init(entry_id)
    assert flow["type"] == "menu", flow
    assert flow["step_id"] == "init"
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"next_step_id": "settings"}
    )
    assert flow["type"] == "form", flow
    assert flow["step_id"] == "settings"
    return await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"device_id": ha_device_id}
    )


async def test_options_aborts_without_devices(hass_setup, hub):
    """No paired tablets: nothing to edit."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    assert flow["type"] == "menu"
    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"next_step_id": "settings"}
    )
    assert result["type"] == "abort"
    assert result["reason"] == "no_devices"


async def test_options_form_is_generated_from_the_schema(hass_setup, server, hub, session, paired):
    """One field per editable leaf, schema order, schema defaults."""
    result = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    assert result["type"] == "form"
    assert result["step_id"] == "device"

    schema = SettingsSchema()
    assert form_keys(result) == [f.key for f in schema.editable_fields()]
    data_schema = result["data_schema"].schema  # voluptuous: {marker: validator}
    markers = {marker.schema: marker for marker in data_schema}
    validators = {marker.schema: data_schema[marker] for marker in data_schema}
    unset = vol.Optional("_probe").default  # voluptuous's "no default" sentinel
    for field in schema.editable_fields():
        if field.type == "array":
            # list leaves show their editor text ("" for the empty default)
            # and validate through a multiline text selector
            assert markers[field.key].default() == array_to_text(field, field.default)
            assert isinstance(validators[field.key], TextSelector)
        elif field.default is NO_DEFAULT:
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

    result = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    values = form_values(schema, screen_brightness=23, identity_name="Kitchen")
    result = await hass_setup.config_entries.options.async_configure(result["flow_id"], values)
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
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, screen_brightness=23)
    )

    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
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

    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
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
    # the paired device registered at schema_version 1 (the register fixture),
    # so the push is translated DOWN: v1 declared, v2-only keys pruned
    assert pushed["args"]["schema_version"] == 1
    assert pushed["args"]["settings"]["screen"]["brightness"] == 31
    assert set(pushed["args"]["settings"]["screensaver"]) == {
        "enabled",
        "idle_timeout",
        "mode",
        "brightness",
    }
    assert "widgets" not in pushed["args"]["settings"]
    assert "schedule" not in pushed["args"]["settings"]


async def test_options_invalid_input_reshows_the_form(hass_setup, server, hub, session, paired):
    """Out-of-range values never reach the flow; nothing is stored.

    The frontend enforces bounds while typing; the vol schema is a hard guard,
    so a bad value raises InvalidData from the flow manager rather than
    reshowing. The JSON re-validation inside the flow covers what vol cannot
    express and reshow with field errors instead (next test).
    """
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
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
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
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


async def test_options_submit_stores_entity_list(hass_setup, server, hub, session, paired):
    """A list of entity ids: one per line in the text editor, stored as a list."""
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    values = form_values(
        schema,
        at_a_glance_entities="binary_sensor.front_door\nlock.garage\n\ncover.shop\n",
    )
    result = await hass_setup.config_entries.options.async_configure(flow["flow_id"], values)
    assert result["type"] == "create_entry", result
    await hass_setup.async_block_till_done()

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    # blank lines are dropped, order kept (F-AAG-01)
    assert stored.settings["at_a_glance"]["entities"] == [
        "binary_sensor.front_door",
        "lock.garage",
        "cover.shop",
    ]
    # and the stored value is suggested back as the editor text
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    markers = {m.schema: m for m in flow["data_schema"].schema}
    assert markers["at_a_glance_entities"].default() == "\n".join(
        ["binary_sensor.front_door", "lock.garage", "cover.shop"]
    )


async def test_options_submit_stores_schedule_slots(hass_setup, server, hub, session, paired):
    """The schedule's slots: a YAML list of objects, validated on submit."""
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    slots_yaml = (
        "- start: '07:00'\n  mode: photos\n  brightness: 40\n- start: '22:30'\n  mode: black\n"
    )
    values = form_values(schema, schedule_enabled=True, schedule_slots=slots_yaml)
    result = await hass_setup.config_entries.options.async_configure(flow["flow_id"], values)
    assert result["type"] == "create_entry", result
    await hass_setup.async_block_till_done()

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.settings["schedule"]["enabled"] is True
    # omitted slot keys keep their schema defaults; unlisted overrides stay
    # null ("inherit", F-SSV-18)
    assert stored.settings["schedule"]["slots"] == [
        {"start": "07:00", "mode": "photos", "brightness": 40},
        {"start": "22:30", "mode": "black"},
    ]


async def test_options_bad_schedule_yaml_reshows(hass_setup, server, hub, session, paired):
    """YAML that is not a list of objects reshows on the slots field."""
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, schedule_slots="07:00 photos\n")
    )
    assert result["type"] == "form", result
    assert result["step_id"] == "device"
    assert result["errors"].get("schedule_slots") == "invalid_settings", result["errors"]

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert runtime.settings.get(paired.device_id) is None


async def test_options_schedule_item_error_maps_to_the_slots_field(
    hass_setup, server, hub, session, paired
):
    """An error inside a slot (bad start) lands on the list's field, not base."""
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    result = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], form_values(schema, schedule_slots="- start: 7:5\n")
    )
    assert result["type"] == "form", result
    assert result["errors"].get("schedule_slots") == "invalid_settings", result["errors"]
    assert "base" not in result["errors"], result["errors"]


async def test_options_unknown_device_aborts(hass_setup, server, hub, session, paired):
    """A device id the hub does not know aborts the flow."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"next_step_id": "settings"}
    )
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
            flow["flow_id"], {"next_step_id": "settings"}
        )
        assert result["type"] == "abort"
        assert result["reason"] == "hub_not_loaded"
    finally:
        hass_setup.data[DOMAIN] = removed


# --- cross-leaf rules (P9.5) --------------------------------------------------


async def test_options_refuses_listed_auto_answer_without_callers(
    hass_setup, server, hub, session, paired
):
    """'only listed callers' with nobody named is a form error, not a save.

    The device treats the pair as a plain ring, so accepting it would store a
    setting that reads as a promise it does not keep (P9.5).
    """
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    values = form_values(schema, intercom_auto_answer="listed", intercom_auto_answer_from="")
    result = await hass_setup.config_entries.options.async_configure(flow["flow_id"], values)
    assert result["type"] == "form", result
    assert result["errors"] == {"intercom_auto_answer_from": "invalid_settings"}

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert runtime.settings.revision(paired.device_id) == 0


async def test_options_saves_listed_auto_answer_with_a_caller(
    hass_setup, server, hub, session, paired
):
    """One caller is enough, and the value is the document the device reads."""
    caller = "0f8fad5b-d9cb-469f-a165-70867728950e"
    flow = await open_device_form(hass_setup, hub.entry_id, paired.ha_device_id)
    schema = SettingsSchema()
    values = form_values(schema, intercom_auto_answer="listed", intercom_auto_answer_from=caller)
    result = await hass_setup.config_entries.options.async_configure(flow["flow_id"], values)
    assert result["type"] == "create_entry", result

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.settings["intercom"] == {
        "auto_answer": "listed",
        "auto_answer_from": [caller],
    }
