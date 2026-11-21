"""Settings schema translations (plan 5.4).

The Store keeps every document at the integration's current schema version
(const.SCHEMA_VERSION). A device that registers with an older ``schema_version``
receives a translation DOWN: one step at a time, dropping keys the target
version does not know (plan 5.4: "the integration stores settings at its
current schema version and translates down for devices reporting an older
one"). A device on a NEWER schema version applies the document and keeps its
own defaults for keys HA does not send (PROTOCOL.md 4.5) - no translation up
exists or is needed.

Every schema bump that removes or reshapes keys adds one entry to DOWNS: the
function that translates a document AT version N into one at N-1. Additive
changes (new keys with defaults) need no entry: the receiving side already
fills defaults for absent keys.

SCHEMA_VERSION 2 (P3.1) is additive, but the device's strict codec rejects
unknown keys (plan 5.4), so the v2 -> v1 step is required, not optional: it
prunes every key the v1 schema does not know, including the new keys nested
inside existing groups (``screensaver.clock`` and friends), against the frozen
v1 schema kept next to the current one (settings.schema.v1.json). The frozen
copy is byte-identical to the repo's schema/settings.schema.v1.json, which is
what the tests validate the translated document against.
"""

from __future__ import annotations

import copy
import json
from collections.abc import Callable
from functools import lru_cache
from pathlib import Path
from typing import Any

from ..const import SCHEMA_VERSION

_BUNDLED_V1_SCHEMA = Path(__file__).parent.parent / "settings.schema.v1.json"


@lru_cache(maxsize=1)
def _v1_schema() -> dict[str, Any]:
    return json.loads(_BUNDLED_V1_SCHEMA.read_text(encoding="utf-8"))


def frozen_schema(version: int) -> dict[str, Any]:
    """The frozen schema document of an older settings version (only 1 so far)."""
    if version != 1:
        raise ValueError(f"no frozen settings schema for version {version}")
    return _v1_schema()


def _prune_to_v1(document: dict[str, Any]) -> dict[str, Any]:
    """Translate a settings document at version 2 down to version 1.

    Keeps only the keys the v1 schema knows, recursing into the object groups
    v1 itself defines (so nested v2-only keys inside ``screensaver`` and the
    like are pruned too); everything else - including whole v2-only groups -
    is dropped. Non-object values (``screen.brightness``, arrays, nulls) pass
    through unchanged when their property exists in v1.
    """

    def prune(node: dict[str, Any], schema: dict[str, Any]) -> dict[str, Any]:
        properties = schema.get("properties", {})
        pruned: dict[str, Any] = {}
        for key, value in node.items():
            prop = properties.get(key)
            if prop is None:
                continue
            if prop.get("type") == "object" and isinstance(value, dict):
                pruned[key] = prune(value, prop)
            else:
                pruned[key] = value
        return pruned

    return prune(document, _v1_schema())


# version N -> function translating a document at version N down to N-1.
DOWNS: dict[int, Callable[[dict[str, Any]], dict[str, Any]]] = {2: _prune_to_v1}


def translate(settings: dict[str, Any], target_version: int) -> dict[str, Any]:
    """Translate a document at SCHEMA_VERSION down to ``target_version``.

    Never mutates the input. Raises ValueError for a target below 1 (the
    register schema demands schema_version >= 1) and RuntimeError when the
    chain to the target is not registered.
    """
    if target_version >= SCHEMA_VERSION:
        # Same version, or a newer device: it fills its own defaults (4.5).
        return copy.deepcopy(settings)
    if target_version < 1:
        raise ValueError(f"cannot translate settings to schema version {target_version}")
    document = copy.deepcopy(settings)
    version = SCHEMA_VERSION
    while version > target_version:
        step = DOWNS.get(version)
        if step is None:
            raise RuntimeError(f"no down-translation from settings schema version {version}")
        document = step(document)
        version -= 1
    return document
