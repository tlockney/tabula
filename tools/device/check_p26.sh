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
# The script cleans up after itself: a trap puts HA back (option A), the
# tablet's Wi-Fi back (option B and step 8) and the screen settings step 2
# changed back, on any failure, Ctrl-C and normal exit.
#
# USB note: option B and step 8 disable the tablet's Wi-Fi through adb, so
# the adb connection must be over USB. With adb over Wi-Fi the script
# refuses those steps - cutting the tablet's Wi-Fi would also cut adb and
# strand the tablet (use option A for step 3; step 8 needs USB either way).
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
# percent -> the 0-255 system scale, as ScreenController.toSystemLevel
# computes it: half-up, not Python's banker's round (review 2, nit 2)
expected_level() { python3 -c "import sys; p=int(sys.argv[1]); print(max(1, int(p*255/100 + 0.5)))" "$1"; }
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
device_id() {
    adb shell run-as "$pkg" cat shared_prefs/tabula_device.xml 2>/dev/null \
        | sed -n 's/.*name="device_id">\([0-9a-f-]\{36\}\)<.*/\1/p' | head -1
}

# --- what the script must restore on any exit (PR review, must-fix 1) -------
# Flags for what this script took down; restore() puts each back. They are
# cleared by the matching "up" call, so a normal run's exit trap is a no-op.
ha_is_down_by_us=0   # option A: this script stopped HA
net_down_by_us=0     # option B / step 8: this script cut the tablet's Wi-Fi
settings_touched=0   # step 2 changed the tablet's stored screen settings
orig_light="on None" # the light's state and brightness, as found
orig_adaptive="unset"  # the adaptive switch's state, as found (or "unset")

usb_adb() { # true when the adb transport is USB; Wi-Fi adb would be cut with the tablet
    local serialno devpath line
    serialno="$(adb get-serialno 2>/dev/null | tr -d '\r')"
    devpath="$(adb get-devpath 2>/dev/null | tr -d '\r')"
    if [[ "$serialno" =~ :[0-9]+$ ]]; then
        echo "-- adb transport: $serialno is a host:port form (not USB)" >&2
        return 1
    fi
    if [[ "$devpath" == usb:* ]]; then return 0; fi
    line="$(adb devices -l | grep -F "$serialno" | head -1)"
    if [[ "$line" == *" usb:"* ]]; then return 0; fi
    echo "-- adb devices line for $serialno: ${line:-<none>}" >&2
    return 1
}
require_usb_adb() { # before anything that disables the tablet's Wi-Fi (must-fix 3)
    usb_adb && return 0
    fail "adb is not over USB, so disabling the tablet's Wi-Fi would also cut adb and strand the tablet. Connect the tablet over USB, or use option A (TABULA_HA_STOP_CMD) for step 3; step 8 needs USB either way."
}

network() { # network <down|up> on the tablet itself (check_p21.sh's fallback)
    if [[ "$1" == down ]]; then
        require_usb_adb # Wi-Fi adb would be cut with the tablet (must-fix 3)
        net_down_by_us=1
        if adb shell cmd connectivity airplane-mode enable 2>/dev/null; then
            echo "-- airplane mode on"
        else
            adb shell svc wifi disable
            echo "-- wifi disabled"
        fi
    else
        net_down_by_us=0
        if adb shell cmd connectivity airplane-mode disable 2>/dev/null; then
            echo "-- airplane mode off"
        else
            adb shell svc wifi enable
            echo "-- wifi enabled"
        fi
    fi
}

ha_down() {
    if [[ -n "${TABULA_HA_STOP_CMD:-}" ]]; then
        echo "-- stopping HA with: $TABULA_HA_STOP_CMD"
        ha_is_down_by_us=1
        bash -c "$TABULA_HA_STOP_CMD"
        echo "-- HA stop command returned; HA should be down now"
    else
        echo "-- TABULA_HA_STOP_CMD unset; taking option B: the tablet's route to HA is cut instead"
        network down
    fi
}
ha_up() {
    ha_is_down_by_us=0
    if [[ -n "${TABULA_HA_START_CMD:-}" ]]; then
        echo "-- starting HA with: $TABULA_HA_START_CMD"
        bash -c "$TABULA_HA_START_CMD"
    else
        network up
    fi
}
ha_http_code() { # HA's HTTP status for /api/; 000 means it did not answer at all
    curl -s -o /dev/null -m 5 -w '%{http_code}' "${TABULA_HA_URL%/}/api/" || true
}
ha_answers() { # the Mac reached HA's API at all; a 401 without a token still means HA is up (must-fix 2)
    [[ "$(ha_http_code)" != 000 ]]
}
ha_is_down() { # the Mac's own check that HA is really unreachable (option A) or the tablet is (B)
    if [[ -n "${TABULA_HA_STOP_CMD:-}" ]]; then
        if ha_answers; then return 1; else return 0; fi
    else
        # option B: HA is up but the tablet has no route; HA's entities read unavailable
        ha_state "$TABULA_SCREENSAVER" | grep -qx unavailable
    fi
}
boot_completed() { [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; }

ha_light_state() { # a light's state and brightness attribute, e.g. "on 153"
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["state"], s["attributes"].get("brightness"))'
}

restore_settings() { # put back what step 2 changed (PR review, should-fix)
    (( settings_touched )) || return 0
    settings_touched=0
    echo "-- restore: putting the tablet's screen settings back (was light='$orig_light', adaptive='$orig_adaptive')" >&2
    # the service calls need HA answering and the tablet re-registered
    local waited=0
    until ha_answers && ha_available "$TABULA_LIGHT"; do
        (( waited >= 240 )) && {
            echo "-- restore: HA or the tablet did not come back within 240s;" >&2
            echo "   set the screen light and the adaptive brightness switch back by hand: light='$orig_light', adaptive='$orig_adaptive'" >&2
            return 0
        }
        sleep 5; waited=$((waited + 5))
    done
    local state brightness body
    read -r state brightness <<< "$orig_light"
    if [[ "$state" == "off" ]]; then
        ha_call light turn_off "{\"entity_id\": \"$TABULA_LIGHT\"}" \
            || echo "-- restore: light.turn_off failed; set it back by hand" >&2
    else
        body="{\"entity_id\": \"$TABULA_LIGHT\""
        [[ "$brightness" != "None" ]] && body+=", \"brightness\": $brightness"
        body+="}"
        ha_call light turn_on "$body" \
            || echo "-- restore: light.turn_on failed; set it back by hand" >&2
    fi
    if [[ "$orig_adaptive" != "unset" ]]; then
        if [[ "$orig_adaptive" == "on" ]]; then
            ha_call switch turn_on "{\"entity_id\": \"$TABULA_ADAPTIVE\"}" \
                || echo "-- restore: switch.turn_on failed; set it back by hand" >&2
        else
            ha_call switch turn_off "{\"entity_id\": \"$TABULA_ADAPTIVE\"}" \
                || echo "-- restore: switch.turn_off failed; set it back by hand" >&2
        fi
    fi
    echo "-- restore: screen settings back" >&2
}

restore() { # on any exit: bring back what this script took down (idempotent)
    local restored=()
    if (( ha_is_down_by_us )); then
        ha_is_down_by_us=0
        ha_up || true
        restored+=("HA")
    fi
    if (( net_down_by_us )); then
        net_down_by_us=0
        network up || true
        restored+=("the tablet's Wi-Fi")
    fi
    if (( settings_touched )); then
        restore_settings || true
        restored+=("the tablet's screen settings")
    fi
    if (( ${#restored[@]} )); then
        echo "-- restore: restored ${restored[*]}" >&2
    fi
    return 0
}
# EXIT restores after any failure; INT/TERM (Ctrl-C) restore and then exit -
# a bare INT trap would run restore and leave the script running.
trap restore EXIT
trap 'restore; exit 130' INT
trap 'restore; exit 143' TERM

step "1. boot check (Phase 2 exit: the service starts on boot)"
if [[ "${TABULA_SKIP_REBOOT:-0}" == "1" ]]; then
    echo "-- TABULA_SKIP_REBOOT=1: skipping the reboot; Tabula must already be running"
    # no fresh boot lines to preserve, so clear the buffer: the log waits in
    # step 2 must not match the previous run's lines (review 2, blocker 2)
    adb shell logcat -c
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
    # no logcat clear here: the buffer is fresh after the reboot, and the
    # boot-time registration and phase lines are the evidence step 2 waits
    # for - the app registers once, at boot, and does not log it again
    # when the activity starts (review 2, blocker 2)
    wait_for_log "dashboard: loaded" 90
    ask "After the reboot: does Tabula show the dashboard with the pairing intact (no pairing screen)?"
fi
pid_before="$(adb shell pidof "$pkg" | tr -d '\r')"
[[ -n "$pid_before" ]] || fail "Tabula is not running"

step "2. online baseline"
adb devices -l
echo "-- device_id: $(device_id)"
if [[ "${TABULA_SKIP_REBOOT:-0}" != "1" ]]; then
    # the registration and phase lines are fresh from the boot (the buffer
    # was never cleared); wait for them, plus live HA-side evidence below
    wait_for_log "registered with HA device" 120
    wait_for_log "phase: CONNECTED" 30
fi
# live evidence either way, and the only log-free gate in a skip rerun: the
# tablet's entities are available in HA right now
wait_for "the screensaver switch to be available in HA" 120 ha_available "$TABULA_SCREENSAVER"
# Step 2 changes the stored screen settings (a manual level writes
# screen.brightness and adaptive off, ADR 0011); remember the originals so
# the exit trap can put them back (PR review, should-fix).
orig_light="$(ha_light_state "$TABULA_LIGHT")"
if [[ -n "${TABULA_ADAPTIVE:-}" ]]; then
    orig_adaptive="$(ha_state "$TABULA_ADAPTIVE")"
else
    orig_adaptive="unset"
fi
settings_touched=1
echo "-- screen settings as found (restored on exit): light='$orig_light', adaptive='$orig_adaptive'"
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
# the upper bound proves the spacing: equal jitter capped at BACKOFF_MAX 60s
# (ADR 0002) yields about 7-8 attempts in 90s; a tight loop would log dozens
(( attempts <= 10 )) || fail "$attempts reconnect attempts in 90s: the backoff is not spacing them"
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
level_after="$(system_level)"
if [[ -n "${TABULA_ADAPTIVE:-}" ]]; then
    # adaptive brightness restores the room-decided level, which can
    # legitimately equal the screensaver level in a dim room; the ask
    # below is the gate in that case
    echo "-- system brightness after the tap: $level_after (adaptive decides it; was $saver_level)"
else
    [[ "$level_after" != "$saver_level" ]] \
        || fail "the backlight stayed at the screensaver level ($saver_level) after the tap"
    echo "-- system brightness after the tap: $level_after (left the screensaver level $saver_level)"
fi
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
# the token travels in the environment (already exported), not argv (review 2, nit 4)
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" "$(device_id)"
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
