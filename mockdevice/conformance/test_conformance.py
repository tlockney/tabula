"""Replay golden fixture frames against a TLP server and check its replies (PROTOCOL.md 2, 4)."""

from __future__ import annotations

import json
import uuid
from typing import Any

import aiohttp
import pytest
from mockdevice.codec import REPO_ROOT, Codec

FIXTURES = REPO_ROOT / "schema" / "fixtures" / "tlp" / "v1"
DEVICE_FRAMES = {"state_delta", "event", "log", "command_result"}
codec = Codec()


def fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def fixtures(frame_type: str, invalid: bool = False) -> list[str]:
    root = FIXTURES / "invalid" if invalid else FIXTURES
    names = [p.stem for p in sorted(root.glob(f"{frame_type}*.json"))]
    names = [n for n in names if n.split(".")[0] == frame_type]
    return [f"invalid/{n}" for n in names] if invalid else names


class Raw:
    """A raw authenticated WebSocket that renumbers fixture frames."""

    def __init__(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        self.ws = ws
        self.next_id = 1

    async def call(self, frame: dict[str, Any]) -> dict[str, Any]:
        frame = {**frame, "id": self.next_id}
        self.next_id += 1
        await self.ws.send_str(json.dumps(frame))
        while True:
            reply = await self.ws.receive_json(timeout=10)
            if reply.get("id") == frame["id"] and reply.get("type") != "event":
                return reply

    async def register(self, device_id: str, **changes: Any) -> dict[str, Any]:
        return await self.call({**fixture("register"), "device_id": device_id, **changes})


async def connect(http: aiohttp.ClientSession, url: str, token: str) -> Raw:
    ws = await http.ws_connect(f"{url}/api/websocket")
    assert (await ws.receive_json(timeout=10))["type"] == "auth_required"
    await ws.send_json({"type": "auth", "access_token": token})
    assert (await ws.receive_json(timeout=10))["type"] == "auth_ok"
    return Raw(ws)


def error_code(reply: dict[str, Any]) -> str | None:
    if reply.get("success") is False:
        codec.validate("error_result", reply, lenient=True)
        return reply["error"]["code"]
    return None


def is_ack(reply: dict[str, Any]) -> bool:
    return not codec.problems("ack", reply, lenient=True)


@pytest.fixture
async def ws(target, http, token) -> Raw:
    raw = await connect(http, target.url, token)
    yield raw
    await raw.ws.close()


@pytest.fixture
async def registered(ws, target) -> Raw:
    reply = await ws.register(target.device_id)
    codec.validate("register_result", reply, lenient=True)
    return ws


async def test_frame_before_register_is_refused(ws):
    assert error_code(await ws.call(fixture("state_delta"))) == "not_registered"


async def test_unsupported_protocol_is_reported_before_validation(ws, target):
    reply = await ws.register(target.device_id, protocol_version=99, unknown_member=True)
    assert error_code(reply) == "unsupported_protocol"


async def test_register_with_another_device_id(ws):
    assert error_code(await ws.register(str(uuid.uuid4()))) == "unknown_device"


async def test_register_result(ws, target):
    sent = fixture("register")
    reply = await ws.register(target.device_id)
    codec.validate("register_result", reply, lenient=True)
    assert reply["result"]["protocol_version"] == 1
    assert set(reply["result"]["capabilities"]) <= set(sent["capabilities"])


async def test_unknown_capability_is_not_accepted(ws, target):
    frame = {**fixture("register.future_capability"), "device_id": target.device_id}
    reply = await ws.call(frame)
    codec.validate("register_result", reply, lenient=True)
    known = set(fixture("register")["capabilities"])
    assert set(reply["result"]["capabilities"]) <= known


async def test_subscribe_once(registered):
    assert is_ack(await registered.call(fixture("subscribe")))
    assert error_code(await registered.call(fixture("subscribe"))) == "invalid_frame"


@pytest.mark.parametrize("name", [n for t in sorted(DEVICE_FRAMES) for n in fixtures(t)], ids=str)
async def test_valid_device_frame_is_acked(registered, name):
    assert is_ack(await registered.call(fixture(name))), name


@pytest.mark.parametrize(
    "name",
    [n for t in sorted(DEVICE_FRAMES | {"register"}) for n in fixtures(t, invalid=True)],
    ids=str,
)
async def test_invalid_device_frame_is_refused(registered, target, name):
    frame = fixture(name)
    if frame.get("type") == "tabula/register" and name != "invalid/register.bad_device_id":
        frame["device_id"] = target.device_id
    assert error_code(await registered.call(frame)) == "invalid_frame", name


async def test_result_for_unknown_command_is_acked(registered):
    frame = {**fixture("command_result"), "command_id": "never-sent-0001"}
    assert is_ack(await registered.call(frame))


async def test_ping(ws):
    reply = await ws.call(fixture("ping"))
    codec.validate("pong", reply, lenient=True)


async def test_newer_registration_supersedes(registered, target, http, token):
    newer = await connect(http, target.url, token)
    try:
        codec.validate("register_result", await newer.register(target.device_id), lenient=True)
        assert error_code(await registered.call(fixture("state_delta"))) == "superseded"
        assert is_ack(await newer.call(fixture("state_delta")))
    finally:
        await newer.ws.close()


# --- pairing views: only requests that create no pending entry ---------------------------


async def http_error(resp: aiohttp.ClientResponse) -> str:
    body = await resp.json(content_type=None)
    codec.validate("http_error", body, lenient=True)
    return body["error"]


async def test_pair_rejects_invalid_request(target, http):
    body = fixture("invalid/http_pair_request.short_code")
    async with http.post(f"{target.url}/api/tabula/pair", json=body) as resp:
        assert (resp.status, await http_error(resp)) == (400, "invalid_request")


async def test_pair_rejects_newer_protocol(target, http):
    body = {**fixture("http_pair_request"), "protocol_version": 99, "future": True}
    async with http.post(f"{target.url}/api/tabula/pair", json=body) as resp:
        assert (resp.status, await http_error(resp)) == (400, "unsupported_protocol")


async def test_claim_without_pending_entry(target, http):
    body = {**fixture("http_claim_request"), "device_id": str(uuid.uuid4())}
    async with http.post(f"{target.url}/api/tabula/pair/claim", json=body) as resp:
        assert (resp.status, await http_error(resp)) == (404, "expired")


async def test_refresh_token_is_exchanged_without_client_id(token):
    assert token
