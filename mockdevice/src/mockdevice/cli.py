"""Command line: `tabula-mockdevice pair` and `tabula-mockdevice run`."""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path

import aiohttp

from mockdevice.client import ProtocolRejected, TLPClient
from mockdevice.codec import Codec
from mockdevice.device import ALL_CAPABILITIES, Behavior, MockDevice
from mockdevice.pairing import CredentialRevoked, PairingError, claim, request_pairing
from mockdevice.store import DeviceStore

DEFAULT_STORE = Path.home() / ".config" / "tabula-mockdevice" / "device.json"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="tabula-mockdevice", description="Python reference device for TLP v1."
    )
    parser.add_argument("--ha", required=True, help="Home Assistant base URL, e.g. http://ha:8123")
    parser.add_argument(
        "--store", type=Path, default=DEFAULT_STORE, help=f"device file (default {DEFAULT_STORE})"
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="cmd", required=True)

    pair = sub.add_parser("pair", help="pair with Home Assistant and store the credential")
    pair.add_argument("--name", default="Mock device")
    pair.add_argument("--model", default="mockdevice")
    pair.add_argument("--timeout", type=float, default=600.0, help="seconds to wait (default 600)")

    run = sub.add_parser("run", help="connect and serve commands until interrupted")
    run.add_argument(
        "--capabilities",
        default=",".join(ALL_CAPABILITIES),
        help="comma-separated capabilities to declare",
    )
    run.add_argument("--fail", default="", help="comma-separated commands that NACK with 'failed'")
    run.add_argument("--drop", default="", help="comma-separated commands that never answer")
    run.add_argument("--delay", type=float, default=0.0, help="seconds before answering a command")
    return parser


def _split(value: str) -> set[str]:
    return {v.strip() for v in value.split(",") if v.strip()}


async def do_pair(args: argparse.Namespace) -> int:
    store = DeviceStore.load(args.store)
    codec = Codec()
    ha = args.ha.rstrip("/")
    async with aiohttp.ClientSession() as session:
        attempt, response = await request_pairing(
            session, codec, ha, store.device_id, args.name, args.model
        )
        print(f"Pairing code: {attempt.code}")
        print("Confirm it in Home Assistant (Settings > Devices & services).")
        result = await claim(
            session, codec, ha, attempt, response["poll_interval"], timeout=args.timeout
        )
    store.refresh_token = result["credential"]["refresh_token"]
    store.ha_device_id = result.get("ha_device_id")
    store.save()
    print(f"Paired. Device {store.device_id}, credential saved to {args.store}")
    return 0


async def do_run(args: argparse.Namespace) -> int:
    store = DeviceStore.load(args.store)
    if not store.paired:
        print("Not paired. Run `tabula-mockdevice pair` first.", file=sys.stderr)
        return 2
    device = MockDevice(
        capabilities=sorted(_split(args.capabilities)),
        behavior=Behavior(fail=_split(args.fail), drop=_split(args.drop), delay=args.delay),
        settings_revision=store.settings_revision,
        base_url=args.ha,
    )
    device.settings = store.settings
    client = TLPClient(
        args.ha,
        store,
        device,
        on_registered=lambda r: print(f"Registered as {r['result']['ha_device_id']}"),
    )
    async with aiohttp.ClientSession() as session:
        await client.run(session)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO)
    handler = do_pair if args.cmd == "pair" else do_run
    try:
        return asyncio.run(handler(args))
    except KeyboardInterrupt:
        return 130
    except PairingError as e:
        print(f"Pairing failed: {e}", file=sys.stderr)
        return 1
    except CredentialRevoked:
        print("Credential revoked. Pair again.", file=sys.stderr)
        return 1
    except ProtocolRejected as e:
        print(f"Home Assistant refused the device: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
