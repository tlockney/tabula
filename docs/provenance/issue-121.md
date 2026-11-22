# Task #121: the notification chime on the media stream

| Behavior | Source | Packet |
| --- | --- | --- |
| A card chime plays on the media stream, so the tablet's silent mode does not mute it; its loudness is the `audio.chime_volume` setting, a share of the media volume (default 100), exposed in HA as a config number | F-NTF-09; ADR 0027, default in ADR 0002 | #121 |

