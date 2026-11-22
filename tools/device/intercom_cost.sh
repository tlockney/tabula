#!/usr/bin/env bash
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
#   TABULA_INTERCOM_APK       the spike APK (default the arm64-v8a debug split)
#   TABULA_HOST_IP, TABULA_INTERCOM_PORT, and the device variables in
#   tools/device/intercom_devices.sh
#
# Output: one CSV line per sample per device in build/intercom-spike/, then a
# summary table. Paste the whole output into the packet Result section.

set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=intercom_devices.sh
source "$here/intercom_devices.sh"

seconds="${TABULA_INTERCOM_SECONDS:-600}"
interval="${TABULA_INTERCOM_INTERVAL:-30}"
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
dev_b logcat -v time -s TabulaIntercomSpike:I > "$logs/cost-b.log" 2>&1 &
b_log_pid=$!

dev_a shell am force-stop "$intercom_spike_pkg" || true
dev_b shell am force-stop "$intercom_spike_pkg" || true
sleep 1
dev_b shell am start-foreground-service -n "$intercom_spike_pkg/.IntercomCallService" \
    -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role answer \
    --es durationSeconds "$((seconds + 30))" > /dev/null
sleep 1
dev_a shell am start-foreground-service -n "$intercom_spike_pkg/.IntercomCallService" \
    -a "$intercom_spike_pkg.START" --es relay "$relay_url" --es room "$room" --es role call \
    --es durationSeconds "$((seconds + 30))" > /dev/null

waited=0
until grep -q "call connected" "$logs/cost-a.log" 2>/dev/null; do
    (( waited >= 30 )) && fail "the call did not connect"
    sleep 1
    waited=$((waited + 1))
done
echo "-- call connected after ${waited}s"

sample() { # sample <A|B> <elapsed s>
    local which="$1" elapsed="$2" cpu total hal pss batt snapshot cpu_part mem_part batt_part
    local -a d
    if [[ "$which" == "A" ]]; then d=(dev_a); else d=(dev_b); fi
    # One round trip per device per sample: three dumpsys calls over one adb
    # shell, split apart here. (Six separate calls, each a fresh ssh to the other
    # host, took longer than the sampling interval.)
    snapshot="$("${d[@]}" shell "echo ===CPU; dumpsys cpuinfo; echo ===MEM; dumpsys meminfo $intercom_spike_pkg; echo ===BATT; dumpsys battery" | tr -d '\r')"
    cpu_part="$(printf '%s\n' "$snapshot" | sed -n '/===CPU/,/===MEM/p')"
    mem_part="$(printf '%s\n' "$snapshot" | sed -n '/===MEM/,/===BATT/p')"
    batt_part="$(printf '%s\n' "$snapshot" | sed -n '/===BATT/,$p')"
    # dumpsys cpuinfo reports each process as a share of ONE core, plus a
    # device-wide TOTAL line. The audio HAL is listed separately and is a large
    # share of a call's cost, so it gets its own column. Fire OS 7 exposes no
    # readable thermal zones on this device, so battery temperature stands in.
    cpu="$(printf '%s\n' "$cpu_part" | awk -v p="$intercom_spike_pkg" '$0 ~ p { v=$1; gsub(/[+%]/, "", v); print v; exit }')"
    total="$(printf '%s\n' "$cpu_part" | awk '/TOTAL:/ { v=$1; gsub(/[+%]/, "", v); print v; exit }')"
    hal="$(printf '%s\n' "$cpu_part" | awk '/fireos.hardware.audio/ { v=$1; gsub(/[+%]/, "", v); print v; exit }')"
    pss="$(printf '%s\n' "$mem_part" | awk '/^ *TOTAL:/ { print $2; exit }')"
    batt="$(printf '%s\n' "$batt_part" | awk '/temperature:/ { print $2; exit }')"
    echo "$which,$elapsed,${cpu:-},${total:-},${hal:-},${pss:-},${batt:-}" >> "$csv"
    printf '  %s t=%4ss app cpu=%s%% of one core, device total=%s%%, audio hal=%s%%, pss=%s kB, battery=%s.%s C\n' \
        "$which" "$elapsed" "${cpu:-?}" "${total:-?}" "${hal:-?}" "${pss:-?}" \
        "$(( ${batt:-0} / 10 ))" "$(( ${batt:-0} % 10 ))"
}

step "sampling for ${seconds}s (Ctrl-C stops; the summary below needs at least one sample)"
echo "device,elapsed_s,cpu_pct,device_cpu_pct,audio_hal_pct,pss_kb,battery_tenths_c" > "$csv"
elapsed=0
while (( elapsed <= seconds )); do
    sample A "$elapsed"
    sample B "$elapsed"
    sleep "$interval"
    elapsed=$((elapsed + interval))
done

step "summary"
echo "-- cpu percentages are shares of ONE core (dumpsys cpuinfo's unit): 50 % means"
echo "   half of one core, not half the device. Device total is the TOTAL line."
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


print("device  samples  app cpu % of one core (min/mean/max)  device total %  audio hal %  pss MB (mean)  battery C (min/mean/max)")
for device in sorted(rows):
    data = rows[device]
    print(
        f"{device:6}  {len(data):7}  {stats(data, 'cpu_pct'):38}"
        f"  {stats(data, 'device_cpu_pct'):13}  {stats(data, 'audio_hal_pct'):11}"
        f"  {stats(data, 'pss_kb', 1024):13}  {stats(data, 'battery_tenths_c', 10)}"
    )
PY

step "the call's media, from the logs"
python3 "$here/intercom_report.py" "$logs/cost-a.log" "$logs/cost-b.log" || true

step "done"
echo "CSV: $csv"
echo "Logs: $logs/cost-a.log $logs/cost-b.log"
