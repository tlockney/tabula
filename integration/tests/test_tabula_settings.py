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

import pytest
import custom_components.tabula as tabula_pkg
from custom_components.tabula import DeviceRecord, DeviceSession, TabulaHub
from custom_components.tabula.const import SCHEMA_VERSION
from custom_components.tabula.settings import (
    SettingsError,
    SettingsSchema,
    SettingsStore,
    async_push_settings,
    schedule_settings_push,
)
from custom_components.tabula.commands import DeviceNotConnected
from helpers import uid

REPO_SCHEMA = Path(__file__).resolve().parents[2] / "schema" / "settings.schema.json"
BUNDLED_SCHEMA = Path(tabula_pkg.__file__).parent / "settings.schema.json"

DEVICE_ID = uid()


def schema() -> SettingsSchema:
    return SettingsSchema()


# --- the bundled schema -------------------------------------------------------------


def test_bundled_settings_schema_matches_repo():
    """The shipped copy is the repo's source of truth, byte for byte."""
    assert BUNDLED_SCHEMA.read_bytes() == REPO_SCHEMA.read_bytes()
    assert schema().version == SCHEMA_VERSION == 1


def test_defaults_cover_every_leaf_with_a_default():
    """Defaults are the schema's; identity and connection stay unset."""
    defaults = schema().defaults()
    schema().validate(defaults)
    assert defaults["screen"]["brightness"] == 60
    assert defaults["screen"]["keep_screen_on"] is True
    assert defaults["screensaver"] == {
        "enabled": True,
        "idle_timeout": 300,
        "mode": "clock",
        "brightness": 20,
    }
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


def test_editable_fields_come_from_the_schema_tags():
    """One field per non-hardware leaf, in schema order, each with a feature tag."""
    fields = schema().editable_fields()
    keys = [f.key for f in fields]
    assert keys == [
        "identity_name",
        "connection_ha_url",
        "screen_brightness",
        "screen_adaptive_brightness",
        "screen_keep_screen_on",
        "screensaver_enabled",
        "screensaver_idle_timeout",
        "screensaver_mode",
        "screensaver_brightness",
        "notifications_default_duration",
        "notifications_default_level",
        "notifications_chime_by_level_info",
        "notifications_chime_by_level_success",
        "notifications_chime_by_level_warning",
        "notifications_chime_by_level_error",
        "notifications_max_visible",
        "audio_media_volume",
        "audio_announce_volume_boost",
    ]
    assert all(f.feature for f in fields)
    assert all(f.role != "hardware" for f in fields)
    # Every leaf is editable in v1 (no hardware members yet), but the walk must
    # still honor the tag: a hardware leaf would be missing from this list.
    assert [f.key for f in schema().fields()] == keys


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
    with pytest.raises(DeviceNotConnected):
        await async_push_settings(runtime, record)


async def test_push_sends_set_settings_and_takes_the_ack(hass_setup, hub, hass_storage):
    """A drift between revisions sends the full document and resolves on ACK."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record, session, connection = _device_with_session(revision=0)
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
    future.set_result(
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
    record, session, connection = _device_with_session(revision=5)
    runtime.devices[record.device_id] = record

    schedule_settings_push(runtime, record)
    await hass_setup.async_block_till_done()
    assert not connection.send_event.called
    assert runtime.settings.revision(record.device_id) == 0
