"""Tabula integration constants. Values and reasoning: docs/decisions/0002-constants.md."""

from __future__ import annotations

# Names
DOMAIN = "tabula"

# The hub is a singleton config entry found by unique_id, never "the first entry"
# (ADR 0001 item 1); paired tablets are HA devices under it, not entries.
HUB_UNIQUE_ID = "tabula-hub"

# Protocol (docs/PROTOCOL.md section 8: the current major and the one before it;
# v1 is the only major, so both are 1).
PROTOCOL_VERSION = 1
SUPPORTED_PROTOCOL_VERSIONS = {1}
SCHEMA_VERSION = 1  # settings schema (P0.5); HA has no settings store until P1.5

# Pairing (ADR 0002)
PAIR_CODE_DIGITS = 6
PAIR_CODE_ATTEMPTS = 5
PAIR_PENDING_TTL = 600.0  # s
PAIR_PENDING_CAP = 8
PAIR_RATE_LIMIT = 10  # requests per window, per source IP
CLAIM_RATE_LIMIT = 60  # requests per window, per source IP
PAIR_RATE_WINDOW = 60.0  # s
CLAIM_POLL_INTERVAL = 3.0  # s; the integer form goes on the wire
PAIR_MAX_BODY = 4096  # bytes

# WebSocket session (ADR 0002)
FRAME_MAX_BYTES = 256 * 1024

# Per-device system users (ADR 0001 items 2-4): local_only=True by default,
# group user via homeassistant.auth.const.GROUP_ID_USER, names "Tabula <id8>".
USER_NAME_PREFIX = "Tabula"
LOCAL_ONLY = "local_only"  # hub option: False creates device users reachable remotely

# Dispatcher signals (P1.3): a device's declared capabilities changed (platforms
# add or remove entities); a device's state or availability changed ({} is the
# device_id).
SIGNAL_CAPABILITIES = f"{DOMAIN}_capabilities"
SIGNAL_DEVICE_UPDATE = f"{DOMAIN}_device_update_{{}}"

# hass.data keys
DATA_WS_REGISTERED = f"{DOMAIN}_ws_registered"
