#!/usr/bin/env bash
# P3.14 hardware check (docs/packets/P3.14-engine-polish.md): pixel shift
# (F-SSV-22), the fade (F-SSV-23) and dim's own level (F-SSV-07).
#
# Prerequisites: the tablet runs a P3.14 build, is provisioned and paired, the
# app is in the foreground with the panel on, and the tablet's HA entry has
# these settings configured (the options flow; they apply when the
# screensaver starts, ADR 0022):
#   screensaver.pixel_shift.enabled on, interval 10 s (TABULA_SHIFT_INTERVAL
#     below must match), amplitude 8 px (large enough to see from a couch)
#   screensaver.fade.enabled on
#   screensaver.brightness 20 (default), brightness_enabled on (default)
#   screensaver.dim_brightness 5 (distinct from 20)
# The environment needs:
#   TABULA_HA_URL           e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN         a long-lived access token (any user that may call services)
#   TABULA_SCREENSAVER      the tablet's screensaver switch, e.g. switch.office_kiosk_screensaver
#   TABULA_SHIFT_INTERVAL   optional, the configured pixel-shift interval (default 10)
#   ANDROID_SERIAL          optional, picks the tablet when several are attached
#
# The visual steps (the fade in/out, the clock's visible step, the dimmed
# dashboard) ask the verifier; the brightness and logcat checks are mechanical.
# Paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
shift_interval="${TABULA_SHIFT_INTERVAL:-10}"

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_SCREENSAVER:?set TABULA_SCREENSAVER to the tablet screensaver switch entity id}"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_call() { # ha_call <domain> <service> <json body>
    curl -fsS -X POST \
        -H "Authorization: Bearer ${TABULA_HA_TOKEN}" -H "Content-Type: application/json" \
        -d "$3" "${TABULA_HA_URL%/}/api/services/$1/$2" > /dev/null
    echo "-- called $1.$2 $3"
}
ha_state() { # ha_state <entity_id> -> prints the state
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" "${TABULA_HA_URL%/}/api/states/$1" \
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
shift_lines() { # pixel-shift log lines currently in the logcat buffer
    adb logcat -d | grep -c 'pixel shift: dx=' || true
}
start_screensaver() { # start_screensaver <mode or empty for the configured one>
    local body
    if [[ -n "$1" ]]; then
        body="{\"entity_id\": \"$TABULA_SCREENSAVER\", \"active\": true, \"mode\": \"$1\"}"
    else
        body="{\"entity_id\": \"$TABULA_SCREENSAVER\", \"active\": true}"
    fi
    ha_call tabula screensaver "$body"
    wait_for "the screensaver switch to turn on" 10 ha_state_is "$TABULA_SCREENSAVER" on
}
dismiss_screensaver() { # tap, then wait for the switch
    tap_centre
    wait_for "the screensaver switch to turn off" 10 ha_state_is "$TABULA_SCREENSAVER" off
}

step "1. app running, screensaver idle"
adb devices -l
adb shell pidof "$pkg" || fail "Tabula is not running"
ha_state_is "$TABULA_SCREENSAVER" off || fail "the screensaver is already on"
day_level="$(system_level)"
echo "-- system brightness before: $day_level"

step "2. clock: fade in, pixel shift, screensaver brightness"
start_screensaver clock
ask "Did the clock fade in (roughly a second, not a snap)? (fade.enabled is on)"
clock_level="$(system_level)"
echo "-- system brightness in clock: $clock_level (screensaver.brightness 20; day was $day_level)"
echo "-- collecting pixel-shift lines over two ${shift_interval}s intervals..."
adb logcat -c
sleep $(( shift_interval * 2 + 15 ))
lines="$(adb logcat -d -s TabulaScreensaver | grep 'pixel shift: dx=' || true)"
count="$(grep -c 'pixel shift: dx=' <<< "$lines" || true)"
(( count >= 2 )) || fail "expected at least 2 pixel-shift log lines, got $count"
echo "$lines"
echo "-- $count shifts logged"
ask "Did the clock's content visibly step by a small offset at the interval?"
dismiss_screensaver
ask "Did the clock fade out after the touch (not a snap)?"
sleep 2
echo "-- system brightness after dismissal: $(system_level) (expect $day_level)"

step "3. black: never shifts (F-SSV-22), lowest backlight (F-SSV-06)"
start_screensaver black
echo "-- system brightness in black: $(system_level) (expect the lowest, 1)"
adb logcat -c
sleep $(( shift_interval + 10 ))
count="$(shift_lines)"
(( count == 0 )) || fail "black mode shifted: $count pixel-shift lines"
echo "-- no pixel-shift lines in black over ${shift_interval}s: 0"
dismiss_screensaver

step "4. dim: holds dim_brightness (F-SSV-07), no fade, restores on dismissal"
start_screensaver dim
dim_level="$(system_level)"
echo "-- system brightness in dim: $dim_level (dim_brightness 5; clock's 20 gave $clock_level)"
[[ "$dim_level" != "$clock_level" ]] || fail "dim is not holding its own level (same as the clock's)"
ask "Is the dashboard still visible, dimmed, with nothing drawn over it (no fade flash)?"
dismiss_screensaver
sleep 2
echo "-- system brightness after dismissal: $(system_level) (expect $day_level)"

echo
echo "P3.14 hardware check complete; paste everything above into the packet Result."
