#!/usr/bin/env bash
# P2.6 hardware check (docs/packets/P2.6-offline-first-check.md):
#   with HA down the screensaver, clock, brightness curve and cached
#   dashboard behave sensibly, and the tablet re-dials and recovers on its
#   own when HA returns (F-HA-04, F-DSH-04, F-SCR-05, F-SSV-01; this is the
#   plan 7.3 Phase 2 exit check).
#
# Prerequisites: the tablet runs a P2.6 build, is provisioned
# (tools/device/provision.sh) and paired, the app is in the foreground with
# the panel on, and the environment has:
#   TABULA_HA_URL            e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN          a long-lived access token (an ADMIN user's; the
#                            device-registry list is admin-only)
#   TABULA_SCREENSAVER       the tablet's screensaver switch, e.g. switch.office_kiosk_screensaver
#   TABULA_NEXT              its next-screensaver sensor, e.g. sensor.office_kiosk_next_screensaver
#   TABULA_LIGHT             its screen light, e.g. light.office_kiosk_screen
#   TABULA_CURRENT_PAGE      its current-page sensor, e.g. sensor.office_kiosk_current_page
#   TABULA_ADAPTIVE          optional: its adaptive-brightness switch; step 6
#                            (the offline brightness curve) runs only when set
#   TABULA_SAVER_BRIGHTNESS_PCT  optional: the screensaver brightness setting,
#                            percent (default 20, ADR 0002); set it if HA
#                            shows the tablet's number entity at another value
#   TABULA_CONNECTIVITY      optional: its connectivity binary sensor
#   ANDROID_SERIAL           optional, picks the tablet when several are attached
#   TABULA_SKIP_REBOOT=1     optional: skip step 1 (the boot check); the
#                            tablet must then already be running Tabula
#
# Taking HA down (step 3), two options, both documented in DEVICE.md:
#   A) set TABULA_HA_STOP_CMD and TABULA_HA_START_CMD to commands that stop
#      and start HA itself, e.g.
#        TABULA_HA_STOP_CMD='ssh ha@homeassistant "ha core stop"'
#        TABULA_HA_START_CMD='ssh ha@homeassistant "ha core start"'
#      The tablet's network stays up: the truer test, because HA is
#      unreachable while everything else is not.
#   B) the default, when TABULA_HA_STOP_CMD is unset: block the device's
#      route to HA by switching the tablet's Wi-Fi off (svc wifi disable),
#      the same fallback check_p21.sh uses. The same offline paths run, but
#      the tablet is offline entirely, and HA-side evidence (unavailable
#      entities) is read from the Mac instead.
#
# The script never touches the tablet's pairing or app data. Paste the whole
# output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
here="$(cd "$(dirname "$0")" && pwd)"
service_tag=TabulaService
dash_tag=TabulaDashboard

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_SCREENSAVER:?set TABULA_SCREENSAVER to the tablet screensaver switch entity id}"
: "${TABULA_NEXT:?set TABULA_NEXT to the tablet next-screensaver sensor entity id}"
: "${TABULA_LIGHT:?set TABULA_LIGHT to the tablet screen light entity id}"
: "${TABULA_CURRENT_PAGE:?set TABULA_CURRENT_PAGE to the tablet current-page sensor entity id}"

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
system_level() { adb shell settings get system screen_brightness | tr -d '\r'; }
# percent -> the 0-255 system scale, as ScreenController.toSystemLevel computes it
expected_level() { python3 -c "import sys; p=int(sys.argv[1]); print(max(1, round(p*255/100)))" "$1"; }
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
service_log() { adb shell logcat -d -v time -s "$service_tag:I" "$service_tag:W"; }
service_log_lines() { # service_log_lines <pattern> -> count of matching lines
    service_log | grep -cF "$1" || true
}
log_tags=(-s "$service_tag:I" "$service_tag:W" "$dash_tag:I" "$dash_tag:W")
wait_for_log() { # wait_for_log <pattern> <timeout seconds>
    local pattern="$1" timeout="$2" waited=0
    until adb shell logcat -d "${log_tags[@]}" 2>/dev/null | grep -F "$pattern" | tail -1 | tee /dev/stderr; do
        (( waited >= timeout )) && fail "no '$pattern' log line within ${timeout}s"
        sleep 5; waited=$((waited + 5))
    done
}
network() { # network <down|up> on the tablet itself (check_p21.sh's fallback)
    if [[ "$1" == down ]]; then
        adb shell cmd connectivity airplane-mode enable 2>/dev/null \
            && echo "-- airplane mode on" || { adb shell svc wifi disable; echo "-- wifi disabled"; }
    else
        adb shell cmd connectivity airplane-mode disable 2>/dev/null \
            && echo "-- airplane mode off" || { adb shell svc wifi enable; echo "-- wifi enabled"; }
    fi
}
device_id() {
    adb shell run-as "$pkg" cat shared_prefs/tabula_device.xml 2>/dev/null \
        | sed -n 's/.*name="device_id">\([0-9a-f-]\{36\}\)<.*/\1/p' | head -1
}

ha_down() {
    if [[ -n "${TABULA_HA_STOP_CMD:-}" ]]; then
        echo "-- stopping HA with: $TABULA_HA_STOP_CMD"
        bash -c "$TABULA_HA_STOP_CMD"
        echo "-- HA stop command returned; HA should be down now"
    else
        echo "-- TABULA_HA_STOP_CMD unset; taking option B: the tablet's route to HA is cut instead"
        network down
    fi
}
ha_up() {
    if [[ -n "${TABULA_HA_START_CMD:-}" ]]; then
        echo "-- starting HA with: $TABULA_HA_START_CMD"
        bash -c "$TABULA_HA_START_CMD"
    else
        network up
    fi
}
ha_is_down() { # the Mac's own check that HA is really unreachable (option A) or the tablet is (B)
    if [[ -n "${TABULA_HA_STOP_CMD:-}" ]]; then
        if curl -fsS -m 5 "${TABULA_HA_URL%/}/api/" > /dev/null 2>&1; then
            return 1
        else
            return 0
        fi
    else
        # option B: HA is up but the tablet has no route; HA's entities read unavailable
        ha_state "$TABULA_SCREENSAVER" | grep -qx unavailable
    fi
}
ha_answers() { # the Mac can reach HA's API (meaningful in option A; trivial in B)
    curl -fsS -m 5 "${TABULA_HA_URL%/}/api/" > /dev/null 2>&1
}
boot_completed() { [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; }

step "1. boot check (Phase 2 exit: the service starts on boot)"
if [[ "${TABULA_SKIP_REBOOT:-0}" == "1" ]]; then
    echo "-- TABULA_SKIP_REBOOT=1: skipping the reboot; Tabula must already be running"
else
    adb reboot
    echo "-- rebooting the tablet"
    adb wait-for-device
    wait_for "boot to complete" 180 boot_completed
    # the boot receiver starts the service; nobody touches the tablet
    wait_for "the Tabula service to start by itself" 120 adb shell pidof "$pkg"
    echo "-- service pid on boot: $(adb shell pidof "$pkg" | tr -d '\r')"
    read -r -p "Unlock the tablet if it asks, then press Enter (Tabula is launched next) " _
    adb shell am start -n "$pkg/.MainActivity"
    adb shell logcat -c
    wait_for_log "dashboard: loaded" 90
    ask "After the reboot: does Tabula show the dashboard with the pairing intact (no pairing screen)?"
fi
pid_before="$(adb shell pidof "$pkg" | tr -d '\r')"
[[ -n "$pid_before" ]] || fail "Tabula is not running"

step "2. online baseline"
adb devices -l
echo "-- device_id: $(device_id)"
wait_for_log "registered with HA device" 120
wait_for_log "phase: CONNECTED" 30
ha_call light turn_on "{\"entity_id\": \"$TABULA_LIGHT\", \"brightness_pct\": 60}"
wait_for "system brightness $(expected_level 60) (60 %)" 15 test "x$(system_level)" = "x$(expected_level 60)"
# the manual level turns adaptive off (ADR 0010 item 5); turning it on now
# lets the room drive the level for the offline curve test in step 6
if [[ -n "${TABULA_ADAPTIVE:-}" ]]; then
    ha_call switch turn_on "{\"entity_id\": \"$TABULA_ADAPTIVE\"}"
    echo "-- adaptive brightness on; the room drives the level from here"
else
    echo "-- TABULA_ADAPTIVE unset: step 6 will be skipped"
fi
next="$(ha_state "$TABULA_NEXT")"
[[ "$next" != unavailable && "$next" != unknown ]] || fail "next screensaver reads $next"
wait_s="$(seconds_until "$next")"
echo "-- screensaver switch: $(ha_state "$TABULA_SCREENSAVER") (off); next screensaver: $next (in ${wait_s}s)"
page_before="$(ha_state "$TABULA_CURRENT_PAGE")"
echo "-- current page: $page_before; HA light: $(ha_state "$TABULA_LIGHT")"
loaded_before="$(adb shell logcat -d -s "$dash_tag:I" "$dash_tag:W" | grep -cF 'dashboard: loaded' || true)"
echo "-- 'dashboard: loaded' lines so far: $loaded_before"

step "3. HA goes down; the app keeps its head"
ha_down
wait_for "HA to be unreachable (from the Mac)" 60 ha_is_down
echo "-- HA unreachable: yes"
adb shell logcat -c
wait_for_log "connection lost; reconnecting with backoff" 90
pid_now="$(adb shell pidof "$pkg" | tr -d '\r')"
[[ "$pid_now" == "$pid_before" ]] || fail "the app process restarted (was $pid_before, now $pid_now): a crash loop, not offline behavior"
echo "-- the app process is unchanged ($pid_now); no crash loop"
# reconnect attempts space themselves out (equal-jitter backoff, ADR 0002)
sleep 90
echo "-- reconnect attempts while HA is down (timestamps show the backoff):"
service_log | grep -F "connection lost; reconnecting with backoff" || true
attempts="$(service_log_lines "connection lost; reconnecting with backoff")"
(( attempts >= 3 )) || fail "only $attempts reconnect attempts in 90s: the app is not re-dialing"
echo "-- $attempts attempts in 90s, spacing apart"
crashes="$(adb shell logcat -d -b crash | grep -c "$pkg" || true)"
(( crashes == 0 )) || fail "$crashes crash-buffer lines mention $pkg"
echo "-- crash buffer mentions of $pkg: $crashes"
if [[ -z "${TABULA_HA_STOP_CMD:-}" ]]; then
    echo "-- HA side (option B: HA is up, the tablet has no route): screensaver switch reads $(ha_state "$TABULA_SCREENSAVER")"
    ha_state "$TABULA_SCREENSAVER" | grep -qx unavailable || fail "entities did not go unavailable (F-HA-04)"
    echo "-- entities read unavailable; the registry entry stays (F-HA-04)"
fi

step "4. the screensaver and its clock work with HA down"
echo "-- waiting for the idle timeout (next screensaver was: $next; do not touch the tablet)"
echo "   (the offline start/stop tabula_screensaver events are dropped by design, ADR 0013;"
echo "    the switch state after recovery in step 7 is the evidence instead)"
saver_level="$(expected_level "${TABULA_SAVER_BRIGHTNESS_PCT:-20}")" # screensaver brightness default 20 % (ADR 0002)
wait_for "the backlight to drop to the screensaver level ($saver_level)" $((wait_s + 60)) \
    test "x$(system_level)" = "x$saver_level"
echo "-- system brightness while it shows: $(system_level) (screensaver level $saver_level)"
ask "With HA down: does the tablet show a clock with the current time and today's date?"

step "5. the cached dashboard survives the outage (F-DSH-04)"
tap_centre
sleep 3
echo "-- system brightness after the tap: $(system_level) (back from $saver_level)"
ask "Is the dashboard back - the same page as before, served from the WebView's own cache?"
loaded_now="$(adb shell logcat -d -s "$dash_tag:I" "$dash_tag:W" | grep -cF 'dashboard: loaded' || true)"
(( loaded_now <= loaded_before )) \
    || fail "the dashboard reloaded during the outage ($loaded_before -> $loaded_now loads): F-DSH-04 says leave the page up"
echo "-- 'dashboard: loaded' lines still: $loaded_now (no reload; the page was never re-fetched)"

step "6. the brightness curve works with HA down (F-SCR-05)"
if [[ -n "${TABULA_ADAPTIVE:-}" ]]; then
    read -r -p "Cover the tablet's light sensor with a finger, then press Enter " _
    sleep 5
    dark="$(system_level)"
    echo "-- covered: system brightness $dark"
    read -r -p "Uncover it and shine a light (a phone torch) at it, then press Enter " _
    sleep 5
    bright="$(system_level)"
    echo "-- lit: system brightness $bright"
    (( bright > dark )) || fail "the panel did not follow the light sensor with HA down ($dark -> $bright)"
    echo "-- the curve followed the room with HA down ($dark -> $bright)"
else
    echo "-- TABULA_ADAPTIVE unset: skipped (the P2.3 check already covers the offline curve)"
fi

step "7. HA returns; the device re-dials and recovers by itself"
adb shell logcat -c
ha_up
echo "waiting up to 240s for re-registration (HA boot + BACKOFF_MAX 60s re-dial)"
wait_for_log "registered with HA device" 240
wait_for "HA to answer the Mac again" 120 ha_answers
wait_for "the screensaver switch to be available again" 120 ha_available "$TABULA_SCREENSAVER"
echo "-- entities refreshed from the device's current state (F-HA-04):"
echo "   screensaver switch: $(ha_state "$TABULA_SCREENSAVER") (expect off; it was dismissed offline)"
ha_state "$TABULA_SCREENSAVER" | grep -qx off || fail "the screensaver switch did not refresh to off"
echo "   current page: $(ha_state "$TABULA_CURRENT_PAGE") (was $page_before)"
ha_available "$TABULA_CURRENT_PAGE" || fail "the current-page sensor is unavailable"
[[ -n "${TABULA_CONNECTIVITY:-}" ]] && { echo "   connectivity: $(ha_state "$TABULA_CONNECTIVITY")"; ha_state "$TABULA_CONNECTIVITY" | grep -qx on || fail "connectivity reads $(ha_state "$TABULA_CONNECTIVITY")"; }
if [[ -n "${TABULA_ADAPTIVE:-}" ]]; then
    echo "   adaptive switch: $(ha_state "$TABULA_ADAPTIVE")"
    ha_state "$TABULA_ADAPTIVE" | grep -qx on || fail "the adaptive switch did not survive the outage"
fi
echo "   HA light: $(ha_state "$TABULA_LIGHT")"
level_now="$(system_level)"
echo "-- system brightness now: $level_now"
echo "-- HA's registry and entity states after recovery:"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$(device_id)"
loaded_now="$(adb shell logcat -d -s "$dash_tag:I" "$dash_tag:W" | grep -cF 'dashboard: loaded' || true)"
(( loaded_now <= loaded_before )) \
    || fail "the dashboard reloaded during recovery ($loaded_before -> $loaded_now loads)"
echo "-- the WebView rode the whole outage on one load ($loaded_before -> $loaded_now)"
ask "Is the dashboard live again (the frontend's reconnect banner gone, cards update)?"

step "8. the reconnect backoff reset after a stable minute (BACKOFF_RESET_AFTER)"
echo "-- waiting 90s so the recovered connection counts as stable (BACKOFF_RESET_AFTER is 60s, ADR 0002)"
sleep 90
adb shell logcat -c
network down
wait_for_log "connection lost; reconnecting with backoff" 60
network up
start="$(date +%s)"
echo "waiting up to 30s for re-registration (the first retry after a stable connection is at BACKOFF_BASE)"
wait_for_log "registered with HA device" 30
took=$(( $(date +%s) - start ))
echo "-- re-registered ${took}s after Wi-Fi came back (a stuck backoff counter would take up to 60s+)"
(( took <= 30 )) || fail "the re-dial took ${took}s: the backoff counter did not reset"

step "9. full service and dashboard log for the record"
service_log | tail -80
adb shell logcat -d -v time -s "$dash_tag:I" "$dash_tag:W" | tail -20

echo
echo "P2.6 hardware check complete; paste everything above into the packet Result."
