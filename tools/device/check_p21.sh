#!/usr/bin/env bash
# P2.1 hardware check (docs/packets/P2.1-service-pairing-client.md):
#   the tablet pairs and appears in HA; airplane mode makes it unavailable;
#   reconnect brings it back.
#
# Prerequisites: the tablet is provisioned (tools/device/provision.sh) and
# paired by hand, the app is in the foreground, and the environment has:
#   TABULA_HA_URL    e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN  a long-lived access token (any user; read-only queries)
#   ANDROID_SERIAL   optional, picks the tablet when several are attached
#
# Paste the whole output into the packet Result section.
#
# Note: with only P1.2 merged there are no entities yet, so "unavailable" is
# observed through the app's logcat (connection lost) and the HA registry
# entry staying put; the entity-state assertions become checkable when P1.3
# lands. The script prints entity states as soon as they exist.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
log_tag=TabulaService

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"

step() { echo; echo "== $*"; }
wait_for_log() { # wait_for_log <pattern> <timeout seconds>
    local pattern="$1" timeout="$2" waited=0
    while (( waited < timeout )); do
        if adb shell logcat -d -s "$log_tag:I" "$log_tag:W" 2>/dev/null \
            | grep -F "$pattern" | tail -1 | tee /dev/stderr; then
            return 0
        fi
        sleep 5; waited=$((waited + 5))
    done
    echo "TIMED OUT waiting for '$pattern' after ${timeout}s" >&2
    return 1
}

step "1. device and service"
adb devices -l
adb shell pidof "$pkg"
adb shell dumpsys activity services "$pkg" | grep -A2 -i tabula || true

step "2. device_id (app storage, debug build run-as)"
device_id="$(
    adb shell run-as "$pkg" cat shared_prefs/tabula_device.xml 2>/dev/null \
        | sed -n 's/.*name="device_id">\([0-9a-f-]\{36\}\)<.*/\1/p' | head -1
)"
echo "device_id: ${device_id:-NOT FOUND}"
[[ -n "$device_id" ]] || { echo "run-as failed: is this the debug build?" >&2; exit 1; }

step "3. HA knows the tablet (device registry)"
here="$(cd "$(dirname "$0")" && pwd)"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$device_id"
echo "-- the registry entry above is the P2.1 'appears in HA' evidence"

adb shell logcat -c
echo "waiting up to 90s for the 'registered' log line"
wait_for_log "registered with HA device" 90

step "4. airplane mode ON (device becomes unavailable)"
adb shell cmd connectivity airplane-mode enable
echo "waiting up to 60s for the 'connection lost' log line"
wait_for_log "connection lost" 60
echo "-- HA side while offline (entities are expected to read unavailable once"
echo "   P1.3 is merged; the registry entry stays):"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$device_id" || true

step "5. airplane mode OFF (reconnect brings it back)"
adb shell cmd connectivity airplane-mode disable
echo "waiting up to 120s for re-registration"
adb shell logcat -c
wait_for_log "registered with HA device" 120
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$device_id"

step "6. full service log for the record"
adb shell logcat -d -s "$log_tag:I" "$log_tag:W"

echo
echo "P2.1 hardware check complete; paste everything above into the packet Result."
