"""Tests for the Kotlin settings codegen (packets P2.0 and P3.1)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools import codegen

ROOT = Path(__file__).resolve().parents[2]


def schema() -> dict:
    return json.loads(codegen.SCHEMA.read_text(encoding="utf-8"))


def test_generated_file_matches_schema():
    """The committed Kotlin file is exactly what the schema generates."""
    committed = codegen.OUT.read_text(encoding="utf-8")
    assert codegen.generate(schema()) == committed


def test_schema_version_constant():
    out = codegen.generate(schema())
    assert "const val SCHEMA_VERSION: Int = 2" in out


def test_snake_case_names():
    assert codegen.kotlin_name("ha_url") == "haUrl"
    assert codegen.kotlin_name("default_level", upper=True) == "DefaultLevel"
    with pytest.raises(ValueError):
        codegen.kotlin_name("object")
    with pytest.raises(ValueError):
        codegen.kotlin_name("3d")


def test_unsupported_construct_fails():
    """A construct the generator does not know must raise, not drop silently."""
    s = schema()
    s["properties"]["screen"]["properties"]["zones"] = {"type": "array", "items": {"type": "array"}}
    with pytest.raises(ValueError, match="unsupported"):
        codegen.generate(s)


def test_missing_default_fails():
    """Boolean and enum properties need schema defaults (device defaults)."""
    s = {
        "x-tabula-schema-version": 1,
        "properties": {
            "g": {
                "type": "object",
                "additionalProperties": False,
                "properties": {"b": {"type": "boolean"}},
            }
        },
    }
    with pytest.raises(ValueError, match="default"):
        codegen.generate(s)


def test_bad_enum_default_fails():
    s = {
        "x-tabula-schema-version": 1,
        "properties": {
            "g": {
                "type": "object",
                "additionalProperties": False,
                "properties": {"e": {"enum": ["a", "b"], "default": "c"}},
            }
        },
    }
    with pytest.raises(ValueError, match="not in enum values"):
        codegen.generate(s)


# --- Settings v2 constructs (P3.1) ---


def v2_schema(properties: dict) -> dict:
    return {
        "x-tabula-schema-version": 2,
        "properties": {
            "g": {"type": "object", "additionalProperties": False, "properties": properties}
        },
    }


def test_array_of_scalars_becomes_a_checked_list():
    out = codegen.generate(
        v2_schema(
            {
                "ids": {
                    "type": "array",
                    "maxItems": 6,
                    "default": [],
                    "items": {"type": "string", "pattern": "^[a-z]+\\.[a-z_]+$"},
                }
            }
        )
    )
    assert "val ids: List<String> = emptyList()" in out
    assert "require(ids.size <= 6)" in out
    assert "ids.all { " in out and ".matches(it) }" in out


def test_array_of_objects_becomes_a_list_of_data_classes():
    out = codegen.generate(
        v2_schema(
            {
                "slots": {
                    "type": "array",
                    "maxItems": 12,
                    "default": [],
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["start"],
                        "properties": {
                            "start": {"type": "string", "pattern": "^[0-9]{2}:[0-9]{2}$"},
                            "level": {
                                "type": ["integer", "null"],
                                "minimum": 0,
                                "maximum": 100,
                                "default": None,
                            },
                        },
                    },
                }
            }
        )
    )
    # the item class is named from the array's path
    assert "data class GSlots(" in out
    assert "List<GSlots> = emptyList()" in out
    # a required leaf carries no Kotlin default: an absent key fails decoding
    assert "val start: String," in out
    # a nullable leaf defaults to null and checks only a present value
    assert "val level: Int? = null" in out
    assert "level?.let { require(it in 0..100)" in out
    assert "slots.size <= 12" in out


def test_nullable_boolean_and_string():
    out = codegen.generate(
        v2_schema(
            {
                "flag": {"type": ["boolean", "null"], "default": None},
                "text": {"type": ["string", "null"], "default": None},
            }
        )
    )
    assert "val flag: Boolean? = null" in out
    assert "val text: String? = null" in out


def test_bad_nullable_type_fails():
    with pytest.raises(ValueError, match="unsupported property type"):
        codegen.generate(v2_schema({"x": {"type": ["integer", "string", "null"], "default": None}}))


def test_nullable_with_a_value_default_fails():
    with pytest.raises(ValueError, match="must default to null"):
        codegen.generate(v2_schema({"x": {"type": ["integer", "null"], "default": 5}}))


def test_required_with_a_default_fails():
    """Required and defaulted contradict: pick one, deliberately."""
    s = v2_schema({"x": {"type": "integer", "minimum": 0, "default": 5}})
    s["properties"]["g"]["required"] = ["x"]
    with pytest.raises(ValueError, match="must not carry a default"):
        codegen.generate(s)


def test_required_lists_unknown_property_fails():
    s = v2_schema({"x": {"type": "integer", "minimum": 0}})
    s["properties"]["g"]["required"] = ["nope"]
    with pytest.raises(ValueError, match="unknown properties"):
        codegen.generate(s)


def test_array_without_empty_default_fails():
    with pytest.raises(ValueError, match="needs default"):
        codegen.generate(v2_schema({"xs": {"type": "array", "items": {"type": "string"}}}))


def test_array_min_items_fails():
    s = {
        "type": "array",
        "minItems": 1,
        "default": [],
        "items": {"type": "string"},
    }
    with pytest.raises(ValueError, match="minItems"):
        codegen.generate(v2_schema({"xs": s}))


def test_shared_pattern_declared_once():
    """The same pattern on two fields shares one Regex val."""
    pattern = "^#[0-9A-Fa-f]{6}$"
    out = codegen.generate(
        v2_schema(
            {
                "a": {"type": "string", "pattern": pattern, "default": "#000000"},
                "b": {"type": "string", "pattern": pattern, "default": "#111111"},
            }
        )
    )
    assert out.count("Regex(") == 1
    assert "GAPattern" in out  # named after the first property that used it
