#!/usr/bin/env bash
# ADB provisioning for a Tabula device (docs/DEVICE.md, packet P2.1).
#
# Usage: ANDROID_SERIAL=<serial> tools/device/provision.sh [APK]
# APK defaults to the debug build (make android-apk).
#
# Every step prints its own evidence; paste the whole output into the packet
# Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
apk="${1:-android/app/build/outputs/apk/debug/app-debug.apk}"

step() { echo; echo "== $*"; }

[[ -f "$apk" ]] || { echo "APK not found: $apk (run make android-apk)" >&2; exit 2; }

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
# The admin receiver component arrives with P2.3; the error output is expected
# until then and the step is not a gate for P2.1.
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
