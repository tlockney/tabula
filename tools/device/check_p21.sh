#!/usr/bin/env bash
# P2.1 hardware check (docs/packets/P2.1-service-pairing-client.md):
#   the tablet pairs and appears in HA; airplane mode makes it unavailable;
#   reconnect brings it back.
#
# Prerequisites: the tablet is provisioned (tools/device/provision.sh) and
# paired by hand, the app is in the foreground, and the environment has:
#   TABULA_HA_URL    e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN  a long-lived access token from an ADMIN user (the
#                    owner's; HA's config/device_registry/list command is
#                    admin-only, so a non-admin token fails step 3)
#   ANDROID_SERIAL   optional, picks the tablet when several are attached
#
# Paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
log_tag=TabulaService

# Airplane mode on/off. `cmd connectivity airplane-mode` is OEM- and
# version-dependent (public references disagree on whether it exists on
# Android 9, and Fire OS 7 is Android 9), so when the subcommand is missing
# the script falls back to `svc wifi disable`/`enable`, which severs the HA
# link the same way for the purposes of this check. Both paths are printed so
# the verifier can see which one ran.
airplane() { # airplane <enable|disable>
    local mode="$1"
    if adb shell cmd connectivity airplane-mode "$mode" 2>/dev/null; then
        echo "-- toggled with: cmd connectivity airplane-mode $mode"
    else
        case "$mode" in
            enable)  echo "-- cmd connectivity airplane-mode unavailable; falling back to: svc wifi disable"
                     adb shell svc wifi disable ;;
            disable) echo "-- cmd connectivity airplane-mode unavailable; falling back to: svc wifi enable"
                     adb shell svc wifi enable ;;
        esac
    fi
}

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"

step() { echo; echo "== $*"; }

# Any exit (a failed gate, Ctrl-C) turns airplane mode back off, so a failed
# run never leaves the tablet offline.
radio_off=0
restore() {
    if (( radio_off )); then
        airplane disable >&2 || true
        radio_off=0
        echo "-- restore: airplane mode off" >&2
    fi
}
trap restore EXIT
trap 'restore; exit 130' INT TERM
wait_for_log() { # wait_for_log <pattern> <timeout seconds>
    local pattern="$1" timeout="$2" waited=0
    while (( waited < timeout )); do
        if adb shell logcat -d -s "$log_tag:I" 2>/dev/null \
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
reg_output="$(
    UV_CONFIG_FILE="$here/../uv.toml" \
        uv run --directory "$here/../.." python tools/device/ha_query.py \
        --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$device_id"
)"
echo "$reg_output"
# the gate itself: the registry output must mention this device_id, and a
# failed HA command makes ha_query.py exit non-zero, so empty evidence fails
echo "$reg_output" | grep -F "$device_id" > /dev/null \
    || { echo "the registry output does not mention $device_id" >&2; exit 1; }
echo "-- the registry entry above is the P2.1 'appears in HA' evidence"

# Registration is gated on HA's live view, not on a log line: the service
# registered when it connected, possibly long before this script started, and
# it does not re-register while the connection holds.
echo "$reg_output" | grep -E '^binary_sensor\.[a-z0-9_]*connectivity: state=on' \
    || { echo "HA does not show the tablet connected (connectivity sensor not on)" >&2; exit 1; }
echo "-- HA shows the tablet connected: the P2.1 'registered' evidence"
adb shell logcat -c

step "4. airplane mode ON (device becomes unavailable)"
radio_off=1
airplane enable
echo "waiting up to 60s for the 'connection lost' log line"
wait_for_log "connection lost" 60
echo "-- HA side while offline (entities read unavailable; the registry entry stays):"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/../.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$device_id" || true

step "5. airplane mode OFF (reconnect brings it back)"
airplane disable
radio_off=0
echo "waiting up to 120s for re-registration"
adb shell logcat -c
wait_for_log "registered with HA device" 120
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/../.." python tools/device/ha_query.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" "$device_id"

step "6. full service log for the record"
adb shell logcat -d -s "$log_tag:I"

echo
echo "P2.1 hardware check complete; paste everything above into the packet Result."
