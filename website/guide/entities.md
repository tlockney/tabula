# Entities and services

Each paired tablet is a Home Assistant device. Entities use standard platforms, so existing automations, scripts and dashboards work with them.

| Platform | Entity |
| --- | --- |
| `light` | Screen brightness; on and off control the panel |
| `media_player` | Device speaker. `tts.speak` and `media_player.play_media` work |
| `notify` | On-screen notifications. `notify.send_message` works |
| `switch` | Screensaver active, keep screen on, adaptive brightness |
| `number`, `select` | Screensaver brightness, screensaver mode, dashboard view |
| `button` | Restart app, reload dashboard, clear cache |
| `sensor`, `binary_sensor` | Battery, charging, connectivity, current page, ambient light |
| `image` | Latest screenshot, on demand |

`tabula.*` services add options the standard platforms lack, such as notification type, duration and chime.

Entity availability depends on the tablet. An ambient light sensor entity exists only on tablets that have the sensor.

The authoritative list is in the [plan](https://github.com/tlockney/tabula/blob/main/docs/PLAN.md) and the [integration source](https://github.com/tlockney/tabula/tree/main/integration/custom_components/tabula).
