"""Pairing and claim behavior on the real HTTP surface (PROTOCOL.md 2.2-2.4).

Covers the packet's Done-when items: happy path, wrong code, wrong nonce,
credential released once, rate limit and pending cap, pending expiry, and
unknown device ids rejected with no side effects.
"""

from __future__ import annotations

import asyncio
import json

import aiohttp
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN, PAIR_PENDING_CAP, PAIR_RATE_LIMIT
from custom_components.tabula.pairing import RateLimiter
from helpers import (
    Paired,
    confirm_code,
    frame_fixture,
    pair_device,
    post_claim,
    post_pair,
    uid,
    wait_for_flow,
    ws_connect,
)
from homeassistant.helpers import device_registry as dr
from mockdevice.pairing import new_nonce, nonce_hash

DEVICE = uid()


async def pair_body(device_id: str, *, nonce: str | None = None, version: int = 1) -> dict:
    """A minimal valid http_pair_request body."""
    nonce = nonce or new_nonce()
    return {
        "device_id": device_id,
        "name": "Office Kiosk",
        "model": "KFTRPWI",
        "protocol_version": version,
        "code": "123456",
        "nonce_hash": nonce_hash(nonce),
    }


async def post(session: aiohttp.ClientSession, url: str, body: dict) -> aiohttp.ClientResponse:
    return await session.post(f"{url}/api/tabula/pair", json=body)


async def post_claim_raw(
    session: aiohttp.ClientSession, url: str, device_id: str, nonce: str
) -> aiohttp.ClientResponse:
    return await session.post(
        f"{url}/api/tabula/pair/claim", json={"device_id": device_id, "nonce": nonce}
    )


# --- happy path ----------------------------------------------------------------------


async def test_pair_happy_path(hass_setup, server, hub, session):
    """Pair + confirm + claim leaves one device, one user, one HA device."""
    paired = await pair_device(hass_setup, server, DEVICE)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[DEVICE]
    assert record.user_id
    assert record.ha_device_id == paired.ha_device_id

    registry = dr.async_get(hass_setup)
    device = registry.async_get(paired.ha_device_id)
    assert device is not None
    assert (DOMAIN, DEVICE) in device.identifiers
    assert paired.refresh_token

    user = await hass_setup.auth.async_get_user(record.user_id)
    assert user is not None
    assert user.name == f"Tabula {DEVICE[:8]}"
    assert not user.is_admin


# --- pair view statuses (PROTOCOL.md 2.2) --------------------------------------------


async def test_pair_retry_same_nonce(hass_setup, server, hub, session):
    """Re-posting the same pair request is a retry: accepted, no second flow."""
    body = await pair_body(DEVICE)
    first = await post(session, server, body)
    assert first.status == 202
    second = await post(session, server, body)
    assert second.status == 202
    flows = [
        f
        for f in hass_setup.config_entries.flow.async_progress_by_handler("tabula")
        if f["context"].get("device_id") == DEVICE
    ]
    assert len(flows) == 1


async def test_pair_conflict_different_nonce(hass_setup, server, hub, session):
    """A different pairing attempt for the same pending device: 409 already_pending."""
    await post(session, server, await pair_body(DEVICE, nonce="aaa"))
    response = await post(session, server, await pair_body(DEVICE, nonce="bbb"))
    assert response.status == 409
    assert (await response.json())["error"] == "already_pending"


async def test_pair_pending_cap(hass_setup, server, hub, session):
    """PAIR_PENDING_CAP devices may pair at once; the next gets 503 too_many_pending."""
    for _ in range(PAIR_PENDING_CAP):
        response = await post(session, server, await pair_body(uid()))
        assert response.status == 202
    response = await post(session, server, await pair_body(uid()))
    assert response.status == 503
    assert (await response.json())["error"] == "too_many_pending"


async def test_pair_rate_limited(hass_setup, server, hub, session):
    """More than PAIR_RATE_LIMIT pair posts from one IP inside the window: 429."""
    body = await pair_body(uid())
    for _ in range(PAIR_RATE_LIMIT):
        assert (await post(session, server, body)).status == 202
    response = await post(session, server, body)
    assert response.status == 429
    body_json = await response.json()
    assert body_json["error"] == "rate_limited"
    assert body_json["retry_after"] >= 1


async def test_pair_unsupported_protocol(hass_setup, server, hub, session):
    """A newer protocol major on the pair request: 400 unsupported_protocol."""
    response = await post(session, server, await pair_body(uid(), version=2))
    assert response.status == 400
    assert (await response.json())["error"] == "unsupported_protocol"


async def test_pair_invalid_body(hass_setup, server, hub, session):
    """Bad JSON, oversize bodies and schema failures: 400 invalid_request."""
    raw = await session.post(
        f"{server}/api/tabula/pair", data=b"{not json", headers={"Content-Type": "application/json"}
    )
    assert raw.status == 400
    assert (await raw.json())["error"] == "invalid_request"

    body = await pair_body(uid())
    body["name"] = "x" * 5000
    big = await post(session, server, body)
    assert big.status == 400

    short = await post(session, server, {**await pair_body(uid()), "code": "123"})
    assert short.status == 400
    assert (await short.json())["error"] == "invalid_request"


async def test_pair_chunked_body(hass_setup, server, hub, session):
    """A body that arrives in several chunks is read in full, not truncated."""
    body = json.dumps(await pair_body(DEVICE)).encode()

    async def slow_chunks():
        yield body[:120]
        await asyncio.sleep(0.05)  # two arrival waves: read(n) alone would truncate
        yield body[120:]

    response = await session.post(
        f"{server}/api/tabula/pair",
        data=slow_chunks(),
        headers={"Content-Type": "application/json"},
    )
    assert response.status == 202
    assert (await response.json())["status"] == "pending"
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.pending.get(DEVICE) is not None


async def test_pair_view_404_without_hub_entry(hass_setup, server, session):
    """The views exist with the component set up, but no entry: plain 404."""
    body = await pair_body(uid())
    response = await post(session, server, body)
    assert response.status == 404


async def test_rate_limiter_unit():
    """The sliding window releases exactly `limit` requests per window."""
    clock = {"now": 0.0}
    limiter = RateLimiter(2, 60.0, clock=lambda: clock["now"])
    assert limiter.check("ip") is None
    assert limiter.check("ip") is None
    wait = limiter.check("ip")
    assert wait is not None and 0 < wait <= 60.0
    clock["now"] += 61.0
    assert limiter.check("ip") is None
    # Other keys are unaffected.
    assert limiter.check("other-ip") is None


# --- discovery flow and the code (PROTOCOL.md 2.2, ADR 0002) ------------------------


async def test_wrong_code_rejected_then_correct(hass_setup, server, hub, session):
    """A wrong code burns one attempt; the right one still pairs afterwards."""
    attempt, response = await post_pair(session, server, DEVICE)
    flow_id = await wait_for_flow(hass_setup, DEVICE)

    wrong = await hass_setup.config_entries.flow.async_configure(flow_id, {"code": "000000"})
    await hass_setup.async_block_till_done()
    assert wrong["type"] == "form"
    assert wrong["errors"] == {"code": "invalid_code"}

    right = await confirm_code(hass_setup, DEVICE, attempt.code)
    assert right["type"] == "abort"
    assert right["reason"] == "paired"
    claimed = await post_claim(session, server, attempt, response)
    assert claimed["status"] == "paired"


async def test_wrong_code_of_any_shape_burns_an_attempt(hass_setup, server, hub, session):
    """Codes are compared as strings after strip; junk still costs an attempt."""
    attempt, _ = await post_pair(session, server, DEVICE)
    flow_id = await wait_for_flow(hass_setup, DEVICE)
    junk = await hass_setup.config_entries.flow.async_configure(flow_id, {"code": "  abc  "})
    await hass_setup.async_block_till_done()
    assert junk["type"] == "form"
    assert junk["errors"] == {"code": "invalid_code"}
    right = await confirm_code(hass_setup, DEVICE, attempt.code)
    assert right["reason"] == "paired"


async def test_non_ascii_code_rejected_cleanly(hass_setup, server, hub, session):
    """Full-width digits from an IME get a form error, not a TypeError/500."""
    attempt, _ = await post_pair(session, server, DEVICE)
    flow_id = await wait_for_flow(hass_setup, DEVICE)
    # Full-width digits are the point of this test (some IMEs emit them); the
    # ambiguity ruff flags is exactly what the flow must reject cleanly.
    wide = await hass_setup.config_entries.flow.async_configure(
        flow_id,
        {"code": "１２３４５６"},  # noqa: RUF001 - intentional full-width digits
    )
    await hass_setup.async_block_till_done()
    assert wide["type"] == "form"
    assert wide["errors"] == {"code": "invalid_code"}
    # It burned one attempt, but the real code still pairs.
    right = await confirm_code(hass_setup, DEVICE, attempt.code)
    assert right["reason"] == "paired"


async def test_too_many_wrong_codes(hass_setup, server, hub, session):
    """Five wrong codes burn the pending entry; claim then says expired."""
    attempt, _ = await post_pair(session, server, DEVICE)
    flow_id = await wait_for_flow(hass_setup, DEVICE)
    result = await hass_setup.config_entries.flow.async_configure(flow_id, {"code": "999999"})
    for _ in range(4):
        result = await hass_setup.config_entries.flow.async_configure(flow_id, {"code": "999999"})
    await hass_setup.async_block_till_done()
    assert result["type"] == "abort"
    assert result["reason"] == "too_many_attempts"
    response = await post_claim_raw(session, server, DEVICE, attempt.nonce)
    assert response.status == 404
    assert (await response.json())["error"] == "expired"


async def test_expired_pending_claim(hass_setup, server, hub, session):
    """A pending entry past its TTL answers expired, not pending."""
    attempt, _ = await post_pair(session, server, DEVICE)
    await wait_for_flow(hass_setup, DEVICE)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    hub_runtime.pending._entries[DEVICE].expires_at -= 601.0  # pretend it is stale
    response = await post_claim_raw(session, server, DEVICE, attempt.nonce)
    assert response.status == 404
    assert (await response.json())["error"] == "expired"


async def test_expired_flow_aborts(hass_setup, server, hub, session):
    """Confirming a code after the attempt was replaced aborts with expired."""
    attempt, _ = await post_pair(session, server, DEVICE)
    flow_id = await wait_for_flow(hass_setup, DEVICE)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    hub_runtime.pending.delete(DEVICE)
    result = await hass_setup.config_entries.flow.async_configure(flow_id, {"code": attempt.code})
    await hass_setup.async_block_till_done()
    assert result["type"] == "abort"
    assert result["reason"] == "expired"


async def test_repair_after_expiry_creates_a_fresh_flow(hass_setup, server, hub, session):
    """Re-pairing after the TTL aborts the stale flow instead of blocking."""
    stale_attempt, _ = await post_pair(session, server, DEVICE)
    stale_flow = await wait_for_flow(hass_setup, DEVICE)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    hub_runtime.pending._entries[DEVICE].expires_at -= 601.0  # pretend it is stale

    attempt, response = await post_pair(session, server, DEVICE)
    assert attempt.nonce != stale_attempt.nonce
    flows = [
        f
        for f in hass_setup.config_entries.flow.async_progress_by_handler(DOMAIN)
        if f["context"].get("device_id") == DEVICE
    ]
    assert len(flows) == 1
    assert flows[0]["flow_id"] != stale_flow

    result = await confirm_code(hass_setup, DEVICE, attempt.code)
    assert result["type"] == "abort"
    assert result["reason"] == "paired"
    claimed = await post_claim(session, server, attempt, response)
    assert claimed["status"] == "paired"


# --- claim view (PROTOCOL.md 2.3) ----------------------------------------------------


async def test_claim_before_confirm_is_pending(hass_setup, server, hub, session):
    """Claiming before the user confirms gets 202 status=pending."""
    attempt, response = await post_pair(session, server, DEVICE)
    await wait_for_flow(hass_setup, DEVICE)
    reply = await post_claim_raw(session, server, DEVICE, attempt.nonce)
    assert reply.status == 202
    assert (await reply.json()) == {"status": "pending"}
    assert response["poll_interval"] >= 1


async def test_claim_wrong_nonce(hass_setup, server, hub, session):
    """Claiming with the wrong nonce: 403 bad_nonce, credential stays unclaimed."""
    attempt, _ = await post_pair(session, server, DEVICE)
    await confirm_code(hass_setup, DEVICE, attempt.code)
    response = await post_claim_raw(session, server, DEVICE, new_nonce())
    assert response.status == 403
    assert (await response.json())["error"] == "bad_nonce"
    # The real nonce still claims.
    ok = await post_claim_raw(session, server, DEVICE, attempt.nonce)
    assert ok.status == 200


async def test_credential_released_exactly_once(hass_setup, server, hub, session):
    """The second claim of the same attempt is told to re-pair."""
    attempt, _ = await post_pair(session, server, DEVICE)
    await confirm_code(hass_setup, DEVICE, attempt.code)
    first = await post_claim_raw(session, server, DEVICE, attempt.nonce)
    assert first.status == 200
    token = (await first.json())["credential"]["refresh_token"]
    second = await post_claim_raw(session, server, DEVICE, attempt.nonce)
    assert second.status == 404
    assert (await second.json())["error"] == "expired"
    assert token


# --- confirmed but never claimed (ADR 0001 item 4) ----------------------------------


async def test_confirmed_but_unclaimed_is_revoked_on_expiry(hass_setup, server, hub, session):
    """A confirmed attempt that never claims leaves no live credential behind."""
    attempt, _ = await post_pair(session, server, DEVICE)
    result = await confirm_code(hass_setup, DEVICE, attempt.code)
    assert result["reason"] == "paired"
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[DEVICE]
    user_id = record.user_id
    ha_device_id = record.ha_device_id
    assert await hass_setup.auth.async_get_user(user_id) is not None

    # The tablet never claims; the entry expires and the TTL sweep drops it.
    hub_runtime.pending._entries[DEVICE].expires_at -= 601.0  # pretend it is stale
    assert hub_runtime.pending.get(DEVICE) is None
    await hass_setup.async_block_till_done()

    assert DEVICE not in hub_runtime.devices
    assert await hass_setup.auth.async_get_user(user_id) is None
    assert dr.async_get(hass_setup).async_get(ha_device_id) is None
    assert hub_runtime.entry.data.get("devices", {}) == {}


# --- unknown device ids: no side effects (packet Done-when) -------------------------


async def test_claim_unknown_device_no_side_effects(hass_setup, server, hub, session):
    """An unknown device_id's claim is terminal, and nothing is created."""
    before = len(await hass_setup.auth.async_get_users())
    response = await post_claim_raw(session, server, uid(), new_nonce())
    assert response.status == 404
    assert (await response.json())["error"] == "expired"
    assert len(await hass_setup.auth.async_get_users()) == before
    assert not hass_setup.config_entries.flow.async_progress_by_handler("tabula")


async def test_register_unknown_device_id_no_side_effects(
    hass_setup, server, hub, session, paired: Paired
):
    """A register frame for a device the hub does not know answers unknown_device."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=uid()))
    await client.close()
    assert reply["success"] is False
    assert reply["error"]["code"] == "unknown_device"
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert paired.device_id in hub_runtime.devices
