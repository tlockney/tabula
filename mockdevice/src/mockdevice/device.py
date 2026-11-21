"""Simulated device: state, capabilities and command handlers with failure and delay modes."""

from __future__ import annotations

import asyncio
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import urljoin, urlsplit

from mockdevice import __version__

ALL_CAPABILITIES = [
    "screen",
    "brightness",
    "adaptive_brightness",
    "screensaver",
    "notifications",
    "audio",
    "battery",
    "navigation",
    "logs",
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
    "reload": None,
    "restart_app": None,
    "clear_cache": None,
    "log_subscription": "logs",
    "screenshot": "screenshot",
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
    ) -> None:
        self.base_url = base_url
        self.capabilities = list(capabilities or ALL_CAPABILITIES)
        self.behavior = behavior or Behavior()
        self.settings_revision = settings_revision
        self.settings: dict[str, Any] = {}
        self.notifications: list[dict[str, Any]] = []
        self.played: list[dict[str, Any]] = []
        self.log_streaming = False
        self.log_lines: list[dict[str, Any]] = []
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
            "current_page": "/lovelace/0",
            "volume": 50,
            "muted": False,
            "media_state": "idle",
            "app_version": __version__,
        }

    def update(self, **changes: Any) -> dict[str, Any]:
        delta = {k: v for k, v in changes.items() if self.state.get(k) != v}
        if delta:
            self.state.update(delta)
            self.on_change(delta)
        return delta

    def begin_connection(self) -> None:
        """A new connection registered: the stale-revision rule restarts (PROTOCOL.md 4.5)."""
        self._connection_revision = None
        self.log_streaming = False

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
        result = getattr(self, f"_cmd_{command}")(args)
        self.log("info", "command", f"applied {command}")
        return result

    # --- command handlers -------------------------------------------------

    def _cmd_set_settings(self, args: dict[str, Any]) -> dict[str, Any]:
        applied = self._connection_revision
        if applied is not None and args["revision"] < applied:
            raise CommandError("stale_revision", f"revision {applied} applied on this connection")
        self.settings = args["settings"]
        self.settings_revision = args["revision"]
        self._connection_revision = args["revision"]
        return {"revision": self.settings_revision}

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

    def _cmd_screenshot(self, args: dict[str, Any]) -> None:  # pragma: no cover - capability off
        raise CommandError("unsupported_command", "screenshot not implemented in the mock")
