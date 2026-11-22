"""The settings schema's string patterns accept and reject what ADR 0002 says."""

import json
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schema" / "settings.schema.json").read_text())


def _start_path_pattern() -> str:
    return SCHEMA["properties"]["dashboard"]["properties"]["start_path"]["pattern"]


@pytest.mark.parametrize(
    "path",
    [
        "",
        "/",
        "/dashboard-office",
        "/lovelace/sensors",
        "/lovelace/kitchen#popup",
        "/lovelace/0",
    ],
)
def test_start_path_accepts(path: str) -> None:
    assert re.fullmatch(_start_path_pattern(), path)


@pytest.mark.parametrize(
    "path",
    [
        "lovelace/0",
        "//example.org/x",
        "/\\example.org",
        "/a\\b",
        "/lovelace/0?edit=1",
        "/lovelace/my view",
        "/lovelace/\tview",
    ],
)
def test_start_path_rejects(path: str) -> None:
    assert not re.fullmatch(_start_path_pattern(), path)
