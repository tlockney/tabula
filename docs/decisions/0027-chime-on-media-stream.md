# 0027: The notification chime plays on the media stream

Status: accepted
Date: 2026-11-21
Packet: none (issue #121)

## Context

P2.5 played the card chime (F-NTF-09) as a synthesized tone on the OS notification stream, so the OS notification volume set its loudness, and recorded that as a stopgap until a notification-volume setting existed. On the Office Kiosk the notification, ring and system streams were muted by the tablet's silent mode while the media stream was not. A `chime: true` card then showed in silence, TTS on the media stream was heard, and HA had no way to tell (#121).

A wall kiosk is set up once and left. Its ringer mode is a phone concept that nobody on the wall sees or changes, and Fire OS's own notification settings can mute it too. Issue #121 offered two options. One plays the chime on a stream Tabula controls. The other keeps the notification stream and reports to HA that the device muted it. The second leaves the chime silent until someone takes the tablet down to change a system setting.

## Decision

1. **The chime plays on the media stream** (`STREAM_MUSIC`), the stream TTS and `play_media` use (F-TTS-07). The OS ringer mode and notification volume no longer affect it.
2. **A new setting sets its loudness: `audio.chime_volume`, 0–100, default 100** (ADR 0002). It is a share of the media volume: the tone generator runs at that percentage of the media stream's level. At 100 a chime is as loud as an announcement. This is the per-device chime level F-NTF-09 asks for, and HA exposes it as a config number entity next to the media volume.
3. **It can scale down, never up.** Raising the chime above the media level would mean changing the stream volume for the length of the tone, a hidden volume jump. ADR 0002 rules that out for announcements, and the same reason holds here. F-NTF-09 describes a chime volume separate from the media volume. This decision meets that only downward: a chime can be quieter than media, not louder. Media muted from HA (F-TTS-07) mutes chimes too, which HA knows because it set it.
4. The P2.5 `CHIME_VOLUME` constant goes away; the setting replaces it.

## Consequences

- A `chime: true` card is audible whatever the tablet's ringer mode, at the level HA sees on the media player.
- A media volume of 0 (or mute) silences chimes. Turning a chime off for one level stays with `notifications.chime_by_level`. Turning all of them down without touching media is `chime_volume`.
- The sound library and default-sound choice (F-NTF-10) still play through this stream and this level when they land.

## Alternatives considered

- **Notification stream plus a "muted by the device" state in HA** (#121 option b). This keeps the dependency on a setting nobody on the wall can reach. HA would know the chime is silent but could not fix it.
- **The alarm stream.** It has its own OS volume, which silent mode leaves alone on most Android builds, so the chime could be louder than media. That behavior varies by vendor and is unverified on Fire OS. It would also need Tabula to set the alarm stream volume, which changes alarms from any other app on the tablet.
- **An absolute chime level on the media stream**, made by setting the stream volume around each tone. This was rejected as a hidden volume jump (item 3).
