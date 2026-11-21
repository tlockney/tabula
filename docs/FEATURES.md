# Features

Written by the spec side only (packet P0.2), then reviewed (P0.2r). Build-side executors read this file but never edit it; questions it leaves open go to the architect as ADRs.

Each entry has a stable ID (for example F-SCR-03) and describes behavior in our own words, with no constants.

## Conventions

- IDs are `F-<AREA>-<NN>`. They are stable: an entry may be reworded but its ID is never reused for something else. Cite IDs in packets, ADRs and `PROVENANCE.md`.
- "(constant: architect picks)" and "(default: architect picks)" mark every place a number, limit, timing or default is needed. They are chosen in `docs/decisions/0002-constants.md`.
- "HA:" lines say in general terms where a behavior surfaces in Home Assistant (entity platform, `tabula.*` service, `tabula_*` event, device setting). Entity names, payloads and schemas are the architect's to design (`docs/PROTOCOL.md`, `schema/`).
- An HA line that names an entity outside the plan's entity table (plan 5.5) describes a candidate; the architect confirms or drops it.
- "Open:" lines are questions the spec could not settle. The architect answers them in an ADR.
- "(independent design)" marks behavior the spec side added rather than derived from the source material.
- Phasing is set by `docs/PLAN.md`, not here. An entry being listed does not schedule it.
- Architecture assumptions (plan section 5): the device talks only to HA over one outbound connection; there is no device-side admin server apart from the later, off-by-default recovery page; settings are owned by HA and cached on the device; the `window.tabula` bridge is the seam for page scripts.

## Areas

| Prefix | Area | Entries |
| --- | --- | --- |
| F-DSH | Dashboard shell | 18 |
| F-NAV | Navigation | 16 |
| F-SCR | Screen control | 14 |
| F-SSV | Screensaver | 26 |
| F-WGT | Screensaver widgets | 9 |
| F-AAG | At a Glance | 4 |
| F-PRS | Presence | 12 |
| F-NTF | On-screen notifications | 12 |
| F-TTS | Text-to-speech and audio announcements | 11 |
| F-MED | Media playback | 9 |
| F-ALM | Alarms | 19 |
| F-TMR | Timers | 10 |
| F-CAL | Calendar | 11 |
| F-KSK | Kiosk and lockdown | 22 |
| F-LNC | Home launcher | 7 |
| F-GST | Gestures and actions | 12 |
| F-HA | Device presence in Home Assistant | 14 |
| F-EVT | Events reported to HA | 8 |
| F-MGT | Management from HA | 11 |
| F-OPT | Dashboard optimizations | 5 |
| F-UPD | Self-update | 10 |
| F-JSB | JavaScript bridge | 8 |

268 entries in total. Items dropped or deferred by plan section 4 are listed once, as one-line hooks, in the last section.

## Dashboard shell (F-DSH)

### F-DSH-01 Dashboard surface
The app's main screen is a full-screen WebView showing the user's HA frontend. It signs in to HA without the user typing credentials, using what the device obtained when it was paired. The dashboard stays loaded underneath every overlay the app draws (menus, overlay pages, screensavers), so returning to it never requires a reload unless something below says so.
Open: how the WebView session is authenticated (reuse of the pairing credential vs. a separate HA login flow) is the architect's call.

### F-DSH-02 Start target
A device setting names the "start target": the page the app opens on launch, after a crash or restart, and when the user or HA asks to go home. It can be any HA dashboard view, an app panel from the HA sidebar, a built-in HA page that non-admin users can open (energy, history, to-do and similar), or a custom page (F-DSH-13). Admin-only HA pages are never offered. Changing the start target does not move the page currently on screen; it only changes where future "go home" actions land. A start target that includes a URL fragment lands on that fragment (for example a pop-up opened by the fragment).
HA: the start target is a device setting. Candidate entities: a select for the start target and a "go to start target" button.
Open: whether those two candidates become entities or stay a setting plus a `tabula.*` service.

### F-DSH-03 Page catalog for pickers
Every place that lets the user pick a page (start target, rotation list, HA-dashboard screensaver, gesture/link targets, HA navigation select) uses one catalog: dashboard views first, then sidebar app panels, then built-in HA pages, then custom pages. The catalog is refreshed when HA reports a dashboard or panel was created, removed or edited, and when the connection to HA comes back after an outage. It is never refreshed on a timer. The last catalog is cached on the device so pickers and the HA select work right after start even if HA is briefly down. If the catalog actually changes, the HA select's options update; an unchanged catalog causes no churn.

### F-DSH-04 HA unreachable or page fails to load
(independent design; the source is silent on most of this.)
- First load with HA unreachable: show a native status screen (not a browser error page) that says what is wrong (no network, HA not answering, auth rejected) and retries on its own with backoff (constants: architect picks). Retry also happens immediately when the network or the device's HA connection comes back.
- HA drops while the dashboard is already showing: leave the page up and let the HA frontend do its own reconnect; do not reload. If the page is still disconnected after a grace period (constant: architect picks), reload it.
- Auth rejected: show the status screen with a pointer to re-pair; do not loop retries against HA.
- The WebView renderer crashes or stops responding: rebuild the WebView and reload the current target. If rebuilds keep failing in a short window (constant: architect picks), stop looping and show the status screen with a diagnostic message; report the condition to HA.
- Native features that do not need the page (screensavers, notifications, alarms, timers) keep working while the page is down.
HA: candidate diagnostic entity for "dashboard loaded / error", with the error reason.
Open: exact backoff schedule; whether a TLS error on HA's own address may be bypassed by a device setting; whether the diagnostic becomes an entity.

### F-DSH-05 Reload and cache clear
Two distinct actions: "reload" re-fetches the current page keeping the browser cache; "clear web cache" empties the WebView cache and then reloads. Clearing the cache does not log the dashboard out. An optional pull-down-to-refresh gesture performs a cache-clearing reload; it can be turned off for dashboards whose cards need downward drags (maps, sliders), in which case the reload action stays reachable from the menu, a gesture or HA.
HA: buttons for reload and for clear cache.

### F-DSH-06 Dashboard zoom
A device setting scales the dashboard page, shrinking it to fit or enlarging it. It applies live, without a reload. Web-page screensavers carry their own independent zoom.
Open: range and default (constant: architect picks).

### F-DSH-07 Hide HA header and sidebar
A device setting hides the HA frontend's top bar and/or sidebar on HA dashboards so only cards show. It does not affect custom pages or external pages. It can be toggled at runtime from the menu, a gesture, a dashboard link and HA; a hidden gesture can therefore expose HA's navigation temporarily without unlocking the kiosk.
HA: candidate switch. Open: switch entity vs. setting plus `tabula.*` service.

### F-DSH-08 Side menu
An edge swipe from the left of the screen pulls out a native drawer over the dashboard. It carries shortcuts useful at the wall, then the app version, then a light/dark theme toggle that is always the last row. Kiosk-mode behavior: F-KSK-02 and F-KSK-06.
- Which entries appear, and in what order, is a device setting edited in HA (choose and reorder). Defaults: architect picks.
- Candidate entries, each present only once its feature ships: go to start target, hide HA header/sidebar, start screensaver, touch lock (F-KSK-11), view freeze (F-DSH-17), app launcher, reload, clear web cache, restart app, exit app, plus shortcuts contributed by other areas (alarms, timers, calendar, now playing).
- No settings entry: Tabula has no on-device settings screen; settings are edited in HA.
- A chosen entry is hidden while it has nothing to do on this device: launcher with no apps picked; exit app while Tabula holds the home role (F-LNC-04); a feature not configured.
- Exit app and restart app require a confirm step.
HA: the entry list is a device setting.

### F-DSH-09 Links from the dashboard to app features
Tabula registers a private URL scheme (name: architect picks). A URL shaped `<scheme>://<action>[/<argument>]`, placed wherever the dashboard can open a URL (tap actions, markdown links, picture-elements icons), is caught by the app, which runs that registry action (F-GST-07); the page itself does not navigate.
- Availability: identical to the action's own rules. A refused link pops a toast giving the reason (e.g. launcher turned off).
- Kiosk: the restricted-menu allow list (F-KSK-06) is not applied to links. Hiding an entry from the menu while putting a link to it on the dashboard is the intended way to expose one feature under kiosk mode.
- Not linkable: exit app and restart app; they keep their confirm step.
- Actions that would switch on a camera or microphone need a genuine user tap; page script and HA cannot fire them this way. For the camera's own presence rule, see F-PRS-08.

### F-DSH-10 Links that open other apps
A second claimed scheme (name: architect picks) opens another installed app by package name. If the app is not installed, a toast says so. Generic browser intent links (which can name any component with arbitrary extras) are refused.

### F-DSH-11 Overlay page
A second web view stacked above the dashboard, with a visible close control. Opened by app-feature links, the "open web page" action, or HA; the dashboard stays live beneath it.
- Dismissal: close control, back, home, or an HA command. Whatever lay under it is visible again.
- Opened by HA: any screensaver is dismissed and the display woken first, so the overlay is actually visible.
- Idle: touching it counts as activity, but its presence does not stop the idle countdown; left alone, it ends up under the screensaver as usual.
- Freeze flag (optional): opening with the flag turns view freeze (F-DSH-17) on; closing turns it off again, unless it was already on beforehand, in which case it stays on. Freeze's own auto-release still applies.
- Sign-in: overlays on HA's address share the dashboard session; other origins get none.
- Schemes: http and https only.
HA: `tabula.*` services to open a URL overlay (optionally with the freeze flag) and to close it.

### F-DSH-12 Custom pages: list
Device setting: a list of custom pages. Each entry = display name + target. The target is either a path on the HA server (joined to HA's base URL; e.g. files in HA's local web folder) or a complete URL on some other server.
- On save the target is fetched; if no page comes back, the entry is rejected.
- Entries can be renamed, re-targeted or deleted. Deleting an entry leaves the underlying file alone.
- Each custom page is listed in every picker (F-DSH-03).
HA: edited along with the other device settings.

### F-DSH-13 Custom pages: behavior
- Page loads: HA dashboard to custom page, custom page to HA dashboard, and one custom page to a different one each reload the WebView. Two entries that point at the same file with different URL fragments switch by fragment only, with no reload, so one file can host several layouts.
- Caching: custom pages skip the HTTP cache and HA's service worker on every load, so edits appear the next time they load. HA dashboards cache normally.
- Features specific to HA dashboards (header/sidebar hiding, update filtering, view carousel) are inert on custom pages; HA's settings UI for those features notes this.
- The page's prefers-color-scheme follows the kiosk's current theme and changes live, without a reload.
- `window.tabula` is injected on custom pages, with kiosk state (kiosk mode, touch lock, screensaver, view freeze, rotation) and change events.
- Trust: a server used for a custom page is treated as first-party, like HA. Navigation among its pages happens in the main surface rather than as an overlay, and every page from it gets the bridge.

### F-DSH-14 Custom pages: shared HA connection
Optional device setting. When on, custom pages may send HA WebSocket commands and subscriptions through the bridge, riding on the HA access the tablet already holds, so the page never deals with a credential of its own. Subscriptions are restored automatically after a reconnect; waiting commands fail with a clear "connection lost" error. The page can do what the device's HA user can, except manage credentials or users (create/list/revoke tokens, change passwords, add users), which are refused. A page's connection ends when the WebView navigates away from that page. Errors are distinguished (not enabled, not configured, HA unreachable, auth invalid, refused).
Open: whether Tabula's single device-to-HA connection can multiplex page traffic, or this needs a scoped credential; the security boundary is the architect's.

### F-DSH-15 Return to start target after idle
A device setting: after no touch for a configured time (default: architect picks; a value meaning never turns it off), the dashboard returns to its start target. Rules:
- The timer keeps running behind a screensaver, so the device can reset overnight without lighting the screen. When the screensaver itself shows an HA dashboard view, the return happens when that screensaver ends instead.
- Paused by view freeze (F-DSH-17) and by page-declared interactions (F-DSH-18).
- In the swipe-map navigation style the return goes to the dashboard view of the current slot (F-NAV-13), not the global start target.
- Features with their own "where to land after closing" setting (e.g. now playing, other areas) override it for that one return.

### F-DSH-16 View rotation
A device setting lists pages (catalog entries, F-DSH-03) to cycle through, each shown for a configured dwell (constant/default: architect picks). Rules:
- Any touch (including gestures) pauses rotation; it resumes after idle.
- Paused while a screensaver is up; resumes from its current position afterwards.
- Paused by view freeze and page-declared interactions.
- Switching between views of HA dashboards is in-page (no reload); switching to/from custom pages reloads (F-DSH-13).
- While rotation is off, a page HA navigates to stays put (useful for automations that show a view temporarily). Turning rotation on restarts from the first entry.
- Unlike view freeze, turning rotation off does not stop the screensaver or screen-off.
HA: the list is a device setting; a rotation on/off switch is a candidate entity.
Open: whether external (non-HA, non-custom) URLs may be rotation entries and, if so, whether they show as overlays; whether rotation on/off gets a switch entity.

### F-DSH-17 View freeze
A mode that freezes the current view: while on, the screensaver does not start, rotation pauses, and return-to-start does not fire. Turning it on dismisses an active screensaver, except a now-playing view the user opened deliberately. It can be toggled from the menu, a gesture, a dashboard link, the bridge, an overlay page's freeze flag (F-DSH-11) and HA. An optional auto-release timer (default: architect picks) turns it off by itself. While on, the menu shows a visible notice that the freeze is active, with a way to release it.
HA: candidate switch. Open: switch entity vs. `tabula.*` service.

### F-DSH-18 Page-declared interactions (bridge)
Through `window.tabula`, a page can bracket an interaction (start/end, with an optional reason such as voice, announcement, timer, media). While any interaction is open, the screensaver, rotation and return-to-start pause. A media-reason interaction holds off only the screensaver; rotation and return-to-start continue. The bridge also lets a page dismiss the screensaver once, or suppress it until released.
Open: a safety timeout for brackets a page never closes (constant: architect picks).

## Navigation (F-NAV)

### F-NAV-01 Navigate a device from HA
HA can send a device to any catalog entry (F-DSH-03) or to an arbitrary HA path.
- Between HA dashboard views the switch happens inside the running frontend; HA is not reloaded.
- A running screensaver is dismissed so the target is visible.
- When the device has no HA dashboard loaded (F-NAV-14), the request is ignored and any screensaver stays up.
HA: the dashboard-view select lists the catalog; choosing an option navigates at once. A `tabula.*` service navigates to a catalog entry or a raw HA path.

### F-NAV-02 Current page reporting
The device publishes the URL on screen, and updates it on client-side route changes inside HA's frontend too (no page load involved).
HA: the current-page sensor. The dashboard-view select (F-NAV-01) shows the matching catalog entry, or unknown when none matches.

### F-NAV-03 Go home, bring to front, restart
- Go home: navigate to the start target (F-DSH-02).
- Bring to front: with another app in front, bring Tabula back; the dashboard appears as it was, not reloaded.
- Restart app: kill and relaunch the process; it reopens on the start target.
HA: the restart-app button. Go-home and bring-to-front buttons are candidate entities.
Open: whether those two are buttons or `tabula.*` services only.

### F-NAV-04 Close overlays
A single command that clears every layer stacked above the dashboard; the dashboard page itself is not touched.
- Closes: screensaver (now playing included), overlay pages, the app launcher, and full-screen feature views from other areas (calendar, alarm list, timers).
- Leaves alone: a ringing alarm, live notifications, and anything another area marks as safety-relevant.
- Display: when a screensaver was among the layers cleared, the panel is switched on.
- Swipe-map style: also moves to the center view.
HA: candidate button entity. Open: button vs. `tabula.*` service.

### F-NAV-05 Open another app
Target named by package; requested from the app launcher, a gesture, a link (F-DSH-10), or HA.
- Tabula keeps running in the background; switching back shows the dashboard as it was, with no reload.
- Active screen pinning is released before the launch.
- Unknown package or failed launch: error returned to the caller (toast on the device, service error in HA).
- While the other app is in front: screensaver idle counting stops and any screensaver showing is closed; the idle countdown restarts from zero once Tabula is in front again.
HA: a `tabula.*` service to launch an app, usable whether or not the on-device launcher is enabled.

### F-NAV-06 App launcher overlay
A native overlay of tiles for apps the user chose (selection and order: a device setting). Dismissed by its close control or a tap outside the tiles. Opening it is refused with a toast if the launcher is disabled or has no apps. Under kiosk mode only the allowed apps show (F-KSK-07).
HA: candidate button to open it.

### F-NAV-07 Auto-return from other apps
Optional device setting: Tabula brings itself back once an app opened from it has gone untouched for a configured period (default: architect picks).
- Idle-based, not a fixed timer: any touch in the other app restarts the period. Touches are observed through a transparent, pass-through overlay window (needs the overlay permission). An app that forbids overlays hides its touches, so the period then runs out regardless of use.
- Optional media/call wait (default: architect picks): audio or video playing in the other app, or an ongoing call, freezes the period; when that ends the full period begins again. Media comes from Android media sessions (needs notification-listener access); calls from the system audio mode (no permission). Lacking notification-listener access, only touches and calls freeze it.
- The permissions this needs are shown next to the setting (F-KSK-18).

### F-NAV-08 Deep links and system settings
Two registry actions hand off to Android while Tabula stays alive underneath: open any URI that some other app handles, and open the Android system Settings app. Invoked from the menu under kiosk mode, both obey the allow list (F-KSK-06).

### F-NAV-09 Back button
Back key, first matching rule wins:
1. A layer is open above the page (menu, overlay page, feature view): close the topmost one.
2. The page has history that stays within HA or the current custom page: step back in the page.
3. Kiosk mode on: nothing; the app is never left.
4. Kiosk mode off: architect decides (ignore, or send the app to the background).
Open: rule 4.

### F-NAV-10 Navigation styles
A device setting selects one of two styles:
- Drawer style: side menu (F-DSH-08), optional pull-to-refresh, optional sideways swipe between dashboard views (the view carousel).
- Swipe-map style: F-NAV-11 to F-NAV-16.
Selecting swipe-map switches off pull-to-refresh, the view carousel and the edge-swipe drawer. Their own settings are preserved (shown disabled with an explanation in HA's settings UI) and apply again, unchanged, after a return to drawer style.
Open: whether swipe-map is in the first release; which views the carousel cycles through is the architect's design.

### F-NAV-11 Swipe-map layout
- Shape: a plus sign. One center view and four arms (up, down, left, right), each arm holding a bounded number of views (constant: architect picks).
- Moving: a swipe along an arm steps one view outward or back toward the center. Away from the center, a swipe at right angles to the current arm is ignored.
- Drag feedback: neighbors preview during the drag; letting go beyond a distance threshold, or a fling, commits; anything shorter springs back (constants: architect picks).
- Options: a plain slide transition in place of the zoom effect; a mini-map showing position while swiping.

### F-NAV-12 Swipe-map contents
Slot candidates:
- HA catalog entries: as many distinct views as fit, no duplicates.
- A controls view: the menu's entries rendered as controls and cards.
- Native views from other areas, each at most once (calendar, alarms, timers, now playing, app launcher).
Any candidate may sit in the center (default center: architect picks). A view whose feature is not configured is skipped. The controls view is optional; without it, menu actions on the device are reachable only through gestures, dashboard links and HA.

### F-NAV-13 Swipe-map and the dashboard page
- A single WebView serves every HA dashboard view in the layout; moving between them is a route change inside the page.
- During the swipe a snapshot of the target view stands in until the page has rendered it, then the live page takes over (timeout: constant, architect picks).
- If the page changes view by itself (card link, HA navigation) to a view that has a slot, the map position follows.
- Return-to-start (F-DSH-15) targets the dashboard view of the user's current slot.

### F-NAV-14 Swipe-map without dashboard views
With no HA dashboard view in the layout, the HA page is never loaded, to save memory. Consequences:
- HA navigation requests are ignored, and both the navigation select and the page-URL sensor read as unknown.
- Exception: a screensaver that shows an HA dashboard view still causes the page to load.
- Adding a dashboard view at runtime loads the page; removing the last one unloads it.
- The stored HA session survives both.

### F-NAV-15 Swipe-map gesture conflicts
- Page first: a drag that begins on a slider, map, sideways-scrolling card or other draggable element belongs to the page, not the map.
- Vertical scroll: on a page or long native list that scrolls vertically, an up/down view change only begins once the content is already scrolled fully to its top or bottom.
- Map suspended: while something outside the layout covers the screen (overlay page, screensaver, ringing alarm, event-triggered camera view), swipes do nothing; they work again once it closes.
- Native views given a slot ignore their own close-when-idle timers; return-to-center (F-NAV-16) handles them instead. Opened outside the layout, they keep those timers.

### F-NAV-16 Swipe-map back, close, idle, kiosk
- Back: off-center, goes to the center; at the center, opens the controls view if the layout has one.
- Close control on a native view: goes to the center. The center view shows no close control.
- Return-to-center: after a configured period without touch (default: architect picks; a value meaning never turns it off), paused by view freeze. Falling due under a screensaver, it is applied silently so the screensaver ends onto the center. A screensaver may start over any slot without changing it.
- Kiosk mode on: slots whose action is not on the allow list (F-KSK-06) are skipped, except the center and HA dashboard views, which always remain. The controls view exists only if the restricted menu is enabled, and then lists allowed actions only. Anything else requires leaving kiosk mode (F-KSK-03).

## Screen control (F-SCR)

### F-SCR-01 Keep the screen awake
While the app is in the foreground, a device setting can stop the operating system's own display timeout from turning the panel off. Panel power is then decided only by Tabula (screensaver rules, HA commands).
HA: device setting; also an HA switch ("keep screen on").

### F-SCR-02 Brightness on start
A device setting chooses whether Tabula applies its stored brightness when the app starts, or leaves the system brightness alone. It is opt-in so a fresh install does not override the user's existing system brightness (default: architect picks).

### F-SCR-03 Writing brightness to the panel
Tabula sets the real system backlight when it has permission to modify system settings, and switches the system's own auto-brightness to manual so the system does not drift away from the value. Without that permission it falls back to dimming only its own window, and the permission screen shows the missing permission.

### F-SCR-04 Screen as an HA light
The panel appears in HA as a light. Brightness on the light changes the stored brightness and the panel follows. Turning the light off powers the panel off; turning it on powers it back on (see F-SSV-20 for what shows on wake). The light reports the brightness setting; a separate diagnostic sensor reports the level actually on the panel, which differs while adaptive brightness or a screensaver level is in effect.
HA: light entity; diagnostic sensor for actual panel level.

### F-SCR-05 Adaptive brightness
When on, brightness follows room light through a user-shaped curve. The reading comes from a light sensor in the tablet itself, or from an HA illuminance entity when there is none on the hardware (or when the user would rather HA decided). With an HA source: non-numeric states (unknown, unavailable) are ignored and the screen holds its last level; the last reading is remembered across restarts so start-up brightness is right before HA connects; if HA is unreachable the screen holds its level. Adaptive brightness runs on the device, so it keeps working when HA is down when the device sensor is the source.
HA: switch to turn adaptive brightness on/off; device settings for source and curve.

### F-SCR-06 Brightness curve
The curve maps light level to brightness through a small fixed number of user-adjustable points (constant: architect picks). The light axis is logarithmic. Between points the curve is smooth and monotonic, so a brighter room never gives a dimmer screen. The curve may be inverted (bright in the dark, dim in daylight) by placing the dark-end point above the bright-end point. Inner points are constrained between their neighbours; moving an end point's brightness rescales the inner points proportionally. The settings UI shows the live reading and the brightness it yields. Default curve shape: architect picks.

### F-SCR-07 Brightness commands while adaptive is on
A brightness value written from HA, the management panel or the JS bridge changes the stored base brightness when adaptive brightness is off, and changes the curve's top (bright-room) level when it is on, rescaling the curve. Requests below the curve's bottom level are rejected. To pin a fixed level from HA an automation turns adaptive brightness off first. Brightness changed from the system quick settings affects only the current session and does not change Tabula's settings.
Open: the plan (section 5.5) says a manual change through the light entity turns adaptive off; the architect reconciles that rule with this one.

### F-SCR-08 Smooth adaptive changes
Adaptive brightness changes the panel only when the computed level moves by more than a small threshold, and no more often than a minimum interval, so passing shadows do not cause flicker (constants: architect picks). Changes fade rather than jump.

### F-SCR-09 Brightness while a screensaver is up
A brightness command received during a screensaver updates the stored setting but leaves the screensaver's level alone; the new value takes effect on dismissal. Screensaver and dim levels scale with the adaptive curve, so a level that is comfortable by day becomes proportionally dimmer at night.

### F-SCR-10 Orientation
Choices: sensor-driven, or fixed landscape, portrait, or the reverse of either. Sensorless devices keep whatever orientation they booted in when sensor-driven is selected.

### F-SCR-11 System bars
Device settings hide the status bar and the navigation bar independently; a hidden bar can be revealed briefly with an edge swipe. Kiosk/lockdown mode always hides both regardless of these settings.

### F-SCR-12 Display cutout
A device setting decides whether the dashboard may paint up to the display's edge where the front camera interrupts it, or must keep its content inside the unbroken rectangle.

### F-SCR-13 Show over a secure lock screen
Optional. When on, the dashboard appears above a PIN/pattern/password lock screen so it returns after Tabula powers the panel back on; other apps and system navigation still require unlocking. When off, a secured lock screen is left in front of the dashboard. Unsecured (swipe-only) lock screens are cleared automatically when Tabula wakes the panel, because powering the panel off arms the lock screen even without a PIN.

### F-SCR-14 Panel power off
Tabula can switch the panel off and on again, which needs the system permission reserved for device administration. Without the permission it logs a warning, keeps the panel on, and offers a black-screen alternative (F-SSV-19) that needs no permission. Features that wait for rising light must not assume the light sensor keeps reporting once the panel has no power; on some hardware it stops.

## Screensaver (F-SSV)

### F-SSV-01 Idle screensaver
After a configurable period with no interaction (constant: architect picks the default), the dashboard is covered by a screensaver. A master switch enables the feature; turning it off dismisses any active screensaver. The screensaver never powers the panel off by itself and never stops the app underneath, so the HA connection, notifications and HA commands keep working while it shows. Panel power-off is a separate, explicit rule (F-SSV-19).
HA: switch for the master enable; switch showing and setting "screensaver active"; button to postpone (reset the idle timer).

### F-SSV-02 What counts as activity
Touch resets the idle timer. The idle timer stops while another app is in the foreground or the panel is off, and restarts when Tabula returns; a running screensaver closes when another app takes the foreground. Playing media holds the screensaver off unless the now-playing view is configured to take its place (F-MED). Opening a full-screen Tabula view that is meant to be watched (e.g. calendar, timers) pauses the idle timer while it is open, unless that view has its own idle close.

### F-SSV-03 Starting manually
The screensaver can be started from the side menu (the menu entry can be hidden), a mapped gesture, the HA "screensaver active" switch, a `tabula.*` service, or the JS bridge.

### F-SSV-04 Dismissing
Touch dismisses the screensaver and resets the idle timer, with these exceptions defined by individual modes: edge taps on photo slideshows step slides (F-SSV-12); modes that host interactive content can require a double tap (F-SSV-14, F-SSV-15); the calendar mode closes only through its close button (F-CAL). Other things that dismiss it: turning "screensaver active" off in HA, the postpone button, navigating the dashboard from HA, and enabling view freeze (F-DSH-17). Presence can dismiss it too (F-PRS).

### F-SSV-05 Mode selection
One active mode at a time, chosen from: clock, weather scene, photo slideshow (several sources), website, HA dashboard view, calendar, dim, black. Each mode has its own settings. A schedule (F-SSV-18) can switch modes by time of day.
HA: select entity for the mode.

### F-SSV-06 Black mode
Shows black with the backlight at its lowest. An option hides every overlay (widgets, At a Glance, notifications drawn over the screensaver) for true black; with that option on, the At a Glance HA subscription is not opened.

### F-SSV-07 Dim mode
Lowers the backlight to a dim level while the live dashboard stays visible. Because the page remains visible, the dashboard keeps rendering (the pause optimization in F-OPT does not apply).

### F-SSV-08 Clock mode
A full-screen clock with several faces: at least a digital face and an analog face with hands (further faces such as split-flap or rolling digits are optional, architect decides). Settings: font family and weight, 12/24-hour, AM/PM marker, seconds, date line, size, text color, background color or background image. Dates use the app language, falling back to the system language when the app has no translation for it.

### F-SSV-09 Clock background image
An optional picture behind the face. Source: a local file, or a web address the tablet downloads directly. A URL may also be re-fetched on an interval (default and interval: architect picks). Writing the setting always triggers a fresh load, identical value or not, which lets an automation refresh a picture served under a fixed address. A failed fetch keeps the previous image and retries later. A darkening layer keeps the clock readable over the image.
HA: text entity for the background source.

### F-SSV-10 Clock night mode
Reads light from the tablet's own sensor only; an HA illuminance entity is never used here. Below a light threshold the clock switches to a night color scheme (text and widgets in a single dim accent color, dark background); above the threshold plus hysteresis it switches back (constants: architect picks). Options: hide the background image at night, hide the corner widgets and the At a Glance row at night, and turn the panel off after night mode has lasted a set time, turning it back on when light rises. The night power-off only applies while the clock mode is showing; ringing alarms, now-playing and other foreground apps keep the panel on and restart the countdown. If the device lacks a light sensor, night mode is unavailable. Missing device-admin permission: night power-off is skipped without prompting. A black-screen alternative avoids power-off for sensors that stop reporting while the panel is off.

### F-SSV-11 Weather scene mode
An animated sky that reflects the condition of a chosen HA weather entity (all standard HA weather conditions) and the time of day from HA's sun entity (dawn/dusk/night), with a local-time fallback when the sun entity is missing. Optional overlays: a clock and a weather information bar (temperature, condition, daily high/low when the entity has a daily forecast, humidity, wind, visibility, apparent temperature, an optional place name). With no entity chosen, the mode shows a prompt to pick one. If weather data drops out it keeps the last condition; with no reading yet, a plain overcast scene is shown. Rendering must stay smooth on weak hardware and pause when the panel is off or the app is backgrounded. A local preview control lets a user try any condition/time of day without HA. Optional: blur behind overlays, lightning toggle.
Open: architect decides how elaborate the animation is for v1.

### F-SSV-12 Photo slideshow modes
Sources: HA media browser (single image, folder of images, video files), a folder on the device, photos picked with the system picker (copied into app storage so they survive reboots and need no broad storage permission), and a single image URL fetched fresh for each slide. Common behaviour:
- Transitions: none, crossfade, slide, zoom, slow pan-and-zoom (stills only), or random per slide.
- Fit: letterbox, smart fill (crop a little when photo and screen shapes nearly match; when they differ a lot, keep the full photo and fill the bars with a blurred, darkened enlargement of it), or always fill.
- Videos play muted, either whole or cut to the photo interval.
- The playlist is built when the screensaver starts; new files appear next time. Shuffle and include-subfolders options for folder sources.
- Images are decoded at panel resolution and previous slides released promptly (low-RAM devices).
- Optional edge zones: tapping near one side edge goes back one slide, near the other goes forward, and the interval restarts; elsewhere a tap dismisses.
- Optional photo details overlay from EXIF (date taken, camera details); no location lookup.
- URL source: the current photo stays until the next has loaded; a failed fetch keeps it and retries later; a small history of recent photos allows stepping back.
HA: buttons for next and previous slide (no-op in other modes).

### F-SSV-13 Website mode
Shows any URL full screen in its own web view sharing the dashboard's cookies, with its own zoom level. If the URL is on the configured HA server, it is authenticated with the dashboard session and shown without HA's header and sidebar; external pages load unchanged. A failed load or server error retries on an interval instead of showing an error page (constant: architect picks).

### F-SSV-14 Website dismissal option
An option makes the website mode require a double tap to dismiss so single taps reach the page.

### F-SSV-15 HA dashboard view mode
Shows a chosen dashboard view as the screensaver by navigating the existing dashboard web view in-page (no reload). On dismissal it goes back to the view that was showing before, or to the start target when the return rule (F-DSH-15) is on; if HA navigated the dashboard during the screensaver, it stays where HA left it. Header/sidebar hiding can be overridden for this mode only. A single tap dismisses without pressing whatever card lies under the finger; with the option that requires two taps turned on, the whole page stays usable and only a double tap leaves. The dashboard keeps rendering in this mode. Requires HA to be connected.

### F-SSV-16 Calendar mode
Shows the calendar view (F-CAL) as the screensaver, with its own choice of calendar view separate from the calendar's default. It stays interactive; only its close button dismisses it. Widgets and At a Glance are not drawn over it.

### F-SSV-17 Screensaver brightness
An optional separate backlight level applies while the screensaver shows (except dim and black, which have their own levels); the previous level returns on dismissal. The pre-screensaver level is persisted so a restart during the night does not adopt the night level as the day level. Two optional temporary brightenings return to the pre-screensaver level: while a notification is visible, and for a short window after any touch that leaves the screensaver in place (for example on a notification card) (constant: architect picks). Both also apply to dim, black and scheduled levels.
HA: switch for screensaver brightness; number for its level.

### F-SSV-18 Schedule
An optional list of daily time slots, each with a start time and a mode; a slot lasts until the next one starts, and the last wraps past midnight. No day-of-week variation. Each slot may override: brightness (fixed level, or inherit global/adaptive), panel power-off delay (including "never" even when the global delay is set), presence dismissal (F-PRS), widgets, At a Glance and the now-playing view. Overrides can only turn off overlays that are configured globally, not invent them. Edits apply live, including to a running screensaver. Crossing into a slot with a different power-off delay restarts that countdown.
HA: switch to turn the schedule on or off.

### F-SSV-19 Panel off after a while
An optional delay after which a running screensaver powers the panel off (requires device admin; constant: architect picks the range). Alternatively a plain black screen at minimum brightness can be shown instead of powering off (no permission needed; nothing is drawn on it, overlays included, until it is woken). Touch on that black screen returns to the dashboard.

### F-SSV-20 Wake behaviour after panel off
Unless configured otherwise (below), any wake from panel-off lands on the live dashboard: touch, power button, HA turning "screensaver active" off, a `tabula.*` service that stops the screensaver. One exception is fixed: turning the HA screen light on restores the screensaver (not the dashboard) and its power-off timer starts over, so a morning automation can light the photos and later dismiss to the dashboard. A setting extends that screensaver-preserving wake to chosen wake sources (presence (F-PRS), power button / any wake Tabula did not cause, touch on the black-screen alternative); with touch selected, touching the dark screen restores the screensaver, and touching again goes on to the dashboard. Under lockdown mode, presence and power-button wakes always keep the screensaver.

### F-SSV-21 Overlays on top of the screensaver
Notifications, timers and the now-playing view may draw over the screensaver. The screensaver honours them as described in F-NTF, F-TMR and F-MED.

### F-SSV-22 Pixel shift
Optional periodic small shifts of screensaver content to avoid image retention (not applied in black mode).

### F-SSV-23 Fade
Optional fade in and out. Modes that show the live page itself (dim, HA dashboard view) and web content switch without a fade.

### F-SSV-24 Presence from HA
A chosen HA occupancy/motion/presence binary sensor can act as a presence source: a transition to on dismisses the screensaver; an on state keeps the idle timer from expiring; a sensor that is already on when a screensaver begins has no effect on that screensaver until it goes off and on again. Options: dismiss only when the panel is off (so a visible slideshow survives people walking by), and postpone-while-present. Presence handling is ignored in lockdown mode. The tablet's own camera can feed the same rule; the presence area (F-PRS) describes it together with what it adds to this source.
Open: proximity sensors and hardware person sensors stay deferred (they are not in the plan's Phase 10 scope); the presence-source abstraction stays open to them.

### F-SSV-25 Interaction with the dashboard
While a screensaver fully covers the dashboard, dashboard rendering may pause (F-OPT). Automatic view rotation pauses and resumes where it was. The return-to-start timer (F-DSH-15) keeps running behind the screensaver, so the dashboard can be reset without lighting the panel.

### F-SSV-26 Lifecycle reporting
Starting and stopping the screensaver (with the mode and the cause) is reported to HA as a `tabula_screensaver` event and reflected in the "screensaver active" switch.

## Screensaver widgets (F-WGT)

### F-WGT-01 Corner widgets
Small overlays anchored to the four screen corners, at most one per corner. Each widget can be individually disabled without losing its corner assignment. A global switch shows or hides all widgets. Widgets draw over all modes except calendar and the black mode's hide-everything option; individual widget types may exclude more modes. A global scale and a per-widget relative scale, a global font with per-widget override, optional text shadow, and an adjustable dark vignette behind widgets for legibility. In clock night mode, all widgets use the night color.
HA: switch for "widgets shown".

### F-WGT-02 Corner priority
Widgets take priority over the photo details overlay, which moves to a free corner.

### F-WGT-03 Small clock widget
Time with optional date, 12/24-hour, color. Not shown over the clock mode.

### F-WGT-04 Weather widget
From an HA weather entity: optional place name, temperature (actual, apparent, or both when they differ), condition with icon, humidity, wind, visibility; lines missing from the entity are hidden. Sunny/partly cloudy icons switch to night variants after sunset (HA sun entity, local-time fallback). Condition names use HA's translations. Live while the screensaver runs; last values kept through brief disconnects. Hidden until an entity is chosen.

### F-WGT-05 Forecast widget
From an HA weather entity's daily forecast: one row per day with day name, condition icon, high and low; number of days and include-today are options. Shows nothing if the entity has no daily forecast; omits lows if only highs are reported.

### F-WGT-06 Battery widget
Reads the device battery directly (works offline): icon, charging indicator, optional percentage, optional "only when low" (hidden while charging). Hidden on devices without a battery.

### F-WGT-07 Next alarm widget
Shows the next on-device alarm when it is within a window (constant: architect picks), or the active snooze; empty otherwise. Tap opens the alarm list.

### F-WGT-08 Up next widget
Upcoming calendar events (F-CAL): person image or color initial, start time, title; options for how many events, how far ahead, and whether to include all-day events. Hidden calendars are excluded. Tap opens the calendar's list view.

### F-WGT-09 Entity widget
One HA entity: icon, value (state or a chosen attribute), optional custom name. Optional list of values for which it is visible (case-insensitive, matching displayed or raw value). The widget disappears entirely when the value is empty; unknown/unavailable still show. Formatting rules as F-AAG-03.

## At a Glance (F-AAG)

### F-AAG-01 Status row
A row of a few HA entity states (a small bounded number, constant: architect picks) shown on the screensaver, for checking things in passing (doors, locks, garage). Order is user-defined. Not drawn on the calendar mode. Optional on the now-playing view. Placement adapts to the mode (centered on black, under the date on the clock, near the bottom over photos/web). Wraps onto two lines in portrait. Text shrinks before names are truncated.

### F-AAG-02 Appearance
Each entity shows icon, name and value, on a contrasting pill unless the plain style is chosen (default: architect picks). The icon background reflects the entity's active/inactive state the way HA tiles do; text color never changes with state. Options: hide names (larger values), monochrome icons, plain floating text without pills (matching the clock color on the clock mode), scale, font.

### F-AAG-03 Value formatting
HA icon if set, else the domain default. States shown capitalized as HA presents them, numeric states rounded to HA's display precision with unit. An entity with an empty value drops out until it has one; unknown and unavailable still show.

### F-AAG-04 Independent subscription
The row (and the HA-backed widgets) subscribe only to their own entities over the device's own HA connection while the screensaver is showing, and unsubscribe when it closes. They do not depend on the dashboard page, so they work while the page reloads, shows a non-HA site, or filters its own updates (F-OPT).

## Presence (F-PRS)

### F-PRS-01 One presence rule, several sources
The tablet keeps a single answer to "is someone here now", and every source the user turns on feeds it: an HA binary sensor (F-SSV-24) and, on hardware with a usable front camera, the camera's own detectors. Each source has its own switch, and with more than one on the rule is satisfied while any of them reports presence. Turning the camera off leaves the HA source working, and the reverse. What presence does is the same whichever source produced it (F-PRS-03).
- A camera detection is not a single instant. Each one holds its source on for a short window after the trigger was last seen (constant: architect picks), so that one brief movement cannot keep a sensor on for as long as the camera remembers it. Everything downstream reads that held state and nothing finer: the idle countdown restarts when the window ends (F-PRS-03), postpone keeps working for as long as it holds (F-PRS-05), and the sensor and events follow it (F-PRS-09, F-EVT-08). An HA source keeps whatever timing its own entity has (F-SSV-24).
HA: the sources are device settings; the combined answer is the presence binary sensor (F-PRS-09).
Open: whether the window is one setting for the camera or one per detector, and what its default is.

### F-PRS-02 Camera detectors
Two detectors can be enabled separately, and either can feed the rule.
- Motion: reacts to change in the picture, so it works in a dark room. A whole-frame change, such as an exposure shift or a large colour wash across the screen, is discarded, and detection pauses briefly around the app's own transitions so its animations cannot wake it.
- Face: reacts only when a person is turned towards the tablet, so someone crossing behind it leaves the screensaver alone. It needs light and cannot work in the dark.
- Precedence: with both enabled, motion wins as the trigger. The cheap change detection is also the gate for the face model: it runs whether or not motion is switched on as a presence trigger, and the face model is asked only where it sees change, where a face was seen recently, or just after the camera opens. An empty room therefore costs no face work.
- Effect on a person who holds still: while a face is being tracked, the gate keeps the face model running for a short while without new movement (the same window as F-PRS-01), so someone reading the screen does not drop out between frames. Past that, a person who neither moves nor changes expression stops being reported even if they are still looking at the tablet.
Open: whether that stillness limit is acceptable or whether the face model should be re-asked on a slow timer while the screensaver is up; a timer costs power for a case the hold window already covers.
- Sensitivity: each detector has its own setting (range: constant, architect picks). For faces it moves the distance at which someone is recognised; for motion it moves how much change counts.
Open: whether the face setting is one distance control or two (near and far behaviour), and what each default should be.

### F-PRS-03 Dismissing the screensaver
A presence report dismisses a running screensaver, and the idle countdown starts again from the moment the source clears. A source that is already reporting presence when the screensaver begins does not dismiss it; it has to clear and report again, or a touch has to happen. Where a wake lands when the panel was off is decided by F-SSV-20.

### F-PRS-04 Only after the panel goes off
An option, shared with the HA source (F-SSV-24), keeps a screensaver that is already visible: presence is ignored while the panel is lit; it acts only once the panel has gone off. A photo slideshow then survives people walking past and still wakes the panel for someone arriving later. The panel counts as off only when it is really powered down, or when the black-screen variant (F-SSV-19) is showing at zero light; a black screensaver at any visible level does not count.
Open: whether the option applies to every source at once or can be set per source.

### F-PRS-05 Postponing the screensaver
An option makes presence hold the idle countdown, so the screensaver does not start while the room is occupied. It only works alongside dismissal for the same source. While it is on, the camera has to watch continuously whenever the panel is on, which is the most expensive setting here; the cost is discussed in F-PRS-06.

### F-PRS-06 Camera lifetime, faults and cost
- Master switch: the camera has its own switch, off on a new device, and nothing touches it until that switch is on (privacy, F-PRS-07).
- When it runs: only while something needs it — a screensaver is up, the panel is off, or postponing is on with the panel lit. It is released the moment none of those hold, without a lingering timeout.
- Busy or silent camera: another app holding the camera, a hardware fault, or frames that stop arriving is treated as a fault. Presence then reads as unavailable rather than absent, the tablet reports it, and the camera is retried instead of being left dead.
- Cost: analysis is light enough for a wall tablet but not free. The frame rate the detectors need is a setting (constant: architect picks), and the load counts against the plan's idle-cost target.
Open: whether the camera can keep watching while another app covers the screen or the panel is truly off; the plan's spike settles that, and the answer may differ per device type.

### F-PRS-07 Privacy
- All analysis happens on the device. Frames exist in memory only while they are being analysed: never written to storage, never logged, never uploaded, and never included in a backup or a diagnostics download.
- Only the state leaves: presence or not, which source produced it, and when it changed. No image, no thumbnail, no description of who was seen.
- No identification: the face detector answers "a person is looking at the tablet", not "which person", and keeps nothing between frames.
- Off by default, and turning the master switch off removes the feature from the device's behaviour entirely.
- The user is told when the camera runs. HA shows it (F-PRS-09), and the tablet shows a sign on screen while it is watching (independent design; the owner asked for presence, not for a hidden camera).
Open: what that on-screen sign looks like, where it sits, and whether it stays up for the whole camera session or only while a detection is being resolved.
Open: whether a brief live view of the frames the detector analysed appears after a face wake, as an aiming and sensitivity aid. It is a separate question from the sign and is deferred for now (see the deferred list); resolving the sign without it must not silently drop the aid.

### F-PRS-08 Enabling, consent and permissions
- The master switch cannot be turned on from HA alone: it needs a person at the tablet, or an ADB grant (independent design, plan section 7.6).
- First enable asks the OS for camera permission. With the switch on and the permission missing, the entry reads as on but inactive, with a way to grant it; when the OS has stopped offering the prompt, that way opens Android's app settings for the app.
- With no permission, or with it withdrawn by the OS, the source reports nothing, the rule falls back to its other sources, and the screensaver keeps working. The state says why.
HA: the master switch and its state; the grant itself can only happen on the tablet.
Open: whether a grant made over ADB is reflected back to HA as the switch coming on.

### F-PRS-09 What HA sees
- Presence (binary_sensor): on while any source reports someone there, using the hold window of F-PRS-01 for camera sources; unknown when every source is off or unavailable, never a stale off.
- Camera active (binary_sensor): on while the camera is running. This is the privacy indicator the plan asks for.
- Controls: the camera master switch, a switch per detector, the HA source's own picker (F-SSV-24), and sensitivity (candidates; the plan leaves the entity list to the architect).
- Entities appear wherever the tablet could supply them, whatever the camera switch says (F-HA-02). On a tablet without a usable camera, the camera entities and detector switches are absent, and the presence sensor follows the remaining sources.
HA: binary sensors and configuration entities, as listed.

### F-PRS-10 Lockdown
Under lockdown, presence neither dismisses nor postpones the screensaver, and it does not wake the panel; detection may still run for the entities. This matches F-SSV-20's rule that a lockdown wake keeps the screensaver.
Open: whether reporting to HA continues under lockdown, which would tell an automation that someone is in the room while the tablet is locked.

### F-PRS-11 Schedule
Screensaver schedule slots (F-SSV-18) can switch each detector off for their window, which also stops the camera when nothing else needs it. That is how a room gets faces in daylight and motion at night, or no camera at all overnight. Overrides can only remove what is configured globally.
Open: whether sensitivity can be set per slot, or only on and off.

### F-PRS-12 Limits
- Another app in front: where a rule needs the camera while the tablet is behind another app, the OS may refuse; presence reads unavailable and the tablet reports it (F-PRS-06).
- No usable camera: a custom image without camera support, a closed physical shutter, or hardware that fails. The settings say so, no camera entities exist, and the remaining sources carry the rule.

## On-screen notifications (F-NTF)

### F-NTF-01 Showing a card
HA can place a card on the tablet's screen.
- Placement: top region, above everything (dashboard, overlay, screensaver); what is underneath keeps running.
- Lifetime: removes itself when its duration runs out.
- Fields: message (required); optional title, duration, severity, icon, size factor, sound on/off, sound choice, volume, image. A missing optional field uses the device setting or built-in fallback.
HA: `notify` entity (text only); `tabula.*` service (all fields).

### F-NTF-02 Severity and icon
Severity values: info, success, warning, error. Missing or unknown -> fallback severity (default: architect picks). Severity sets the stock icon and the accent color. A Material Design icon name replaces the icon only; color stays with severity. Icons are bundled in the app, so no network is needed.

### F-NTF-03 Text formatting
The message accepts limited Markdown.
- Rendered: bold, italic, bold+italic, inline code, strikethrough, line breaks, paragraphs, headings (one bold style for every level), bullet lists with nesting, numbered lists (numbers kept as written), backslash escapes.
- Links: label only, not clickable; nothing on a card navigates.
- Left literal: underscores (entity IDs stay intact), an asterisk standing alone or followed by a space, and any syntax not listed above.
- Title: inline styles only.

### F-NTF-04 Size factor
Per-card scale applied to text, icon, padding and width together, for reading at a distance. Range and default: architect picks. Zero or invalid -> normal size.

### F-NTF-05 Duration
Per-card lifetime (default: architect picks). A special "no expiry" value keeps the card until it is tapped or dismissed from HA. Invalid -> fallback.

### F-NTF-06 Image
Optional picture under the text.
- Sources: an absolute web address, or a path relative to the HA server (camera proxy, static file, ...). HA-relative paths are fetched with the tablet's own HA credentials.
- Text shows immediately; the picture is added once loaded.
- Load time and file size are capped (constants: architect picks).
- Load failure -> card without picture; error logged.

### F-NTF-07 Stacking
- Newest card on top; each card times out independently.
- Visible cards are capped (constant: architect picks); overflow evicts the oldest.
- No de-duplication: a repeated message yields a separate card.

### F-NTF-08 Dismissal and identity
- Tap -> removes that card only; a tap does nothing else.
- The show call returns a card ID, unique per tablet (one alert sent to three tablets has three IDs).
- Dismiss with ID -> that card; dismiss without ID -> all cards.
- A candidate "dismiss all" button (F-HA-08) lets an automation clear an alert on every tablet once someone acknowledges it on one.
- Each card's exit emits `tabula_notification` (F-EVT-02).
HA: ID in the service response; event; candidate button.
Open: whether cards get action buttons that emit an event naming the chosen action (independent design; no source behavior requires it).

### F-NTF-09 Sound
- Sound on -> a chime at the notification volume (a device setting separate from the media volume; the OS master volume still scales it), or at the card's own volume if given.
- Sound off -> silent card.
- A card may name a file from the sound library; if the file is missing, the standard notification sound plays and the problem is logged.

### F-NTF-10 Sound library and default sound
- The tablet stores user-uploaded audio files (common formats); they persist across restarts and updates.
- Standard notification sound: a device setting choosing a built-in chime (always present) or a library file.
- Selected file missing -> the setting flags it; the built-in chime plays until the file is back.
- A test action in HA sends a real card so sound and loudness can be judged.
HA: device settings; uploads through settings management.

### F-NTF-11 Appearance settings
Device settings: card opacity (content behind shows through; text and icons stay opaque) and background blur behind see-through cards where the platform supports it. Defaults: architect picks.

### F-NTF-12 Interaction with the screensaver
- Card arrives during the screensaver -> drawn on top; the screensaver stays.
- Panel dimmed by the screensaver and "brighten for notifications" (device setting) on -> normal brightness while at least one card is up, screensaver level again after the last one leaves. Only brightness changes. The setting can be off per device (e.g. bedrooms).
- Tapping a card does not end the screensaver.
Open: whether a card should switch on a panel the screensaver already powered off (announcements do, F-TTS-04), and whether a black-screen variant hides cards.

## Text-to-speech and audio announcements (F-TTS)

### F-TTS-01 Speaker as a media player
The tablet's speaker is an HA media_player that accepts announcements, HA TTS and media URLs. Its volume is the media channel volume, and it is the volume HA controls directly (F-TTS-07). Standard HA TTS aimed at a media player works with no `tabula`-specific setup.
HA: media_player entity; `tabula.*` announce and stop services for the extra options below.

### F-TTS-02 Announcement content
Content is either text (synthesized by an HA TTS engine) or an audio URL. The tablet requests the audio from HA, then plays it. Text requires the HA connection; if HA cannot synthesize, the call returns an error.

### F-TTS-03 Voice choice
- Device settings: TTS engine (one of HA's TTS entities, or "first available"), language, voice.
- Per call: engine, language and voice may override them.
- HA refuses the language or voice -> retry with the engine's own defaults rather than staying silent.
- URL playback ignores all three.
HA: device settings; service fields.

### F-TTS-04 What the screen does
Two display modes; which one applies when a call names neither: architect picks.
- With card: a card carries the spoken text for the duration. The screensaver stays up; as with notifications, brightness is restored while the card shows, and a panel the screensaver powered off is switched on.
- Audio only: sound with no card, no screen wake, and the app is not brought forward.

### F-TTS-05 Chime, repeat
- Optional lead-in chime, played once (device setting, overridable per call). Sound: a bundled tone or a library file (F-NTF-10); if that file is gone, use the configured default sound, and the bundled tone after that.
- Repeat count and the gap between repeats are bounded (constants: architect picks).
- The response includes the clip's length.

### F-TTS-06 Conflict and stopping
- Latest wins: a new announcement cuts off the current one. Two calls landing nearly at once -> the later is played and the earlier call gets an error.
- Stop service: ends playback and aborts a fetch still in progress; the result says whether anything was stopped; having nothing to stop is not an error.
- A device setting can disable announcements; calls then fail with an error.
- A ringing alarm has priority. Open: whether an announcement during a ringing alarm is refused, queued or mixed.

### F-TTS-07 Volume channels
Four independent levels:
- media: music, video, announcements through the media player; this is the media_player volume;
- assistant: spoken responses, chimes, timer sound;
- notification: card chimes;
- alarm.
Assistant, notification and alarm levels are device settings. A call may pass a one-off volume as a fraction of the OS master volume; otherwise the media level applies. Tabula never changes the OS master volume itself.
HA: media_player volume; other channels as device settings.
Open: whether the non-media channels also get HA entities (candidate numbers); whether media_player mute is a true mute of the media channel.

### F-TTS-08 Ducking
While an announcement plays, Tabula's own other audio (its music player and similar) drops to a ducked level (architect picks) and returns to its prior level after the last overlapping announcement ends. Other apps' audio and sound produced by the web page are not managed.

### F-TTS-09 Adaptive volume
Optional room-following loudness (whether enabled initially: architect picks): softer in a quiet room, louder in a noisy one.
- Input: microphone loudness only; nothing is recorded or sent. Sampled only while Tabula is silent; samples close to its own playback are discarded.
- Decision point: the level is fixed when a sound begins and kept for that sound. A media stream is re-leveled only after a pause of some length (constant: architect picks).
- Smoothing: going up needs several loud samples in a row; going down is gradual; a sample after a long gap is used directly (constants: architect picks). Before any sample exists, each channel uses its configured level unchanged.
- Microphone unavailable (permission missing, OS muting the mic in the background) -> keep the last room level.
- Mapping: a short ordered list of points (count: architect picks) from room level to a fraction of the channel's configured level; never decreasing as the room gets louder; linear between points, clamped beyond the ends. The configured level (and the media_player volume) is the loud-room level.
- Per-channel opt-in: assistant, media. Never adapted: alarms, card chimes, announcements with an explicit volume, other apps, page audio.
- A test action plays a sample at the level the room currently implies.
- Profiles: the mapping and per-channel choices are room-specific, so new profiles leave them out (F-MGT-04); the master enable may be shared.
HA: device settings; candidate room-noise sensor.
Open: the microphone is otherwise deferred. Architect decides whether this waits for microphone support or reads room loudness from an HA sound-level sensor chosen in settings (independent design).

### F-TTS-10 Playback must always end
Rule: every sound (chime, announcement, TTS) reaches a terminal state.
- A stream ending partway through a decoded frame still completes.
- No progress for longer than a watchdog limit (constant: architect picks) -> stop it, undo ducking and screen changes, log, and return an error to the caller.
- A silent "playing" state never holds audio focus indefinitely.

### F-TTS-11 TTS troubleshooting
- Log, per sound and tagged with its ID: HA fetch (begin, headers, done), chosen decoder, playback start, position progress, end of stream / finish. Enough to diagnose silence or endless playback from the log alone.
- Optional capture: once armed, keeps the raw bytes of the next streamed sound in RAM only (size bound: constant, architect picks; discarded on app exit; re-arming overwrites). Actions: status (bytes, result), export, local replay with the normal decoder or a software one for comparison, clear. Replay requires a complete successful capture and runs one at a time.
HA: diagnostics download and/or a diagnostic `tabula.*` service, not a regular entity.
Open: whether capture/replay is worth building for the first release.

## Media playback (F-MED)

### F-MED-01 The device as an HA media player
The device's media_player (the same entity as F-TTS-01) plays audio HA sends: URLs and HA media-source items (resolved via HA).
- Controls: play, pause, stop, seek (when the stream permits), next / previous (when a queue exists), volume, mute.
- Reports: state, title, artist, album, artwork, position, duration; live streams report no position.
- Announcements and TTS duck the music (F-MED-09).
HA: the device's media_player entity.

### F-MED-02 Choosing what the on-screen player follows
The followed player is what the on-screen player UI (F-MED-03, F-MED-04) displays and controls.
- Either the device's own player or any other HA media_player; for another player the tablet is a remote and makes no sound. The own entity is not offered in the "other player" list.
- Metadata, artwork, progress, state and seeking come from the followed entity. A control appears only if the entity advertises the feature (no seek bar without seek support; no favorite or queue where absent).
- The full-screen view shows the followed player's name.
- An entity reporting a TV or line-in style source with no track counts as idle unless a setting says otherwise, so e.g. a TV soundbar does not block the screensaver.
HA: per-device settings for the followed player, never shared by profiles (F-MGT-03).
Open: whether the device's own player stays available in HA while another player is followed.

### F-MED-03 Floating mini player
- Shown while the followed player is playing or has a queue loaded; absent otherwise.
- Shows artwork, title, artist (scrolls if long) and a progress bar (none for live streams). A larger variant adds previous / play-pause / next.
- Draggable; its position is remembered.
- Swipe away -> hidden; playback also stops unless a "keep playing" setting is on.
- Paused -> stays with a play control until a pause timeout (constant: architect picks), then hides.
- Gives way to higher-priority full-screen interactions and comes back after them.
- On a track change, the old artwork is kept briefly to avoid flicker.
HA: device settings (show, size, keep playing on dismiss, pause timeout).

### F-MED-04 Full-screen Now Playing view
- Content: large artwork over a blurred copy of it; title, artist, album; optional transport, progress/seek, volume and mute, shuffle, repeat (off / all / one), favorite, queue panel, each only where the source supports it.
- Layouts: stacked; side by side; artwork only (a tap reveals details and controls, which fade after a delay, constant: architect picks). Text and controls scale separately. Artwork is drawn square unless clearly non-square (threshold: constant, architect picks), then at its own aspect.
- Opened by: playback start (setting), the mini player, the app's menu, a gesture, a navigation layout slot, HA.
- Closed by: close control; optional double-tap on an empty area; back key; optional presence/motion; playback stopping; a pause timeout. With controls visible, a background tap does nothing.
HA: `tabula.*` show/hide service (candidate button); device settings; shown/hidden state in the bridge's kiosk state (F-JSB-04).

### F-MED-05 Now Playing and the screensaver
- Followed player actively playing -> the normal screensaver is held off. View rotation and the return-to-start timer keep running unless the view is frozen.
- Mode "replace": an idle timeout during playback brings up Now Playing in place of the screensaver.
- Mode "beside": the screen is split between screensaver and Now Playing, axis following orientation; a screen too small along the split (threshold: constant, architect picks) gets full-screen Now Playing instead. Dashboard-view and dim screensavers keep showing the dashboard in their part. A tap on the screensaver part dismisses both; the player part has no close control. The screensaver's dismissal rules and brightness (schedules included) apply unless a "use normal brightness" override is set. Playback ends -> the screensaver takes the whole screen.
- Per schedule window: follow global / force beside / suppress Now Playing (music continues, no auto-open, brightness unchanged).
- Return target when Now Playing ends a screensaver session: the start target, the view that was showing when the screensaver began, or a fixed view (default: architect picks).
- Precedence: ringing alarms and timers and other higher-priority interruptions come first. If Now Playing is the center of a directional navigation layout, tap, key and motion dismissal are disabled (it would just reopen).
HA: device settings; the per-window override is stored with screensaver schedules.

### F-MED-06 Hardware volume buttons steer the followed player
- Option: the physical volume keys adjust the followed player rather than the tablet, either only while Now Playing is shown or whenever that player plays.
- Step per press is configurable (range: constant, architect picks); the mute key mutes it.
- Precedence: lockdown or a "volume keys disabled" setting -> keys do nothing.
- Key press while Now Playing is open -> its volume slider appears briefly.
HA: device settings.

### F-MED-07 Speaker grouping
- Offered only when the followed player supports HA grouping; otherwise no menu.
- The menu lists the leader, current members and joinable players with checkboxes; a change shows as pending until HA confirms the new group.
- Per-member volume sliders where the members allow it.
- A per-device allowlist limits joinable players; current members always appear so they can be removed.
HA: standard media player join / unjoin; the allowlist is a device setting.

### F-MED-08 Music library browser
Full-screen browser for finding music and playing it on the followed player (or another chosen speaker), driven by HA's media browsing for that player.
- Content: whatever the player's integration exposes through HA; search only where the integration offers it. Image, camera and TTS sources are left out.
- Actions where supported: open item; play (replacing the queue); start a collection partway through at a picked item; play next; add to queue. Feedback while playback starts; a queue addition confirms with a link to the queue.
- Speaker picker: current speaker and its group with volume; picking another makes it the followed player (F-MED-02). Playing with no speaker set asks for one first.
- Bottom bar mirrors the followed player (supported controls only); tapping it opens Now Playing, and closing that returns to the library.
- Queue panel: current and upcoming items; reorder, remove, jump, clear (confirmed) where the player exposes a queue; hidden otherwise.
- Opened from the app's menu, a dashboard link, a gesture, a Now Playing button, or HA. Closes after inactivity (constant: architect picks); the screensaver waits while it is open. Kiosk-mode action limits apply.
- Per-device options: default source and speaker, hide source picker, hide speaker picker, text scale, tile density.
HA: `tabula.*` open service (candidate button); device settings.
Open: how queue management works for players with no generic HA queue API.

### F-MED-09 Ducking and stop
- Interruptions (TTS, cards with sound, alarms, timers, other announcements) lower the followed player to a fraction of its current level (constant: architect picks); the level is restored after the last interruption ends.
- Own player: local gain change. Another HA player: a temporary volume command.
- Switching the followed player restores the previous one first.

## Alarms (F-ALM)

### F-ALM-01 Alarms live on the device
- Storage and scheduling: on the tablet, through the OS alarm-clock service.
- Ringing needs none of: HA connection, network, loaded dashboard.
- HA role: observe and control (F-ALM-15..17); never a prerequisite for a ring.
- Each device holds its own alarm list. Fleet profiles copy alarm defaults (F-ALM-18) only, never the alarms themselves.
HA: the alarm list is mirrored to HA for visibility and control.

### F-ALM-02 Alarm list and entry points
List view: one row per alarm with time, label, repeat days and an enable toggle usable in place. A snoozed row shows its next ring time plus a stop control.
Ways in:
- side menu;
- the next-alarm screensaver widget (F-ALM-14);
- a touch gesture action;
- the dashboard, through a `window.tabula` call or a dashboard link.
A separate entry opens the details of the soonest alarm. The screensaver is held off while the list or a details view is open. Whether the restricted lockdown menu includes alarms is a device setting.
HA: side-menu visibility and lockdown allowance are device settings.

### F-ALM-03 Creating and editing an alarm
Create: choose a time (format per F-ALM-19), save. Result: an enabled alarm, plus a short notice of the time left until it fires.
Editable in details: time, repeat days, label, tone, wake-up media (F-ALM-11), sunrise (F-ALM-09), volume ramp (F-ALM-08), spoken phrase (F-ALM-10); also save and delete.
Rules:
- Identity = time + repeat-day set. A create request matching an existing alarm enables and opens that alarm; no duplicate is made. Applies to all creation sources (on device, HA, settings import).
- Enabling or editing an alarm whose time today is already past schedules the following occurrence. A missed occurrence is never rung late.

### F-ALM-04 Repeat and one-shot alarms
- Repeat days set -> fires on each chosen weekday.
- No repeat days -> fires once at the next matching clock time, then switches itself off and remains listed.
- Repeating alarm switched off -> no rings until switched on again.

### F-ALM-05 Ringing
On fire:
- screen turns on; the alarm surface is raised above the dashboard or whatever app is foreground;
- audio goes to the OS alarm stream, so a silenced media stream cannot mute it;
- the alarm stream is set to the configured alarm level for the ring and put back to its earlier level afterwards; media and other stream levels are untouched.
Ringing surface: clock time, label, a snooze control and a stop control. Touches anywhere else are ignored (guards against accidental dismissal).
- Same-minute alarms merge into one ring listing every label; stop, snooze and skip apply to the whole group.
- A ring ends on: stop, snooze, an HA command, or auto-silence (F-ALM-06).
- With touch disabled by lockdown, HA can still stop or snooze.
- Screen precedence: ringing alarm > screensaver, notifications, timers view. The screensaver never covers a ringing alarm. (Ordering versus notifications and timers: independent design.)
HA: candidate entity: binary sensor "alarm ringing". Open: confirm this entity (not in the plan's entity list).

### F-ALM-06 Snooze, stop and auto-silence
- Snooze: quiet for the snooze interval (constant: architect picks; user-adjustable within an architect-chosen range), then the same ring repeats. No cap on repeated snoozes.
- Stop: ends the ring and cancels any pending snooze. One-shot -> stays off. Repeating -> waits for its next scheduled day.
- No response: the ring stops itself after a ring limit (constant: architect picks; user-adjustable). Outcome is "stopped", not "snoozed".
- Device off or rebooting through an alarm's entire ring window -> on boot the alarm is treated as skipped, not rung late. Back up partway through the window -> it rings for what is left (independent design).
HA: candidate entities: stop and snooze buttons; timestamp sensor for snooze end (unknown when not snoozed). Open: confirm these entities (not in the plan's entity list).

### F-ALM-07 Skip the next ring
- Where: the clock and weather screensavers, during a lead window ahead of an alarm (constant: architect picks; user-adjustable; one choice disables it). The control shows the upcoming ring time.
- Effect: removes only the next occurrence. Repeating -> later days unaffected. One-shot -> becomes disabled.
- After use: screensaver remains, control is removed.
- Same-minute group -> skipped as a unit.
- If a sunrise (F-ALM-09) is in progress, skipping cancels it too.
- Any edit to a skipped alarm reinstates its upcoming occurrence.
HA: reported via the alarm event (F-ALM-17). Open: whether HA also gets a "skip next" control (independent design).

### F-ALM-08 Gradual volume
Per-alarm option. When on, the tone or media begins low and climbs to the alarm level across a ramp period (constant: architect picks; user-adjustable; a single global value). An alarm inherits the global on/off choice until it is set on that alarm; after that the alarm's own choice sticks.

### F-ALM-09 Sunrise pre-brightening
Starts a lead period before the ring (constant: architect picks; user-adjustable).
- Own alarm view: backlight climbs from minimum to maximum while a full-screen fill shifts from dark warm red toward warm white. The ring begins with the fill at its end color.
- Tap during sunrise: a stop control appears for a moment. Stop cancels both the sunrise and that occurrence.
- Takeover screensaver case (F-ALM-12): screensaver content stays; only the backlight ramps. Dismissing the screensaver by touch aborts the sunrise, yet the ring still happens on schedule.
HA: sunrise start is an alarm event; a stop during sunrise is reported as "stopped".

### F-ALM-10 Spoken phrase
Tone alarms only (not media alarms). Ring cycle: tone a few times, phrase spoken, tone again, repeating (constant: architect picks cycle counts).
Phrase template placeholders:
- alarm label;
- display name of the HA user;
- time;
- time and date pieces individually (hour, minute, weekday, day of month, month, year), so engines that mispronounce combined forms can get them split.
Empty placeholders vanish together with neighbouring punctuation. A global phrase exists; alarms whose phrase was never customised track changes to it.
Speech source: HA TTS using the configured engine, language and voice (shared with announcements). The tone never waits for speech: slow or unreachable HA -> ring without speech; user name not available in time -> left out.
HA: uses HA TTS; engine, language and voice are device settings. Open: placeholder syntax.

### F-ALM-11 Wake-up media
Alternative to a tone: play something from an HA media source.
- Target player: this tablet's player or any HA media player (default: architect picks).
- Options: random item, shuffle; preview while choosing.
- A global media choice exists. Alarms marked "follow global" use it; an alarm can force tone-only.
- During the ring: plays at alarm level (ramped if F-ALM-08 is on), loops until stopped, and is exempt from the ducking the ring would otherwise apply.
- On stop or snooze: playback stops, the item is taken off the player's queue, and the player's earlier volume, shuffle and repeat are put back.
- Fallback to the tone when the source or player cannot be reached, or playback has not begun within a start timeout (constant: architect picks).
HA: browsing and playback go through HA. Open: whether this is in the first alarm release or arrives later with media playback.

### F-ALM-12 Ringing over the screensaver
Takeover applies when the current screensaver is clock or weather and its takeover option is on (default: architect picks). Then:
- the ring is shown inside that screensaver, keeping its look and widgets;
- the label takes the date line's place;
- snooze and stop are drawn in the screensaver's palette (weather variant: they take the weather strip's place);
- if the screensaver had lowered brightness through its own setting, brightness goes back to normal during the ring and drops again after snooze or stop.
Precedence:
1. Eligible screensaver showing -> ring on it.
2. Takeover on but something else showing -> start that screensaver, then ring on it.
3. Otherwise -> the alarm's own full-screen view.

### F-ALM-13 Alarm tone and volume
Tone choice per alarm: "use global default", a bundled tone, or an entry from the device sound library (shared with notification sounds; users can add to it). Selecting a tone plays a single preview at the alarm level. Alarm level is its own device setting, independent of media volume.
HA: alarm level and global tone are device settings.

### F-ALM-14 Next-alarm screensaver widget
Corner widget content, in priority order:
1. snoozed -> time the snooze ends;
2. an alarm within a look-ahead horizon (constant: architect picks) -> that alarm;
3. otherwise blank.
Tapping it opens the alarm list instead of dismissing the screensaver (an exception to the usual touch-to-dismiss rule).

### F-ALM-15 Alarm state in HA
Reported to HA:
- next alarm time, counting alarms that other apps registered with the OS alarm clock;
- ringing yes/no;
- snooze end time.
Automations can key off "ringing" going false (e.g. a morning routine).
HA: candidate entities: timestamp sensor (next alarm), binary sensor (ringing), timestamp sensor (snooze end). Open: confirm these entities (not in the plan's entity list).

### F-ALM-16 Controlling alarms from HA
Per target device, HA can:
- stop or snooze the current ring;
- list alarms (each with its next ring time);
- create, update, enable, disable and delete alarms, covering time, repeat days, label and the remaining alarm fields.
Create obeys the duplicate rule in F-ALM-03. This is the path for automations (for instance deriving a wake time from a calendar entry) and any later voice feature.
HA: candidate stop/snooze buttons; `tabula.*` services for alarm create/update/delete and a listing service that returns a response. Open: whether a command aimed at an offline device is queued or rejected.

### F-ALM-17 Alarm lifecycle events
One event per alarm per transition. Payload content: device, stable alarm ID, label, local time, repeat days, enabled flag.
Transitions:
- created (any origin);
- updated (includes enable/disable, and a one-shot turning itself off after firing);
- deleted;
- sunrise started;
- ringing (re-sent after every snooze);
- snoozed (with next ring time in UTC);
- stopped (from ring, snooze or sunrise);
- skipped (ahead of the ring);
- silenced (auto-silence after no response).
HA: a `tabula_*` alarm event. Open: whether events raised while disconnected are replayed on reconnect.

### F-ALM-18 Alarm settings
Per-device settings (owned by HA, cached on the device):
- 12-hour or 24-hour display for every alarm surface (initial value: architect picks; candidate: copy the OS clock format once, then independent);
- alarm level, global tone, global wake-up media;
- volume-ramp on/off inheritance value and ramp period;
- snooze interval, auto-silence limit, sunrise lead, skip window;
- global phrase; TTS engine, language, voice;
- side-menu visibility.
Fleet profiles carry these defaults; the alarms themselves never travel.
HA: device settings; an alarm category in fleet profiles.

### F-ALM-19 Time format
The single 12/24-hour choice controls alarm times everywhere they appear: list, picker, ring surface, widget, Tabularium alarm editors, and hour/time placeholders in spoken phrases.

## Timers (F-TMR)

### F-TMR-01 Timer model
Fields: optional name, initial duration, remaining time, state (running, paused, finished/alerting, gone).
- Ownership: the device that started it.
- HA has visibility of all timers on all devices.
- Origins: manual on device, HA; possibly a later voice feature.
- An unnamed timer is referred to by its initial duration. Duplicate names on one device make name-based commands ambiguous.
Open: whether the device or HA is the source of truth for a running timer, and how a timer behaves while the device is disconnected from HA (Tabula requirement: a running timer must still count down and alert locally).

### F-TMR-02 Setting and changing by hand
- New: a "new timer" control in the timers view -> pick hours/minutes/seconds, optionally type a name -> start gives a running timer.
- Adjust: tap the remaining time -> enter a new remaining time, or delete.
- Per-timer controls: pause/resume, cancel.
- A name is fixed once the timer exists.
HA: the same operations are available as `tabula.*` services (F-TMR-08).

### F-TMR-03 Full-screen timers view
Layout:
- Primary: the timer due soonest, large: name, remaining time, an emptying progress bar, pause and cancel.
- Secondary: remaining timers with the same fields, below the primary (or to its side on wide, low screens); scrolls when it overflows.
Closing:
- A close control hides the view; it reappears on the next trigger (start, near-end or finish of any timer).
- It also closes automatically when the last timer is gone.
Ways in: side menu, gesture action, dashboard link or `window.tabula` call, or as a page within the swipe navigation layout. Lockdown allowance is a device setting.

### F-TMR-04 When the view appears; screensaver interaction
Triggers:
- Start -> view opens and the screensaver is dismissed, if the "open on start" option is on (default: architect picks).
- Near end (remaining reaches a lead time; constant: architect picks; user-adjustable) -> view reopens if closed or covered, one time per timer; screensaver dismissed and suppressed.
- Finish -> view opens; screensaver dismissed and suppressed, brightness at full.
Screensaver while counting:
- Base rule: a running timer alone does not suppress the screensaver.
- Optional "hold screensaver" (default: architect picks): suppresses it only while the view is foreground and showing a timer that is running, not paused.
- View closed, or timer gone -> usual idle-timeout rules resume.
Precedence: a ringing alarm (F-ALM-05) beats the timers view.

### F-TMR-05 Finished timer alert
Appearance: the finished timer jumps to the top, alert-colored, labelled as expired, with a dismiss control. The sound loops until dismissal.
Settings: alert sound (bundled or from the device sound library), alert level, mute (visual only), and two optional unattended limits, each either "never" or a duration (ranges: architect picks):
- silence limit -> sound ends; the expired timer remains visible until dismissed; media that was ducked or paused for the alert resumes;
- dismiss limit -> same effect as tapping dismiss, including closing the alert and telling HA.
Both limits restart from the newest timer to expire, so another expiry while an earlier one is silenced sounds again.
Optional speech: HA TTS speaks a phrase between sounds. Two configurable phrases, one for unnamed timers and one for named timers (with the name inserted). Engine, language and voice are settings; if unset, HA's first available engine is used.
HA: candidate entity: a mute switch (not in the plan's entity list; Open: confirm). Other alert options are device settings.

### F-TMR-06 Floating timer badges (alternative display)
Display mode alternative to the full view: small floating badges, draggable, layered over any screen.
- Options: show name, badge size, badge (and name) for expired timers.
- Gestures: single tap toggles pause; double tap cancels.
- In badge mode the full view opens only when explicitly requested; open-on-start, near-end reopen and hold-screensaver are ignored.

### F-TMR-07 Shared timers across devices
- Each device has its own outgoing share list (other Tabula devices) and its own accept/refuse setting for incoming shares.
- Transport: HA acts as directory and relay; no device-to-device links.
- Receivers show shared timers (view or badges) tagged with the owner's device name; countdown and alerting stay synchronised with the owner.
- Pause, cancel, adjust and dismiss may be issued from any receiver; the owner applies them and the result propagates to all.
- Receiver-local: mute, silence limit, speech, badge options. Owner-controlled: dismiss limit; a dismissal on the owner closes the alert on every receiver.
- A shared timer whose owner goes silent beyond a staleness limit (constant: architect picks) is removed.
- HA identity (entities, events, service targets) belongs to the owner.
HA: share list and accept option are device settings. Open: integration relay design and stale detection.

### F-TMR-08 Timers in HA
Candidate entities per device: a count sensor (running + paused timers), a timestamp sensor for the earliest running timer's end, and the mute switch. Open: confirm these entities (not in the plan's entity list).
Services: start; list (returns a response); pause; resume; cancel; extend or shorten by a duration.
Event per timer on: start, change, cancel, finish, dismissal.
HA: sensors, switch, `tabula.*` timer services, a `tabula_*` timer event.

### F-TMR-09 Disabling timers
A device setting switches the feature off completely:
- gone: view, badges, sharing in both directions;
- hidden or skipped: menu entries, gesture actions, links, the swipe-layout page (a layout whose center is the timers page uses the start dashboard instead);
- removed from HA: the device's timer entities and services.
At the moment of switching off: running timers are cancelled and an alerting timer is silenced. Other timer settings are kept for later re-enabling. Alarms are not affected.

### F-TMR-10 Timer settings
Per-device: feature on/off, open on start, near-end lead, hold screensaver, side-menu visibility, sharing (enable, share list, accept incoming), alert sound, level, mute, speech, phrases and TTS, silence limit, dismiss limit, badge mode and badge options. Fleet profiles carry all of them except the share list and alert sound, which remain per device.

## Calendar (F-CAL)

### F-CAL-01 Family calendar from HA calendars
Native view built from a configured list of HA calendar entities (from any calendar integration). Per list entry:
- display name (initial value: architect picks; candidate: the entity's name);
- color: auto-assigned from a fixed palette (first unused) or custom;
- optional HA person, whose picture is used in avatars and chips in place of a name initial;
- enabled flag (disable without removing).
Entries can be edited, removed and reordered; order is significant (F-CAL-05).
HA: the calendar list is a device setting carried by fleet profiles.

### F-CAL-02 Opening and closing
Ways in: side menu, the up-next screensaver widget, a gesture action, a dashboard link or `window.tabula` call, or HA (opens a chosen view).
Closing back to the dashboard:
- after an idle period with no touch (constant: architect picks; user-adjustable; one choice means never);
- when the screensaver starts.
Exception: inside the swipe navigation layout, that layout's return rule governs, and the calendar may stay beneath the screensaver. Lockdown allowance is a device setting.
HA: candidate entity: an HA select (or similar) that opens a given view (Open: confirm; the plan's select covers dashboard view and screensaver mode only). Idle close is a device setting.

### F-CAL-03 Views
Four views; the initial one is a setting (default: architect picks). Navigation: a today control, previous/next by the view's unit; horizontal swipes do the same.
- Day: the hours run down the screen with a marker at the present moment; whole-day items sit in a strip above them; each calendar gets a vertical band labelled with its person and colour, and events sharing a band are drawn side by side.
- Week: a column per weekday, all-day entries on top, today emphasised; a tap on a column header goes to that day.
- Month: each cell shows the events that fit plus a count of the rest; a tap on a cell goes to that day.
- Agenda: a "today" panel (date, current forecast, upcoming item, then today's remaining items); a second panel with the coming days over a look-ahead window (constant: architect picks).
Small screens: chips shrink to avatars and scroll horizontally. Phone-sized: week and month become a day strip or dot grid over the chosen day's event list.
Settings: first day of week (or follow locale), 12-hour or 24-hour (initial value: architect picks; candidate: the OS clock format).

### F-CAL-04 Per-device calendar filtering
Header chips, one per calendar, toggle that calendar's visibility locally. A hidden calendar remains hidden until re-enabled and is also excluded from the up-next widget. Local only: fleet profiles do not carry it.

### F-CAL-05 Shared events
Merging: one event found on more than one listed calendar is drawn once, with a diagonal multi-band background (one band per calendar, in list order).
Same-event test, in order:
1. identical HA event ID;
2. failing that, equal title, start and end.
Hidden calendars: the event still appears for its visible calendars, minus the hidden ones' bands. Details list all owning calendars.
Open: matching rule when identifiers differ but timing matches imprecisely (independent design).

### F-CAL-06 Event details
Tap an event -> owners (calendars and people), date and time, a readable recurrence summary, location, description. Edit and delete controls are present only when editing is allowed (F-CAL-07).

### F-CAL-07 Editing (opt-in)
Read-only unless switched on. Two separate permissions (defaults: architect picks): add/edit, and delete.
Add/edit gives: a create control, tap an empty slot in a day lane to create there, edit from details.
Form fields: title; target calendars (more than one -> a single shared event; changing the set creates on newly chosen ones and removes from deselected ones); all-day; start; end; recurrence (simple common patterns; an existing complex rule is offered as-is); location; notes.
Recurring events:
- save -> choose scope: this occurrence, or this and later;
- delete -> confirm, then choose scope: this occurrence, this and later, or the whole series.
Shared event delete -> removed from every calendar it is on.
Calendars backed by an integration that cannot be written: not offered as targets, and their events lack edit/delete.
The calendar screensaver mode never allows editing.
HA: writes use HA calendar services.

### F-CAL-08 Weather on the calendar
Optional HA weather entity -> daily forecast on week and agenda days and on the phone-layout day header. No forecast traffic when the calendar is not on screen.

### F-CAL-09 Data freshness and offline
- Fetch from HA only while a visible surface needs the data (the open calendar, or the up-next widget when displayed).
- Updates: live subscription where HA offers one; otherwise periodic polling (constant: architect picks) plus refetch on navigation.
- Recurrences come pre-expanded from HA.
- HA unreachable -> keep showing the last loaded events with a "last updated" indicator.
- A non-admin HA user is sufficient to view and edit.

### F-CAL-10 Calendar as a screensaver; up-next widget
- Calendar screensaver mode: read-only, no idle close; details live in the screensavers area.
- Up-next widget: upcoming events from calendars not hidden on this device; a tap opens the calendar.

### F-CAL-11 Calendar settings
Per-device: calendar list (name, color, person, enabled, order), initial view, first day of week, 12/24-hour, weather entity, add/edit permission, delete permission, idle close, side-menu visibility. Fleet profiles carry all of them; per-device chip hiding (F-CAL-04) is excluded.

## Kiosk and lockdown (F-KSK)

### F-KSK-01 Two layers of protection
- Kiosk mode: persistent, survives reboot, stays on until turned off; a configurable set of protections.
- Touch lock: a temporary state on top that makes the screen untouchable (F-KSK-11). Ending it returns exactly to the previous protection state.
Everything is limited to what a non-device-owner app can do on Android/Fire OS. Fire tablets cannot be device owners (F-KSK-21), so on the first target lockdown is: start on boot + screen pinning + overlay-based reclaim + optional accessibility guard.
HA: a kiosk-mode switch (planned for a later phase); a touch-lock switch is a candidate entity. Example use: lock at bedtime, unlock in the morning.
Open: whether touch lock gets its own switch entity.

### F-KSK-02 Kiosk mode master switch
Turning kiosk mode on: the edge swipe opens only the restricted menu (F-KSK-06) if that is enabled, and nothing otherwise; the exit gesture (F-KSK-03) is armed; back can no longer leave the app; each enabled protection (F-KSK-05) is armed.

### F-KSK-03 Exit gesture
The on-device way to leave kiosk mode. Shape: a fast run of taps anywhere on screen (tap count from a small fixed set: constant, architect picks), optionally with the final tap held down.
- Sequence: gesture, then the PIN prompt if a PIN is set (F-KSK-04), then kiosk mode turns off; HA's kiosk state reflects the change and the normal menu (F-DSH-08) is back.
- It leaves kiosk mode only. It never opens settings; there is no on-device settings screen.
- It can be disabled; then only HA, or the recovery paths in F-KSK-17, can turn kiosk mode off.
- Taps are counted regardless of position and coexist with custom gestures (F-GST-05).
Open: whether leaving kiosk mode on the device is permanent until re-enabled, or re-arms by itself after a period (constant: architect picks).

### F-KSK-04 Kiosk PIN
Optional. After a correct exit gesture, a PIN prompt must be passed before kiosk mode turns off. The PIN is a device setting owned by HA and cached on the device, so it works offline. Wrong entries simply fail. The kiosk PIN is separate from the recovery-page PIN (F-KSK-17); neither one opens what the other guards.
Open: PIN storage form (hash) and whether to throttle repeated wrong entries (constant: architect picks).

### F-KSK-05 Kiosk protections (individually selectable)
- Status bar: an invisible strip across the top edge stops the notification shade being pulled down; with the shade guard (F-KSK-15) on, a shade that still opens is closed at once.
- Volume keys: hardware volume presses are swallowed.
- Power button: cannot be blocked; pressing it makes the app turn the screen straight back on. HA-commanded screen-off still works.
- Home/recents: blocked via Android screen pinning (F-KSK-08).
- Page long-press: context menus and text selection in the WebView are suppressed.
- Pull-to-refresh: ignored.
- Custom gestures: all gesture bindings (F-GST) go dormant while kiosk mode is on; they come back when it is off.

### F-KSK-06 Restricted menu
Optional; kiosk mode only. The normal edge swipe (or, in swipe-map style, the controls view) opens a cut-down menu showing only actions on an allow list the user picks.
- Starting set: whatever F-DSH-08 offers, in that order; the allow list narrows it.
- Never allowable: clear web cache, restarting the app or device, exit app.
- Anything else requires leaving kiosk mode (exit gesture, then PIN).
- Typical use: allow touch lock so anyone can freeze the screen before wiping it.

### F-KSK-07 Allowed apps
If the app launcher is on the allow list, a second list picks which launcher apps are reachable under kiosk mode. With kiosk mode on, the launcher offers only that subset, whichever way it is opened (menu, gesture, link, HA). Whether an app added to the launcher later starts allowed: default: architect picks. Ordering follows the launcher.

### F-KSK-08 Screen pinning and staying in front
- Pinning blocks home and recents. On a non-managed device, Android asks for consent the first time ("got it" once; some devices ask again every boot). Holding the home role (F-LNC) makes pinning unnecessary.
- Kiosk mode does not depend on that consent: if pinning is declined or lost, the app brings itself back to front shortly after losing focus (constant: architect picks). Swiping the app out of recents causes an immediate relaunch.
- Exceptions where the app does not pull itself back: an app launched through the app launcher/gesture/HA (auto-return F-NAV-07 governs that), and Android permission screens the app itself opened.
- Needs the overlay permission.

### F-KSK-09 Persistent service and self-heal
A foreground service runs on every install, regardless of features. It keeps the HA connection alive while the screen is off or another app is in front, relaunches the app after a crash or after it is swiped from recents, and carries an ongoing notification whose text says what it is doing. While the screen is off it holds a Wi-Fi lock and, optionally (device setting; default: architect picks), a CPU wake lock so keepalives fire on time; turning the CPU lock off is meant for battery-only devices. Relaunching needs the overlay permission. On Fire OS, where the system restarts a dead service slowly, a periodic watchdog alarm (interval: architect picks) also checks and relaunches.
HA: candidate diagnostic for app uptime / last start, so restarts are visible. Open: separate entity or an attribute of the app-version sensor.

### F-KSK-10 Start on boot
Device setting. The app launches itself after the device boots. Needs the overlay permission on recent Android versions; the requirement is shown beside the setting. Redundant (and stands down) while the app holds the home role.

### F-KSK-11 Touch lock
A temporary mode in which the dashboard stays visible and live but cannot be touched: every touch lands on a full-screen shield and briefly shows a "screen locked" notice. While it holds:
- All kiosk protections are armed at runtime without changing the stored kiosk settings; ending it restores the previous state exactly.
- All gestures (F-GST) are silenced, and so are bridge-triggered actions.
- The screensaver is suppressed unless a setting allows it; if allowed, wake-on-presence/motion style dismissals are disabled for the duration (so passers-by do not wake it), and touch, power-button and double-tap wakes leave the screensaver up.
HA: candidate switch (see F-KSK-01).

### F-KSK-12 Starting and ending touch lock
- Start: from HA, from an optional menu entry (default: architect picks), or via the restricted menu if allowed there.
- End: HA, or the touch-lock exit gesture followed by the PIN if set. The touch-lock exit gesture is a separate setting with the same options as F-KSK-03. Only one exit gesture is armed at a time (touch lock's replaces kiosk's while it holds), so both may use the same tap count. If its exit gesture is disabled, only HA can end it.
- Optional auto-unlock after a configured time (default: architect picks; a value meaning "stay locked" turns it off) applies only to locks started from the on-device menu (the screen-cleaning case). Locks started from HA hold until HA ends them. The exit gesture can end either early.

### F-KSK-13 Touch lock blackout
Optional: the shield is solid black and dashboard rendering pauses underneath to save power. The panel stays powered and the device stays fully connected and controllable from HA.

### F-KSK-14 Shield is system-level
The shield is a system overlay window covering the whole display, so it stays effective if another app comes forward. Without the overlay permission it falls back to covering only the app's own window; the permission status shows as missing (F-KSK-18).

### F-KSK-15 Shade guard (optional accessibility service)
An optional accessibility service that, only while touch lock or matching kiosk protections are active, collapses the notification shade the moment it opens and bounces the recents screen. Otherwise it is idle. It observes window-change events only and declares that it cannot read screen content. Android requires a person at the device (or ADB) to enable it.

### F-KSK-16 Known gaps without device ownership
Without device-owner lock-task mode, some system surfaces cannot be fully claimed: the navigation bar may flash before hiding; a determined swipe can show the shade for an instant before the guard closes it; pinning needs consent at least once; the app cannot reboot the device. HA's settings UI for kiosk mode states these limits so users are not surprised.

### F-KSK-17 Recovery and the offline case
Ways back into a locked device, in order of preference:
1. HA: kiosk mode and touch lock can always be switched off from HA. The kiosk PIN and exit gesture are HA-owned settings, so a forgotten PIN or a disabled gesture is fixed there.
2. ADB: the device-side fallback before Phase 4 (per the device runbook).
3. Recovery page (Phase 4): opt-in, guarded by its own PIN, switches itself off after a timeout. It can only change the connection keys (HA URL, credential) or reset settings, which is enough to reach HA again or clear a lockout.
Risk: HA unreachable, exit gesture disabled, no ADB access and the recovery page not enabled together leave no way in on the device.
Open: whether the app refuses to disable the exit gesture while that combination is possible, re-enables it after HA has been unreachable for a period (constant: architect picks), or relies on ADB and the recovery page.

### F-KSK-18 Permission status
The app tracks every special permission it uses, each with a one-line purpose and a state: granted; missing (an enabled feature needs it); or not granted (nothing enabled needs it yet; may be granted ahead of time). The device reports this list to HA. HA's settings UI shows, beside each feature's switch, the permissions that feature needs. For a permission not yet granted, HA can ask the device to open the matching Android grant screen; a person at the device must accept. The list warns that vendor battery managers Android cannot see may still kill the app. ADB grant commands are documented for provisioning from a computer.
HA: candidate diagnostic entity (or HA repair issue) listing missing permissions; a `tabula.*` service to open a given permission's grant screen on the device.

### F-KSK-19 Permissions Tabula uses (in-scope subset)
- Overlay ("display over other apps"): touch-lock shield, status-bar strip, reclaiming the foreground, crash/recents relaunch, start on boot, auto-return touch detection, fallback screen-wake.
- Battery optimization exemption: always needed; keeps the HA connection alive with the screen off.
- Post notifications: the service's ongoing notification (runtime prompt only on newer Android; the service works without it, the notification is just hidden).
- Modify system settings: real panel brightness instead of window dimming.
- Device admin: real screen off.
- Usage access: reporting which app is in front.
- Notification-listener access: seeing other apps' media sessions (auto-return wait, local media following). No notification content is read.
- Accessibility (shade guard): optional.
- Location: only for web pages that request geolocation when a page-geolocation setting is on.
- Install unknown apps: self-update (see that area).
- Camera: the presence sensor only (F-PRS-08). Microphone: deferred with the voice work.

### F-KSK-20 Foreground-app reporting
The device reports which package is in the foreground. That needs usage access; lacking it, the device can only tell whether Tabula itself is in front.
HA: candidate sensor.

### F-KSK-21 Fire OS 7 (Fire HD 10 Plus) specifics
- Home role unavailable: Fire OS refuses launcher replacement (F-LNC-03). Lockdown = start on boot + kiosk protections.
- Device owner unavailable: Fire's parental-controls component already occupies the profile-owner slot, so device-owner provisioning always fails. Consequences: no lock-task mode, no reboot by the app, no silent self-update (an update needs one confirmation tap; see the self-update area).
- Lock screen: switching the panel off through device admin engages Fire's swipe lock screen. On Fire OS 7 the app may dismiss that lock screen, provided it has no security, at the moment it wakes the panel, so waking shows the kiosk directly.
- Slow restart of dead services by the system: covered by the watchdog alarm (F-KSK-09).
- Microphone permission does not persist for a sideloaded app: Fire OS withdraws it at boot and after a Wi-Fi reconnect, and it has to be granted again over ADB. Anything that listens (F-GST-12) reads as inactive until it is.
- The system WebView can take down the whole process from its audio code; the self-heal relaunch handles that.
- The system WebView lacks some real-time media features; features relying on them need a fallback (matters for deferred camera work).
- Setup: install by ADB sideload; overlay, write-settings, usage-access, battery-exemption and device-admin grants can all be applied over ADB in a single session; the accessibility guard optionally too.

### F-KSK-22 Kiosk state on the bridge
`window.tabula` exposes kiosk mode, touch lock, header/sidebar hiding, screensaver state, view freeze and rotation state, with a change event whenever any of them moves.

## Home launcher (F-LNC)

### F-LNC-01 Act as the home screen
Device setting (master switch) that asks the OS to make Tabula the default home app. The device reports the role state to HA: held, awaiting the user's confirmation on the device, or blocked, plus what a person must do on the device when the OS needs one. A second option keeps screen pinning on even while holding the role (blocks recents natively, but brings back the pin consent prompt on non-managed devices).

### F-LNC-02 Acquisition per OS
- Recent Android: the system role dialog is shown; if the OS stops showing it after repeated denials, the app opens the system's default-home settings instead.
- Older Android versions: the system default-home settings open for the user to choose.
- Fire OS: not supported (F-LNC-03).

### F-LNC-03 Fire OS
Fire OS silently denies every attempt to replace its launcher (and even ADB role grants do not change the home button), so on Fire tablets the switch is not offered; HA's settings UI explains why and points to start on boot and kiosk protections. On the first target this whole area is therefore dormant.

### F-LNC-04 While the role is held
- After a reboot the tablet comes up in the app by itself, so the start-on-boot setting has nothing left to do.
- Home always returns to the app; kiosk-mode pinning is skipped (unless the keep-pinning option is on) since the role already covers home.
- Pressing home while the app is already in front closes the menu, app launcher, overlays and screensaver and lands on the dashboard.
- "Exit app" is hidden (the OS would relaunch it); "restart app" stays.

### F-LNC-05 Zero footprint when off
With the switch off, the app does not appear as a home candidate anywhere (launcher chooser, default-apps list).

### F-LNC-06 Getting out
Giving up the role never depends on app state:
- Switching the setting off from HA returns home to the remaining launcher at once.
- A release command from HA works even when the app's UI is frozen, provided the process is alive.
- Uninstalling clears it automatically.
HA: candidate entities: a switch for the role, or a release button plus a status sensor.

### F-LNC-07 Crash fuse
If the process starts repeatedly without drawing a first frame (count and window: constants, architect picks), the app returns the home role to the stock launcher by itself.
- The check runs first thing at process start, so it still works when the rest of the app is broken.
- After tripping: the switch reads off, and the reported status and the log give the reason. Switching it on again re-arms the fuse.
- Any start that reaches its first frame clears the counter, so routine restarts, updates and reboots cannot trip it.

## Gestures and actions (F-GST)

### F-GST-01 Gesture bindings
A device setting holds a list of bindings, each one gesture shape → one action. Bindings are live for as long as the app runs, kiosk mode or not (except as silenced by F-GST-06). Adding a binding asks for the shape, then the action, then only the parameters that action needs. The list is part of the settings synced across a fleet (except bindings to device-local plugins, deferred).

### F-GST-02 Touch gesture shapes
- Corner taps: a quick burst of taps, all landing in the same corner (tap counts: small fixed set, constant, architect picks).
- Corner hold: one finger kept on a corner for a configured time (range: constant, architect picks).
- Multi-finger tap: a single or double tap made with more than one finger, anywhere (finger counts: architect picks).
- Multi-finger hold: several fingers resting anywhere.
- Corner pattern: taps on corners in a set order, working as a secret pattern; the least conspicuous shape, suited to sensitive actions.
Corner zones have a fixed physical size (constant: architect picks), independent of screen size.

### F-GST-03 Observe, never intercept
The recognizer only watches: touches that form a gesture are still delivered to the dashboard as normal. Shapes are picked (corners, extra fingers, holds) to stay clear of ordinary card taps and swipes. A long press in the page may still trigger text selection or the page's context menu unless kiosk mode suppresses that (F-KSK-05).

### F-GST-04 Timing and disambiguation
- Taps form one run only while each comes soon after the previous; a long gap or a tap outside the corner starts over. Corner patterns tolerate a slower pace.
- Holds fire at the configured duration with the finger still down, not on release.
- Two bindings on the same corner with different tap counts: the lower count waits briefly to rule out another tap before it fires.
(All windows: constants, architect picks.)

### F-GST-05 Coexistence with other touch features
- The kiosk exit gesture's rapid-tap counter is position-blind and unaffected by bindings; its hold variant (single finger anywhere after a tap run) cannot collide with corner holds or multi-finger holds.
- A gesture counts as a touch: it resets the idle timer and pauses rotation.
- A tap that dismisses a screensaver also counts as the first tap of a gesture: two quick taps in a corner of a dark screen both wake it and run the bound action.

### F-GST-06 Silencing
With kiosk mode on and its "disable gestures" protection selected, all bindings are dormant until kiosk mode is off. Touch lock silences every binding and also actions requested through the bridge. Actions run from the HA side are never silenced by either.

### F-GST-07 Action registry
A single registry of named actions, each with typed parameters, used by gestures, menu entries, dashboard links (F-DSH-09), HA services/buttons, and the `window.tabula` bridge. Every caller gets the same behavior and availability rules (an action whose feature is disabled refuses with a toast stating why). In-scope actions:
- App: go to a catalog page; open a web page as overlay; reload; start screensaver; stop screensaver; toggle view freeze; toggle HA header/sidebar hiding; open app launcher; start/stop touch lock.
- Android: open an app by package; open a URI handled by another app; open system Settings.
- HA: invoke an HA service (target entity and extra fields are optional); run a script; trigger an automation; fire an event (type, optional data).
- Added by other areas as they land: alarms (stop, snooze, open list, open next), timers view, calendar view, now playing / play-pause, and similar.
Open: whether registry actions are callable from the bridge by any page or only by trusted (HA-origin/custom) pages.

### F-GST-08 HA action feedback
HA actions (service, script, automation, event) have no visible effect of their own, so on completion the device shows a brief toast naming the action type and what ran. On failure the toast is styled as an error and says why (not paired, HA unreachable, call rejected). Other actions confirm themselves by acting.

### F-GST-09 Validating HA actions
When an HA action is configured, its domain, service and entity are checked against the connected HA before the binding is saved.

### F-GST-10 Which device ran it
HA actions carry the originating device's identity so one binding shared across a fleet can act on that device's room:
- Script and automation: as variables (no script fields required).
- Event: added to the event data next to user data.
- Service call: only substituted where the data contains explicit placeholders for device id or device name, since many services reject unknown keys.
If a placeholder cannot be filled (e.g. no device name set), the call fails with an error toast.
Open: placeholder syntax and variable names (architect picks).

### F-GST-11 Gesture events to HA
(independent design) Optionally, firing a binding also emits a `tabula_*` event identifying the device and the gesture, so automations can react without a per-binding event action.
HA: a `tabula_*` event.

### F-GST-12 Sound-triggered gestures
A binding can use a short run of claps rather than a touch shape, so an action can be fired from across the room without approaching the panel.
- Count: the run has a user-chosen length inside a small allowed range (constant: architect picks).
- Timing: the claps must follow one another closely; a longer pause closes the run (window: constant, architect picks). As with corner tap counts, a binding waiting for a longer run makes the shorter one hold briefly before it fires (F-GST-04).
- Detection: the app listens on the microphone and reacts to a sudden broad sound that stands out from the room's background level; the threshold follows that background, and all of it runs on the device with no network service.
- Microphone: where voice capture is already running, the same input is reused; otherwise the app captures only while at least one clap binding exists. On Fire OS, where the OS withdraws microphone permission at boot or after a reconnect (F-KSK-21), the binding reads as inactive until it comes back.
- Suppression: silenced the same way as touch shapes (F-GST-06), and ignored during a voice turn so that speaking cannot fire an action. Where voice input is muted, nothing is captured at all.
Open: whether this ships on hardware whose microphone permission does not stay granted (Fire OS); the architect decides alongside the voice work in the plan.

## Device presence in Home Assistant (F-HA)

### F-HA-01 One device per tablet
- Each paired tablet is exactly one HA device in the `tabula` integration; all its entities and services belong to it.
- The device and its entity IDs survive app restarts, app updates and renaming the tablet. A rename changes display names only; automation targets do not move.
- A factory reset or an app reinstall discards the pairing: the tablet pairs again and becomes a new HA device. The old entry stays in HA until the user deletes it; its settings can be carried over with a restore (F-MGT-05).
HA: one device per tablet.

### F-HA-02 Entities follow capability
Rule: create an entity only when the tablet can supply it.
- Hardware absent -> no entity (no battery level on a mains-only tablet, no illuminance without a light sensor).
- Privilege absent -> no entity (no reboot control unless the app is allowed to reboot).
- Feature-bound entities (media transport, calendar view, and similar) are added when the feature is configured and removed when it is switched off.
- No entity is kept as a permanently unavailable placeholder.
HA: the device reports its entity set; HA updates it in place.

### F-HA-03 Hiding entities
A device setting lists every entity the tablet could offer; the user can untick any of them. Changing the selection updates the HA entity set without re-pairing. Initial selection: architect picks.
HA: device setting.

### F-HA-04 Connection loss
- Link to HA lost -> every entity of the device becomes unavailable (no stale values, no invented "off").
- Link restored -> every entity is refreshed from the device's current state.
HA: all entities of the device.
Open: whether the connectivity binary sensor (F-HA-09), which HA computes itself, reads "disconnected" instead of going unavailable with the rest.

### F-HA-05 Diagnostic sensors
Settled (plan): battery level (tablets with a battery only), charging, current page, app version. Current page is the URL in the dashboard and follows in-app (single-page) route changes as well as full loads.
Candidate entities, diagnostic category:
- battery temperature, where the hardware reports it;
- CPU load (where the OS permits measuring it), CPU clock, CPU temperature (where reported);
- memory free and total; storage free and total, polled (interval: constant, architect picks); a full disk reads as zero, never unknown;
- model, OS version and build;
- foreground app (Tabula or which other app);
- panel brightness as read back from hardware, not the last requested level;
- IPv4/IPv6 addresses of the active wired or wireless link (routable IPv6 preferred over link-local; correct while a VPN is up), plus per-interface detail so wired and wireless can be told apart; re-read soon after any network change;
- app start time, network-up time, time of the last successful exchange with HA.
HA: sensor / binary_sensor entities.
Open: which candidates ship, and in which phase.

### F-HA-06 Ambient light sensor
Candidate entity, only on tablets with a light sensor. A real change goes out at once; an oscillating reading is rate-limited to one update per interval (constant: architect picks) so HA's recorder is not flooded.
HA: candidate illuminance sensor.
Open: whether to expose it at all.

### F-HA-07 Activity timestamps
- Next screensaver (settled): when the idle countdown will reach the screensaver. Recomputed on touch, on dismissal and when the idle timeout changes, so automations can trigger on it or count down without per-second updates. Unknown while no countdown runs (screensaver disabled or already up, freeze mode, another app in front, and similar). Under continuous touching, republishing is throttled (minimum gap: constant, architect picks), but a fresh value is always sent before the previously published time arrives.
- Last interaction (candidate): time of the last touch or user-initiated wake; kept across app restarts.
HA: timestamp sensors.
Open: whether last interaction ships.

### F-HA-08 Controls (buttons)
A button does exactly what the same action does on the tablet.
Settled (plan): restart app, reload dashboard, clear web cache.
Candidate buttons:
- go to the home dashboard; bring Tabula to the front;
- close overlays: removes everything layered over the dashboard (screensaver, calendar, app launcher, overlay pages, media library and the like) and leaves the dashboard where it was; ringing alarms and notification cards are not touched; when it removes a screensaver, the panel ends up powered;
- restart device (only where the app holds reboot privilege, e.g. device owner);
- postpone screensaver (idle countdown starts over);
- slideshow next / previous (photo screensaver only; the new slide then stays for a full interval; does nothing in other modes);
- dismiss all notifications (F-NTF-08); open app launcher (when enabled); media transport for the followed player (when media is configured); alarm stop / snooze (alarms area); take screenshot (F-HA-12).
HA: button entities.
Open: which candidates become buttons and which stay `tabula.*` services only.

### F-HA-09 Connectivity
Binary sensor: on while the tablet holds its link to HA.
HA: connectivity binary_sensor.

### F-HA-10 Selects
Settled (plan):
- Screensaver mode (options defined in the screensaver area).
- Dashboard view: choosing an option navigates there immediately. Option order: views of every HA dashboard, sidebar panels, built-in HA pages, the user's own pages. The list is rebuilt when HA signals a dashboard or panel create/delete/edit and after a reconnect; it is never polled. The last list is cached so the select works at startup before HA answers. A rebuild must leave the device's other entities alone.
Candidate selects:
- Start dashboard: same option list; sets where the tablet starts, the "go home" target and the reload target after a crash; changing it does not navigate.
- Calendar view: closed or one of the calendar layouts; choosing opens or closes it.
- Theme: automatic / light / dark; light or dark forces the dashboard theme and overrides any theme schedule or sync while set.
- Clock style (screensaver area).
HA: select entities.
Open: whether start dashboard, theme, calendar view and clock style become selects or remain device settings edited in HA only.

### F-HA-11 Switches and numbers (configuration)
Settled (plan); each reflects device state both ways and HA always shows the live value:
- switch "screensaver active": on starts the screensaver now, off ends it;
- switch "keep screen on";
- switch "adaptive brightness";
- switch "kiosk lockdown" (later phase);
- number "screensaver brightness".
Volume is not a number: it is the media_player's volume (F-TTS-01, F-TTS-07).
Candidate entities (until decided, these are device settings edited in HA):
- switches: screensaver enabled; freeze mode (keep current view, suppress screensaver); view rotation; kiosk mode; HA header/sidebar hiding; screensaver dimming; screensaver schedule; screensaver widgets; Now Playing view and its layout options;
- numbers: non-media volume channels (F-TTS-07); idle timeout (a new value takes effect by starting the countdown over; a "never" value leaves only switch, schedule or gesture to start the screensaver); screensaver-to-screen-off delay (a "never" value disables it; a change while the screensaver runs starts that countdown over; an active schedule entry carrying its own value takes precedence);
- text: clock screensaver background image (path or URL; each write reloads it, even with the same value).
Screen power and brightness are the light entity (screen area).
HA: switch / number entities, configuration category.
Open: which candidates get entities and which stay settings only.

### F-HA-12 Screenshot
Later phase (plan). An image entity shows the screen, refreshed by the capture action (whether a plain HA fetch also refreshes it: architect decides). A timestamp holds the time of the last capture started by that action and persists across restarts; ordinary image fetches leave it alone.
HA: image entity (later); candidate capture button and timestamp sensor.

### F-HA-13 Update entity
Later phase (plan). Shows installed and newest version with release notes and offers install. An HA refresh request triggers an immediate release check, independent of the periodic schedule.
HA: update entity (self-update area).

### F-HA-14 Device services
`tabula.*` services cover actions that take parameters:
- show / dismiss notification (F-NTF); announce / stop (F-TTS);
- launch an installed app by package name: error if absent or not launchable; lockdown re-pinning and auto-return still apply;
- open a web page overlaid on the dashboard, with an optional "freeze until closed" flag; close it;
- set brightness (screen area); set the followed media player (media area).
Rule: a service that cannot act returns an error to the caller; nothing fails silently.
HA: `tabula.*` services targeted at a device.

## Events reported to HA (F-EVT)

### F-EVT-01 Event basics
- HA bus events named `tabula_*`, one name per subject; a field says which change occurred.
- Each event carries the originating tablet's HA device, so automations can filter per tablet.
- Events fire regardless of which entities are enabled.
Open: events raised while offline: drop them, or buffer and replay them with their original timestamps after reconnecting.

### F-EVT-02 `tabula_notification`
One event per card as it leaves, with the reason: tapped, timed out, dismissed from HA (by ID, clear-all, or the dismiss-all control), or evicted by overflow. Payload: card ID, title, message, severity. A bulk clear yields one event per card.

### F-EVT-03 `tabula_screensaver`
Emitted on screensaver start and on screensaver end.

### F-EVT-04 `tabula_screen`
Emitted on screen on and on screen off, with an origin field:
- `tabula`: screensaver power-off, an HA screen command, a touch or schedule wake;
- `system`: hardware power key or the OS display timeout (the app cannot tell these two apart).
While keep-screen-on is active the OS timeout cannot fire, so a `system` off then means the power key.

### F-EVT-05 `tabula_alarm`
Per alarm, emitted on: create, edit (including enable/disable), delete, wake-light ramp start, ring (and each re-ring after snooze), snooze, stop, auto-silence after ringing unanswered. Payload: alarm ID, label, time, repeat days, enabled; snooze end time on snooze. See the alarms area.

### F-EVT-06 `tabula_timer`
Emitted on: start, change (time added or removed, pause, resume), cancel, finish (ringing begins), ringing dismissed. Payload: timer ID, name, total, remaining, running flag. See the timers area.

### F-EVT-07 `tabula_announcement`
Emitted when an announcement starts and when it ends; the end carries a reason (finished, replaced, stopped, failed). Lets automations, say, turn another speaker down meanwhile. Independent design.

### F-EVT-08 `tabula_presence`
Emitted when the presence answer changes, and when a source that had been unavailable comes back. Payload: the new state, the source that caused it (camera motion, camera face, HA entity), and whether the change also dismissed or postponed the screensaver. (independent design)
Open: whether a change caused by several sources at once yields one event or one per source.

## Management from HA (F-MGT)

### F-MGT-01 Per-device settings edited in HA
HA owns every device setting and is where they are edited (options flow now, Tabularium later); the device keeps a cached copy.
- Applied live, without a restart where possible; unchanged values are not re-applied.
- Each write is validated; rejected keys come back with reasons and the valid ones still apply.
- Secrets are write-only in the UI (shown only as set or unset).
- Offline device: runs from its cache and receives the current settings on reconnect.
- There is no settings editing on the tablet. The only device-side edits are on the recovery page (F-MGT-11), limited to connection keys (HA address, credential) and a settings reset.
HA: options flow / Tabularium; `tabula.*` service to set one or more settings.

### F-MGT-02 Shared profiles
- Profile: a named partial set of setting values that many devices can share. Every device belongs to exactly one profile (the built-in empty one unless another is assigned).
- Effective value per key: the profile's value if the profile covers that key, otherwise the device's own.
- What a profile covers: chosen setting groups, minus individually excluded keys, plus toggles for sharing each kind of credential and for sharing the start page.
- Built-in empty profile: covers no keys (for devices that only want coordinated updates); read-only and permanent; usable as a template for a new profile.
- Names are unique. Removing a profile moves its devices to the empty profile.
- On change: pushed to member devices that are online; offline members receive it on reconnect; only values that differ are applied.
HA: Tabularium profile editor; profile assignment as a device setting; `tabula.*` services to assign and to sync now.
Open: what happens when a key the profile covers is edited for a single device (per-device override, refusal, or overwritten on next sync). Recommendation: an explicit per-device override with a visible marker.

### F-MGT-03 Keys never shared by profiles
Always per device; dropped even if a profile includes them:
- identity (name, IDs) and the device's HA pairing credentials;
- hardware choices (audio outputs, rendering fallbacks, UI scale);
- followed media player and speaker allowlist;
- runtime state: alarm list and ringing state, hidden calendars, freeze mode, lockdown on/off, positions of floating elements;
- interface language;
- update installer choices (e.g. use of a privileged helper).
HA: enforced by the integration; these keys are not offered in the profile editor.

### F-MGT-04 Keys left out of new profiles
A new profile starts without keys tied to the physical panel or room; the user can add them back:
- zoom and scale; brightness levels (normal, adaptive range, screensaver brightness, dim level); every volume level; orientation and display-cutout handling;
- which HA entity drives adaptive brightness or motion wake;
- per-device media and photo selections, local file picks, notification sound file, allowed-apps list.
Settings carry file references, never the files. A device missing a referenced file uses that setting's normal missing-file fallback.

### F-MGT-05 Backup and restore
- Single-device export: all settings (secrets included, with a clear warning) plus the dashboard page's stored browser data; the file name carries device and date.
- Full export: every device and profile in one file; an offline device is included from HA's copy.
- Import modes:
  - replace: the target takes the source's name and all its settings (for a replaced or reset tablet, which is a new HA device per F-HA-01);
  - copy: the target keeps its own identity; the page's stored data can be left out so it does not pose as the original.
- Never exported or imported: device private keys and certificates, another device's pairing secrets.
HA: Tabularium actions; optionally `tabula.*` services. HA's own backups cover settings held by the integration.
Open: whether a replace import can also move the old HA device's entity IDs and history onto the new device, or the old entry is simply deleted.

### F-MGT-06 Logs and diagnostics
- HA can pull the app log and the dashboard's JavaScript console output (bounded buffers, size: constant, architect picks), stream both live in Tabularium, and include them in HA's diagnostics download for the device.
- Device health goes into that download: battery and charging (when present), screen size and orientation, memory, storage, CPU load and temperature, WebView package and version, link type and Wi-Fi signal strength (never the network name), app/device/network uptimes, app version.
HA: diagnostics download; live view in Tabularium; items beyond the plan's sensor list are candidate sensors (F-HA-05).

### F-MGT-07 Screenshots
Later phase (plan).
- On-demand capture; the latest one is shown in an HA image entity.
- Screen off -> a placeholder image instead of a black frame.
- Tabularium may re-capture right after a screen-state change (off, screensaver, Now Playing) and caption the image with that state.
HA: image entity (later); `tabula.*` capture service or candidate button.

### F-MGT-08 Device commands as HA services and buttons
Available from HA; buttons only for the plan's settled set (F-HA-08), the rest as `tabula.*` services (some are candidate buttons):
- Navigation: open a URL; open an HA dashboard or view; go to the start page; reload.
- Screen: on, off; set brightness (the normal level, or the adaptive ceiling when adaptive brightness is on; optionally only for the current session).
- Screensaver: start, stop, postpone (restart idle countdown, dismissing it if up), next / previous slide in photo modes (otherwise no-op; the result says whether it moved).
- Overlay page: show a page over the dashboard with a close control, optionally freezing the view while shown; hide it.
- Apps: launch another installed app over Tabula; bring Tabula back; list launchable apps; show / hide the app launcher (refused when the launcher is disabled; limited to allowed apps in kiosk mode).
- Launcher role: report status, take the home role, release it (release works even with no Tabula screen visible, as a recovery lever).
- Speech and notices: speak text; show and dismiss cards (notifications area).
- Media: show / hide Now Playing; open the music library.
- Process: restart the app; reboot only where the OS permits (device owner or privileged helper), with HA told whether reboot is possible and, if not, why.
Each call returns success or failure.
HA: buttons and `tabula.*` services.

### F-MGT-09 Device problems surfaced in HA
Situations needing a person become HA repair issues and clear themselves once resolved:
- an update available, or one waiting for an on-screen tap;
- a feature enabled whose OS permission is missing;
- an incompatible app version;
- the device unable to apply its settings.
HA: repair issues; problem binary sensors as candidate entities.

### F-MGT-10 Trusting HA's certificate
- HA on HTTPS with a private certificate authority: the user installs that CA in Android's settings, and Tabula trusts user-installed CAs for all traffic to HA (dashboard, HA connection, media, downloads).
- The CA list is read when the app starts; a restart is needed after adding one.
- Logging: trusted CAs at startup; every rejected certificate with its host and issuer.
Open: whether to offer any way past a certificate that fails validation (e.g. self-signed without an installed CA): a per-device "accept invalid certificate" setting, pinning the certificate seen at first pairing, or nothing.

### F-MGT-11 Bootstrapping and recovery
- HA unreachable -> keep running on cached settings and keep retrying.
- Before Phase 4, recovery is ADB only: an ADB-only entry point accepts a settings payload (export format) to set the HA address or pairing details on a fresh or broken device; other apps on the tablet cannot reach it.
- Phase 4 recovery page, for someone on the LAN when HA cannot be reached:
  - disabled unless switched on from HA (device setting) or over ADB;
  - protected by a PIN that is set from HA or over ADB;
  - turns itself off after a timeout (constant: architect picks);
  - can change only connection keys (HA URL, credential) or reset settings; nothing else.
- Always available as escape hatches: ADB, and releasing the home launcher role (F-MGT-08).
HA: recovery page enable and PIN are device settings.

## Dashboard optimizations (F-OPT)

### F-OPT-01 Keep the dashboard's HA session alive while hidden
- Problem: HA's frontend closes its live connection a while after the page becomes invisible, and a wall panel is often off or covered.
- Behavior: Tabula keeps the dashboard's frontend connection open whatever the page visibility, re-applied on every page load, so waking shows fresh state with no reconnect wait.
- Can be switched off to get HA's normal behavior.
HA: a device setting (default: architect picks).

### F-OPT-02 Stop drawing the dashboard while it is fully covered
Fully covered by something opaque -> the WebView stops painting (animations, charts, image refreshes); uncovered -> it is shown again immediately.
- Covers that count: any full-overlay screensaver; full-screen overlay pages (an opened external link, an external page in view rotation); a blackout-style lockdown screen.
- Covers that do not count: the dim screensaver and the dashboard-view screensaver (dashboard still visible); overlay pages from the HA server's own origin (treated as part of the dashboard).
- Only painting pauses: page scripts, the HA connection, entity state, timers, alarms, cards and TTS all keep working, so the page is current the moment it is revealed.
- Enabling this forces F-OPT-01 on (a paused page must stay connected).
- Must be possible to switch off for WebViews that redraw wrongly after resuming.
HA: a device setting (default: architect picks).

### F-OPT-03 Release dashboard camera streams while covered
- Trigger: an opaque screensaver over the dashboard, or the panel powered off while a screensaver runs.
- Action: live camera players inside HA's stock camera card are torn down (video players and periodically refreshed snapshots alike) and rebuilt once the trigger ends; video then takes a moment to return.
- Exempt: streams playing audio (sound may be wanted); cards with their own player implementations; the dim screensaver (streams keep running).
- Independent of F-OPT-02. Switching the setting off brings streams back at once, without reloading the page.
HA: a device setting (default: architect picks).

### F-OPT-04 Feed the dashboard only the entities it shows
Tabula determines which entities the visible dashboard view uses and discards state updates for every other entity before the page handles them.
- Recomputed on each view change.
- A view with cards whose entity use cannot be determined reliably (templated or dynamic custom cards) -> no filtering for that view; worst case is the normal unfiltered page, never a broken card.
- A component that walks the whole state table -> the view is treated as reading everything, and a single diagnostic is recorded (call stack captured once per visit to the view, size-bounded, constant: architect picks) so the user can find the culprit. Normal reads never trigger a capture.
- Not applied to custom pages (user-authored pages that are not HA dashboards).
- Visible status: entities tracked, share of updates discarded, the tracked entity list, and a mode of "filtering", "unfiltered (view reads all states)" or "not applicable (custom page)".
HA: a device setting (default: architect picks); filter status and tracked count as a candidate diagnostic sensor, refreshed at a bounded rate (constant: architect picks).
Open: whether filtering intercepts the frontend's subscription inside the WebView or uses another mechanism; the behavior above must hold either way.

### F-OPT-05 Precedence and interaction with other features
- While anything keeps the dashboard visible (dim screensaver, dashboard-view screensaver, Now Playing beside a dashboard-view screensaver), F-OPT-02 and F-OPT-03 are suspended.
- F-OPT-04 ignores covering and keeps filtering behind a cover, so the revealed page is current.

## Self-update (F-UPD)

### F-UPD-01 Update discovery
- When: once soon after app start (delay: constant, architect picks), then periodically (interval: constant, architect picks), and on demand.
- A newer release appears: on the HA update entity (installed, latest, release notes, install action, progress), as an on-screen indicator on the tablet (not shown under lockdown), and on the device's Tabularium page.
- Release notes unavailable -> the update is still offered, without notes.
HA: update entity per device; HA's standard refresh of that entity triggers an immediate check.
Open: where the check runs. Since the device talks only to HA, the integration (not the device) should query the release source and tell each device what is available.

### F-UPD-02 Release channels
A device follows one release channel (e.g. stable, pre-release) and is offered only that channel's releases. A channel whose newest release is older than the installed build offers nothing; there is no downgrade.
HA: a device setting (default: architect picks).
Open: the channel set, and whether the channel is per device or integration-wide. (Independent design.)

### F-UPD-03 Installing
Started from the HA update entity, from Tabularium, or from the on-screen indicator; all run the same steps:
1. Check again for the newest release right before downloading, so the newest is what gets installed.
2. Choose the package matching the device's CPU architecture, trying its supported architectures in order; use a universal package if none matches or the architecture is unknown (a 64-bit CPU with a 32-bit OS gets the 32-bit package).
3. Download, with progress shown on the tablet, on the HA update entity and in Tabularium; cancellable from the tablet and from HA / Tabularium.
4. Verify: it is a Tabula package; it is not older than the installed build; there is enough free space for the installer to stage it, which needs headroom beyond the package size (ratio: constant, architect picks). Refuse with a clear message otherwise.
5. Hand over to the installer path chosen by F-UPD-04.
Nothing installs without a person deciding, unless the keep-current policy of F-UPD-07 is enabled.
HA: install action on the update entity; outcome (installed, cancelled, failed with reason, uncertain) via a `tabula_*` event or an entity attribute.
Open: package transport. With HA as the only peer, the device must get the package through HA (integration-proxied download or an HA-hosted file). Architect decides.

### F-UPD-04 Installer paths and confirmation
Quietest available path first:
1. OS silent install: when the app is device owner, or, on newer Android, once the app is installer of record after one confirmed in-app update (an external sideload resets this; the next in-app update needs one confirmation again).
2. A privileged helper the user has set up, if enabled and authorized. Checked before download and again before install; if unavailable or it fails, report the error; never fall back silently to the confirmation dialog.
3. Otherwise the system confirmation dialog on screen, which someone has to tap.
Confirmation-based installs need the OS "install unknown apps" grant for Tabula. If it is missing, the user is sent to that system toggle (the grant persists once given); it can also be granted over ADB.
HA: the update entity shows when an install is waiting for an on-screen tap, so the person in HA knows to go to the tablet.

### F-UPD-05 Fire OS constraints
On Fire tablets (target Fire OS 7):
- No OS silent path: an ordinary install always needs an on-screen confirmation.
- Device ownership generally cannot be set, because the OS claims a profile owner during initial setup.
- So silent install on Fire OS needs the privileged-helper path; without it, each update needs a tap.
- The unknown-sources grant is in Fire OS's own security settings.

### F-UPD-06 Lockdown during install and relaunch after
- Kiosk lockdown would hide the system confirmation dialog, so lockdown is suspended for the install only and restored on completion, failure or cancel.
- Replacing the package kills the running app; it must restart itself. On Android versions that demand it, self-restart requires the overlay ("draw over other apps") permission, so setup asks for it. If the permission is missing, the new build is installed but stays closed until someone launches it.
- After restart: reconnect to HA; the update entity then shows the new installed version.
- Contact lost after the install was committed -> outcome reported as uncertain; no automatic retry; the user first checks which version is installed.

### F-UPD-07 Coordinated updates from HA
- HA can update a set of devices as one job: sequentially or in bounded parallel batches (constant: architect picks); each device reports its outcome.
- A device that needs an on-screen confirmation prompts on its own display; the job records it as waiting.
- Optional keep-current policy (per device or integration-wide): a device that falls behind gets the install started for it without anyone pressing install.
HA: a `tabula.*` service to update a set of devices; the policy as a setting (default: architect picks).

### F-UPD-08 Version compatibility between app and integration
The integration knows which app versions it can talk to. A device on an incompatible version is flagged (HA repair issue; candidate problem sensor) and offered the update. Settings or profile pushes its version cannot understand are held and applied automatically after it updates.
Open: the compatibility rule (protocol version vs app version) is the architect's.

### F-UPD-09 Installing a supplied package
For devices without a route to the release source: a package file the user supplies (Tabularium, through HA) can be installed.
- The device reads the package's identity and version first and shows what replaces what.
- Refused: non-packages, other apps' packages, builds older than the installed one, packages there is no space to stage.
- Install then follows F-UPD-04.
- A supplied package can go to several devices at once; devices already on that build are skipped.
HA: Tabularium action; outcome as in F-UPD-03.

### F-UPD-10 Manual sideload
Sideloading over ADB always works and keeps all settings and the HA pairing (its effect on silent installs: F-UPD-04).

## JavaScript bridge (F-JSB)

### F-JSB-01 Presence and shape
- `window.tabula` is defined synchronously from document start in the dashboard WebView, so a simple existence check works.
- Plain properties: platform, app version, OS.
- Methods return promises. Failure resolves to `null` for queries and `false` for commands instead of rejecting; the HA-connection methods (F-JSB-08) are the exception.
- Device-to-page signals: DOM custom events on `window`, names prefixed `tabula:`.
Open: which origins receive the bridge (recommendation: HA's origin and explicitly allowed custom pages only).

### F-JSB-02 Device and screen
- Read identity and status: ID, name, model, OS and version, app version, battery, charging.
- Read and set brightness on a normalized scale (range: architect picks), with the same meaning as the HA brightness command (F-MGT-08).
- Turn the screen on or off; read whether it is on.
- Events: screen on, screen off.

### F-JSB-03 Theme
- Read the app's light/dark mode: the setting and what it currently resolves to.
- Read HA's theme as Tabula applies it: setting, current light/dark or "HA decides", whether it is time-scheduled, whether it follows the app.
- One event, with the same shape, fires whenever either changes.

### F-JSB-04 Kiosk state
One snapshot: kiosk mode, lockdown, HA header/sidebar hiding, screensaver active and its kind, screen on, freeze mode, view rotation, and the followed media player (state playing/paused/idle, title, artist, player name, Now Playing shown). Any field change fires an event carrying the full snapshot.

### F-JSB-05 Interaction holds and screensaver
- Interaction hold (distinct from view freeze, F-DSH-17): a page marks the start and end of an interaction, optionally with a reason. While one is open, the screensaver, view rotation and the return-to-start timer are paused. Reason "media" pauses only the screensaver.
- Dismiss the screensaver once.
- Suppress / release the screensaver: shorthand for an interaction hold with no reason.
- Query: whether Tabula owns the screen (so the page should not run its own screensaver).
- Events: screensaver started, screensaver stopped.
Open: what releases a hold when a page navigates away or crashes without releasing it (recommendation: page unload releases its holds, plus a safety timeout, constant: architect picks).

### F-JSB-06 Native sound playback
- A page can have the app play a sound URL natively: avoids WebView autoplay limits and uses the app's network stack, so HA certificates the app trusts work.
- Options: cache (instant replay of fixed assets) or stream while downloading (generated audio such as TTS).
- Loudness always comes from the app's own volume setting; a volume passed by the page is ignored.
- Also: prefetch a URL; stop a sound by ID.
- Events: playback really started (drive UI from this, not from the call resolving); output level for visualizers (best effort, rate-bounded, constant: architect picks; absent where the device cannot measure it); sound ended (exactly once per sound, with an error on failure or after a stop).

### F-JSB-07 Timers
- Read the device's timers, ordered running, paused, ringing; each with ID, name, total, remaining, active, end time, finished.
- Control one: pause, resume, cancel, add or remove time. An empty ID addresses the sole timer.
- After every change an event delivers the full list. It does not tick; pages count down from the end time themselves.

### F-JSB-08 HA connection for custom pages
A custom page (not HA's own dashboard) can use HA's WebSocket API through Tabula without holding a token: one-shot command, subscription (re-sent automatically after reconnect), or a socket object compatible with HA's standard JS WebSocket client library. The page acts as the HA user Tabula is paired as and manages its own message IDs.
- Allowed only if a device setting enables it and the page is a custom page; a page's connections close when it navigates away.
- Refused with a not-allowed error: token creation, auth commands other than reading the current user and signing a path, user and password administration. The page therefore cannot mint its own token.
- These methods reject with a code: not allowed, not configured, auth invalid, unreachable, too many sockets (per-page bound, constant: architect picks), connection lost.
- Events: connection closed (with reason; pending commands reject), connection restored (subscriptions re-sent).
HA: the enable switch is a device setting (default: architect picks).
Open: whether this shares the device's single HA connection or opens a separate one; the single-connection rule suggests multiplexing.

## Deferred and dropped

One-line hooks only. Each is out of scope until a later packet brings it in.

- Bluetooth proxy and its sensors (nearby/connected/max connections): dropped.
- Voice satellite entities, voice-interaction events and speaker-identification events: deferred with the voice satellite.
- Ambient noise sensor: deferred with the microphone (see F-TTS-09 Open).
- Intercom entities, services and events: deferred.
- Camera snapshot, camera views, camera streams and RTSP: deferred (the camera as a presence sensor lives in F-PRS).
- LED control entities: deferred.
- GPS location sensors: deferred (no target hardware has GPS).
- Shell-command service via privileged helper: dropped.
- Remote-admin URL sensor and device-side admin server link: dropped (only the later recovery page exists).
- Local Android broadcasts of the same events to on-device automation apps: deferred.
- Firmware update entity for custom device images: dropped (Fire OS only).
- MQTT or other device-side transports, node naming and generated network identity: not applicable; the device talks to HA only through the `tabula` integration.
- Stopping/snoozing by spoken stop word or by voice request: deferred with voice satellite.
- Setting alarms by voice through an LLM agent: deferred; a later voice feature would call the F-ALM-16 services.
- Hand and finger gestures seen by the camera (to stop or snooze, say): deferred with the camera work.
- Alarm control from a device-side remote-admin page: dropped; HA and Tabularium cover it.
- Speaker-brand-specific media sources for wake-up media: dropped; generic HA media only.
- Local OS broadcast intents for other on-device automation apps: deferred.
- Timers set by voice through an assistant: deferred with voice satellite; would create timers via F-TMR-08.
- Dismissing a timer with a spoken stop word: deferred with voice satellite.
- Peer-to-peer sharing over a device-side server and network discovery: dropped; HA relays.
- Timer page in a device-side remote-admin: dropped; HA/Tabularium cover it.
- Managing the calendar by voice (add, move, rename, cancel, query, open on a day, highlight the touched event, person resolution by voice identity): deferred with voice satellite.
- Calendar page in a device-side remote-admin: dropped; HA/Tabularium cover it.
- Plugin-reserved screen-edge insets for custom pages and plugin menu entries (plugin ecosystem dropped; bridge seam only).
- Echo/Portal/Raspberry Pi device-specific shell behavior.
- Menu entries for intercom, camera view, music library/assistant, voice.
- Swipe-map views for intercom, camera view and music library.
- Plugin overlays and plugin windows interacting with swipes.
- Remote-admin web pages and REST commands for navigation (replaced by HA services and entities).
- Device-owner provisioning and lock-task mode (impossible on Fire; may revisit for other targets).
- Device reboot via device owner or Shizuku.
- Fire OS 8-only items (lock-screen dismissal block, wireless-debugging port churn).
- Microphone, nearby-devices, log-access and all-files permissions (their features are deferred).
- Android 14 Wi-Fi lock downgrade workaround and MediaTek background-killer workaround (not on target hardware).
- Remote-admin recovery path (replaced by HA; later off-by-default recovery page).
- Voice wake-word muting during touch lock (voice dropped).
- Silent home-role acquisition on custom device images (device-owner only).
- Camera hand gestures (finger counting).
- Plugin triggers and plugin-hosted action settings.
- Hardware-button gestures on screenless devices.
- Optional usage analytics and crash reporting: dropped; no telemetry leaves the house.
- Actions for intercom, camera views, music library/assistant, voice satellite.
- Benchmarks and comparisons against other kiosk browsers: not a behavior, dropped.
- Custom release mirror on a user web server: replaced by HA-mediated delivery (F-UPD-03); revisit if needed.
- ADB-started update helper bundled in the app: deferred; Shizuku specifics deferred.
- OS (system) update scheduling for device owners: deferred (not available on Fire OS).
- Fleet leader pushing packages to followers over the LAN: dropped (no device-to-device traffic); HA coordinates (F-UPD-07).
- Platform-specific installer verifier workarounds for non-Fire targets: dropped.
- Sendspin synchronized multi-room protocol and its sync tuning: deferred.
- AirPlay receiver: deferred.
- Direct (non-HA) control of third-party speaker households: dropped; only via HA entities.
- Following another app's Android media session on the device: deferred.
- Lyrics display (needs an external lyrics source): deferred.
- Audio-quality badges (format, bit depth, provider): deferred.
- Music server web overlay with auto sign-in: deferred; use a generic overlay page.
- Voice-driven music requests and blueprints: dropped with voice.
- DLNA renderer: dropped.
- Plugin-provided players and library sources: deferred with plugins.
- Device-hosted web admin, REST API, admin WebSocket, logins and long-lived admin tokens: dropped (management goes through HA).
- Device mDNS hostname and kiosk switcher between device admin pages: dropped.
- Peer-to-peer leader/follower settings sync, invitations and fleet tokens: dropped; replaced by HA profiles (F-MGT-02).
- Device TLS certificate management for its own servers: dropped.
- Device file manager (upload/download files to device storage): deferred.
- Health endpoint readable by any web page without auth: dropped; use HA sensors.
- Syncing plugin packages and plugin secrets via profiles: deferred with plugins.
- TTS playback diagnostics capture: deferred.
- Wake word configuration, wake events and the microphone handoff protocol: dropped with voice.
- Assist pipeline delegation (native mic upload, buffering, levels): dropped with voice.
- Voice-assistant chime durations and voice-timer pill hand-off: dropped with voice.
- Intercom status and microphone-yield events: dropped.
- Plugin APIs on the bridge: deferred; the bridge is the seam they will extend.
- Night-sky / astronomy screensaver: dropped for v1.
- Immich photo source: dropped (possible future plugin).
- Camera-stream screensaver: deferred.
- Live preview of the frames the detector analysed, shown briefly after a face wake as an aiming aid: deferred (see F-PRS-07).
- Proximity and hardware person-sensor wake: deferred.
- Voice wake-word dismissal and voice-timer pills: parked with voice.
