# Third-party components

Tabula's own code and documentation are Apache-2.0 (see [LICENSE](../LICENSE)).
This file records the outside work the project ships or builds from, and the
attribution each licence asks for.

## The wake-word model

`okay_nabu.tflite` is microWakeWord's v2 wake-word model for "okay nabu".

- Copyright the Open Home Foundation and contributors.
- Source: <https://github.com/esphome/micro-wake-word-models>, path
  `models/v2/okay_nabu.tflite`, commit
  `40ff33f57f8fc6ad71a75ef085abbf742495b225`.
- Licence: Apache License 2.0, per that repository's `LICENSE`.
- sha256: `0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891`.

The model is not kept in git. A Gradle build fetches it at the pinned commit,
refuses a file whose hash differs, and copies it into the APK's
`assets/wakeword/` together with a `NOTICE.txt` for the same attribution
(ADR 0030 item 8).
