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
fills defaults for absent keys. With SCHEMA_VERSION 1 there is nothing to
translate, so DOWNS is empty; the registry and the chain walk below are the
scaffold later versions fill in, and their tests exercise the walk directly.
"""

from __future__ import annotations

import copy
from collections.abc import Callable
from typing import Any

from ..const import SCHEMA_VERSION

# version N -> function translating a document at version N down to N-1.
DOWNS: dict[int, Callable[[dict[str, Any]], dict[str, Any]]] = {}


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
