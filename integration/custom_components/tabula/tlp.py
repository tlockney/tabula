"""TLP v1 frame validation for the integration side (docs/PROTOCOL.md section 4).

Same schema, same two modes as mockdevice's Codec (adapted from
mockdevice/src/mockdevice/codec.py, this project, Apache-2.0):

- strict: the full schema, used for frames this integration sends (strict senders).
- receiving: ``additionalProperties: false`` relaxed everywhere so unknown object
  members from a newer device on the same protocol major are ignored (PROTOCOL.md 4.0),
  except in ``state``, where unknown keys stay invalid in v1 (4.4).

Tolerated future values (PROTOCOL.md section 8): a known state key carrying an
unknown enum value has that key dropped for that frame; an unknown capability is
dropped from the accepted list. Everything else - required members, types, ranges,
patterns, formats - rejects the frame.
"""

from __future__ import annotations

import copy
import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

Frame = dict[str, Any]

BUNDLED_SCHEMA = Path(__file__).parent / "tlp-v1.schema.json"


def _relax(node: Any) -> Any:
    """Copy of a schema with every `additionalProperties: false` removed."""
    if isinstance(node, dict):
        return {
            k: _relax(v)
            for k, v in node.items()
            if not (k == "additionalProperties" and v is False)
        }
    if isinstance(node, list):
        return [_relax(v) for v in node]
    return node


def _receiving_schema(schema: dict[str, Any]) -> dict[str, Any]:
    """Relaxed everywhere except the state object: unknown state keys stay invalid."""
    relaxed = _relax(copy.deepcopy(schema))
    relaxed["$defs"]["state"]["additionalProperties"] = False
    return relaxed


def _registry_for(schema: dict[str, Any]) -> Registry:
    return Registry().with_resource(schema["$id"], Resource.from_contents(schema))


@lru_cache(maxsize=1)
def _load() -> dict[str, Registry]:
    return {
        "strict": _registry_for(json.loads(BUNDLED_SCHEMA.read_text(encoding="utf-8"))),
        "receiving": _registry_for(
            _receiving_schema(json.loads(BUNDLED_SCHEMA.read_text(encoding="utf-8")))
        ),
    }


class TLPValidator:
    """Validates TLP frames by frame type ($defs name) in strict or receiving mode."""

    def __init__(self) -> None:
        self._registries: dict[str, Registry] = _load()
        self._validators: dict[tuple[str, bool], Draft202012Validator] = {}
        # The schema document's own frame registry and capability list.
        self._doc = json.loads(BUNDLED_SCHEMA.read_text(encoding="utf-8"))
        self.frame_types = tuple(self._doc["x-tabula-frames"])
        self.known_capabilities = frozenset(self._doc["$defs"]["capability"]["enum"])

    def _validator(self, frame_type: str, lenient: bool) -> Draft202012Validator:
        if frame_type not in self.frame_types:
            raise KeyError(f"unknown frame type: {frame_type}")
        key = (frame_type, lenient)
        if key not in self._validators:
            schema = self._doc
            self._validators[key] = Draft202012Validator(
                {"$ref": f"{schema['$id']}#/$defs/{frame_type}"},
                registry=self._registries["receiving" if lenient else "strict"],
                format_checker=Draft202012Validator.FORMAT_CHECKER,
            )
        return self._validators[key]

    def problems(self, frame_type: str, frame: Frame, lenient: bool = True) -> list[str]:
        """Validation problems for a frame; empty when the frame is acceptable."""
        errors = sorted(
            self._validator(frame_type, lenient).iter_errors(frame), key=lambda e: list(e.path)
        )
        return [f"{'/'.join(map(str, e.path)) or '(root)'}: {e.message}" for e in errors]

    def validate(self, frame_type: str, frame: Frame, lenient: bool = False) -> Frame:
        """Strict by default: senders validate what they send (PROTOCOL.md 4.0)."""
        if problems := self.problems(frame_type, frame, lenient):
            raise ValueError(f"{frame_type}: {'; '.join(problems)}")
        return frame

    def accept(self, frame_type: str, frame: Frame) -> Frame | None:
        """Validate a received frame, tolerating future enum values (PROTOCOL.md 8).

        Returns the frame to process (possibly with unknown enum values dropped), or
        None when the frame is rejected.
        """
        validator = self._validator(frame_type, True)
        if not (errors := list(validator.iter_errors(frame))):
            return frame
        # Section 8 tolerance: drop state keys and capability items whose only
        # problem is an unknown enum value. Problems of any other kind, anywhere,
        # reject the frame (4.4: unknown state keys are invalid in v1).
        state_drops: set[str] = set()
        cap_drops: set[int] = set()
        for error in errors:
            if error.validator != "enum" or len(error.path) != 2:
                continue
            if error.path[0] == "state" and isinstance(error.path[1], str):
                state_drops.add(error.path[1])
            elif error.path[0] == "capabilities" and isinstance(error.path[1], int):
                cap_drops.add(error.path[1])
        if not state_drops and not cap_drops:
            return None
        trimmed = copy.deepcopy(frame)
        if state_drops:
            state = {k: v for k, v in frame.get("state", {}).items() if k not in state_drops}
            if not state:
                # register and state_delta both require a non-empty state object.
                return None
            trimmed["state"] = state
        if cap_drops:
            trimmed["capabilities"] = [
                c for i, c in enumerate(frame.get("capabilities", [])) if i not in cap_drops
            ]
        if self.problems(frame_type, trimmed):
            return None
        return trimmed
