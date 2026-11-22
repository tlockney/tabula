#!/usr/bin/env bash
# P9.0 hardware check (docs/packets/P9.0-intercom-webrtc-spike.md, ADR 0028):
# two Fire HD 10 Plus kiosks place an audio-only WebRTC call over the LAN with
# host candidates and no STUN or TURN, and the run prints the evidence for the
# packet's questions 1, 2, 4, 5 and 6, plus the automated half of 3 and 7.
#
# What needs a person in the room (speaking at 1 m and 3 m, the clap test) is
# tools/device/check_p90_owner.sh. The 10-minute cost run is
# tools/device/intercom_cost.sh; set TABULA_INTERCOM_COST=1 to run it here.
#
# The spike app is a separate application (net.lockney.tabula.spike.intercom),
# so installing it never replaces, uninstalls or unpairs net.lockney.tabula.
# This script never uninstalls or clears either app.
#
# Environment:
#   TABULA_INTERCOM_APK      default android/spike-intercom/build/outputs/apk/
#                            debug/spike-intercom-arm64-v8a-debug.apk
#                            (`make android-spike-apk` builds it)
#   TABULA_HOST_IP           the address the tablets reach this machine on
#                            (default: ipconfig getifaddr en0)
#   TABULA_INTERCOM_PORT     the throwaway relay's port (default 8099)
#   TABULA_INTERCOM_COST     1 also runs intercom_cost.sh (10 minutes)
#   TABULA_INTERCOM_SECONDS  the cost run's length (default 600)
#   and the device variables in tools/device/intercom_devices.sh
#
# Paste the whole output into the packet Result section.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=intercom_devices.sh
source "$here/intercom_devices.sh"

apk="${TABULA_INTERCOM_APK:-$root/android/spike-intercom/build/outputs/apk/debug/spike-intercom-arm64-v8a-debug.apk}"
port="${TABULA_INTERCOM_PORT:-8099}"
host_ip="${TABULA_HOST_IP:-$(ipconfig getifaddr en0 2>/dev/null || true)}"
logs="$root/build/intercom-spike"
relay_url=""
room="p90-$(date +%s)"
a_log=""
b_log=""
a_log_pid=""
b_log_pid=""
all_log_pid=""
relay_pid=""

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

cleanup() {
    stop_logs
    if [[ -n "$all_log_pid" ]]; then kill "$all_log_pid" 2>/dev/null || true; fi
    if [[ -n "$a_log_pid" ]]; then kill "$a_log_pid" 2>/dev/null || true; fi
    if [[ -n "$b_log_pid" ]]; then kill "$b_log_pid" 2>/dev/null || true; fi
    if [[ -n "$relay_pid" ]]; then kill "$relay_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT

stop_logs() {
    if [[ -n "$a_log_pid" ]]; then kill "$a_log_pid" 2>/dev/null || true; a_log_pid=""; fi
    if [[ -n "$b_log_pid" ]]; then kill "$b_log_pid" 2>/dev/null || true; b_log_pid=""; fi
    # adb logcat on the other host outlives its ssh client and would keep
    # appending to the next run's file; stop it by name.
    ssh -o ConnectTimeout=10 "$intercom_b_host" \
        "export PATH=$intercom_b_path:\$PATH; pkill -f 'adb -s $intercom_b_serial logcat' || true" \
        > /dev/null 2>&1 || true
    sleep 1
}

start_logs() { # start_logs <tag>
    local tag="$1"
    a_log="$logs/$tag-a.log"
    b_log="$logs/$tag-b.log"
    dev_a logcat -c > /dev/null 2>&1 || true
    dev_b logcat -c > /dev/null 2>&1 || true
    dev_a logcat -v time -s TabulaIntercomSpike:I > "$a_log" 2>&1 &
    a_log_pid=$!
    disown "$a_log_pid" 2>/dev/null || true
    dev_b logcat -v time -s TabulaIntercomSpike:I > "$b_log" 2>&1 &
    b_log_pid=$!
    disown "$b_log_pid" 2>/dev/null || true
    echo "-- collecting both devices' TabulaIntercomSpike logs into $logs/$tag-*.log"
}

wait_log() { # wait_log <file> <pattern> <timeout s> <what>
    local file="$1" pattern="$2" timeout="$3" what="$4" waited=0
    until grep -q "$pattern" "$file" 2>/dev/null; do
        (( waited >= timeout )) && fail "timed out after ${timeout}s waiting for $what"
        sleep 1
        waited=$((waited + 1))
    done
    echo "-- $what after ${waited}s"
}

inbound_packets() { # inbound_packets <log file>
    grep -o 'spike: stats in kind=audio packets=[0-9]*' "$1" 2>/dev/null \
        | tail -1 | sed 's/.*packets=//' || echo 0
}

call_a() { # call_a <role> <extras...>
    local role="$1"
    shift
    dev_a shell am start-foreground-service -n "$intercom_spike_pkg/.IntercomCallService" \
        -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role "$role" "$@" \
        > /dev/null
}

call_b() { # call_b <role> <extras...>
    local role="$1"
    shift
    dev_b shell am start-foreground-service -n "$intercom_spike_pkg/.IntercomCallService" \
        -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role "$role" "$@" \
        > /dev/null
}

stop_apps() {
    stop_logs
    dev_a shell am force-stop "$intercom_spike_pkg" || true
    dev_b shell am force-stop "$intercom_spike_pkg" || true
    sleep 1
}

mkdir -p "$logs"

step "0. devices"
echo "Office Kiosk (A, role=call): serial=$intercom_a_serial over local adb"
dev_a shell getprop ro.product.model
dev_a shell getprop ro.build.version.sdk
dev_a shell getprop ro.product.cpu.abi
echo "Desk Kiosk (B, role=answer): serial=$intercom_b_serial over ssh $intercom_b_host"
dev_b shell getprop ro.product.model
dev_b shell getprop ro.build.version.sdk
dev_b shell getprop ro.product.cpu.abi
echo "-- the production app on each (must stay installed and paired)"
dev_a shell dumpsys package "$intercom_prod_pkg" | grep -m1 versionName || echo "not installed on A"
dev_b shell dumpsys package "$intercom_prod_pkg" | grep -m1 versionName || echo "not installed on B"

step "1. install the spike app (its own applicationId, so the production app is untouched)"
[[ -f "$apk" ]] || fail "no APK at $apk (run make android-spike-apk)"
intercom_install dev_a "$apk"
intercom_install dev_b "$apk"
dev_a shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
dev_b shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
echo "-- installed packages beside the production app"
dev_a shell pm list packages | grep -E 'net\.lockney\.tabula' || true
dev_b shell pm list packages | grep -E 'net\.lockney\.tabula' || true

step "2. question 1: the WebRTC artifact, its license and the size per ABI"
echo "artifact io.github.webrtc-sdk:android:125.6422.07 (Maven Central, BSD-3-Clause)"
echo "per-ABI APKs from this build (splits, arm64-v8a is what the Fire HD 10 Plus uses):"
for split in "$root"/android/spike-intercom/build/outputs/apk/debug/*.apk; do
    printf '  %-46s %11s bytes\n' "$(basename "$split")" "$(stat -f %z "$split")"
done
echo "-- the native library inside each split (the added size):"
for split in "$root"/android/spike-intercom/build/outputs/apk/debug/spike-intercom-*-debug.apk; do
    lib="$(unzip -l "$split" 2>/dev/null | awk '/libjingle_peerconnection_so.so/ {print $1; exit}')"
    [[ -n "$lib" ]] && printf '  %-46s %11s bytes\n' "$(basename "$split")" "$lib"
done

step "3. the throwaway signaling relay (question 2's signaling path)"
[[ -n "$host_ip" ]] || fail "could not read this machine's LAN address; set TABULA_HOST_IP"
relay_url="http://$host_ip:$port"
if curl -fsS --max-time 2 "$relay_url/poll?room=probe&role=call&since=0" > /dev/null 2>&1; then
    echo "-- a relay already answers at $relay_url; reusing it"
else
    python3 "$here/intercom_relay.py" --port "$port" > "$logs/relay.log" 2>&1 &
    relay_pid=$!
    curl -fsS --max-time 5 "$relay_url/poll?room=probe&role=call&since=0" > /dev/null \
        || fail "the relay did not start at $relay_url"
    echo "-- relay listening at $relay_url (pid $relay_pid)"
fi

step "4. questions 2 and 4: a call, and the states it survives"
stop_apps
start_logs call
echo "-- the call is started from the foreground service, with no app in front (question 4)"
call_b answer --es durationSeconds 150
sleep 1
call_a call --es durationSeconds 150
wait_log "$a_log" "call offer-sent" 20 "the caller's offer"
wait_log "$b_log" "call offer-received" 20 "the answerer receiving the offer"
wait_log "$a_log" "call connected" 30 "the caller's ICE connection"
wait_log "$a_log" "call first-audio side=in" 30 "audio arriving at the caller"
wait_log "$b_log" "call first-audio side=in" 30 "audio arriving at the answerer"
wait_log "$a_log" "stats pair rtt=" 30 "a selected candidate pair"

echo "-- connection evidence (both ends)"
grep -hE 'spike: call (offer-sent|offer-received|answer-sent|answer-received|connected|first-audio)' \
    "$a_log" "$b_log" | sed 's/^/  /'
echo "-- candidates: host addresses on the LAN, no STUN or TURN (nothing else is configured)"
grep -hE 'spike: (local-candidate|stats (local|remote)-candidate)' "$a_log" "$b_log" | sort -u | sed 's/^/  /'
echo "-- codec"
grep -h 'spike: stats codec' "$a_log" "$b_log" | sort -u | sed 's/^/  /'

echo
echo "-- 4a. the dashboard in front on the caller (the spike app is behind another app)"
dev_a shell am start -n "$intercom_prod_pkg/.MainActivity" > /dev/null || true
sleep 3
before="$(inbound_packets "$a_log")"
sleep 8
after="$(inbound_packets "$a_log")"
echo "  caller inbound packets: $before -> $after (must grow while the dashboard is in front)"
(( after > before )) || fail "audio stopped while the dashboard was in front"

echo
echo "-- 4b. another app in front on the answerer"
dev_b shell am start -n "$intercom_prod_pkg/.MainActivity" > /dev/null || true
sleep 3
before="$(inbound_packets "$b_log")"
sleep 8
after="$(inbound_packets "$b_log")"
echo "  answerer inbound packets: $before -> $after (must grow)"
(( after > before )) || fail "audio stopped while another app was in front on the answerer"

echo
echo "-- 4c. the panel off on the caller (KEYCODE_POWER), then back on"
dev_a shell input keyevent KEYCODE_POWER
sleep 3
before="$(inbound_packets "$a_log")"
sleep 15
after="$(inbound_packets "$a_log")"
dev_a shell input keyevent KEYCODE_POWER
echo "  caller inbound packets with the panel off: $before -> $after (must grow)"
(( after > before )) || fail "audio stopped with the panel off"

echo
echo "-- 4d. the sender's cost during this call (question 6's CPU on the sender)"
dev_a shell dumpsys cpuinfo | grep -E "$intercom_spike_pkg|TOTAL" | sed 's/^/  /' || true

stop_apps

step "5. questions 3 and 7 (automated half): a tone played at one end, heard and recorded at the other"
echo "-- caller AEC off, so the tone reaches the microphone; answerer in listen-only mode,"
echo "   recording its own speaker through the air. The person-in-the-room half is check_p90_owner.sh."
room="p90-tone-$(date +%s)"
start_logs tone
call_b answer --es durationSeconds 60 --es listenOnly 1 --es recordSeconds 40
sleep 1
call_a call --es durationSeconds 60 --es aec off --es toneAfter 8
wait_log "$b_log" "tone-onset" 70 "the tone at the answerer"
stop_logs
echo
python3 "$here/intercom_report.py" "$a_log" "$b_log" || true
recording="$(grep -o 'file=[^ ]*' "$b_log" | tail -1 | cut -d= -f2 || true)"
if [[ -n "$recording" ]]; then
    name="$(basename "$recording")"
    dev_b pull "$recording" "$logs/$name" > /dev/null 2>&1 || true
    [[ -f "$logs/$name" ]] && echo "-- the answerer's recording pulled to $logs/$name"
fi
stop_apps

step "6. question 5: RECORD_AUDIO revoked on the spike app only"
echo "-- revoking RECORD_AUDIO from $intercom_spike_pkg on the answerer"
dev_b shell pm revoke "$intercom_spike_pkg" android.permission.RECORD_AUDIO
dev_b shell dumpsys package "$intercom_spike_pkg" | grep -E 'RECORD_AUDIO' | sed 's/^/  /' || true

echo
echo "-- 5a. the answerer answers with its microphone path (no listen-only): what the OS does"
room="p90-denied-$(date +%s)"
dev_b logcat -c > /dev/null 2>&1 || true
dev_b logcat -v time > "$logs/denied-b-all.log" 2>&1 &
all_log_pid=$!
disown "$all_log_pid" 2>/dev/null || true
start_logs denied
call_b answer --es durationSeconds 40
sleep 1
call_a call --es durationSeconds 40
sleep 20
echo "  the answerer's log:"
grep -hE 'spike: (audio|listen-only|call connected|call first-audio|stats (in|out)|ice-state)' "$b_log" \
    | tail -8 | sed 's/^/    /'
echo "  the caller's log:"
grep -hE 'spike: (call connected|call first-audio|stats in)' "$a_log" | tail -4 | sed 's/^/    /'
stop_logs
kill "$all_log_pid" 2>/dev/null || true
all_log_pid=""
ssh -o ConnectTimeout=10 "$intercom_b_host" \
    "export PATH=$intercom_b_path:\$PATH; pkill -f 'adb -s $intercom_b_serial logcat' || true" > /dev/null 2>&1 || true
sleep 1
echo "  what the audio stack said about the missing permission:"
grep -hiE 'webrtcaudio|audiorecord|RECORD_AUDIO|AudioFlinger.*denied' "$logs/denied-b-all.log" \
    | grep -viE 'GmsContextWrapper|device idle' | head -10 | sed 's/^/    /' || true
stop_apps

echo
echo "-- 5b. the same revoked permission, listen-only on the answerer (the production shape)"
room="p90-listenonly-$(date +%s)"
start_logs listenonly
call_b answer --es durationSeconds 40 --es listenOnly 1
sleep 1
call_a call --es durationSeconds 40
wait_log "$b_log" "listen-only" 20 "the answerer's listen-only mode"
wait_log "$b_log" "call first-audio side=in" 40 "audio arriving at the listen-only answerer"
echo "  answerer inbound: packets=$(inbound_packets "$b_log") (receiving and playing with no RECORD_AUDIO)"
grep -hE 'spike: (listen-only|call connected|call first-audio|stats in kind=audio)' "$b_log" | tail -4 | sed 's/^/    /'
stop_apps
dev_b shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
echo "-- RECORD_AUDIO granted again on the answerer"

step "7. question 6: broadcast"
echo "One sender to two receivers needs a third device; this run has two Fire HD 10 Plus"
echo "units, so broadcast is NOT measured here. The one-to-one call above carries the same"
echo "sender-side code path and the same per-receiver audio track; the sender's CPU with one"
echo "receiver is above. The packet Result records this as unanswered."

if [[ "${TABULA_INTERCOM_COST:-0}" == "1" ]]; then
    step "8. question 7: the 10-minute cost run"
    TABULA_INTERCOM_SECONDS="${TABULA_INTERCOM_SECONDS:-600}" "$here/intercom_cost.sh"
else
    step "8. question 7: the 10-minute cost run"
    echo "Run it separately (it takes 10 minutes and needs a pty):"
    echo "  script -q /dev/null tools/device/intercom_cost.sh"
fi

step "done"
echo "Logs: $logs"
echo "Questions 3 and 7's human half: tools/device/check_p90_owner.sh (1 m, 3 m, same room, clap)."
