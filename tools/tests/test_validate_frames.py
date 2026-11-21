import json
from pathlib import Path

import pytest

from tools import validate_frames as vf


@pytest.fixture(scope="module")
def schema():
    return vf.load_schema()


def test_repo_fixtures_pass(schema):
    report, errors = vf.validate(schema)
    assert errors == []
    assert any(r.startswith("bad") for r in report)


def test_every_frame_needs_a_fixture(schema, tmp_path: Path):
    src = vf.FIXTURES / "register.json"
    (tmp_path / "register.json").write_text(src.read_text())
    (tmp_path / "invalid").mkdir()
    _, errors = vf.validate(schema, tmp_path)
    assert "frame type 'subscribe' has no valid fixture" in errors
    assert "frame type 'register' has no valid fixture" not in errors


def test_invalid_fixture_that_validates_is_an_error(schema, tmp_path: Path):
    (tmp_path / "invalid").mkdir()
    (tmp_path / "invalid" / "subscribe.oops.json").write_text(
        json.dumps({"id": 1, "type": "tabula/subscribe"})
    )
    _, errors = vf.validate(schema, tmp_path)
    assert "invalid/subscribe.oops.json: expected to be invalid, but it validates" in errors


def test_unknown_frame_name(schema, tmp_path: Path):
    (tmp_path / "invalid").mkdir()
    (tmp_path / "bogus.json").write_text("{}")
    _, errors = vf.validate(schema, tmp_path)
    assert "bogus.json: no frame type 'bogus' in the schema" in errors


def test_formats_are_checked(schema):
    v = vf.frame_validator(schema, "state_delta")
    assert not v.is_valid({"id": 1, "type": "tabula/state", "state": {"next_screensaver": "soon"}})
    assert v.is_valid({"id": 1, "type": "tabula/state", "state": {"next_screensaver": None}})


def test_frame_list_must_match_defs(schema):
    broken = {**schema, "x-tabula-frames": [*schema["x-tabula-frames"], "ghost"]}
    assert vf.check_frame_list(broken) == [
        "x-tabula-frames lists 'ghost' but $defs has no such entry"
    ]


def test_invalid_fixture_must_fail_where_expected(schema, tmp_path: Path):
    (tmp_path / "invalid").mkdir()
    (tmp_path / "invalid" / "state_delta.x.json").write_text(
        json.dumps({"id": 1, "type": "tabula/state", "state": {"brightness": 500}})
    )
    (tmp_path / "invalid" / "EXPECTED.json").write_text(
        json.dumps({"state_delta.x": "state/muted"})
    )
    _, errors = vf.validate(schema, tmp_path)
    assert (
        "invalid/state_delta.x.json: fails at ['state/brightness'], expected at 'state/muted'"
        in errors
    )


@pytest.mark.parametrize(
    "path", ["//evil.example/x", "/\\evil.example", "https://evil.example/", "lovelace"]
)
def test_navigate_stays_on_origin(schema, path):
    v = vf.frame_validator(schema, "command_navigate")
    msg = json.loads((vf.FIXTURES / "command_navigate.json").read_text())
    msg["event"]["args"]["path"] = path
    assert not v.is_valid(msg)


def test_unknown_state_key_rejected(schema):
    v = vf.frame_validator(schema, "state_delta")
    assert not v.is_valid({"id": 1, "type": "tabula/state", "state": {"nope": 1}})
    assert v.is_valid({"id": 1, "type": "tabula/state", "state": {"muted": True}})


def test_command_union_is_strict(schema):
    v = vf.frame_validator(schema, "command_notify")
    msg = json.loads((vf.FIXTURES / "command_notify.json").read_text())
    msg["event"]["command"] = "screen"
    assert not v.is_valid(msg)
