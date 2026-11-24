#!/usr/bin/env bash
# P5.4 hardware check (docs/packets/P5.4-log-viewer.md):
#   the app's own log lines and the dashboard's JavaScript console stream live
#   to the Tabularium, stopping the subscription stops them, a URL that carries
#   a token is redacted before it is logged, and the buffer is bounded
#   (F-MGT-06, PROTOCOL.md 4.8, ADR 0002).
#
# Prerequisites: the tablet runs a P5.4 build and is paired and online, and the
# environment has:
#   TABULA_HA_URL      e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN    a long-lived token of an ADMIN user (the panel is admin-only)
#   TABULA_DEVICE      the device's Tabula id; leave unset to be shown the list
#   TABULA_NAVIGATE    optional: a path that raises a dashboard console error,
#                      e.g. /lovelace/no-such-view (default: that path)
#
# The script drives HA only: it never touches the tablet, its pairing or its app
# data. It navigates the dashboard away and back, which the device check wants;
# paste the whole output into the packet Result section.

set -euo pipefail

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to an admin long-lived access token}"
navigate_path="${TABULA_NAVIGATE:-/lovelace/no-such-view}"
here="$(cd "$(dirname "$0")" && pwd)"
logs() { uv run python "$here/ha_logs.py" --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$@"; }

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_post() { # ha_post <domain> <service> <json body>
    curl -fsS -X POST \
        -H "Authorization: Bearer ${TABULA_HA_TOKEN}" \
        -H "Content-Type: application/json" \
        -d "$3" "${TABULA_HA_URL%/}/api/services/$1/$2" > /dev/null
    echo "-- called $1.$2 $3"
}

if [[ -z "${TABULA_DEVICE:-}" ]]; then
    step "paired devices (Tabula id, HA device id, name, state)"
    logs devices
    fail "set TABULA_DEVICE to one of the Tabula ids above and run again"
fi

# The services take the HA device id; the panel commands take the Tabula one.
ha_device="$(logs devices | awk -v id="$TABULA_DEVICE" '$1 == id { print $2 }')"
[[ -n "$ha_device" ]] || fail "TABULA_DEVICE is not a paired device id; run without it to see the list"
echo "-- HA device id: $ha_device"

step "1. the buffer an idle device holds"
logs snapshot --device "$TABULA_DEVICE" --tail 5

step "2. follow the stream: reload the dashboard for an app line"
# `stream` prints every line it receives; the reload and the navigate below run
# in the background so both land inside its window.
( sleep 2
  # A navigation is an app line ("dashboard: loading ...").
  ha_post tabula navigate '{"device_id":"'"$ha_device"'","path":"/lovelace/0"}'
  sleep 2
  # A path the frontend cannot render makes it log to its console, so a
  # console-tagged line arrives even when the page has nothing to say.
  ha_post tabula navigate '{"device_id":"'"$ha_device"'","path":"'"$navigate_path"'"}'
  sleep 2
  # A URL carrying a token is the redaction case: PROTOCOL.md 4.8 strips the
  # query string before the line is logged, so the marker must not appear.
  ha_post tabula navigate \
      '{"device_id":"'"$ha_device"'","path":"/lovelace/0?access_token=p54-secret-marker"}'
) >&2 &
background=$!
stream_out="$(logs stream --device "$TABULA_DEVICE" --seconds 20 || true)"
wait "$background" || true
echo "$stream_out"

grep -q 'SUMMARY lines=' <<<"$stream_out" || fail "the stream did not open; is the device online?"
lines="$(sed -n 's/^SUMMARY lines=\([0-9]*\) console=\([0-9]*\)$/\1/p' <<<"$stream_out")"
console="$(sed -n 's/^SUMMARY lines=\([0-9]*\) console=\([0-9]*\)$/\2/p' <<<"$stream_out")"
[[ "${lines:-0}" -gt 0 ]] || fail "no app line arrived while streaming"
[[ "${console:-0}" -gt 0 ]] ||
    fail "no console line arrived; open the dashboard, make it log an error, and run again"
grep -q 'p54-secret-marker' <<<"$stream_out" &&
    fail "a token from a URL query reached the log; PROTOCOL.md 4.8 says it must not"

step "3. stopping the subscription stops the lines"
stopped="$(logs stream --device "$TABULA_DEVICE" --seconds 1 >/dev/null 2>&1 || true)"
ha_post tabula navigate '{"device_id":"'"$ha_device"'","path":"/lovelace/1"}'
sleep 3
logs snapshot --device "$TABULA_DEVICE" --tail 3
echo "-- no stream is open now ($stopped), so the reload above must add nothing: compare"
echo "   the snapshot's newest ts with the reload's time."

step "4. the buffer is bounded"
logs snapshot --device "$TABULA_DEVICE" --tail 0 | grep -E 'lines=[0-9]+/[0-9]+' ||
    fail "the snapshot did not report the buffer's bound"

step "5. what to paste"
echo "App lines: $(grep -cE ' (info|warning|error|debug) (app|service|dashboard|logs): ' <<<"$stream_out" || true)"
echo "Console lines: ${console:-0}"
echo "Redaction marker seen: $(grep -c 'p54-secret-marker' <<<"$stream_out" || true) (must be 0)"
