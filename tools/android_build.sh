#!/usr/bin/env bash
# make android-apk / make android-spike-apk: build a debug APK.
#
# Argument: the Gradle module, default `app` (docs/DEVICE.md). P9.0's intercom
# spike builds its own module, which has a different applicationId so installing
# it never touches the production app.
#
# Skips with a clear message when the toolchain is missing, like
# tools/android_test.sh. CI never needs the APK, so TABULA_REQUIRE_ANDROID is
# not consulted here.

set -euo pipefail

module="${1:-app}"

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

cd "$here/../android"
export JAVA_HOME="$jdk"
export ANDROID_HOME="$sdk"
./gradlew --no-daemon ":$module:assembleDebug" --console=plain
echo
if [[ "$module" == "app" ]]; then
    echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "APKs: android/$module/build/outputs/apk/debug/ ($module-*-debug.apk, one per ABI)"
fi
