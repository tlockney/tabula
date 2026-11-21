"""Translate-down migrations (plan 5.4, PROTOCOL.md 4.5).

The registry is empty at SCHEMA_VERSION 1, so these tests drive hypothetical
future steps to prove the chain walk: each step sees the document version above
it, a missing step is a hard error, and the input document is never mutated.
"""

from __future__ import annotations

import pytest
from custom_components.tabula import migrations
from custom_components.tabula.const import SCHEMA_VERSION
from custom_components.tabula.settings import SettingsSchema

DOC = {"screen": {"brightness": 42, "keep_screen_on": True}, "v3only": {"x": 1}}


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


async def test_translated_documents_still_validate_today():
    """A translate-down result is a valid document of the older schema.

    The v1 schema has no removed keys yet, so the identity translation is the
    only honest check available; the invariant is what matters (PROTOCOL.md
    4.5: the device validates against its own version).
    """
    schema = SettingsSchema()
    schema.validate(migrations.translate(schema.defaults(), 1))
