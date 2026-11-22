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
if [[ "$module" == "app" ]]; then
    echo "APK: $apk_dir/app-debug.apk"
else
    # A module with ABI splits writes one APK per ABI; one without writes a
    # single file. Print what the build actually left behind.
    shopt -s nullglob
    apks=("$apk_dir"/*.apk)
    shopt -u nullglob
    if [[ ${#apks[@]} -gt 1 ]]; then
        echo "APKs: $apk_dir ($module-*-debug.apk, one per ABI)"
    else
        echo "APK: $apk_dir/$module-debug.apk"
    fi
fi
