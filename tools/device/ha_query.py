"""Query HA over its WebSocket API for the Tabula device registry entries.

Hardware checks (docs/DEVICE.md) use this to see what HA knows about paired
tablets. The token must belong to an ADMIN user (HA's owner, typically), because
`config/device_registry/list` is an admin-only WebSocket command. Run through
the repo's uv environment:

    uv run python tools/device/ha_query.py --ha http://ha:8123 --token <admin-token> [device_id]

Prints every device registry entry for the tabula integration and the current
state of its entities. Exits non-zero if any HA command fails, so a caller can
gate on the evidence being real, not merely printed.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any

import aiohttp


async def run(ha_url: str, token: str, device_id: str | None) -> int:
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
            print(
                f"auth failed ({auth['type']}); is the token valid and from an admin user?",
                file=sys.stderr,
            )
            return 1

        next_id = 1

        async def command(type_: str, **extra: Any) -> list[Any] | None:
            """Run one command; None (not []) on failure so the caller must notice."""
            nonlocal next_id
            msg = {"id": next_id, "type": type_, **extra}
            next_id += 1
            await ws.send_json(msg)
            reply = await ws.receive_json()
            if not reply.get("success"):
                print(f"{type_} failed: {reply}", file=sys.stderr)
                return None
            return reply.get("result") or []

        devices: list[dict[str, Any]] | None = await command("config/device_registry/list")
        entities = await command("config/entity_registry/list")
        states = await command("get_states")
        if devices is None or entities is None or states is None:
            print("an HA command failed; see stderr above", file=sys.stderr)
            return 1

        tabula_devices = [
            d
            for d in devices
            if any("tabula" in str(id_) for id_ in d.get("config_entries", []))
            or any("tabula" in str(id_) for id_ in d.get("identifiers", []))
            or (device_id and device_id in str(d))
        ]
        print(f"tabula devices in registry: {len(tabula_devices)}")
        for device in tabula_devices:
            print(json.dumps(device, indent=2, default=str))

        entry_ids = {
            entry for device in tabula_devices for entry in device.get("config_entries", [])
        }
        device_entities = [
            entity for entity in entities if entity.get("config_entry_id") in entry_ids
        ]
        if not device_entities:
            print("entities: none registered for these devices")
        state_by_id = {s["entity_id"]: s for s in states}
        for entity in device_entities:
            state = state_by_id.get(entity["entity_id"], {})
            print(
                f"{entity['entity_id']}: state={state.get('state', 'unknown')} "
                f"last_changed={state.get('last_changed')}"
            )
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ha", required=True, help="Home Assistant base URL")
    parser.add_argument(
        "--token",
        required=True,
        help="long-lived access token from an admin user (the owner's; "
        "the device-registry list command is admin-only)",
    )
    parser.add_argument("device_id", nargs="?", help="match only this tabula device id")
    args = parser.parse_args()
    return asyncio.run(run(args.ha, args.token, args.device_id))


if __name__ == "__main__":
    sys.exit(main())
