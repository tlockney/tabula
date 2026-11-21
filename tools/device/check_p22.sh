#!/usr/bin/env bash
# P2.2 hardware check (docs/packets/P2.2-dashboard-webview.md):
#   the dashboard loads with the device credential; internal links stay in
#   the WebView; screen off/on doesn't reload.
#
# It also records the device's WebView version, the measurement ADR 0001
# item 6 has been waiting for.
#
# Prerequisites: the tablet is provisioned (tools/device/provision.sh),
# paired by hand and connected (the app shows the dashboard), and the
# environment has:
#   TABULA_HA_URL    e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN  a long-lived access token from an ADMIN user (the
#                    owner's; the registry list command is admin-only)
#   ANDROID_SERIAL   optional, picks the tablet when several are attached
#
# Paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
log_tags="TabulaService:I TabulaDashboard:I"

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"

here="$(cd "$(dirname "$0")" && pwd)"

ha_query() { # ha_query [args...] - run tools/device/ha_query.py in the repo env
    UV_CONFIG_FILE="$here/../uv.toml" \
        uv run --directory "$here/../.." python tools/device/ha_query.py \
        --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$@"
}

step() { echo; echo "== $*"; }
wait_for_log() { # wait_for_log <pattern> <timeout seconds>
    local pattern="$1" timeout="$2" waited=0
    while (( waited < timeout )); do
        if adb shell logcat -d -s "$log_tags" 2>/dev/null \
            | grep -F "$pattern" | tail -1 | tee /dev/stderr; then
            return 0
        fi
        sleep 5; waited=$((waited + 5))
    done
    echo "TIMED OUT waiting for '$pattern' after ${timeout}s" >&2
    return 1
}

step "1. service running and connected"
adb shell pidof "$pkg"
adb shell dumpsys activity services "$pkg" | grep -A2 -i tabula || true
echo "waiting up to 120s for the CONNECTED phase"
wait_for_log "phase: CONNECTED" 120

step "2. WebView version (ADR 0001 item 6)"
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.name
adb shell getprop ro.build.mktg.fireos || true
adb shell pm list packages | grep -iE 'webview|chrome' || true
adb shell dumpsys webviewupdate || true
adb shell dumpsys package com.amazon.webview.chromium 2>/dev/null \
    | grep -E 'versionName|versionCode|lastUpdateTime|firstInstallTime' || true
adb shell dumpsys package com.android.webview 2>/dev/null \
    | grep -E 'versionName|versionCode|lastUpdateTime' || true

step "3. dashboard loaded with the device credential"
device_id="$(
    adb shell run-as "$pkg" cat shared_prefs/tabula_device.xml 2>/dev/null \
        | sed -n 's/.*name="device_id">\([0-9a-f-]\{36\}\)<.*/\1/p' | head -1
)"
echo "device_id: ${device_id:-NOT FOUND}"
[[ -n "$device_id" ]] || { echo "run-as failed: is this the debug build?" >&2; exit 1; }
adb shell logcat -c
echo "waiting up to 180s for the dashboard to load (page finished log line)"
wait_for_log "dashboard: loaded" 180
echo "-- the external-auth bridge answered the frontend:"
adb shell logcat -d -s "$log_tags" | grep -F "bridge getExternalAuth: answered" | tail -1 || true
echo "-- HA's entities for the tablet (the current-page sensor reads the loaded page):"
ha_query "$device_id" | grep -E "current_page|tabula devices" || true

step "4. internal links stay in the WebView"
# navigate from HA to a panel every user can open; the page must load inside
# the app's WebView, with no other app coming to the front
adb shell logcat -c
ha_query "$device_id" --navigate /profile
echo "waiting up to 60s for the /profile page to load in the WebView"
wait_for_log "dashboard: loaded /profile" 60
echo "-- the window focus is still the Tabula activity (no browser took over):"
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2
adb shell dumpsys window | grep -E "mCurrentFocus" | grep -F "$pkg" > /dev/null \
    || { echo "the focus left net.lockney.tabula; a link opened outside the WebView" >&2; exit 1; }
echo "-- HA's current-page sensor followed:"
ha_query "$device_id" | grep -F "current_page" || true

step "5. screen off/on doesn't reload"
adb shell logcat -c
echo "-- screen off"
adb shell input keyevent KEYCODE_POWER
sleep 15
echo "-- screen on"
adb shell input keyevent KEYCODE_POWER
sleep 15
if adb shell logcat -d -s "$log_tags" 2>/dev/null | grep -F "dashboard: loaded" \
    | grep -vF "dashboard: loading" | tee /dev/stderr; then
    echo "FAIL: the dashboard reloaded during screen off/on" >&2
    exit 1
fi
echo "-- no 'dashboard: loaded' line after the toggle: no reload"
adb shell dumpsys window | grep -E "mCurrentFocus" | head -1
ha_query "$device_id" | grep -F "current_page"

step "6. full dashboard and service log for the record"
adb shell logcat -d -s "$log_tags"

echo
echo "P2.2 hardware check complete; paste everything above into the packet Result."
