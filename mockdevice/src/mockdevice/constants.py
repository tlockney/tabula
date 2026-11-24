"""TLP v1 constants. Values and reasoning: docs/decisions/0002-constants.md."""

import base64

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

# Screenshots (P3.13)
SCREENSHOT_TIMEOUT = 30.0  # s; the command's wait, longer than the others (0002-constants.md)
SCREENSHOT_MAX_BYTES = 5 * 1024 * 1024  # the upload body's cap (0002-constants.md)
# The mock's simulated capture: a 16x16 slate JPEG, so the flow needs no Pillow.
# The `quality` argument is validated but not applied - the mock has no screen.
SCREENSHOT_JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDwiiiitjE//9k="
)

# Voice audio (P7.2, PROTOCOL.md 4.10; values from HA's assist_pipeline/const.py)
VOICE_CHUNK_BYTES = 320  # 160 samples of 16-bit PCM = 10 ms of audio
VOICE_FRAME_INTERVAL = 0.01  # s; one chunk per 10 ms of audio
VOICE_TURN_MAX_S = 300.0  # s; HA's DEFAULT_PIPELINE_TIMEOUT

# Reconnect
BACKOFF_BASE = 1.0  # s
BACKOFF_MAX = 60.0  # s
BACKOFF_RESET_AFTER = 60.0  # s

# Intercom (P9.1; values chosen in docs/decisions/0002-constants.md)
INTERCOM_RING_LIMIT_S = 45  # a ringing call nobody answers ends
INTERCOM_CONNECT_TIMEOUT_S = 10  # offer to connected audio
INTERCOM_CALL_LIMIT_S = 300  # the hard call limit, from the moment the call is up
INTERCOM_SIGNAL_MAX_BYTES = 4096  # one offer, answer or candidate's payload
INTERCOM_BROADCAST_MAX_TARGETS = 8  # one broadcast's receivers (provisional, ADR 0037 decision 7)

# Logs
LOG_MAX_LINES = 200
LOG_MIN_INTERVAL = 1.0  # s
LOG_SUBSCRIPTION_TTL = 600.0  # s
