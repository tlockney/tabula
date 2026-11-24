"""Read the Tabularium's log view from the command line (hardware check P5.4).

Three subcommands, all against the integration's admin-only panel commands
(integration/custom_components/tabula/logs.py), so a device check needs nothing
but HA and a long-lived admin token:

    # Which device is which: Tabula id, HA device id, name, connection
    uv run python tools/device/ha_logs.py devices --ha http://ha:8123 --token <token>

    # The buffered lines and the stream's state
    uv run python tools/device/ha_logs.py snapshot --ha ... --device <device_id>

    # Follow the stream for a while, one line each: "<ts> <level> <tag>: <message>"
    uv run python tools/device/ha_logs.py stream --ha ... --device <device_id> \
        --seconds 20 [--level warning]

`stream` prints a final `SUMMARY lines=N console=N` line and exits non-zero if
the subscription could not be opened (an offline device), so a caller can gate
on the evidence being real.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any

import aiohttp

NEXT_ID = 1


def _id() -> int:
    global NEXT_ID
    NEXT_ID += 1
    return NEXT_ID


async def connect(session: aiohttp.ClientSession, ha_url: str, token: str):
    """An authenticated HA WebSocket, or an exception the caller can report."""
    ws = await session.ws_connect(f"{ha_url.rstrip('/')}/api/websocket", max_msg_size=0)
    greeting = await ws.receive_json()
    if greeting["type"] != "auth_required":
        raise RuntimeError(f"unexpected greeting: {greeting}")
    await ws.send_json({"type": "auth", "access_token": token})
    auth = await ws.receive_json()
    if auth["type"] != "auth_ok":
        raise RuntimeError(f"auth failed ({auth['type']}): {auth.get('message')}")
    return ws


async def call(ws, msg: dict[str, Any]) -> Any:
    """One command; returns its result (any JSON shape), or raises HA's error."""
    msg = {"id": _id(), **msg}
    await ws.send_json(msg)
    while True:
        reply = await ws.receive_json()
        if reply.get("id") != msg["id"] or reply.get("type") == "event":
            continue
        if reply.get("success") is False:
            error = reply.get("error", {})
            raise RuntimeError(f"{error.get('code')}: {error.get('message')}")
        return reply.get("result")


async def devices(args) -> int:
    async with aiohttp.ClientSession() as session:
        ws = await connect(session, args.ha, args.token)
        try:
            for entry in await call(ws, {"type": "tabula/panel_devices"}):
                name = entry.get("name") or entry["device_id"][:8]
                # Column 2 is the HA device id: services and buttons take that.
                state = "online" if entry["connected"] else "offline"
                print(f"{entry['device_id']}\t{entry['ha_device_id']}\t{name}\t{state}")
        finally:
            await ws.close()
    return 0


async def snapshot(args) -> int:
    async with aiohttp.ClientSession() as session:
        ws = await connect(session, args.ha, args.token)
        try:
            result = await call(ws, {"type": "tabula/panel_logs", "device_id": args.device})
        finally:
            await ws.close()
    subscription = result["subscription"]
    print(
        f"connected={result['connected']} lines={len(result['lines'])}/{result['capacity']} "
        f"streaming={subscription['active']} level={subscription['level']} "
        f"expires_at={subscription['expires_at']}"
    )
    for line in result["lines"][-args.tail :]:
        print(f"{line['ts']} {line['level']} {line['tag']}: {line['message']}")
    return 0


async def stream(args) -> int:
    msg: dict[str, Any] = {
        "type": "tabula/panel_logs_subscribe",
        "device_id": args.device,
        "enabled": True,
    }
    if args.level:
        msg["level"] = args.level
    lines = 0
    console = 0
    async with aiohttp.ClientSession() as session:
        ws = await connect(session, args.ha, args.token)
        try:
            state = await call(ws, msg)
            print(f"-- subscribed: level={state['level']} expires_at={state['expires_at']}")
            deadline = asyncio.get_running_loop().time() + args.seconds
            while (remaining := deadline - asyncio.get_running_loop().time()) > 0:
                try:
                    async with asyncio.timeout(remaining):
                        message = await ws.receive_json()
                except TimeoutError:
                    break
                if message.get("type") != "event":
                    continue
                for line in message["event"].get("lines", []):
                    lines += 1
                    console += 1 if line["tag"] == "console" else 0
                    print(f"{line['ts']} {line['level']} {line['tag']}: {line['message']}")
                if "subscription" in message["event"]:
                    print(f"-- subscription: {json.dumps(message['event']['subscription'])}")
        finally:
            # The last viewer leaving stops the device's subscription (P5.4).
            try:
                await call(
                    ws,
                    {
                        "type": "tabula/panel_logs_subscribe",
                        "device_id": args.device,
                        "enabled": False,
                    },
                )
            except Exception as err:
                print(f"-- stop failed: {err}", file=sys.stderr)
            await ws.close()
    print(f"SUMMARY lines={lines} console={console}")
    return 0 if lines else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read the Tabularium's log view.")
    parser.add_argument("--ha", required=True, help="HA base URL, e.g. http://ha:8123")
    parser.add_argument("--token", required=True, help="a long-lived admin access token")
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("devices", help="list paired devices").set_defaults(func=devices)

    snap = commands.add_parser("snapshot", help="the buffered lines and the stream state")
    snap.add_argument("--device", required=True, help="the Tabula device id")
    snap.add_argument("--tail", type=int, default=20, help="lines to print (default 20)")
    snap.set_defaults(func=snapshot)

    follow = commands.add_parser("stream", help="follow the live stream")
    follow.add_argument("--device", required=True, help="the Tabula device id")
    follow.add_argument("--seconds", type=float, default=20.0)
    follow.add_argument("--level", choices=("debug", "info", "warning", "error"))
    follow.set_defaults(func=stream)

    args = parser.parse_args(argv)
    try:
        return asyncio.run(args.func(args))
    except RuntimeError as err:
        print(f"FAIL: {err}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
