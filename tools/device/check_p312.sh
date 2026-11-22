#!/usr/bin/env bash
# P3.12 hardware check (docs/packets/P3.12-schedule.md): the screensaver
# schedule (F-SSV-18) - slot crossings with trigger `schedule`, and a live
# edit of the running slot's brightness (ADR 0023).
#
# Prerequisites: the tablet runs a P3.12 build, is provisioned and paired,
# the app is in the foreground with the panel on, and the tablet is left
# alone for the run (a touch dismisses the screensaver). The environment:
#   TABULA_HA_URL           e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN         a long-lived access token of an ADMIN user
#                           (the options flow needs config-entry edit rights)
#   TABULA_SCREENSAVER      the tablet's screensaver switch, e.g. switch.office_kiosk_screensaver
#   TABULA_DEVICE           the tablet's HA device id (the options flow's
#                           device picker; Settings > Devices > the tabula
#                           device, the id in the page URL)
#   ANDROID_SERIAL          optional, picks the tablet when several are attached
#   TABULA_BLACK_AHEAD       optional minutes until the black slot (default 2)
#   TABULA_CLOCK_AHEAD       optional minutes until the clock slot after that
#                           (default 5; must leave time to watch the black slot)
#   TABULA_SLOT_BRIGHTNESS   optional brightness for the clock slot (default 15)
#   TABULA_SLOT_BRIGHTNESS2  optional brightness the live edit sets (default 10)
#
# The script sets the schedule itself (two slots a few minutes ahead, tablet
# local time) through HA's options-flow API, starts the screensaver, and
# checks the crossings mechanically from the captured tabula_screensaver
# events and the panel's system brightness; the screen's look asks the
# verifier. Paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
black_ahead="${TABULA_BLACK_AHEAD:-2}"
clock_ahead="${TABULA_CLOCK_AHEAD:-5}"
slot_brightness="${TABULA_SLOT_BRIGHTNESS:-15}"
slot_brightness2="${TABULA_SLOT_BRIGHTNESS2:-10}"

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived admin access token}"
: "${TABULA_SCREENSAVER:?set TABULA_SCREENSAVER to the tablet screensaver switch entity id}"
: "${TABULA_DEVICE:?set TABULA_DEVICE to the tablet HA device id}"
schedule_switch="${TABULA_SCREENSAVER%_screensaver}_schedule_enabled"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_call() { # ha_call <domain> <service> <json body>
    curl -fsS -X POST \
        -H "Authorization: Bearer ${TABULA_HA_TOKEN}" -H "Content-Type: application/json" \
        -d "$3" "${TABULA_HA_URL%/}/api/services/$1/$2" > /dev/null
    echo "-- called $1.$2 $3"
}
ha_state() { # ha_state <entity_id> -> prints the state
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])'
}
ha_state_is() { [[ "$(ha_state "$1")" == "$2" ]]; }
ha_post() { # ha_post <path> <json body> -> prints the response JSON
    curl -fsS -X POST \
        -H "Authorization: Bearer ${TABULA_HA_TOKEN}" -H "Content-Type: application/json" \
        -d "$2" "${TABULA_HA_URL%/}$1"
}
system_level() { adb shell settings get system screen_brightness | tr -d '\r'; }
wait_for() { # wait_for <description> <timeout s> <command...>
    local what="$1" timeout="$2"; shift 2
    local waited=0
    until "$@"; do
        (( waited >= timeout )) && fail "timed out after ${timeout}s waiting for $what"
        sleep 1; waited=$((waited + 1))
    done
    echo "-- $what after ${waited}s"
}
ask() { # ask <question> -> fails unless the verifier answers y
    local answer
    read -r -p "$1 [y/n] " answer
    [[ "$answer" == y* ]] || fail "verifier answered '$answer' to: $1"
    echo "-- verifier: yes ($1)"
}
tap_centre() { # only while the screensaver shows: the overlay takes the touch
    local size w h
    size="$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
    read -r w h <<< "$size"
    adb shell input tap $((w / 2)) $((h / 2))
    echo "-- tapped the centre of the screen"
}
events="$PWD/.p312_events.jsonl"
listener_log="$PWD/.p312_events.listen.log"

# The tablet's own clock reads the slots (local wall time, F-SSV-18).
tablet_time() { # tablet_time <epoch seconds> <format>
    adb shell "date -d @$1 +$2" | tr -d '\r'
}

# set_slots <slots YAML> - the options flow: device picker, then the settings
# form; submitting only the schedule fields leaves every other setting as is.
flow_field() { # flow_field <response JSON> <key>
    python3 -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$1" "$2"
}
flow_form_is() { # flow_form_is <response JSON> <step id>
    python3 -c 'import json,sys; d=json.loads(sys.argv[1]); sys.exit(0 if d.get("type")=="form" and d.get("step_id")==sys.argv[2] else 1)' \
        "$1" "$2"
}
flow_saved() { # flow_saved <response JSON>
    python3 -c 'import json,sys; sys.exit(0 if json.loads(sys.argv[1]).get("type")=="create_entry" else 1)' "$1"
}
set_slots() {
    step "push the schedule through the options flow"
    local entries entry_id init form reply
    entries="$(ha_post /api/config/config_entries/entry/list '{}')" || true
    if [[ -z "$entries" ]]; then
        entries="$(ha_post /api/config/config_entries/entry '{}')" || true
    fi
    [[ -n "$entries" ]] || fail "no config entry list from HA"
    entry_id="$(python3 -c '
import json, sys
entries = json.loads(sys.argv[1])
tabula = [e for e in entries if e.get("domain") == "tabula"]
if not tabula:
    sys.exit("no tabula config entry found")
if len(tabula) > 1:
    sys.exit("several tabula entries: " + ", ".join(e.get("title", e["entry_id"]) for e in tabula))
print(tabula[0]["entry_id"])
' "$entries")" || fail "could not pick the tabula config entry"
    echo "-- config entry: $entry_id"
    init="$(ha_post /api/config/config_entries/options/flow "{\"handler\": \"$entry_id\"}")" \
        || fail "the options flow did not open"
    flow_form_is "$init" init || fail "the options flow did not open on the device picker: $init"
    form="$(ha_post /api/config/config_entries/options/flow/"$(flow_field "$init" flow_id)" \
        "{\"device_id\": \"$TABULA_DEVICE\"}")" || fail "the device step did not submit"
    flow_form_is "$form" device || fail "the device step did not open: $form"
    reply="$(ha_post /api/config/config_entries/options/flow/"$(flow_field "$form" flow_id)" \
        "{\"schedule_enabled\": true, \"schedule_slots\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1")}")" \
        || fail "the slots did not submit"
    flow_saved "$reply" || fail "the flow did not save the slots: $reply"
    echo "-- options flow saved the slots"
}

# events_seen - every captured event so far, one per line, for the checks.
events_seen() { [[ -f "$events" ]] && cat "$events" || true; }
schedule_events() { # schedule_events -> prints the schedule-triggered events
    events_seen | python3 -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    d = json.loads(line).get("data", {})
    if d.get("trigger") == "schedule":
        print(d.get("type"), d.get("mode"), d.get("trigger"))
'
}
has_schedule_event() { # has_schedule_event <type> <mode>
    [[ "$(schedule_events)" == *"$1 $2 schedule"* ]]
}

step "1. app running, screensaver off, no leftover schedule"
adb devices -l
adb shell pidof "$pkg" || fail "Tabula is not running"
ha_state_is "$TABULA_SCREENSAVER" off || fail "the screensaver is already on"
ha_state_is "$schedule_switch" off || echo "-- note: the schedule switch was already on"
day_level="$(system_level)"
echo "-- system brightness before: $day_level"
rm -f "$events" "$listener_log"
listen_seconds=$(( clock_ahead * 60 + 240 ))
(uv run python tools/device/ha_events.py --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" \
    --event tabula_screensaver --seconds "$listen_seconds" > "$events" 2> "$listener_log") &
listener=$!
echo "-- listening for tabula_screensaver events (${listen_seconds}s)"

step "2. two slots a few minutes ahead: black, then clock"
now_s="$(adb shell date +%s | tr -d '\r')"
black_s=$(( now_s + black_ahead * 60 ))
clock_s=$(( now_s + clock_ahead * 60 ))
black_start="$(tablet_time "$black_s" '%H:%M')"
clock_start="$(tablet_time "$clock_s" '%H:%M')"
echo "-- tablet time $(tablet_time "$now_s" '%H:%M'); slots: $black_start black, $clock_start clock (brightness $slot_brightness)"
set_slots "- start: \"$black_start\"
  mode: black
- start: \"$clock_start\"
  mode: clock
  brightness: $slot_brightness
"
wait_for "the schedule switch to turn on" 30 ha_state_is "$schedule_switch" on

step "3. the screensaver starts in clock, then crosses into black"
ha_call tabula screensaver "{\"entity_id\": \"$TABULA_SCREENSAVER\", \"active\": true, \"mode\": \"clock\"}"
wait_for "the screensaver switch to turn on" 10 ha_state_is "$TABULA_SCREENSAVER" on
sleep $(( black_ahead * 60 + 20 ))
wait_for "the crossing into black to fire" 60 has_schedule_event screensaver_started black
ha_state_is "$TABULA_SCREENSAVER" on || fail "the screensaver stopped at the black crossing"
echo "-- schedule events so far:"
schedule_events
black_level="$(system_level)"
echo "-- system brightness in black: $black_level (expect the lowest, 1)"
[[ "$black_level" == 1 ]] || fail "black is not holding the lowest backlight: $black_level"
ask "Is the screen black at ${black_start} (the black slot), not the clock?"

step "4. the second crossing: back to clock at $clock_start"
sleep $(( (clock_ahead - black_ahead) * 60 + 20 ))
wait_for "the crossing into clock to fire" 60 has_schedule_event screensaver_started clock
echo "-- all schedule-triggered events:"
schedule_events
python3 - "$events" <<'EOF' || fail "the crossing events are not the expected sequence"
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
data = [l["data"] for l in lines]
trigger = [(d["type"], d["mode"]) for d in data if d.get("trigger") == "schedule"]
expected = [
    ("screensaver_stopped", "clock"),
    ("screensaver_started", "black"),
    ("screensaver_stopped", "black"),
    ("screensaver_started", "clock"),
]
sys.exit(0 if trigger == expected else 1)
EOF
echo "-- the four schedule events are exactly: clock->black->clock crossings"
slot_level="$(system_level)"
echo "-- system brightness in the clock slot: $slot_level (the slot's $slot_brightness)"
[[ "$slot_level" == "$slot_brightness" ]] || fail "the clock slot is not holding its brightness: $slot_level"
ask "Is the clock showing again at ${clock_start}?"

step "5. editing the running slot's brightness applies live (no restart)"
before="$(schedule_events)"
set_slots "- start: \"$black_start\"
  mode: black
- start: \"$clock_start\"
  mode: clock
  brightness: $slot_brightness2
"
sleep 3
after="$(schedule_events)"
[[ "$before" == "$after" ]] || fail "the brightness edit restarted the screensaver: $after"
echo "-- no new schedule events: the run kept going"
ha_state_is "$TABULA_SCREENSAVER" on || fail "the screensaver stopped on the brightness edit"
edited_level="$(system_level)"
echo "-- system brightness after the edit: $edited_level (the edited $slot_brightness2)"
[[ "$edited_level" == "$slot_brightness2" ]] || fail "the live edit did not apply: $edited_level"

step "6. cleanup: dismiss, restore, schedule off"
tap_centre
wait_for "the screensaver switch to turn off" 10 ha_state_is "$TABULA_SCREENSAVER" off
sleep 2
echo "-- system brightness after dismissal: $(system_level) (expect $day_level)"
ha_call switch turn_off "{\"entity_id\": \"$schedule_switch\"}"
wait_for "the schedule switch to turn off" 30 ha_state_is "$schedule_switch" off
kill "$listener" 2>/dev/null || true
sleep 1

echo
echo "-- captured tabula_screensaver events:"
cat "$events"
rm -f "$events" "$listener_log"
echo
echo "P3.12 hardware check complete; paste everything above into the packet Result."
