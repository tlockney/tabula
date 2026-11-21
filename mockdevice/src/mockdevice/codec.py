"""TLP v1 frame codec: JSON encoding plus schema validation (schema/tlp-v1.schema.json).

Senders are strict and receivers tolerant (docs/PROTOCOL.md 4.0): frames this device sends
are validated against the schema as written; frames it receives are validated with
`additionalProperties` relaxed, so members added by a newer server are ignored.
"""

from __future__ import annotations

import copy
import json
import os
from functools import cached_property
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from mockdevice.constants import FRAME_MAX_BYTES

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_SCHEMA = REPO_ROOT / "schema" / "tlp-v1.schema.json"

Frame = dict[str, Any]

UNKNOWN_COMMAND = "command_unknown"


class FrameError(ValueError):
    """A frame failed to decode or does not match its schema."""

    def __init__(self, frame_type: str | None, problems: list[str]) -> None:
        self.frame_type = frame_type
        self.problems = problems
        super().__init__(f"{frame_type or 'frame'}: " + "; ".join(problems))


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


class Codec:
    """Encode, decode and validate TLP frames by frame type ($defs name)."""

    def __init__(self, schema_path: Path | None = None) -> None:
        path = schema_path or Path(os.environ.get("TABULA_TLP_SCHEMA", DEFAULT_SCHEMA))
        self.schema: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(self.schema)
        self._registries = {
            False: Registry().with_resource(
                self.schema["$id"], Resource.from_contents(self.schema)
            ),
            True: Registry().with_resource(
                self.schema["$id"], Resource.from_contents(_relax(copy.deepcopy(self.schema)))
            ),
        }
        self._validators: dict[tuple[str, bool], Draft202012Validator] = {}

    @cached_property
    def frame_types(self) -> list[str]:
        return list(self.schema["x-tabula-frames"])

    @cached_property
    def command_types(self) -> dict[str, str]:
        """Map wire command name (e.g. 'notify') to frame type ('command_notify')."""
        out = {}
        for frame in self.frame_types:
            if frame.startswith("command_") and frame != "command_result":
                event = self.schema["$defs"][frame]["properties"]["event"]
                out[event["properties"]["command"]["const"]] = frame
        return out

    def _validator(self, frame_type: str, lenient: bool) -> Draft202012Validator:
        if frame_type not in self.frame_types:
            raise FrameError(frame_type, [f"unknown frame type {frame_type!r}"])
        key = (frame_type, lenient)
        if key not in self._validators:
            self._validators[key] = Draft202012Validator(
                {"$ref": f"{self.schema['$id']}#/$defs/{frame_type}"},
                registry=self._registries[lenient],
                format_checker=Draft202012Validator.FORMAT_CHECKER,
            )
        return self._validators[key]

    def problems(self, frame_type: str, frame: Frame, lenient: bool = False) -> list[str]:
        validator = self._validator(frame_type, lenient)
        errors = sorted(validator.iter_errors(frame), key=lambda e: list(e.path))
        return [f"{'/'.join(map(str, e.path)) or '(root)'}: {e.message}" for e in errors]

    def validate(self, frame_type: str, frame: Frame, lenient: bool = False) -> Frame:
        if problems := self.problems(frame_type, frame, lenient):
            raise FrameError(frame_type, problems)
        return frame

    def encode(self, frame_type: str, frame: Frame) -> str:
        """Validate strictly (we are the sender) and serialize."""
        self.validate(frame_type, frame)
        text = json.dumps(frame, separators=(",", ":"), ensure_ascii=False)
        if len(text.encode()) > FRAME_MAX_BYTES:
            raise FrameError(frame_type, [f"frame exceeds {FRAME_MAX_BYTES} bytes"])
        return text

    def decode(self, text: str | bytes, frame_type: str, lenient: bool = True) -> Frame:
        """Parse and validate a received frame (tolerant by default)."""
        try:
            frame = json.loads(text)
        except json.JSONDecodeError as e:
            raise FrameError(frame_type, [f"not JSON: {e}"]) from e
        return self.validate(frame_type, frame, lenient)

    def classify_server_message(self, frame: Frame) -> str:
        """Frame type of a WebSocket message from the server.

        Results are classified by shape: register_result if it carries ha_device_id,
        ack for a null result, error_result for failures. Events are commands; a command
        this version does not know is UNKNOWN_COMMAND, which the device NACKs.
        """
        kind = frame.get("type")
        if kind == "event":
            command = (frame.get("event") or {}).get("command")
            return self.command_types.get(command, UNKNOWN_COMMAND)
        if kind == "pong":
            return "pong"
        if kind == "result":
            if frame.get("success") is False:
                return "error_result"
            result = frame.get("result")
            if isinstance(result, dict) and "ha_device_id" in result:
                return "register_result"
            return "ack"
        raise FrameError(None, [f"unexpected message type {kind!r}"])
