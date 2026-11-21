#!/usr/bin/env bash
# P3.13 hardware check (docs/packets/P3.13-screenshots.md):
#   the capture button makes the image entity show the current screen within
#   SCREENSHOT_TIMEOUT, the timestamp sensor updates and survives an HA
#   restart untouched by ordinary image fetches, and with the panel off the
#   entity shows the placeholder (F-HA-12, F-MGT-07, ADR 0014).
#
# Prerequisites: the tablet runs a P3.13 build, is provisioned
# (tools/device/provision.sh) and paired, the app is in the foreground, and
# the environment has:
#   TABULA_HA_URL          e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN        a long-lived access token
#   TABULA_SCREEN          the tablet's screen light, e.g. light.office_kiosk_screen
#   TABULA_BUTTON          its capture button, e.g. button.office_kiosk_capture_screenshot
#   TABULA_IMAGE           its screenshot image entity, e.g. image.office_kiosk_screenshot
#   TABULA_LAST_CAPTURE    its last-capture sensor, e.g. sensor.office_kiosk_last_capture
#   TABULA_HA_STOP_CMD     a command that stops HA, e.g.
#                          TABULA_HA_STOP_CMD='ssh ha@homeassistant "ha core stop"'
#   TABULA_HA_START_CMD    a command that starts HA again, e.g.
#                          TABULA_HA_START_CMD='ssh ha@homeassistant "ha core start"'
#   ANDROID_SERIAL         optional, picks the tablet when several are attached
#
# Step 5 restarts HA, so both commands are required (no Wi-Fi fallback: a
# restart is the point). The trap puts HA back on any failure and Ctrl-C.
#
# The script never touches the tablet's pairing or app data. Paste the whole
# output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
service_tag=TabulaService

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_SCREEN:?set TABULA_SCREEN to the tablet screen light entity id}"
: "${TABULA_BUTTON:?set TABULA_BUTTON to the capture button entity id}"
: "${TABULA_IMAGE:?set TABULA_IMAGE to the screenshot image entity id}"
: "${TABULA_LAST_CAPTURE:?set TABULA_LAST_CAPTURE to the last-capture sensor entity id}"
: "${TABULA_HA_STOP_CMD:?set TABULA_HA_STOP_CMD, e.g. 'ssh ha@homeassistant \"ha core stop\"' (step 5 restarts HA)}"
: "${TABULA_HA_START_CMD:?set TABULA_HA_START_CMD, e.g. 'ssh ha@homeassistant \"ha core start\"'}"

# child processes (none here) and curl read the token from the environment
export TABULA_HA_TOKEN

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
ha_available() { [[ "$(ha_state "$1")" != unavailable && "$(ha_state "$1")" != unknown ]]; }
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
device_id() {
    adb shell run-as "$pkg" cat shared_prefs/tabula_device.xml 2>/dev/null \
        | sed -n 's/.*name="device_id">\([0-9a-f-]\{36\}\)<.*/\1/p' | head -1
}

# --- what the script must restore on any exit --------------------------------
ha_down_by_us=0
panel_off_by_us=0
completed=0

restore() {
    if (( ha_down_by_us )); then
        ha_down_by_us=0
        echo "-- restore: starting HA with: $TABULA_HA_START_CMD" >&2
        bash -c "$TABULA_HA_START_CMD" || echo "-- restore: the HA start command failed; start HA by hand" >&2
    fi
    if (( panel_off_by_us )); then
        panel_off_by_us=0
        if ha_answers; then
            ha_call light turn_on "{\"entity_id\": \"$TABULA_SCREEN\"}" \
                || echo "-- restore: light.turn_on failed; turn the panel back on by hand" >&2
        else
            echo "-- restore: HA is not answering; turn the tablet's panel back on by hand" >&2
        fi
    fi
    if (( completed )); then
        echo
        echo "P3.13 hardware check complete; paste everything above into the packet Result."
    fi
    return 0
}
trap restore EXIT
trap 'restore; exit 130' INT
trap 'restore; exit 143' TERM

ha_http_code() { # HA's HTTP status for /api/; 000 means it did not answer at all
    curl -s -o /dev/null -m 5 -w '%{http_code}' "${TABULA_HA_URL%/}/api/" || true
}
ha_answers() { # the Mac reached HA's API at all; a 401 without a token still means HA is up
    [[ "$(ha_http_code)" != 000 ]]
}
ha_stopped() { ! ha_answers; }
# wait_for re-runs its command each second, but command substitution in its
# arguments would expand once at call time; these re-read the state per call
sensor_is() { # sensor_is <entity> <expected>
    [[ "x$(ha_state "$1")" == "x$2" ]]
}
sensor_moved_past() { # sensor_moved_past <entity> <old value>
    [[ "x$(ha_state "$1")" != "x$2" ]]
}

step "1. baseline: the tablet is paired and the entities are live"
adb devices -l
[[ -n "$(adb shell pidof "$pkg" | tr -d '\r')" ]] || fail "Tabula is not running on the tablet"
echo "-- device_id: $(device_id)"
wait_for "the capture button to be available in HA" 120 ha_available "$TABULA_BUTTON"
echo "-- image entity state (the last capture's time, or unknown before the first capture): $(ha_state "$TABULA_IMAGE")"
echo "-- last-capture sensor: $(ha_state "$TABULA_LAST_CAPTURE")"

step "2. panel off: the capture ACKs, nothing uploads, the placeholder shows (F-MGT-07)"
ha_call light turn_off "{\"entity_id\": \"$TABULA_SCREEN\"}"
panel_off_by_us=1
sleep 3
capture_before="$(ha_state "$TABULA_LAST_CAPTURE")"
ha_call button press "{\"entity_id\": \"$TABULA_BUTTON\"}"
# the command has SCREENSHOT_TIMEOUT (30 s) to complete; the sensor moves only
# on success, and a screen-off capture is a success (F-MGT-07)
wait_for "the last-capture sensor to move (screen-off capture completed)" 45 \
    sensor_moved_past "$TABULA_LAST_CAPTURE" "$capture_before"
echo "-- last-capture sensor: $(ha_state "$TABULA_LAST_CAPTURE") (was $capture_before)"
ask "In HA: does the image entity now show the placeholder - a dark slate with a power-off glyph, not a black frame and not the previous screenshot?"

step "3. panel on: the capture shows the current screen (F-HA-12)"
ha_call light turn_on "{\"entity_id\": \"$TABULA_SCREEN\"}"
panel_off_by_us=0
sleep 3
capture_before="$(ha_state "$TABULA_LAST_CAPTURE")"
ha_call button press "{\"entity_id\": \"$TABULA_BUTTON\"}"
wait_for "the last-capture sensor to move (screen-on capture completed)" 45 \
    sensor_moved_past "$TABULA_LAST_CAPTURE" "$capture_before"
capture_time="$(ha_state "$TABULA_LAST_CAPTURE")"
echo "-- last-capture sensor: $capture_time (was $capture_before)"
[[ "$(ha_state "$TABULA_IMAGE")" == "$capture_time" ]] \
    || fail "the image entity's state ($(ha_state "$TABULA_IMAGE") != the sensor's $capture_time)"
echo "-- the image entity's state matches the sensor"
# Some Fire OS WebView builds draw a blank bitmap (the Fire OS note in
# DEVICE.md and DashboardView.capture): this ask is the check for it.
ask "In HA: does the image entity show the tablet's current screen, drawn in full - not blank, not all-black, not partially missing UI?"

step "4. ordinary image fetches leave the timestamp alone (F-HA-12)"
echo "-- (the ask above fetched the image; the timestamp must not have moved)"
sensor_is "$TABULA_LAST_CAPTURE" "$capture_time" \
    || fail "the timestamp moved without a capture button press"
echo "-- timestamp still $capture_time after the image fetches"

step "5. the timestamp survives an HA restart (F-HA-12)"
echo "-- stopping HA with: $TABULA_HA_STOP_CMD"
ha_down_by_us=1
bash -c "$TABULA_HA_STOP_CMD"
wait_for "HA to stop answering" 90 ha_stopped
echo "-- HA is down; starting it again with: $TABULA_HA_START_CMD"
bash -c "$TABULA_HA_START_CMD"
ha_down_by_us=0
wait_for "HA to answer the Mac again" 240 ha_answers
wait_for "the tablet to re-register (entities available again)" 240 ha_available "$TABULA_BUTTON"
sensor_is "$TABULA_LAST_CAPTURE" "$capture_time" \
    || fail "last_capture did not survive the restart: $(ha_state "$TABULA_LAST_CAPTURE") != $capture_time"
echo "-- last-capture sensor after the restart: $(ha_state "$TABULA_LAST_CAPTURE") (unchanged)"
ask "In HA: does the image entity still show the screenshot from step 3 after the restart?"

step "6. service log for the record"
adb shell logcat -d -v time -s "$service_tag:I" "$service_tag:W" | tail -40

completed=1
