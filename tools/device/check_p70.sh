#!/usr/bin/env bash
[[ "${TABULA_AUDIO_OK:-0}" == 1 ]] || { echo "refusing to run: audio device tests must be scheduled with the owner; set TABULA_AUDIO_OK=1 only for an approved run" >&2; exit 2; }
# P7.0 voice spike check (docs/packets/P7.0-voice-device-spike.md):
#   microphone grant persistence, which AudioSource gives usable 16 kHz mono,
#   whether an echo canceller exists and what it removes, the cost of
#   continuous capture with a wake-word model, and capture with another app in
#   front and with the panel off.
#
# Prerequisites: the voice spike APK (make voice-spike-apk) is installed on the
# tablet, ANDROID_SERIAL is set when several devices are attached, and the
# tablet is on USB (the Wi-Fi toggle step cuts Wi-Fi, so adb must not ride on
# it).
#
# The spike app is net.lockney.tabula.spike.voice, its own applicationId: it
# installs beside the paired app and this script never installs, uninstalls or
# clears net.lockney.tabula. The one step that disturbs the whole tablet is the
# reboot in the persistence check.
#
# The steps that need a person (wake-word accuracy at 1 m and 3 m, with and
# without playback) run only with TABULA_PERSON=1, and that needs an
# interactive terminal: they prompt. Everything else runs unattended.
#
# Paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
prod=net.lockney.tabula
spike=net.lockney.tabula.spike.voice
tag=TabulaVoiceSpike
model_url=https://raw.githubusercontent.com/esphome/micro-wake-word-models/40ff33f57f8fc6ad71a75ef085abbf742495b225/models/v2/okay_nabu.tflite
model_sha=0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891
model=build/spike/okay_nabu.tflite
device_model=/sdcard/Android/data/$spike/files/model.tflite
logs=build/spike/logs
cost_seconds="${TABULA_COST_S:-600}"
person="${TABULA_PERSON:-0}"
apk="${TABULA_SPIKE_APK:-android/spike-voice/build/outputs/apk/debug/spike-voice-debug.apk}"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
note() { echo "-- $*"; }

# shellcheck source=prompt.sh
source "$(dirname "$0")/prompt.sh"

[[ "$spike" != "$prod" ]] || fail "the spike applicationId must differ from the production one"
mkdir -p "$logs"

pkg_present() {
    adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$spike" \
        || fail "$spike is not installed any more; another run on this tablet removed it: adb install -r $apk"
}

service_listed() {
    adb shell dumpsys activity services "$spike" 2>/dev/null | grep -q "ServiceRecord"
}

# `adb logcat` ignores SIGTERM, so a plain kill leaves a reader attached to the
# tablet for the rest of the session.
finish_reader() {
    kill -9 "$1" 2>/dev/null || true
    wait "$1" 2>/dev/null || true
}

require_device() {
    [[ "$(adb get-state 2>/dev/null | tr -d '\r')" == "device" ]] \
        || fail "${1:-the tablet left USB; nothing after this point was collected}"
}

# Runs one spike command and prints the log lines it wrote. The reader is a
# live `logcat` rather than a `logcat -d` dump at the end: the tablet's buffer
# is small enough that a ten-minute cost run rolls the first lines away.
#
# Every invocation writes its own log file. A single `logcat` per command name
# would let a reader that outlived its step append to the next step's file, and
# a stale completion line is exactly what would make a step return somebody
# else's measurement.
run_no=0
_spike_run() { # _spike_run <soft 0|1> <cmd> <timeout s> [extras...]
    local soft="$1" cmd="$2" timeout="$3"; shift 3
    run_no=$((run_no + 1))
    local log reader waited=0 gone=0
    log="$logs/$(printf '%02d' "$run_no")-$cmd.log"
    pkg_present
    adb logcat -c > /dev/null 2>&1 || true
    adb logcat -s "$tag:I" > "$log" 2>&1 &
    reader=$!
    adb shell am start -n "$spike/.SpikeActivity" --es cmd "$cmd" "$@" > /dev/null \
        || fail "the spike activity would not start; is $spike installed?"
    until grep -q "SPIKE done cmd=$cmd" "$log" 2>/dev/null; do
        if (( waited >= timeout )); then
            finish_reader "$reader"
            grep "SPIKE " "$log" 2>/dev/null | sed 's/^/   /' || true
            if [[ "$soft" == "1" ]]; then
                echo "   (the job '$cmd' did not finish within ${timeout}s)"
                return 1
            fi
            fail "the spike job '$cmd' did not finish within ${timeout}s"
        fi
        if ! adb get-state > /dev/null 2>&1; then
            finish_reader "$reader"
            grep "SPIKE " "$log" 2>/dev/null | sed 's/^/   /' || true
            if [[ "$soft" == "1" ]]; then
                echo "   (the tablet left USB during '$cmd')"
                return 1
            fi
            fail "the tablet left USB during '$cmd'"
        fi
        # A job that is not in the service list and has not reported done was
        # stopped by something else on the tablet: the spike app itself, a
        # package removal, or the system. Say so instead of waiting out the
        # clock; the shared tablet makes this a real case.
        if ! service_listed; then
            gone=$((gone + 1))
            if (( gone >= 3 )); then
                finish_reader "$reader"
                grep "SPIKE " "$log" 2>/dev/null | sed 's/^/   /' || true
                if [[ "$soft" == "1" ]]; then
                    echo "   (the spike app was stopped during '$cmd', not by this script)"
                    return 1
                fi
                fail "the spike app was stopped during '$cmd' by something else on the tablet"
            fi
        else
            gone=0
        fi
        sleep 1; waited=$((waited + 1))
    done
    sleep 1
    finish_reader "$reader"
    grep "SPIKE " "$log" | sed 's/^/   /'
}

spike_run() { _spike_run 0 "$@"; }
spike_try() { _spike_run 1 "$@"; }

model_ready() {
    if [[ ! -f "$model" ]]; then
        note "fetching the wake-word model into $model (pinned commit, sha256 checked)"
        mkdir -p "$(dirname "$model")"
        curl -fsS -o "$model" "$model_url" || fail "could not fetch the model; see the P7.0 ADR for its source"
    fi
    local got
    got="$(shasum -a 256 "$model" | cut -d' ' -f1)"
    [[ "$got" == "$model_sha" ]] || fail "model sha256 is $got, expected $model_sha"
    adb shell mkdir -p "/sdcard/Android/data/$spike/files"
    adb push "$model" "$device_model" > /dev/null
    echo "   model: $model sha256=$got (microWakeWord v2 okay_nabu, Apache-2.0)"
}

# --- preflight ---------------------------------------------------------------

step "0. connected tablet, both packages, the production app untouched"
require_device "no tablet: attach it over USB, or set ANDROID_SERIAL"
adb devices -l
adb shell dumpsys package "$prod" | grep -E "versionName|userId" | sed 's/^/   /' \
    || fail "$prod is not installed on this tablet"
adb shell dumpsys package "$spike" | grep -E "versionName|userId" | sed 's/^/   /' \
    || fail "$spike is not installed; run make voice-spike-apk and install $apk"
echo "   production data dir:"
adb shell ls -d "/data/data/$prod" 2>/dev/null | sed 's/^/   /' || note "not listable over adb on this build"
if [[ -f "$apk" ]]; then
    note "installing $apk (same applicationId, so this only updates the spike app)"
    adb install -r "$apk" | sed 's/^/   /'
fi

step "0b. microphone grant on the spike app"
adb shell pm grant "$spike" android.permission.RECORD_AUDIO || fail "pm grant RECORD_AUDIO failed"
adb shell dumpsys package "$spike" | grep "android.permission.RECORD_AUDIO" | sed 's/^/   /' || true
adb shell appops get "$spike" RECORD_AUDIO | sed 's/^/   /' || true

if [[ "$person" == "1" ]]; then
    note "the person steps at the end will prompt: run this script in an interactive terminal"
fi

# --- question 3: which AudioSource gives usable 16 kHz mono ------------------

step "1. audio sources at a 16 kHz mono request (question 3)"
spike_run sources 120

# --- question 4: echo cancellation ------------------------------------------

step "2. echo cancellers and audio effects (question 4)"
spike_run effects 60
echo "   platform effect chains:"
adb shell dumpsys media.audio_flinger 2>/dev/null | grep -iE "echo|aec" | head -10 | sed 's/^/   /' || true

step "2b. does anything cancel the device's own playback (question 4)"
spike_run aec 120 --ei seconds 3

# --- question 5: the cost of continuous detection ---------------------------

step "3. continuous capture with the wake-word model (question 5)"
model_ready
adb shell dumpsys battery | grep -E "temperature|level" | sed 's/^/   before: /'
adb shell cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null | sed 's/^/   thermal_zone0: /' || true
(
    sleep 30
    adb shell dumpsys cpuinfo | grep -i "$spike" | sed 's/^/   cpuinfo: /'
    adb shell dumpsys battery | grep -E "temperature|level" | sed 's/^/   during: /'
) &
sampler=$!
spike_run cost "$((cost_seconds + 60))" --ei seconds "$cost_seconds" --es model "$device_model"
wait "$sampler" 2>/dev/null || true
adb shell dumpsys battery | grep -E "temperature|level" | sed 's/^/   after: /'
adb shell cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null | sed 's/^/   thermal_zone0: /' || true

# --- question 2: capture with other windows in front ------------------------

step "4. capture while the dashboard is in front (question 2)"
adb shell am start -n "$prod/.MainActivity" > /dev/null || true
sleep 3
spike_run capture 60 --ei seconds 10 --es name foreground-dashboard

step "4b. capture with another app in front (question 2)"
adb shell am start -a android.settings.SETTINGS > /dev/null || true
sleep 3
spike_run capture 60 --ei seconds 10 --es name foreground-settings

step "4c. capture with the panel off (question 2)"
adb shell input keyevent KEYCODE_POWER > /dev/null
sleep 3
spike_run capture 60 --ei seconds 10 --es name panel-off
adb shell input keyevent KEYCODE_POWER > /dev/null
note "a panel-off capture must still report about 160000 samples; a starved or"
note "blocked microphone shows up as far fewer, and the app logs it"

step "4d. what the OS shows the user while the microphone is open"
adb shell dumpsys notification --noredact 2>/dev/null | grep -A2 "tabula.spike.voice" | head -12 | sed 's/^/   /' || true
adb shell dumpsys audio 2>/dev/null | grep -iE "record|mic" | head -8 | sed 's/^/   /' || true

# --- question 1: grant persistence (last: it reboots the tablet) ------------

step "5. does the microphone grant survive a reboot (question 1)"
note "rebooting; the whole tablet goes down for about a minute"
adb reboot || fail "adb reboot failed"
for _ in $(seq 1 60); do
    [[ "$(adb get-state 2>/dev/null | tr -d '\r')" == "device" ]] && break
    sleep 2
done
require_device "the tablet did not come back over USB after the reboot"
for _ in $(seq 1 60); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    sleep 2
done
sleep 10
echo "   after reboot:"
adb shell dumpsys package "$spike" | grep "android.permission.RECORD_AUDIO" | sed 's/^/   /' || true
adb shell appops get "$spike" RECORD_AUDIO | sed 's/^/   /' || true
spike_try capture 90 --ei seconds 5 --es name after-reboot \
    || note "no capture after the reboot: the grant did not survive it"

step "5b. does the microphone grant survive a Wi-Fi cycle (question 1)"
adb shell svc wifi disable
sleep 5
adb shell svc wifi enable
sleep 15
adb shell dumpsys package "$spike" | grep "android.permission.RECORD_AUDIO" | sed 's/^/   /' || true
adb shell appops get "$spike" RECORD_AUDIO | sed 's/^/   /' || true
spike_try capture 60 --ei seconds 5 --es name after-wifi-cycle \
    || note "no capture after the Wi-Fi cycle: the grant did not survive it"

step "5c. the restore paths (question 1)"
note "1. adb, no person:   adb shell pm grant $spike android.permission.RECORD_AUDIO"
note "2. adb, app-op:      adb shell appops set $spike RECORD_AUDIO allow"
note "3. a person, on the tablet: the spike app's Grant microphone button"
echo "   re-granting over adb and trying again:"
adb shell pm grant "$spike" android.permission.RECORD_AUDIO
adb shell appops set "$spike" RECORD_AUDIO allow
spike_try capture 60 --ei seconds 5 --es name after-regrant \
    || note "no capture after the re-grant either"

# --- person steps ------------------------------------------------------------

if [[ "$person" == "1" ]]; then
    step "6. wake-word accuracy at 1 m and 3 m (question 5, needs a person)"
    pause "Stand 1 m from the tablet, facing it, in a quiet room"
    for phrase in 1 2 3; do
        note "say the wake word now, three times, once per 2 s: clip wake-1m-$phrase"
        spike_run capture 60 --ei seconds 6 --es name "wake-1m-$phrase"
    done
    pause "Stand 3 m from the tablet, facing it"
    for phrase in 1 2 3; do
        note "say the wake word now, three times, once per 2 s: clip wake-3m-$phrase"
        spike_run capture 60 --ei seconds 6 --es name "wake-3m-$phrase"
    done
    pause "Start the tablet's own playback (the owner checklist's step), then press Enter"
    for phrase in 1 2 3; do
        note "say the wake word over the playback: clip wake-playback-$phrase"
        spike_run capture 60 --ei seconds 6 --es name "wake-playback-$phrase"
    done
    step "6b. scoring the clips with the reference frontend and the model"
    mkdir -p build/spike/clips
    adb pull "/sdcard/Android/data/$spike/files/clips/." build/spike/clips/ | tail -2
    uv run --with ai-edge-litert --with pymicro-features \
        python tools/device/score_wakeword.py build/spike/clips --model "$model"
else
    step "6. wake-word accuracy and the day-long uptime check: skipped"
    note "they need a person; the owner steps are in the packet's Result and in the PR"
    note "re-run with TABULA_PERSON=1 in an interactive terminal to record and score clips"
fi

# --- question 6 --------------------------------------------------------------

step "7. HA round trip (question 6): not run by this script"
note "it needs the binary channel from ADR 0024 decision 2 and a throwaway"
note "integration on a test Home Assistant, never the owner's; the owner steps"
note "are in the packet's Result"

step "8. the production app is still the same install"
adb shell dumpsys package "$prod" | grep -E "versionName|userId" | sed 's/^/   /'
adb shell dumpsys package "$spike" | grep -E "versionName|userId" | sed 's/^/   /'
