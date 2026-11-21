#!/usr/bin/env bash
# P3.16 hardware check (docs/packets/P3.16-illuminance-sensor.md):
#   the illuminance entity exists in HA with device class illuminance and unit
#   lx, covering and uncovering the light sensor moves the value within a few
#   seconds, and a steady room produces no more updates than ADR 0002's
#   ILLUMINANCE_MIN_INTERVAL allows (F-HA-06, ADR 0019).
#
# Prerequisites: the tablet runs a P3.16 build, is paired, the app is in the
# foreground with the panel on, and the environment has:
#   TABULA_HA_URL            e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN          a long-lived access token (any user that may call services)
#   TABULA_ILLUMINANCE       the tablet's illuminance sensor, e.g. sensor.office_kiosk_illuminance
#   TABULA_STEADY_S          the steady-room observation window, seconds (default 180)
#   ANDROID_SERIAL           optional, picks the tablet when several are attached
#
# Steps 2 and 3 ask the verifier to cover and uncover the light sensor (on the
# Fire HD 10 Plus it sits next to the front camera). Paste the whole output
# into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
service_tag=TabulaService

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_ILLUMINANCE:?set TABULA_ILLUMINANCE to the illuminance sensor entity id}"
steady_s="${TABULA_STEADY_S:-180}"

# child processes and curl read the token from the environment
export TABULA_HA_TOKEN

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_state() { # ha_state <entity_id> -> prints the state
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])'
}
ha_attributes() { # ha_attributes <entity_id> -> prints the attributes as JSON
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["attributes"], sort_keys=True))'
}
ha_available() { [[ "$(ha_state "$1")" != unavailable && "$(ha_state "$1")" != unknown ]]; }
wait_for() { # wait_for <description> <timeout s> <command...>
    local what="$1" timeout="$2"; shift 2
    local waited=0
    until "$@"; do
        (( waited >= timeout )) && fail "timed out after ${timeout}s waiting for $what"
        sleep 1; waited=$((waited + 1))
    done
    echo "-- $what after ${waited}s"
}
# a real change per ADR 0002: at least 20 % relative to the old value (a step
# goes out at once), or any change when the room was dark
moved() { # moved <old value or none> - compares against the current HA state
    python3 - "$1" "$(ha_state "$TABULA_ILLUMINANCE")" <<'EOF'
import sys
old, new = sys.argv[1], sys.argv[2]
if old == "unknown":
    sys.exit(1)
if old in ("", "none", "None"):
    sys.exit(0)  # the first reading arrived
o, n = float(old), float(new)
sys.exit(0 if abs(n - o) >= 0.2 * o else 1)
EOF
}
changed_since() { # changed_since <iso8601> - the number of state changes since
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" \
        "${TABULA_HA_URL%/}/api/history/period/$1?filter_entity_id=$TABULA_ILLUMINANCE&minimal_response" \
        | python3 -c 'import json,sys; print(sum(len(states) for states in json.load(sys.stdin)))'
}
ask() { # ask <question> - fails unless the verifier answers y
    local answer
    read -r -p "$1 [y/n] " answer
    [[ "$answer" == y* ]] || fail "verifier answered '$answer' to: $1"
    echo "-- verifier: yes ($1)"
}

step "1. baseline: the tablet is paired and the illuminance entity is live"
adb devices -l
[[ -n "$(adb shell pidof "$pkg" | tr -d '\r')" ]] || fail "Tabula is not running on the tablet"
wait_for "the illuminance sensor to be available in HA" 120 ha_available "$TABULA_ILLUMINANCE"
echo "-- entity: $TABULA_ILLUMINANCE"
echo "-- state: $(ha_state "$TABULA_ILLUMINANCE")"
echo "-- attributes: $(ha_attributes "$TABULA_ILLUMINANCE")"
python3 -c 'import json,sys; a=json.loads(sys.argv[1]); assert a["device_class"]=="illuminance" and a["unit_of_measurement"]=="lx" and a["state_class"]=="measurement" and "entity_category" not in a, a' \
    "$(ha_attributes "$TABULA_ILLUMINANCE")" \
    || fail "the entity is not the P3.16 illuminance sensor (see attributes above)"
echo "-- device class illuminance, unit lx, state class measurement, not diagnostic (ADR 0019)"

step "2. covering and uncovering the sensor moves the value within a few seconds (F-HA-06)"
ask "Cover the tablet's light sensor completely, then answer y"
before="$(ha_state "$TABULA_ILLUMINANCE")"
echo "-- value before: $before"
wait_for "the covered sensor's value to drop (a step change goes out at once)" 20 moved "$before"
covered="$(ha_state "$TABULA_ILLUMINANCE")"
echo "-- value covered: $covered (was $before)"
ask "Uncover the light sensor completely, then answer y"
wait_for "the uncovered sensor's value to rise (a step change goes out at once)" 20 moved "$covered"
uncovered="$(ha_state "$TABULA_ILLUMINANCE")"
echo "-- value uncovered: $uncovered (was $covered)"

step "3. a steady room produces no more updates than ADR 0002 allows (F-HA-06)"
echo "-- observing for ${steady_s}s; leave the room's light alone and hands off the sensor"
since="$(python3 -c 'import datetime; print(datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat())')"
sleep "$steady_s"
now="$(python3 -c 'import datetime; print(datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat())')"
updates="$(changed_since "$since")"
# ILLUMINANCE_MIN_INTERVAL (30 s) bounds sub-threshold updates (ADR 0002);
# the +2 covers the window's edges and one step change
bound="$(python3 -c "print(int($steady_s // 30) + 2)")"
echo "-- state changes in the window ($since to $now): $updates, bound: $bound (one per 30 s + 2)"
(( updates <= bound )) || fail "too many updates in a steady room: $updates > $bound"
echo "-- within ADR 0002's ILLUMINANCE_MIN_INTERVAL bound"

step "4. service log for the record"
adb shell logcat -d -v time -s "$service_tag:I" | tail -20

echo
echo "P3.16 hardware check complete; paste everything above into the packet Result."
