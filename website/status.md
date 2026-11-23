# Status

Tabula is in early development.

## Implemented

The first milestone targets the Fire HD 10 Plus. These parts are built and pass CI; some on-device checks are still being run.

- Pairing with Home Assistant and registration as a device.
- Dashboard shown full screen, with a configurable start page and return to it after idle.
- Screen control: brightness, adaptive brightness, panel on and off.
- Clock screensaver with several faces, a photo slideshow, and a schedule.
- Notifications, text-to-speech and a notification chime.
- Screenshots on demand and an ambient light sensor.
- Offline-first behavior across Home Assistant restarts.

## In progress

- **Voice satellite.** A native `assist_satellite` entity with on-device wake-word detection.
- **Intercom.** Hands-free calls between tablets, and broadcasts, started from Home Assistant or by voice. Each tablet enables it only if its hardware supports it.
- Weather and at-a-glance screensavers, night mode, corner widgets.

## Planned

- Management panel inside Home Assistant.
- Recovery page on the tablet.
- Additional devices, starting with the Echo Show 8 (2nd gen) running community LineageOS.
- Camera-based presence sensing, processed on the device.

The detailed plan is [`docs/PLAN.md`](https://github.com/tlockney/tabula/blob/main/docs/PLAN.md). Work items are in [GitHub issues](https://github.com/tlockney/tabula/issues).
