# 0030: Wake-word detection in the production app: where it runs, what it reports, and what it does not promise

Status: accepted for the CI part; the device path is pending the owner's
scheduled microphone run
Date: 2026-11-22
Packet: P7.1

## Context

ADR 0024 decided that the wake word runs on the device. ADR 0029 measured the
Fire HD 10 Plus and settled the audio path, the engine, the model and the cost:
`MIC` at 16 kHz mono with `VOICE_RECOGNITION` as fallback, microWakeWord's
`okay_nabu` (Apache-2.0, pinned by sha256) on TensorFlow Lite, no usable
platform echo canceller so detection is half-duplex, and 6.0 % of one core for
capture plus frontend plus inference. The spike left three things undone: the
code lived in a separate debug app, nothing in the production app ran it, and
no accuracy figure existed because question 5's clips were never recorded.

The owner's instruction for this packet is what sets the priority: wake-word
detection on a single Fire HD 10 Plus comes before intercom. A detection that
reaches HA is useful before the assist pipeline exists — an automation can stop
a timer's tone, dim the panel or announce something — so this packet ships
detection and its HA surface, and leaves streaming audio to the later voice
packet that owns `assist_satellite`.

Two constraints shape the result more than the feature request does. The
microphone must not run unless a household asked for it, and Fire OS can take
`RECORD_AUDIO` away from a sideloaded app (F-KSK-21) so the device must be able
to say "I am not listening" instead of looking healthy and hearing nothing.

## Decision

1. **Detection runs in the production app's foreground service, and only while
   three things are true.** The setting `voice.wake_word_enabled` is on (default
   off), `RECORD_AUDIO` is granted at the moment capture would start, and the
   service is running. Turning the setting off closes the microphone and stops
   the capture thread; it is not a filter that keeps reading and discards.
   Off-by-default is the household's decision to make: a kiosk whose microphone
   is always open is a different product from one that is not, and a setting in
   HA is where that belongs (F-MGT-03: settings are per device and shared).

2. **A new `voice` settings group, in settings schema v3.** The key is
   `voice.wake_word_enabled`. It is not a leaf of `audio`, which is about volume;
   microphone behavior is its own group, and a later voice packet (assistant
   volume, reply length, a second phrase) extends the same group. Adding a key
   bumps the schema: v2 is frozen as `schema/settings.schema.v2.json` with the
   integration's bundled copy, `DOWNS` gains a v3→v2 rule and `since()` walks
   v3, v2 and v1, so a device registered at v2 still receives a document it can
   parse and a device at v3 gets the new key. A key added without a bump would
   be pushed to an older app whose strict codec rejects unknown keys, which is
   the failure the version exists to prevent.

3. **The device reports three things, all covered by a new `wake_word`
   capability.** `wake_word_active` (bool) is true exactly while the microphone
   is open and the frontend is computing features; `last_wake_word` (date-time)
   is the device's clock at the last accepted detection and is omitted until
   there is one (PROTOCOL 4.2's rule for a value that does not exist yet); and
   the event `wake_word_detected` carries `phrase` and `probability`
   (0–255, the model's own scale). `wake_word_active` is not decoration: ADR
   0029 item 7 requires "no capture" to be visible, and without the key a
   revoked permission is indistinguishable from a quiet room. The capability is
   declared on every register, not only while listening: the app reads its
   capability list once per session and cannot re-register mid-session, so a
   list that tracked the setting would reach HA only after a reconnect - and a
   device whose grant was withdrawn would never declare the capability and so
   could never report that it is not listening, the exact case this item
   exists for. The switch and the grant show up in `wake_word_active`, and the
   integration creates the three entities from the capability's presence.

4. **One event, three HA surfaces.** The TLP event becomes the
   `tabula_wake_word` bus event (like every other device event, plan 5.7), an
   `event` entity whose event type is `detected` with the phrase and
   probability as attributes (a second phrase later stays a type, not a new
   event), and a `sensor.<device>_last_wake_word` timestamp. They answer
   different questions:
   the bus event is the trigger an automation subscribes to, the sensor is "when
   did this last happen" (history, conditions, a dashboard card), and the event
   entity is what the device page shows with the payload attached. `wake_word_active`
   is a diagnostic binary sensor, because it describes the integration with the
   device rather than the room. None of the three is created without the
   capability (F-HA-02), and all three go unavailable with the device (F-HA-04).

5. **The detector's frontend is the spike's, not the reference one.** The port
   keeps the spike's 30 ms window, 10 ms hop, 512-point FFT, 40 mel bins between
   125 Hz and 7500 Hz, 20 dB per-frame dynamic range, and the quantisation the
   model's input tensor declares (scale 0.10196, zero point -128, top value 26).
   It does not implement the reference frontend's spectral noise reduction or
   per-channel amplitude normalisation. That is a deliberate limit, not an
   oversight: the reference frontend is a C++ library built for ESP32, and the
   spike's cost figure was measured with this one. The consequence is stated
   plainly — the desk harness measures the model and the clips, not this
   frontend, so the owner's run on the tablet is the only test of the device
   path, and a detection rate on the desk does not transfer to the device.

6. **Cutoff and cooldown.** `DETECTION_CUTOFF` stays 200/255 from ADR 0029, so
   the host scorer and the device agree on what a hit is. `WAKE_WORD_COOLDOWN`
   is 4 s: one utterance is about a second, the model's window is 30 ms of
   features and its score stays high for several hundred milliseconds after the
   phrase, and 4 s keeps a single "okay nabu" from producing a burst of events
   without being so long that a second attempt a moment later is dropped.

7. **Half-duplex, as ADR 0029 item 3 settled.** Detection stops whenever the
   device is playing anything it started (a media stream, including a TTS
   announcement, and the notification chime) and resumes when the sound ends.
   Stopping means the microphone is *closed*, not muted: the capture job is
   cancelled, the `AudioRecord` is stopped and released, and the model stays
   loaded. Resuming reopens the capture and calls the detector's `reset()`
   first, so the window that still holds the tail of the sound is dropped
   rather than scored. `wake_word_active` goes false while paused, so HA sees
   the pause rather than a device that silently stopped hearing. The inputs are
   the media channel's own state and the chime: the tone has no completion
   callback, so a chime keeps detection closed for `WAKE_WORD_CHIME_GUARD_MS`
   (500 ms, ADR 0002) measured from the moment it plays. The three inputs are
   combined in `WakeWordGate`, which is plain Kotlin in `:core` so the
   transitions are unit-tested on a JVM rather than only on a tablet.

8. **The model is fetched at build time and never committed.** A Gradle task
   downloads `models/v2/okay_nabu.tflite` from
   `esphome/micro-wake-word-models` at commit
   `40ff33f57f8fc6ad71a75ef085abbf742495b225`, checks the sha256
   (`0689abe1…b6f891`) and copies it into the generated assets directory. An
   existing file with the right hash is reused, so a build after the first is
   offline; a wrong hash fails the build rather than shipping something else.
   The licence is Apache-2.0 (the repository's `LICENSE` at that commit) and its
   `NOTICE` asks for attribution, so `docs/THIRD-PARTY.md` and the APK's
   `assets/wakeword/NOTICE.txt` both name the Open Home Foundation, the source,
   the commit and the hash. This is the licence check ADR 0029 deferred to "the
   packet that first ships a model".

9. **The host scorer's quantisation is fixed.** `tools/device/score_wakeword.py`
   divided the reference frontend's 0–26 features by 25.6 before applying the
   model's scale, so every feature landed near zero and no clip could ever
   score above the cutoff. P7.0 recorded no accuracy table, so no published
   number rested on it; the desk harness uses the corrected tool and the
   packet's Result records the before and after.

## Consequences

- A household that turns the setting on has a microphone open whenever the
  screen is on, the device is idle and nothing is playing. The foreground
  service's notification says what it is doing, and `wake_word_active` is the
  honest signal; nothing on the device shows a microphone indicator on Fire OS.
- A revoked permission (F-KSK-21) leaves the feature off and visible as
  `wake_word_active: false`; restoring it needs ADB or a person on the tablet's
  settings screen (DEVICE.md). The device never pretends to listen.
- The device path's accuracy is unknown until the owner's run. If the phrase
  does not fire at 1 m, the next step is a frontend that follows the reference
  more closely (noise reduction, PCAN), not a lower cutoff.
- Every APK build needs network once per machine, which is a change from a
  fully offline Gradle build. The alternative — committing the model — was
  rejected in ADR 0029 and stays rejected.
- Detection stops during playback, so a phrase spoken over a reply is missed.
  The model was trained on clean speech; half-duplex is the trade ADR 0029
  already made.
- The spike app stays, so the cost and audio measurements can be repeated on a
  Fire OS update without touching the production app.

## Alternatives considered

- **Reuse the spike app and add HA entity support there.** Rejected: the feature
  belongs to the product, the spike has its own applicationId and a debug-only
  build, and a household cannot install it from the release path.
- **Keep detection in `TabulaService` without a separate module.** Rejected: the
  frontend and the detector are pure JVM logic and belong in `android/core`,
  where unit tests run without an emulator; only the TFLite model and the
  `AudioRecord` wrapper need Android APIs.
- **A state key `wake_word_enabled` mirroring the setting.** Rejected: HA already
  owns the setting (plan 5.6), and a mirrored key would be a second source of
  truth to reconcile. `wake_word_active` reports capture, which HA cannot infer.
- **A sensor per detection with the probability as state.** Rejected: a
  timestamp is what automations condition on, and the probability rides on the
  event and the event entity where it belongs.
- **Lowering `DETECTION_CUTOFF` for the device to improve recall.** Rejected
  before it was tried: the cutoff is the shared definition of a hit between the
  scorer and the device, and lowering it to make clips pass would hide the
  frontend difference that actually matters.
- **Committing the model to `android/app/src/main/assets`.** Rejected: ADR 0029
  item 4 keeps a 60 KB binary out of git, and a build-time fetch with a hash
  check is auditable in the build log.
