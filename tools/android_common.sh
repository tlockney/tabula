#!/usr/bin/env bash
# Shared Android toolchain discovery, sourced by tools/setup_android.sh and
# tools/android_test.sh. Prints a usable JAVA_HOME (JDK 17-21, the range the
# pinned AGP supports) or an Android SDK home; returns non-zero when none is
# found. Not a user entry point.

# Print the home of a JDK between 17 and 21, trying JAVA_HOME, PATH, then the
# usual package-manager install locations.
find_jdk() {
    local candidates=("${JAVA_HOME:-}")
    if command -v java >/dev/null 2>&1; then
        candidates+=("$(dirname "$(dirname "$(command -v java)")")")
    fi
    candidates+=(
        /opt/homebrew/opt/openjdk@21
        /opt/homebrew/opt/openjdk@17
        /opt/homebrew/opt/openjdk
        /opt/homebrew/Cellar/openjdk@21/*
        /opt/homebrew/Cellar/openjdk@17/*
        /usr/lib/jvm/*-17*
        /usr/lib/jvm/*-21*
        /usr/lib/jvm/*17
        /usr/lib/jvm/*21
        /usr/java/*17
        /usr/java/*21
    )
    local home major
    for home in "${candidates[@]}"; do
        [[ -n "$home" && -x "$home/bin/java" ]] || continue
        major="$("$home/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
        case "$major" in
            17|18|19|20|21) echo "$home"; return 0 ;;
        esac
    done
    return 1
}

# Print the Android SDK home (cmdline-tools present), trying ANDROID_HOME,
# ANDROID_SDK_ROOT and the usual install locations.
find_android_sdk() {
    local candidates=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/local/lib/android/sdk /opt/android-sdk)
    local sdk
    for sdk in "${candidates[@]}"; do
        for tools in cmdline-tools/latest cmdline-tools/newest; do
            if [[ -n "$sdk" && -x "$sdk/$tools/bin/sdkmanager" ]]; then
                echo "$sdk"
                return 0
            fi
        done
    done
    return 1
}

# The SDK components the pinned AGP needs (docs/decisions/0007-*).
android_platform=android-34
android_build_tools=34.0.0

sdk_components_present() {
    [[ -d "$1/platforms/$android_platform" && -d "$1/build-tools/$android_build_tools" ]]
}
