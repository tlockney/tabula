"""Print HA events of one type as they fire, one JSON object per line.

Hardware checks (docs/DEVICE.md) run this in the background to record the
events a tablet sends, e.g. `tabula_screensaver` (packet P2.4). Any user's
long-lived token may subscribe. Run through the repo's uv environment:

    uv run python tools/device/ha_events.py --ha http://ha:8123 --token <token> \
        --event tabula_screensaver --seconds 600

Exits non-zero if authentication or the subscription fails, so a caller can
gate on the evidence being real.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import sys

import aiohttp


async def run(ha_url: str, token: str, event_type: str, seconds: float) -> int:
    async with (
        aiohttp.ClientSession() as session,
        session.ws_connect(f"{ha_url.rstrip('/')}/api/websocket") as ws,
    ):
        greeting = await ws.receive_json()
        if greeting["type"] != "auth_required":
            print(f"unexpected greeting: {greeting}", file=sys.stderr)
            return 1
        await ws.send_json({"type": "auth", "access_token": token})
        auth = await ws.receive_json()
        if auth["type"] != "auth_ok":
            print(f"auth failed ({auth['type']})", file=sys.stderr)
            return 1
        await ws.send_json({"id": 1, "type": "subscribe_events", "event_type": event_type})
        reply = await ws.receive_json()
        if not reply.get("success"):
            print(f"subscribe_events failed: {reply}", file=sys.stderr)
            return 1
        print(f"listening for {event_type} for {seconds:.0f}s", file=sys.stderr, flush=True)

        async def listen() -> None:
            while True:
                message = await ws.receive_json()
                if message.get("type") == "event":
                    event = message["event"]
                    line = {"time_fired": event.get("time_fired"), "data": event.get("data")}
                    print(json.dumps(line), flush=True)

        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(listen(), timeout=seconds)
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ha", required=True, help="Home Assistant base URL")
    parser.add_argument("--token", required=True, help="long-lived access token")
    parser.add_argument("--event", required=True, help="event type, e.g. tabula_screensaver")
    parser.add_argument("--seconds", type=float, default=600, help="how long to listen")
    args = parser.parse_args()
    return asyncio.run(run(args.ha, args.token, args.event, args.seconds))


if __name__ == "__main__":
    sys.exit(main())
