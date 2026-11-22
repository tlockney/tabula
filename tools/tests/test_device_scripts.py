"""Tests for the hardware check scripts in tools/device/, run without a tablet.

Each test guards against a bug that reached a real hardware run:

- a logcat filter listing one tag twice (`Tag:I Tag:W`): logcat keeps the
  last level, so the run silently showed warnings only;
- ha_query sending `data` to HA's call_service, which wants `service_data`;
- `read -p` with no terminal: bash prints no prompt and blocks forever.

The HA side is a small fake of HA core's WebSocket API that rejects unknown
message keys, as HA's own command schemas do. The tablet side is a stub `adb`
on PATH that records its arguments and answers with canned output.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
from aiohttp import web

ROOT = Path(__file__).resolve().parents[2]
DEVICE = ROOT / "tools" / "device"
SCRIPTS = sorted(DEVICE.glob("check_*.sh"))
DEVICE_ID = "0f0e0d0c-0000-4000-8000-0000000000d1"
TOKEN = "test-token"


def load(name: str) -> Any:
    spec = importlib.util.spec_from_file_location(name, DEVICE / f"{name}.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --- fake HA core -------------------------------------------------------------

# Per command: (required keys, optional keys), as in HA core's websocket
# schemas. A missing required key or any other key is invalid_format.
SCHEMAS: dict[str, tuple[set[str], set[str]]] = {
    "config/device_registry/list": (set(), set()),
    "config/entity_registry/list": (set(), set()),
    "get_states": (set(), set()),
    "subscribe_events": (set(), {"event_type"}),
    "call_service": ({"domain", "service"}, {"service_data", "target", "return_response"}),
}


class FakeHa:
    def __init__(self) -> None:
        self.received: list[dict[str, Any]] = []
        self.devices = [
            {
                "id": "ha-device-1",
                "config_entries": ["entry-1"],
                "identifiers": [["tabula", DEVICE_ID]],
                "name": "Office Kiosk",
            }
        ]
        self.entities = [
            {"entity_id": "binary_sensor.office_kiosk_connectivity", "config_entry_id": "entry-1"}
        ]
        self.states = [
            {
                "entity_id": "binary_sensor.office_kiosk_connectivity",
                "state": "on",
                "last_changed": "2026-11-21T00:00:00+00:00",
            }
        ]
        self.events: list[dict[str, Any]] = []

    async def websocket(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        await ws.send_json({"type": "auth_required"})
        auth = await ws.receive_json()
        if auth != {"type": "auth", "access_token": TOKEN}:
            await ws.send_json({"type": "auth_invalid"})
            await ws.close()
            return ws
        await ws.send_json({"type": "auth_ok"})
        async for msg in ws:
            frame = json.loads(msg.data)
            self.received.append(frame)
            await ws.send_json(self.answer(frame))
            if frame["type"] == "subscribe_events":
                for event in self.events:
                    await ws.send_json({"id": frame["id"], "type": "event", "event": event})
        return ws

    def answer(self, frame: dict[str, Any]) -> dict[str, Any]:
        def ok(result: Any) -> dict[str, Any]:
            return {"id": frame["id"], "type": "result", "success": True, "result": result}

        kind = frame["type"]
        required, optional = SCHEMAS.get(kind, (None, set()))
        keys = set(frame) - {"id", "type"}
        if required is None or not required <= keys or not keys <= required | optional:
            return {
                "id": frame["id"],
                "type": "result",
                "success": False,
                "error": {"code": "invalid_format", "message": f"bad message: {sorted(frame)}"},
            }
        if kind == "config/device_registry/list":
            return ok(self.devices)
        if kind == "config/entity_registry/list":
            return ok(self.entities)
        if kind == "get_states":
            return ok(self.states)
        if kind == "subscribe_events":
            return ok(None)
        return ok({"context": {"id": "ctx"}, "response": None})  # call_service


@pytest.fixture(autouse=True)
def _real_sockets(socket_enabled):
    """The fake HA is a real server; PHACC's plugin disables sockets otherwise."""


@pytest.fixture
async def ha() -> AsyncIterator[tuple[FakeHa, str]]:
    fake = FakeHa()
    app = web.Application()
    app.router.add_get("/api/websocket", fake.websocket)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = runner.addresses[0][1]
    yield fake, f"http://127.0.0.1:{port}"
    await runner.cleanup()


# --- ha_query / ha_events -------------------------------------------------------


async def test_navigate_sends_service_data(ha, capsys):
    fake, url = ha
    ha_query = load("ha_query")
    assert await ha_query.navigate(url, TOKEN, DEVICE_ID, "/profile") == 0
    call = next(f for f in fake.received if f["type"] == "call_service")
    assert call["service_data"] == {"path": "/profile"}
    assert call["target"] == {"device_id": "ha-device-1"}
    assert (call["domain"], call["service"]) == ("tabula", "navigate")


async def test_fake_ha_rejects_data_like_ha_does(ha):
    """The regression guard above only works if `data` really fails here."""
    fake, _ = ha
    reply = fake.answer(
        {"id": 1, "type": "call_service", "domain": "a", "service": "b", "data": {}}
    )
    assert reply["success"] is False


@pytest.mark.parametrize(
    "frame",
    [
        {"type": "call_service", "domain": "tabula"},  # no service
        {"type": "call_service", "service": "navigate"},  # no domain
        {"type": "get_states", "extra": 1},
        {"type": "subscribe_events", "eventType": "x"},
        {"type": "no/such_command"},
    ],
)
async def test_fake_ha_rejects_what_ha_rejects(ha, frame):
    fake, _ = ha
    assert fake.answer({"id": 1, **frame})["success"] is False


async def test_query_lists_the_device_and_its_entities(ha, capsys):
    _, url = ha
    ha_query = load("ha_query")
    assert await ha_query.run(url, TOKEN, DEVICE_ID) == 0
    out = capsys.readouterr().out
    assert "tabula devices in registry: 1" in out
    assert DEVICE_ID in out
    assert "binary_sensor.office_kiosk_connectivity: state=on" in out


async def test_query_fails_on_a_failed_command(ha):
    fake, url = ha
    fake.answer = lambda frame: {"id": frame["id"], "type": "result", "success": False}  # type: ignore[method-assign]
    assert await load("ha_query").run(url, TOKEN, DEVICE_ID) == 1


async def test_query_fails_on_bad_auth(ha):
    _, url = ha
    assert await load("ha_query").run(url, "wrong", DEVICE_ID) == 1


async def test_events_prints_each_event(ha, capsys):
    fake, url = ha
    fake.events = [{"time_fired": "t", "data": {"type": "started"}}]
    assert await load("ha_events").run(url, TOKEN, "tabula_screensaver", 0.5) == 0
    assert json.loads(capsys.readouterr().out.strip()) == {
        "time_fired": "t",
        "data": {"type": "started"},
    }


# --- logcat filters -------------------------------------------------------------

FILTER_SPEC = re.compile(r"(\$?\{?[A-Za-z_][\w]*\}?):([VDIWEFS])(?![\w])")


def repeated_tags(line: str) -> list[str]:
    """Tags given more than once in one logcat filter line."""
    tags = [m.group(1) for m in FILTER_SPEC.finditer(line)]
    return sorted({t for t in tags if tags.count(t) > 1})


def test_repeated_tags_catches_the_old_bug():
    assert repeated_tags('logcat -d -s "TabulaService:I" "TabulaService:W"') == ["TabulaService"]
    assert repeated_tags('logcat -s "$tag:I" "$tag:W"') == ["$tag"]
    assert repeated_tags('logcat -s "$service_tag:I" "$dash_tag:I"') == []


@pytest.mark.parametrize("script", SCRIPTS, ids=lambda p: p.name)
def test_no_logcat_filter_repeats_a_tag(script: Path):
    # `-s Tag:I` already includes W and E; a second `Tag:W` replaces the
    # first and hides everything below warnings.
    for lineno, line in enumerate(script.read_text().splitlines(), start=1):
        if "logcat" in line or "log_tags" in line:
            assert not repeated_tags(line), f"{script.name}:{lineno}: {line.strip()}"


# --- prompts --------------------------------------------------------------------


@pytest.mark.parametrize("script", SCRIPTS, ids=lambda p: p.name)
def test_scripts_prompt_only_through_prompt_sh(script: Path):
    for lineno, line in enumerate(script.read_text().splitlines(), start=1):
        assert not re.search(r"\bread\b[^#]*\s-[a-z]*p", line), (
            f"{script.name}:{lineno}: use pause/ask from prompt.sh, which fail fast "
            "without a terminal"
        )


@pytest.mark.parametrize("call", ["pause 'Cover the sensor'", "ask 'Did it chime?'"])
@pytest.mark.parametrize("stdin", [subprocess.DEVNULL, subprocess.PIPE], ids=["devnull", "open"])
def test_prompts_fail_fast_without_a_terminal(call: str, stdin: int):
    # An open pipe that never sends anything is the case that used to hang.
    result = subprocess.run(
        ["bash", "-c", f"set -euo pipefail; source {DEVICE / 'prompt.sh'}; {call}"],
        stdin=stdin,
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert result.returncode == 3
    assert "needs a person at a terminal" in result.stderr


# --- dry run: check_p21 against the stub adb and fake HA ------------------------

STUB_ADB = f"""#!{sys.executable}
import json, os, sys
args = sys.argv[1:]
if args[:1] == ["-s"]:
    args = args[2:]
with open(os.environ["STUB_ADB_LOG"], "a") as log:
    log.write(json.dumps(args) + "\\n")
cmd = " ".join(args)
if cmd == "devices -l":
    print("List of devices attached\\nSTUB123 device product:stub")
elif cmd.startswith("shell pidof"):
    print("4242")
elif cmd.startswith("shell run-as") and "tabula_device.xml" in cmd:
    print('<string name="device_id">{DEVICE_ID}</string>')
elif cmd.startswith("shell logcat -d"):
    print("I TabulaService: connection lost")
    print("I TabulaService: registered with HA device ha-device-1")
"""


@pytest.fixture
def stub_adb(tmp_path: Path) -> tuple[Path, Path]:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    adb = bin_dir / "adb"
    adb.write_text(STUB_ADB)
    adb.chmod(0o755)
    return bin_dir, tmp_path / "adb.log"


@pytest.mark.skipif(shutil.which("uv") is None, reason="the check scripts run ha_query via uv")
async def test_check_p21_dry_run(ha, stub_adb):
    fake, url = ha
    bin_dir, log = stub_adb
    env = {
        **os.environ,
        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
        "STUB_ADB_LOG": str(log),
        "TABULA_HA_URL": url,
        "TABULA_HA_TOKEN": TOKEN,
    }
    env.pop("ANDROID_SERIAL", None)
    proc = await asyncio.create_subprocess_exec(
        "bash",
        str(DEVICE / "check_p21.sh"),
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=env,
    )
    out, _ = await asyncio.wait_for(proc.communicate(), timeout=120)
    text = out.decode()
    assert proc.returncode == 0, text
    assert "P2.1 hardware check complete" in text

    calls = [json.loads(line) for line in log.read_text().splitlines()]
    filters = [c[c.index("-s") + 1 :] for c in calls if c[:2] == ["shell", "logcat"] and "-s" in c]
    assert filters and all(f == ["TabulaService:I"] for f in filters), filters
    # the radio went off and came back on
    airplane = [c[-1] for c in calls if c[:4] == ["shell", "cmd", "connectivity", "airplane-mode"]]
    assert airplane == ["enable", "disable"]
    assert sum(f["type"] == "config/device_registry/list" for f in fake.received) == 3


# --- audio opt-in ---------------------------------------------------------------

# Audio checks make the tablet listen and speak in the room it lives in, so they
# run only in a session the owner scheduled. The guard is the first executable
# line of the script and needs no device to fire.
AUDIO_SCRIPTS = [
    DEVICE / "check_p70.sh",
    DEVICE / "check_p25.sh",
    DEVICE / "check_p90.sh",
    DEVICE / "check_p90_owner.sh",
    DEVICE / "check_p90_cost.sh",
]


@pytest.mark.parametrize("script", AUDIO_SCRIPTS, ids=lambda p: p.name)
def test_audio_script_refuses_without_the_opt_in(script: Path, stub_adb):
    bin_dir, log = stub_adb
    env = {
        **os.environ,
        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
        "STUB_ADB_LOG": str(log),
    }
    env.pop("TABULA_AUDIO_OK", None)
    env.pop("ANDROID_SERIAL", None)
    result = subprocess.run(
        ["bash", str(script)],
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        env=env,
        timeout=30,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "scheduled with the owner" in result.stderr
    assert "TABULA_AUDIO_OK" in result.stderr
    # nothing reached the device: the stub adb was never called
    assert not log.exists(), log.read_text()


def test_every_audio_script_is_behind_the_opt_in():
    for script in SCRIPTS:
        text = script.read_text()
        if "RECORD_AUDIO" in text or "tts.speak" in text or "media_player" in text:
            assert script in AUDIO_SCRIPTS, (
                f"{script.name} drives audio but is not in AUDIO_SCRIPTS, so nothing "
                "checks that it refuses to run without TABULA_AUDIO_OK=1"
            )
