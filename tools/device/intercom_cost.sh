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
    local which="$1" elapsed="$2" cpu total pss batt zone
    local -a d
    if [[ "$which" == "A" ]]; then d=(dev_a); else d=(dev_b); fi
    cpu="$("${d[@]}" shell dumpsys cpuinfo | awk -v p="$intercom_spike_pkg" '$0 ~ p { gsub("%", "", $1); print $1; exit }')"
    total="$("${d[@]}" shell dumpsys cpuinfo | awk '/TOTAL/ { gsub("%", "", $2); print $2; exit }')"
    pss="$("${d[@]}" shell dumpsys meminfo "$intercom_spike_pkg" | awk '/TOTAL PSS:/ { print $3; exit }')"
    batt="$("${d[@]}" shell dumpsys battery | awk '/temperature:/ { print $2; exit }')"
    zone="$("${d[@]}" shell 'cat /sys/class/thermal/thermal_zone*/temp' 2>/dev/null | sort -n | tail -1 || true)"
    echo "$which,$elapsed,${cpu:-},${total:-},${pss:-},${batt:-},${zone:-}" >> "$csv"
    printf '  %s t=%4ss cpu=%s%% (device %s%%) pss=%s kB batt=%s zone=%s\n' \
        "$which" "$elapsed" "${cpu:-?}" "${total:-?}" "${pss:-?}" "${batt:-?}" "${zone:-?}"
}

step "sampling for ${seconds}s (Ctrl-C stops; the summary below needs at least one sample)"
echo "device,elapsed_s,cpu_pct,device_cpu_pct,pss_kb,battery_tenths_c,hottest_zone_milli_c" > "$csv"
elapsed=0
while (( elapsed <= seconds )); do
    sample A "$elapsed"
    sample B "$elapsed"
    sleep "$interval"
    elapsed=$((elapsed + interval))
done

step "summary"
python3 - "$csv" <<'PY'
import csv
import statistics
import sys

path = sys.argv[1]
rows: dict[str, list[dict]] = {}
with open(path, newline="") as handle:
    for row in csv.DictReader(handle):
        rows.setdefault(row["device"], []).append(row)


def stats(rows, key, scale=1.0):
    values = []
    for row in rows:
        try:
            values.append(float(row[key]) / scale)
        except (KeyError, TypeError, ValueError):
            pass
    if not values:
        return "n/a"
    return f"{min(values):.1f} / {statistics.fmean(values):.1f} / {max(values):.1f}"


print("device  samples  cpu % (min/mean/max)  device cpu %  pss MB  battery C  hottest zone C")
for device in sorted(rows):
    data = rows[device]
    print(
        f"{device:6}  {len(data):7}  {stats(data, 'cpu_pct'):21}"
        f"  {stats(data, 'device_cpu_pct'):12}  {stats(data, 'pss_kb', 1024):6}"
        f"  {stats(data, 'battery_tenths_c', 10):9}  {stats(data, 'hottest_zone_milli_c', 1000)}"
    )
PY

step "the call's media, from the logs"
python3 "$here/intercom_report.py" "$logs/cost-a.log" "$logs/cost-b.log" || true

step "done"
echo "CSV: $csv"
echo "Logs: $logs/cost-a.log $logs/cost-b.log"
