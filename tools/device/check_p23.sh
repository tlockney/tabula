#!/usr/bin/env bash
# P2.3 hardware check (docs/packets/P2.3-screen-control.md):
#   the HA light entity controls the real panel; adaptive brightness keeps
#   working with the network down; a manual change turns adaptive off.
#
# Prerequisites: the tablet runs a P2.3 build, is provisioned
# (tools/device/provision.sh: WRITE_SETTINGS and the device admin) and paired,
# the app is in the foreground, and the environment has:
#   TABULA_HA_URL           e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN         a long-lived access token (any user that may call services)
#   TABULA_LIGHT            the tablet's screen light, e.g. light.office_kiosk_screen
#   TABULA_ADAPTIVE         its adaptive-brightness switch, e.g. switch.office_kiosk_adaptive_brightness
#   ANDROID_SERIAL          optional, picks the tablet when several are attached
#
# Step 4 asks the verifier to cover and uncover the light sensor (on the Fire
# HD 10 Plus it sits next to the front camera). Paste the whole output into
# the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_LIGHT:?set TABULA_LIGHT to the tablet screen light entity id}"
: "${TABULA_ADAPTIVE:?set TABULA_ADAPTIVE to the adaptive-brightness switch entity id}"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_call() { # ha_call <domain> <service> <json body>
    curl -fsS -X POST \
        -H "Authorization: Bearer $TABULA_HA_TOKEN" -H "Content-Type: application/json" \
        -d "$3" "${TABULA_HA_URL%/}/api/services/$1/$2" > /dev/null
    echo "-- called $1.$2 $3"
}
ha_state() { # ha_state <entity_id> -> prints state and brightness attribute
    curl -fsS -H "Authorization: Bearer $TABULA_HA_TOKEN" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["state"], s["attributes"].get("brightness"))'
}
system_level() { adb shell settings get system screen_brightness | tr -d '\r'; }
wakefulness() { adb shell dumpsys power | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1; }
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
level_is() { [[ "$(system_level)" == "$1" ]]; }
panel_off() { [[ "$(wakefulness)" != Awake ]]; }
panel_on() { [[ "$(wakefulness)" == Awake ]]; }
ha_state_is() { [[ "$(ha_state "$1" | cut -d' ' -f1)" == "$2" ]]; }
ha_available() { [[ "$(ha_state "$1" | cut -d' ' -f1)" != unavailable ]]; }

# Wi-Fi off/on (same fallback reasoning as check_p21.sh)
network() { # network <down|up>
    if [[ "$1" == down ]]; then
        adb shell cmd connectivity airplane-mode enable 2>/dev/null \
            && echo "-- airplane mode on" || { adb shell svc wifi disable; echo "-- wifi disabled"; }
    else
        adb shell cmd connectivity airplane-mode disable 2>/dev/null \
            && echo "-- airplane mode off" || { adb shell svc wifi enable; echo "-- wifi enabled"; }
    fi
}

step "1. permissions and hardware"
adb devices -l
adb shell pidof "$pkg" || fail "Tabula is not running"
echo "WRITE_SETTINGS: $(adb shell appops get "$pkg" WRITE_SETTINGS | tr -d '\r')"
adb shell dumpsys device_policy | grep -A1 -i "ScreenAdminReceiver" || echo "device admin: NOT ACTIVE (panel off will be refused)"
adb shell dumpsys sensorservice | grep -i -m3 "light" || echo "no light sensor listed (adaptive_brightness will not be declared)"
echo "brightness mode: $(adb shell settings get system screen_brightness_mode | tr -d '\r') (0 = manual)"

step "2. the light entity controls the panel"
ha_call switch turn_off "{\"entity_id\": \"$TABULA_ADAPTIVE\"}"
for pct in 20 80; do
    ha_call light turn_on "{\"entity_id\": \"$TABULA_LIGHT\", \"brightness_pct\": $pct}"
    want="$(expected_level "$pct")"
    wait_for "system brightness $want ($pct %)" 10 level_is "$want"
    echo "-- HA light: $(ha_state "$TABULA_LIGHT")"
done

step "3. panel off and on"
ha_call light turn_off "{\"entity_id\": \"$TABULA_LIGHT\"}"
wait_for "the panel to go off" 10 panel_off
echo "-- wakefulness: $(wakefulness); HA light: $(ha_state "$TABULA_LIGHT")"
ha_call light turn_on "{\"entity_id\": \"$TABULA_LIGHT\"}"
wait_for "the panel to come back" 10 panel_on
echo "-- wakefulness: $(wakefulness); HA light: $(ha_state "$TABULA_LIGHT")"
adb shell dumpsys window | grep -m1 -E "mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen" || true

step "4. adaptive brightness with the network down"
ha_call switch turn_on "{\"entity_id\": \"$TABULA_ADAPTIVE\"}"
sleep 3
echo "-- adaptive switch in HA: $(ha_state "$TABULA_ADAPTIVE")"
network down
sleep 5
read -r -p "Cover the tablet's light sensor with a finger, then press Enter " _
sleep 5
dark="$(system_level)"
echo "-- covered: system brightness $dark"
read -r -p "Uncover it and shine a light (a phone torch) at it, then press Enter " _
sleep 5
bright="$(system_level)"
echo "-- lit: system brightness $bright"
network up
(( bright > dark )) || fail "the panel did not follow the light sensor offline ($dark -> $bright)"
echo "-- adaptive brightness followed the room with the network down ($dark -> $bright)"

step "5. a manual change turns adaptive off"
echo "waiting up to 120s for the tablet to reconnect"
wait_for "the HA light to be available" 120 ha_available "$TABULA_LIGHT"
ha_call light turn_on "{\"entity_id\": \"$TABULA_LIGHT\", \"brightness_pct\": 50}"
wait_for "system brightness $(expected_level 50) (50 %)" 10 level_is "$(expected_level 50)"
wait_for "the adaptive switch to read off" 10 ha_state_is "$TABULA_ADAPTIVE" off
echo "-- adaptive switch: $(ha_state "$TABULA_ADAPTIVE"); HA light: $(ha_state "$TABULA_LIGHT")"

step "6. screen log for the record"
adb shell logcat -d -s TabulaScreen:I TabulaScreen:W TabulaService:I | tail -40

echo
echo "P2.3 hardware check complete; paste everything above into the packet Result."
