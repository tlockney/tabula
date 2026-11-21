# 0019: Close device sockets when the config entry unloads

Status: accepted
Date: 2026-11-21
Issue: #97

## Context

Editing a paired device's name or options makes HA reload the Tabula config
entry. The old hub unloaded, but the tablet's WebSocket stayed open on a
session that no longer existed: the device kept a socket nobody answered,
saw no error, and never re-registered, so every entity sat at unavailable
until the tablet's radio was toggled (#97). The alternative is making the
device treat certain error replies as a session loss, which needs every
device implementation to cooperate.

## Decision

`async_unload_entry` closes every registered device WebSocket. The hub calls
websocket_api's own per-connection cancel handle - the same one auth
revocation uses to cut a socket - through one small helper. From HA 2026.11
the handle is an attribute of the connection itself; in 2025.1-2026.10 the
connection's `send_message` is bound to websocket_api's `WebSocketHandler`
(http.py), whose `_cancel` cancels both of its tasks and closes the socket
(the ADR 0001 item 8 range). Credentials are untouched, so the device
reconnects through its normal backoff (PROTOCOL.md 7) and re-registers: the
first retry lands within about a second, well inside the `BACKOFF_MAX` bound.

## Consequences

- Entities go unavailable for the reconnect window, exactly as any
  device-side disconnect; commands in flight fail immediately instead of
  timing out.
- The device needs no change: any implementation that follows PROTOCOL.md 7
  reconnects on socket close.
- Both handle locations are covered: the test-matrix legs exercise the
  handler path (HA 2025.1.0 through 2026.10.4), and the connection attribute
  is verified against the 2026.11.2 source.
- The hardware part of #97 (edit name or options; the tablet is available
  again within seconds) stays open until run on a real tablet.

## Alternatives considered

- Device-side interpretation of error replies, the issue's second option:
  lost because every device implementation must cooperate, and the original
  symptom is a device that does not.
- Revoking device tokens on reload: lost because the device would treat its
  next authentication as fatal and stop reconnecting.
- Sending only an error frame: lost because the socket stays open and the
  device keeps its dead session.
