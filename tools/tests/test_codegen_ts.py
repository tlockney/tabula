"""Tests for the TypeScript settings codegen the panel uses (packet P5.1)."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
from custom_components.tabula import settings as integration_settings

from tools import codegen_ts

ROOT = Path(__file__).resolve().parents[2]


def schema() -> dict[str, Any]:
    return json.loads(codegen_ts.SCHEMA.read_text(encoding="utf-8"))


def ts_default(value: Any) -> str:
    """The generated literal for a schema default, matching settings.py's sentinel."""
    if value is integration_settings.NO_DEFAULT:
        return "NO_DEFAULT"
    return json.dumps(value)


def parse_fields(source: str) -> list[dict[str, str]]:
    """The generated SETTINGS_FIELDS table as one dict per entry."""
    body = source.split("export const SETTINGS_FIELDS: readonly SettingsField[] = [", 1)[1]
    body = body.split("\n];", 1)[0]
    entries: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for raw in body.splitlines():
        line = raw.strip()
        if line == "{":
            current = {}
        elif line == "},":
            assert current is not None
            entries.append(current)
            current = None
        elif current is not None and ": " in line:
            name, _, value = line.partition(": ")
            current[name] = value.rstrip(",")
    return entries


def leaves(node: dict[str, Any], path: str = "") -> list[tuple[str, dict[str, Any]]]:
    """Every leaf property of a schema document, in document order."""
    out: list[tuple[str, dict[str, Any]]] = []
    for name, prop in node.get("properties", {}).items():
        if prop.get("type") == "object":
            out.extend(leaves(prop, f"{path}{name}."))
        else:
            out.append((f"{path}{name}", prop))
    return out


def test_generated_file_matches_schema():
    """The committed TypeScript module is exactly what the schema generates."""
    committed = codegen_ts.OUT.read_text(encoding="utf-8")
    assert codegen_ts.generate(schema()) == committed


def test_generation_is_deterministic():
    first = codegen_ts.generate(schema())
    second = codegen_ts.generate(schema())
    assert first == second


def test_schema_version_and_roles():
    doc = schema()
    source = codegen_ts.generate(doc)
    assert f"export const SCHEMA_VERSION = {doc['x-tabula-schema-version']};" in source
    assert f"const SCHEMA_VERSION = {integration_settings.SCHEMA_VERSION}" in source
    roles = ", ".join(json.dumps(role) for role in doc["x-tabula-roles"])
    assert f"export const SETTINGS_ROLES = [{roles}] as const;" in source


def test_every_schema_leaf_has_a_role_and_a_feature():
    for path, prop in leaves(schema()):
        assert prop.get("x-tabula-role"), f"{path} has no x-tabula-role"
        assert prop.get("x-tabula-feature"), f"{path} has no x-tabula-feature"


def test_field_table_matches_the_integration():
    """The panel's table and settings.py `_fields()` cannot disagree on a key."""
    entries = parse_fields(codegen_ts.generate(schema()))
    fields = integration_settings._fields()
    assert len(entries) == len(fields)
    for entry, field in zip(entries, fields, strict=True):
        path = ".".join(field.path)
        assert entry["path"] == json.dumps(path), path
        assert entry["key"] == json.dumps(field.key), path
        assert entry["role"] == json.dumps(field.role), path
        assert entry["feature"] == json.dumps(field.feature), path
        assert entry["type"] == json.dumps(field.type), path
        expected_enum = (
            "null" if field.enum is None else "[" + ", ".join(map(json.dumps, field.enum)) + "]"
        )
        assert entry["enumValues"] == expected_enum, path
        assert entry["minimum"] == ts_default(field.minimum), path
        assert entry["maximum"] == ts_default(field.maximum), path
        assert entry["minLength"] == ts_default(field.min_length), path
        assert entry["maxLength"] == ts_default(field.max_length), path
        assert entry["default"] == ts_default(field.default), path


def test_defaults_document_holds_every_group_and_the_schema_defaults():
    source = codegen_ts.generate(schema())
    defaults = source.split("export const SETTINGS_DEFAULTS: Settings = ", 1)[1]
    for group in schema()["properties"]:
        assert f"\n  {group}: " in defaults, group
    # A representative defaulted leaf from each shape: number, boolean, string,
    # enum and the empty list.
    screen = schema()["properties"]["screen"]["properties"]
    assert f"brightness: {ts_default(screen['brightness']['default'])}," in defaults
    assert f"adaptive_brightness: {ts_default(screen['adaptive_brightness']['default'])}," in (
        defaults
    )
    screensaver = schema()["properties"]["screensaver"]["properties"]
    assert f"mode: {ts_default(screensaver['mode']['default'])}," in defaults
    assert "slots: []," in defaults


def test_roles_declared_but_unused_are_still_listed():
    """No leaf carries 'hardware' yet (settings v3); codegen must not drop it."""
    source = codegen_ts.generate(schema())
    assert '"hardware"' in source.split("export interface SettingsField", 1)[0]
    assert not any(prop["x-tabula-role"] == "hardware" for _, prop in leaves(schema())), (
        "the schema gained a hardware leaf: this test's premise changed"
    )


@pytest.mark.parametrize(
    "mutate",
    [
        pytest.param(
            lambda doc: doc["properties"]["identity"]["properties"]["name"].update(
                {"type": "array", "items": {"type": "array"}, "default": []}
            ),
            id="array-of-arrays",
        ),
        pytest.param(
            lambda doc: (
                doc["properties"]["screen"]["properties"]["brightness"].update({"type": "boolean"}),
                doc["properties"]["screen"]["properties"]["brightness"].pop("default"),
            ),
            id="boolean-without-default",
        ),
        pytest.param(
            lambda doc: doc["properties"]["screensaver"]["properties"]["mode"].update(
                {"enum": ["clock"], "default": "weather"}
            ),
            id="enum-default-not-in-values",
        ),
        pytest.param(
            lambda doc: doc["properties"]["screen"].update({"additionalProperties": True}),
            id="object-allowing-extra-properties",
        ),
        pytest.param(
            lambda doc: doc["properties"]["identity"]["properties"]["name"].pop("x-tabula-role"),
            id="leaf-without-role",
        ),
        pytest.param(
            lambda doc: doc["properties"]["widgets"]["properties"]["small_clock"]["properties"][
                "enabled"
            ].pop("x-tabula-feature"),
            id="leaf-without-feature",
        ),
        pytest.param(
            lambda doc: doc["properties"].update({"not_a_group": {"type": "string"}}),
            id="root-not-an-object",
        ),
    ],
)
def test_unsupported_construct_fails(mutate):
    """A construct or missing tag must raise, not drop silently."""
    doc = copy.deepcopy(schema())
    mutate(doc)
    with pytest.raises(ValueError):
        codegen_ts.generate(doc)


def test_check_passes_on_the_committed_file(monkeypatch):
    """`make codegen-drift` is green when the committed module is current."""
    monkeypatch.setattr("sys.argv", ["codegen_ts", "--check"])
    assert codegen_ts.main() == 0


def test_check_fails_on_a_stale_file(monkeypatch, tmp_path):
    target = tmp_path / "settings.ts"
    target.write_text("// stale\n", encoding="utf-8")
    monkeypatch.setattr(codegen_ts, "ROOT", tmp_path)
    monkeypatch.setattr(codegen_ts, "OUT", target)
    monkeypatch.setattr("sys.argv", ["codegen_ts", "--check"])
    assert codegen_ts.main() == 1
