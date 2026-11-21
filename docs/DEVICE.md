# Device provisioning and recovery

How a Tabula device (Fire HD 10 Plus, Fire OS 7 / Android 9, API 28) gets an
APK, its ADB grants and its pairing, and how to recover one. Written by
P2.1; later packets extend it (Shizuku and the recovery page arrive in
Phase 4, plan sections 5.9 and 7.4).

Everything here is scripted so a hardware check is one paste of output
(AGENTS.md, "Work packets"). No step roots, unlocks a bootloader or needs
anything the tablet's own settings do not allow.

## Prerequisites (the Mac, or any machine with ADB)

- `adb` on PATH, USB debugging enabled on the tablet (Settings → Device
  Options → Developer Options; tap the serial number seven times).
- The Android toolchain: `make setup-android` (JDK 17-21, SDK platform 34 —
  ADR 0008). `make android-apk` builds
  `android/app/build/outputs/apk/debug/app-debug.apk`.
- For HA queries: the repo's uv environment (`make setup`) and a long-lived
  access token with which to read the device registry.

With several devices attached, set `ANDROID_SERIAL` so the scripts pick the
right one.

## Provisioning a device

```sh
make android-apk
ANDROID_SERIAL=<serial> tools/device/provision.sh
```

The script installs the APK and applies the ADB grants (plan section 3):

| Step | What it does | Why |
| --- | --- | --- |
| `adb install -r` | installs the debug APK | no app store on the target |
| `pm grant … WRITE_SETTINGS` + `appops set … WRITE_SETTINGS allow` | lets the app set window brightness (screen control, P2.3) | appops-backed permission, scripted form |
| `appops set … SYSTEM_ALERT_WINDOW allow` | overlay drawing | self-relaunch after a crash (Phase 4) |
| `dumpsys deviceidle whitelist +net.lockney.tabula` | battery-optimization exemption | keeps the HA connection alive with the screen off (F-KSK-09) |
| `dpm set-active-admin …` | device administration | panel power-off needs it (P2.3); the admin receiver component does not exist until then, so the step prints an expected failure and is not a gate for P2.1 |

Finish by hand:

1. Launch Tabula once. A freshly installed app is in the stopped state and
   receives no boot broadcast until it has been launched once; after that
   the boot receiver starts the service on every boot.
2. On the pairing screen, enter the HA address (for example
   `http://homeassistant.local:8123`) and tap pair.
3. Confirm the six-digit code in Home Assistant: Settings → Devices &
   services → the discovered Tabula device (P1.2's discovery flow).

Pairing exchanges the credential over the LAN (docs/PROTOCOL.md 2). Over
plain http an on-path attacker can read it; run HA behind HTTPS if you can,
and if not, treat the LAN as the security boundary (plan 5.2, section 9).

## Hardware check (P2.1)

After pairing, run:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
ANDROID_SERIAL=<serial> tools/device/check_p21.sh
```

It verifies, with pasted evidence for each step:

1. the service is running (`dumpsys activity services net.lockney.tabula`);
2. the app's persisted device_id;
3. HA's device registry knows the tablet, with its entity states (the
   entity platforms are in the tree since P1.3);
4. airplane mode: the app logs `connection lost` and HA's entities for the
   tablet read unavailable (F-HA-04); the registry entry stays;
5. airplane mode off: the app re-registers (`registered with HA device`)
   and HA's entities refresh from the tablet's state;
6. the full service log.

The script's exit status is 0 only when every step's evidence appeared.

### Echo Show 8 (2nd gen)

P0.6 gave a provisional no-go (ADR 0007): no settings-only path to ADB is
known, and every working path is an exploit plus bootloader unlock, which the
plan rules out. The P2.1 boot-start observation on an Echo Show is therefore
skipped; if the ADR's device check ever overturns the no-go, P2.7 owns the
bring-up.

## Recovery (before Phase 4's recovery page)

Recovery is ADB-only until Phase 4 (plan 5.9). All of these preserve ADB
access; none needs the app to be working.

**Re-pair after the credential was revoked in HA** (deleting the device in
HA, or an HA restore): launch the app — it shows the pairing screen again
(the stored credential no longer works) — and pair as above. The old HA
entry stays until you delete it (F-HA-01).

**Wrong HA address or broken pairing state:**

```sh
adb shell pm clear net.lockney.tabula   # wipes app data: device_id, credential, settings
```

A cleared device pairs again as a *new* device (new device_id); delete the
old entry in HA. `pm clear` also clears the ADB grants, so re-run
`tools/device/provision.sh` afterwards.

**Reinstall or update:** `adb install -r <apk>` over the existing install
keeps the pairing and all data (F-UPD's rule; the P2.1 build is a debug APK
signed with the debug key, so the same machine can always update it).

**Stolen or suspect tablet:** revoke first (plan section 9) — delete the
device in HA, which removes its user and credential (P1.2) — then wipe the
tablet. The stolen device's credential gives a non-admin HA user; treat the
house control it allows as compromised until revoked.

**Leftover system users:** each paired tablet has an invisible
system-generated user in HA (ADR 0001 item 2). The integration sweeps users
it no longer knows at setup and removes all of them when its entry is
removed, so leftovers only appear if the hub entry was deleted with the
integration files also removed. Reinstall the integration and its entry to
run the sweep, or remove the users with HA's `auth` tooling on the host.

## Known device behaviors

- **Boot start needs one launch first.** Android's stopped state (above).
  Provisioning therefore always ends with a manual launch.
- **Fire OS restarts a killed service slowly.** The P2.1 watchdog alarm
  (F-KSK-09) arrives with the kiosk work in Phase 4; until then, if the
  service is killed, the boot receiver covers reboots and START_STICKY
  covers most kills.
- **Foreground service type.** The service declares `connectedDevice`; on
  the API 28 targets this is unrestricted. On Android 14+ devices the type
  has runtime prerequisites — revisit in an ADR before targeting newer
  devices (P2.7 or later).
