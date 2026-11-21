"""TLP v1 constants. Values and reasoning: docs/decisions/0002-constants.md."""

PROTOCOL_VERSION = 1
SCHEMA_VERSION = 1

# Pairing
PAIR_CODE_DIGITS = 6
PAIR_CODE_ATTEMPTS = 5
PAIR_PENDING_TTL = 600  # s
NONCE_BYTES = 32
CLAIM_POLL_INTERVAL = 3.0  # s; the server's poll_interval overrides it

# Credential
ACCESS_TOKEN_LIFETIME = 1800  # s; HA's own value (ADR 0001), used by the test server
TOKEN_REFRESH_MARGIN = 300.0  # s

# WebSocket session
FRAME_MAX_BYTES = 256 * 1024
REGISTER_TIMEOUT = 10.0  # s
STATE_MIN_INTERVAL = 0.25  # s
APP_PING_INTERVAL = 30.0  # s
PONG_TIMEOUT = 10.0  # s
COMMAND_TIMEOUT = 10.0  # s

# Reconnect
BACKOFF_BASE = 1.0  # s
BACKOFF_MAX = 60.0  # s
BACKOFF_RESET_AFTER = 60.0  # s

# Logs
LOG_MAX_LINES = 200
LOG_MIN_INTERVAL = 1.0  # s
LOG_SUBSCRIPTION_TTL = 600.0  # s
