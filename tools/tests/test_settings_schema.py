"""Tests for the device settings schema (packets P0.5 and P3.1).

schema/settings.schema.json is the single source of truth for per-device
settings (plan 5.6). These tests pin what the packets require:

- the schema is valid draft 2020-12 and declares settings schema version 2;
- the v2 defaults fixture validates and equals the schema's own defaults;
- the v1 fixtures still validate: the v2 bump is additive (plan 5.4), which
  is what lets a stored v1 document load as v2 (SettingsStore) and what the
  down-translation depends on;
- every other valid fixture validates; every known-bad fixture fails, at the
  spot invalid/EXPECTED.json records (same convention as the TLP fixtures);
- every property carries a role tag, and every default is transcribed from
  docs/decisions/0002-constants.md ("Phase 1-2 behavior defaults" and
  "Settings schema v2 (P3.1)") — and, for the v2 leaves, that the ADR's v2
  section really carries each leaf's default and bounds in one of its table
  rows (the photos gap found in PR #73's second review is what this guards
  against; a missing row fails the test).
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
V1_SCHEMA_PATH = ROOT / "schema" / "settings.schema.v1.json"
FIXTURES = ROOT / "schema" / "fixtures" / "settings"
V1_FIXTURES = FIXTURES / "v1"
V2_FIXTURES = FIXTURES / "v2"

ROLES = {"identity", "connection", "hardware", "shared"}

# One row per leaf property: (role tag, default). Defaults are transcribed
# from docs/decisions/0002-constants.md; None means the property has no
# default (it keeps whatever the device already has).
#
# Role assignment (F-MGT-03, F-MGT-04): identity keys are never shared;
# connection keys (ha_url) are the only ones recovery may change (F-MGT-11)
# and are never shared either. Every v2 leaf is "shared" (no hardware facts
# arrive before the phases that build them).
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
    ("screensaver", "brightness_enabled"): ("shared", True),
    ("screensaver", "dim_brightness"): ("shared", 10),
    ("screensaver", "clock", "face"): ("shared", "digital"),
    ("screensaver", "clock", "font_family"): ("shared", "sans-serif"),
    ("screensaver", "clock", "font_weight"): ("shared", 400),
    ("screensaver", "clock", "hour_24"): ("shared", False),
    ("screensaver", "clock", "show_seconds"): ("shared", False),
    ("screensaver", "clock", "show_am_pm"): ("shared", True),
    ("screensaver", "clock", "show_date"): ("shared", True),
    ("screensaver", "clock", "size"): ("shared", 100),
    ("screensaver", "clock", "text_color"): ("shared", "#FFFFFFFF"),
    ("screensaver", "clock", "background_color"): ("shared", "#FF000000"),
    ("screensaver", "clock", "background_source"): ("shared", ""),
    ("screensaver", "clock", "background_refresh"): ("shared", 0),
    ("screensaver", "clock", "background_darken"): ("shared", 40),
    ("screensaver", "night_mode", "enabled"): ("shared", False),
    ("screensaver", "night_mode", "light_threshold"): ("shared", 10),
    ("screensaver", "night_mode", "hysteresis_lux"): ("shared", 2),
    ("screensaver", "night_mode", "hide_background"): ("shared", True),
    ("screensaver", "night_mode", "hide_overlays"): ("shared", True),
    ("screensaver", "night_mode", "power_off_delay"): ("shared", 0),
    ("screensaver", "night_mode", "black_screen_alternative"): ("shared", False),
    ("screensaver", "weather", "entity_id"): ("shared", ""),
    ("screensaver", "weather", "place_name"): ("shared", ""),
    ("screensaver", "weather", "clock_overlay"): ("shared", False),
    ("screensaver", "weather", "info_bar"): ("shared", True),
    ("screensaver", "weather", "lightning"): ("shared", False),
    ("screensaver", "photos", "source"): ("shared", "folder"),
    ("screensaver", "photos", "folder_path"): ("shared", ""),
    ("screensaver", "photos", "image_url"): ("shared", ""),
    ("screensaver", "photos", "slide_interval"): ("shared", 300),
    ("screensaver", "photos", "transition"): ("shared", "crossfade"),
    ("screensaver", "photos", "fit"): ("shared", "smart_fill"),
    ("screensaver", "photos", "shuffle"): ("shared", False),
    ("screensaver", "photos", "include_subfolders"): ("shared", False),
    ("screensaver", "photos", "edge_taps"): ("shared", True),
    ("screensaver", "photos", "exif_details"): ("shared", False),
    ("screensaver", "website", "url"): ("shared", ""),
    ("screensaver", "website", "zoom"): ("shared", 100),
    ("screensaver", "website", "double_tap_dismiss"): ("shared", False),
    ("screensaver", "pixel_shift", "enabled"): ("shared", False),
    ("screensaver", "pixel_shift", "interval"): ("shared", 300),
    ("screensaver", "pixel_shift", "amplitude"): ("shared", 4),
    ("screensaver", "fade", "enabled"): ("shared", False),
    ("notifications", "default_duration"): ("shared", 10),
    ("notifications", "default_level"): ("shared", "info"),
    ("notifications", "chime_by_level", "info"): ("shared", False),
    ("notifications", "chime_by_level", "success"): ("shared", False),
    ("notifications", "chime_by_level", "warning"): ("shared", True),
    ("notifications", "chime_by_level", "error"): ("shared", True),
    ("notifications", "max_visible"): ("shared", 3),
    ("audio", "media_volume"): ("shared", 50),
    ("audio", "announce_volume_boost"): ("shared", "none"),
    ("at_a_glance", "entities"): ("shared", []),
    ("at_a_glance", "style"): ("shared", "pill"),
    ("at_a_glance", "hide_names"): ("shared", False),
    ("at_a_glance", "monochrome_icons"): ("shared", False),
    ("at_a_glance", "scale"): ("shared", 100),
    ("at_a_glance", "font"): ("shared", "sans-serif"),
    ("widgets", "shown"): ("shared", True),
    ("widgets", "scale"): ("shared", 100),
    ("widgets", "font"): ("shared", "sans-serif"),
    ("widgets", "vignette"): ("shared", 0),
    ("widgets", "small_clock", "enabled"): ("shared", False),
    ("widgets", "small_clock", "corner"): ("shared", "tl"),
    ("widgets", "small_clock", "show_date"): ("shared", True),
    ("widgets", "small_clock", "hour_24"): ("shared", False),
    ("widgets", "small_clock", "color"): ("shared", "#FFFFFFFF"),
    ("widgets", "weather", "enabled"): ("shared", False),
    ("widgets", "weather", "corner"): ("shared", "tr"),
    ("widgets", "weather", "entity_id"): ("shared", ""),
    ("widgets", "weather", "place_name"): ("shared", ""),
    ("widgets", "forecast", "enabled"): ("shared", False),
    ("widgets", "forecast", "corner"): ("shared", "br"),
    ("widgets", "forecast", "entity_id"): ("shared", ""),
    ("widgets", "forecast", "days"): ("shared", 5),
    ("widgets", "forecast", "include_today"): ("shared", True),
    ("widgets", "battery", "enabled"): ("shared", False),
    ("widgets", "battery", "corner"): ("shared", "bl"),
    ("widgets", "battery", "show_percent"): ("shared", True),
    ("widgets", "battery", "only_when_low"): ("shared", False),
    ("widgets", "entity", "enabled"): ("shared", False),
    ("widgets", "entity", "corner"): ("shared", "tr"),
    ("widgets", "entity", "entity_id"): ("shared", ""),
    ("widgets", "entity", "attribute"): ("shared", ""),
    ("widgets", "entity", "name"): ("shared", ""),
    ("widgets", "entity", "visible_values"): ("shared", []),
    ("schedule", "enabled"): ("shared", False),
    ("schedule", "slots"): ("shared", []),
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
    """Every leaf property (one with no properties of its own).

    A schema with a ``required`` list inside array items (the schedule's
    slots) is not walked into the items: those properties are pinned by the
    fixtures and the down-translation tests, not by LEAF_SPEC.
    """
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


def test_schema_is_valid_and_declares_version_2() -> None:
    assert schema()["x-tabula-schema-version"] == 2
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


def test_v2_defaults_and_bounds_are_documented_in_adr_0002() -> None:
    """Every settings-v2 leaf's default and bounds live in ADR 0002's v2 section.

    Constants are chosen by the architect and recorded in the ADR (AGENTS.md,
    "Code style"): one table row must carry the leaf's name together with its
    default, every numeric bound, and every enum member. The photos table
    missing from the first version of the v2 section (PR #73's second review)
    is the case this test exists for — a schema leaf without its ADR row
    fails here, not silently.
    """
    adr = (ROOT / "docs" / "decisions" / "0002-constants.md").read_text(encoding="utf-8")
    section = adr.split("### Settings schema v2 (P3.1)", 1)[1]
    section = section.split("### Phase 1-2 behavior defaults", 1)[0]
    rows = [line.lower() for line in section.splitlines() if line.startswith("|")]

    v1 = json.loads(V1_SCHEMA_PATH.read_text(encoding="utf-8"))
    v1_paths = {path for path, _ in leaves(v1)}

    for path, prop in leaves(schema()):
        if path in v1_paths:
            continue
        name = path[-1]
        expected: list[str] = []
        if prop.get("type") != "array" and prop.get("default") is not None:
            expected.append(str(prop["default"]).lower())
        for key in ("minimum", "maximum", "minLength", "maxLength", "maxItems"):
            if key in prop:
                expected.append(str(prop[key]))
        if prop.get("type") == "array":
            expected.append("[]")
            for key in ("minLength", "maxLength"):
                if isinstance(prop.get("items"), dict) and key in prop["items"]:
                    expected.append(str(prop["items"][key]))
        if "enum" in prop:
            expected += [member.lower() for member in prop["enum"]]

        documented = any(name in row and all(token in row for token in expected) for row in rows)
        assert documented, (
            f"{'/'.join(path)}: no row in ADR 0002's settings-v2 section carries "
            f"its default/bounds {expected}"
        )


def test_every_leaf_traces_to_a_feature() -> None:
    for path, prop in leaves(schema()):
        trace = prop.get("x-tabula-feature")
        assert isinstance(trace, str) and trace, f"{path}: missing x-tabula-feature"


def test_v2_is_additive_over_the_frozen_v1_schema() -> None:
    """Plan 5.4: a stored v1 document must stay a valid v2 document.

    Every v1 leaf keeps its shape and default; v2 only adds keys.
    """
    v1 = json.loads(V1_SCHEMA_PATH.read_text(encoding="utf-8"))
    assert v1["x-tabula-schema-version"] == 1

    def v1_leaves(node, path=()):
        for key, prop in node.get("properties", {}).items():
            if "properties" in prop:
                yield from v1_leaves(prop, (*path, key))
            else:
                yield (*path, key), prop

    for path, prop in v1_leaves(v1):
        assert LEAF_SPEC[path] == (prop["x-tabula-role"], prop.get("default")), path

    # a full v1 document validates under v2 unchanged
    full = json.loads((V1_FIXTURES / "full.json").read_text(encoding="utf-8"))
    assert not list(validator().iter_errors(full))


# --- Fixtures ---


@pytest.mark.parametrize("path", sorted(V2_FIXTURES.glob("*.json")), ids=lambda p: p.name)
def test_valid_v2_fixture_validates(path: Path) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    errors = list(validator().iter_errors(doc))
    assert not errors, [e.message for e in errors]


@pytest.mark.parametrize("path", sorted(V1_FIXTURES.glob("*.json")), ids=lambda p: p.name)
def test_v1_fixture_still_validates_under_v2(path: Path) -> None:
    """The v1 golden documents stay valid: the bump is additive (plan 5.4)."""
    doc = json.loads(path.read_text(encoding="utf-8"))
    errors = list(validator().iter_errors(doc))
    assert not errors, [e.message for e in errors]


def test_defaults_fixture_is_exactly_the_schema_defaults() -> None:
    doc = json.loads((V2_FIXTURES / "defaults.json").read_text(encoding="utf-8"))
    assert doc == nested_defaults()


def test_invalid_fixtures_fail_where_expected() -> None:
    expected = json.loads((V2_FIXTURES / "invalid" / "EXPECTED.json").read_text(encoding="utf-8"))
    checked = 0
    for path in sorted((V2_FIXTURES / "invalid").glob("*.json")):
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
