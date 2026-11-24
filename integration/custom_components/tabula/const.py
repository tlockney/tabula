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
SCHEMA_VERSION = 4  # settings schema (P0.5; P3.1 added v2, P7.1 added v3, P9.5 added v4)

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
# P7.3: how long the satellite waits for the device to report the end of a
# spoken reply before it returns to idle anyway (ADR 0002).
VOICE_REPLY_TIMEOUT = 60.0  # s

# The capabilities an intercom call is built from (PROTOCOL.md 4.5): listen can
# hear one, talk can capture one, and caller can be the calling end of a call
# the integration started (ADR 0037 decision 2).
CAP_LISTEN = "intercom_listen"
CAP_TALK = "intercom_talk"
CAP_CALLER = "intercom_caller"

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
    # Intercom commands need a device that can hear a call (PROTOCOL.md 4.5,
    # ADR 0025 decision 7); the relay and the end reach every device in a call.
    "intercom_ring": frozenset({CAP_LISTEN}),
    "intercom_signal": frozenset({CAP_LISTEN}),
    "intercom_end": frozenset({CAP_LISTEN}),
    # The caller's own frame, gated on the capability that says a build can be
    # the calling end of a call HA started (ADR 0037 decision 2). It is an
    # any-of gate, so a caller's further requirements are a conjunction in
    # intercom._check_caller and never a second member here.
    "intercom_start": frozenset({CAP_CALLER}),
    # P7.2: voice turns carry their audio as binary frames on this socket (4.10).
    "voice_start": frozenset({"voice"}),
    "voice_stop": frozenset({"voice"}),
}

# Intercom (P9.2; values chosen in ADR 0002, "Intercom"). The integration owns
# these timers: a ring that nobody answers is a missed call, an answered call
# that never exchanges a signal gives up, and every call ends at the hard limit
# (ADR 0028 guards 2 and 3).
INTERCOM_RING_LIMIT_S = 45.0
INTERCOM_CONNECT_TIMEOUT_S = 10.0
INTERCOM_CALL_LIMIT_S = 300.0
# One broadcast's receivers, provisional until a broadcast is measured on
# hardware (ADR 0037 decision 7). A resolved set larger than this is refused,
# never trimmed.
INTERCOM_BROADCAST_MAX_TARGETS = 8

# Voice-started calls (P9.6, ADR 0036). A name, not a tunable: the hub entry
# option that maps a person's name onto an area or a tablet. Empty by default,
# so "call Thomas" says nothing Thomas has not set up.
INTERCOM_PERSONS = "intercom_persons"
# The capability pair that decides whether the intents exist at all: a tablet
# that can hear a request and capture a call (PROTOCOL.md 4.5, ADR 0025
# decisions 7 and 8).
INTERCOM_VOICE_CAPABILITIES = frozenset({"voice", "intercom_talk"})

# Logs (P5.4, ADR 0002). The device and HA hold the same bounded ring; the panel
# renders at most LOG_VIEW_MAX_LINES rows, which is the panel's constant.
LOG_BUFFER_LINES = 2000
LOG_SUBSCRIPTION_TTL = 600.0  # s; the device's own subscription limit
# The levels a subscription may filter at (PROTOCOL 4.8), weakest first.
LOG_LEVELS = ("debug", "info", "warning", "error")

# Profiles (P5.6, ADR 0002 "Profiles", ADR 0035). A profile name is a label in a
# picker; the profile count bounds one panel payload and the store.
PROFILE_NAME_MAX = 64
PROFILE_MAX = 32
# The built-in empty profile: covers no keys, permanent and read-only. Its id is
# a name no stored profile may take (ADR 0035 item 9).
EMPTY_PROFILE_ID = "empty"
EMPTY_PROFILE_NAME = "Empty"

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
# The Tabularium bundle is registered as a static path at most once per HA run
# (P5.1); a reload only re-registers the panel.
DATA_PANEL_REGISTERED = f"{DOMAIN}_panel_registered"
DATA_PANEL_STATIC_PATH = f"{DOMAIN}_panel_static_path"

# The Tabularium panel (P5.1, docs/packets/P5.1-panel-shell-and-codegen.md): the
# sidebar entry, the URL HA serves the bundle from and the custom element name
# inside it. Names, not tunables, so they live here rather than ADR 0002.
PANEL_URL_PATH = "tabularium"
PANEL_STATIC_URL_PATH = "/tabula_panel"
PANEL_WEBCOMPONENT = "tabula-panel"
PANEL_TITLE = "Tabularium"
PANEL_ICON = "mdi:tablet-dashboard"
