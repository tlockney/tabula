# ruff: noqa: E501  # the fixtures are real logcat lines; wrapping them would change them
"""Tests for tools/device/intercom_report.py, the P9.0 log summariser.

The arithmetic that matters is the latency: each device logs the relay's clock
beside its own, and the mouth-to-ear figure has to compare two instants from
different clocks without either device's uptime leaking in. These tests fix
that with synthetic logs whose true answer is known, and check the report
refuses to invent a number when a tone was never heard.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
DEVICE = ROOT / "tools" / "device"


def load() -> Any:
    spec = importlib.util.spec_from_file_location("intercom_report", DEVICE / "intercom_report.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    # dataclasses resolve their module by name, so it has to be registered.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


report = load()


SENDER = """\
11-21 19:00:00.000 I/TabulaIntercomSpike(1): spike: call role=call room=r1 aec=false listenOnly=false toneAfter=8.0 recordSeconds=0 durationSeconds=60 relay=http://x:8099 sdk=28 device=KFTRPWI
11-21 19:00:00.100 I/TabulaIntercomSpike(1): spike: call offer-sent elapsedMs=100000 sdpLines=38
11-21 19:00:00.600 I/TabulaIntercomSpike(1): spike: call connected fromOfferMs=500
11-21 19:00:20.000 I/TabulaIntercomSpike(1): spike: tone-start elapsedMs=120000 amplitude=0.35 durationMs=2000
11-21 19:00:21.000 I/TabulaIntercomSpike(1): spike: time-sync serverMs=131000 elapsedMs=130000 rttMs=20
11-21 19:00:23.000 I/TabulaIntercomSpike(1): spike: stats codec mime=audio/opus clock=48000 channels=2
11-21 19:00:23.000 I/TabulaIntercomSpike(1): spike: stats out kind=audio packets=900 bytes=72000 level=null
11-21 19:00:23.000 I/TabulaIntercomSpike(1): spike: stats pair rtt=0.004 local=a remote=b
11-21 19:00:23.000 I/TabulaIntercomSpike(1): spike: stats local-candidate type=host protocol=udp address=192.168.1.129 port=60043
"""

RECEIVER = """\
11-21 19:00:00.000 I/TabulaIntercomSpike(2): spike: call role=answer room=r1 aec=true listenOnly=true toneAfter=0.0 recordSeconds=40 durationSeconds=60 relay=http://x:8099 sdk=28 device=KFTRPWI
11-21 19:00:00.200 I/TabulaIntercomSpike(2): spike: call offer-received elapsedMs=200000
11-21 19:00:00.500 I/TabulaIntercomSpike(2): spike: call connected fromOfferMs=300
11-21 19:00:02.000 I/TabulaIntercomSpike(2): spike: call first-audio side=in fromOfferMs=2000
11-21 19:00:20.168 I/TabulaIntercomSpike(2): spike: tone-onset elapsedMs=120168 rms=904 file=/sdcard/rec.wav
11-21 19:00:21.000 I/TabulaIntercomSpike(2): spike: time-sync serverMs=201010 elapsedMs=200000 rttMs=20
11-21 19:00:23.000 I/TabulaIntercomSpike(2): spike: stats in kind=audio packets=1900 bytes=152000 lost=0 jitter=0.005 level=0.001
11-21 19:00:23.000 I/TabulaIntercomSpike(2): spike: stats pair rtt=0.004 local=c remote=d
11-21 19:00:23.000 I/TabulaIntercomSpike(2): spike: stats remote-candidate type=host protocol=udp address=192.168.1.103 port=52650
"""


@pytest.fixture
def logs(tmp_path: Path) -> tuple[Path, Path]:
    sender = tmp_path / "sender.log"
    receiver = tmp_path / "receiver.log"
    sender.write_text(SENDER)
    receiver.write_text(RECEIVER)
    return sender, receiver


def test_parses_the_call_shape(logs: tuple[Path, Path]) -> None:
    sender, _ = logs
    log = report.parse(sender)
    assert log.role == "call"
    assert log.room == "r1"
    assert log.aec == "false"
    assert log.connected_from_offer_ms == 500
    assert log.first_audio == {}
    assert log.codec == "audio/opus"
    assert log.packets_out == 900
    assert log.rtts == [4.0]
    assert "host/udp 192.168.1.129:60043" in log.local_candidates


def test_latency_uses_the_relay_clock_for_both_ends(logs: tuple[Path, Path]) -> None:
    sender, receiver = logs
    value, why = report.latency(report.parse(sender), report.parse(receiver))
    assert why == ""
    # play at sender 120000 with offset 990; heard at receiver 120168 with
    # offset 1000: the two devices' uptimes never enter the figure.
    assert value == pytest.approx(178.0)


def test_offset_takes_the_lowest_rtt_sample(tmp_path: Path) -> None:
    path = tmp_path / "one.log"
    path.write_text(
        "spike: time-sync serverMs=100100 elapsedMs=100000 rttMs=100\n"
        "spike: time-sync serverMs=200010 elapsedMs=200000 rttMs=20\n"
    )
    log = report.parse(path)
    assert log.offset is not None
    assert log.offset.offset_ms == pytest.approx(0.0)
    assert log.offset_spread_ms == pytest.approx(50.0)


def test_no_tone_means_no_number(tmp_path: Path, logs: tuple[Path, Path]) -> None:
    sender, receiver_path = logs
    receiver_path.write_text(
        RECEIVER.replace(
            "spike: tone-onset elapsedMs=120168 rms=904 file=/sdcard/rec.wav",
            "spike: tone-onset none (no tone above the noise floor) file=/sdcard/rec.wav",
        )
    )
    value, why = report.latency(report.parse(sender), report.parse(receiver_path))
    assert value is None
    assert why == "the receiver heard no tone"


def test_report_prints_the_measurements(
    logs: tuple[Path, Path], capsys: pytest.CaptureFixture[str]
) -> None:
    sender, receiver = logs
    assert report.report(sender, receiver) == 0
    out = capsys.readouterr().out
    assert "tone play to tone heard:         178 ms" in out
    assert "codec:                           audio/opus" in out
    assert "packets in 1900" in out


def test_missing_sender_tone_is_reported(logs: tuple[Path, Path]) -> None:
    sender, receiver = logs
    sender.write_text(SENDER.replace("spike: tone-start", "spike: nothing"))
    value, why = report.latency(report.parse(sender), report.parse(receiver))
    assert value is None
    assert why == "the sender logged no tone-start"
