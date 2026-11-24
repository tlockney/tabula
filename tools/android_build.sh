#!/usr/bin/env bash
# make android-apk: build the debug APK for provisioning (docs/DEVICE.md).
# make voice-spike-apk: build the P7.0 voice spike's debug APK, a separate
# applicationId that installs beside the paired app. Debug only: nothing in the
# release path knows about it.
# make android-spike-apk: build P9.0's intercom spike, which is also its own
# applicationId (ADR 0028) and splits its APK per ABI.
#
# The Gradle module is the first argument (default: app).
#
# Skips with a clear message when the toolchain is missing, like
# tools/android_test.sh. CI never needs the APK, so TABULA_REQUIRE_ANDROID is
# not consulted here.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=android_common.sh
source "$here/android_common.sh"

skip() {
    echo "android-apk: skipped - $1 (see docs/DEVELOPMENT.md)" >&2
    exit 0
}

jdk="$(find_jdk || true)"
[[ -n "$jdk" ]] || skip "no JDK 17-21 found; make setup-android installs or points at one"

sdk="$(find_android_sdk || true)"
[[ -n "$sdk" ]] || skip "no Android SDK found; make setup-android installs components when the SDK is present"

if ! sdk_components_present "$sdk"; then
    skip "Android SDK platform $android_platform / build-tools $android_build_tools missing; run make setup-android"
fi

module="${1:-app}"

cd "$here/../android"
export JAVA_HOME="$jdk"
export ANDROID_HOME="$sdk"
./gradlew --no-daemon ":$module:assembleDebug" --console=plain
echo
apk_dir="android/$module/build/outputs/apk/debug"
# A module with ABI splits writes one APK per ABI; one without writes a single
# file. Print what the build actually left behind, with each file's size - the
# app ships one APK per ABI since P9.3 (ADR 0028).
shopt -s nullglob
apks=("$apk_dir"/*.apk)
shopt -u nullglob
if [[ ${#apks[@]} -eq 0 ]]; then
    echo "android-apk: the build left no APK in $apk_dir" >&2
    exit 1
fi
if [[ ${#apks[@]} -eq 1 ]]; then
    echo "APK: ${apks[0]} ($(wc -c <"${apks[0]}" | tr -d ' ') bytes)"
else
    echo "APKs: $apk_dir (one per ABI)"
    for apk in "${apks[@]}"; do
        echo "  $(basename "$apk")  $(wc -c <"$apk" | tr -d ' ') bytes"
    done
fi
