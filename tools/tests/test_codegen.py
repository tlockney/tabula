"""Tests for the Kotlin settings codegen (packet P2.0)."""

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
    assert "const val SCHEMA_VERSION: Int = 1" in out


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
    s["properties"]["screen"]["properties"]["zones"] = {"type": "array"}
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
