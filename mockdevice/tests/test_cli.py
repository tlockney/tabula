from __future__ import annotations

import asyncio

import pytest
from mockdevice.cli import build_parser, do_pair, do_run
from mockdevice.store import DeviceStore


def test_help_lists_commands(capsys):
    with pytest.raises(SystemExit):
        build_parser().parse_args(["--help"])
    out = capsys.readouterr().out
    assert "pair" in out and "run" in out


async def test_pair_saves_credential(server, tmp_path, capsys):
    path = tmp_path / "device.json"
    args = build_parser().parse_args(["--ha", server.url, "--store", str(path), "pair"])
    task = asyncio.create_task(do_pair(args))
    while not server.pending:
        await asyncio.sleep(0.01)
    device_id, pending = next(iter(server.pending.items()))
    assert f"Pairing code: {pending.code}" in capsys.readouterr().out
    assert server.confirm(device_id, pending.code)
    assert await asyncio.wait_for(task, 5) == 0
    store = DeviceStore.load(path)
    assert store.refresh_token == server.devices[device_id].refresh_token
    assert oct(path.stat().st_mode & 0o777) == "0o600"


async def test_run_refuses_unpaired_store(tmp_path, capsys):
    args = build_parser().parse_args(
        ["--ha", "http://127.0.0.1:9", "--store", str(tmp_path / "d.json"), "run"]
    )
    assert await do_run(args) == 2
    assert "Not paired" in capsys.readouterr().err
