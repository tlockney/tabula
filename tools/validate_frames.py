"""Validate the golden TLP frames in schema/fixtures/ against schema/tlp-v1.schema.json.

Fixture layout (docs/PROTOCOL.md, "Fixtures"):

    schema/fixtures/tlp/v1/<frame>[.<variant>].json          must validate as <frame>
    schema/fixtures/tlp/v1/invalid/<frame>.<variant>.json    must fail validation as <frame>

Every frame type listed in the schema's x-tabula-frames needs at least one valid fixture.
Exit status is non-zero when any check fails.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "schema" / "tlp-v1.schema.json"
FIXTURES = ROOT / "schema" / "fixtures" / "tlp" / "v1"


def load_schema(path: Path = SCHEMA) -> dict[str, Any]:
    schema = json.loads(path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return schema


def frame_validator(schema: dict[str, Any], frame: str) -> Draft202012Validator:
    """Return a validator for one frame type ($defs entry) of the protocol schema."""
    if frame not in schema["x-tabula-frames"]:
        raise KeyError(f"unknown frame type: {frame}")
    registry = Registry().with_resource(schema["$id"], Resource.from_contents(schema))
    return Draft202012Validator(
        {"$ref": f"{schema['$id']}#/$defs/{frame}"},
        registry=registry,
        format_checker=Draft202012Validator.FORMAT_CHECKER,
    )


def frame_of(path: Path) -> str:
    return path.name.split(".", 1)[0]


def validate(schema: dict[str, Any], fixtures: Path = FIXTURES) -> tuple[list[str], list[str]]:
    """Return (report lines, errors)."""
    report: list[str] = []
    errors: list[str] = []
    frames = schema["x-tabula-frames"]
    validators: dict[str, Draft202012Validator] = {}
    covered: set[str] = set()

    def check(path: Path, expect_valid: bool) -> None:
        rel = path.relative_to(fixtures)
        frame = frame_of(path)
        if frame not in frames:
            errors.append(f"{rel}: no frame type '{frame}' in the schema")
            return
        validator = validators.setdefault(frame, frame_validator(schema, frame))
        try:
            message = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            errors.append(f"{rel}: not JSON: {e}")
            return
        problems = sorted(validator.iter_errors(message), key=lambda e: list(e.path))
        if expect_valid and problems:
            for p in problems:
                where = "/".join(str(x) for x in p.path) or "(root)"
                errors.append(f"{rel}: {where}: {p.message}")
        elif not expect_valid and not problems:
            errors.append(f"{rel}: expected to be invalid, but it validates")
        else:
            report.append(f"{'ok  ' if expect_valid else 'bad '} {rel}")
            if expect_valid:
                covered.add(frame)

    for path in sorted(fixtures.glob("*.json")):
        check(path, expect_valid=True)
    for path in sorted((fixtures / "invalid").glob("*.json")):
        check(path, expect_valid=False)
    errors += [f"frame type '{f}' has no valid fixture" for f in frames if f not in covered]
    return report, errors


def main() -> int:
    schema = load_schema()
    report, errors = validate(schema)
    for line in report:
        print(line)
    for e in errors:
        print(e, file=sys.stderr)
    if errors:
        print(f"validate_frames: {len(errors)} problem(s)", file=sys.stderr)
        return 1
    valid = sum(1 for r in report if r.startswith("ok"))
    print(
        f"validate_frames: ok ({len(schema['x-tabula-frames'])} frame types, "
        f"{valid} valid and {len(report) - valid} invalid fixtures)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
