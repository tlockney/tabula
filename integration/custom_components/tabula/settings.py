"""Per-device settings: the schema-backed document, the HA Store, and the push.

Settings are data, not entities (plan 5.6). HA is authoritative: every paired
device's settings document lives here in one HA Store, keyed by the Tabula
device ID, with a per-device ``revision`` that counts data changes only
(plan 5.4). Edits arrive through the options flow (options_flow.py), which
saves here and pushes the document to the device with a ``set_settings``
command (PROTOCOL.md 4.5); the device ACKs with the revision it applied.

The shape of a document is schema/settings.schema.json (P0.5), shipped as a
bundled copy next to this file so the integration is self-contained. The
options-flow form is generated from that schema's role and feature tags; no
hand-kept field list exists in code. A property whose role is ``hardware`` is a
device fact HA does not set and never appears in the form (F-MGT-03); v1 has
none, and the code must not assume any role tag has members.

Documents are stored at the integration's current schema version
(const.SCHEMA_VERSION). A device that registered with an older one receives a
translation down (migrations/), dropping keys it does not know (plan 5.4).
"""

from __future__ import annotations

import copy
import json
import logging
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING, Any

from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.storage import Store
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from . import migrations
from .const import DOMAIN, SCHEMA_VERSION, SIGNAL_SETTINGS_UPDATE

if TYPE_CHECKING:
    from . import DeviceRecord, TabulaHub

_LOGGER = logging.getLogger(__name__)

BUNDLED_SCHEMA = Path(__file__).parent / "settings.schema.json"
STORAGE_KEY = f"{DOMAIN}.settings"
STORAGE_VERSION = 1

# Roles the options flow may edit (F-MGT-03): everything except hardware facts.
EDITABLE_ROLES = frozenset({"identity", "connection", "shared"})

NO_DEFAULT = object()


class SettingsError(ValueError):
    """A settings document does not match the schema."""

    def __init__(self, problems: list[str]) -> None:
        self.problems = problems
        super().__init__("; ".join(problems))


@dataclass(frozen=True)
class SettingsField:
    """One editable leaf of the settings schema, addressed by its full path.

    ``type`` is "array" for list-valued leaves (settings v2); ``array_items``
    then says whether the items are scalars or objects, which is what decides
    the options-flow editor (options_flow.py).
    """

    path: tuple[str, ...]
    role: str
    feature: str
    type: str  # JSON schema type: "integer", "boolean", "string" or "array"
    enum: tuple[str, ...] | None = None
    minimum: int | None = None
    maximum: int | None = None
    min_length: int | None = None
    max_length: int | None = None
    default: Any = NO_DEFAULT  # NO_DEFAULT: no schema default (unset)
    array_items: str | None = None  # "scalar" | "object" when type is "array"

    @property
    def key(self) -> str:
        """The flat form key: the path joined with underscores."""
        return "_".join(self.path)


def _document() -> dict[str, Any]:
    """The bundled settings schema document (shared, immutable)."""
    return json.loads(BUNDLED_SCHEMA.read_text(encoding="utf-8"))


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    doc = _document()
    if doc.get("x-tabula-schema-version") != SCHEMA_VERSION:
        # Programming error: the bundled schema and const.py must move together.
        bundled = doc.get("x-tabula-schema-version")
        raise SettingsError([f"bundled schema is version {bundled}, const says {SCHEMA_VERSION}"])
    Draft202012Validator.check_schema(doc)
    registry = Registry().with_resource(doc["$id"], Resource.from_contents(doc))
    return Draft202012Validator(
        doc, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
    )


@lru_cache(maxsize=1)
def _fields() -> tuple[SettingsField, ...]:
    """Every leaf property of the schema, in document order."""
    doc = _document()
    out: list[SettingsField] = []

    def walk(properties: dict[str, Any], path: tuple[str, ...]) -> None:
        for name, prop in properties.items():
            if prop.get("type") == "object":
                walk(prop["properties"], (*path, name))
                continue
            if prop.get("type") == "array":
                # List-valued leaves (settings v2): the leaf is the whole
                # array; its items' own structure stays inside the schema and
                # is validated there. The options flow picks its editor from
                # array_items.
                items = prop.get("items")
                out.append(
                    SettingsField(
                        path=(*path, name),
                        role=prop["x-tabula-role"],
                        feature=prop["x-tabula-feature"],
                        type="array",
                        maximum=prop.get("maxItems"),
                        default=prop.get("default", NO_DEFAULT),
                        array_items="object"
                        if isinstance(items, dict) and items.get("type") == "object"
                        else "scalar",
                    )
                )
                continue
            # Enum-only leaves carry no "type"; every enum is strings.
            out.append(
                SettingsField(
                    path=(*path, name),
                    role=prop["x-tabula-role"],
                    feature=prop["x-tabula-feature"],
                    # Enum-only leaves carry no "type"; every v1 enum is strings.
                    type=prop.get("type", "string"),
                    enum=tuple(prop["enum"]) if "enum" in prop else None,
                    minimum=prop.get("minimum"),
                    maximum=prop.get("maximum"),
                    min_length=prop.get("minLength"),
                    max_length=prop.get("maxLength"),
                    default=prop.get("default", NO_DEFAULT),
                )
            )

    walk(doc["properties"], ())
    keys = [f.key for f in out]
    if len(set(keys)) != len(keys):
        raise SettingsError([f"flat settings keys are not unique: {keys}"])
    return tuple(out)


class SettingsSchema:
    """The bundled settings schema: validation, defaults and editable fields."""

    @property
    def version(self) -> int:
        return SCHEMA_VERSION

    def validate(self, document: dict[str, Any]) -> dict[str, Any]:
        """Validate a full document; raises SettingsError with every problem."""
        errors = sorted(_validator().iter_errors(document), key=lambda e: list(e.path))
        if errors:
            raise SettingsError(
                [f"{'/'.join(map(str, e.path)) or '(root)'}: {e.message}" for e in errors]
            )
        return document

    def defaults(self) -> dict[str, Any]:
        """The document with every schema default applied; groups whose leaves
        have no defaults (identity, connection) are absent."""
        document: dict[str, Any] = {}
        for field_ in _fields():
            if field_.default is not NO_DEFAULT:
                # A deep copy per leaf: array defaults ([] for settings v2)
                # are mutable, and every call must return an independent
                # document.
                set_path(document, field_.path, copy.deepcopy(field_.default))
        return document

    def fields(self) -> tuple[SettingsField, ...]:
        """Every leaf property, any role."""
        return _fields()

    def editable_fields(self) -> tuple[SettingsField, ...]:
        """Leaves the options flow may set (F-MGT-03): not hardware facts."""
        return tuple(f for f in _fields() if f.role in EDITABLE_ROLES)

    def field(self, key: str) -> SettingsField:
        """The leaf with this flat key; KeyError when the schema has none."""
        for field_ in _fields():
            if field_.key == key:
                return field_
        raise KeyError(key)

    def since(self, key: str) -> int:
        """The settings schema version that introduced this leaf (P3.2).

        The oldest frozen schema that knows the leaf's whole path answers; a
        leaf none of them knows arrived with the current version. Settings
        entities exist only for devices whose registered schema_version knows
        their key, since an older device never receives it (plan 5.4,
        migrations.DOWNS).
        """
        path = self.field(key).path
        for version in migrations.frozen_schema_versions():
            node: Any = migrations.frozen_schema(version)
            for part in path:
                node = node.get("properties", {}).get(part) if isinstance(node, dict) else None
                if node is None:
                    break
            else:
                return version
        return SCHEMA_VERSION


def get_path(document: dict[str, Any] | None, path: tuple[str, ...]) -> Any:
    """The value at ``path``, or NO_DEFAULT when any part of it is absent."""
    node: Any = document
    for key in path:
        if not isinstance(node, dict) or key not in node:
            return NO_DEFAULT
        node = node[key]
    return node


def set_path(document: dict[str, Any], path: tuple[str, ...], value: Any) -> None:
    """Set ``value`` at ``path``, creating intermediate groups as needed."""
    node = document
    for key in path[:-1]:
        node = node.setdefault(key, {})
    node[path[-1]] = value


def delete_path(document: dict[str, Any], path: tuple[str, ...]) -> None:
    """Remove the leaf at ``path`` when present (an unset value stays unset)."""
    node: Any = document
    for key in path[:-1]:
        if not isinstance(node, dict) or key not in node:
            return
        node = node[key]
    if isinstance(node, dict):
        node.pop(path[-1], None)


@dataclass
class DeviceSettings:
    """One device's stored settings document and its revision."""

    revision: int
    settings: dict[str, Any]


class SettingsStore:
    """Per-device settings documents in one HA Store (plan 5.6).

    Layout: ``{"devices": {device_id: {"revision": n, "settings": {...}}}}``.
    A document is always complete: saving fills schema defaults for keys the
    caller omitted, so the push replaces the device's copy wholesale
    (PROTOCOL.md 4.5 - a key HA does not send keeps the device default, which
    is what an absent key in the document means). The revision starts at 1
    with the first document and counts data changes only (plan 5.4).
    """

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass
        self._store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        self._devices: dict[str, DeviceSettings] = {}
        self.schema = SettingsSchema()

    def get(self, device_id: str) -> DeviceSettings | None:
        """A copy of the device's stored document, or None when there is none."""
        stored = self._devices.get(device_id)
        if stored is None:
            return None
        return DeviceSettings(stored.revision, copy.deepcopy(stored.settings))

    def value(self, device_id: str, key: str) -> Any:
        """One leaf of the device's document by flat key (settings entities, P3.2).

        A device with no stored document reads the schema default: that is
        what it runs with, and what the first save will store.
        """
        field_ = self.schema.field(key)
        stored = self._devices.get(device_id)
        value = get_path(stored.settings, field_.path) if stored is not None else NO_DEFAULT
        if value is NO_DEFAULT:
            value = field_.default
        return None if value is NO_DEFAULT else copy.deepcopy(value)

    def revision(self, device_id: str) -> int:
        """The revision register_result reports (0 until a document is stored)."""
        stored = self._devices.get(device_id)
        return stored.revision if stored is not None else 0

    async def async_load(self) -> None:
        """Load stored documents; invalid entries are dropped with a log entry.

        Keys added by a newer schema version are filled from the defaults -
        a schema change, not a data change, so the revision is untouched.
        """
        data = await self._store.async_load()
        self._devices = {}
        for device_id, info in (data or {}).get("devices", {}).items():
            try:
                revision = int(info["revision"])
                document = self._merge_defaults(info["settings"])
                self.schema.validate(document)
                if revision < 1:
                    raise ValueError(f"revision {revision} is not positive")
            except (KeyError, TypeError, ValueError, SettingsError) as e:
                _LOGGER.error("Dropping stored settings for %s: %s", device_id, e)
                continue
            self._devices[device_id] = DeviceSettings(revision, document)

    def _merge_defaults(self, settings: dict[str, Any]) -> dict[str, Any]:
        """A stored document upgraded to the current schema version.

        Nested objects merge recursively (settings v2 nests groups under
        ``screensaver`` and per-widget objects under ``widgets``), so a
        partial nested document keeps the defaults of the keys it omits.
        Arrays and ``null`` (the schedule's "inherit") are values, not groups:
        they replace, not merge.
        """

        def merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
            document = dict(base)
            for key, value in override.items():
                if isinstance(value, dict) and isinstance(document.get(key), dict):
                    document[key] = merge(document[key], value)
                else:
                    document[key] = value
            return document

        return merge(self.schema.defaults(), settings)

    async def async_save(self, device_id: str, settings: dict[str, Any]) -> DeviceSettings:
        """Fill defaults, validate and store the next revision of a document."""
        document = self._merge_defaults(settings)
        self.schema.validate(document)
        old = self._devices.get(device_id)
        stored = DeviceSettings(old.revision + 1 if old is not None else 1, document)
        self._devices[device_id] = stored
        await self._store.async_save(self._data())
        # settings entities (P3.2) show HA's copy: every save refreshes them
        async_dispatcher_send(self._hass, SIGNAL_SETTINGS_UPDATE.format(device_id))
        return DeviceSettings(stored.revision, copy.deepcopy(stored.settings))

    async def async_remove(self, device_id: str) -> None:
        """Forget a device's settings (its HA device or the hub entry is gone)."""
        if self._devices.pop(device_id, None) is None:
            return
        await self._store.async_save(self._data())

    def _data(self) -> dict[str, Any]:
        return {
            "devices": {
                device_id: {"revision": s.revision, "settings": s.settings}
                for device_id, s in self._devices.items()
            }
        }


async def async_push_settings(hub: TabulaHub, record: DeviceRecord) -> int | None:
    """Push the stored document to the device and await the ACK (4.5).

    Returns the revision the device applied, or None when HA has no document.
    Raises HomeAssistantError when the device cannot take the push; the
    document stays stored either way and the next registration reconciles
    (schedule_settings_push).
    """
    stored = hub.settings.get(record.device_id)
    if stored is None:
        return None
    # plan 5.4: translate down for a device on an older settings schema.
    target = min(SCHEMA_VERSION, record.schema_version or SCHEMA_VERSION)
    document = (
        stored.settings
        if target == SCHEMA_VERSION
        else migrations.translate(stored.settings, target)
    )
    session = record.current
    data = await hub.async_command(
        record,
        "set_settings",
        {"revision": stored.revision, "schema_version": target, "settings": document},
    )
    applied = data.get("revision")
    # The ACK is the device's new reported revision (PROTOCOL.md 4.6): the
    # next drift check compares against it, not the register-time report.
    # Only on the session that took it: a reconnect mid-push reports its own.
    if applied is not None and session is not None and record.current is session:
        session.reported_settings_revision = applied
    return applied


async def _push_task(hub: TabulaHub, record: DeviceRecord) -> None:
    """Best effort: HA is authoritative, so a failed push is logged, not raised.

    The document is saved before this runs; when the device cannot take it now
    (offline, NACK, timeout) the next registration reconciles the revisions
    (PROTOCOL.md 4.2, schedule_settings_push).
    """
    try:
        applied = await async_push_settings(hub, record)
    except (HomeAssistantError, ValueError, RuntimeError) as e:
        # ValueError/RuntimeError: no translation down to the device's schema.
        _LOGGER.warning(
            "Settings push to %s failed (%s); it will be retried at the next registration",
            record.name or record.device_id[:8],
            e,
        )
        return
    _LOGGER.info(
        "Settings for %s applied at revision %s",
        record.name or record.device_id[:8],
        applied,
    )


@callback
def schedule_settings_push(hub: TabulaHub, record: DeviceRecord) -> None:
    """Push the stored document when the device has not applied it (4.2, 4.5).

    Called right after the subscription opens, and after an options-flow save.
    One rule covers both (plan 5.6: HA is authoritative): a document exists and
    the device's reported revision differs from HA's - including a device
    AHEAD of HA, which applies HA's copy whatever its revision (PROTOCOL.md
    4.5). With no stored document there is nothing to be authoritative about,
    so a device that has never received settings keeps its own defaults.
    """
    session = record.current
    if session is None or session.subscription_id is None:
        return
    stored = hub.settings.get(record.device_id)
    if stored is None or session.reported_settings_revision == stored.revision:
        return
    hub.hass.async_create_task(_push_task(hub, record))


async def async_set_setting(
    hub: TabulaHub, device_id: str, settings_key: str, value: Any
) -> DeviceSettings:
    """Write one settings leaf from an entity action (F-MGT-01).

    Entities that map to a settings leaf (the P1.3 switch/number/select) go
    through the store, never straight to a command: HA owns every setting, so
    the write bumps the revision and pushes like any other edit, and an
    offline tablet is reconciled at its next registration. ``settings_key`` is
    the schema's flat field key (SettingsField.key, unique by construction).
    """
    return await async_set_settings(hub, device_id, {settings_key: value})


async def async_set_settings(
    hub: TabulaHub, device_id: str, values: dict[str, Any]
) -> DeviceSettings:
    """Write several settings leaves as one revision (see async_set_setting).

    One save and one push, so a device never sees half of a change that
    belongs together (the manual brightness level and adaptive off, ADR 0011).
    """
    fields = {f.key: f for f in hub.settings.schema.fields()}
    unknown = [key for key in values if key not in fields]
    if unknown:
        raise SettingsError([f"unknown settings key: {key}" for key in unknown])
    stored = hub.settings.get(device_id)
    document = stored.settings if stored is not None else hub.settings.schema.defaults()
    for key, value in values.items():
        set_path(document, fields[key].path, value)
    saved = await hub.settings.async_save(device_id, document)
    if (record := hub.devices.get(device_id)) is not None:
        schedule_settings_push(hub, record)
    return saved
