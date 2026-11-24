# Device provisioning and recovery

How a Tabula device (Fire HD 10 Plus, Fire OS 7 / Android 9, API 28) gets an
APK, its ADB grants and its pairing, how the Home Assistant side is
installed, and how to recover one. Written by P2.1; later packets extend it
(Shizuku and the recovery page arrive in Phase 4, plan sections 5.9 and 7.4).

Everything here is scripted so a hardware check is one paste of output
(AGENTS.md, "Work packets"). No scripted step roots or unlocks a bootloader:
the one bootloader unlock in the fleet is the owner-performed, by-hand Echo
Show 8 (2nd gen) flash (ADR 0026), done before Tabula work begins.

## Prerequisites (the Mac, or any machine with ADB)

- `adb` on PATH, USB debugging enabled on the tablet (Settings → Device
  Options → Developer Options; tap the serial number seven times).
- The Android toolchain: `make setup-android` (JDK 17-21, SDK platform 34 —
  ADR 0008). `make android-apk` builds
  `android/app/build/outputs/apk/debug/app-debug.apk`.
- For HA queries: the repo's uv environment (`make setup`) and a long-lived
  access token from an **admin user** (HA's owner, typically) — HA's
  `config/device_registry/list` WebSocket command is admin-only, so a
  non-admin token fails the check's HA steps.

With several devices attached, set `ANDROID_SERIAL` so the scripts pick the
right one.

## Installing the integration on Home Assistant

Provisioning below ends by pairing, and pairing needs the HA side in place
first: the pair view answers a plain 404 while no Tabula hub is loaded, so a
tablet has nothing to pair to. One time per HA instance, before the first
tablet:

Tabula needs HA 2025.1.0 or later (ADR 0001 item 8: a chosen floor, known to
work, not re-tested below).

1. Copy `integration/custom_components/tabula` from a checkout of this repo
   into HA's config directory, as `custom_components/tabula` (create
   `custom_components/` if there is none). The config directory, and how to
   reach it, depends on the install type:
   - **HA OS or Supervised**: `/config`. Install the Samba add-on and copy
     over its `config` share (`smb://homeassistant.local/config` on a Mac,
     `\\homeassistant.local\config` on Windows), or install the Terminal &
     SSH add-on (port 22222 by default; enable password auth or add a key in
     its configuration) and copy with:
     ```
     scp -P 22222 -r integration/custom_components/tabula \
       root@homeassistant.local:/config/custom_components/
     ```
   - **Docker (HA Container) or Core**: the directory HA runs from — the
     host directory bind-mounted at `/config` (`-v /opt/ha/config:/config` in
     `docker run`), or the one passed to `hass -c`. Copy into it on the host:
     ```
     cp -R integration/custom_components/tabula /opt/ha/config/custom_components/
     ```
2. Restart HA — however that install restarts: `ha core restart` (OS or
   Supervised, e.g. through the Terminal & SSH add-on), `docker restart
   homeassistant` (Container), restarting the service (Core), or the restart
   option in Settings → System. On startup HA loads the integration and
   installs the manifest's requirements (`jsonschema`, `referencing`).
3. Add the hub: Settings → Devices & services → Add integration, search for
   "Tabula", and submit its dialog. The dialog has no fields — "Set up the
   Tabula hub. Tablets pair to it from the Tabula app on the tablet." — and
   the entry "Tabula" appears. A second attempt is refused with "The Tabula
   hub is already set up." because the hub is a singleton found by unique_id
   (ADR 0001 item 1); there is nothing else to set up.

Then run "Provisioning a device" below. Pairing's six-digit code is confirmed
in HA as the discovered Tabula device.

**Upgrading.** Replace `custom_components/tabula` with the new copy and
restart HA again. The hub entry, its settings and the paired devices live in
HA's `.storage`, so they survive; paired tablets reconnect on their own once
HA is back (the P2.6 offline-first behavior, checked by `check_p26.sh`).

**Removing.** Delete the entry first, the folder second — Settings → Devices
& services → Tabula → the entry's three-dot menu → Delete integration entry —
then remove `custom_components/tabula` and restart HA. Deleting the entry
revokes every paired device's user and credential (P1.2; ADR 0001 items
3-4): tokens first, which also closes each device's live WebSocket, then the
user. The tablets' credentials stop working and each tablet falls back to
its pairing screen. Deleting the folder first instead strands the devices'
invisible system users — the "Leftover system users" case in Recovery below.

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
| `dpm set-active-admin …` | device administration (force-lock only) | panel power-off (P2.3, F-SCR-14). Builds before P2.3 have no admin receiver, so the step prints an expected failure there. Without it, turning the screen light off is refused and the panel stays on |

Finish by hand:

1. Launch Tabula once. A freshly installed app is in the stopped state and
   receives no boot broadcast until it has been launched once; after that
   the boot receiver starts the service on every boot.
2. On the pairing screen, enter the HA address (for example
   `http://homeassistant.local:8123`). Then check the device name, which is
   the name HA shows and the source of the entity ids. It is prefilled with the
   tablet's own name plus the first four characters of its device id (for
   example "Fire Tablet 78b2"); change it to something like "Office Kiosk".
   Then tap pair.
3. Confirm the six-digit code in Home Assistant: Settings → Devices &
   services → the discovered Tabula device (P1.2's discovery flow).

Pairing exchanges the credential over the LAN (docs/PROTOCOL.md 2). Over
plain http an on-path attacker can read it; run HA behind HTTPS if you can,
and if not, treat the LAN as the security boundary (plan 5.2, section 9).

## Provisioning a second Fire HD 10 Plus

A second tablet of the same model needs nothing new: the runbook above is
written per device. Work through it in this order, with both tablets
attached at once (each step below is the same one as before; the only
addition is picking the right tablet).

1. **Prepare the tablet itself** (once, by hand): complete the Fire OS
   setup on Wi-Fi, then enable Developer Options (Settings → Device
   Options → tap the serial number seven times) and USB debugging.
2. **Pick the tablet for every command.** With two attached,
   `adb devices -l` lists both serials; export
   `ANDROID_SERIAL=<the new tablet's serial>` so the scripts target it and
   not the Office Kiosk. The scripts all honor it (`provision.sh`, every
   `check_p2x.sh`).
3. **Install and provision**: `make android-apk`, then
   `ANDROID_SERIAL=<serial> tools/device/provision.sh` — the same grants,
   the same device admin, printed evidence for each.
4. **Launch and pair** (the "Finish by hand" steps of provision.sh): launch
   Tabula once, enter the HA address and a device name (the prefilled
   default already differs per tablet), confirm the new six-digit code in HA.
   The new tablet appears as its own device with its own entity set
   (entity ids carry its device name); nothing about the Office Kiosk
   changes.
5. **Verify with the same checks**: run `tools/device/check_p21.sh` (and
   the P2.2–P2.5 checks if wanted) with the new tablet's entity ids in the
   `TABULA_*` variables, then `check_p26.sh` below. The entity ids are on
   the new tablet's device page in HA.

Two tablets on one HA is the normal state (plan 3): each pairs to its own
non-admin user; deleting one in HA does not affect the other.

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

The script's exit status is 0 only when every step's evidence appeared, and
the "appears in HA" step fails unless the registry output actually mentions
the tablet's device_id (a failed HA command makes ha_query.py exit non-zero,
so empty evidence cannot pass).

Airplane mode is toggled with `cmd connectivity airplane-mode`; that
subcommand is OEM- and version-dependent (it may not exist on Fire OS 7's
Android 9), so the script detects a missing subcommand and falls back to
`svc wifi disable`/`enable`, which severs the HA link the same way. The
output names which path ran.

## Hardware check (P2.3, screen control)

Install a P2.3 build, run `provision.sh` again (it grants WRITE_SETTINGS and
activates the device admin), then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_LIGHT=light.<tablet>_screen TABULA_ADAPTIVE=switch.<tablet>_adaptive_brightness \
ANDROID_SERIAL=<serial> tools/device/check_p23.sh
```

The entity ids are on the tablet's device page in HA. The script checks:

1. WRITE_SETTINGS, the device admin and the light sensor;
2. `light.turn_on` at 20 % and 80 % sets the system backlight to the matching
   0-255 level;
3. `light.turn_off` puts the device to sleep and `light.turn_on` wakes it;
4. with adaptive brightness on and the network down, the backlight follows
   the light sensor. The script asks you to cover the sensor, then shine a
   light on it;
5. after reconnecting, `light.turn_on` at 50 % sets the level and turns the
   adaptive switch off in HA;
6. the screen log.

Without WRITE_SETTINGS the app dims only its own window, so step 2 fails on
purpose. Without the device admin, turning the light off is refused with an
error in HA, and the panel stays on.

## Hardware check (P2.4, screensaver)

Install a P2.4 build, open Tabula so its window is in front, then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_SCREENSAVER=switch.<tablet>_screensaver TABULA_NEXT=sensor.<tablet>_next_screensaver \
ANDROID_SERIAL=<serial> tools/device/check_p24.sh
```

The script records `tabula_screensaver` events in HA in the background, then
checks:

1. the app runs and the screensaver is off;
2. without touching the tablet, the screensaver starts at the time the
   next-screensaver sensor showed (the idle timeout, 300 s by default), the
   backlight drops to the screensaver brightness, and the clock shows. The
   script asks you to confirm what is on screen;
3. a tap dismisses it and the previous backlight level returns;
4. `tabula.screensaver` with `mode: black` starts it black at the lowest
   backlight, and the HA switch stops it;
5. the HA switch starts the configured mode and stops it again;
6. HA received at least six `tabula_screensaver` events.

### Echo Show 8 (2nd gen)

The owner unlocks the bootloader and flashes the community LineageOS 18.1
(Android 11) image himself, by hand, from the public community guide, before
any Tabula work (ADR 0026) — that step is his alone; nothing here or in
`tools/device/` unlocks, roots or flashes anything. Once the device boots
LineageOS, Tabula's part begins: the read-only device probe (P2.7 step 1 —
WebView version, SELinux mode, screen, sensors), then provisioning and
recovery exactly as above, over ADB. The image ships SELinux permissive, so
treat the device as less trusted hardware: nothing sensitive beyond the
paired HA credential lives on it.

## Hardware check (P2.5, notifications and audio)

Install a P2.5 build, open Tabula so its window is in front, and give HA a
TTS entity if it has none (Settings → Devices & services → Add integration →
Text-to-Speech, e.g. the Google Translate one; its entity id is on its
device page). Then:

```sh
TABULA_AUDIO_OK=1 TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_MEDIA=media_player.<tablet>_speaker TABULA_TTS=tts.google_translate_com \
ANDROID_SERIAL=<serial> tools/device/check_p25.sh
```

This check speaks through the tablet, so it is behind the audio opt-in described
under the P7.0 check: it exits with status 2 unless `TABULA_AUDIO_OK=1` is set,
for a session scheduled with the owner.

The entity ids are on the tablet's device page in HA. The script records
`tabula_notification` and `tabula_media` events in the background, then
checks:

1. the app runs and the media player entity is available;
2. `tabula.notify` with a title, `level: warning` and `chime: true` shows a
   card at the top of the screen and chimes;
3. the same `tag` again replaces the card;
4. a card with `duration: 5` removes itself;
5. over a screensaver started from HA, a card draws on top, and tapping the
   card removes the card but leaves the screensaver up (F-NTF-12);
6. `tts.speak` to the tablet's media player is audible and the entity shows
   `playing` and then `idle` again;
7. `media_player.volume_set` and `volume_mute` reach the device's media
   channel and are reported back;
8. HA received at least six `tabula_notification` events (shown, replaced,
   timed out, tapped) and at least one `tabula_media` (`media_finished`)
   event.

### Chime in silent mode (#121, ADR 0027)

The chime plays on the media stream, so the tablet's silent mode must not
mute it. By ear, with the media volume around half:

1. Silence the notification and ring streams:
   `adb shell media volume --stream 5 --set 0` and
   `adb shell media volume --stream 2 --set 0`. Confirm with
   `adb shell dumpsys audio | grep -A3 "STREAM_NOTIFICATION:"`.
2. `tabula.notify` with `level: warning` and `chime: true`: the beep is
   audible.
3. Set the tablet's **Chime volume** entity to 30 and send the card again:
   the beep is quieter. At 0, the card shows silently.
4. Set **Chime volume** back to 100 and restore the two streams.

## Hardware check (P2.6, offline-first)

Install a P2.6 build (or later; the build contains no P2.6 code changes on
the device), provision and pair, let the dashboard load, leave Tabula in
front with the panel on, then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_SCREENSAVER=switch.<tablet>_screensaver TABULA_NEXT=sensor.<tablet>_next_screensaver \
TABULA_LIGHT=light.<tablet>_screen TABULA_CURRENT_PAGE=sensor.<tablet>_current_page \
TABULA_ADAPTIVE=switch.<tablet>_adaptive_brightness \
ANDROID_SERIAL=<serial> tools/device/check_p26.sh
```

The entity ids are on the tablet's device page in HA. `TABULA_ADAPTIVE` is
optional (it enables the offline curve step); so are
`TABULA_SAVER_BRIGHTNESS_PCT` and `TABULA_CONNECTIVITY`. Set
`TABULA_SKIP_REBOOT=1` only when the reboot step is being checked by itself.

**USB adb.** Option B and step 8 disable the tablet's Wi-Fi through adb,
so the adb connection must be over **USB**. With adb connected over
Wi-Fi/TCP (`adb connect host:port`), the script refuses those steps
rather than cutting the line adb itself rides on — a Wi-Fi cut would
strand the tablet with no remote way back. Use option A (stop HA) for
step 3 with Wi-Fi adb; step 8 cannot run without USB. (The older
`check_p21.sh` and `check_p23.sh` cut Wi-Fi the same way without this
guard; plug in USB before running them too.)

**Cleanup.** The script puts back whatever it took down, on any failure,
Ctrl-C and normal exit: a stopped HA is started again, the tablet's Wi-Fi
is re-enabled, and the screen settings step 2 changed (the manual 60 %
level and adaptive brightness) are restored to their original values.

**Taking HA down — two options.** The script checks the same offline
behavior either way; the first is the truer test.

- **Option A (preferred): stop HA itself.** Set
  `TABULA_HA_STOP_CMD` and `TABULA_HA_START_CMD` to commands the Mac can
  run, e.g. `TABULA_HA_STOP_CMD='ssh ha@homeassistant "ha core stop"'` and
  `TABULA_HA_START_CMD='ssh ha@homeassistant "ha core start"'` (adapt to
  however HA is hosted). The tablet's network stays up, so what fails is
  exactly HA. HA's own restart also matches the Phase 2 exit ("survives an
  HA restart").
- **Option B (default): block the tablet's route to HA.** Without the stop
  commands the script switches the tablet's Wi-Fi off and on
  (`svc wifi disable`), the same fallback `check_p21.sh` uses. The device
  sees the same dead connection, but its whole network is down, and the
  HA-side "unavailable" evidence is read from the Mac instead.

The script reboots the tablet first (the boot receiver must start the
service on its own), then checks, with pasted evidence for each step:

1. the service starts on boot and the dashboard loads with the pairing
   intact;
2. the online baseline: connected, manual brightness from HA, the idle
   deadline, the current page;
3. HA down: the app logs `connection lost` and re-dials with the backoff
   spacing visible in the timestamps, the process stays up (no crash
   loop), the crash buffer stays clean, and (option B) HA's entities read
   unavailable while the registry entry stays;
4. the screensaver still idles in with HA down, at the screensaver
   brightness, and the clock shows the current time and date;
5. the cached dashboard survives the outage: a tap dismisses the
   screensaver, the same page is back, and no new `dashboard: loaded` line
   appears (the page was never reloaded);
6. the brightness curve still follows the light sensor with HA down
   (cover it, then shine a light);
7. HA returns: the tablet re-registers by itself, HA's entities refresh
   from the device's current state (the screensaver switch reads off after
   the offline dismiss), the current page is unchanged, and the WebView
   still has not reloaded;
8. after a stable minute, a Wi-Fi blip re-registers within 30 s — the
   reconnect backoff reset (BACKOFF_RESET_AFTER, ADR 0002);
9. the full service and dashboard log for the record.

The exit status is 0 only when every gate passed. Offline
screensaver start/stop events never reach HA by design (ADR 0013): the
register frame carries the current state, which is what step 7 checks
instead.

## Hardware check (P3.8, photo slideshow)

Install a P3.8 build, provision and pair, and configure the photos settings
on the tablet's HA entry (they apply when the screensaver starts, ADR 0016):

- screensaver mode `photos`
- photos source `folder`, folder path
  `/storage/emulated/0/Android/data/net.lockney.tabula/files/photos`
- slide interval 5 s, transition `crossfade`, fit `smart_fill`
- edge taps on, EXIF details on

Generate the test photos (small JPEGs with EXIF: a 16:10 landscape, a 4:3
and a portrait photo that show smart fill's blurred bars, and one without
EXIF; a large number is drawn on each so the slide change is visible from
across the room) — output goes to `build/`, never committed:

```sh
uv run --with 'pillow>=10' --with piexif \
    python tools/device/gen_test_photos.py
```

Then leave Tabula in front with the panel on and run:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_SCREENSAVER=switch.<tablet>_screensaver \
ANDROID_SERIAL=<serial> tools/device/check_p38.sh
```

The script pushes the photos into the app's external files directory (the
configured folder path) over adb — the app reads its own directory with no
storage permission, and the shell can write it on the HD 10 Plus — starts
the slideshow from HA, counts the slides the app logs, checks the edge taps
step slides without dismissing, checks a centre tap dismisses, and compares
`dumpsys meminfo` TOTAL PSS before and after the run against a bound
(`TABULA_MEM_BOUND_MB`, default 48 MB: two screen-sized bitmaps plus the
blur copies and normal jitter, ADR 0016).

**Getting photos onto the device by hand.** Two routes:

- **The folder source**: push any photos into the folder path above with
  `adb push`, or configure any directory the app can read (app-scoped
  storage; nothing broader is requested, ADR 0016).
- **The picker source**: set the source to `picker`, tap the "Pick photos"
  action on Tabula's ongoing notification, and pick photos with the system
  document picker; they are copied into app storage and join the playlist
  the next time the screensaver starts. (When Phase 4's lockdown pins the
  device to Tabula, the DocumentsUI package
  (`com.android.documentsui`) must be allowlisted for the picker to open —
  a follow-up for that packet.)

The URL source needs no photos: point `screensaver.photos.image_url` at any
image URL and the slideshow fetches it fresh for every slide.

## Hardware check (P3.3, clock faces and background)

Install a P3.3 build, provision and pair, and configure the clock settings
on the tablet's HA entry (they apply while the clock shows, F-SSV-09):

- screensaver mode `clock`
- clock face `digital`, font family `serif`, font weight `700`
- seconds on, date line on
- background source empty, darkening at the default 40 %

Generate the test photos as for P3.8 (the check serves two of them as the
background over plain HTTP), then leave Tabula in front with the panel on
and run:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_SCREENSAVER=switch.<tablet>_screensaver \
TABULA_BACKGROUND=text.<tablet>_clock_background \
ANDROID_SERIAL=<serial> tools/device/check_p33.sh
```

The script serves the background from this machine (set `TABULA_HOST_IP`
when the tablet reaches the Mac on another interface than en0), starts the
clock from HA, and walks the faces and the background rules: the digital
face with the changed font, seconds and date line; the analog face (the
verifier switches it in HA — the save is the settings write, and the face
changes while the screensaver stays up); the digital face over a URL
background with the darkening layer visible; a broken URL keeping the
previous image; and the same URL written again reloading it with new bytes
behind the same address (F-SSV-09: the revision change is the signal). The
app's `TabulaClock` logcat lines are the load, show and failure evidence.
The centre-tap dismissal closes the check.

The background's other source is a local file path: push any image the app
can read (its own storage, ADR 0016) and set it as the background source to
check that route by hand.

## Hardware check (P3.13, screenshots)

Install a P3.13 build (or later), provision and pair, leave Tabula in
front with the panel on, then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_SCREEN=light.<tablet>_screen \
TABULA_BUTTON=button.<tablet>_capture_screenshot \
TABULA_IMAGE=image.<tablet>_screenshot \
TABULA_LAST_CAPTURE=sensor.<tablet>_last_capture \
TABULA_HA_STOP_CMD='ssh ha@homeassistant "ha core stop"' \
TABULA_HA_START_CMD='ssh ha@homeassistant "ha core start"' \
ANDROID_SERIAL=<serial> tools/device/check_p313.sh
```

The entity ids are on the tablet's device page in HA. The stop/start
commands are required — the check restarts HA to prove the timestamp
persists (F-HA-12) — and the exit trap starts HA again on any failure or
Ctrl-C, and puts the panel back on.

The check runs, in order: the baseline (entities live), a **panel-off
capture** (the command ACKs, nothing uploads, the image entity shows the
placeholder — a dark slate with a power-off glyph, not a black frame;
F-MGT-07, ADR 0014), a **panel-on capture** (the image entity shows the
tablet's current screen within `SCREENSHOT_TIMEOUT`, and its state — the
last capture's time — matches the timestamp sensor), a re-read after the
verifier has opened the image in the UI (**ordinary fetches do not move
the timestamp**), and an **HA restart** (the timestamp and the stored
screenshot survive). Two steps are visual and the script stops to ask:
whether the entity shows the placeholder, and whether it shows the
current screen. Paste the whole output into the packet Result.

Fire OS note (review): the panel-on ask checks the capture is not blank.
Some Fire OS WebView builds draw their hardware-accelerated layers to a
blank bitmap via `View.draw` (`DashboardView.capture`); if the check
reports a blank image, the fallback is `PixelCopy` on the window — note
what you saw in the packet Result either way.

## Hardware check (P3.15, start page and return to it after idle)

Install a P3.15 build (or later), provision and pair, leave Tabula in
front with the panel on, then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_START_PATH=/lovelace/1 \
TABULA_OTHER_PATH=/lovelace/0 \
TABULA_TEXT=text.<tablet>_start_path \
TABULA_NUMBER=number.<tablet>_return_home_after \
TABULA_BUTTON=button.<tablet>_go_home \
TABULA_RESTART=button.<tablet>_restart_app \
TABULA_PAGE=sensor.<tablet>_current_page \
ANDROID_SERIAL=<serial> tools/device/check_p315.sh
```

`TABULA_START_PATH` is a second dashboard view and `TABULA_OTHER_PATH`
a different one to navigate away to. The entity ids are on the tablet's
device page in HA. The exit trap clears the start path, sets the return
delay back to 0 and stops the screensaver on any failure or Ctrl-C.

The check runs, in order: the baseline (the go-to-start-page button is
live), the **start-path setting** (writing it moves nothing on screen,
F-DSH-02), the **go-home button** (from the other view it returns to the
start path), a **cold start** (`force-stop` plus a relaunch lands on the
start view), an **app restart** (the restart-app button lands there too,
F-NAV-03), and the **idle return** (a 15 s return delay, then a
**hand-tap move** to the other view — the current-page sensor must pick
the client-side move up, which is the part a full-load-only report
would miss — the screensaver started from HA; after the delay the
current-page sensor shows the start path while the panel stays dark —
the return happened behind the screensaver, F-DSH-15), and a
**delay-only round** (start path cleared, delay still 15 s, the tablet
navigated away: the page must stay put and no dashboard load may
appear in the log for two delay periods — with no start target there
is no return and no reload churn, ADR 0021 item 7 — then the go-home
button still lands on the default panel). Four steps ask the verifier
to act or look: the cold-start view, the hand-tap move confirmation,
the panel-stayed-dark check, and the default panel after the button.
Paste the whole output into the packet Result.

## Hardware check (P3.16, illuminance)

Install a P3.16 build (or later), pair, leave Tabula in front with the panel
on, then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_ILLUMINANCE=sensor.<tablet>_illuminance \
TABULA_STEADY_S=180 \
ANDROID_SERIAL=<serial> tools/device/check_p316.sh
```

The entity id is on the tablet's device page in HA (the sensor exists only
when the tablet has a light sensor, F-HA-02; the Fire HD 10 Plus has one
next to the front camera). The check runs, in order: the **baseline** (the
entity is live with device class illuminance, unit lx, state class
measurement and no diagnostic category, ADR 0020), **covering and
uncovering the sensor** (each is a step change, so the value moves within a
few seconds — a real change goes out at once, F-HA-06), a **steady-room
window** (leave the room's light alone for three minutes; the state changes
in HA's history must stay within ADR 0002's `ILLUMINANCE_MIN_INTERVAL`
bound of one per 30 s plus two for the window's edges), and a logcat tail
for the record. Two steps pause to ask the verifier to cover and uncover the
sensor. Paste the whole output into the packet Result.

## Hardware check (P3.14, screensaver engine polish)

Install a P3.14 build (or later), provision and pair, and configure the
polish settings on the tablet's HA entry (the options flow; they apply when
the screensaver starts, ADR 0022):

- pixel shift on, interval 10 s, amplitude 8 px (visible from a couch; the
  schema bounds are 10–86400 s and 1–64 px)
- fade on
- `screensaver.brightness` 20 (default) and `dim_brightness` 5, so dim's
  level is distinct from the clock's

Leave Tabula in front with the panel on and run:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
TABULA_SCREENSAVER=switch.<tablet>_screensaver \
ANDROID_SERIAL=<serial> tools/device/check_p314.sh
```

The script starts each mode from HA (`tabula.screensaver` with a per-start
`mode`, which never changes the configured setting, ADR 0013 item 3) and
checks: the clock **fades in** on start and **out** on the dismissing touch
(verifier answers), its content **steps by a small offset** at the interval
(two logcat `pixel shift: dx=… dy=…` lines over two intervals, plus the
verifier's eye) at the screensaver brightness; **black never shifts**
(zero shift lines over an interval) and holds the lowest backlight; **dim**
holds `dim_brightness` (a different system level from the clock's, nothing
drawn over the dashboard, no fade flash) and every dismissal returns the
previous level. Paste the whole output into the packet Result.

## Hardware check (P3.12, screensaver schedule)

Install a P3.12 build (or later), provision and pair, and leave the tablet
in front with the panel on and untouched for the whole run (a touch
dismisses the screensaver). The script sets the schedule itself — two slots
a few minutes ahead on the tablet's own clock, a black one then a clock
one — through HA's options-flow API, so `TABULA_HA_TOKEN` must belong to an
admin user (the options flow needs config-entry edit rights). No settings
need configuring beforehand; the clock slot carries its own fixed brightness
so the live edit is visible on the panel.

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<admin token> \
TABULA_SCREENSAVER=switch.<tablet>_screensaver TABULA_DEVICE=<device id> \
ANDROID_SERIAL=<serial> tools/device/check_p312.sh
```

`TABULA_DEVICE` is the tablet's HA device id (the id in the URL of its
Settings > Devices page); the slot offsets are `TABULA_BLACK_AHEAD`
(default 2 minutes) and `TABULA_CLOCK_AHEAD` (default 5). The script
starts the screensaver in clock mode from HA, then checks: the first
crossing **restarts it into black** — the captured `tabula_screensaver`
events show `stopped clock` + `started black`, both with trigger
`schedule`, the switch stays on, the panel holds the lowest backlight and
the verifier sees black; the second crossing **restarts the clock** the
same way, with exactly the four schedule-triggered events in order
(clock → black → clock) and the clock slot's fixed brightness on the
panel; then it **edits the running slot's brightness** through the options
flow — the run keeps going (no new events, the switch stays on) and the
panel's level moves at once; dismissal restores the previous level, and
the script turns the schedule switch off again (the slots would otherwise
repeat daily at those times). `ha_events.py` captures the events; paste
the whole output into the packet Result.

## Hardware check (P7.0, voice spike)

The P7.0 spike answers four device questions the Phase 7 voice work depends
on: whether the microphone grant survives a reboot and a Wi-Fi cycle, which
`AudioSource` gives usable 16 kHz mono, whether an echo canceller exists and
what it removes, and what continuous capture with a wake-word model costs in
CPU, heat and battery. It ships as its own app.

Audio checks in `tools/device/` are opt-in. `check_p70.sh` and `check_p25.sh`
exit with status 2 unless `TABULA_AUDIO_OK=1` is set, because a run makes the
tablet listen and speak in the room it lives in: setting it is a decision to
schedule that with the owner, not a flag to leave on. Nothing in `make check`,
CI or the release path sets it, and `tools/tests/test_device_scripts.py` fails
if an audio check loses the guard.

```sh
make voice-spike-apk          # debug only, never in a release
adb -s <serial> install -r android/spike-voice/build/outputs/apk/debug/spike-voice-debug.apk
adb -s <serial> shell pm grant net.lockney.tabula.spike.voice android.permission.RECORD_AUDIO
TABULA_AUDIO_OK=1 ANDROID_SERIAL=<serial> make check-p70
```

The app's applicationId is `net.lockney.tabula.spike.voice`, so installing it
adds a second app and never replaces, uninstalls, clears or unpairs
`net.lockney.tabula`. It carries none of the production service, dashboard or
credentials. Both apps then want the one microphone: while another app holds
it, this app's capture is not a reliable measurement, so close anything else
that listens before a run. The check prints each capture's sample count and
level, which is how a microphone that was taken by someone else shows up.

The check measures what needs no person and reboots the tablet once (the
persistence question needs it); it also turns Wi-Fi off and on, so run it over
USB, and expect nothing else on the tablet to work for that minute. It fetches
the wake-word model into `build/spike/` from a pinned commit and checks its
sha256 before pushing it to the tablet; the model is not committed and needs
network access on this machine. `make check-p70` needs `TABULA_COST_S`
(default 600) for the length of the cost run.

The steps that need a person — wake-word accuracy at 1 m and 3 m, with and
without the tablet's own playback, and the day-long uptime check — are off
unless `TABULA_PERSON=1`, and then they prompt, so they need an interactive
terminal:

```sh
TABULA_AUDIO_OK=1 ANDROID_SERIAL=<serial> TABULA_PERSON=1 make check-p70
```

Recording the clips pulls them back to `build/spike/clips/` and scores them
with the reference frontend (`uv run --with pymicro-features --with
ai-edge-litert python tools/device/score_wakeword.py`), because the app's own
frontend is a cost proxy: a microphone that cannot hear the wake word at 3 m
and a frontend that does not match the model look identical on the tablet. The
scorer's default cutoff is the device's own (200/255), so its hit count and the
app's agree. Paste the whole output into the packet Result.

## Hardware check (P2.2)

After provisioning, pairing and letting the app reach its dashboard, run:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
ANDROID_SERIAL=<serial> tools/device/check_p22.sh
```

It verifies, with pasted evidence for each step:

1. the service is running and the phase is CONNECTED;
2. the device's WebView version and how it updates — the output ADR 0001
   item 6 has been waiting for (every read-only command from that ADR);
3. the dashboard loaded with the device credential: the app's
   `dashboard: loaded` page-finished log line, the external-auth bridge's
   answered log line, and HA's current-page sensor reading the loaded
   path (F-DSH-01, F-NAV-02);
4. navigation stays inside the WebView: it calls `tabula.navigate` from HA
   to `/profile` (a page every user can open), waits for the page-finished
   line for that path, and fails if the window focus left `net.lockney.tabula`
   (a link that opened outside, e.g. in a browser, fails the check);
5. screen off/on (`input keyevent KEYCODE_POWER` each way, 15 s apart)
   doesn't reload: any new `dashboard: loaded` line after the toggle fails
   the check, and HA's current-page sensor is unchanged;
6. the full dashboard and service log.

The exit status is 0 only when every step's evidence appeared. Step 4 uses
`ha_query.py --navigate`, which targets the tablet by its persisted
device_id over HA's WebSocket and fails when the service call fails, so a
navigation that never reached the device cannot pass silently.

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

## Hardware check (P9.0, intercom spike)

P9.0's spike is a **second application**, `net.lockney.tabula.spike.intercom`,
with its own applicationId and no release variant (ADR 0028). Installing it
never replaces, uninstalls or unpairs `net.lockney.tabula`, and nothing in these
checks clears or uninstalls the production app. It needs two tablets, because a
call needs two ends.

Build the spike APK (arm64-v8a is the Fire HD 10 Plus's ABI; ABI splits put one
per ABI in the same directory):

```sh
make android-spike-apk
```

The checks take the tablets from the environment, since one end is attached to
this machine and the other to another host over ssh. The defaults
are the fleet's serials; override them when running elsewhere:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TABULA_INTERCOM_A_SERIAL` | `G001NW0623720AWP` | the Office Kiosk, on local adb |
| `TABULA_INTERCOM_B_SERIAL` | `G001NW0631460BXX` | the Desk Kiosk, reached over ssh |
| `TABULA_INTERCOM_B_HOST` | `m4mini` | the host that tablet is attached to |
| `TABULA_HOST_IP` | `ipconfig getifaddr en0` | the Mac address the tablets reach |
| `TABULA_INTERCOM_PORT` | `8099` | the throwaway signaling relay's port |

The automated run places calls by itself (it starts the spike's foreground
service over ADB, so no app has to be in front) and prints the evidence for the
packet's questions 1, 2, 4, 5 and 6 plus the automated half of 3 and 7. These
three checks make a tablet listen and speak in the room it lives in, so all of
them refuse to run (exit 2) unless `TABULA_AUDIO_OK=1` is set; only set it for a
run the owner has scheduled (ADR 0028).

```sh
TABULA_AUDIO_OK=1 TABULA_HOST_IP=192.168.1.126 tools/device/check_p90.sh
```

The 10-minute cost run needs a pty so Ctrl-C reaches it, and places its own
call:

```sh
TABULA_AUDIO_OK=1 script -q /dev/null tools/device/check_p90_cost.sh
```

`check_p90_cost.sh` prints each process's CPU as a share of **one core**
(`dumpsys cpuinfo`'s unit) beside the device's `TOTAL` line, the audio HAL's
share, the app's PSS and the battery temperature. Fire OS 7 exposes no readable
`thermal_zone*/temp` on this device, so battery temperature is the heat measure.

The judgments that need a person — speech at 1 m and 3 m, both tablets in one
room, and the clap — are `check_p90_owner.sh`, run from an interactive shell:

```sh
TABULA_AUDIO_OK=1 TABULA_HOST_IP=192.168.1.126 tools/device/check_p90_owner.sh
```

It places a 6-minute call and asks; the numbers of record stay the ones
`check_p90.sh` prints (connection times, jitter, loss, the instrumented tone
latency). Mouth-to-ear latency and hands-free quality are only ever this run's
judgment, and stay "not measured, needs an owner-scheduled run" until it
happens. Paste the whole output of each run into the packet Result.

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

## Hardware check (P3.6, At a Glance row)

Install a P3.6 build (or later), provision and pair, and configure the row on
the tablet's HA entry (the options flow):

- `at_a_glance.entities`: a lock and a door or garage sensor, e.g.
  `["lock.front_door","binary_sensor.garage_door"]`
- `at_a_glance.style` pill, `scale` 100, `hide_names` off, the default font

Leave Tabula in front with the panel on and run:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<token> \
ANDROID_SERIAL=<serial> tools/device/check_p36.sh
```

The script starts nothing itself: it reads HA's REST states and dumps the
tablet's log buffer, and the verifier starts the screensaver from HA, changes
one entity and dismisses the screensaver at each prompt. It checks: the row
draws with one chip per configured entity and each entity's live value
(`TabulaGlance` `row:` and `chip <id>: <value>` lines, plus the verifier's
eye for the placement under the date line); a state change in HA reaches the
chip within a few seconds (a new chip line with the new value); and
dismissing the screensaver closes the subscription (`TabulaService`
`entity data: closed`, F-AAG-04). Night mode is not run here - the row's
night flag is the never-night default until P3.4 lands - so hiding and
recoloring are checked in P3.4's run. Paste the whole output into the packet
Result.


## Hardware check (P5.4, log viewer and live log stream)

Install a P5.4 build (or later), provision and pair, leave Tabula in front
with the panel on, then:

```sh
TABULA_HA_URL=http://ha:8123 TABULA_HA_TOKEN=<admin token> \
TABULA_DEVICE=<tabula device id> tools/device/check_p54.sh
```

Run it once with `TABULA_DEVICE` unset to be shown the paired devices: the
first column is the Tabula device id the panel commands take, and the
second is the HA device id the `tabula.navigate` service takes. The token
must belong to an admin user, because every panel command is admin-only.

The script drives HA only - it never touches the tablet - and reads the
integration's own log view through `tools/device/ha_logs.py`, which speaks
the same `tabula/panel_logs` and `tabula/panel_logs_subscribe` commands the
Tabularium uses. In order it: prints the buffer the idle device holds;
follows the stream for twenty seconds while navigating the dashboard to a
normal path (an app line), to a path the frontend cannot render (a
console-tagged line, F-MGT-06's JavaScript console) and to a path carrying
`access_token=p54-secret-marker` (**the marker must never appear** - the
query string is stripped before the line is logged, PROTOCOL.md 4.8); then
stops the subscription, navigates again and shows that the buffer takes no
new line; and last reports the buffer's bound, which the snapshot prints as
`lines=<n>/<capacity>`.

The script fails, with the reason, if no app line or no console line
arrives, or if the marker is seen. If the console line never arrives, make
the dashboard log an error yourself (open a path that does not exist in the
HA UI) and run it again: some frontend builds report a failed route to the
network panel rather than the console, and the packet Result should record
which happened. Paste the whole output into the packet Result.
