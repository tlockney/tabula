#!/usr/bin/env bash
# P2.5 hardware check (docs/packets/P2.5-notifications-and-audio.md):
#   an HA automation shows a card with a chime and a TTS announcement, and the
#   tabula_notification and tabula_media events are visible in HA.
#
# Prerequisites: the tablet runs a P2.5 build, is provisioned and paired, the
# app is in the foreground with the panel on, HA has a TTS entity (Settings ->
# Devices & services -> Add integration -> Text-to-Speech, e.g. the Google
# Translate one), and the environment has:
#   TABULA_HA_URL           e.g. http://homeassistant.local:8123
#   TABULA_HA_TOKEN         a long-lived access token (any user that may call services)
#   TABULA_MEDIA            the tablet's media_player entity, e.g. media_player.office_kiosk_speaker
#   TABULA_TTS              a TTS entity, e.g. tts.google_translate_com
#   ANDROID_SERIAL          optional, picks the tablet when several are attached
#
# The check asks the verifier what the tablet shows and whether sounds were
# heard; paste the whole output into the packet Result section.

set -euo pipefail

serial="${ANDROID_SERIAL:-}"
adb() {
    if [[ -n "$serial" ]]; then command adb -s "$serial" "$@"; else command adb "$@"; fi
}
pkg=net.lockney.tabula
here="$(cd "$(dirname "$0")" && pwd)"

: "${TABULA_HA_URL:?set TABULA_HA_URL, e.g. http://homeassistant.local:8123}"
: "${TABULA_HA_TOKEN:?set TABULA_HA_TOKEN to a long-lived access token}"
: "${TABULA_MEDIA:?set TABULA_MEDIA to the tablet media_player entity id}"
: "${TABULA_TTS:?set TABULA_TTS to a TTS entity id, e.g. tts.google_translate_com}"

step() { echo; echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

ha_call() { # ha_call <domain> <service> <json body>
    curl -fsS -X POST \
        -H "Authorization: Bearer $TABULA_HA_TOKEN" -H "Content-Type: application/json" \
        -d "$3" "${TABULA_HA_URL%/}/api/services/$1/$2" > /dev/null
    echo "-- called $1.$2 $3"
}
ha_state() { # ha_state <entity_id> -> prints the state
    curl -fsS -H "Authorization: Bearer $TABULA_HA_TOKEN" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])'
}
ha_attr() { # ha_attr <entity_id> <attribute> -> prints the attribute
    curl -fsS -H "Authorization: Bearer $TABULA_HA_TOKEN" "${TABULA_HA_URL%/}/api/states/$1" \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['attributes'].get('$2'))"
}
ha_state_is() { [[ "$(ha_state "$1")" == "$2" ]]; }
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

events_log="$(mktemp)"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_events.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" --event tabula_notification --seconds 900 \
    > "$events_log" &
listener=$!
media_log="$(mktemp)"
UV_CONFIG_FILE="$here/../uv.toml" \
    uv run --directory "$here/.." python tools/device/ha_events.py \
    --ha "$TABULA_HA_URL" --token "$TABULA_HA_TOKEN" --event tabula_media --seconds 900 \
    > "$media_log" &
media_listener=$!
trap 'kill "$listener" "$media_listener" 2>/dev/null || true' EXIT

step "1. app running, media player idle"
adb devices -l
adb shell pidof "$pkg" || fail "Tabula is not running"
sleep 3
kill -0 "$listener" 2>/dev/null || fail "the HA event listener did not start"
kill -0 "$media_listener" 2>/dev/null || fail "the HA media event listener did not start"
ha_state_is "$TABULA_MEDIA" unavailable && fail "the media_player entity is unavailable (is the tablet connected?)"
echo "-- media player state: $(ha_state "$TABULA_MEDIA"), volume: $(ha_attr "$TABULA_MEDIA" volume_level)"

step "2. HA shows a warning card with a chime"
ha_call tabula notify "{\"entity_id\": \"$TABULA_MEDIA\", \"message\": \"The washer has finished\", \"title\": \"Laundry\", \"level\": \"warning\", \"chime\": true, \"tag\": \"p25\"}"
sleep 2
ask "Does the tablet show a card titled Laundry over the dashboard (top of the screen)?"
ask "Did the tablet chime when it appeared?"

step "3. the same tag replaces the card"
ha_call tabula notify "{\"entity_id\": \"$TABULA_MEDIA\", \"message\": \"The dryer has finished too\", \"title\": \"Laundry\", \"level\": \"warning\", \"chime\": false, \"tag\": \"p25\"}"
sleep 2
ask "Is the card now the 'dryer' message (the same tag replaced it)?"

step "4. a short card times out by itself"
ha_call tabula notify "{\"entity_id\": \"$TABULA_MEDIA\", \"message\": \"This card removes itself in five seconds\", \"level\": \"info\", \"duration\": 5, \"tag\": \"p25-timed\"}"
sleep 2
ask "Is the timed card showing?"
sleep 6
ask "Has the timed card gone by itself (the tag card may still show)?"

step "5. a card survives over the screensaver (F-NTF-12)"
ha_call tabula screensaver "{\"entity_id\": \"$TABULA_MEDIA\", \"active\": true}"
sleep 2
ha_call tabula notify "{\"entity_id\": \"$TABULA_MEDIA\", \"message\": \"Card over the screensaver\", \"level\": \"error\", \"duration\": 30, \"tag\": \"p25-over\"}"
sleep 2
ask "Does the error card draw on top of the screensaver, with the screensaver still showing?"
# a tap on the card removes only the card; the screensaver stays (F-NTF-12)
adb_shell_tap() {
    local size w h
    size="$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
    read -r w h <<< "$size"
    adb shell input tap $((w / 2)) $((h / 8))
}
adb_shell_tap
sleep 2
ask "Did tapping the card remove the card and leave the screensaver up?"
ha_call tabula screensaver "{\"entity_id\": \"$TABULA_MEDIA\", \"active\": false}"

step "6. TTS speaks through the media player (tts.speak)"
ha_call tts speak "{\"entity_id\": \"$TABULA_TTS\", \"media_player_entity_id\": \"$TABULA_MEDIA\", \"media\": \"The P2.5 hardware check is speaking\"}"
wait_for "the media player to play" 20 ha_state_is "$TABULA_MEDIA" playing
echo "-- media player: $(ha_state "$TABULA_MEDIA")"
ask "Did you hear the announcement from the tablet?"
wait_for "the media player to return to idle" 30 ha_state_is "$TABULA_MEDIA" idle

step "7. HA sets volume and mute through media_control"
ha_call media_player volume_set "{\"entity_id\": \"$TABULA_MEDIA\", \"volume_level\": 0.8}"
sleep 2
echo "-- volume attribute in HA: $(ha_attr "$TABULA_MEDIA" volume_level) (expect 0.8)"
[[ "$(ha_attr "$TABULA_MEDIA" volume_level)" == 0.8* ]] || fail "the volume did not reach 0.8"
ha_call media_player volume_mute "{\"entity_id\": \"$TABULA_MEDIA\", \"is_volume_muted\": true}"
sleep 2
[[ "$(ha_attr "$TABULA_MEDIA" is_volume_muted)" == True ]] || fail "the player did not report muted"
ha_call media_player volume_mute "{\"entity_id\": \"$TABULA_MEDIA\", \"is_volume_muted\": false}"
sleep 2
ha_call media_player volume_set "{\"entity_id\": \"$TABULA_MEDIA\", \"volume_level\": 0.5}"

step "8. tabula_notification and tabula_media events received by HA"
sleep 2
echo "-- tabula_notification events:"
cat "$events_log"
echo "-- tabula_media events:"
cat "$media_log"
count="$(grep -c '"data"' "$events_log" || true)"
(( count >= 6 )) || fail "expected at least 6 tabula_notification events (shown, replaced, shown, shown, timeout, shown, dismissed), got $count"
mcount="$(grep -c '"data"' "$media_log" || true)"
(( mcount >= 1 )) || fail "expected at least 1 tabula_media (media_finished) event, got $mcount"
echo "-- $count notification events, $mcount media events"

echo
echo "P2.5 hardware check complete; paste everything above into the packet Result."
