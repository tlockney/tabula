"""Settings schema translations (plan 5.4).

The Store keeps every document at the integration's current schema version
(const.SCHEMA_VERSION). A device that registers with an older ``schema_version``
receives a translation DOWN: one step at a time, dropping keys the target
version does not know (plan 5.4: "the integration stores settings at its
current schema version and translates down for devices reporting an older
one"). A device on a NEWER schema version applies the document and keeps its
own defaults for keys HA does not send (PROTOCOL.md 4.5) - no translation up
exists or is needed.

Every step translates a document AT version N into one at N-1 by pruning it
against the frozen schema of N-1: keys that schema does not know are dropped,
whole groups included. Every bump needs its step, additive or not, because the
device's strict codec rejects unknown keys (plan 5.4): a new key inside an
existing group is still a key an older device would reject.

The frozen copies (settings.schema.v1.json, settings.schema.v2.json) are
byte-identical to the repo's schema/settings.schema.v1.json and
schema/settings.schema.v2.json, which is what the tests validate every
translated document against.
"""

from __future__ import annotations

import copy
import json
from collections.abc import Callable
from functools import cache
from pathlib import Path
from typing import Any

from ..const import SCHEMA_VERSION

_BUNDLED = Path(__file__).parent.parent
_FROZEN_FILES = {
    1: _BUNDLED / "settings.schema.v1.json",
    2: _BUNDLED / "settings.schema.v2.json",
}


@cache
def frozen_schema(version: int) -> dict[str, Any]:
    """The frozen schema document of an older settings version (1 or 2)."""
    try:
        path = _FROZEN_FILES[version]
    except KeyError:
        raise ValueError(f"no frozen settings schema for version {version}") from None
    return json.loads(path.read_text(encoding="utf-8"))


def frozen_schema_versions() -> tuple[int, ...]:
    """The versions that have a frozen copy, oldest first."""
    return tuple(sorted(_FROZEN_FILES))


def _prune_to(version: int) -> Callable[[dict[str, Any]], dict[str, Any]]:
    """A translator that drops every key the frozen schema at ``version`` lacks.

    Recurses into the object groups that schema itself defines (so a key nested
    inside a group that survives is pruned too) and drops whole groups the
    schema does not know. Non-object values (``screen.brightness``, arrays,
    nulls) pass through unchanged when their property exists in the frozen
    schema.
    """
    schema = frozen_schema(version)

    def prune(node: dict[str, Any], target: dict[str, Any]) -> dict[str, Any]:
        properties = target.get("properties", {})
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

    def translate(document: dict[str, Any]) -> dict[str, Any]:
        return prune(document, schema)

    return translate


# version N -> function translating a document at version N down to N-1.
DOWNS: dict[int, Callable[[dict[str, Any]], dict[str, Any]]] = {
    2: _prune_to(1),
    3: _prune_to(2),
}


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
