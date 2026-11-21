"""The slice of HA core's WebSocket API the device's screensaver data uses (ADR 0017).

A test double for `subscribe_entities`, `unsubscribe_events`, `weather/subscribe_forecast`,
`config/entity_registry/list_for_display`, `frontend/get_translations` and
`frontend/get_icons`. Event payloads follow HA core's own formats (websocket_api and weather
components, Apache-2.0): `subscribe_entities` sends the compressed form, an initial `a`
snapshot of the subscribed entities that exist, then `a` (added), `c` (changed: `+` holds the
new state, last_changed and only the added or changed attributes, `-` the removed attribute
keys) and `r` (removed). Anything simpler would let a client pass these tests and fail on HA.
"""

from __future__ import annotations

import contextlib
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

Frame = dict[str, Any]
Send = Callable[[Frame], Awaitable[None]]

COMMANDS = {
    "subscribe_entities",
    "unsubscribe_events",
    "weather/subscribe_forecast",
    "config/entity_registry/list_for_display",
    "frontend/get_translations",
    "frontend/get_icons",
}
FORECAST_TYPES = {"daily", "hourly", "twice_daily"}


@dataclass
class EntityRecord:
    state: str
    attributes: dict[str, Any]
    last_changed: float
    last_updated: float
    context_id: str


@dataclass
class Subscription:
    owner: object  # the WebSocket session
    msg_id: int
    send: Send
    kind: str
    entity_ids: list[str] = field(default_factory=list)
    forecast: tuple[str, str] | None = None

    def describe(self) -> dict[str, Any]:
        out: dict[str, Any] = {"id": self.msg_id, "type": self.kind}
        if self.kind == "subscribe_entities":
            out["entity_ids"] = list(self.entity_ids)
        elif self.forecast:
            out["entity_id"], out["forecast_type"] = self.forecast
        return out


def _compressed(record: EntityRecord) -> Frame:
    out: Frame = {
        "s": record.state,
        "a": dict(record.attributes),
        "c": record.context_id,
        "lc": record.last_changed,
    }
    if record.last_updated != record.last_changed:
        out["lu"] = record.last_updated
    return out


def _diff(old: EntityRecord, new: EntityRecord) -> Frame:
    plus: Frame = {}
    if old.state != new.state:
        plus["s"] = new.state
    if old.last_changed != new.last_changed:
        plus["lc"] = new.last_changed
    elif old.last_updated != new.last_updated:
        plus["lu"] = new.last_updated
    if old.context_id != new.context_id:
        plus["c"] = new.context_id
    changed = {
        key: value
        for key, value in new.attributes.items()
        if key not in old.attributes or old.attributes[key] != value
    }
    if changed:
        plus["a"] = changed
    diff: Frame = {"+": plus}
    if removed := [key for key in old.attributes if key not in new.attributes]:
        diff["-"] = {"a": removed}
    return diff


class HaCore:
    """States, forecasts and display data the tests set; subscriptions per connection."""

    def __init__(self) -> None:
        self.entities: dict[str, EntityRecord] = {}
        self.forecasts: dict[tuple[str, str], list[Frame]] = {}
        self.registry: list[Frame] = []
        self.translations: dict[str, str] = {}
        self.icons: dict[str, dict[str, Any]] = {"entity_component": {}, "entity": {}}
        self.subscriptions: list[Subscription] = []
        self.commands: list[Frame] = []

    # --- test controls --------------------------------------------------------

    async def set_state(
        self, entity_id: str, state: str, attributes: dict[str, Any] | None = None
    ) -> None:
        """Create or update an entity and push the change to subscribers, as HA does."""
        now = time.time()
        old = self.entities.get(entity_id)
        attrs = dict(attributes if attributes is not None else (old.attributes if old else {}))
        if old is not None and old.state == state and old.attributes == attrs:
            return  # HA fires no state_changed for an identical write
        new = EntityRecord(
            state=state,
            attributes=attrs,
            last_changed=now if old is None or old.state != state else old.last_changed,
            last_updated=now,
            context_id=uuid.uuid4().hex,
        )
        self.entities[entity_id] = new
        if old is None:
            await self._push_entities(entity_id, {"a": {entity_id: _compressed(new)}})
        else:
            await self._push_entities(entity_id, {"c": {entity_id: _diff(old, new)}})

    async def remove(self, entity_id: str) -> None:
        if self.entities.pop(entity_id, None) is not None:
            await self._push_entities(entity_id, {"r": [entity_id]})

    async def set_forecast(self, entity_id: str, forecast_type: str, forecast: list[Frame]) -> None:
        self.forecasts[(entity_id, forecast_type)] = forecast
        for sub in list(self.subscriptions):
            if sub.forecast == (entity_id, forecast_type):
                await _deliver(
                    sub, self._event(sub.msg_id, {"type": forecast_type, "forecast": forecast})
                )

    def drop_owner(self, owner: object) -> None:
        """A connection closed: its subscriptions go with it."""
        self.subscriptions = [s for s in self.subscriptions if s.owner is not owner]

    def report(self, owner: object | None = None) -> list[dict[str, Any]]:
        return [s.describe() for s in self.subscriptions if owner is None or s.owner is owner]

    # --- commands ---------------------------------------------------------------

    async def handle(self, owner: object, frame: Frame, send: Send) -> None:
        """Answer one HA core command; subscriptions send their initial event after the result."""
        self.commands.append(frame)
        msg_id = frame["id"]
        kind = frame["type"]
        if kind == "subscribe_entities":
            ids = frame.get("entity_ids")
            if not isinstance(ids, list) or not ids:
                # HA would subscribe to every entity; the device must never ask for that
                await send(_error(msg_id, "invalid_format", "entity_ids must be a non-empty list"))
                return
            sub = Subscription(owner, msg_id, send, kind, entity_ids=[str(i) for i in ids])
            self.subscriptions.append(sub)
            await send(_result(msg_id, None))
            snapshot = {
                eid: _compressed(self.entities[eid])
                for eid in sub.entity_ids
                if eid in self.entities
            }
            await send(self._event(msg_id, {"a": snapshot}))
        elif kind == "unsubscribe_events":
            target = frame.get("subscription")
            match = [s for s in self.subscriptions if s.owner is owner and s.msg_id == target]
            if not match:
                await send(_error(msg_id, "not_found", "Subscription not found."))
                return
            self.subscriptions.remove(match[0])
            await send(_result(msg_id, None))
        elif kind == "weather/subscribe_forecast":
            entity_id = frame.get("entity_id")
            forecast_type = frame.get("forecast_type")
            if forecast_type not in FORECAST_TYPES:
                await send(_error(msg_id, "invalid_format", "unknown forecast_type"))
                return
            if not isinstance(entity_id, str) or entity_id not in self.entities:
                await send(
                    _error(msg_id, "invalid_entity_id", f"Weather entity not found: {entity_id}")
                )
                return
            key = (entity_id, forecast_type)
            if key not in self.forecasts:
                await send(
                    _error(
                        msg_id,
                        "forecast_not_supported",
                        "The weather entity does not support forecast type",
                    )
                )
                return
            sub = Subscription(owner, msg_id, send, kind, forecast=key)
            self.subscriptions.append(sub)
            await send(_result(msg_id, None))
            await send(
                self._event(msg_id, {"type": forecast_type, "forecast": self.forecasts[key]})
            )
        elif kind == "config/entity_registry/list_for_display":
            await send(
                _result(
                    msg_id,
                    {
                        "entity_categories": {"0": "config", "1": "diagnostic"},
                        "entities": self.registry,
                    },
                )
            )
        elif kind == "frontend/get_translations":
            category = frame.get("category")
            wanted = _integrations(frame.get("integration"))
            resources = {
                key: text
                for key, text in self.translations.items()
                if _translation_matches(key, category, wanted)
            }
            await send(_result(msg_id, {"resources": resources}))
        elif kind == "frontend/get_icons":
            category = frame.get("category")
            wanted = _integrations(frame.get("integration"))
            pool = self.icons.get(str(category), {})
            resources = {k: v for k, v in pool.items() if wanted is None or k in wanted}
            await send(_result(msg_id, {"resources": resources}))

    async def _push_entities(self, entity_id: str, payload: Frame) -> None:
        for sub in list(self.subscriptions):
            if sub.kind == "subscribe_entities" and entity_id in sub.entity_ids:
                await _deliver(sub, self._event(sub.msg_id, payload))

    @staticmethod
    def _event(msg_id: int, event: Frame) -> Frame:
        return {"id": msg_id, "type": "event", "event": event}


async def _deliver(sub: Subscription, frame: Frame) -> None:
    # a connection closing under a push is the drop the test is about; not an error here
    with contextlib.suppress(ConnectionError, RuntimeError):
        await sub.send(frame)


def _result(msg_id: int, result: Any) -> Frame:
    return {"id": msg_id, "type": "result", "success": True, "result": result}


def _error(msg_id: int, code: str, message: str) -> Frame:
    return {
        "id": msg_id,
        "type": "result",
        "success": False,
        "error": {"code": code, "message": message},
    }


def _integrations(value: Any) -> set[str] | None:
    if value is None:
        return None
    if isinstance(value, str):
        return {value}
    return {str(v) for v in value}


def _translation_matches(key: str, category: Any, wanted: set[str] | None) -> bool:
    # component.<integration>.<category>.…
    parts = key.split(".")
    if len(parts) < 3 or parts[0] != "component" or parts[2] != category:
        return False
    return wanted is None or parts[1] in wanted
