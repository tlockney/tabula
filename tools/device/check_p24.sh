#!/usr/bin/env bash
# P2.4 hardware check (docs/packets/P2.4-screensaver.md):
#   the tablet idles into the clock, touch dismisses it, and HA (an automation
#   calls the same services) can start and stop it; each start and stop
#   reaches HA as a tabula_screensaver event.
#
# Prerequisites: the tablet runs a P2.4 build, is provisioned and paired, the
# app is in the foreground with the panel on, and the environment has:
#   TABULA_HA_URL           e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN         a long-lived access token (any user that may call services)
#   TABULA_SCREENSAVER      the tablet's screensaver switch, e.g. switch.office_kiosk_screensaver
#   TABULA_NEXT             its next-screensaver sensor, e.g. sensor.office_kiosk_next_screensaver
#   ANDROID_SERIAL          optional, picks the tablet when several are attached
#
# Step 2 waits for the idle timeout (default 300 s, ADR 0002) without touching
# the tablet, and asks the verifier what the screen shows. Paste the whole
# output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
here="$(cd "$(dirname "$0")" && pwd)"

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_SCREENSAVER:?set TABULA_SCREENSAVER to the tablet screensaver switch entity id}"
: "${TABULA_NEXT:?set TABULA_NEXT to the tablet next-screensaver sensor entity id}"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_call() { # ha_call <domain> <service> <json body>
    curl -fsS -X POST \
        -H "Authorization: Bearer $TABULA_HA_TOKEN" -H "Content-Type: application/json" \
        -d "$3" "${TABULA_HA_URL%/}/api/services/$1/$2" > /dev/null
    echo "-- called $1.$2 $3"
}
ha_state() { # ha_state <entity_id> -> prints the state
    curl -fsS -H "Authorization: Bearer $TABULA_HA_TOKEN" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])'
}
ha_state_is() { [[ "$(ha_state "$1")" == "$2" ]]; }
system_level() { adb shell settings get system screen_brightness | tr -d '\r'; }
wait_for() { # wait_for <description> <timeout s> <command...>
    local what="$1" timeout="$2"; shift 2
    local waited=0
    until "$@"; do
        (( waited >= timeout )) && fail "timed out after ${timeout}s waiting for $what"
        sleep 1; waited=$((waited + 1))
    done
    echo "-- $what after ${waited}s"
}
seconds_until() { # seconds_until <RFC 3339 time> -> whole seconds from now (0 if past)
    python3 -c 'import sys,datetime as d; t=d.datetime.fromisoformat(sys.argv[1].replace("Z","+00:00")); print(max(0, int((t-d.datetime.now(d.timezone.utc)).total_seconds())))' "$1"
}
ask() { # ask <question> -> fails unless the verifier answers y
    local answer
    read -r -p "$1 [y/n] " answer
    [[ "$answer" == y* ]] || fail "verifier answered '$answer' to: $1"
    echo "-- verifier: yes ($1)"
}
tap_centre() { # only while the screensaver shows: the overlay takes the touch
    local size w h
    size="$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
    read -r w h <<< "$size"
    adb shell input tap $((w / 2)) $((h / 2))
    echo "-- tapped the centre of the screen"
}

events_log="$(mktemp)"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_events.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" --event tabula_screensaver --seconds 1200 \
    > "$events_log" &
listener=$!
trap 'kill "$listener" 2>/dev/null || true' EXIT

step "1. app running, screensaver idle"
adb devices -l
adb shell pidof "$pkg" || fail "Tabula is not running"
sleep 3
kill -0 "$listener" 2>/dev/null || fail "the HA event listener did not start"
# a harmless key press counts as activity and starts the idle timer afresh
# (a tap could press something on the dashboard)
adb shell input keyevent KEYCODE_SHIFT_LEFT
sleep 3
echo "-- screensaver switch: $(ha_state "$TABULA_SCREENSAVER"); next screensaver: $(ha_state "$TABULA_NEXT")"
ha_state_is "$TABULA_SCREENSAVER" off || fail "the screensaver is already on"
day_level="$(system_level)"
echo "-- system brightness before: $day_level"

step "2. idle into the clock (do not touch the tablet)"
next="$(ha_state "$TABULA_NEXT")"
[[ "$next" != unknown && "$next" != unavailable ]] || fail "next screensaver is $next"
wait_s="$(seconds_until "$next")"
echo "-- the idle timer fires at $next, in ${wait_s}s"
wait_for "the screensaver switch to turn on" $((wait_s + 30)) ha_state_is "$TABULA_SCREENSAVER" on
echo "-- next screensaver while it shows: $(ha_state "$TABULA_NEXT") (expect unknown)"
echo "-- system brightness while it shows: $(system_level) (screensaver brightness; was $day_level)"
ask "Does the tablet show a clock with today's date on black?"

step "3. touch dismisses it"
tap_centre
wait_for "the screensaver switch to turn off" 10 ha_state_is "$TABULA_SCREENSAVER" off
sleep 2
echo "-- next screensaver: $(ha_state "$TABULA_NEXT"); system brightness: $(system_level) (expect $day_level)"
ask "Is the dashboard (or status screen) back?"

step "4. HA starts it in black mode and stops it"
ha_call tabula screensaver "{\"entity_id\": \"$TABULA_SCREENSAVER\", \"active\": true, \"mode\": \"black\"}"
wait_for "the screensaver switch to turn on" 10 ha_state_is "$TABULA_SCREENSAVER" on
echo "-- system brightness in black: $(system_level) (expect the lowest, 1)"
ask "Is the screen black?"
ha_call switch turn_off "{\"entity_id\": \"$TABULA_SCREENSAVER\"}"
wait_for "the screensaver switch to turn off" 10 ha_state_is "$TABULA_SCREENSAVER" off

step "5. HA switch starts the configured mode"
ha_call switch turn_on "{\"entity_id\": \"$TABULA_SCREENSAVER\"}"
wait_for "the screensaver switch to turn on" 10 ha_state_is "$TABULA_SCREENSAVER" on
ask "Does the tablet show the clock?"
ha_call switch turn_off "{\"entity_id\": \"$TABULA_SCREENSAVER\"}"
wait_for "the screensaver switch to turn off" 10 ha_state_is "$TABULA_SCREENSAVER" off

step "6. tabula_screensaver events received by HA"
sleep 2
cat "$events_log"
count="$(grep -c '"data"' "$events_log" || true)"
(( count >= 6 )) || fail "expected at least 6 tabula_screensaver events (3 starts, 3 stops), got $count"
echo "-- $count events"

echo
echo "P2.4 hardware check complete; paste everything above into the packet Result."
