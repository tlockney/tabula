"""Profiles: the store, effective values, diff, drift, push and services (P5.6).

Everything here drives the real surfaces: the hub's ``ProfileStore`` through
the panel's WebSocket commands, the ``tabula.*`` services through HA's service
registry, and the two-device end-to-end tests through the real mock device. The
rules under test are ADR 0035's: a profile holds ``shared``-role leaves only, a
profile write is one revision of one settings document, an override shadows its
profile for one device and survives profile saves, and drift is revision-based
because TLP has no upload direction.
"""

from __future__ import annotations

import asyncio
import os
from typing import Any, ClassVar

import aiohttp
import pytest
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import EMPTY_PROFILE_ID, SCHEMA_VERSION
from custom_components.tabula.profiles import (
    ProfileError,
    ProfileStore,
    split_shared_values,
)
from custom_components.tabula.settings import SettingsSchema, async_set_settings
from helpers import WS, Paired, pair_device, uid, ws_connect
from homeassistant.auth.const import GROUP_ID_ADMIN
from homeassistant.core import HomeAssistant
from mockdevice.client import TLPClient
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.store import DeviceStore

# --- helpers -----------------------------------------------------------------


async def admin_client(hass: HomeAssistant, server: str, session) -> WS:
    """A WebSocket client authenticated as an admin (the panel's audience)."""
    user = await hass.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass.auth.async_create_refresh_token(user)
    return await ws_connect(session, server, refresh.token)


async def call(client: WS, payload: dict[str, Any], msg_id: int = 1) -> dict[str, Any]:
    """Call one panel command and assert it succeeded; return its result."""
    result = await client.reply({"id": msg_id, **payload})
    assert result["success"] is True, result
    return result["result"]


@pytest.fixture(name="runtime")
async def runtime_fixture(hass_setup, hub) -> TabulaHub:
    """The live hub runtime (the ``hub`` fixture is the config entry)."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    return runtime


def store(hass: HomeAssistant) -> ProfileStore:
    hub = TabulaHub.get(hass)
    assert hub is not None
    return hub.profiles


def settings_store(hass: HomeAssistant):
    hub = TabulaHub.get(hass)
    assert hub is not None
    return hub.settings


async def make_profile(hass: HomeAssistant, name: str, values: dict[str, Any]) -> tuple:
    """Save a profile straight through the store (no panel round trip)."""
    return await store(hass).async_save(profile_id=None, name=name, values=values)


def strings_in(value: Any) -> list[str]:
    """Every string anywhere in a JSON-shaped payload."""
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [item for key, child in value.items() for item in (key, *strings_in(child))]
    if isinstance(value, list):
        return [item for child in value for item in strings_in(child)]
    return []


def keys_in(value: Any) -> set[str]:
    """Every dict key anywhere in a JSON-shaped payload."""
    if isinstance(value, dict):
        nested = {child for item in value.values() for child in keys_in(item)}
        return set(value) | nested
    if isinstance(value, list):
        return {child for item in value for child in keys_in(item)}
    return set()


# --- the role rule (ADR 0035 item 8) -----------------------------------------


class _Leaf:
    def __init__(self, role: str) -> None:
        self.role = role


class _StubSchema:
    """A schema with one leaf per role, so ``hardware`` can be exercised.

    The real schema (v3) declares no ``hardware`` leaf yet, and the rule must
    not be a list of the roles that happen to have members today.
    """

    _FIELDS: ClassVar[dict[str, _Leaf]] = {
        "screen_brightness": _Leaf("shared"),
        "identity_name": _Leaf("identity"),
        "connection_ha_url": _Leaf("connection"),
        "panel_ui_scale": _Leaf("hardware"),
    }

    def group_keys(self) -> frozenset[str]:
        return frozenset({"screen"})

    def field(self, key: str) -> _Leaf:
        return self._FIELDS[key]

    def validate_value(self, key: str, value: Any) -> list[str]:
        if key == "screen_brightness" and not 0 <= value <= 100:
            return ["out of range"]
        return []


def test_every_role_but_shared_is_refused():
    """Identity, connection and hardware keys are refused, each with a reason."""
    accepted, rejected = split_shared_values(
        _StubSchema(),  # type: ignore[arg-type]
        {
            "screen_brightness": 40,
            "identity_name": "Kitchen",
            "connection_ha_url": "http://elsewhere:8123",
            "panel_ui_scale": 150,
        },
    )
    assert accepted == {"screen_brightness": 40}
    assert {item["key"] for item in rejected} == {
        "identity_name",
        "connection_ha_url",
        "panel_ui_scale",
    }
    for item in rejected:
        assert "per device" in item["reason"]


def test_group_unknown_and_invalid_values_are_refused():
    """A group key, an unknown key and a bad value each get their own reason."""
    accepted, rejected = split_shared_values(
        _StubSchema(),  # type: ignore[arg-type]
        {"screen": {"brightness": 40}, "screen_sharpness": 3, "screen_brightness": 400},
    )
    assert accepted == {}
    reasons = {item["key"]: item["reason"] for item in rejected}
    assert "group" in reasons["screen"]
    assert "No setting has that name" in reasons["screen_sharpness"]
    assert "out of range" in reasons["screen_brightness"]


@pytest.mark.parametrize(
    "values",
    [
        {"identity_name": "Sneaky"},
        {"connection_ha_url": "http://elsewhere:8123"},
        {"screen_brightness": 40, "identity_name": "Sneaky", "connection_ha_url": "http://x"},
    ],
)
async def test_stored_profile_never_holds_a_forbidden_key(hass_setup, runtime, values):
    """A save keeps the shared keys and refuses the rest, whatever was sent."""
    profile, rejected = await store(hass_setup).async_save(
        profile_id=None, name="Smuggled", values=values
    )
    expected = {"screen_brightness": 40} if "screen_brightness" in values else {}
    assert profile.values == expected
    assert {item["key"] for item in rejected} == set(values) - set(expected)
    stored = store(hass_setup).get(profile.profile_id)
    assert stored is not None
    assert set(stored.values) == set(profile.values)


# --- the store's own rules ---------------------------------------------------


async def test_names_are_unique(hass_setup, runtime):
    """Two profiles cannot share a name, in any case or spacing."""
    await store(hass_setup).async_save(profile_id=None, name="Kitchen", values={})
    with pytest.raises(ProfileError) as error:
        await store(hass_setup).async_save(profile_id=None, name=" kitchen ", values={})
    assert "already named" in str(error.value)


async def test_empty_profile_is_permanent_and_reserved(hass_setup, runtime):
    """The built-in profile cannot be renamed, saved over or deleted."""
    profiles = store(hass_setup).profiles()
    assert profiles[0].profile_id == EMPTY_PROFILE_ID
    assert profiles[0].values == {}
    with pytest.raises(ProfileError):
        await store(hass_setup).async_save(profile_id=EMPTY_PROFILE_ID, name="Mine", values={})
    with pytest.raises(ProfileError):
        await store(hass_setup).async_save(profile_id=None, name="Empty", values={})
    with pytest.raises(ProfileError):
        await store(hass_setup).async_delete(EMPTY_PROFILE_ID)


async def test_a_profile_with_no_keys_is_valid(hass_setup, runtime):
    """An empty named profile is a legal document (F-MGT-04's starting point)."""
    profile, rejected = await store(hass_setup).async_save(
        profile_id=None, name="Nothing yet", values={}
    )
    assert profile.values == {}
    assert rejected == []


async def test_delete_moves_members_to_the_empty_profile(hass_setup, runtime, paired):
    """Removing a profile leaves its members unassigned and writes nothing."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    before = settings_store(hass_setup).get(paired.device_id)
    assert before is not None

    moved = await store(hass_setup).async_delete(profile.profile_id)

    assert moved == [paired.device_id]
    assert store(hass_setup).assignment(paired.device_id) == EMPTY_PROFILE_ID
    after = settings_store(hass_setup).get(paired.device_id)
    assert after is not None
    assert (after.revision, after.settings) == (before.revision, before.settings)


# --- effective values and overrides ------------------------------------------


async def test_effective_values_use_the_profile_then_the_override(hass_setup, runtime, paired):
    """Covered keys come from the profile; an override shadows its own key."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    assert store(hass_setup).effective_values(paired.device_id) == {"screen_brightness": 40}

    # A per-device write of a covered key becomes an override (ADR 0035 item 1).
    await async_set_settings(runtime, paired.device_id, {"screen_brightness": 30})
    assert store(hass_setup).overrides(paired.device_id) == {"screen_brightness": 30}
    assert store(hass_setup).effective_values(paired.device_id) == {"screen_brightness": 30}

    # Submitting the profile's own value clears the override.
    await async_set_settings(runtime, paired.device_id, {"screen_brightness": 40})
    assert store(hass_setup).overrides(paired.device_id) == {}


async def test_uncovered_keys_keep_their_own_value(hass_setup, runtime, paired):
    """A profile writes only the keys it covers."""
    await async_set_settings(runtime, paired.device_id, {"screen_brightness": 55})
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screensaver_idle_timeout": 120})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    assert store(hass_setup).stored_value(paired.device_id, "screen_brightness") == 55
    assert store(hass_setup).stored_value(paired.device_id, "screensaver_idle_timeout") == 120


async def test_profile_save_leaves_an_override_alone(hass_setup, runtime, paired):
    """Saving a profile applies to every member except an overridden key."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    await store(hass_setup).async_set_override(paired.device_id, "screen_brightness", 30)

    saved, _ = await store(hass_setup).async_save(
        profile_id=profile.profile_id, name="Kitchen", values={"screen_brightness": 70}
    )
    applied = await store(hass_setup).async_apply_profile(saved.profile_id)

    assert applied[0]["applied"] == []
    assert store(hass_setup).stored_value(paired.device_id, "screen_brightness") == 30
    # Clearing the override is what applies the profile's value.
    await store(hass_setup).async_set_override(paired.device_id, "screen_brightness", None)
    assert store(hass_setup).stored_value(paired.device_id, "screen_brightness") == 70


async def test_override_needs_a_covered_key(hass_setup, runtime, paired):
    """A key the profile does not cover cannot be overridden."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    with pytest.raises(ProfileError):
        await store(hass_setup).async_set_override(paired.device_id, "screensaver_idle_timeout", 5)


async def test_reassignment_drops_overrides_that_shadow_nothing(hass_setup, runtime, paired):
    """An override exists only while the assigned profile covers its key."""
    first, _ = await make_profile(hass_setup, "One", {"screen_brightness": 40})
    second, _ = await make_profile(hass_setup, "Two", {"screensaver_idle_timeout": 300})
    await store(hass_setup).async_assign(paired.device_id, first.profile_id)
    await store(hass_setup).async_set_override(paired.device_id, "screen_brightness", 30)

    await store(hass_setup).async_assign(paired.device_id, second.profile_id)

    assert store(hass_setup).overrides(paired.device_id) == {}
    # The value the override held is now simply the device's own.
    assert store(hass_setup).stored_value(paired.device_id, "screen_brightness") == 30


# --- one revision, only what changes ----------------------------------------


async def test_apply_writes_only_differing_keys_as_one_revision(hass_setup, runtime, paired):
    """Two covered keys, one differing: one revision, one key in the write."""
    await async_set_settings(
        runtime,
        paired.device_id,
        {"screen_brightness": 40, "screensaver_idle_timeout": 300},
    )
    revision = settings_store(hass_setup).revision(paired.device_id)
    profile, _ = await make_profile(
        hass_setup, "Kitchen", {"screen_brightness": 40, "screensaver_idle_timeout": 600}
    )

    result = await store(hass_setup).async_assign(paired.device_id, profile.profile_id)

    assert result["applied"] == ["screensaver_idle_timeout"]
    assert result["revision"] == revision + 1
    assert settings_store(hass_setup).revision(paired.device_id) == revision + 1


async def test_apply_twice_writes_nothing_the_second_time(hass_setup, runtime, paired):
    """An already-current member costs no revision and no push."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    revision = settings_store(hass_setup).revision(paired.device_id)

    applied = await store(hass_setup).async_apply_profile(profile.profile_id)

    assert applied[0]["applied"] == []
    assert applied[0]["revision"] is None
    assert settings_store(hass_setup).revision(paired.device_id) == revision


# --- diff and drift ----------------------------------------------------------


async def test_diff_lists_covered_keys_and_whether_they_change(hass_setup, runtime, paired):
    """Each member row carries the profile value, the current value and the change."""
    await async_set_settings(
        runtime,
        paired.device_id,
        {"screen_brightness": 40, "screensaver_idle_timeout": 300},
    )
    # An empty profile first: assigning it writes nothing, so the values can be
    # compared before any push has moved them.
    profile, _ = await make_profile(hass_setup, "Kitchen", {})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    profile, _ = await store(hass_setup).async_save(
        profile_id=profile.profile_id,
        name="Kitchen",
        values={"screen_brightness": 55, "screensaver_idle_timeout": 300},
    )

    rows = {row["key"]: row for row in store(hass_setup).diff(profile_id=profile.profile_id)}

    assert set(rows) == {"screen_brightness", "screensaver_idle_timeout"}
    assert rows["screen_brightness"]["profile_value"] == 55
    assert rows["screen_brightness"]["device_value"] == 40
    assert rows["screen_brightness"]["changes"] is True
    assert rows["screensaver_idle_timeout"]["device_value"] == 300
    assert rows["screensaver_idle_timeout"]["changes"] is False


async def test_diff_previews_unsaved_values(hass_setup, runtime, paired):
    """An unsaved document diffs against the members without storing anything."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)

    rows = store(hass_setup).diff(profile_id=profile.profile_id, values={"screen_brightness": 90})

    assert [row["profile_value"] for row in rows] == [90]
    assert store(hass_setup).get(profile.profile_id).values == {"screen_brightness": 40}


async def test_drift_reports_a_stale_revision(hass_setup, runtime, paired):
    """A member whose reported revision is behind HA's is drifted."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    record = runtime.devices[paired.device_id]
    record.current = FakeSession(reported=0)  # type: ignore[assignment]

    rows = store(hass_setup).drift()

    assert [row["device_id"] for row in rows] == [paired.device_id]
    row = rows[0]
    assert row["drifted"] is True
    assert row["revisions_behind"] == settings_store(hass_setup).revision(paired.device_id)
    assert row["connected"] is True  # a session exists; the revision is what is stale
    record.current = None


async def test_drift_reports_an_override(hass_setup, runtime, paired):
    """A member that intentionally differs is listed with its exception."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    await store(hass_setup).async_set_override(paired.device_id, "screen_brightness", 20)

    rows = store(hass_setup).drift(profile.profile_id)

    assert [row["device_id"] for row in rows] == [paired.device_id]
    assert rows[0]["overrides"] == [{"key": "screen_brightness", "value": 20}]
    assert rows[0]["drifted"] is False


async def test_drift_reports_a_key_the_device_is_too_old_for(hass_setup, runtime, paired):
    """A covered key newer than the device's schema is named, not silently lost."""
    schema = SettingsSchema()
    newer = [
        field.key
        for field in schema.fields()
        if field.role == "shared" and schema.since(field.key) == SCHEMA_VERSION
    ]
    assert newer, "the current schema must add at least one shared key"
    key = newer[0]
    profile, _ = await make_profile(hass_setup, "Kitchen", {key: _value_for(key)})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    runtime.devices[paired.device_id].schema_version = 1

    rows = store(hass_setup).drift()

    assert [row["device_id"] for row in rows] == [paired.device_id]
    assert rows[0]["behind_keys"] == [key]


def _value_for(key: str) -> Any:
    """A schema-valid value for a leaf, from its own subschema."""
    schema = SettingsSchema()
    field = schema.field(key)
    if field.enum:
        return field.enum[0]
    if field.type == "boolean":
        return True
    if field.type == "integer":
        return field.minimum if field.minimum is not None else 1
    if field.type == "array":
        return []
    if field.min_length:
        return "x" * field.min_length
    return "profile-value"


class FakeSession:
    """The parts of a DeviceSession the drift report reads."""

    def __init__(self, reported: int) -> None:
        self.reported_settings_revision = reported
        self.subscription_id = 1


# --- panel commands ----------------------------------------------------------


async def test_panel_profiles_lists_profiles_assignments_and_devices(
    hass_setup, server, runtime, session, paired
):
    """One snapshot: the empty profile, the assignments and the assignable devices."""
    await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    client = await admin_client(hass_setup, server, session)
    try:
        payload = await call(client, {"type": "tabula/panel_profiles"})
    finally:
        await client.close()

    names = [profile["name"] for profile in payload["profiles"]]
    assert names == ["Empty", "Kitchen"]
    assert payload["profiles"][0]["builtin"] is True
    assert payload["assignments"] == {paired.device_id: EMPTY_PROFILE_ID}
    assert [device["device_id"] for device in payload["devices"]] == [paired.device_id]


async def test_panel_commands_are_admin_only(hass_setup, server, runtime, session, paired):
    """Every profile command refuses a non-admin, like the rest of the panel."""
    user = await hass_setup.auth.async_create_system_user("Not an admin")
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    payloads = [
        {"type": "tabula/panel_profiles"},
        {"type": "tabula/panel_profile_save", "name": "Nope", "values": {}},
        {"type": "tabula/panel_profile_delete", "profile_id": EMPTY_PROFILE_ID},
        {
            "type": "tabula/panel_profile_assign",
            "device_id": paired.device_id,
            "profile_id": EMPTY_PROFILE_ID,
        },
        {"type": "tabula/panel_profile_diff", "profile_id": EMPTY_PROFILE_ID},
        {"type": "tabula/panel_profile_sync", "profile_id": EMPTY_PROFILE_ID},
        {"type": "tabula/panel_drift"},
        {
            "type": "tabula/panel_profile_override",
            "device_id": paired.device_id,
            "key": "screen_brightness",
            "value": 10,
        },
    ]
    try:
        results = [
            await client.reply({"id": index + 1, **payload})
            for index, payload in enumerate(payloads)
        ]
    finally:
        await client.close()

    assert [result["success"] for result in results] == [False] * len(payloads)
    assert {result["error"]["code"] for result in results} == {"unauthorized"}


async def test_panel_save_assign_sync_and_override(hass_setup, server, runtime, session, paired):
    """The panel's write commands do what the store does, and report per key."""
    client = await admin_client(hass_setup, server, session)
    try:
        saved = await call(
            client,
            {
                "type": "tabula/panel_profile_save",
                "name": "Kitchen",
                "values": {"screen_brightness": 40, "identity_name": "Sneaky"},
            },
            msg_id=1,
        )
        profile_id = saved["profile"]["profile_id"]
        assert saved["profile"]["values"] == {"screen_brightness": 40}
        assert [item["key"] for item in saved["rejected"]] == ["identity_name"]

        assigned = await call(
            client,
            {
                "type": "tabula/panel_profile_assign",
                "device_id": paired.device_id,
                "profile_id": profile_id,
            },
            msg_id=2,
        )
        assert assigned["profile_id"] == profile_id
        assert assigned["applied"]["applied"] == ["screen_brightness"]

        diff = await call(
            client, {"type": "tabula/panel_profile_diff", "profile_id": profile_id}, msg_id=3
        )
        assert diff["changing"] == 0
        assert [row["device_value"] for row in diff["rows"]] == [40]

        overridden = await call(
            client,
            {
                "type": "tabula/panel_profile_override",
                "device_id": paired.device_id,
                "key": "screen_brightness",
                "value": 25,
            },
            msg_id=4,
        )
        assert overridden["overrides"] == {"screen_brightness": 25}
        assert overridden["applied"]["applied"] == ["screen_brightness"]

        drift = await call(client, {"type": "tabula/panel_drift"}, msg_id=5)
        assert [row["device_id"] for row in drift["members"]] == [paired.device_id]

        synced = await call(
            client,
            {"type": "tabula/panel_profile_sync", "profile_id": profile_id},
            msg_id=6,
        )
        assert synced["applied"][0]["applied"] == []

        cleared = await call(
            client,
            {
                "type": "tabula/panel_profile_override",
                "device_id": paired.device_id,
                "key": "screen_brightness",
                "value": None,
            },
            msg_id=7,
        )
        assert cleared["overrides"] == {}
        assert cleared["applied"]["applied"] == ["screen_brightness"]

        deleted = await call(
            client,
            {"type": "tabula/panel_profile_delete", "profile_id": profile_id},
            msg_id=8,
        )
        assert deleted["moved"] == [paired.device_id]
    finally:
        await client.close()


async def test_panel_payloads_carry_no_credential(hass_setup, server, runtime, session, paired):
    """No panel payload mentions a token, a user id or a pairing detail."""
    client = await admin_client(hass_setup, server, session)
    try:
        payloads = [
            await call(client, {"type": "tabula/panel_profiles"}, msg_id=1),
            await call(client, {"type": "tabula/panel_drift"}, msg_id=2),
        ]
    finally:
        await client.close()

    forbidden = {"refresh_token", "access_token", "user_id", "nonce", "nonce_hash", "password"}
    for payload in payloads:
        assert not (keys_in(payload) & forbidden)
        assert all(paired.refresh_token not in text for text in strings_in(payload))


# --- services ----------------------------------------------------------------


async def test_assign_profile_service(hass_setup, server, runtime, session, paired):
    """tabula.assign_profile names a profile and targets devices (F-MGT-02)."""
    await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await hass_setup.services.async_call(
        "tabula",
        "assign_profile",
        {"profile": "Kitchen", "device_id": paired.ha_device_id},
        blocking=True,
    )
    assert store(hass_setup).profile_of(paired.device_id).name == "Kitchen"
    assert settings_store(hass_setup).value(paired.device_id, "screen_brightness") == 40


async def test_sync_profile_service(hass_setup, server, runtime, session, paired):
    """tabula.sync_profile applies a saved profile by name or id."""
    profile, _ = await make_profile(hass_setup, "Kitchen", {"screen_brightness": 40})
    await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
    await hass_setup.services.async_call(
        "tabula",
        "sync_profile",
        {"profile": profile.profile_id, "device_id": paired.ha_device_id},
        blocking=True,
    )
    assert settings_store(hass_setup).value(paired.device_id, "screen_brightness") == 40


async def test_unknown_profile_is_a_service_error(hass_setup, server, runtime, session, paired):
    """A name that matches no profile raises instead of doing nothing."""
    from homeassistant.exceptions import ServiceValidationError

    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(
            "tabula",
            "assign_profile",
            {"profile": "Nowhere", "device_id": paired.ha_device_id},
            blocking=True,
        )


# --- end to end, two mock devices -------------------------------------------


async def _run_client(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


async def connect_mock(
    hass: HomeAssistant, server: str, paired: Paired, tmp_path, tag: str
) -> tuple[MockDevice, TLPClient, asyncio.Task]:
    """A registered, subscribed mock device connected to the live hub."""
    store_file = tmp_path / f"device-{tag}.json"
    device_store = DeviceStore(
        store_file,
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(capabilities=ALL_CAPABILITIES, base_url=server)
    registered = asyncio.Event()
    client = TLPClient(server, device_store, device, on_registered=lambda _: registered.set())
    task = asyncio.create_task(_run_client(client))
    await asyncio.wait_for(registered.wait(), 10)
    runtime = TabulaHub.get(hass)
    assert runtime is not None
    record = runtime.devices[paired.device_id]
    for _ in range(250):
        if record.current is not None and record.current.subscription_id is not None:
            break
        await asyncio.sleep(0.02)
    assert record.current is not None and record.current.subscription_id is not None
    return device, client, task


def observe(label: str, device: MockDevice, hass: HomeAssistant, paired: Paired) -> None:
    """Print one observation of the two-device manual check (see the packet).

    Run with TABULA_PROFILES_MANUAL=1 to see the transcript the P5.6 manual
    check pastes: what each device holds and which revision HA stores.
    """
    if not os.environ.get("TABULA_PROFILES_MANUAL"):
        return
    runtime = TabulaHub.get(hass)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    record = runtime.devices[paired.device_id]
    print(
        f"[manual] {label}: {record.name or paired.device_id[:8]}"
        f" brightness={device.settings.get('screen', {}).get('brightness')!r}"
        f" device_revision={device.settings_revision}"
        f" ha_revision={stored.revision if stored else None}"
        f" connected={record.connected}"
    )


async def _until(predicate, timeout: float = 10.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def test_two_devices_share_a_profile(hass_setup, server, runtime, session, paired, tmp_path):
    """A profile save reaches both members; an offline one catches up later (step 10)."""
    second = await pair_device(hass_setup, server, uid(), name="Hallway Tablet")
    one, one_client, one_task = await connect_mock(hass_setup, server, paired, tmp_path, "one")
    two, two_client, two_task = await connect_mock(hass_setup, server, second, tmp_path, "two")
    running = [(one_client, one_task), (two_client, two_task)]
    try:
        profile, _ = await make_profile(hass_setup, "Household", {"screen_brightness": 40})
        await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
        await store(hass_setup).async_assign(second.device_id, profile.profile_id)
        await _until(
            lambda: (
                one.settings.get("screen", {}).get("brightness") == 40
                and two.settings.get("screen", {}).get("brightness") == 40
            )
        )
        observe("profile created, both assigned", one, hass_setup, paired)
        observe("profile created, both assigned", two, hass_setup, second)

        # The hallway tablet drops off; the profile changes; the kitchen follows.
        two_client.stop()
        await asyncio.wait_for(two_task, 10)
        running.remove((two_client, two_task))
        await _until(lambda: runtime.devices[second.device_id].current is None)
        saved, _ = await store(hass_setup).async_save(
            profile_id=profile.profile_id, name="Household", values={"screen_brightness": 65}
        )
        await store(hass_setup).async_apply_profile(saved.profile_id)
        await _until(lambda: one.settings.get("screen", {}).get("brightness") == 65)
        observe("profile changed offline", one, hass_setup, paired)
        observe("profile changed offline", two, hass_setup, second)

        # The offline member is listed as drifted, and catches up on reconnect.
        rows = {row["device_id"]: row for row in store(hass_setup).drift()}
        assert rows[second.device_id]["connected"] is False
        assert rows[second.device_id]["drifted"] is True
        if os.environ.get("TABULA_PROFILES_MANUAL"):
            row = rows[second.device_id]
            print(
                f"[manual] drift: {row['name']} connected={row['connected']}"
                f" ha_revision={row['ha_revision']} device_revision={row['device_revision']}"
                f" revisions_behind={row['revisions_behind']}"
            )
        again, again_client, again_task = await connect_mock(
            hass_setup, server, second, tmp_path, "two-again"
        )
        running.append((again_client, again_task))
        await _until(lambda: again.settings.get("screen", {}).get("brightness") == 65)
        observe("reconnected, caught up", again, hass_setup, second)
    finally:
        for client, task in running:
            client.stop()
            await asyncio.wait_for(task, 10)


async def test_older_schema_member_gets_the_keys_it_knows(
    hass_setup, server, runtime, session, paired, tmp_path
):
    """A member on an older schema takes what it can and drifts on the rest."""
    schema = SettingsSchema()
    newer = [
        field.key
        for field in schema.fields()
        if field.role == "shared" and schema.since(field.key) == SCHEMA_VERSION
    ]
    assert newer
    device, client, task = await connect_mock(hass_setup, server, paired, tmp_path, "old")
    try:
        assert runtime.devices[paired.device_id].schema_version == 1
        profile, _ = await make_profile(
            hass_setup,
            "Household",
            {"screen_brightness": 33, newer[0]: _value_for(newer[0])},
        )
        await store(hass_setup).async_assign(paired.device_id, profile.profile_id)
        await _until(lambda: device.settings.get("screen", {}).get("brightness") == 33)

        # The newer key was translated away for the device, so the report names it.
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        await _until(
            lambda: (
                runtime.devices[paired.device_id].current.reported_settings_revision
                == settings_store(hass_setup).revision(paired.device_id)
            )
        )
        rows = store(hass_setup).drift()
        assert rows[0]["behind_keys"] == [newer[0]]
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)
