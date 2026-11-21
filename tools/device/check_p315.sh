#!/usr/bin/env bash
# P3.15 hardware check (docs/packets/P3.15-start-page.md):
#   with dashboard.start_path set to a second dashboard view, a cold start and
#   an app restart land on it, the go-to-start-page button returns to it from
#   another view, and with dashboard.return_home_after set short the tablet
#   returns to the start page after idle - also while a screensaver covers it
#   (F-DSH-02, F-DSH-15, F-NAV-03; ADR 0019).
#
# Prerequisites: the tablet runs a P3.15 build, is provisioned
# (tools/device/provision.sh) and paired, the app is in the foreground, and
# the environment has:
#   TABULA_HA_URL          e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN        a long-lived access token
#   TABULA_START_PATH      a second dashboard view, e.g. /lovelace/1
#   TABULA_OTHER_PATH      a different view to navigate away to, e.g. /lovelace/0
#   TABULA_TEXT            its start-page text entity, e.g. text.office_kiosk_start_path
#   TABULA_NUMBER          its return-home number, e.g. number.office_kiosk_return_home_after
#   TABULA_BUTTON          its go-to-start-page button, e.g. button.office_kiosk_go_home
#   TABULA_RESTART         its restart-app button, e.g. button.office_kiosk_restart_app
#   TABULA_PAGE            its current-page sensor, e.g. sensor.office_kiosk_current_page
#   ANDROID_SERIAL         optional, picks the tablet when several are attached
#
# The trap puts the settings back (start path empty, return never, screensaver
# off) on any failure and Ctrl-C. Paste the whole output into the packet
# Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
service_tag=TabulaService

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_START_PATH:?set TABULA_START_PATH to a second dashboard view, e.g. /lovelace/1}"
: "${TABULA_OTHER_PATH:?set TABULA_OTHER_PATH to a different view, e.g. /lovelace/0}"
: "${TABULA_TEXT:?set TABULA_TEXT to the start-page text entity id}"
: "${TABULA_NUMBER:?set TABULA_NUMBER to the return-home number entity id}"
: "${TABULA_BUTTON:?set TABULA_BUTTON to the go-to-start-page button entity id}"
: "${TABULA_RESTART:?set TABULA_RESTART to the restart-app button entity id}"
: "${TABULA_PAGE:?set TABULA_PAGE to the current-page sensor entity id}"

# child processes and curl read the token from the environment
export TABULA_HA_TOKEN

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $* (start path: $(ha_state "$TABULA_TEXT") / return: $(ha_state "$TABULA_NUMBER"))" >&2; exit 1; }

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
sensor_is() { [[ "x$(ha_state "$1")" == "x$2" ]]; }
ask() { # ask <question> -> fails unless the verifier answers y
    local answer
    read -r -p "$1 [y/n] " answer
    [[ "$answer" == y* ]] || fail "verifier answered '$answer' to: $1"
    echo "-- verifier: yes ($1)"
}

# --- what the script must restore on any exit --------------------------------
completed=0

restore() {
    if ha_answers; then
        ha_call tabula screensaver "{\"active\": false}" \
            || echo "-- restore: stopping the screensaver failed; dismiss it by hand" >&2
        ha_call number set_value "{\"entity_id\": \"$TABULA_NUMBER\", \"value\": 0}" \
            || echo "-- restore: setting the return delay back to 0 failed" >&2
        ha_call text set_value "{\"entity_id\": \"$TABULA_TEXT\", \"value\": \"\"}" \
            || echo "-- restore: clearing the start path failed" >&2
    else
        echo "-- restore: HA is not answering; clear the start path and the return delay by hand" >&2
    fi
    if (( completed )); then
        echo
        echo "P3.15 hardware check complete; paste everything above into the packet Result."
    fi
    return 0
}
trap restore EXIT
trap 'restore; exit 130' INT
trap 'restore; exit 143' TERM

ha_http_code() { curl -s -o /dev/null -m 5 -w '%{http_code}' "${TABULA_HA_URL%/}/api/" || true; }
ha_answers() { [[ "$(ha_http_code)" != 000 ]]; }

step "1. baseline: the tablet is paired and the P3.15 entities are live"
adb devices -l
[[ -n "$(adb shell pidof "$pkg" | tr -d '\r')" ]] || fail "Tabula is not running on the tablet"
wait_for "the go-to-start-page button to be available in HA" 120 ha_available "$TABULA_BUTTON"
echo "-- current page: $(ha_state "$TABULA_PAGE")"
echo "-- start path:   $(ha_state "$TABULA_TEXT")"
echo "-- return after: $(ha_state "$TABULA_NUMBER") s"
sensor_is "$TABULA_TEXT" "" || fail "the start path is not empty at the start; clear it and rerun"

step "2. setting the start path does not move the page (F-DSH-02)"
page_before="$(ha_state "$TABULA_PAGE")"
ha_call text set_value "{\"entity_id\": \"$TABULA_TEXT\", \"value\": \"$TABULA_START_PATH\"}"
wait_for "the tablet to apply the settings push" 30 sensor_is "$TABULA_TEXT" "$TABULA_START_PATH"
sleep 3
sensor_is "$TABULA_PAGE" "$page_before" \
    || fail "changing the start path navigated: $(ha_state "$TABULA_PAGE") != $page_before"
echo "-- the page stayed on $page_before"

step "3. go_home returns from another view (F-NAV-03, F-HA-08)"
ha_call tabula navigate "{\"path\": \"$TABULA_OTHER_PATH\"}"
wait_for "the tablet to reach $TABULA_OTHER_PATH" 30 sensor_is "$TABULA_PAGE" "$TABULA_OTHER_PATH"
ha_call button press "{\"entity_id\": \"$TABULA_BUTTON\"}"
wait_for "the go-home button to land on $TABULA_START_PATH" 30 \
    sensor_is "$TABULA_PAGE" "$TABULA_START_PATH"
echo "-- the button returned the tablet to $TABULA_START_PATH"

step "4. a cold start lands on the start target (F-DSH-02)"
adb shell am force-stop "$pkg"
sleep 3
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null
wait_for "the relaunched app to land on $TABULA_START_PATH" 120 \
    sensor_is "$TABULA_PAGE" "$TABULA_START_PATH"
ask "On the tablet: is it showing the $TABULA_START_PATH view?"

step "5. an app restart lands on the start target too (F-NAV-03)"
ha_call tabula navigate "{\"path\": \"$TABULA_OTHER_PATH\"}"
wait_for "the tablet to reach $TABULA_OTHER_PATH" 30 sensor_is "$TABULA_PAGE" "$TABULA_OTHER_PATH"
ha_call button press "{\"entity_id\": \"$TABULA_RESTART\"}"
wait_for "the restarted app to land on $TABULA_START_PATH" 120 \
    sensor_is "$TABULA_PAGE" "$TABULA_START_PATH"
echo "-- the app restarted on $TABULA_START_PATH"

step "6. the idle return fires, behind a screensaver included (F-DSH-15)"
ha_call number set_value "{\"entity_id\": \"$TABULA_NUMBER\", \"value\": 15}"
ha_call tabula navigate "{\"path\": \"$TABULA_OTHER_PATH\"}"
wait_for "the tablet to reach $TABULA_OTHER_PATH" 30 sensor_is "$TABULA_PAGE" "$TABULA_OTHER_PATH"
ha_call tabula screensaver "{\"active\": true}"
echo "-- screensaver on; waiting out the 15 s return delay"
sleep 20
sensor_is "$TABULA_PAGE" "$TABULA_START_PATH" \
    || fail "the idle return did not fire behind the screensaver (page: $(ha_state "$TABULA_PAGE"))"
echo "-- the dashboard returned to $TABULA_START_PATH while covered"
ask "On the tablet: did the panel stay dark (the screensaver never lifted) while the page changed underneath?"

step "7. service log for the record"
adb shell logcat -d -v time -s "$service_tag:I" "TabulaDashboard:I" | tail -40

completed=1
