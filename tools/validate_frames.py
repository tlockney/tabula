"""Validate the golden TLP frames in schema/fixtures/ against schema/tlp-v1.schema.json.

Fixture layout (docs/PROTOCOL.md, "Fixtures"):

    schema/fixtures/tlp/v1/<frame>[.<variant>].json          must validate as <frame>
    schema/fixtures/tlp/v1/invalid/<frame>.<variant>.json    must fail validation as <frame>
    schema/fixtures/tlp/v1/invalid/EXPECTED.json             where each invalid fixture fails

Every frame type listed in the schema's x-tabula-frames needs at least one valid fixture, and
x-tabula-frames must match the frame $defs. Formats (date-time, uuid, …) are checked; this needs
jsonschema's format extras (jsonschema[format-nongpl]).
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
# The integration ships its own copy of each schema and validates every frame
# and settings document against them (integration/custom_components/tabula/).
BUNDLED = ROOT / "integration" / "custom_components" / "tabula" / "tlp-v1.schema.json"
SETTINGS_SCHEMA = ROOT / "schema" / "settings.schema.json"
SETTINGS_BUNDLED = ROOT / "integration" / "custom_components" / "tabula" / "settings.schema.json"


SHARED_DEFS = {"device_id", "command_id", "capability", "error_code", "error", "state", "log_line"}


def load_schema(path: Path = SCHEMA) -> dict[str, Any]:
    schema = json.loads(path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return schema


def check_frame_list(schema: dict[str, Any]) -> list[str]:
    """x-tabula-frames must list exactly the $defs that are not shared types."""
    listed = set(schema.get("x-tabula-frames", []))
    defined = set(schema.get("$defs", {})) - SHARED_DEFS
    return [
        f"x-tabula-frames lists '{f}' but $defs has no such entry" for f in sorted(listed - defined)
    ] + [f"$defs entry '{f}' is missing from x-tabula-frames" for f in sorted(defined - listed)]


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
    errors: list[str] = check_frame_list(schema)
    if errors:
        return report, errors
    frames = schema["x-tabula-frames"]
    expected_path = {}
    expected_file = fixtures / "invalid" / "EXPECTED.json"
    if expected_file.is_file():
        expected_path = json.loads(expected_file.read_text(encoding="utf-8"))
    validators: dict[str, Draft202012Validator] = {}
    covered: set[str] = set()

    def check(path: Path, expect_valid: bool) -> None:
        rel = path.relative_to(fixtures)
        frame = frame_of(path)
        if frame not in frames:
            errors.append(f"{rel}: no frame type '{frame}' in the schema")
            return
        if frame not in validators:
            validators[frame] = frame_validator(schema, frame)
        validator = validators[frame]
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
        elif not expect_valid and path.stem not in expected_path:
            errors.append(f"{rel}: no entry in invalid/EXPECTED.json")
        elif not expect_valid and not any(
            "/".join(str(x) for x in p.path) == expected_path[path.stem] for p in problems
        ):
            got = sorted({"/".join(str(x) for x in p.path) for p in problems})
            errors.append(f"{rel}: fails at {got}, expected at '{expected_path[path.stem]}'")
        else:
            report.append(f"{'ok  ' if expect_valid else 'bad '} {rel}")
            if expect_valid:
                covered.add(frame)

    for path in sorted(fixtures.glob("*.json")):
        check(path, expect_valid=True)
    for path in sorted(
        p for p in (fixtures / "invalid").glob("*.json") if p.name != "EXPECTED.json"
    ):
        check(path, expect_valid=False)
    errors += [f"frame type '{f}' has no valid fixture" for f in frames if f not in covered]
    return report, errors


def check_bundled_copy() -> list[str]:
    """Bundled schemas must match the repo schemas byte for byte."""
    errors: list[str] = []
    for repo, bundled in (
        (SCHEMA, BUNDLED),
        (SETTINGS_SCHEMA, SETTINGS_BUNDLED),
    ):
        if not bundled.is_file():
            errors.append(f"{bundled.relative_to(ROOT)} is missing (copy it from schema/)")
        elif bundled.read_bytes() != repo.read_bytes():
            errors.append(
                f"{bundled.relative_to(ROOT)} differs from {repo.relative_to(ROOT)}; re-copy it"
            )
    return errors


def main() -> int:
    schema = load_schema()
    report, errors = validate(schema)
    errors += check_bundled_copy()
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
