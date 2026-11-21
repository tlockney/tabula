"""Every golden fixture round-trips through the mock's codec."""

from __future__ import annotations

import json

import pytest
from mockdevice.codec import REPO_ROOT, UNKNOWN_COMMAND, Codec, FrameError

FIXTURES = REPO_ROOT / "schema" / "fixtures" / "tlp" / "v1"
VALID = sorted(FIXTURES.glob("*.json"))
INVALID = sorted(p for p in (FIXTURES / "invalid").glob("*.json") if p.name != "EXPECTED.json")

codec = Codec()


def frame_type(path) -> str:
    return path.name.split(".")[0]


def test_fixtures_present():
    assert len(VALID) >= len(codec.frame_types)
    assert {frame_type(p) for p in VALID} == set(codec.frame_types)


@pytest.mark.parametrize("path", VALID, ids=lambda p: p.name)
def test_valid_fixture_round_trips(path):
    text = path.read_text(encoding="utf-8")
    decoded = codec.decode(text, frame_type(path), lenient=False)
    assert json.loads(codec.encode(frame_type(path), decoded)) == json.loads(text)


@pytest.mark.parametrize("path", INVALID, ids=lambda p: p.name)
def test_invalid_fixture_is_rejected(path):
    with pytest.raises(FrameError):
        codec.decode(path.read_text(encoding="utf-8"), frame_type(path), lenient=False)


def test_receiver_ignores_unknown_members():
    frame = json.loads((FIXTURES / "register_result.json").read_text())
    frame["result"]["future_field"] = 1
    frame["extra"] = True
    with pytest.raises(FrameError):
        codec.validate("register_result", frame)
    assert codec.validate("register_result", frame, lenient=True) is frame


def test_encode_rejects_oversized_frame():
    frame = json.loads((FIXTURES / "log.json").read_text())
    frame["lines"] = [dict(frame["lines"][0], message="x" * 4000) for _ in range(200)]
    with pytest.raises(FrameError, match="exceeds"):
        codec.encode("log", frame)


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ({"id": 1, "type": "pong"}, "pong"),
        ({"id": 1, "type": "result", "success": True, "result": None}, "ack"),
        ({"id": 1, "type": "result", "success": False, "error": {}}, "error_result"),
        (
            {"id": 1, "type": "result", "success": True, "result": {"ha_device_id": "x"}},
            "register_result",
        ),
        ({"id": 1, "type": "event", "event": {"command": "notify"}}, "command_notify"),
        ({"id": 1, "type": "event", "event": {"command": "teleport"}}, UNKNOWN_COMMAND),
    ],
)
def test_classify(message, expected):
    assert codec.classify_server_message(message) == expected
