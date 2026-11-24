"""Profiles: named partial settings documents many devices share (P5.6, ADR 0035).

A profile holds only ``shared``-role leaves (F-MGT-02, F-MGT-03) and is applied
by writing effective values into each member's normal settings document through
``async_set_settings``: one revision, one ``set_settings`` push, the same path
every other settings write takes (plan 5.6). Nothing here changes the protocol:
a device only ever receives a settings document, never a profile, an assignment
or an override.

Three pieces of HA-side state live in one ``Store`` (ADR 0035 item 6):

* ``profiles``: named partial documents of shared keys;
* ``assignments``: device id -> profile id (every device belongs to exactly one
  profile, the built-in empty one by default);
* ``overrides``: device id -> {flat key: value}, a per-device exception that
  shadows its profile's value for that key (ADR 0035 items 1-5).

Offline members need no queue. A profile save writes the next revision into
their document, so the existing registration reconcile (PROTOCOL.md 4.2,
``settings.schedule_settings_push``) delivers it when they come back.
"""

from __future__ import annotations

import copy
import logging
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from homeassistant.helpers.storage import Store
from homeassistant.util.ulid import ulid_now

from .const import (
    DOMAIN,
    EMPTY_PROFILE_ID,
    EMPTY_PROFILE_NAME,
    PROFILE_MAX,
    PROFILE_NAME_MAX,
)
from .settings import NO_DEFAULT, async_set_settings, get_path

if TYPE_CHECKING:
    from . import TabulaHub
    from .settings import SettingsSchema

_LOGGER = logging.getLogger(__name__)

STORAGE_KEY = f"{DOMAIN}.profiles"
STORAGE_VERSION = 1

# The only role a profile may hold (F-MGT-03, ADR 0035 item 8). The check is by
# role, not by a list of keys: every leaf the schema gives another role is
# refused, including ``hardware``, which has no members today.
SHARED_ROLE = "shared"
PROFILE_ROLES = frozenset({SHARED_ROLE})


class ProfileError(ValueError):
    """A profile request the store refuses; ``problems`` are the reasons."""

    def __init__(self, problems: list[str]) -> None:
        self.problems = problems
        super().__init__("; ".join(problems))


@dataclass
class Profile:
    """A named partial settings document: flat shared key -> value."""

    profile_id: str
    name: str
    values: dict[str, Any] = field(default_factory=dict)

    @property
    def builtin(self) -> bool:
        """The built-in empty profile is permanent and read-only (ADR 0035 item 9)."""
        return self.profile_id == EMPTY_PROFILE_ID

    def covers(self, key: str) -> bool:
        return key in self.values

    def to_storage(self) -> dict[str, Any]:
        return {"profile_id": self.profile_id, "name": self.name, "values": self.values}


def empty_profile() -> Profile:
    """The built-in empty profile: covers no keys, so it changes nothing."""
    return Profile(EMPTY_PROFILE_ID, EMPTY_PROFILE_NAME, {})


def split_shared_values(
    schema: SettingsSchema, values: dict[str, Any]
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    """Split submitted values into the shared keys the schema accepts and reasons.

    Per key (F-MGT-03, ADR 0035 item 8): a group key is a wrong shape, an
    unknown key names nothing the schema has, a leaf of any role but ``shared``
    is per device and cannot travel in a profile, and a value the leaf's own
    subschema refuses comes back with the schema's message. One forbidden key
    does not lose its neighbours: the caller keeps the accepted ones.
    """
    groups = schema.group_keys()
    accepted: dict[str, Any] = {}
    rejected: list[dict[str, str]] = []
    for raw_key, value in values.items():
        key = str(raw_key)
        if key in groups:
            rejected.append(
                {"key": key, "reason": "This is a group; set the keys inside it instead."}
            )
            continue
        try:
            field_ = schema.field(key)
        except KeyError:
            rejected.append({"key": key, "reason": "No setting has that name."})
            continue
        if field_.role not in PROFILE_ROLES:
            rejected.append(
                {
                    "key": key,
                    "reason": (
                        f"This setting is per device ({field_.role}); a profile cannot share it."
                    ),
                }
            )
            continue
        if problems := schema.validate_value(key, value):
            rejected.append({"key": key, "reason": "; ".join(problems)})
            continue
        accepted[key] = copy.deepcopy(value)
    return accepted, rejected


def _same(left: Any, right: Any) -> bool:
    """Equality for the JSON-shaped values settings hold; unset equals only unset."""
    if left is NO_DEFAULT or right is NO_DEFAULT:
        return left is right
    return left == right


class ProfileStore:
    """Profiles, assignments and overrides in one HA Store (ADR 0035)."""

    def __init__(self, hub: TabulaHub) -> None:
        self._hub = hub
        self._store = Store(hub.hass, STORAGE_VERSION, STORAGE_KEY)
        self._profiles: dict[str, Profile] = {}
        self._assignments: dict[str, str] = {}
        self._overrides: dict[str, dict[str, Any]] = {}

    # --- reads -------------------------------------------------------------

    def profiles(self) -> list[Profile]:
        """The built-in empty profile first, then the stored ones by name."""
        stored = sorted(self._profiles.values(), key=lambda p: p.name.casefold())
        return [empty_profile(), *stored]

    def get(self, profile_id: str) -> Profile | None:
        """A copy of one profile, or None; the built-in empty profile always exists."""
        if profile_id == EMPTY_PROFILE_ID:
            return empty_profile()
        stored = self._profiles.get(profile_id)
        if stored is None:
            return None
        return Profile(stored.profile_id, stored.name, copy.deepcopy(stored.values))

    def by_name(self, name: str) -> Profile | None:
        """The profile with this name, compared case-insensitively (unique names)."""
        wanted = name.strip().casefold()
        for profile in self.profiles():
            if profile.name.casefold() == wanted:
                return profile
        return None

    def resolve(self, name_or_id: str) -> Profile | None:
        """A profile by id, then by name: what a service call may name."""
        return self.get(name_or_id) or self.by_name(name_or_id)

    def assignment(self, device_id: str) -> str:
        """The profile a device belongs to; the built-in empty one by default."""
        profile_id = self._assignments.get(device_id, EMPTY_PROFILE_ID)
        return profile_id if profile_id in self._profiles else EMPTY_PROFILE_ID

    def profile_of(self, device_id: str) -> Profile:
        """The profile a device belongs to, never None."""
        return self.get(self.assignment(device_id)) or empty_profile()

    def members(self, profile_id: str) -> list[str]:
        """Paired devices assigned to a profile, in a stable order."""
        return sorted(
            device_id for device_id in self._hub.devices if self.assignment(device_id) == profile_id
        )

    def overrides(self, device_id: str) -> dict[str, Any]:
        """A copy of a device's overrides (only keys its profile covers)."""
        profile = self.profile_of(device_id)
        return {
            key: copy.deepcopy(value)
            for key, value in self._overrides.get(device_id, {}).items()
            if profile.covers(key)
        }

    def effective_values(self, device_id: str) -> dict[str, Any]:
        """The effective value of every key the device's profile covers (ADR 0035 item 2).

        Per key: the override if one is set, else the profile's value. Uncovered
        keys are absent: a profile never writes them.
        """
        profile = self.profile_of(device_id)
        overrides = self.overrides(device_id)
        return {
            key: copy.deepcopy(overrides.get(key, value)) for key, value in profile.values.items()
        }

    def stored_value(self, device_id: str, key: str) -> Any:
        """The device's own stored value for a leaf, or NO_DEFAULT when it has none."""
        field_ = self._hub.settings.schema.field(key)
        stored = self._hub.settings.get(device_id)
        if stored is None:
            return NO_DEFAULT
        value = get_path(stored.settings, field_.path)
        return NO_DEFAULT if value is NO_DEFAULT else value

    def changes_for(self, device_id: str) -> dict[str, Any]:
        """The effective values that differ from what the device's document holds."""
        return {
            key: value
            for key, value in self.effective_values(device_id).items()
            if not _same(self.stored_value(device_id, key), value)
        }

    # --- writes ------------------------------------------------------------

    async def async_load(self) -> None:
        """Load stored profiles, dropping invalid entries with a log entry."""
        data = await self._store.async_load() or {}
        schema = self._hub.settings.schema
        self._profiles = {}
        self._assignments = {}
        self._overrides = {}
        for info in data.get("profiles", []):
            try:
                profile_id = str(info["profile_id"])
                name = str(info["name"]).strip()
                if not profile_id or profile_id == EMPTY_PROFILE_ID or not name:
                    raise ValueError(f"unusable profile {profile_id!r}")
                accepted, rejected = split_shared_values(schema, info.get("values") or {})
                if rejected:
                    raise ValueError(f"refused keys {rejected}")
            except (KeyError, TypeError, ValueError) as e:
                _LOGGER.error("Dropping stored profile: %s", e)
                continue
            self._profiles[profile_id] = Profile(profile_id, name, accepted)
        for device_id, profile_id in (data.get("assignments") or {}).items():
            if profile_id in self._profiles:
                self._assignments[str(device_id)] = profile_id
        for device_id, overrides in (data.get("overrides") or {}).items():
            assigned = self.get(self.assignment(str(device_id)))
            covered = assigned.values if assigned is not None else {}
            kept = {str(key): value for key, value in (overrides or {}).items() if key in covered}
            if kept:
                self._overrides[str(device_id)] = kept

    async def _async_persist(self) -> None:
        await self._store.async_save(
            {
                "profiles": [p.to_storage() for p in self._profiles.values()],
                "assignments": self._assignments,
                "overrides": self._overrides,
            }
        )

    def _check_name(self, name: str, *, profile_id: str | None) -> str:
        """The trimmed name, or ProfileError with every reason it cannot be used."""
        trimmed = name.strip()
        problems: list[str] = []
        if not trimmed:
            problems.append("A profile needs a name.")
        elif len(trimmed) > PROFILE_NAME_MAX:
            problems.append(f"A profile name is at most {PROFILE_NAME_MAX} characters.")
        elif trimmed.casefold() == EMPTY_PROFILE_NAME.casefold():
            problems.append(f'"{EMPTY_PROFILE_NAME}" is the built-in profile\'s name.')
        for profile in self._profiles.values():
            if profile.profile_id != profile_id and profile.name.casefold() == trimmed.casefold():
                problems.append(f'Another profile is already named "{profile.name}".')
        if problems:
            raise ProfileError(problems)
        return trimmed

    async def async_save(
        self, *, profile_id: str | None, name: str, values: dict[str, Any]
    ) -> tuple[Profile, list[dict[str, str]]]:
        """Create a profile (``profile_id`` None) or replace one, as one revision.

        Returns the saved profile and the keys it refused with a reason each
        (ADR 0035 item 8). The saved profile covers the accepted keys only; the
        refused ones are not stored, so the response is what the panel shows.
        """
        if profile_id == EMPTY_PROFILE_ID:
            raise ProfileError(["The built-in profile cannot be edited."])
        if profile_id is not None and profile_id not in self._profiles:
            raise ProfileError([f"No profile has the id {profile_id!r}."])
        trimmed = self._check_name(name, profile_id=profile_id)
        accepted, rejected = split_shared_values(self._hub.settings.schema, values)
        target_id = profile_id
        if target_id is None:
            if len(self._profiles) >= PROFILE_MAX:
                raise ProfileError([f"At most {PROFILE_MAX} profiles are supported."])
            target_id = ulid_now()
        self._profiles[target_id] = Profile(target_id, trimmed, accepted)
        self._drop_dead_overrides()
        await self._async_persist()
        saved = self.get(target_id)
        assert saved is not None
        return saved, rejected

    async def async_delete(self, profile_id: str) -> list[str]:
        """Remove a profile; its members move to the empty one (F-MGT-02).

        Nothing is written to the members' settings: they keep the last applied
        values as their own (ADR 0035, consequences). The overrides that belonged
        to the old profile go with it.
        """
        if profile_id == EMPTY_PROFILE_ID:
            raise ProfileError(["The built-in profile cannot be deleted."])
        if profile_id not in self._profiles:
            raise ProfileError([f"No profile has the id {profile_id!r}."])
        moved = self.members(profile_id)
        del self._profiles[profile_id]
        for device_id in moved:
            self._assignments.pop(device_id, None)
        self._drop_dead_overrides()
        await self._async_persist()
        return moved

    async def async_assign(self, device_id: str, profile_id: str) -> dict[str, Any]:
        """Assign a device to a profile and apply what that changes (ADR 0035 item 2)."""
        if self.get(profile_id) is None:
            raise ProfileError([f"No profile has the id {profile_id!r}."])
        if profile_id == EMPTY_PROFILE_ID:
            self._assignments.pop(device_id, None)
        else:
            self._assignments[device_id] = profile_id
        # An override only exists while its key is covered (ADR 0035 item 5).
        self._drop_dead_overrides()
        await self._async_persist()
        return await self.async_apply(device_id)

    async def async_set_override(self, device_id: str, key: str, value: Any) -> dict[str, Any]:
        """Set (or clear, with None) one key's override and apply the result.

        Only a key the device's profile covers can be overridden: any other key
        is the device's own value already, and an override there would only be a
        name for it. Clearing an override applies the profile's value.
        """
        profile = self.profile_of(device_id)
        if not profile.covers(key):
            raise ProfileError([f"This device's profile does not cover {key!r}."])
        overrides = self._overrides.setdefault(device_id, {})
        if value is None:
            overrides.pop(key, None)
        else:
            accepted, rejected = split_shared_values(self._hub.settings.schema, {key: value})
            if rejected:
                raise ProfileError([item["reason"] for item in rejected])
            overrides[key] = accepted[key]
        if not overrides:
            self._overrides.pop(device_id, None)
        await self._async_persist()
        return await self.async_apply(device_id)

    async def async_record_overrides(self, device_id: str, values: dict[str, Any]) -> None:
        """Turn a per-device write of covered keys into overrides (ADR 0035 item 1).

        Called after every write that is not a profile application. A value that
        differs from the profile's is an explicit per-device choice; a value that
        equals it clears the override, because the two are the same state.
        """
        profile = self.profile_of(device_id)
        if not profile.values:
            return
        overrides = self._overrides.get(device_id, {})
        changed = False
        for key, value in values.items():
            if not profile.covers(key):
                continue
            if _same(value, profile.values[key]):
                if overrides.pop(key, NO_DEFAULT) is not NO_DEFAULT:
                    changed = True
            elif not _same(overrides.get(key, NO_DEFAULT), value):
                overrides[key] = copy.deepcopy(value)
                changed = True
        if not changed:
            return
        if overrides:
            self._overrides[device_id] = overrides
        else:
            self._overrides.pop(device_id, None)
        await self._async_persist()

    async def async_record_document_overrides(
        self, device_id: str, before: dict[str, Any] | None, after: dict[str, Any]
    ) -> None:
        """Turn the covered leaves a whole-document write changed into overrides.

        The options flow submits a whole settings document rather than a dict of
        leaves, and ADR 0035 item 1 names it among the writers that create an
        override, so the changed keys are worked out from the two documents:
        ``before`` is what the save replaced (None when the device had none) and
        ``after`` is the document it wrote.
        """
        profile = self.profile_of(device_id)
        if not profile.values:
            return
        schema = self._hub.settings.schema
        changed: dict[str, Any] = {}
        for key in profile.values:
            path = schema.field(key).path
            old = get_path(before, path) if before is not None else NO_DEFAULT
            new = get_path(after, path)
            if new is NO_DEFAULT or _same(old, new):
                continue
            changed[key] = new
        if changed:
            await self.async_record_overrides(device_id, changed)

    async def async_remove_device(self, device_id: str) -> None:
        """Forget a device's assignment and overrides (its record is gone)."""
        changed = self._assignments.pop(device_id, None) is not None
        changed = self._overrides.pop(device_id, None) is not None or changed
        if changed:
            await self._async_persist()

    def _drop_dead_overrides(self) -> None:
        """Drop every override no profile covers any more (ADR 0035 item 5)."""
        for device_id in list(self._overrides):
            profile = self.profile_of(device_id)
            kept = {
                key: value
                for key, value in self._overrides[device_id].items()
                if profile.covers(key)
            }
            if kept:
                self._overrides[device_id] = kept
            else:
                del self._overrides[device_id]

    # --- application -------------------------------------------------------

    async def async_apply(self, device_id: str) -> dict[str, Any]:
        """Write a device's effective values as one revision (ADR 0035 item 2).

        Only keys whose effective value differs from the device's document are
        sent, so an already-current member costs no revision and no push. The
        write skips override recording: it carries the profile's values, not a
        user's per-device exception.
        """
        changes = self.changes_for(device_id)
        record = self._hub.devices.get(device_id)
        result: dict[str, Any] = {
            "device_id": device_id,
            "connected": record.connected if record is not None else False,
            "applied": sorted(changes),
            "revision": None,
        }
        if not changes:
            return result
        saved = await async_set_settings(self._hub, device_id, changes, record_overrides=False)
        result["revision"] = saved.revision
        return result

    async def async_apply_profile(
        self, profile_id: str, device_ids: list[str] | None = None
    ) -> list[dict[str, Any]]:
        """Apply a profile to its members (all of them unless named)."""
        targets = device_ids if device_ids is not None else self.members(profile_id)
        return [await self.async_apply(device_id) for device_id in targets]

    # --- diff and drift ----------------------------------------------------

    def diff(
        self,
        *,
        profile_id: str | None = None,
        values: dict[str, Any] | None = None,
        device_ids: list[str] | None = None,
    ) -> list[dict[str, Any]]:
        """What a profile would change, per member and key (ADR 0035 item 7).

        ``values`` previews an unsaved document: only the shared keys the schema
        accepts take part. ``profile_id`` None diffs the values alone; otherwise
        its members are the default targets. A row carries the profile's value,
        the member's current value and whether the write would change it.
        """
        profile = self.get(profile_id) if profile_id is not None else None
        if profile_id is not None and profile is None:
            raise ProfileError([f"No profile has the id {profile_id!r}."])
        if values is None:
            covered = profile.values if profile is not None else {}
        else:
            covered, _ = split_shared_values(self._hub.settings.schema, values)
        if device_ids is not None:
            targets = device_ids
        elif profile is not None:
            # A preview still diffs against the profile's members.
            targets = self.members(profile.profile_id)
        else:
            targets = []
        rows: list[dict[str, Any]] = []
        for device_id in targets:
            overrides = self.overrides(device_id)
            for key, value in covered.items():
                effective = overrides.get(key, value)
                current = self.stored_value(device_id, key)
                rows.append(
                    {
                        "device_id": device_id,
                        "key": key,
                        "profile_value": copy.deepcopy(value),
                        "effective_value": copy.deepcopy(effective),
                        "device_value": None if current is NO_DEFAULT else copy.deepcopy(current),
                        "device_unset": current is NO_DEFAULT,
                        "overridden": key in overrides,
                        "changes": not _same(current, effective),
                    }
                )
        return rows

    def drift(self, profile_id: str | None = None) -> list[dict[str, Any]]:
        """Members that disagree with HA, by revision or by what they can take.

        Revision-based, not value-based (ADR 0035 item 7): TLP has no upload
        direction, so HA knows only the revision the device reported. A member is
        listed when that revision differs from HA's, when it has an override
        shadowing a covered key, or when its settings schema predates a covered
        key the push translates away.
        """
        schema = self._hub.settings.schema
        rows: list[dict[str, Any]] = []
        for device_id in sorted(self._hub.devices):
            profile = self.profile_of(device_id)
            if profile_id is not None and profile.profile_id != profile_id:
                continue
            record = self._hub.devices[device_id]
            stored = self._hub.settings.get(device_id)
            # A live session's report is the newest; otherwise the last one it
            # gave before the socket closed (persisted, ADR 0035 item 7).
            session = record.current
            reported = (
                session.reported_settings_revision
                if session is not None
                else record.last_settings_revision
            )
            ha_revision = stored.revision if stored is not None else 0
            device_schema = record.schema_version
            behind_keys = [
                key
                for key in profile.values
                if device_schema is not None and schema.since(key) > device_schema
            ]
            overrides = self.overrides(device_id)
            drifted = reported is not None and reported != ha_revision
            if not drifted and not overrides and not behind_keys:
                continue
            rows.append(
                {
                    "device_id": device_id,
                    "ha_device_id": record.ha_device_id,
                    "name": record.name,
                    "profile_id": profile.profile_id,
                    "profile_name": profile.name,
                    "connected": record.connected,
                    "last_seen": record.last_seen,
                    "ha_revision": ha_revision,
                    "device_revision": reported,
                    # How far behind HA's copy: positive means the device owes
                    # revisions, negative means it reported one HA never made.
                    "revisions_behind": (ha_revision - reported) if reported is not None else None,
                    "drifted": drifted,
                    "overrides": [
                        {"key": key, "value": copy.deepcopy(value)}
                        for key, value in overrides.items()
                    ],
                    "behind_keys": behind_keys,
                }
            )
        return rows
