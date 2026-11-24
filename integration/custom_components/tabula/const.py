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
SCHEMA_VERSION = (
    3  # settings schema (P0.5; P3.1 added v2, P7.1 added v3); documents are stored at this version
)

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
COMMAND_TIMEOUT = 10.0  # s; awaiting a command's tabula/result (PROTOCOL.md 4.6)
SCREENSHOT_TIMEOUT = 30.0  # s
# PROTOCOL.md 5: the upload body's cap; the view refuses more with 413.
SCREENSHOT_MAX_BYTES = 5 * 1024 * 1024

# Command -> capabilities, any of which allows it (PROTOCOL.md 4.5); empty means
# always allowed. The integration never sends a command the device did not declare.
COMMAND_CAPABILITIES: dict[str, frozenset[str]] = {
    "set_settings": frozenset(),
    "screen": frozenset({"screen", "brightness"}),
    "notify": frozenset({"notifications"}),
    "play_media": frozenset({"audio"}),
    "media_control": frozenset({"audio"}),
    "screensaver": frozenset({"screensaver"}),
    "navigate": frozenset({"navigation"}),
    "go_home": frozenset({"go_home"}),
    "reload": frozenset(),
    "restart_app": frozenset(),
    "clear_cache": frozenset(),
    "log_subscription": frozenset({"logs"}),
    "screenshot": frozenset({"screenshot"}),
}

# Intercom (P9.2; values chosen in ADR 0002, "Intercom"). The integration owns
# these timers: a ring that nobody answers is a missed call, an answered call
# that never exchanges a signal gives up, and every call ends at the hard limit
# (ADR 0028 guards 2 and 3).
INTERCOM_RING_LIMIT_S = 45.0
INTERCOM_CONNECT_TIMEOUT_S = 10.0
INTERCOM_CALL_LIMIT_S = 300.0

# Per-device system users (ADR 0001 items 2-4): local_only=True by default,
# group user via homeassistant.auth.const.GROUP_ID_USER, names "Tabula <id8>".
USER_NAME_PREFIX = "Tabula"
LOCAL_ONLY = "local_only"  # hub option: False creates device users reachable remotely

# Dispatcher signals (P1.3): a device's declared capabilities changed (platforms
# add or remove entities); a device's state or availability changed ({} is the
# device_id).
SIGNAL_CAPABILITIES = f"{DOMAIN}_capabilities"
SIGNAL_DEVICE_UPDATE = f"{DOMAIN}_device_update_{{}}"
# A capture landed (or the stored one was dropped, F-MGT-07); the image entity
# refreshes (P3.13).
SIGNAL_SCREENSHOT = f"{DOMAIN}_screenshot_{{}}"
# A device's stored settings document changed (P3.2: settings entities refresh).
SIGNAL_SETTINGS_UPDATE = f"{DOMAIN}_settings_update_{{}}"

# hass.data keys
DATA_WS_REGISTERED = f"{DOMAIN}_ws_registered"
