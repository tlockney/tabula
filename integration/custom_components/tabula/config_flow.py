"""Config flow: the hub entry, and discovery flows for pairing tablets.

The hub is a singleton found by unique_id, never single_config_entry (ADR 0001 item 1).
A pair request creates an integration_discovery flow whose context carries the
device_id; the user confirms the PAIR_CODE_DIGITS code shown on the tablet. The flow
ends with an abort ("paired") when the device's user, token and HA device exist - the
device then claims its credential from the claim view. Ignored entries do not block
re-pairing (ADR 0001 item 1 consequences).
"""

from __future__ import annotations

import secrets
from typing import Any

import voluptuous as vol
from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_CODE

from . import TabulaHub
from .const import DOMAIN, HUB_UNIQUE_ID, PAIR_CODE_ATTEMPTS

CODE_SCHEMA = vol.Schema({vol.Required(CONF_CODE): str})


class TabulaConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle the Tabula hub config flow."""

    VERSION = 1

    def __init__(self) -> None:
        self._pair: dict[str, Any] = {}

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Create the hub entry (found by unique_id; duplicates abort)."""
        await self.async_set_unique_id(HUB_UNIQUE_ID)
        self._abort_if_unique_id_configured()
        if user_input is not None:
            return self.async_create_entry(title="Tabula", data={})
        return self.async_show_form(step_id="user")

    async def async_step_integration_discovery(
        self, discovery_info: dict[str, Any]
    ) -> ConfigFlowResult:
        """A tablet posted to the pair view; ask the user for its code."""
        self._pair = dict(discovery_info)
        device_id: str = self._pair["device_id"]
        await self.async_set_unique_id(device_id)
        self._abort_if_unique_id_configured()
        self.context["title_placeholders"] = {
            "name": self._pair.get("name") or device_id[:8],
            "device_id": device_id,
        }
        return await self.async_step_device()

    async def async_step_device(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Check the code against the pending pairing (PAIR_CODE_ATTEMPTS)."""
        hub = TabulaHub.get(self.hass)
        device_id: str = self._pair["device_id"]
        placeholders = {
            "name": self._pair.get("name") or device_id[:8],
            "device_id": device_id,
        }
        if user_input is None:
            return self.async_show_form(
                step_id="device", data_schema=CODE_SCHEMA, description_placeholders=placeholders
            )
        if hub is None:
            return self.async_abort(reason="hub_not_loaded")
        pending = hub.pending.get(device_id)
        if pending is None or pending.nonce_hash != self._pair["nonce_hash"]:
            # Expired, or replaced by a newer pairing attempt from the same device.
            return self.async_abort(reason="expired")
        code = user_input[CONF_CODE].strip()
        if not secrets.compare_digest(code, pending.code):
            # Any wrong entry burns an attempt, whatever shape it has (ADR 0002:
            # PAIR_CODE_ATTEMPTS is the whole defense; the code is 6 digits).
            pending.attempts += 1
            if pending.attempts >= PAIR_CODE_ATTEMPTS:
                hub.pending.delete(device_id)
                return self.async_abort(reason="too_many_attempts")
            return self.async_show_form(
                step_id="device",
                data_schema=CODE_SCHEMA,
                description_placeholders=placeholders,
                errors={CONF_CODE: "invalid_code"},
            )
        # Confirmed: create the device's user, token and HA device (ADR 0001 item 2).
        token, ha_device_id = await hub.create_device(
            device_id=device_id, name=pending.name, model=pending.model
        )
        pending.confirmed_token = token
        pending.confirmed_ha_device_id = ha_device_id
        return self.async_abort(reason="paired")
