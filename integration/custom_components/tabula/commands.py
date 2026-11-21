"""Send a TLP command and await its result (docs/PROTOCOL.md 4.5-4.6).

P1.5 needs exactly one command (set_settings, pushed by settings.py). P1.4
grows this module into the full command set behind the HA services; the
send/await/timeout path is shared and stays as it is here.

A command is an ``event`` frame sent on the device's subscription, carrying a
fresh ``command_id``; the device answers ``tabula/result`` on the same
connection, and ws_api resolves the pending future by (device, command_id)
(4.6). Senders validate strictly (4.0) and frames obey the size cap.
"""

from __future__ import annotations

import asyncio
import json
from typing import TYPE_CHECKING, Any

from homeassistant.util import ulid as ulid_util

from .const import COMMAND_TIMEOUT, FRAME_MAX_BYTES
from .tlp import Frame

if TYPE_CHECKING:
    from . import DeviceRecord, TabulaHub


class CommandError(Exception):
    """A command was not applied: the device is gone, refused, or too slow."""


class DeviceNotConnected(CommandError):
    """The device has no subscribed session to send the command on."""

    def __init__(self, command: str) -> None:
        super().__init__(f"{command}: device is not connected")


class CommandNacked(CommandError):
    """The device answered ok=false."""

    def __init__(self, command: str, code: str, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(f"{command} refused: {code}: {message}")


class CommandTimedOut(CommandError):
    """COMMAND_TIMEOUT (ADR 0002) passed with no tabula/result."""

    def __init__(self, command: str) -> None:
        super().__init__(f"{command}: no result before timeout")


async def async_send_command(
    hub: TabulaHub,
    record: DeviceRecord,
    command: str,
    args: dict[str, Any],
    timeout: float = COMMAND_TIMEOUT,
) -> Frame:
    """Send one command on the device's subscription and await its result.

    Raises DeviceNotConnected when the device has no current subscribed
    session, CommandNacked on ok=false, CommandTimedOut when nothing arrives
    before `timeout`.
    """
    session = record.current
    if session is None or session.subscription_id is None:
        raise DeviceNotConnected(command)
    command_id = ulid_util.ulid_now()
    frame: Frame = {
        "id": session.subscription_id,
        "type": "event",
        "event": {"command_id": command_id, "command": command, "args": args},
    }
    # Strict sender validation (PROTOCOL.md 4.0); the size cap is the sender's
    # duty too (4, "Size") - a device that cannot take the frame must not get it.
    hub.tlp.validate(f"command_{command}", frame)
    if len(json.dumps(frame).encode()) > FRAME_MAX_BYTES:
        raise CommandError(f"{command}: frame exceeds {FRAME_MAX_BYTES} bytes")
    key = (record.device_id, command_id)
    future: asyncio.Future[Frame] = asyncio.get_running_loop().create_future()
    hub.result_futures[key] = future
    try:
        session.connection.send_event(session.subscription_id, frame["event"])
        result = await asyncio.wait_for(future, timeout)
    except TimeoutError as e:
        raise CommandTimedOut(command) from e
    finally:
        hub.result_futures.pop(key, None)
    if not result.get("ok", False):
        error = result.get("error") or {}
        raise CommandNacked(command, error.get("code", "failed"), error.get("message", ""))
    return result
