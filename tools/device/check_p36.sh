#!/usr/bin/env bash
# P3.6 hardware check (docs/packets/P3.6-at-a-glance.md):
#   with two or three entity ids configured, the At a Glance row appears on the
#   screensaver with live values; changing an entity's state in HA updates the
#   chip within a few seconds while the screensaver shows; dismissing the
#   screensaver closes the entity subscription (the connection's own lifecycle
#   lines); night mode hides or recolors the row per settings (F-AAG-01 to
#   F-AAG-04, F-SSV-10; logcat lines from TabulaGlance are the row's evidence,
#   TabulaService's `entity data:` lines the subscription's).
#
# Prerequisites: the tablet runs a P3.6 build, is provisioned
# (tools/device/provision.sh) and paired, the app is in the foreground with the
# panel on, and the tablet's HA entry has these settings (its Configure page):
#   screensaver mode: clock
#   at_a_glance.entities: a lock and a door/garage sensor, e.g.
#     ["lock.front_door","binary_sensor.garage_door"]
#   at_a_glance.style: pill, scale 100, hide_names off, font sans-serif
# The environment has:
#   TABULA_HA_URL            e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN          a long-lived access token (reads only; this script
#                            calls no service)
#   ANDROID_SERIAL           optional, picks the tablet when several are attached
#
# This script only reads: it queries HA's REST states and dumps the tablet's
# log buffer. It starts nothing, changes no setting and calls no service - the
# verifier starts the screensaver, toggles the entities and dismisses it, and
# the script checks what the tablet logged at that point and asks about the
# panel. Night mode is not exercised here: the row's night flag is the
# never-night default until P3.4 lands, so the night step runs as part of
# P3.4's hardware check (the packet's "Done when" says so).
#
# Paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
# shellcheck source=prompt.sh
source "$(dirname "$0")/prompt.sh"

ha_state() { # ha_state <entity_id> -> prints the state
    curl -fsS -H "Authorization: Bearer ${TABULA_HA_TOKEN}" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])'
}
row_lines() { adb logcat -d -s TabulaGlance:I 2>/dev/null | grep -c "row: " || true; }
chip_line() { # chip_line <entity_id> -> the row's last line for that entity
    adb logcat -d -s TabulaGlance:I 2>/dev/null | grep "chip $1:" | tail -1 || true
}
subscribed() { # subscribed: the subscription's own lifecycle line is present
    adb logcat -d -s TabulaService:I 2>/dev/null | grep "entity data: subscribed to" | tail -1
}
closed() { # closed: the subscription was closed with the screensaver
    adb logcat -d -s TabulaService:I 2>/dev/null | grep "entity data: closed" | tail -1
}

step "1. app running, and the row's entities in HA"
adb devices -l
adb shell pidof "$pkg" || fail "Tabula is not running"
echo "-- panel: $(adb shell wm size | sed -n 's/.*: //p' | tail -1) (landscape expected: the row takes one line)"
entities="$(adb logcat -d -s TabulaGlance:I 2>/dev/null | grep -o 'chip [a-z_]*\.[a-z0-9_]*:' | sed 's/^chip //; s/:$//' | sort -u | tr '\n' ' ' || true)"
if [[ -n "$entities" ]]; then
    echo "-- entities the row has drawn before: $entities"
    for id in $entities; do echo "-- HA says $id = $(ha_state "$id")"; done
else
    echo "-- the row has not drawn yet; start the screensaver in step 2"
fi
pause "Have you configured at_a_glance.entities with a lock and a door or garage sensor?"

step "2. the row appears on the clock screensaver with live values"
before="$(row_lines)"
echo "-- start the screensaver from HA (the tablet's screensaver switch, or tabula.screensaver)"
pause "Start the screensaver now"
sleep 3
after="$(row_lines)"
(( after > before )) || fail "no new TabulaGlance 'row:' line after the screensaver started (is the screensaver up, and are entities configured?)"
adb logcat -d -s TabulaGlance:I 2>/dev/null | grep "row: " | tail -1
for id in $entities; do chip_line "$id"; done
ask "Is the row under the clock's date line, one chip per configured entity, each with an icon, a name and the value HA shows?"

step "3. a state change in HA reaches the chip within a few seconds"
echo "-- in HA, change one configured entity (e.g. unlock the lock, or open the door)."
pause "Have you changed it?"
changed_id="${entities%% *}"
[[ -n "$changed_id" ]] || fail "no configured entity to watch (step 1 saw none)"
old="$(chip_line "$changed_id")"
echo "-- before: ${old:-<none>} (HA now says $(ha_state "$changed_id"))"
sleep 6
new="$(chip_line "$changed_id")"
echo "-- after:  ${new:-<none>}"
[[ -n "$new" ]] || fail "the row logged no chip line for $changed_id"
[[ "$new" != "$old" ]] || fail "the chip's value did not change within 6 s of the HA change"
ask "Did the chip's value on the tablet change to HA's new state (capitalized as HA shows it, with the unit for a number)?"

step "4. dismissing the screensaver closes the subscription"
echo "-- dismiss the screensaver (a touch on the panel)"
pause "Dismiss it now"
sleep 3
echo "-- subscription: $(subscribed)"
closed || fail "no 'entity data: closed' line after the dismissal (F-AAG-04: the subscription closes with the screensaver)"
ask "Is the dashboard back, with the row gone?"

step "5. the night step belongs to P3.4"
echo "-- the row's night flag is the never-night default until P3.4 merges, so a night"
echo "-- run here would check nothing. P3.4's hardware check covers hiding and"
echo "-- recoloring with screensaver.night_mode.hide_overlays on and off."

echo
echo "== PASS: P3.6 hardware check complete"
