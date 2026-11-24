"""Translate-down migrations (plan 5.4, PROTOCOL.md 4.5).

SCHEMA_VERSION is 4, so DOWNS[4], DOWNS[3] and DOWNS[2] are real: [4] drops the
v4-only `intercom` group (P9.5), [3] drops the v3-only `voice` group (P7.1),
[2] prunes every v2-only key, nested ones included, and each result validates
strictly against the frozen schema below it (`schema/settings.schema.v3.json`,
`schema/settings.schema.v2.json`, `schema/settings.schema.v1.json`) - the
frozen files are what a device on that version enforces, so "valid v2" has to
mean the v2 schema, not today's. The chain-walk tests drive hypothetical future
steps on top, to prove the walk itself keeps working past v3: each step sees
the document version above it, a missing step is a hard error, and the input
document is never mutated.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

import pytest
from custom_components.tabula import migrations
from custom_components.tabula.const import SCHEMA_VERSION
from custom_components.tabula.settings import SettingsSchema
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

REPO_ROOT = Path(__file__).resolve().parents[2]
V1_SCHEMA_PATH = REPO_ROOT / "schema" / "settings.schema.v1.json"
V2_SCHEMA_PATH = REPO_ROOT / "schema" / "settings.schema.v2.json"
V3_SCHEMA_PATH = REPO_ROOT / "schema" / "settings.schema.v3.json"
V2_FULL_FIXTURE = REPO_ROOT / "schema" / "fixtures" / "settings" / "v2" / "full.json"
V3_FULL_FIXTURE = REPO_ROOT / "schema" / "fixtures" / "settings" / "v3" / "full.json"
V4_FULL_FIXTURE = REPO_ROOT / "schema" / "fixtures" / "settings" / "v4" / "full.json"

DOC = {"screen": {"brightness": 42, "keep_screen_on": True}, "v3only": {"x": 1}}


@lru_cache(maxsize=1)
def v1_validator() -> Draft202012Validator:
    """Strict validation against the frozen v1 schema."""
    return _validator(V1_SCHEMA_PATH)


@lru_cache(maxsize=1)
def v2_validator() -> Draft202012Validator:
    """Strict validation against the frozen v2 schema."""
    return _validator(V2_SCHEMA_PATH)


@lru_cache(maxsize=1)
def v3_validator() -> Draft202012Validator:
    """Strict validation against the frozen v3 schema."""
    return _validator(V3_SCHEMA_PATH)


def _validator(path: Path) -> Draft202012Validator:
    doc = json.loads(path.read_text(encoding="utf-8"))
    registry = Registry().with_resource(doc["$id"], Resource.from_contents(doc))
    return Draft202012Validator(
        doc, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
    )


async def test_current_version_returns_a_copy():
    """Translating to the current version changes nothing but the identity."""
    out = migrations.translate(DOC, SCHEMA_VERSION)
    assert out == DOC
    assert out is not DOC


async def test_newer_device_document_passes_through():
    """A device on a newer schema keeps its keys; HA never translates up."""
    out = migrations.translate(DOC, SCHEMA_VERSION + 2)
    assert out == DOC
    assert "v3only" in out


def _steps(monkeypatch, version: int, downs: dict) -> None:
    monkeypatch.setattr(migrations, "SCHEMA_VERSION", version)
    monkeypatch.setattr(migrations, "DOWNS", downs)


async def test_chain_walk_applies_every_step_in_order(monkeypatch):
    """Each step maps version n to n-1; the walk visits all of them."""

    def three_to_two(doc: dict) -> dict:
        assert doc["v3only"], "the step above sees the newer document"
        return {k: v for k, v in doc.items() if k != "v3only"}

    def two_to_one(doc: dict) -> dict:
        assert "v3only" not in doc
        return doc

    _steps(monkeypatch, 3, {3: three_to_two, 2: two_to_one})
    out = migrations.translate(DOC, 1)
    assert out == {"screen": {"brightness": 42, "keep_screen_on": True}}
    # the caller's document is untouched
    assert DOC["v3only"] == {"x": 1}


async def test_partial_chain_walk(monkeypatch):
    """Translating to an intermediate version stops there."""

    def three_to_two(doc: dict) -> dict:
        return {k: v for k, v in doc.items() if k != "v3only"}

    _steps(monkeypatch, 3, {3: three_to_two})
    out = migrations.translate(DOC, 2)
    assert out == {"screen": {"brightness": 42, "keep_screen_on": True}}


async def test_missing_step_is_an_error(monkeypatch):
    """A document version without a translate-down step is a bug, not a guess."""

    def three_to_two(doc: dict) -> dict:
        return doc

    _steps(monkeypatch, 3, {3: three_to_two})
    with pytest.raises(RuntimeError, match="no down-translation"):
        migrations.translate(DOC, 1)


async def test_target_below_one_is_rejected():
    with pytest.raises(ValueError):
        migrations.translate(DOC, 0)


async def test_v4_document_translates_down_to_strict_v3():
    """The populated v4 fixture prunes to a document the frozen v3 schema accepts.

    P9.5 added the `intercom` group. A device still on v3 rejects unknown keys,
    so the step drops it whole and leaves a document v3 validates.
    """
    document = json.loads(V4_FULL_FIXTURE.read_text(encoding="utf-8"))
    assert "intercom" in document, "the fixture stopped exercising the v4 group"
    out = migrations.translate(document, 3)
    assert not list(v3_validator().iter_errors(out))
    assert "intercom" not in out
    # nothing else moves: the rest of the document is passed through
    assert out == {k: v for k, v in document.items() if k != "intercom"}


async def test_v3_document_translates_down_to_strict_v2():
    """The populated v3 fixture prunes to a document the frozen v2 schema accepts.

    P7.1 added the `voice` group. A device still on v2 rejects unknown keys, so
    the step has to drop it whole - and dropping it must leave a document v2
    validates, not just one that looks smaller.
    """
    document = json.loads(V3_FULL_FIXTURE.read_text(encoding="utf-8"))
    assert "voice" in document, "the fixture stopped exercising the v3 group"
    out = migrations.translate(document, 2)
    assert not list(v2_validator().iter_errors(out))
    assert "voice" not in out
    # nothing else moves: the rest of the document is passed through
    assert out == {k: v for k, v in document.items() if k != "voice"}


async def test_v2_document_translates_down_to_strict_v1():
    """The populated v2 fixture prunes to a document v1 validates strictly.

    A device's strict codec rejects unknown keys (plan 5.4), so every v2-only
    key must go - the new top-level groups and the new keys nested inside
    existing groups alike. Kept values pass through unchanged.
    """
    document = json.loads(V2_FULL_FIXTURE.read_text(encoding="utf-8"))
    out = migrations.translate(document, 1)
    assert not list(v1_validator().iter_errors(out))
    # the new top-level groups are gone (dashboard included, P3.15)
    assert set(out) == {"identity", "connection", "screen", "screensaver", "notifications", "audio"}
    # the keys nested inside existing groups are gone too
    assert set(out["screensaver"]) == {"enabled", "idle_timeout", "mode", "brightness"}
    # v1 values pass through unchanged
    assert out["screen"]["brightness"] == document["screen"]["brightness"]
    assert out["screensaver"]["mode"] == document["screensaver"]["mode"]
    assert out["notifications"]["chime_by_level"] == document["notifications"]["chime_by_level"]


async def test_v2_defaults_translate_down_to_strict_v1():
    """The v2 defaults document alone is a valid v1 document."""
    schema = SettingsSchema()
    out = migrations.translate(schema.defaults(), 1)
    assert not list(v1_validator().iter_errors(out))


async def test_down_translation_never_mutates_the_input():
    document = json.loads(V2_FULL_FIXTURE.read_text(encoding="utf-8"))
    snapshot = json.loads(V2_FULL_FIXTURE.read_text(encoding="utf-8"))
    migrations.translate(document, 1)
    assert document == snapshot


async def test_down_translation_is_registered_for_every_step():
    """Every version above the floor has a step, or the walk is a bug."""
    assert set(migrations.DOWNS) == set(range(2, SCHEMA_VERSION + 1))


async def test_translated_documents_still_validate_today():
    """A translate-down result is also a valid current-schema document:
    the bump is additive (plan 5.4), so pruning v2-only keys removes nothing
    v2 requires (PROTOCOL.md 4.5: the device validates against its own
    version).
    """
    schema = SettingsSchema()
    schema.validate(migrations.translate(schema.defaults(), 1))
