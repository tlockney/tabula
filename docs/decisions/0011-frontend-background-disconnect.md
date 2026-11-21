# 0011: The frontend's background-disconnect behavior

Status: accepted
Date: 2026-11-21
Packet: P2.2

## Context

Plan 5.8 (Dashboard WebView) says the frontend "drops its socket when the
page is hidden" and requires measuring whether that happens in practice
**before** any shim is written, because Phase 4's WebView-hiding optimization
must be designed together with the measured behavior. A wall tablet's
dashboard is "hidden" whenever the screen is off or a native layer covers
the WebView, so this decides whether Tabula needs to patch, fake or
supplement anything for the dashboard to survive at the wall.

## Method

- A throwaway local Home Assistant **2026.2.3** (the repo's pinned venv) with
  the shipped frontend **20260128.6**, onboarded over its REST API, on
  `http://127.0.0.1:8199`.
- Headless Chromium (Playwright 1.59.0) with `window.externalApp` (the V1
  bridge, the same contract Tabula implements) injected before load, answering
  `getExternalAuth` with an access token and `config/get` with a minimal
  config. `window.WebSocket` is subclassed in the harness to record every
  socket's open and close.
- The page loaded `/?external_auth=1`, authenticated, rendered the Overview
  panel; then the harness made the page hidden the only way the frontend can
  observe: it overrode `document.hidden`/`document.visibilityState` and
  dispatched a real `visibilitychange`, then restored them.
- Source reading (Apache-2.0): the frontend's `src/layouts/home-assistant.ts`
  (decoded from the shipped bundle and confirmed against the dev source) and
  `src/external_app/external_messaging.ts`.

The measurement harness is not committed; the evidence below is its pasted
output.

## Findings

### 1. The page hidden does not touch the socket for five minutes

Loaded and visible, then hidden; snapshots of the instrumented WebSocket:

```text
loaded/visible: {"url":"http://127.0.0.1:8199/home/overview?external_auth=1","panel":"home","authFlow":false,"user":"Measurement","authCalls":1,"bus":["config/get","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}],"now":1795276639189}
hidden/15s:   {"url":"http://127.0.0.1:8199/home/overview?external_auth=1","panel":"home","authFlow":false,"user":"Measurement","authCalls":1,"bus":["config/get","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}],"now":1795276654200}
hidden/60s:   {"url":"http://127.0.0.1:8199/home/overview?external_auth=1","panel":"home","authFlow":false,"user":"Measurement","authCalls":1,"bus":["config/get","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}],"now":1795276699207}
hidden/120s:  {"url":"http://127.0.0.1:8199/home/overview?external_auth=1","panel":"home","authFlow":false,"user":"Measurement","authCalls":1,"bus":["config/get","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}],"now":1795276759214}
hidden/180s:  {"url":"http://127.0.0.1:8199/home/overview?external_auth=1","panel":"home","authFlow":false,"user":"Measurement","authCalls":1,"bus":["config/get","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}],"now":1795276819220}
hidden/300s:  {"url":"http://127.0.0.1:8199/home/overview?external_auth=1","panel":"home","authFlow":false,"user":"Measurement","authCalls":1,"bus":["config/get","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}],"now":1795276939227}
```

(Each `now` is `Date.now()`; the page became hidden between the first and
second line.)

The frontend's `_onHidden()` (from its source): it calls
`connection.suspendReconnectUntil(<a promise that resolves when visible>)` —
so a connection that *drops* while hidden is not re-attempted until the page
is visible again — and starts a **300 000 ms** timer. While the page stays
hidden less than five minutes, nothing is closed.

### 2. At exactly five minutes hidden, the socket closes cleanly

```text
hidden/390s: {"authCalls":1,"bus":["config/get","connection-status","theme-update","connection-status"],
              "ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":{"code":1000,"at":1795276939232}}]}
```

The close timestamp is 299.8 s after the page became hidden: the 5-minute
timer fired, the frontend ran `window.stop()` and `connection.suspend()`, and
the socket closed with code 1000. The timer's callback re-checks
`document.hidden` before suspending, and the frontend's own source notes the
timer can be *delayed* indefinitely on Android when the app is in the
background ("Hey Android!") — so on a real tablet with the screen off the
suspend may never fire until the screen is back.

### 3. Becoming visible again reconnects without a reload or a re-auth

```text
visible-again/10s: {"authCalls":1,"bus":["config/get","connection-status","theme-update","connection-status","connection-status","theme-update"],"ws":[{"url":"ws://127.0.0.1:8199/api/websocket","closed":{"code":1000,"at":1795276939232}},{"url":"ws://127.0.0.1:8199/api/websocket","closed":null}]}
RESULT reloaded: False
```

On visible the frontend opens a fresh WebSocket to the same page — no
navigation, no `external_auth` bootstrap repeat (`authCalls` stayed 1; the
auth object lives in the page's memory, not in a token the page could lose).
A "suspend" is a socket lifecycle event, not a page reload, so the
P2.2 done-when item "screen off/on doesn't reload" is satisfied by the
frontend's own behavior.

### 4. Harness constraint discovered: bus answers must be asynchronous

The frontend registers the pending reply for a bus message *after*
`externalApp.externalBus()` returns, so a bridge that answers synchronously
gets its reply dropped (`Received unknown msg ID 1`, and the page hangs on
its launch screen waiting for the config). Tabula's bridge answers through
`WebView.evaluateJavascript`, which is asynchronous by construction, so it
cannot hit this; the constraint is recorded here because any future
test harness or reimplementation must answer async too.

## Decision

1. **No shim.** P2.2 ships nothing that patches or supplements the
   frontend's background behavior: measured, it needs no help. Short screen
   off periods (the P2.2 hardware check's 15 s toggle) never leave the
   five-minute window, and even a five-minute-plus suspension recovers with
   a reconnect, not a reload.
2. **Phase 4's WebView-hiding optimization designs against this ADR.** If
   the benchmark wants the socket kept alive behind an opaque screensaver,
   the frontend exposes the lever already: the `hass-suspend-when-hidden`
   event (persisted as `suspendWhenHidden` in localStorage) turns the whole
   suspend behavior off, no internals patching. If instead the target is
   *more* aggressive power saving, letting the frontend suspend is the
   default. The Phase 4 packet decides with the CPU benchmark in hand.
3. The device never mirrors the frontend's socket state into TLP: the
   frontend's connection is its own; Tabula's TLP session (P2.1) is
   independent and already survives the screen off.

## Consequences

- Plan 5.8's assumption is corrected: the frontend does not drop its socket
  the moment the page is hidden; it suspends after five minutes, and only
  reconnect attempts (not the socket) are held while hidden.
- P2.2's `DashboardView` must not pause the WebView on activity lifecycle
  events (pausing would stop the frontend's timers and visibility, which the
  measurement shows the frontend manages fine on its own); the activity does
  not call `WebView.onPause()`.
- The external-bus answers in `ExternalAuthBridge` stay asynchronous
  (evaluateJavascript), per finding 4.

## Alternatives considered

- **Dispatch `hass-suspend-when-hidden` (suspend off) now:** premature — the
  Phase 4 benchmark has not said whether keeping the socket open behind a
  screensaver costs anything worth avoiding.
- **Keep the WebView 'visible' while hidden (fake visibilityState):** hides
  real behavior from the frontend and would fight its reconnect suspension;
  rejected.
- **A Tabula-side keepalive over the frontend's socket:** the frontend owns
  that socket; Tabula's own TLP session already covers keepalive needs
  (P2.1, ADR 0002 APP_PING_INTERVAL).
