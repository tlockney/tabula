"""Settings schema, store and push (P1.5, plan 5.6, PROTOCOL.md 4.2/4.5).

The store round-trips documents with revisions through real HA storage; the
schema-derived form fields and defaults match schema/settings.schema.json; the
push sends a strict-valid set_settings on the device's subscription and only
when the device has not applied the stored revision.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import MagicMock

import custom_components.tabula as tabula_pkg
import pytest
from custom_components.tabula import DeviceRecord, DeviceSession, TabulaHub
from custom_components.tabula.const import SCHEMA_VERSION
from custom_components.tabula.settings import (
    SettingsError,
    SettingsSchema,
    SettingsStore,
    async_push_settings,
    schedule_settings_push,
)
from helpers import uid
from homeassistant.exceptions import HomeAssistantError

REPO_SCHEMA = Path(__file__).resolve().parents[2] / "schema" / "settings.schema.json"
BUNDLED_SCHEMA = Path(tabula_pkg.__file__).parent / "settings.schema.json"

DEVICE_ID = uid()


def schema() -> SettingsSchema:
    return SettingsSchema()


# --- the bundled schema -------------------------------------------------------------


def test_bundled_settings_schema_matches_repo():
    """The shipped copy is the repo's source of truth, byte for byte."""
    assert BUNDLED_SCHEMA.read_bytes() == REPO_SCHEMA.read_bytes()
    assert schema().version == SCHEMA_VERSION == 2


def test_bundled_frozen_v1_schema_matches_repo():
    """The frozen v1 copy (down-translation, migrations/) is the repo's."""
    bundled_v1 = BUNDLED_SCHEMA.parent / "settings.schema.v1.json"
    repo_v1 = REPO_SCHEMA.parent / "settings.schema.v1.json"
    assert bundled_v1.read_bytes() == repo_v1.read_bytes()


def test_defaults_cover_every_leaf_with_a_default():
    """Defaults are the schema's; identity and connection stay unset."""
    defaults = schema().defaults()
    schema().validate(defaults)
    assert defaults["screen"]["brightness"] == 60
    assert defaults["screen"]["keep_screen_on"] is True
    assert defaults["screensaver"]["enabled"] is True
    assert defaults["screensaver"]["brightness"] == 20
    assert defaults["screensaver"]["mode"] == "clock"
    assert defaults["notifications"]["chime_by_level"] == {
        "info": False,
        "success": False,
        "warning": True,
        "error": True,
    }
    assert defaults["audio"]["announce_volume_boost"] == "none"
    # No defaults for the pairing-owned keys: an unset name falls back to the
    # model or device id (schema: identity.name).
    assert "identity" not in defaults
    assert "connection" not in defaults
    # settings v2: nested groups under screensaver get their own defaults
    assert defaults["screensaver"]["dim_brightness"] == 10
    assert defaults["screensaver"]["clock"]["face"] == "digital"
    assert defaults["screensaver"]["night_mode"]["enabled"] is False
    assert defaults["screensaver"]["photos"]["slide_interval"] == 300
    # every defaults() call is an independent document (array defaults are
    # mutable and shared as schema constants otherwise)
    again = schema().defaults()
    assert again == defaults
    assert again["at_a_glance"]["entities"] is not defaults["at_a_glance"]["entities"]
    assert again["schedule"]["slots"] == []


def test_editable_fields_come_from_the_schema_tags():
    """One field per non-hardware leaf, in schema order, each with a feature tag."""
    fields = schema().editable_fields()
    keys = [f.key for f in fields]
    # identity and connection come first, then the v1 keys, then the v2 groups
    assert keys[:2] == ["identity_name", "connection_ha_url"]
    assert "screensaver_dim_brightness" in keys
    assert "screensaver_clock_face" in keys
    assert "at_a_glance_entities" in keys
    assert "widgets_entity_visible_values" in keys
    # the two v2 list leaves and the schedule close the form, schema order
    assert keys[-1] == "schedule_slots"
    assert all(f.feature for f in fields)
    assert all(f.role != "hardware" for f in fields)
    # Every leaf is editable (no hardware members yet), but the walk must
    # still honor the tag: a hardware leaf would be missing from this list.
    assert [f.key for f in schema().fields()] == keys
    # list leaves know what their items are, which picks the flow's editor
    arrays = {f.key: f for f in fields if f.type == "array"}
    assert set(arrays) == {
        "at_a_glance_entities",
        "widgets_entity_visible_values",
        "schedule_slots",
    }
    assert arrays["at_a_glance_entities"].array_items == "scalar"
    assert arrays["schedule_slots"].array_items == "object"


def test_validate_rejects_bad_documents():
    doc = schema().defaults()
    schema().validate(doc)
    with pytest.raises(SettingsError):
        schema().validate({"screen": {"brightness": 101}})
    with pytest.raises(SettingsError):
        schema().validate({"screen": {"brightness": "bright"}})
    with pytest.raises(SettingsError):
        schema().validate({"unknown_key": True})
    with pytest.raises(SettingsError):
        schema().validate({"screensaver": {"mode": "disco"}})
    with pytest.raises(SettingsError):
        schema().validate({"identity": {"name": ""}})
    with pytest.raises(SettingsError):
        # Credentials in the URL would be stored and exported with settings.
        schema().validate({"connection": {"ha_url": "http://user:pw@ha:8123/"}})


# --- the store ----------------------------------------------------------------------


async def test_store_round_trip(hass, hass_storage):
    """Save, reload from disk, revise, remove: revisions count data changes."""
    store = SettingsStore(hass)
    await store.async_load()
    assert store.get(DEVICE_ID) is None
    assert store.revision(DEVICE_ID) == 0

    saved = await store.async_save(DEVICE_ID, {"screen": {"brightness": 42}})
    assert saved.revision == 1
    # Defaults are filled for keys the caller omitted (a complete document is
    # what gets pushed; absent keys keep the device default, PROTOCOL.md 4.5).
    assert saved.settings["screen"]["brightness"] == 42
    assert saved.settings["screensaver"]["mode"] == "clock"

    again = await store.async_save(DEVICE_ID, {"screen": {"brightness": 43}})
    assert again.revision == 2
    await hass.async_block_till_done()

    fresh = SettingsStore(hass)  # a second instance reads the same storage
    await fresh.async_load()
    loaded = fresh.get(DEVICE_ID)
    assert loaded is not None
    assert loaded.revision == 2
    assert loaded.settings["screen"]["brightness"] == 43
    assert fresh.revision(DEVICE_ID) == 2

    await store.async_remove(DEVICE_ID)
    assert store.get(DEVICE_ID) is None
    assert store.revision(DEVICE_ID) == 0
    await hass.async_block_till_done()
    reloaded = SettingsStore(hass)
    await reloaded.async_load()
    assert reloaded.get(DEVICE_ID) is None


async def test_store_rejects_invalid_documents(hass, hass_storage):
    """Nothing is stored that would fail the push's strict validation."""
    store = SettingsStore(hass)
    await store.async_load()
    with pytest.raises(SettingsError):
        await store.async_save(DEVICE_ID, {"screen": {"brightness": 500}})
    assert store.get(DEVICE_ID) is None


async def test_store_drops_corrupt_entries(hass, hass_storage):
    """A damaged storage file loses its entry; the rest still loads."""
    good, bad = uid(), uid()
    hass_storage["tabula.settings"] = {
        "version": 1,
        "data": {
            "devices": {
                good: {"revision": 1, "settings": {"screen": {"brightness": 10}}},
                bad: {"revision": 1, "settings": {"screen": {"brightness": 999}}},
            }
        },
    }
    store = SettingsStore(hass)
    await store.async_load()
    assert store.get(bad) is None
    kept = store.get(good)
    assert kept is not None
    assert kept.revision == 1
    assert kept.settings["screen"]["brightness"] == 10


# --- schema v2 loads and merges -----------------------------------------------------


async def test_stored_v1_document_loads_as_v2_without_revision_bump(hass, hass_storage):
    """A v1 document upgrades on load: recursive defaults fill the v2 keys.

    Loading fills defaults (a schema change, not a data change), so the
    revision does not move and nothing is written back.
    """
    hass_storage["tabula.settings"] = {
        "version": 1,
        "data": {
            "devices": {
                DEVICE_ID: {
                    "revision": 5,
                    "settings": {
                        "screen": {"brightness": 42},
                        "screensaver": {"enabled": False, "idle_timeout": 60},
                    },
                }
            }
        },
    }
    store = SettingsStore(hass)
    await store.async_load()
    loaded = store.get(DEVICE_ID)
    assert loaded is not None
    assert loaded.revision == 5
    settings = loaded.settings
    # kept v1 values
    assert settings["screen"]["brightness"] == 42
    assert settings["screensaver"]["enabled"] is False
    # nested v2 groups filled at every depth
    assert settings["screensaver"]["clock"]["face"] == "digital"
    assert settings["screensaver"]["night_mode"]["light_threshold"] == 10
    assert settings["widgets"]["small_clock"]["corner"] == "tl"
    assert settings["schedule"] == {"enabled": False, "slots": []}
    schema().validate(settings)


async def test_partial_nested_save_merges_into_defaults(hass, hass_storage):
    """A partial nested document merges into the defaults at every depth.

    The options flow and entity writes save leaf-by-leaf; omitted nested
    keys must keep their defaults instead of being dropped. Arrays and nulls
    (the schedule's "inherit") are values: they replace, not merge.
    """
    store = SettingsStore(hass)
    await store.async_load()
    saved = await store.async_save(
        DEVICE_ID,
        {
            "screensaver": {"clock": {"face": "analog"}},
            "widgets": {"battery": {"enabled": True}},
            "schedule": {"slots": [{"start": "07:00", "brightness": None}]},
        },
    )
    settings = saved.settings
    assert settings["screensaver"]["clock"]["face"] == "analog"
    # sibling keys inside the nested group survive
    assert settings["screensaver"]["clock"]["size"] == 100
    assert settings["screensaver"]["mode"] == "clock"
    # sibling groups survive the partial widget object
    assert settings["widgets"]["battery"]["enabled"] is True
    assert settings["widgets"]["battery"]["corner"] == "bl"
    assert settings["widgets"]["shown"] is True
    # arrays replace, not merge; a slot's nulls (inherit) are kept as values
    assert settings["schedule"]["enabled"] is False
    assert settings["schedule"]["slots"] == [{"start": "07:00", "brightness": None}]
    schema().validate(settings)


# --- the push -----------------------------------------------------------------------


def _device_with_session(revision: int) -> tuple[DeviceRecord, DeviceSession, MagicMock]:
    record = DeviceRecord(device_id=uid(), user_id="u", refresh_token_id="t", ha_device_id="h")
    connection = MagicMock()
    session = DeviceSession(
        connection=connection,
        device=record,
        registered=True,
        subscription_id=7,
        reported_settings_revision=revision,
    )
    record.current = session
    record.schema_version = SCHEMA_VERSION
    return record, session, connection


async def test_push_offline_device_raises(hass_setup, hub, hass_storage):
    """async_push_settings needs a subscribed session; a hub is not enough."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record = DeviceRecord(device_id=uid(), user_id="u", refresh_token_id="t", ha_device_id="h")
    await runtime.settings.async_save(record.device_id, {"screen": {"brightness": 1}})
    with pytest.raises(HomeAssistantError, match="not connected"):
        await async_push_settings(runtime, record)


async def test_push_sends_set_settings_and_takes_the_ack(hass_setup, hub, hass_storage):
    """A drift between revisions sends the full document and resolves on ACK."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record, _session, connection = _device_with_session(revision=0)
    runtime.devices[record.device_id] = record
    saved = await runtime.settings.async_save(record.device_id, {"screen": {"brightness": 42}})
    assert saved.revision == 1

    schedule_settings_push(runtime, record)
    for _ in range(100):
        if connection.send_event.called:
            break
        await asyncio.sleep(0.02)
    assert connection.send_event.called, "the push must reach the subscription"
    (subscription_id, event), _ = connection.send_event.call_args
    assert subscription_id == 7
    assert event["command"] == "set_settings"
    assert event["command_id"]
    assert event["args"] == {
        "revision": 1,
        "schema_version": SCHEMA_VERSION,
        "settings": saved.settings,
    }
    assert (
        runtime.tlp.problems(
            "command_set_settings", {"id": 7, "type": "event", "event": event}, lenient=False
        )
        == []
    )

    # Answer like the device: applied the revision, tabula/result (PROTOCOL.md 4.6).
    future = runtime.result_futures[(record.device_id, event["command_id"])]
    future.future.set_result(
        {
            "id": 9,
            "type": "tabula/result",
            "command_id": event["command_id"],
            "ok": True,
            "data": {"revision": 1},
        }
    )
    await hass_setup.async_block_till_done()
    assert (record.device_id, event["command_id"]) not in runtime.result_futures


async def test_push_skipped_when_revisions_match(hass_setup, hub, hass_storage):
    """A device that applied the stored revision gets no push (plan 5.6)."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record, session, connection = _device_with_session(revision=0)
    runtime.devices[record.device_id] = record
    saved = await runtime.settings.async_save(record.device_id, {"screen": {"brightness": 42}})
    session.reported_settings_revision = saved.revision

    schedule_settings_push(runtime, record)
    await hass_setup.async_block_till_done()
    assert not connection.send_event.called


async def test_push_skipped_without_a_document(hass_setup, hub, hass_storage):
    """Nothing is authoritative without a document; the device keeps its own."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record, _session, connection = _device_with_session(revision=5)
    runtime.devices[record.device_id] = record

    schedule_settings_push(runtime, record)
    await hass_setup.async_block_till_done()
    assert not connection.send_event.called
    assert runtime.settings.revision(record.device_id) == 0
