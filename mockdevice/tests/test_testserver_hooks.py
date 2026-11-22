"""HTTP test hooks on the in-process server (used by the Android JVM integration test too)."""

from __future__ import annotations

import pytest
from mockdevice.codec import Codec
from mockdevice.pairing import claim, request_pairing
from mockdevice.store import DeviceStore

codec = Codec()


async def test_confirm_hook_confirms_or_rejects_the_code(server, http, tmp_path):
    store = DeviceStore.load(tmp_path / "d.json")
    attempt, _ = await request_pairing(http, codec, server.url, store.device_id)

    wrong = await http.post(
        f"{server.url}/test/confirm",
        json={
            "device_id": store.device_id,
            "code": "000000" if attempt.code != "000000" else "111111",
        },
    )
    assert wrong.status == 200
    assert (await wrong.json())["confirmed"] is False

    right = await http.post(
        f"{server.url}/test/confirm",
        json={"device_id": store.device_id, "code": attempt.code},
    )
    assert right.status == 200
    assert (await right.json())["confirmed"] is True

    result = await claim(http, codec, server.url, attempt, poll_interval=0.01, timeout=2)
    assert result["credential"]["refresh_token"] == server.devices[store.device_id].refresh_token


async def test_confirm_hook_without_pending_is_404(server, http, tmp_path):
    store = DeviceStore.load(tmp_path / "d.json")
    resp = await http.post(
        f"{server.url}/test/confirm", json={"device_id": store.device_id, "code": "123456"}
    )
    assert resp.status == 404


async def test_command_hook_round_trips_a_result(server, http, store, start):
    await start(store)
    await server.wait_registered(store.device_id)
    resp = await http.post(
        f"{server.url}/test/command",
        json={
            "device_id": store.device_id,
            "command": "set_settings",
            "args": {"revision": 1, "schema_version": 1, "settings": {}},
        },
    )
    assert resp.status == 200
    result = (await resp.json())["result"]
    assert result["ok"] is True
    assert result["data"] == {"revision": 1}


async def test_command_hook_without_a_connection_conflicts(server, http, store):
    resp = await http.post(
        f"{server.url}/test/command",
        json={"device_id": store.device_id, "command": "reload", "args": {}},
    )
    assert resp.status == 409


async def test_report_hook_shows_the_server_view(server, http, store, start):
    await start(store)
    await server.wait_registered(store.device_id)
    resp = await http.get(f"{server.url}/test/report")
    assert resp.status == 200
    report = await resp.json()
    assert report["problems"] == []
    device = report["devices"][store.device_id]
    assert device["registered"] is True
    assert device["connections"] >= 1
    assert "battery_level" in device["state"]


@pytest.mark.parametrize(
    "argv",
    [
        ["--ha", "http://ha:8123", "pair"],
        ["serve"],  # serve needs no --ha
        ["serve", "--port", "8123"],
    ],
)
def test_cli_parser(argv):
    from mockdevice.cli import build_parser

    build_parser().parse_args(argv)


def test_cli_requires_ha_for_pair():
    from mockdevice.cli import main

    assert main(["pair"]) == 2  # --ha is missing; nothing runs


async def test_unknown_hook_is_404(server, http):
    for name in ("nope", "hook", "a.b"):
        resp = await http.post(f"{server.url}/test/{name}", json={})
        assert resp.status == 404, name


async def test_report_has_no_feature_sections_until_a_feature_writes_one(
    server, http, store, start
):
    await start(store)
    await server.wait_registered(store.device_id)
    report = await (await http.get(f"{server.url}/test/report")).json()
    assert "screenshot" not in report["devices"][store.device_id]
