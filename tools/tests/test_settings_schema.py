"""Tests for the device settings schema (packet P0.5).

schema/settings.schema.json is the single source of truth for per-device
settings (plan 5.6). These tests pin what the packet requires:

- the schema is valid draft 2020-12 and declares settings schema version 1;
- the defaults fixture validates and equals the schema's own defaults;
- every other valid fixture validates; every known-bad fixture fails, at the
  spot invalid/EXPECTED.json records (same convention as the TLP fixtures);
- every property carries a role tag, and every default is transcribed from
  docs/decisions/0002-constants.md ("Phase 1-2 behavior defaults").
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from functools import cache
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "schema" / "settings.schema.json"
FIXTURES = ROOT / "schema" / "fixtures" / "settings" / "v1"

ROLES = {"identity", "connection", "hardware", "shared"}

# One row per leaf property: (role tag, default). Defaults are transcribed
# from docs/decisions/0002-constants.md; None means the property has no
# default (it keeps whatever the device already has).
#
# Role assignment (F-MGT-03, F-MGT-04): identity keys are never shared;
# connection keys (ha_url) are the only ones recovery may change (F-MGT-11)
# and are never shared either. Brightness and volume levels ARE shared: a
# profile may carry them, new profiles just start without them (F-MGT-04).
# No v1 leaf is "hardware" — F-MGT-03 hardware choices (audio output, UI
# scale) arrive with the phases that build them.
LEAF_SPEC: dict[tuple[str, ...], tuple[str, Any]] = {
    ("identity", "name"): ("identity", None),
    ("connection", "ha_url"): ("connection", None),
    ("screen", "brightness"): ("shared", 60),
    ("screen", "adaptive_brightness"): ("shared", False),
    ("screen", "keep_screen_on"): ("shared", True),
    ("screensaver", "enabled"): ("shared", True),
    ("screensaver", "idle_timeout"): ("shared", 300),
    ("screensaver", "mode"): ("shared", "clock"),
    ("screensaver", "brightness"): ("shared", 20),
    ("notifications", "default_duration"): ("shared", 10),
    ("notifications", "default_level"): ("shared", "info"),
    ("notifications", "chime_by_level", "info"): ("shared", False),
    ("notifications", "chime_by_level", "success"): ("shared", False),
    ("notifications", "chime_by_level", "warning"): ("shared", True),
    ("notifications", "chime_by_level", "error"): ("shared", True),
    ("notifications", "max_visible"): ("shared", 3),
    ("audio", "media_volume"): ("shared", 50),
    ("audio", "announce_volume_boost"): ("shared", "none"),
}


@cache
def schema() -> dict[str, Any]:
    doc = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(doc)
    return doc


@cache
def validator() -> Draft202012Validator:
    doc = schema()
    registry = Registry().with_resource(doc["$id"], Resource.from_contents(doc))
    return Draft202012Validator(
        doc, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
    )


def all_properties(
    node: dict[str, Any], path: tuple[str, ...] = ()
) -> Iterator[tuple[tuple[str, ...], dict[str, Any]]]:
    """Every property node at any depth, groups included."""
    for key, prop in node.get("properties", {}).items():
        yield (*path, key), prop
        yield from all_properties(prop, (*path, key))


def leaves(
    node: dict[str, Any], path: tuple[str, ...] = ()
) -> Iterator[tuple[tuple[str, ...], dict[str, Any]]]:
    """Every leaf property (one with no properties of its own)."""
    for key, prop in node.get("properties", {}).items():
        if "properties" in prop:
            yield from leaves(prop, (*path, key))
        else:
            yield (*path, key), prop


def nested_defaults() -> dict[str, Any]:
    """The schema's defaults as a settings document (only default-valued keys)."""
    out: dict[str, Any] = {}
    for path, prop in leaves(schema()):
        if "default" not in prop:
            continue
        node = out
        for part in path[:-1]:
            node = node.setdefault(part, {})
        node[path[-1]] = prop["default"]
    return out


# --- Schema shape ---


def test_schema_is_valid_and_declares_version_1() -> None:
    assert schema()["x-tabula-schema-version"] == 1
    assert schema()["x-tabula-roles"] == ["identity", "connection", "hardware", "shared"]


def test_leaf_set_matches_spec() -> None:
    assert {path for path, _ in leaves(schema())} == set(LEAF_SPEC)


def test_every_property_has_a_role_tag() -> None:
    for path, prop in all_properties(schema()):
        assert prop.get("x-tabula-role") in ROLES, f"{path}: missing or bad x-tabula-role"


def test_leaf_roles_match_spec() -> None:
    for path, prop in leaves(schema()):
        assert prop["x-tabula-role"] == LEAF_SPEC[path][0], path


def test_defaults_match_adr_0002() -> None:
    for path, prop in leaves(schema()):
        assert prop.get("default") == LEAF_SPEC[path][1], path


def test_every_leaf_traces_to_a_feature() -> None:
    for path, prop in leaves(schema()):
        trace = prop.get("x-tabula-feature")
        assert isinstance(trace, str) and trace, f"{path}: missing x-tabula-feature"


# --- Fixtures ---


@pytest.mark.parametrize("path", sorted(FIXTURES.glob("*.json")), ids=lambda p: p.name)
def test_valid_fixture_validates(path: Path) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    errors = list(validator().iter_errors(doc))
    assert not errors, [e.message for e in errors]


def test_defaults_fixture_is_exactly_the_schema_defaults() -> None:
    doc = json.loads((FIXTURES / "defaults.json").read_text(encoding="utf-8"))
    assert doc == nested_defaults()


def test_invalid_fixtures_fail_where_expected() -> None:
    expected = json.loads((FIXTURES / "invalid" / "EXPECTED.json").read_text(encoding="utf-8"))
    checked = 0
    for path in sorted((FIXTURES / "invalid").glob("*.json")):
        if path.name == "EXPECTED.json":
            continue
        doc = json.loads(path.read_text(encoding="utf-8"))
        errors = list(validator().iter_errors(doc))
        assert errors, f"{path.name}: expected to be invalid, but it validates"
        assert path.stem in expected, f"{path.name}: no entry in invalid/EXPECTED.json"
        pointers = {"/".join(str(p) for p in e.path) for e in errors}
        assert expected[path.stem] in pointers, f"{path.name}: fails at {sorted(pointers)}"
        checked += 1
    assert checked == len(expected), "invalid/EXPECTED.json has entries with no fixture"
