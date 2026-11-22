# 0029: Voice on the Fire HD 10 Plus: what the spike measured, and the engine and settings it settles

Status: accepted for the audio source, echo-cancellation and engine items; the go decision waits on the owner's scheduled run of the persistence, background-capture and accuracy steps
Date: 2026-11-22
Packet: P7.0

## Context

ADR 0024 puts the wake word on the device and leaves the engine, the audio
settings and the echo-cancellation answer open. PLAN 7.4 parks voice behind a
device spike, and P7.0 is that spike on the Desk Kiosk (Fire HD 10 Plus, Fire
OS 7 / API 28, `G001NW0631460BXX`). Two device types matter for v1: this
tablet, and the Echo Show 8 on LineageOS 18.1 (ADR 0026), which was not on
Wi-Fi during the run. The Echo Show rows in the packet stay open until P2.7's
bring-up puts it on the network.

FEATURES F-KSK-21 makes the microphone the gating question: it says Fire OS
withdraws `RECORD_AUDIO` from a sideloaded app at boot and after a Wi-Fi
reconnect. A voice satellite that silently loses its microphone is worse than
one that was never offered, so the spike is built to measure the
withdrawal-and-restore path rather than assume either answer.

The spike is a separate app (`net.lockney.tabula.spike.voice`, item 6 below),
so nothing here disturbed the paired production app or its credentials.

**What was actually collected.** Three runs of the unattended part of
`tools/device/check_p70.sh` on 2026-11-21, at 20:01, 20:05 and 20:10, plus a
manual 445 s cost capture at 19:50–19:57. Each script run printed sections 0b
to 2 and was then stopped — the last two inside section 2b or 3 — so the
script's remaining sections, which are the cost completion line, capture with
another window in front, the panel-off capture, the reboot, the Wi-Fi cycle and
the accuracy clips, have **no output in any log**. No number below is invented,
and no question is answered from outside these files:

- `build/spike/check_p70.killed.log` (20:01, stopped after the effects list)
- `build/spike/check_p70.died2.log` (20:05, stopped after section 2b)
- `build/spike/check_p70.log` and `build/spike/logs/` (20:10, stopped inside 2b)
- `build/spike/live.log` (the 445 s cost capture)

## Measurements

Fire HD 10 Plus, Fire OS 7 (API 28), quiet room. Levels are dBFS with a
-120 dB floor. `active` is the share of samples above the app's silence
threshold; `peak` is the absolute sample maximum.

### Question 3: audio sources at a 16 kHz, mono, 16-bit request

Two 2 s-per-source runs agree closely. The 20:05 run:

```
SPIKE source=mic result=ok sr=16000 ch=1 fmt=2 samples=32000 rms_db=-61.0 peak=221 active=0.00
SPIKE source=voice_recognition result=ok sr=16000 ch=1 fmt=2 samples=32000 rms_db=-58.1 peak=2634 active=0.00
SPIKE source=voice_communication result=ok sr=16000 ch=1 fmt=2 samples=32000 rms_db=-51.9 peak=962 active=0.00
SPIKE source=camcorder result=ok sr=16000 ch=1 fmt=2 samples=32000 rms_db=-61.5 peak=150 active=0.00
SPIKE source=voice_call result=unavailable error=state=0
SPIKE source=unprocessed result=ok sr=16000 ch=1 fmt=2 samples=32000 rms_db=-38.3 peak=417 active=0.00
```

The 20:10 run, same request (the `samples=`, `sr=`, `ch=` and `fmt=` fields
elided here; both runs are pasted whole in the packet Result):

```
SPIKE source=mic ... rms_db=-58.8 peak=252
SPIKE source=voice_recognition ... rms_db=-62.0 peak=708
SPIKE source=voice_communication ... rms_db=-50.8 peak=2446
SPIKE source=camcorder ... rms_db=-61.5 peak=244
SPIKE source=voice_call result=unavailable error=state=0
SPIKE source=unprocessed ... rms_db=-38.1 peak=419
```

Every source except `VOICE_CALL` opens at 16 kHz mono in 16-bit PCM;
`VOICE_CALL` needs a call session. The two runs agree within about 4 dB on
`MIC`, `VOICE_RECOGNITION`, `CAMCORDER` and `VOICE_COMMUNICATION`.

`UNPROCESSED` is the one line that does not behave like the others. It printed
-38.1, -38.2 and -38.3 dBFS in the three runs in which everything else moved
by roughly 30 dB between the 20:01 run and the other two. A level that does not
move with the room is not a measurement of the room's loudness, so this run
does **not** establish that `UNPROCESSED` is louder, quieter or better than
`MIC`; it is recorded as uncharacterised.

These numbers order the capture paths; they do not measure how well any path
hears a voice, because a quiet room's noise floor is most of what they report.
Question 5's clips were meant to answer that and were not recorded.

### Question 4: echo cancellation

The 20:05 run is the only complete one:

```
SPIKE effects aec=false ns=false agc=false
SPIKE effect type=46d279d9-9be7-453d-9d7c-ef937f675587 name=DAP FULL implementor=Dolby Laboratories
SPIKE effect type=7261676f-6d75-7369-6364-28e2fd3ac39e name=Dynamics Processing implementor=The Android Open Source Project
SPIKE effect type=fe3199be-aed0-413f-87bb-11260eb63cf1 name=Loudness Enhancer implementor=The Android Open Source Project
SPIKE effect type=381e49cc-a858-4aa2-87f6-e8388e7601b2 name=Multichannel Downmix To Stereo implementor=The Android Open Source Project
SPIKE effect type=e46b26a0-dddd-11db-8afd-0002a5d5c51b name=Visualizer implementor=The Android Open Source Project
SPIKE effect type=47382d60-ddd8-11db-bf3a-0002a5d5c51b name=Insert Preset Reverb implementor=NXP Software Ltd.
SPIKE effect type=47382d60-ddd8-11db-bf3a-0002a5d5c51b name=Auxiliary Preset Reverb implementor=NXP Software Ltd.
SPIKE effect type=c2e5d5f0-94bd-4763-9cac-4e234d06839e name=Insert Environmental Reverb implementor=NXP Software Ltd.
SPIKE effect type=c2e5d5f0-94bd-4763-9cac-4e234d06839e name=Auxiliary Environmental Reverb implementor=NXP Software Ltd.
SPIKE effect type=09e8ede0-ddde-11db-b4f6-0002a5d5c51b name=Volume implementor=NXP Software Ltd.
SPIKE effect type=0bed4300-ddd6-11db-8f34-0002a5d5c51b name=Equalizer implementor=NXP Software Ltd.
SPIKE effect type=37cc2c00-dddd-11db-8577-0002a5d5c51b name=Virtualizer implementor=NXP Software Ltd.
SPIKE effect type=0634f220-ddd4-11db-a0fc-0002a5d5c51b name=Dynamic Bass Boost implementor=NXP Software Ltd.
SPIKE aec available=false ns=false
SPIKE aec pass=silent sr=16000 window=44800 rms_db=-62.3 peak=175 active=0.00 aec_enabled=false
SPIKE aec pass=off sr=16000 window=44800 rms_db=-45.9 peak=2552 active=0.02 aec_enabled=false
SPIKE aec pass=on sr=16000 window=44800 rms_db=-58.6 peak=538 active=0.00 aec_enabled=false
SPIKE aec erle_db=12.7 off_db=-45.9 on_db=-58.6
SPIKE aec room_floor_db=-62.3
```

`AcousticEchoCanceler.isAvailable()` is false, `NoiseSuppressor` and
`AutomaticGainControl` report false, and the platform's thirteen effects
contain no echo canceller. The tablet's own playback reached the microphone
16.4 dB above the room floor (-45.9 against -62.3), and asking for the
canceller on `VOICE_COMMUNICATION` removed 12.7 dB of it while the effect
object still reported `aec_enabled=false`: Fire OS's audio HAL cancels on that
path, not the Java effect.

That 12.7 dB is one 3 s window in one run. The 20:10 run reached only its
silent and off passes (-60.1 then -58.4 dBFS) before it was stopped, so it has
no `erle_db`; its playback peak (454) was lower than its resting peak (588),
which is what a pass with nothing audible playing looks like, so it neither
confirms nor contradicts the figure and cannot be read as good isolation. The
20:01 run's passes are mutually inconsistent (a -18.2 dBFS window with 47 % of
its samples active, followed by -65.0 off and -64.6 on) and are not used.

### Question 5: cost of continuous detection

`build/spike/live.log`, `VOICE_RECOGNITION`, 600 s requested, 445 s collected,
screen on:

```
SPIKE cost t=45.0s frames=4488 invocations=1496 capture_ms=41623.5 frontend_ms=3315.0 invoke_ms=1470.1 busy_pct=10.6 cpu_pct=7.7 bytes=1436800
SPIKE cost t=245.0s frames=24458 invocations=8152 capture_ms=230523.6 frontend_ms=14123.6 invoke_ms=4367.0 busy_pct=7.5 cpu_pct=6.1 bytes=7827200
SPIKE cost t=445.0s frames=44438 invocations=14812 capture_ms=419303.3 frontend_ms=25034.6 invoke_ms=7091.4 busy_pct=7.2 cpu_pct=6.0 bytes=14220800
```

(three of the forty printed lines; the whole window is in the packet Result)

- `cpu_pct` is the process's own CPU time from the kernel over elapsed time;
  `busy_pct` is frontend plus inference over elapsed time. Both settle by about
  t=250 s: `cpu_pct` 6.1 then 6.0, `busy_pct` 7.5 then 7.2, after 7.7 and 10.6
  in the first 45 s.
- Bytes: 14,220,800 at 445.0 s is 31,957 bytes/s, or **115 MB/h** (109.7 MiB/h)
  for the streaming fallback.
- There is no `SPIKE cost done` line, so the run produced no `max_prob`, no
  `detections` count and no final summary. Detection accuracy is therefore
  **not measured** — this file supports the cost claim only.
- Heat and battery: the only readings in any log are the pre-run pair
  `temperature: 220` (22.0 °C) and `level: 100` in the 20:01 and 20:05 runs.
  The `during:` and `after:` samples never printed, so temperature rise and
  battery drain over a long run are **not measured**.

### Questions 1, 2 and 6

1. **Mic persistence — not measured.** Each run re-applied the grant and read
   it back (`RECORD_AUDIO: granted=true`), and one run's app-op read
   `RECORD_AUDIO: allow; time=+1m42s403ms ago; duration=+10m0s58ms`. No run
   rebooted the tablet, cycled Wi-Fi or lasted a day, so whether the grant
   survives any of them is unmeasured.
2. **Background capture — not measured.** The script's sections 4 to 4d, which
   are the dashboard in front, another app in front, the panel off and the
   notification dump, were never reached.
6. **HA round trip — not run.** It needs the binary channel from ADR 0024
   decision 2, which does not exist yet, and a throwaway integration on a test
   Home Assistant. A latency number for a path nobody has written would be a
   guess.

## Decision

1. **No device type is cleared for voice by this packet.** The Fire HD 10 Plus
   is the only candidate with measurements, and they settle the audio path
   (sources, echo cancellation, CPU) while leaving the three questions that
   decide whether a satellite is usable: does the grant survive (1), does
   capture work unseen (2), can it hear the phrase (5). It stays the Phase 7
   target, and the go is gated on one owner-scheduled run that closes those
   three. If the grant cannot be restored without a person, or background
   capture fails, or the clips do not fire, this device type gets no voice and
   stays a working kiosk without it (ADR 0024 item 1). The Echo Show 8 is open
   until P2.7.
2. **Capture settings.** `MIC` at 16 kHz, 16-bit, mono for detection: the raw
   path, which is what the model family expects. `VOICE_RECOGNITION` is the
   fallback when another app has claimed `MIC`. `VOICE_COMMUNICATION` only when
   capture must run while the tablet plays (item 3). `UNPROCESSED` is not
   chosen: its reported level did not track the room across the three runs, so
   nothing here says it hears better, and it would need its own measurement.
3. **No platform echo canceller; detect half-duplex.** Detection stops for the
   duration of a reply: the assistant audio path (P2.5) knows when playback
   starts and ends, and the device discards the microphone while it plays. This
   is the robust choice on a device whose canceller is unavailable and whose
   own playback was measured 16.4 dB above the room floor in the one run that
   clearly had it playing, and it also stops the reply from being mistaken for
   the wake phrase. The 12.7 dB that the HAL gives on
   `VOICE_COMMUNICATION` is unclaimed: one window in one run is not enough to
   build on, and a later packet that measures it as reliable can start
   detection during playback there instead.
4. **Engine and model: microWakeWord, streaming TFLite.** The model is
   `models/v2/okay_nabu.tflite` from `esphome/micro-wake-word-models` at commit
   `40ff33f57f8fc6ad71a75ef085abbf742495b225`, 60,264 bytes; the copy in
   `build/spike/` hashes to
   `0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891`, which is
   the pin the script checks, and that is the only licence-adjacent fact this
   run produced. The licence is Apache-2.0 as recorded in the packet's
   prior-art list; the upstream LICENSE and NOTICE were not re-read during this
   run, so the packet that first ships a model confirms them before release.
   The model is fetched into `build/` at the pinned commit and is never
   committed. The runtime is `org.tensorflow:tensorflow-lite` 2.16.1 from Maven
   Central, consistent with AGENTS.md rule 7. The phrase is the model's own
   ("Okay Nabu"); a user-chosen phrase needs a trained model and belongs to the
   Phase 7 packet that owns voice settings.
5. **On-device detection, not the streaming fallback.** 6.0 % of one core for
   capture, frontend and model together after warm-up, against the Phase 4 idle
   target of 15 %; the first 45 s read 7.7 %, so the figure is quoted as
   "settles at 6.0 %". Heat and battery over the run were not measured, so this
   item is a CPU decision and not yet a thermal one. The fallback stays
   documented rather than implemented: 31,957 bytes/s, about 115 MB/h, and it
   would need the HA-side engine for a device that cannot afford item 5's
   numbers.
6. **The spike app is kept, named, and stays out of the release path.** It is
   the `:spike-voice` Gradle module with its own applicationId
   `net.lockney.tabula.spike.voice`, built only by `make voice-spike-apk` as a
   debug APK; nothing in the release pipeline includes it and no production
   code calls into it. It stays because the owner's run, the Echo Show 8 run
   and any later Fire OS update need the same measurements again. It is
   deleted, with this ADR marked superseded, when the Phase 7 voice path
   carries its own device check and the measurements move into it.
7. **Provisioning and recovery for the microphone (the DEVICE.md step).** The
   grant is applied after install, and re-applied by someone with ADB:
   `adb shell pm grant net.lockney.tabula.spike.voice android.permission.RECORD_AUDIO`,
   or `appops set ... RECORD_AUDIO allow` when the permission is held but the
   app-op was flipped, or from the app's own grant screen with a person. No
   integration-side or in-app route exists: Fire OS gives a sideloaded app no
   device owner (F-KSK-21), so nothing on the HA side can restore it. Whether
   it is ever needed after a boot or a Wi-Fi drop is the owner's step, not this
   run's result; until it is known, the device must report no capture rather
   than stream silence, so a listener reads as inactive instead of pretending
   to hear.
8. **Wire format proposal for ADR 0002 and PROTOCOL.md (Phase 7 packet).**
   16 kHz, 16-bit, little-endian, mono, the format `assist_satellite` takes;
   chunks of 3200 bytes (100 ms) per binary frame, ten frames a second, so a
   reply's first audio can start within one chunk of the end of speech while a
   frame stays far below the existing 64 KiB cap. Smaller chunks multiply
   per-frame overhead without helping latency on a local network; larger ones
   delay the pipeline's first STT bytes.
9. **What changes in ADR 0024.** Item 3's fallback is not needed for this
   device; item 1's capability gating stands and now has its measurements; add
   that detection is suspended while a reply plays (item 3 here).

## Consequences

- Voice on this tablet will need a person with a cable after a reboot or a
  Wi-Fi dropout if F-KSK-21 holds for this build. The owner's day-long run
  decides how often that bites, and this packet does not assume either answer.
- Half-duplex costs a beat: a wake word spoken while the tablet is answering is
  missed. On a device without a usable canceller that is the right trade.
- The app's frontend is a cost proxy, not the model's reference frontend, so
  the on-device probability is indicative and the accuracy verdict comes from
  the host scorer over device clips (`tools/device/score_wakeword.py`).
- The spike module stays in the repository until Phase 7 replaces it, which
  means one more module in `make android-test` and one more APK to build.
- Until the owner's run, any Phase 7 packet that assumes a voice satellite on
  this tablet is written against a device whose microphone has not been shown
  to survive its own boot.

## Alternatives considered

- **Wyoming satellite on the device.** ADR 0024 rejected it for the product;
  the spike has no reason to revisit it, and it would have measured a different
  stack's cost.
- **openWakeWord.** Its bundled pre-trained models are non-commercial, which the
  ADR 0024 decision already excluded; microWakeWord's are Apache-2.0.
- **A custom-trained phrase.** It needs a training run and a licence file per
  model; the spike needs a cost and accuracy number, and `okay_nabu` gives one
  without shipping anything unlicensed.
- **Half-duplex versus canceller-only.** Canceller-only would let detection run
  during replies, but `AcousticEchoCanceler.isAvailable()` is false and the
  one measured HAL reduction leaves the reply 3.7 dB above the room floor,
  which is the level a wake-word model is trained to notice.
- **Keeping the spike inside the production app behind a debug flag.** It would
  risk the paired install, its credentials and its dashboard on every
  measurement run; a separate applicationId costs one module and one APK.
- **Deleting the spike now.** The Echo Show 8 rows are still open, the owner's
  steps are still unrun, and Fire OS updates can change any of these answers;
  the measurements have to be repeatable on the next unit.
