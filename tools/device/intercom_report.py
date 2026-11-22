#!/usr/bin/env python3
"""Summarises the P9.0 intercom spike's logs into the packet's measurements.

The spike app (android/spike-intercom, ADR 0028) logs one `spike:` line per
event under the tag `TabulaIntercomSpike`; the hardware checks stream both
devices' logcat into files. This reads two of those files and prints:

- connection: offer to connected, offer to first audio, both ends;
- the selected ICE candidate pair (host addresses on the LAN, packet question 2);
- the media: codec, round-trip time, jitter, packets and loss (questions 2, 3);
- the tone test: play instant, detection instant, and the mouth-to-ear latency
  in the relay's clock, so the two devices' independent clocks cancel
  (question 7). Each device logs `time-sync serverMs= elapsedMs= rttMs=` every
  5 s; the offset estimate uses the lowest-RTT sample and the spread of the
  samples is printed as its uncertainty.

Usage:
    python3 tools/device/intercom_report.py SENDER_LOG RECEIVER_LOG

Standard library only.
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

FIELDS = re.compile(r"(\w[\w-]*)=([^\s]+)")


@dataclass
class Sync:
    server_ms: float
    elapsed_ms: float
    rtt_ms: float

    @property
    def offset_ms(self) -> float:
        """Server clock minus the device's local clock at the same instant."""
        return self.server_ms - self.elapsed_ms - self.rtt_ms / 2.0


@dataclass
class DeviceLog:
    path: Path
    role: str = "?"
    room: str = "?"
    aec: str = "?"
    listen_only: str = "?"
    offer_sent_ms: float | None = None
    offer_received_ms: float | None = None
    answer_ms: float | None = None
    connected_from_offer_ms: float | None = None
    first_audio: dict[str, float] = field(default_factory=dict)
    tone_start_ms: float | None = None
    tone_onset_ms: float | None = None
    tone_onset_rms: int | None = None
    recording: str | None = None
    syncs: list[Sync] = field(default_factory=list)
    rtts: list[float] = field(default_factory=list)
    jitters: list[float] = field(default_factory=list)
    codec: str | None = None
    local_candidates: list[str] = field(default_factory=list)
    remote_candidates: list[str] = field(default_factory=list)
    packets_in: int = 0
    packets_out: int = 0
    lost_in: int = 0
    ended: dict[str, str] = field(default_factory=dict)
    recorder_refused: str | None = None
    events: list[str] = field(default_factory=list)

    @property
    def offset(self) -> Sync | None:
        return min(self.syncs, key=lambda s: s.rtt_ms) if self.syncs else None

    @property
    def offset_spread_ms(self) -> float | None:
        if len(self.syncs) < 2:
            return None
        offsets = [s.offset_ms for s in self.syncs]
        return max(offsets) - min(offsets)


def parse(path: Path) -> DeviceLog:
    log = DeviceLog(path)
    for line in path.read_text(errors="replace").splitlines():
        if "spike:" not in line:
            continue
        body = line.split("spike:", 1)[1].strip()
        fields = {k: v for k, v in FIELDS.findall(body)}
        log.events.append(body)
        if body.startswith("call role="):
            log.role = fields.get("role", log.role)
            log.room = fields.get("room", log.room)
            log.aec = fields.get("aec", log.aec)
            log.listen_only = fields.get("listenOnly", log.listen_only)
        elif body.startswith("call offer-sent"):
            log.offer_sent_ms = _num(fields.get("elapsedMs"))
        elif body.startswith("call offer-received"):
            log.offer_received_ms = _num(fields.get("elapsedMs"))
        elif body.startswith("call answer-sent") or body.startswith("call answer-received"):
            log.answer_ms = _num(fields.get("elapsedMs"))
        elif body.startswith("call connected"):
            log.connected_from_offer_ms = _num(fields.get("fromOfferMs"))
        elif body.startswith("call first-audio"):
            side = fields.get("side", "?")
            value = _num(fields.get("fromOfferMs"))
            if value is not None:
                log.first_audio[side] = value
        elif body.startswith("tone-start"):
            log.tone_start_ms = _num(fields.get("elapsedMs"))
        elif body.startswith("tone-onset"):
            log.tone_onset_ms = _num(fields.get("elapsedMs"))
            log.tone_onset_rms = int(_num(fields.get("rms")) or 0)
            log.recording = fields.get("file")
        elif body.startswith("time-sync"):
            s = Sync(
                server_ms=_num(fields.get("serverMs")) or 0.0,
                elapsed_ms=_num(fields.get("elapsedMs")) or 0.0,
                rtt_ms=_num(fields.get("rttMs")) or 0.0,
            )
            log.syncs.append(s)
        elif body.startswith("stats pair"):
            rtt = _num(fields.get("rtt"))
            if rtt is not None:
                log.rtts.append(rtt * 1000.0)
        elif body.startswith("stats in"):
            log.packets_in = max(log.packets_in, int(_num(fields.get("packets")) or 0))
            log.lost_in = max(log.lost_in, int(_num(fields.get("lost")) or 0))
            jitter = _num(fields.get("jitter"))
            if jitter is not None:
                log.jitters.append(jitter * 1000.0)
        elif body.startswith("stats out"):
            log.packets_out = max(log.packets_out, int(_num(fields.get("packets")) or 0))
        elif body.startswith("stats codec"):
            log.codec = fields.get("mime")
        elif body.startswith("stats local-candidate"):
            log.local_candidates.append(_candidate(fields))
        elif body.startswith("stats remote-candidate"):
            log.remote_candidates.append(_candidate(fields))
        elif body.startswith("call ended"):
            log.ended = fields
        elif "recorder refused" in body or "recorder could not" in body or "recorder not" in body:
            log.recorder_refused = body
    return log


def _num(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def _candidate(fields: dict[str, str]) -> str:
    return f"{fields.get('type', '?')}/{fields.get('protocol', '?')} {fields.get('address', '?')}:{fields.get('port', '?')}"


def _fmt(value: float | None, unit: str = "ms") -> str:
    return "n/a" if value is None else f"{value:.0f} {unit}"


def latency(sender: DeviceLog, receiver: DeviceLog) -> tuple[float | None, str]:
    """Mouth-to-ear in the relay's clock: (latency ms, why it is missing)."""
    if sender.tone_start_ms is None:
        return None, "the sender logged no tone-start"
    if receiver.tone_onset_ms is None or receiver.tone_onset_ms < 0:
        return None, "the receiver heard no tone"
    s_off, r_off = sender.offset, receiver.offset
    if s_off is None or r_off is None:
        return None, "one end logged no time-sync"
    play = sender.tone_start_ms + s_off.offset_ms
    hear = receiver.tone_onset_ms + r_off.offset_ms
    return hear - play, ""


def report(sender_path: Path, receiver_path: Path) -> int:
    sender = parse(sender_path)
    receiver = parse(receiver_path)
    print(f"sender   {sender_path}  role={sender.role} room={sender.room} aec={sender.aec} listenOnly={sender.listen_only}")
    print(f"receiver {receiver_path}  role={receiver.role} room={receiver.room} aec={receiver.aec} listenOnly={receiver.listen_only}")
    print()
    print("question 2 — connection")
    print(f"  offer to connected, caller:      {_fmt(sender.connected_from_offer_ms)}")
    print(f"  offer to connected, answerer:    {_fmt(receiver.connected_from_offer_ms)}")
    print(f"  offer to first audio, caller in:  {_fmt(receiver.first_audio.get('in'))}")
    print(f"  offer to first audio, answerer in:{_fmt(sender.first_audio.get('in'))}")
    print(f"  local candidates, caller:        {', '.join(sorted(set(sender.local_candidates))) or 'n/a'}")
    print(f"  local candidates, answerer:      {', '.join(sorted(set(receiver.local_candidates))) or 'n/a'}")
    print(f"  remote candidates seen, caller:  {', '.join(sorted(set(sender.remote_candidates))) or 'n/a'}")
    print(f"  remote candidates seen, answerer:{', '.join(sorted(set(receiver.remote_candidates))) or 'n/a'}")
    print()
    print("question 3 — media")
    print(f"  codec:                           {sender.codec or 'n/a'}")
    for label, log in (("caller", sender), ("answerer", receiver)):
        rtt = f"{statistics.median(log.rtts):.1f} ms" if log.rtts else "n/a"
        jit = f"{statistics.median(log.jitters):.1f} ms" if log.jitters else "n/a"
        print(
            f"  {label}: rtt {rtt}, jitter {jit}, packets in {log.packets_in}, "
            f"out {log.packets_out}, lost {log.lost_in}"
        )
    if receiver.recorder_refused:
        print(f"  receiver recorder: {receiver.recorder_refused}")
    if receiver.recording:
        print(f"  receiver recording: {receiver.recording}")
    print()
    print("question 7 — latency (tone test, relay clock)")
    sync_note = ""
    spreads = [s for s in (sender.offset_spread_ms, receiver.offset_spread_ms) if s is not None]
    if spreads:
        sync_note = f" (clock-offset spread ±{max(spreads) / 2:.0f} ms)"
    value, why = latency(sender, receiver)
    if value is None:
        print(f"  n/a: {why}")
    else:
        print(f"  tone play to tone heard:         {value:.0f} ms{sync_note}")
        print(f"  receiver onset rms:              {receiver.tone_onset_rms}")
    for label, log in (("sender", sender), ("receiver", receiver)):
        if log.offset:
            print(
                f"  {label} clock offset vs relay:    {log.offset.offset_ms:.0f} ms "
                f"(best rtt {log.offset.rtt_ms:.0f} ms, {len(log.syncs)} samples)"
            )
    if sender.ended or receiver.ended:
        print()
        print("end of call")
        for label, log in (("caller", sender), ("answerer", receiver)):
            if log.ended:
                print(
                    f"  {label}: duration {log.ended.get('durationMs', '?')} ms, "
                    f"packets in {log.ended.get('packetsIn', '?')}, out {log.ended.get('packetsOut', '?')}"
                )
    return 0 if value is not None else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarise P9.0 intercom spike logs")
    parser.add_argument("sender", type=Path)
    parser.add_argument("receiver", type=Path)
    args = parser.parse_args()
    for path in (args.sender, args.receiver):
        if not path.is_file():
            sys.stderr.write(f"intercom_report: no such log: {path}\n")
            return 2
    return report(args.sender, args.receiver)


if __name__ == "__main__":
    raise SystemExit(main())
