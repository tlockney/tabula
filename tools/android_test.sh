#!/usr/bin/env bash
# make android-test: run the Android unit tests through the Gradle wrapper.
#
# Skips with a clear message when the toolchain is missing, so Python-only
# machines still pass `make check` (AGENTS.md rule 6). CI sets
# TABULA_REQUIRE_ANDROID=1, which turns a skip into a failure, so these tests
# always run there.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=android_common.sh
source "$here/android_common.sh"

skip() {
    if [[ "${TABULA_REQUIRE_ANDROID:-0}" == "1" ]]; then
        echo "android: $1; CI requires these tests (see docs/DEVELOPMENT.md)" >&2
        exit 1
    fi
    echo "android: skipped - $1 (CI runs these tests; see docs/DEVELOPMENT.md)"
    exit 0
}

jdk="$(find_jdk || true)"
if [[ -z "$jdk" ]]; then
    skip "no JDK 17-21 found; make setup-android installs or points at one"
fi

sdk="$(find_android_sdk || true)"
if [[ -z "$sdk" ]]; then
    skip "no Android SDK found; make setup-android installs or points at one"
fi
if ! sdk_components_present "$sdk"; then
    skip "Android SDK platform $android_platform / build-tools $android_build_tools missing; run make setup-android"
fi

cd "$here/../android"
export JAVA_HOME="$jdk"
export ANDROID_HOME="$sdk"
exec ./gradlew --no-daemon test --console=plain
