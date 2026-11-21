#!/usr/bin/env bash
# make setup-android: verify (and where possible install) the Android build
# toolchain — a JDK 17-21 and the Android SDK components the pinned AGP needs
# (platform android-34, build-tools 34.0.0; docs/decisions/0007-*).
#
# Network needs, documented in docs/DEVELOPMENT.md: dl.google.com for SDK
# components via sdkmanager, services.gradle.org for the pinned Gradle
# wrapper distribution (SHA-256 checked), Google's Maven repository and
# Maven Central for build dependencies.
#
# Set TABULA_REQUIRE_ANDROID=1 to fail instead of reporting gaps (CI does
# this).

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=android_common.sh
source "$here/android_common.sh"

status=0
fail() {
    echo "setup-android: $1" >&2
    if [[ "${TABULA_REQUIRE_ANDROID:-0}" == "1" ]]; then
        status=1
    fi
}

jdk="$(find_jdk || true)"
if [[ -n "$jdk" ]]; then
    echo "setup-android: JDK ok ($jdk)"
    export JAVA_HOME="$jdk"
else
    fail "no JDK 17-21 found. Install one from a package registry, e.g.
       brew install openjdk@17        (macOS)
       apt install temurin-17-jdk     (Debian/Ubuntu)
     then re-run; or point JAVA_HOME at an existing one."
fi

sdk="$(find_android_sdk || true)"
if [[ -n "$sdk" ]]; then
    echo "setup-android: Android SDK ok ($sdk)"
    if sdk_components_present "$sdk"; then
        echo "setup-android: platform $android_platform and build-tools $android_build_tools present"
    else
        manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
        if [[ ! -x "$manager" ]]; then
            manager="$sdk/cmdline-tools/newest/bin/sdkmanager"
        fi
        if [[ -z "$jdk" ]]; then
            fail "SDK components missing and no JDK to run sdkmanager; run again with a JDK installed"
        else
            echo "setup-android: installing platform $android_platform and build-tools $android_build_tools"
            yes | "$manager" "platforms;$android_platform" "build-tools;$android_build_tools" >/dev/null
            echo "setup-android: components installed"
        fi
    fi
else
    fail "no Android SDK found. Install the command-line tools package
     (https://developer.android.com/studio#command-line-tools-only), unpack
     to cmdline-tools/latest under an SDK root, accept licenses with
     'yes | sdkmanager --licenses', and point ANDROID_HOME at it; or on
     macOS install the Android Studio SDK under ~/Library/Android/sdk."
fi

exit "$status"
