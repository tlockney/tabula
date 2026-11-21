"""The test server's HA core path (ADR 0017): HA's real compressed entity format over the socket."""

from __future__ import annotations

import aiohttp
import pytest
from mockdevice.testserver import TestServer


class Ws:
    """A raw HA WebSocket client: auth, then numbered commands."""

    def __init__(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        self.ws = ws
        self.next_id = 1

    async def send(self, msg: dict) -> int:
        msg_id = self.next_id
        self.next_id += 1
        await self.ws.send_json({"id": msg_id, **msg})
        return msg_id

    async def recv(self) -> dict:
        return await self.ws.receive_json(timeout=5)


@pytest.fixture
async def ha(server: TestServer, http: aiohttp.ClientSession, store):
    token = await http.post(
        f"{server.url}/auth/token",
        data={"grant_type": "refresh_token", "refresh_token": store.refresh_token},
    )
    access = (await token.json())["access_token"]
    async with http.ws_connect(f"{server.url}/api/websocket") as ws:
        assert (await ws.receive_json())["type"] == "auth_required"
        await ws.send_json({"type": "auth", "access_token": access})
        assert (await ws.receive_json())["type"] == "auth_ok"
        yield Ws(ws)


async def hook(http: aiohttp.ClientSession, server: TestServer, path: str, body: dict) -> None:
    resp = await http.post(f"{server.url}/test/{path}", json=body)
    assert resp.status == 200, await resp.text()


async def test_subscribe_entities_speaks_the_compressed_format(server, http, ha):
    await hook(
        http,
        server,
        "entity",
        {
            "entity_id": "lock.front",
            "state": "locked",
            "attributes": {"friendly_name": "Front", "battery": 80},
        },
    )
    sub = await ha.send(
        {"type": "subscribe_entities", "entity_ids": ["lock.front", "sensor.absent"]}
    )
    assert (await ha.recv()) == {"id": sub, "type": "result", "success": True, "result": None}

    initial = await ha.recv()
    assert initial["id"] == sub and initial["type"] == "event"
    added = initial["event"]["a"]
    assert set(added) == {"lock.front"}, "a missing entity is simply absent, as in HA"
    assert added["lock.front"]["s"] == "locked"
    assert added["lock.front"]["a"] == {"friendly_name": "Front", "battery": 80}
    assert {"c", "lc"} <= set(added["lock.front"])

    # a change carries only what changed: state, last_changed, changed attrs; removed keys under "-"
    await hook(
        http,
        server,
        "entity",
        {"entity_id": "lock.front", "state": "unlocked", "attributes": {"friendly_name": "Front"}},
    )
    diff = (await ha.recv())["event"]["c"]["lock.front"]
    assert diff["+"]["s"] == "unlocked"
    assert "lc" in diff["+"]
    assert "a" not in diff["+"], "unchanged attributes are not resent"
    assert diff["-"] == {"a": ["battery"]}

    # an attribute-only change keeps last_changed and sends last_updated
    await hook(
        http,
        server,
        "entity",
        {
            "entity_id": "lock.front",
            "state": "unlocked",
            "attributes": {"friendly_name": "Front door"},
        },
    )
    plus = (await ha.recv())["event"]["c"]["lock.front"]["+"]
    assert "s" not in plus and "lc" not in plus and "lu" in plus
    assert plus["a"] == {"friendly_name": "Front door"}

    # an entity appearing later is an "a"; removal is an "r"
    await hook(http, server, "entity", {"entity_id": "sensor.absent", "state": "3"})
    assert set((await ha.recv())["event"]["a"]) == {"sensor.absent"}
    await hook(http, server, "entity", {"entity_id": "lock.front", "remove": True})
    assert (await ha.recv())["event"] == {"r": ["lock.front"]}

    # entities outside the list are never sent
    await hook(http, server, "entity", {"entity_id": "light.other", "state": "on"})
    unsub = await ha.send({"type": "unsubscribe_events", "subscription": sub})
    reply = await ha.recv()
    assert reply["id"] == unsub and reply["success"] is True
    assert server.ha.report() == []


async def test_an_empty_entity_list_is_refused(server, ha):
    msg_id = await ha.send({"type": "subscribe_entities", "entity_ids": []})
    reply = await ha.recv()
    assert reply["id"] == msg_id and reply["success"] is False
    assert server.ha.report() == []


async def test_forecast_subscription_and_its_refusals(server, http, ha):
    await hook(http, server, "entity", {"entity_id": "weather.home", "state": "sunny"})
    missing = await ha.send(
        {
            "type": "weather/subscribe_forecast",
            "entity_id": "weather.nope",
            "forecast_type": "daily",
        }
    )
    assert (await ha.recv()) | {"id": missing} == {
        "id": missing,
        "type": "result",
        "success": False,
        "error": {"code": "invalid_entity_id", "message": "Weather entity not found: weather.nope"},
    }
    await ha.send(
        {
            "type": "weather/subscribe_forecast",
            "entity_id": "weather.home",
            "forecast_type": "hourly",
        }
    )
    assert (await ha.recv())["error"]["code"] == "forecast_not_supported"

    days = [
        {"datetime": "2026-11-22T00:00:00Z", "condition": "rainy", "temperature": 12, "templow": 6}
    ]
    await hook(
        http,
        server,
        "forecast",
        {"entity_id": "weather.home", "forecast_type": "daily", "forecast": days},
    )
    sub = await ha.send(
        {
            "type": "weather/subscribe_forecast",
            "entity_id": "weather.home",
            "forecast_type": "daily",
        }
    )
    assert (await ha.recv())["success"] is True
    assert (await ha.recv())["event"] == {"type": "daily", "forecast": days}
    later = [*days, {"datetime": "2026-11-23T00:00:00Z", "condition": "sunny", "temperature": 14}]
    await hook(
        http,
        server,
        "forecast",
        {"entity_id": "weather.home", "forecast_type": "daily", "forecast": later},
    )
    event = await ha.recv()
    assert event["id"] == sub and event["event"]["forecast"] == later


async def test_display_data_commands(server, http, ha):
    await hook(
        http,
        server,
        "display",
        {
            "registry": [{"ei": "sensor.temp", "pl": "demo", "dp": 1}],
            "translations": {
                "component.lock.entity_component._.state.locked": "Locked",
                "component.demo.entity.sensor.temp.state.x": "X",
            },
            "icons": {"entity_component": {"lock": {"_": {"default": "mdi:lock"}}, "light": {}}},
        },
    )
    reg = await ha.send({"type": "config/entity_registry/list_for_display"})
    reply = await ha.recv()
    assert reply["id"] == reg and reply["result"]["entities"] == [
        {"ei": "sensor.temp", "pl": "demo", "dp": 1}
    ]

    await ha.send(
        {
            "type": "frontend/get_translations",
            "language": "en",
            "category": "entity_component",
            "integration": ["lock"],
        }
    )
    assert (await ha.recv())["result"]["resources"] == {
        "component.lock.entity_component._.state.locked": "Locked"
    }

    await ha.send(
        {"type": "frontend/get_icons", "category": "entity_component", "integration": ["lock"]}
    )
    assert (await ha.recv())["result"]["resources"] == {"lock": {"_": {"default": "mdi:lock"}}}


async def test_drop_hook_closes_the_connection_and_its_subscriptions(server, http, store, start):
    await start(store)
    await server.wait_registered(store.device_id)
    await hook(http, server, "drop", {"device_id": store.device_id})
    await server.wait_registered(store.device_id, connections=2)
    report = await (await http.get(f"{server.url}/test/report")).json()
    assert report["devices"][store.device_id]["ha_subscriptions"] == []
    missing = await http.post(f"{server.url}/test/drop", json={"device_id": "nobody"})
    assert missing.status == 409
