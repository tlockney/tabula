#!/usr/bin/env bash
[[ "${TABULA_AUDIO_OK:-0}" == 1 ]] || { echo "refusing to run: audio device tests must be scheduled with the owner; set TABULA_AUDIO_OK=1 only for an approved run" >&2; exit 2; }
# P9.0's cost run (packet question 7): CPU, memory and temperature on both
# kiosks during one call, sampled every interval for ten minutes by default,
# so the numbers repeat. It places the call itself.
#
# Run it under a pty so Ctrl-C reaches the script:
#   script -q /dev/null tools/device/intercom_cost.sh
#
# Environment:
#   TABULA_INTERCOM_SECONDS   call length (default 600)
#   TABULA_INTERCOM_INTERVAL  sample interval, seconds (default 30)
#   TABULA_INTERCOM_WINDOW    the CPU measurement window, seconds (default 5)
#   TABULA_INTERCOM_APK       the spike APK (default the arm64-v8a debug split)
#   TABULA_HOST_IP, TABULA_INTERCOM_PORT, and the device variables in
#   tools/device/intercom_devices.sh
#
# Output: one CSV line per sample per device in build/intercom-spike/, then a
# summary table. Paste the whole output into the packet Result section.
#
# Why /proc rather than `dumpsys cpuinfo` for the app's own CPU: on the Fire HD
# 10 Plus, dumpsys cpuinfo reported "+0%" for a process that was plainly
# decoding and mixing audio on one unit and 46 % on the other in the same call.
# The app's CPU here comes from /proc/<pid>/stat's utime+stime against
# /proc/stat's total ticks over the same window, which needs no sampling luck.
# The device-wide TOTAL and the audio HAL figures still come from dumpsys
# cpuinfo, as a cross-check.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=intercom_devices.sh
source "$here/intercom_devices.sh"

seconds="${TABULA_INTERCOM_SECONDS:-600}"
interval="${TABULA_INTERCOM_INTERVAL:-30}"
window="${TABULA_INTERCOM_WINDOW:-5}"
apk="${TABULA_INTERCOM_APK:-$root/android/spike-intercom/build/outputs/apk/debug/spike-intercom-arm64-v8a-debug.apk}"
port="${TABULA_INTERCOM_PORT:-8099}"
host_ip="${TABULA_HOST_IP:-$(ipconfig getifaddr en0 2>/dev/null || true)}"
logs="$root/build/intercom-spike"
csv="$logs/cost-$(date +%s).csv"
room="p90-cost-$(date +%s)"
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

mkdir -p "$logs"
[[ -f "$apk" ]] || fail "no APK at $apk (run make android-spike-apk)"
[[ -n "$host_ip" ]] || fail "could not read this machine's LAN address; set TABULA_HOST_IP"

step "preparing"
intercom_install dev_a "$apk" > /dev/null
intercom_install dev_b "$apk" > /dev/null
dev_a shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
dev_b shell pm grant "$intercom_spike_pkg" android.permission.RECORD_AUDIO
relay_url="http://$host_ip:$port"
if ! curl -fsS --max-time 2 "$relay_url/poll?room=probe&role=call&since=0" > /dev/null 2>&1; then
    python3 "$here/intercom_relay.py" --port "$port" > "$logs/relay-cost.log" 2>&1 &
    relay_pid=$!
    sleep 1
fi
echo "-- relay $relay_url, room $room, ${seconds}s at ${interval}s intervals"

dev_a logcat -c > /dev/null 2>&1 || true
dev_b logcat -c > /dev/null 2>&1 || true
dev_a logcat -v time -s TabulaIntercomSpike:I > "$logs/cost-a.log" 2>&1 &
a_log_pid=$!
disown "$a_log_pid" 2>/dev/null || true
dev_b logcat -v time -s TabulaIntercomSpike:I > "$logs/cost-b.log" 2>&1 &
b_log_pid=$!
disown "$b_log_pid" 2>/dev/null || true

dev_a shell am force-stop "$intercom_spike_pkg" || true
dev_b shell am force-stop "$intercom_spike_pkg" || true
sleep 1
start_service dev_b -n "$intercom_spike_pkg/.IntercomCallService" \
    -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role answer \
    --es durationSeconds "$((seconds + 60))" > /dev/null
sleep 1
start_service dev_a -n "$intercom_spike_pkg/.IntercomCallService" \
    -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role call \
    --es durationSeconds "$((seconds + 60))" > /dev/null

waited=0
until grep -q "call connected" "$logs/cost-a.log" 2>/dev/null; do
    (( waited >= 30 )) && fail "the call did not connect"
    sleep 1
    waited=$((waited + 1))
done
echo "-- call connected after ${waited}s"

# One shell command per device per reading: the pid, the machine's total CPU
# ticks, its core count and the process's own ticks. No per-core sampling luck.
proc_cmd="p=\$(pidof $intercom_spike_pkg); t=\$(awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=\$i; print s}' /proc/stat); n=\$(grep -c '^cpu[0-9]' /proc/stat); u=\$(awk '{print \$14+\$15}' /proc/\$p/stat 2>/dev/null); echo \"\${p:-none}|\${t:-0}|\${n:-0}|\${u:-0}\""

read_proc() { # read_proc <A|B>
    if [[ "$1" == "A" ]]; then dev_a shell "$proc_cmd"; else dev_b shell "$proc_cmd"; fi
}

cpu_core() { # cpu_core "<t0 reading>" "<t1 reading>" -> the app's CPU as % of one core
    printf '%s\n%s\n' "$1" "$2" | awk -F'|' '
        NR == 1 { ta = $2; n = $3; ua = $4 }
        NR == 2 { tb = $2; ub = $4 }
        END {
            if (n > 0 && tb - ta > 0) printf "%.1f", 100 * (ub - ua) * n / (tb - ta)
            else printf "n/a"
        }
    '
}

snapshot() { # snapshot <A|B> -> "device_total|audio_hal|pss_kb|battery_tenths"
    local which="$1" out cpu_part mem_part batt_part
    local -a d
    if [[ "$which" == "A" ]]; then d=(dev_a); else d=(dev_b); fi
    out="$("${d[@]}" shell "echo ===CPU; dumpsys cpuinfo; echo ===MEM; dumpsys meminfo $intercom_spike_pkg; echo ===BATT; dumpsys battery" | tr -d '\r')"
    cpu_part="$(printf '%s\n' "$out" | sed -n '/===CPU/,/===MEM/p')"
    mem_part="$(printf '%s\n' "$out" | sed -n '/===MEM/,/===BATT/p')"
    batt_part="$(printf '%s\n' "$out" | sed -n '/===BATT/,$p')"
    printf '%s|%s|%s|%s\n' \
        "$(printf '%s\n' "$cpu_part" | awk '/TOTAL:/ { v=$1; gsub(/[+%]/, "", v); print v; exit }')" \
        "$(printf '%s\n' "$cpu_part" | awk '/fireos.hardware.audio/ { v=$1; gsub(/[+%]/, "", v); print v; exit }')" \
        "$(printf '%s\n' "$mem_part" | awk '/^ *TOTAL:/ { print $2; exit }')" \
        "$(printf '%s\n' "$batt_part" | awk '/temperature:/ { print $2; exit }')"
}

step "sampling for ${seconds}s (Ctrl-C stops; the summary below needs at least one sample)"
echo "-- cpu percentages are shares of ONE core (the unit /proc and dumpsys cpuinfo"
echo "   both use here): 50 % means half of one core, not half the device."
echo "device,elapsed_s,cpu_pct,device_cpu_pct,audio_hal_pct,pss_kb,battery_tenths_c" > "$csv"
elapsed=0
while (( elapsed <= seconds )); do
    a0="$(read_proc A)"
    b0="$(read_proc B)"
    sleep "$window"
    a1="$(read_proc A)"
    b1="$(read_proc B)"
    IFS='|' read -r a_total a_hal a_pss a_batt <<< "$(snapshot A)"
    IFS='|' read -r b_total b_hal b_pss b_batt <<< "$(snapshot B)"
    a_cpu="$(cpu_core "$a0" "$a1")"
    b_cpu="$(cpu_core "$b0" "$b1")"
    echo "A,$elapsed,$a_cpu,$a_total,$a_hal,$a_pss,$a_batt" >> "$csv"
    echo "B,$elapsed,$b_cpu,$b_total,$b_hal,$b_pss,$b_batt" >> "$csv"
    printf '  A t=%4ss app=%s%% of one core, device total=%s%%, audio hal=%s%%, pss=%s kB, battery=%s.%s C\n' \
        "$elapsed" "$a_cpu" "$a_total" "$a_hal" "$a_pss" "$(( ${a_batt:-0} / 10 ))" "$(( ${a_batt:-0} % 10 ))"
    printf '  B t=%4ss app=%s%% of one core, device total=%s%%, audio hal=%s%%, pss=%s kB, battery=%s.%s C\n' \
        "$elapsed" "$b_cpu" "$b_total" "$b_hal" "$b_pss" "$(( ${b_batt:-0} / 10 ))" "$(( ${b_batt:-0} % 10 ))"
    if (( interval > window )); then
        sleep $((interval - window))
    else
        sleep 1
    fi
    elapsed=$((elapsed + interval))
done

step "summary"
python3 - "$csv" <<'PY'
import csv
import statistics
import sys

rows: dict[str, list[dict]] = {}
with open(sys.argv[1], newline="") as handle:
    for row in csv.DictReader(handle):
        rows.setdefault(row["device"], []).append(row)


def stats(data, key, scale=1.0):
    values = []
    for row in data:
        try:
            values.append(float(row[key]) / scale)
        except (KeyError, TypeError, ValueError):
            pass
    if not values:
        return "n/a"
    return f"{min(values):.1f}/{statistics.fmean(values):.1f}/{max(values):.1f}"


print(
    "device samples  app cpu % of one core (min/mean/max)  device total %  "
    "audio hal %  pss MB (mean)  battery C (min/mean/max)"
)
for device in sorted(rows):
    data = rows[device]
    print(
        f"{device:6} {len(data):7}  {stats(data, 'cpu_pct'):38}"
        f"  {stats(data, 'device_cpu_pct'):13}  {stats(data, 'audio_hal_pct'):11}"
        f"  {stats(data, 'pss_kb', 1024):13}  {stats(data, 'battery_tenths_c', 10)}"
    )
PY

step "the call's media, from the logs"
python3 "$here/intercom_report.py" "$logs/cost-a.log" "$logs/cost-b.log" || true

step "done"
echo "CSV: $csv"
echo "Logs: $logs/cost-a.log $logs/cost-b.log"
