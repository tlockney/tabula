#!/usr/bin/env bash
[[ "${TABULA_AUDIO_OK:-0}" == 1 ]] || { echo "refusing to run: audio device tests must be scheduled with the owner; set TABULA_AUDIO_OK=1 only for an approved run" >&2; exit 2; }
# P9.0's person-in-the-room half (packet questions 3 and 7): two kiosks place a
# normal two-way call with echo cancellation on, and the verifier speaks at
# 1 m and 3 m, listens at the far end, puts both tablets in one room for the
# feedback case, and claps for the latency judgment. The check asks; it cannot
# hear. Run it from an interactive shell (it needs a pty).
#
#   TABULA_HOST_IP=192.168.1.126 tools/device/check_p90_owner.sh
#
# The automated half is tools/device/check_p90.sh; the numbers it prints (the
# tone test's mouth-to-ear figure, jitter, loss) are the measurements of record.
#
# Paste the whole output into the packet Result section.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=intercom_devices.sh
source "$here/intercom_devices.sh"
# shellcheck source=prompt.sh
source "$here/prompt.sh"

apk="${TABULA_INTERCOM_APK:-$root/android/spike-intercom/build/outputs/apk/debug/spike-intercom-arm64-v8a-debug.apk}"
port="${TABULA_INTERCOM_PORT:-8099}"
host_ip="${TABULA_HOST_IP:-$(ipconfig getifaddr en0 2>/dev/null || true)}"
logs="$root/build/intercom-spike"
room="p90-owner-$(date +%s)"
relay_pid=""
a_log_pid=""
b_log_pid=""

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
cleanup() {
    for pid in "$a_log_pid" "$b_log_pid" "$relay_pid"; do
        [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
    done
    dev_a shell am force-stop "$intercom_spike_pkg" > /dev/null 2>&1 || true
    dev_b shell am force-stop "$intercom_spike_pkg" > /dev/null 2>&1 || true
}
trap cleanup EXIT

need_tty "this check asks what you hear"
[[ -f "$apk" ]] || fail "no APK at $apk (run make android-spike-apk)"
[[ -n "$host_ip" ]] || fail "could not read this machine's LAN address; set TABULA_HOST_IP"
mkdir -p "$logs"

relay_url="http://$host_ip:$port"
step "preparing the call"
echo "A = the Office Kiosk (serial $intercom_a_serial), the CALLER: you speak toward it."
echo "B = the Desk Kiosk (serial $intercom_b_serial), the ANSWERER: you listen at it."
echo "Both run the spike app in its own application; the production app is untouched."
intercom_install dev_a "$apk" > /dev/null
intercom_install dev_b "$apk" > /dev/null
dev_a shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
dev_b shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
if ! curl -fsS --max-time 2 "$relay_url/poll?room=probe&role=call&since=0" > /dev/null 2>&1; then
    python3 "$here/intercom_relay.py" --port "$port" > "$logs/relay-owner.log" 2>&1 &
    relay_pid=$!
    sleep 1
fi
dev_a shell am force-stop "$intercom_spike_pkg" || true
dev_b shell am force-stop "$intercom_spike_pkg" || true
sleep 1
dev_a logcat -c > /dev/null 2>&1 || true
dev_b logcat -c > /dev/null 2>&1 || true
dev_a logcat -v time -s TabulaIntercomSpike:I > "$logs/owner-a.log" 2>&1 &
a_log_pid=$!
dev_b logcat -v time -s TabulaIntercomSpike:I > "$logs/owner-b.log" 2>&1 &
b_log_pid=$!

# A 6-minute call: long enough for two walk-overs and a judgment.
dev_b shell am start-foreground-service -n "$intercom_spike_pkg/.IntercomCallService" \
    -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role answer \
    --es durationSeconds 380 > /dev/null
sleep 1
dev_a shell am start-foreground-service -n "$intercom_spike_pkg/.IntercomCallService" \
    -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role call \
    --es durationSeconds 380 > /dev/null
waited=0
until grep -q "call connected" "$logs/owner-a.log" 2>/dev/null; do
    (( waited >= 30 )) && fail "the call did not connect"
    sleep 1
    waited=$((waited + 1))
done
echo "-- connected after ${waited}s; echo cancellation and noise suppression are ON at both ends"
echo "-- nobody touches the Desk Kiosk: the call answers itself"

step "1 m — speech at normal volume"
pause "Stand 1 m from the Office Kiosk, speak normally for a few sentences, then walk to the Desk Kiosk and listen"
ask "At the Desk Kiosk, was the speech intelligible at normal speaking volume"
ask "Did the Desk Kiosk's speaker feed back into the call (the far end hearing itself, or howling)"

step "3 m — speech from across the room"
pause "Stand 3 m from the Office Kiosk, speak normally, then listen at the Desk Kiosk again"
ask "At 3 m, was the speech intelligible"
ask "Any echo or howling at 3 m"

step "both kiosks in one room (the feedback case ADR 0025's consequences name)"
pause "Move both tablets into one room, 1 m apart, speakers facing away from each other, then speak at one of them"
ask "With both tablets in one room, was there audible howling or runaway feedback"
echo "-- if there was feedback, note it: ADR 0025 refuses same-area calls for this reason"

step "clap test (question 7's human latency judgment)"
pause "Clap once, loudly, 1 m from the Office Kiosk's microphone"
ask "At the Desk Kiosk, did the clap arrive without a noticeable delay (under about a tenth of a second)"
echo "-- the measurable figure is the tone test in check_p90.sh (mouth to ear, relay clock);"
echo "   judge this one only roughly, and say what you compared it against."

step "the call's media, from the logs"
python3 "$here/intercom_report.py" "$logs/owner-a.log" "$logs/owner-b.log" || true

step "summary"
echo "A (Office Kiosk, caller): $intercom_a_serial"
echo "B (Desk Kiosk, answerer): $intercom_b_serial"
echo "Logs: $logs/owner-a.log $logs/owner-b.log"
echo "Paste this whole output into the packet Result section."
