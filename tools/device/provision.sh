#!/usr/bin/env bash
# ADB provisioning for a Tabula device (docs/DEVICE.md, packet P2.1).
#
# Usage: ANDROID_SERIAL=<serial> tools/device/provision.sh [APK]
# APK defaults to the debug build (make android-apk), which is one file per ABI
# since P9.3: arm64-v8a is the v1 shape, armeabi-v7a the fallback.
#
# Every step prints its own evidence; paste the whole output into the packet
# Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
apk_dir=android/app/build/outputs/apk/debug
apk="${1:-}"
if [[ -z "$apk" ]]; then
    for candidate in "$apk_dir/app-arm64-v8a-debug.apk" "$apk_dir/app-armeabi-v7a-debug.apk"; do
        if [[ -f "$candidate" ]]; then
            apk="$candidate"
            break
        fi
    done
fi
apk="${apk:-$apk_dir/app-arm64-v8a-debug.apk}"

step() { echo; echo "== $*"; }

[[ -f "$apk" ]] || { echo "APK not found: $apk (run make android-apk; it writes one APK per ABI)" >&2; exit 2; }

step "connected device"
adb devices -l

step "install"
adb install -r "$apk"

step "write-settings grant (window brightness, P2.3)"
# WRITE_SETTINGS is appops-backed; pm grant is the scripted form (plan 3, ADB provisioning).
adb shell pm grant "$pkg" android.permission.WRITE_SETTINGS || true
adb shell appops set "$pkg" WRITE_SETTINGS allow

step "overlay permission (self-relaunch, Phase 4)"
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow

step "battery-optimization exemption (keeps the connection alive, F-KSK-09)"
adb shell dumpsys deviceidle whitelist +"$pkg"

step "device admin (panel power-off, P2.3)"
# The admin receiver (force-lock only) arrived with P2.3; on older builds the
# error output is expected. Without it the panel cannot be switched off.
adb shell dpm set-active-admin "$pkg/.ScreenAdminReceiver" || true

step "verify"
adb shell dumpsys deviceidle whitelist | grep -i tabula || true
adb shell dumpsys package "$pkg" | grep -E "versionName|userId" || true

cat <<'EOF'

Finish by hand:
  1. Launch Tabula once on the tablet (a freshly installed app is in the
     stopped state and receives no boot broadcast until it has been launched).
  2. Enter the HA address and confirm the pairing code in Home Assistant
     (Settings > Devices & services > Tabula).
EOF
