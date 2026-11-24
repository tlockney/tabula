"""Simulated device: state, capabilities and command handlers with failure and delay modes."""

from __future__ import annotations

import asyncio
import re
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import urljoin, urlsplit

from mockdevice import __version__
from mockdevice.constants import (
    INTERCOM_CALL_LIMIT_S,
    INTERCOM_RING_LIMIT_S,
    INTERCOM_SIGNAL_MAX_BYTES,
    SCREENSHOT_JPEG,
)

ALL_CAPABILITIES = [
    "screen",
    "brightness",
    "adaptive_brightness",
    "screensaver",
    "notifications",
    "audio",
    "battery",
    "navigation",
    "go_home",
    "logs",
    "screenshot",
    "illuminance",
    "wake_word",
    # Intercom (P9.1, ADR 0025 decision 7): listen can receive audio, talk can
    # capture. The mock carries no audio, so declaring both is a claim about
    # its signaling only.
    "intercom.listen",
    "intercom.talk",
]

# Which capability a command needs (docs/PROTOCOL.md 4.5); None = always available.
COMMAND_CAPABILITY: dict[str, str | None] = {
    "set_settings": None,
    "screen": "screen",
    "notify": "notifications",
    "play_media": "audio",
    "media_control": "audio",
    "screensaver": "screensaver",
    "navigate": "navigation",
    "go_home": "go_home",
    "reload": None,
    "restart_app": None,
    "clear_cache": None,
    "log_subscription": "logs",
    "screenshot": "screenshot",
    # Intercom commands are gated by listen: every device that can take part in
    # a call can receive one, whether or not it can talk back (ADR 0025
    # decision 7). intercom_start_request is the talk-side event.
    "intercom_ring": "intercom.listen",
    "intercom_signal": "intercom.listen",
    "intercom_end": "intercom.listen",
}


_SECRET = re.compile(
    r"(?i)(authorization:\s*\S+(?:\s+\S+)?|access_token=[^&\s]+|authSig=[^&\s]+"
    r"|refresh_token[\"'=:\s]+[^&\s\"',}]+)"
)
_URL_QUERY = re.compile(r"(https?://[^\s?#]+|(?<![\w/])/[^\s?#]*)[?#][^\s]*")


def redact(message: str) -> str:
    """Remove credentials and URL query strings from a log line (docs/PROTOCOL.md 4.8)."""
    message = _SECRET.sub(lambda m: m.group(0).split("=")[0].split(":")[0] + "=<redacted>", message)
    return _URL_QUERY.sub(lambda m: m.group(1), message)


def page_path(path: str) -> str:
    """Path without query string or fragment, as current_page and page_changed carry it."""
    return urlsplit(path).path or "/"


def same_origin(base_url: str, path: str) -> bool:
    """True if `path` resolved against the HA base URL stays on that origin."""
    base, target = urlsplit(base_url), urlsplit(urljoin(base_url, path))
    return (base.scheme, base.netloc) == (target.scheme, target.netloc)


def new_call_id() -> str:
    """A fresh call ID in the schema's form (8-64 chars of [0-9A-Za-z_-])."""
    return "call-" + secrets.token_hex(8)


def now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass
class Behavior:
    """Test knobs: make commands fail, answer late, or never answer."""

    fail: set[str] = field(default_factory=set)
    delay: float = 0.0
    drop: set[str] = field(default_factory=set)


class CommandError(Exception):
    def __init__(self, code: str, message: str) -> None:
        self.code = code
        super().__init__(message)


class MockDevice:
    """Holds state and applies commands. Reports state changes through on_change."""

    def __init__(
        self,
        capabilities: list[str] | None = None,
        behavior: Behavior | None = None,
        settings_revision: int = 0,
        base_url: str = "http://homeassistant.local:8123",
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.base_url = base_url
        # The clock drives the return-to-start countdown (F-DSH-15, P3.15);
        # tests inject a fake one.
        self.clock = clock or time.monotonic
        self._last_activity = self.clock()
        self.capabilities = list(capabilities or ALL_CAPABILITIES)
        self.behavior = behavior or Behavior()
        self.settings_revision = settings_revision
        self.settings: dict[str, Any] = {}
        self.notifications: list[dict[str, Any]] = []
        self.played: list[dict[str, Any]] = []
        self.log_streaming = False
        self.log_lines: list[dict[str, Any]] = []
        # The latest capture (P3.13): bytes when the screen was on, None when it
        # was off (F-MGT-07). The client reads it after the command runs.
        self.last_screenshot: bytes | None = None
        self._connection_revision: int | None = None
        self.on_change: Callable[[dict[str, Any]], None] = lambda delta: None
        self.on_event: Callable[[str, dict[str, Any]], None] = lambda event, data: None
        self.state: dict[str, Any] = {
            "screen_on": True,
            "brightness": 60,
            "adaptive_brightness": False,
            "keep_screen_on": True,
            "screensaver_active": False,
            "screensaver_mode": "clock",
            "screensaver_brightness": 20,
            "next_screensaver": (datetime.now(UTC) + timedelta(minutes=5))
            .replace(microsecond=0)
            .isoformat()
            .replace("+00:00", "Z"),
            "battery_level": 87,
            "charging": True,
            "network": "wifi",
            "wifi_rssi": -54,
            # the room's light (P3.16): the mock "has a light sensor"
            "illuminance": 128.5,
            "current_page": "/lovelace/0",
            "volume": 50,
            "muted": False,
            "media_state": "idle",
            # the capability is always declared, so the flag is always sent;
            # off until a settings push switches it on (P7.1, ADR 0030)
            "wake_word_active": False,
            "intercom_state": "idle",
            "app_version": __version__,
        }
        # The call this device is in, if any (P9.1): its id, mode, caller and
        # the two deadlines the tests drive with the injected clock.
        self.intercom_call: dict[str, Any] | None = None
        self.intercom_signals: list[dict[str, Any]] = []

    def update(self, **changes: Any) -> dict[str, Any]:
        delta = {k: v for k, v in changes.items() if self.state.get(k) != v}
        if delta:
            self.state.update(delta)
            self.on_change(delta)
        return delta

    def begin_connection(self) -> None:
        """A new connection registered: the stale-revision rule restarts (PROTOCOL.md 4.5)."""
        # No auto-resume after a reconnect or a restart (ADR 0028 guard 4): a
        # call that was up or ringing when the session dropped does not come
        # back, and the device says so on the new connection.
        if self.intercom_call is not None:
            self._end_intercom("disconnect")
        self._connection_revision = None
        self.log_streaming = False
        # A registration is a fresh launch: the mock lands on its start target
        # (F-DSH-02, P3.15). An invalid or off-origin path keeps the default
        # page, exactly as the app falls back.
        self._land_on_start_page()

    # --- start target (P3.15, F-DSH-02, F-DSH-15) -------------------------

    def start_path(self) -> str:
        """dashboard.start_path, or "" when unset."""
        return self.settings.get("dashboard", {}).get("start_path", "")

    def _return_after(self) -> float:
        """dashboard.return_home_after in seconds; 0 (or absent) never returns."""
        return float(self.settings.get("dashboard", {}).get("return_home_after", 0))

    def _land_on_start_page(self) -> None:
        path = self.start_path()
        if path and same_origin(self.base_url, path):
            self.update(current_page=page_path(path))

    def _cmd_go_home(self, args: dict[str, Any]) -> None:
        path = self.start_path()
        if path and not same_origin(self.base_url, path):
            raise CommandError("invalid_args", "start path leaves the Home Assistant origin")
        target = page_path(path) if path else "/"
        self.update(current_page=target)
        self.on_event("page_changed", {"path": target})

    def touch(self) -> None:
        """A touch on the kiosk: resets the return-to-start countdown (F-DSH-15)."""
        self._last_activity = self.clock()

    def tick(self) -> bool:
        """The clock moved: return to the start target after the idle delay.

        The countdown runs behind a screensaver (F-DSH-15, F-OPT) and from the
        last touch; a device already on the start target (path only, so a
        fragment target counts as home) stays put. Either way the countdown
        starts over once the deadline has passed (ADR 0021).

        No configured start target (empty, or "/" - which the frontend
        redirects client-side to its default panel and which the page can
        never rest on) means no idle return at all: returning would reload
        once per delay period forever (ADR 0021 item 7).
        """
        delay = self._return_after()
        if delay <= 0:
            return False
        now = self.clock()
        if now - self._last_activity < delay:
            return False
        self._last_activity = now
        path = self.start_path()
        if not path or path == "/":
            return False
        target = page_path(path)
        if target is None or self.state["current_page"] == target:
            return False
        if not same_origin(self.base_url, path):
            return False
        self.update(current_page=target)
        self.on_event("page_changed", {"path": target})
        return True

    def log(self, level: str, tag: str, message: str) -> None:
        if self.log_streaming:
            self.log_lines.append(
                {"ts": now_iso(), "level": level, "tag": tag, "message": redact(message)[:4000]}
            )

    def should_drop(self, command: str) -> bool:
        return command in self.behavior.drop

    async def handle(self, command: str, args: dict[str, Any]) -> dict[str, Any] | None:
        """Apply a command. Returns result data, or raises CommandError for a NACK."""
        if self.behavior.delay:
            await asyncio.sleep(self.behavior.delay)
        if command in self.behavior.fail:
            raise CommandError("failed", f"{command} failed (mock failure mode)")
        cap = COMMAND_CAPABILITY.get(command, "unknown")
        if cap == "unknown" or (cap is not None and cap not in self.capabilities):
            raise CommandError("unsupported_command", f"{command} not supported")
        self.check_intercom_timers()
        result = getattr(self, f"_cmd_{command}")(args)
        self.log("info", "command", f"applied {command}")
        return result

    # --- command handlers -------------------------------------------------

    def _cmd_set_settings(self, args: dict[str, Any]) -> dict[str, Any]:
        applied = self._connection_revision
        if applied is not None and args["revision"] < applied:
            raise CommandError("stale_revision", f"revision {applied} applied on this connection")
        old_delay = self._return_after()
        self.settings = args["settings"]
        self._apply_voice_settings()
        # A changed return delay restarts the countdown (ADR 0021 item 5);
        # a changed start path never moves the page (F-DSH-02).
        if self._return_after() != old_delay:
            self.touch()
        self.settings_revision = args["revision"]
        self._connection_revision = args["revision"]
        return {"revision": self.settings_revision}

    # --- intercom (P9.1, ADR 0025) -----------------------------------------

    def _intercom_state(self, state: str) -> None:
        self.update(intercom_state=state)

    def check_intercom_timers(self) -> str | None:
        """Apply the ring limit and the hard call limit (ADR 0028 guards 2).

        The mock opens no microphone and plays nothing, so the deadlines are
        the whole of its timekeeping: a ring that nobody answers ends after
        ``INTERCOM_RING_LIMIT_S`` and a call ends by itself after
        ``INTERCOM_CALL_LIMIT_S``. Tests drive this with the injected clock;
        every command checks it too, so a live mock notices on its next one.
        Returns the reason the call ended, or None.
        """
        call = self.intercom_call
        if call is None:
            return None
        now = self.clock()
        if self.state["intercom_state"] == "ringing" and now >= call["ring_deadline"]:
            self._end_intercom("timeout")
            return "timeout"
        if now >= call["call_deadline"]:
            self._end_intercom("limit")
            return "limit"
        return None

    def _end_intercom(self, reason: str) -> None:
        """Leave the call, whatever state it is in, and report why."""
        call = self.intercom_call
        self.intercom_call = None
        self._intercom_state("idle")
        if call is not None:
            self.on_event("intercom_ended", {"call_id": call["call_id"], "reason": reason})

    def _cmd_intercom_ring(self, args: dict[str, Any]) -> None:
        """A call is offered: ring, or start a broadcast playing straight away.

        A broadcast is one-way and live (ADR 0025 decision 2): nobody answers
        it, so the device is in the call immediately and its microphone stays
        closed. A call rings until someone answers, declines, or the ring limit
        passes.
        """
        if self.intercom_call is not None:
            raise CommandError("busy", "already in a call")
        now = self.clock()
        self.intercom_call = {
            "call_id": args["call_id"],
            "mode": args["mode"],
            "caller_device_id": args["caller_device_id"],
            "caller_name": args["caller_name"],
            "ring_deadline": now + INTERCOM_RING_LIMIT_S,
            "call_deadline": now + INTERCOM_CALL_LIMIT_S,
        }
        self._intercom_state("ringing" if args["mode"] == "call" else "in_call")

    def _cmd_intercom_signal(self, args: dict[str, Any]) -> None:
        """Relay one signal for the current call; the mock has no audio to set up."""
        self._require_call(args["call_id"])
        if len(args["payload"].encode()) > INTERCOM_SIGNAL_MAX_BYTES:
            raise CommandError("invalid_args", "signal payload is over the size limit")
        self.intercom_signals.append({"kind": args["kind"], "payload": args["payload"]})
        self.intercom_call["last_signal_at"] = self.clock()

    def _cmd_intercom_end(self, args: dict[str, Any]) -> None:
        """End the call. A device not in that call answers ok and changes nothing."""
        call = self.intercom_call
        if call is None or call["call_id"] != args["call_id"]:
            return
        self._end_intercom(args["reason"])

    def _require_call(self, call_id: str) -> dict[str, Any]:
        call = self.intercom_call
        if call is None or call["call_id"] != call_id:
            raise CommandError("invalid_args", "not in that call")
        return call

    def answer_intercom_call(self) -> None:
        """Answer a ringing call, as a person tapping answer does (P9.3 wires the screen).

        Answers with intercom_answer and starts the hard call limit, which runs
        from the moment the call is up (ADR 0028 guard 2).
        """
        call = self._ringing_call()
        call["call_deadline"] = self.clock() + INTERCOM_CALL_LIMIT_S
        self._intercom_state("in_call")
        self.on_event("intercom_answer", {"call_id": call["call_id"]})

    def decline_intercom_call(self) -> None:
        """Decline a ringing call: back to idle, and the caller hears why."""
        call = self._ringing_call()
        self.intercom_call = None
        self._intercom_state("idle")
        self.on_event("intercom_decline", {"call_id": call["call_id"]})

    def _ringing_call(self) -> dict[str, Any]:
        call = self.intercom_call
        if call is None or self.state["intercom_state"] != "ringing":
            raise CommandError("invalid_args", "no call is ringing")
        return call

    def send_intercom_signal(self, kind: str, payload: str) -> None:
        """Emit intercom_signal for the current call, the device's side of the relay."""
        call = self.intercom_call
        if call is None:
            raise CommandError("invalid_args", "no call is up")
        if len(payload.encode()) > INTERCOM_SIGNAL_MAX_BYTES:
            raise CommandError("invalid_args", "signal payload is over the size limit")
        self.on_event(
            "intercom_signal", {"call_id": call["call_id"], "kind": kind, "payload": payload}
        )

    def start_intercom_call(
        self,
        mode: str,
        target_device_id: str | None = None,
        target_area_id: str | None = None,
    ) -> None:
        """Ask HA for a call started on this device's screen (ADR 0025 decision 6).

        The device proposes the target and the call id; HA resolves the targets
        and rings them, so nothing changes here yet. The checks match the event
        schema: a call needs exactly one target, and the two targets exclude
        each other.
        """
        if target_device_id is not None and target_area_id is not None:
            raise CommandError("invalid_args", "a call has one target, device or area")
        if mode == "call" and target_device_id is None and target_area_id is None:
            raise CommandError("invalid_args", "a call needs one target")
        data: dict[str, Any] = {"call_id": new_call_id(), "mode": mode}
        if target_device_id is not None:
            data["target_device_id"] = target_device_id
        if target_area_id is not None:
            data["target_area_id"] = target_area_id
        self.on_event("intercom_start_request", data)

    # --- wake word (P7.1, ADR 0030) ---------------------------------------

    def _apply_voice_settings(self) -> None:
        """Follow voice.wake_word_enabled the way the app does at startup.

        ``wake_word`` is declared from the start: the app ships the detector in
        every build and the capability list is read once per session, so a
        device that declared it only while listening could not report that it
        is not listening (ADR 0030 items 3 and 5). The setting shows up in
        ``wake_word_active`` instead, which the mock sets to "on and not
        playing" because it has no audio to play.
        """
        enabled = bool(self.settings.get("voice", {}).get("wake_word_enabled"))
        self.update(wake_word_active=enabled)

    def detect_wake_word(self, phrase: str = "okay_nabu", probability: int = 231) -> None:
        """Simulate one accepted detection: the event, then the new timestamp.

        Raises CommandError when the wake word is off, which is what the real
        detector cannot do - it is not listening.
        """
        if not self.settings.get("voice", {}).get("wake_word_enabled"):
            raise CommandError("unsupported_command", "wake word is off")
        self.on_event("wake_word_detected", {"phrase": phrase, "probability": probability})
        self.update(last_wake_word=now_iso())

    def _cmd_screen(self, args: dict[str, Any]) -> None:
        changes: dict[str, Any] = {}
        if "on" in args:
            changes["screen_on"] = args["on"]
        if "brightness" in args:
            changes["brightness"] = args["brightness"]
            changes["adaptive_brightness"] = False  # manual change wins (plan 5.5)
        self.update(**changes)

    def _cmd_notify(self, args: dict[str, Any]) -> None:
        tag = args.get("tag")
        if tag and any(n.get("tag") == tag for n in self.notifications):
            self.notifications = [n for n in self.notifications if n.get("tag") != tag]
            self.on_event("notification_dismissed", {"tag": tag, "reason": "replaced"})
        self.notifications.append(args)
        self.on_event("notification_shown", {"tag": tag} if tag else {})

    def _cmd_play_media(self, args: dict[str, Any]) -> None:
        self.played.append(args)
        changes: dict[str, Any] = {"media_state": "playing"}
        if "volume" in args:
            changes["volume"] = args["volume"]
        self.update(**changes)

    def _cmd_media_control(self, args: dict[str, Any]) -> None:
        action = args["action"]
        if action in ("next", "previous"):
            raise CommandError("failed", "no queue")  # the mock plays single items
        if action == "seek":
            if self.state["media_state"] == "idle":
                raise CommandError("failed", "nothing to seek")
            return
        if action == "stop" and self.state["media_state"] != "idle":
            self.on_event("media_finished", {"reason": "stopped"})
        changes = {
            "stop": {"media_state": "idle"},
            "pause": {"media_state": "paused"},
            "resume": {"media_state": "playing"},
            "set_volume": {"volume": args.get("volume")},
            "mute": {"muted": True},
            "unmute": {"muted": False},
        }[action]
        self.update(**changes)

    def _cmd_screensaver(self, args: dict[str, Any]) -> None:
        changes: dict[str, Any] = {"screensaver_active": args["active"]}
        if "mode" in args:
            changes["screensaver_mode"] = args["mode"]
        if args["active"]:
            changes["next_screensaver"] = None
        self.update(**changes)
        event = "screensaver_started" if args["active"] else "screensaver_stopped"
        self.on_event(event, {"mode": self.state["screensaver_mode"], "trigger": "command"})

    def _cmd_navigate(self, args: dict[str, Any]) -> None:
        if not same_origin(self.base_url, args["path"]):
            raise CommandError("invalid_args", "path leaves the Home Assistant origin")
        path = page_path(args["path"])
        self.update(current_page=path)
        self.on_event("page_changed", {"path": path})

    def _cmd_reload(self, args: dict[str, Any]) -> None:
        return None

    def _cmd_restart_app(self, args: dict[str, Any]) -> None:
        return None

    def _cmd_clear_cache(self, args: dict[str, Any]) -> None:
        return None

    def _cmd_log_subscription(self, args: dict[str, Any]) -> None:
        self.log_streaming = args["enabled"]

    def _cmd_screenshot(self, args: dict[str, Any]) -> None:
        """Capture the screen (P3.13, PROTOCOL.md 4.5).

        The command's `quality` argument is validated against the schema but
        not applied: the mock has no screen, so a capture is the fixed JPEG in
        `mockdevice.constants`. The client uploads the capture before it
        answers the command, exactly as the app does (PROTOCOL.md 5). With the
        screen off there is nothing to show: the capture is None, no upload
        happens, and HA falls back to the placeholder (F-MGT-07).
        """
        quality = args.get("quality")
        if quality is not None and not 10 <= int(quality) <= 95:
            raise CommandError("invalid_args", "quality must be between 10 and 95")
        self.last_screenshot = SCREENSHOT_JPEG if self.state["screen_on"] else None
