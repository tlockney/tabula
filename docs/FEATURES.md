# Features

Written by the spec side only (packet P0.2), then reviewed (P0.2r). Build-side executors read this file but never edit it; questions it leaves open go to the architect as ADRs.

Each entry has a stable ID (for example F-SCR-03) and describes behavior in our own words, with no constants.

## Conventions

- IDs are `F-<AREA>-<NN>`. They are stable: an entry may be reworded but its ID is never reused for something else. Cite IDs in packets, ADRs and `PROVENANCE.md`.
- "(constant: architect picks)" and "(default: architect picks)" mark every place a number, limit, timing or default is needed. They are chosen in `docs/decisions/0002-constants.md`.
- "HA:" lines say in general terms where a behavior surfaces in Home Assistant (entity platform, `tabula.*` service, `tabula_*` event, device setting). Entity names, payloads and schemas are the architect's to design (`docs/PROTOCOL.md`, `schema/`).
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
| F-NTF | On-screen notifications | 12 |
| F-TTS | Text-to-speech and audio announcements | 11 |
| F-MED | Media playback | 9 |
| F-ALM | Alarms | 19 |
| F-TMR | Timers | 10 |
| F-CAL | Calendar | 11 |
| F-KSK | Kiosk and lockdown | 22 |
| F-LNC | Home launcher | 7 |
| F-GST | Gestures and actions | 11 |
| F-HA | Device presence in Home Assistant | 14 |
| F-EVT | Events reported to HA | 7 |
| F-MGT | Management from HA | 11 |
| F-OPT | Dashboard optimizations | 5 |
| F-UPD | Self-update | 10 |
| F-JSB | JavaScript bridge | 8 |

254 entries in total. Items dropped or deferred by plan section 4 are listed once, as one-line hooks, in the last section.

## Dashboard shell (F-DSH)

### F-DSH-01 Dashboard surface
The app's main screen is a full-screen WebView showing the user's HA frontend. It signs in to HA without the user typing credentials, using what the device obtained when it was paired. The dashboard stays loaded underneath every overlay the app draws (menus, overlay pages, screensavers), so returning to it never requires a reload unless something below says so.
Open: how the WebView session is authenticated (reuse of the pairing credential vs. a separate HA login flow) is the architect's call.

### F-DSH-02 Start target
A device setting names the "start target": the page the app opens on launch, after a crash or restart, and when the user or HA asks to go home. It can be any HA dashboard view, an app panel from the HA sidebar, a built-in HA page that non-admin users can open (energy, history, to-do and similar), or a custom page (F-DSH-13). Admin-only HA pages are never offered. Changing the start target does not move the page currently on screen; it only changes where future "go home" actions land. A start target that includes a URL fragment lands on that fragment (for example a pop-up opened by the fragment).
HA: a select for the start target; a button for "go to start target".

### F-DSH-03 Page catalog for pickers
Every place that lets the user pick a page (start target, rotation list, HA-dashboard screensaver, gesture/link targets, HA navigation select) uses one catalog: dashboard views first, then sidebar app panels, then built-in HA pages, then custom pages. The catalog is refreshed when HA reports a dashboard or panel was created, removed or edited, and when the connection to HA comes back after an outage. It is never refreshed on a timer. The last catalog is cached on the device so pickers and the HA select work right after start even if HA is briefly down. If the catalog actually changes, the HA select's options update; an unchanged catalog causes no churn.

### F-DSH-04 HA unreachable or page fails to load
(independent design; the source is silent on most of this.)
- First load with HA unreachable: show a native status screen (not a browser error page) that says what is wrong (no network, HA not answering, auth rejected) and retries on its own with backoff (constants: architect picks). Retry also happens immediately when the network or the device's HA connection comes back.
- HA drops while the dashboard is already showing: leave the page up and let the HA frontend do its own reconnect; do not reload. If the page is still disconnected after a grace period (constant: architect picks), reload it.
- Auth rejected: show the status screen with a pointer to re-pair; do not loop retries against HA.
- The WebView renderer crashes or stops responding: rebuild the WebView and reload the current target. If rebuilds keep failing in a short window (constant: architect picks), stop looping and show the status screen with a diagnostic message; report the condition to HA.
- Native features that do not need the page (screensavers, notifications, alarms, timers) keep working while the page is down.
HA: a diagnostic sensor or binary_sensor for "dashboard loaded / error", with the error reason.
Open: exact backoff schedule and whether a TLS error on HA's own address may be bypassed by a device setting.

### F-DSH-05 Reload and cache clear
Two distinct actions: "reload" re-fetches the current page keeping the browser cache; "clear web cache" empties the WebView cache and then reloads. Clearing the cache does not log the dashboard out. An optional pull-down-to-refresh gesture performs a cache-clearing reload; it can be turned off for dashboards whose cards need downward drags (maps, sliders), in which case the reload action stays reachable from the menu, a gesture or HA.
HA: buttons for reload and for clear cache.

### F-DSH-06 Dashboard zoom
A device setting scales the dashboard page (below 1 to fit, above 1 to enlarge). It applies live, without a reload. Web-page screensavers carry their own independent zoom.
Open: range and default (constant: architect picks).

### F-DSH-07 Hide HA header and sidebar
A device setting hides the HA frontend's top bar and/or sidebar on HA dashboards so only cards show. It does not affect custom pages or external pages. It can be toggled at runtime from the menu, a gesture, a dashboard link and HA; a hidden gesture can therefore expose HA's navigation temporarily without unlocking the kiosk.
HA: a switch.

### F-DSH-08 Side menu
An on-device menu slides in from the left edge on an edge swipe. It holds shortcuts the user needs at the wall plus the app version and a light/dark theme toggle (the toggle always sits at the bottom). Behavior with kiosk mode on is in F-KSK-03 and F-KSK-06.
- The set of entries and their order are a device setting (picker with check boxes and reordering). Defaults: architect picks.
- Candidate entries (each appears only once its feature exists): go to start target, device settings, hide HA header/sidebar, start screensaver, touch lock (F-KSK-11), view hold (F-DSH-17), app launcher, reload, clear web cache, restart app, exit app, and feature shortcuts added by other areas (alarms, timers, calendar, now playing).
- A checked entry still hides when it cannot do anything here (app launcher with no apps picked; exit app while the app holds the home role, F-LNC-04; a feature not set up).
- "Exit app" and "restart app" ask for confirmation.
- The entry that opens device settings cannot be removed unless another path to settings is guaranteed (see F-KSK-17).
HA: the entry list is a device setting.

### F-DSH-09 Links from the dashboard to app features
The app claims its own URL scheme (name: architect picks). Any dashboard element that opens a URL (button tap action, markdown link, picture-elements icon) can point at `<scheme>://<action>[/<argument>]`; the app intercepts it, runs the named registry action (F-GST-07) and the page does not navigate.
- Links follow the same availability rules as the action (e.g. app launcher refuses if disabled); a refusal shows a short toast explaining why.
- Links are not filtered by the kiosk's restricted-menu allow list (F-KSK-06): a link is a deliberate way to keep a feature reachable while its menu entry is hidden.
- Links never offer exit app, restart app, or device settings; those stay behind confirmation or the exit gesture and PIN.
- A link action that would turn on a camera or microphone runs only on a real user tap, never from page script or HA (relevant once those features exist).

### F-DSH-10 Links that open other apps
A second claimed scheme (name: architect picks) opens another installed app by package name. If the app is not installed, a toast says so. Generic browser intent links (which can name any component with arbitrary extras) are refused.

### F-DSH-11 Overlay page
A web page shown over the dashboard with a visible close control, used by app-feature links, the "open web page" action, and HA. The dashboard keeps running underneath.
- Closes via its close control, back, home, or HA. Closing returns to whatever was beneath.
- When opened from HA, the app first dismisses any screensaver and wakes the screen so the page is seen.
- Touches on it count as activity, but the idle clock keeps running, so on an untouched device the screensaver covers it after the normal timeout. An optional "hold" flag turns view hold (F-DSH-17) on with the page and off when the page closes, unless the hold was already on before, in which case it is left on. The hold's own auto-release still applies.
- A page on HA's own address shares the dashboard's sign-in; other pages do not.
- Only http/https URLs.
HA: a `tabula.*` service to open a URL overlay (with the hold flag) and one to close it.

### F-DSH-12 Custom pages: list
A device setting holds a list of custom pages, each a display name plus either a path on the HA server (resolved against HA's base URL, e.g. files HA serves from its local web folder) or a full URL on another server. Saving validates that the server actually returns the page; an invalid entry is not saved. Entries can be renamed, re-pointed or removed; removal never touches the file. Every custom page appears in all pickers (F-DSH-03).
HA: part of device settings.

### F-DSH-13 Custom pages: behavior
- Moving between an HA dashboard and a custom page, or between two different custom pages, is a full page load. Two custom-page entries that differ only by URL fragment switch by changing the fragment, with no reload, so a single file can hold several layouts.
- Custom pages always load fresh: HTTP cache and HA's service worker are bypassed for them, so an edited file shows on the next load. HA dashboards keep normal caching.
- HA-dashboard-only features (header/sidebar hiding, update filtering, view carousel) do not apply to custom pages; any setting screen for those features says so when the current page is custom.
- The page's light/dark media query answers with the kiosk's current theme and updates live without a reload.
- The `window.tabula` bridge is available on custom pages, including kiosk state (kiosk mode, touch lock, screensaver, view hold, rotation) and change events.
- A server added as a custom page is trusted like HA: links between pages on that server stay in the main surface rather than opening as an overlay, and those pages get the bridge.

### F-DSH-14 Custom pages: shared HA connection
Optional device setting. When on, custom pages may send HA WebSocket commands and subscriptions through the bridge, riding on the device's own authenticated HA access, so the page needs no token. Subscriptions are restored automatically after a reconnect; waiting commands fail with a clear "connection lost" error. The page can do what the device's HA user can, except manage credentials or users (create/list/revoke tokens, change passwords, add users), which are refused. A page's connection ends when the WebView navigates away from that page. Errors are distinguished (not enabled, not configured, HA unreachable, auth invalid, refused).
Open: whether Tabula's single device-to-HA connection can multiplex page traffic, or this needs a scoped credential; the security boundary is the architect's.

### F-DSH-15 Return to start target after idle
A device setting: after no touch for a configured time (default: architect picks; zero disables), the dashboard returns to its start target. Rules:
- The timer keeps running behind a screensaver, so the device can reset overnight without lighting the screen. When the screensaver itself shows an HA dashboard view, the return happens when that screensaver ends instead.
- Paused by view hold (F-DSH-17) and by page-declared interactions (F-DSH-18).
- In the swipe-map navigation style the return goes to the dashboard view of the current slot (F-NAV-13), not the global start target.
- Features with their own "where to land after closing" setting (e.g. now playing, other areas) override it for that one return.

### F-DSH-16 View rotation
A device setting lists pages (catalog entries, F-DSH-03) to cycle through, each shown for a configured dwell (constant/default: architect picks). Rules:
- Any touch (including gestures) pauses rotation; it resumes after idle.
- Paused while a screensaver is up; resumes from its current position afterwards.
- Paused by view hold and page-declared interactions.
- Switching between views of HA dashboards is in-page (no reload); switching to/from custom pages reloads (F-DSH-13).
- While rotation is off, a page HA navigates to stays put (useful for automations that show a view temporarily). Turning rotation on restarts from the first entry.
- Unlike view hold, turning rotation off does not stop the screensaver or screen-off.
HA: a switch to turn rotation on/off; the list is a device setting.
Open: whether external (non-HA, non-custom) URLs may be rotation entries and, if so, whether they show as overlays.

### F-DSH-17 View hold
A mode that freezes the current view: while on, the screensaver does not start, rotation pauses, and return-to-start does not fire. Turning it on dismisses an active screensaver, except a now-playing view the user opened deliberately. It can be toggled from the menu, a gesture, a dashboard link, the bridge, an overlay page's hold flag (F-DSH-11) and HA. An optional auto-release timer (default: architect picks) turns it off by itself. While on, the menu shows a visible notice that the hold is active, with a way to release it.
HA: a switch.

### F-DSH-18 Page-declared interactions (bridge)
Through `window.tabula`, a page can bracket an interaction (start/end, with an optional reason such as voice, announcement, timer, media). While any interaction is open, the screensaver, rotation and return-to-start pause. A media-reason interaction holds off only the screensaver; rotation and return-to-start continue. The bridge also lets a page dismiss the screensaver once, or suppress it until released.
Open: a safety timeout for brackets a page never closes (constant: architect picks).

## Navigation (F-NAV)

### F-NAV-01 Navigate a device from HA
HA can move a device to any catalog page (F-DSH-03). Navigation between HA dashboard views happens inside the running frontend without reloading HA. Navigating dismisses a running screensaver and shows the target. If the device is configured with no HA dashboard loaded at all (F-NAV-14), the request does nothing and leaves the screensaver as it is.
HA: a select listing the catalog (selection navigates now); a `tabula.*` service to navigate to a catalog entry or an arbitrary HA path.

### F-NAV-02 Current page reporting
The device reports the URL currently on screen, including in-app (single-page) navigations inside HA's frontend.
HA: a sensor. The navigation select (F-NAV-01) reflects the current page when it matches a catalog entry; otherwise unknown.

### F-NAV-03 Go home, bring to front, restart
- "Go to start target" navigates to F-DSH-02's target.
- "Bring to front" returns the app to the foreground from another app, landing on the live dashboard (no reload).
- "Restart app" restarts the app process; it comes back on the start target.
HA: buttons for each.

### F-NAV-04 Close overlays
One command closes everything drawn over the dashboard and leaves the page where it is: screensaver (including now playing), overlay pages, the app launcher, and full-screen feature views from other areas (calendar, alarm list, timers). It deliberately does not close: a ringing alarm, active notifications, the on-device settings screen, or anything safety-relevant another area designates. If it closes a screensaver, the screen turns on. In the swipe-map style it also returns to the center view.
HA: a button.

### F-NAV-05 Open another app
The app can open another installed app by package name, from the app launcher, a gesture, a link (F-DSH-10), or HA. The kiosk keeps running behind it, so coming back lands on the live dashboard. If screen pinning is active it is released before launching. A missing package or a launch failure is reported back as an error (a toast on device; a service error in HA). While another app is in front, the screensaver stops counting and an active screensaver closes; the idle clock restarts when the app returns to front.
HA: a `tabula.*` service to launch an app; works whether or not the on-device app launcher is enabled.

### F-NAV-06 App launcher overlay
An overlay of tiles for apps the user picked (list and order are a device setting). Closes via its close control or by tapping outside. Refuses (toast) when disabled or empty. With kiosk mode on, only the allowed subset shows (F-KSK-07).
HA: a button to open it.

### F-NAV-07 Auto-return from other apps
Optional device setting: after an app launched from the kiosk has gone untouched for a configured time (default: architect picks), the kiosk comes back to front.
- It is an idle clock, not a fixed countdown: every touch in the other app restarts it. Touches are detected with an invisible, non-interactive overlay window, which needs the overlay permission. If the other app blocks overlays, touches cannot be seen and the clock runs regardless of use.
- Optional "wait during media and calls" (default: architect picks): while another app plays audio/video or a call is active, the clock is held; when it stops, the full time starts over. Media is detected via Android media sessions (needs notification-listener access); calls via the system audio mode (no permission). Without notification access, only touches and calls hold it.
- Required permissions for this feature show beside its setting (F-KSK-18).

### F-NAV-08 Deep links and system settings
Registry actions can open an arbitrary URI that another app handles, and open Android's system Settings app, with the kiosk staying alive behind. Both are subject to kiosk-mode allow rules when reached from the menu.

### F-NAV-09 Back button
Back first closes the topmost overlay (menu, overlay page, feature view). With nothing open on the dashboard, back navigates back inside the page only while that stays within HA or the current custom page; it never leaves the app while kiosk mode is on. With kiosk mode off and nothing to go back to, the architect decides whether back is a no-op or backgrounds the app.
Open: exact back behavior with kiosk mode off.

### F-NAV-10 Navigation styles
Two styles, chosen by a device setting: the drawer style (side menu F-DSH-08, optional pull-to-refresh, optional horizontal swipe between dashboard views) and the swipe-map style (F-NAV-11..16). Switching to swipe-map disables pull-to-refresh, the dashboard carousel and the edge-swipe menu; their settings are kept (shown greyed with a note) and return unchanged when switching back.
Open: whether swipe-map ships in the first release; the carousel's exact behavior (which views it swipes through) is the architect's design.

### F-NAV-11 Swipe-map layout
Views are arranged in a cross around a center view; each of the four sides holds a bounded number of views (constant: architect picks). Swiping moves one step along a side, toward or away from the center; a swipe across a side (sideways while off-center) does nothing. While dragging, the current view previews its neighbors; releasing past a threshold or flinging commits, a shorter drag snaps back (constants: architect picks). An option replaces the zoom effect with a plain slide. An optional mini-map shows position while swiping.

### F-NAV-12 Swipe-map contents
Allowed views: HA catalog entries (any number of distinct views, each once), a controls view (the menu's entries as controls and cards), and once-only native views from other areas (calendar, alarms, timers, now playing, app launcher). The center can be any of them; by default it is the start target. A view whose prerequisite is not set up is skipped. The controls view is optional, but if the device has no other path to settings (F-KSK-17), it is added on one side automatically.

### F-NAV-13 Swipe-map and the dashboard page
All HA dashboard views in the layout share one WebView; swiping between them switches the view in-page. The swipe shows a still image of the destination until the page has built it, then the live page (timeout: constant, architect picks). If the page navigates on its own (a card link or HA navigation) to a view that is in the layout, the layout's position follows. Return-to-start (F-DSH-15) goes back to the dashboard view of the slot the user is on.

### F-NAV-14 Swipe-map without dashboard views
If the layout holds no HA dashboard view, the HA page is not loaded at all (saving memory). HA navigation does nothing, and the navigation select and current-page sensor read unknown. The page still loads if an HA-dashboard screensaver needs it. Adding a dashboard view at runtime loads it; removing the last one frees it. The dashboard's stored session is kept either way.

### F-NAV-15 Swipe-map gesture conflicts
- A swipe that starts on something that scrolls or drags in the page (slider, map, horizontally scrolling card) stays with the page. On a vertically scrolling page, vertical moves between views only start from the top or bottom edge of the scroll; same for long native lists.
- Swiping is disabled while something not in the layout is on screen (overlay page, screensaver, ringing alarm, camera opened by an event); it resumes when that closes.
- Views placed in the layout ignore their own "close when idle" timers; return-to-center governs them. The same views opened outside the layout keep their own timers.

### F-NAV-16 Swipe-map back, close, idle, kiosk
- Back goes to the center; at the center, back opens the controls view if present.
- A native view's close control goes to the center; the view at the center has no close control.
- Return-to-center after no touch for a configured time (default: architect picks; zero disables), paused by view hold. If it comes due under a screensaver, the device lands on the center when the screensaver ends. The screensaver can start over any view and leaves it intact.
- With kiosk mode on, views whose action is not allowed (F-KSK-06) are skipped; the center and HA dashboard views always show; the controls view appears only if the restricted menu is allowed, holding only allowed actions, with settings behind the exit gesture and PIN.

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
When on, brightness follows room light through a user-shaped curve. The light reading comes from the device's ambient light sensor, or from a chosen HA illuminance sensor instead (useful on devices with no sensor). With an HA source: non-numeric states (unknown, unavailable) are ignored and the screen holds its last level; the last reading is remembered across restarts so start-up brightness is right before HA connects; if HA is unreachable the screen holds its level. Adaptive brightness runs on the device, so it keeps working when HA is down when the device sensor is the source.
HA: switch to turn adaptive brightness on/off; device settings for source and curve.

### F-SCR-06 Brightness curve
The curve maps light level to brightness through a small fixed number of user-adjustable points (constant: architect picks). The light axis is logarithmic. Between points the curve is smooth and monotonic, so a brighter room never gives a dimmer screen. The curve may be inverted (bright in the dark, dim in daylight) by placing the dark-end point above the bright-end point. Inner points are constrained between their neighbours; moving an end point's brightness rescales the inner points proportionally. The settings UI shows the live reading and the brightness it yields. Default curve shape: architect picks.

### F-SCR-07 Brightness commands while adaptive is on
A brightness value written from HA, the management panel or the JS bridge changes the stored base brightness when adaptive brightness is off, and changes the curve's top (bright-room) level when it is on, rescaling the curve. Requests below the curve's bottom level are rejected. To pin a fixed level from HA an automation turns adaptive brightness off first. Brightness changed from the system quick settings affects only the current session and does not change Tabula's settings.
Open: the plan (section 5.5) says a manual change through the light entity turns adaptive off; the architect reconciles that rule with this one.

### F-SCR-08 Smooth adaptive changes
Adaptive brightness changes the panel only when the computed level moves by more than a small threshold, and no more often than a minimum interval, so passing shadows do not cause flicker (constants: architect picks). Changes fade rather than jump.

### F-SCR-09 Brightness while a screensaver is up
A brightness command received during a screensaver updates the stored setting but leaves the screensaver's level alone; the new value applies when the screensaver ends. Screensaver and dim levels scale with the adaptive curve, so a level that is comfortable by day becomes proportionally dimmer at night.

### F-SCR-10 Orientation
A device setting forces landscape, portrait, either reversed, or follows the rotation sensor (falling back to boot orientation on devices without one).

### F-SCR-11 System bars
Device settings hide the status bar and the navigation bar independently; a hidden bar can be revealed briefly with an edge swipe. Kiosk/lockdown mode always hides both regardless of these settings.

### F-SCR-12 Display cutout
A device setting chooses whether the dashboard draws into the area around a camera cutout or stays clear of it.

### F-SCR-13 Show over a secure lock screen
Optional. When on, the dashboard appears above a PIN/pattern/password lock screen so it returns after Tabula powers the panel back on; other apps and system navigation still require unlocking. When off, a secured lock screen is left in front of the dashboard. Unsecured (swipe-only) lock screens are cleared automatically when Tabula wakes the panel, because powering the panel off arms the lock screen even without a PIN.

### F-SCR-14 Panel power off
Tabula can power the panel off (requires the device-admin permission) and back on. Without the permission it logs a warning, keeps the panel on, and offers a black-screen alternative (F-SSV-19) that needs no permission. Some light sensors stop reporting while the panel is off; features that wait for light to rise must not rely on them in that state.

## Screensaver (F-SSV)

### F-SSV-01 Idle screensaver
After a configurable period with no interaction (constant: architect picks the default), the dashboard is covered by a screensaver. A master switch enables the feature; turning it off dismisses any active screensaver. The screensaver never powers the panel off by itself and never stops the app underneath, so the HA connection, notifications and HA commands keep working while it shows. Panel power-off is a separate, explicit rule (F-SSV-19).
HA: switch for the master enable; switch showing and setting "screensaver active"; button to postpone (reset the idle timer).

### F-SSV-02 What counts as activity
Touch resets the idle timer. The idle timer stops while another app is in the foreground or the panel is off, and restarts when Tabula returns; a running screensaver closes when another app takes the foreground. Playing media holds the screensaver off unless the now-playing view is configured to take its place (F-MED). Opening a full-screen Tabula view that is meant to be watched (e.g. calendar, timers) pauses the idle timer while it is open, unless that view has its own idle close.

### F-SSV-03 Starting manually
The screensaver can be started from the side menu (the menu entry can be hidden), a mapped gesture, the HA "screensaver active" switch, a `tabula.*` service, or the JS bridge.

### F-SSV-04 Dismissing
Touch dismisses the screensaver and resets the idle timer, with these exceptions defined by individual modes: edge taps on photo slideshows step slides (F-SSV-12); modes that host interactive content can require a double tap (F-SSV-14, F-SSV-15); the calendar mode closes only through its close button (F-CAL). Other things that dismiss it: turning "screensaver active" off in HA, the postpone button, navigating the dashboard from HA, and enabling view hold (F-DSH-17). External presence sensors can dismiss it (F-SSV-24).

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
The clock can show an image behind the face, from a file on the device or an http(s) URL the device fetches itself. A URL may be refreshed on an interval (off by default, constant: architect picks). Each write of the setting reloads the image even when the value is unchanged, so an automation can force a refresh of the same URL. A failed fetch keeps the previous image and retries later. A darkening layer keeps the clock readable over the image.
HA: text entity for the background source.

### F-SSV-10 Clock night mode
Uses the device's own light sensor (never the HA illuminance source). Below a light threshold the clock switches to a night color scheme (text and widgets in a single dim accent color, dark background); above the threshold plus hysteresis it switches back (constants: architect picks). Options: hide the background image at night, hide widgets and At a Glance at night, and turn the panel off after night mode has lasted a set time, turning it back on when light rises. The night power-off only applies while the clock mode is showing; ringing alarms, now-playing and other foreground apps keep the panel on and restart the countdown. If the device lacks a light sensor, night mode is unavailable. If the device-admin permission is missing, the panel stays on silently at night. A black-screen alternative avoids power-off for sensors that stop reporting while the panel is off.

### F-SSV-11 Weather scene mode
An animated sky that reflects the condition of a chosen HA weather entity (all standard HA weather conditions) and the time of day from HA's sun entity (dawn/dusk/night), with a local-time fallback when the sun entity is missing. Optional overlays: a clock and a weather information bar (temperature, condition, daily high/low when the entity has a daily forecast, humidity, wind, visibility, apparent temperature, an optional place name). With no entity chosen, the mode shows a prompt to pick one. If weather data drops out it keeps the last condition; before any reading it shows a neutral sky. Rendering must stay smooth on weak hardware and pause when the panel is off or the app is backgrounded. A local preview control lets a user try any condition/time of day without HA. Optional: blur behind overlays, lightning toggle.
Open: architect decides how elaborate the animation is for v1.

### F-SSV-12 Photo slideshow modes
Sources: HA media browser (single image, folder of images, video files), a folder on the device, photos picked with the system picker (copied into app storage so they survive reboots and need no broad storage permission), and a single image URL fetched fresh for each slide. Common behaviour:
- Transitions: none, crossfade, slide, zoom, slow pan-and-zoom (stills only), or random per slide.
- Fit: letterbox, smart fill (crop modestly when the aspect ratio is close to the screen's; otherwise show the whole photo over a blurred, darkened copy of itself), or always fill.
- Videos play muted, either whole or cut to the photo interval.
- The playlist is built when the screensaver starts; new files appear next time. Shuffle and include-subfolders options for folder sources.
- Images are decoded at panel resolution and previous slides released promptly (low-RAM devices).
- Optional edge taps: a tap on the left or right edge zone steps back or forward and restarts the slide's interval; taps in the middle dismiss.
- Optional photo details overlay from EXIF (date taken, camera details); no location lookup.
- URL source: the current photo stays until the next has loaded; a failed fetch keeps it and retries later; a small history of recent photos allows stepping back.
HA: buttons for next and previous slide (no-op in other modes).

### F-SSV-13 Website mode
Shows any URL full screen in its own web view sharing the dashboard's cookies, with its own zoom level. If the URL is on the configured HA server, it is authenticated with the dashboard session and shown without HA's header and sidebar; external pages load unchanged. A failed load or server error retries on an interval instead of showing an error page (constant: architect picks).

### F-SSV-14 Website dismissal option
An option makes the website mode require a double tap to dismiss so single taps reach the page.

### F-SSV-15 HA dashboard view mode
Shows a chosen dashboard view as the screensaver by navigating the existing dashboard web view in-page (no reload). On dismissal it returns to the view that was showing before, or to the home view if return-to-home is configured; if HA navigated the dashboard during the screensaver, it stays where HA left it. Header/sidebar hiding can be overridden for this mode only. A tap dismisses without activating the card under the finger, unless double-tap-to-dismiss is on, in which case the dashboard is fully usable and a double tap dismisses. The dashboard keeps rendering in this mode. Requires HA to be connected.

### F-SSV-16 Calendar mode
Shows the calendar view (F-CAL) as the screensaver, with its own choice of calendar view separate from the calendar's default. It stays interactive; only its close button dismisses it. Widgets and At a Glance are not drawn over it.

### F-SSV-17 Screensaver brightness
An optional separate backlight level applies while the screensaver shows (except dim and black, which have their own levels); the previous level returns on dismissal. The pre-screensaver level is persisted so a restart during the night does not adopt the night level as the day level. Two optional temporary brightenings return to the pre-screensaver level: while a notification is visible, and for a short period after a touch that does not dismiss the screensaver (constant: architect picks). Both also apply to dim, black and scheduled levels.
HA: switch for screensaver brightness; number for its level.

### F-SSV-18 Schedule
An optional list of daily time slots, each with a start time and a mode; a slot lasts until the next one starts, and the last wraps past midnight. No day-of-week variation. Each slot may override: brightness (fixed level, or inherit global/adaptive), panel power-off delay (including "never" even when the global delay is set), presence-sensor dismissal, widgets, At a Glance and the now-playing view. Overrides can only turn off overlays that are configured globally, not invent them. Edits apply live, including to a running screensaver. Crossing into a slot with a different power-off delay restarts that countdown.
HA: switch to turn the schedule on or off.

### F-SSV-19 Panel off after a while
An optional delay after which a running screensaver powers the panel off (requires device admin; constant: architect picks the range). Alternatively a plain black screen at minimum brightness can be shown instead of powering off (no permission needed; hides widgets, timers, notifications and now-playing until woken). Touch on that black screen returns to the dashboard.

### F-SSV-20 Wake behaviour after panel off
By default every wake from panel-off returns to the live dashboard: touch, power button, HA turning "screensaver active" off, a `tabula.*` service that stops the screensaver. One exception is fixed: turning the HA screen light on restores the screensaver (not the dashboard) with a fresh power-off countdown, so a morning automation can light the photos and later dismiss to the dashboard. A setting extends that screensaver-preserving wake to chosen wake sources (external presence, power button / any wake Tabula did not cause, touch on the black-screen alternative); for touch, the first touch brings the screensaver back and a second opens the dashboard. Under lockdown mode, presence and power-button wakes always keep the screensaver.

### F-SSV-21 Overlays on top of the screensaver
Notifications, timers and the now-playing view may draw over the screensaver. The screensaver honours them as described in F-NTF, F-TMR and F-MED.

### F-SSV-22 Pixel shift
Optional periodic small shifts of screensaver content to avoid image retention (not applied in black mode).

### F-SSV-23 Fade
Optional fade in and out. Modes that show the live page itself (dim, HA dashboard view) and web content switch without a fade.

### F-SSV-24 Presence from HA
A chosen HA occupancy/motion/presence binary sensor can act as a presence source: when it turns on it dismisses the screensaver; while it stays on it keeps postponing the screensaver; it never dismisses a screensaver that started while it was already on. Options: dismiss only when the panel is off (so a visible slideshow survives people walking by), and postpone-while-present. Presence handling is ignored in lockdown mode.
Open: device sensors (camera motion/face, proximity) are deferred; the architect may keep the presence-source abstraction general so they plug in later.

### F-SSV-25 Interaction with the dashboard
While a screensaver fully covers the dashboard, dashboard rendering may pause (F-OPT). Automatic view rotation pauses and resumes where it was. The return-to-home-view timer keeps running behind the screensaver so the dashboard is reset without lighting the panel.

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
Each entity shows icon, name and value, by default on a contrasting pill. The icon background reflects the entity's active/inactive state the way HA tiles do; text color never changes with state. Options: hide names (larger values), monochrome icons, plain floating text without pills (matching the clock color on the clock mode), scale, font.

### F-AAG-03 Value formatting
HA icon if set, else the domain default. States shown capitalized as HA presents them, numeric states rounded to HA's display precision with unit. An entity with an empty value drops out until it has one; unknown and unavailable still show.

### F-AAG-04 Independent subscription
The row (and the HA-backed widgets) subscribe only to their own entities over the device's own HA connection while the screensaver is showing, and unsubscribe when it closes. They do not depend on the dashboard page, so they work while the page reloads, shows a non-HA site, or filters its own updates (F-OPT).

## On-screen notifications (F-NTF)

### F-NTF-01 Showing a card
HA can put a notification card on the tablet's screen. The card appears near the top of the display above whatever is showing (dashboard, overlay, screensaver) without interrupting it, and leaves on its own after its duration. A card can carry: message (required), title, duration, severity, icon, size factor, sound on/off, sound choice, volume and an image. Every field except the message is optional and falls back to a device setting or default.
HA: `notify` entity for simple text, and a `tabula.*` service for the full field set.

### F-NTF-02 Severity and icon
Severity is one of info, success, warning, error (default info when absent or unrecognised). It sets the card's default icon and accent colour. A Material Design icon name can replace the icon; the colour still follows severity. The icon set is bundled so it works offline.

### F-NTF-03 Text formatting
The message supports a small Markdown subset: bold, italic, bold-italic, inline code, strikethrough, line breaks and paragraphs, headings (all levels look alike, just bolder), bulleted lists with nesting, numbered lists (number shown as written), backslash escapes. Links render as their label only; nothing on a card opens a link. Underscores never cause emphasis (so entity IDs read as typed) and a lone or space-followed asterisk stays literal. Anything outside the subset is shown as typed. The title takes inline styles only.

### F-NTF-04 Size factor
A per-card size factor scales text, icon, padding and width together so a card can be read across a room. Bounded range and default (constant: architect picks); zero or invalid means normal size.

### F-NTF-05 Duration
A per-card duration (default: architect picks). An explicit "no expiry" value keeps the card until tapped or dismissed by HA. Invalid values fall back to the default.

### F-NTF-06 Image
A card can show a picture beneath the text, from an http(s) URL or a path on the HA server (e.g. a camera proxy or a static file); HA-server paths are fetched with the tablet's own HA credentials. The text appears immediately and the image fills in when it arrives. Fetch time and size are bounded (constant: architect picks); on failure the card shows without the image and the error is logged.

### F-NTF-07 Stacking
Cards stack newest on top, each with its own countdown. At most a bounded number are visible (constant: architect picks); a new card beyond that removes the oldest. Identical messages sent twice make two cards (no de-duplication).

### F-NTF-08 Dismissal and identity
- Tapping a card removes that card only. Tap has no other effect.
- Showing a card returns an ID to the caller. Dismissing by ID removes that card; dismissing without an ID clears all cards.
- A "dismiss all" button entity clears all cards, so acknowledging an alert on one tablet can clear it everywhere via automation.
- IDs are per tablet; the same alert sent to several tablets has a different ID on each.
- Every card that leaves the screen fires a `tabula_notification` event (F-EVT-02).
HA: service response carries the ID; button entity; event.
Open: whether cards should support action buttons that fire an event with the chosen action (independent design; nothing in the source behaviour requires it).

### F-NTF-09 Sound
A card can play a chime. The chime plays at the notification volume (a device setting independent of media and assistant volumes; the system master volume still applies) unless the card names its own volume. Turning the chime off for a card makes it silent. A card may name a sound file from the tablet's sound library; a missing file falls back to the default notification sound and the problem is logged.

### F-NTF-10 Sound library and default sound
The tablet keeps a library of user-added sound files in common audio formats, surviving restarts and updates. The default notification sound is a device setting choosing a built-in chime (always available) or a library file. If the chosen file goes missing the setting shows it as missing and the built-in chime plays until it returns. A test control fires a real notification to judge sound and volume.
HA: device settings; sound upload via settings management.

### F-NTF-11 Appearance settings
Device settings control card translucency (how much of the content below shows through; text and icons stay solid) and a background blur behind translucent cards, where the platform can render it. Defaults: architect picks.

### F-NTF-12 Interaction with the screensaver
A card arriving during the screensaver is shown over it; the screensaver stays up. If the screensaver has dimmed the panel and the "brighten for notifications" setting is on, the panel returns to normal brightness while any card is visible, then goes back to the screensaver level when the last card is gone. Only brightness changes; the screensaver content stays. The setting can be turned off (e.g. bedrooms). A tap on a card does not dismiss the screensaver.
Open: whether a card should light a screen that the screensaver has already turned fully off (announcements do, F-TTS-04), and whether a black-screen variant hides cards.

## Text-to-speech and audio announcements (F-TTS)

### F-TTS-01 Speaker as a media player
The tablet's speaker appears in HA as a media player that accepts announcements and HA text-to-speech, and plays media URLs. Its volume is the tablet's media volume. Standard HA TTS calls that target a media player work without any `tabula`-specific setup.
HA: media_player entity; `tabula.*` announce and stop services for the extra options below.

### F-TTS-02 Announcement content
An announcement is either a text message, spoken via an HA text-to-speech engine, or an audio file URL. The tablet asks HA for the audio, then plays it. Speaking text needs the HA connection; if HA cannot produce speech the call reports an error.

### F-TTS-03 Voice choice
Device settings pick the default TTS engine (from HA's TTS entities, or "first available"), language and voice; each announcement may override engine, language and voice. If HA rejects the requested language or voice, the tablet retries with the engine's defaults rather than staying silent. Overrides are ignored when playing a URL.
HA: device settings; service fields.

### F-TTS-04 What the screen does
By default an announcement shows a card with the spoken text while it plays. It does not dismiss the screensaver; like a notification it restores normal brightness while visible, and lights the panel if the screensaver had turned it off. An "audio only" option plays the sound with no card, no screen wake and without bringing the app to the front.

### F-TTS-05 Chime, repeat
An optional chime plays once before the announcement (device default, overridable per call). The chime sound is a built-in chime or a library file (F-NTF-10), falling back to the configured sound and then the built-in one if missing. An announcement can repeat a bounded number of times with a bounded pause between plays (constants: architect picks). The call returns the clip length.

### F-TTS-06 Conflict and stopping
- A new announcement replaces the one playing. If two arrive almost together, the newer plays and the older call reports an error.
- A stop service ends the playing announcement and cancels one still fetching audio; it reports whether anything was stopped and is not an error when nothing was.
- Announcements can be disabled by a device setting; calls are then refused with an error.
- Alarm ringing takes priority; the architect decides whether an announcement during a ringing alarm is refused, queued or mixed. Open.

### F-TTS-07 Volume channels
The tablet keeps separate volume levels, each exposed to HA: media (music, video, media-player announcements), assistant (speech responses and chimes; timer sound), notification (card chimes) and alarm. An announcement may give a one-off volume as a share of the system master volume; without it, it plays at the media volume. The tablet never changes the system master volume on its own.
HA: number entities; media_player volume = media volume.
Open: whether media_player mute maps to a real mute of the media channel.

### F-TTS-08 Ducking
While an announcement plays, other audio the tablet itself is playing (its music player and similar) is lowered to a ducked level and restored to its previous level after the last overlapping announcement ends (ducked level: architect picks). Audio from other apps and sounds the dashboard page plays are not managed.

### F-TTS-09 Adaptive volume
Optional (default: architect picks) automatic volume that follows room loudness: quieter in a quiet room, louder in a noisy one.
- The room level is measured from the microphone as a level only; no audio is stored or sent. It is measured only while the tablet itself is silent, and a window around the tablet's own playback is discarded.
- The volume is chosen when a sound starts and held until it ends; a media stream is re-evaluated only after a pause of some length (constant: architect picks).
- Smoothing: the level rises only after consecutive loud readings, falls slowly, and after a long gap without readings the next reading is taken as-is (constants: architect picks). Until the first reading every channel plays at its own slider.
- If the microphone is unavailable (permission missing, app in the background and muted by the OS) the last level is kept.
- A curve of a small fixed number of points (constant: architect picks) maps room level to a percentage of each channel's slider; points are ordered and volumes may not decrease as the room gets louder; between points the value is interpolated, beyond the ends it is clamped. The sliders and HA volume entities still set the loud-room level.
- Per-channel switches choose which channels follow the room (assistant, media). Alarms, notification chimes, announcements with an explicit volume, and audio from other apps or the dashboard page keep their own volume.
- Test controls play a sample at the volume the room currently gives.
- The curve and per-channel switches are tied to the room, so fleet profiles leave them out by default; the master on/off can travel with profiles.
HA: device settings; optionally a room-noise sensor.
Open: the microphone is otherwise deferred. Architect decides whether adaptive volume ships later with the microphone, or uses an HA noise/sound-level sensor chosen in settings as its room level (independent design).

### F-TTS-10 Playback must always end
Every sound the tablet plays (chime, announcement, TTS) must reach a finished state. A stream whose decoded audio ends mid-frame must still complete. If playback is stuck without progress for too long (constant: architect picks), it is ended, ducking and screen state are restored, and an error is logged and reported to the caller. Silent-but-"playing" states must not hold audio focus indefinitely.

### F-TTS-11 TTS troubleshooting
- The app log records, per sound and tagged with that sound's ID, the fetch from HA (start, headers, completion), decoder chosen, playback start, position advancing and end-of-stream/finish, so a silent or never-ending announcement can be diagnosed from logs alone.
- An opt-in diagnostic capture keeps the raw bytes of the next streamed sound in memory only (bounded size, constant: architect picks; cleared when the app exits; re-arming replaces it). It can be inspected (status, bytes, result), exported, replayed locally with the default or a software-only decoder for comparison, and cleared. Replay needs a complete successful capture and runs one at a time.
HA: diagnostics download and/or a `tabula.*` diagnostic service, not a normal entity.
Open: whether capture/replay is worth building in the first release.

## Media playback (F-MED)

### F-MED-01 The device as an HA media player
Each paired device is an HA media player that plays audio HA sends it: URLs and HA media-source items (resolved through HA), with play, pause, stop, seek (where the stream allows), next/previous where a queue exists, volume and mute. It reports state, title, artist, album, artwork, position and duration. Live streams report no position. Announcements and TTS play over music via ducking (F-MED-09).
HA: the HA media_player entity for the device.

### F-MED-02 Choosing what the on-screen player follows
The on-screen player UI (F-MED-03, F-MED-04) shows and controls one chosen player: either the device's own player (F-MED-01) or any other HA media player entity (the device acts as a remote control and makes no sound for it). The device's own player entity is excluded from the "other player" list.
- Track info, artwork, progress, transport state and seeking reflect the followed entity; controls the entity does not support (per its reported features) are hidden, e.g. no seek handle when seeking is unsupported, no favorite or queue where unavailable.
- The followed player's name is shown on the full-screen view.
- A followed player that reports a TV/line-in style input with no track is treated as idle unless a setting says to show it, so a sound bar under a TV does not hold off the screensaver.
HA: device settings for the followed player (never shared across devices, see F-MGT-05).
Open: whether the device's own player stays available in HA while following another player.

### F-MED-03 Floating mini player
While the followed player has a track playing or a loaded queue, a small card floats over the dashboard with artwork, title, artist (scrolling when long) and a progress line (absent for live streams). A larger variant adds previous / play-pause / next. It can be dragged; its position persists. Swiping it away hides it and, unless a "keep playing" setting is on, also stops playback. A paused card stays visible with a play control until a pause timeout (constant: architect picks) then hides. It hides during higher-priority full-screen interactions and returns afterwards. With nothing playing or queued, it does not appear. While the track changes, the previous artwork is held briefly to avoid flicker.
HA: device settings (show, size, keep-playing on dismiss, pause timeout).

### F-MED-04 Full-screen Now Playing view
A full-screen view of the followed player: large artwork over a blurred version of it, title/artist/album, and optionally transport controls, progress/seek, volume and mute, shuffle, repeat (off/all/one), favorite and a queue panel where the source has them. Layout variants: stacked, side-by-side, and artwork-only (details and controls appear on tap and fade after a constant delay, architect picks). Text and control size are each scalable. Artwork is shown square unless clearly non-square (threshold: constant, architect picks), in which case it keeps its shape.
It opens: automatically when playback starts (setting), from the mini player, from the device menu, a gesture, a navigation layout slot, or an HA command. Dismiss: close control, optional double-tap anywhere (not on controls), the back key, optionally motion/presence. With controls visible, a background tap does not dismiss. When playback stops or a pause timeout elapses, it closes.
HA: `tabula.*` service or button to show/hide it; a device setting set for its behavior; its shown/hidden state in device state (F-JSB-04).

### F-MED-05 Now Playing and the screensaver
- Active playback of the followed player holds off the normal screensaver. View rotation and return-to-home timers keep running unless the hold (pin current view) mode is on.
- Option: Now Playing replaces the screensaver while playing; when the idle timeout fires during playback, Now Playing shows instead.
- Option: shown beside the screensaver: the screensaver keeps part of the screen and Now Playing the rest (split direction follows orientation; screens too small along the split keep full-screen Now Playing, threshold constant: architect picks). The dashboard-view and dim screensavers keep showing the dashboard in their part. In the shared layout, a tap on the screensaver part dismisses both; the player part has no close control; the screensaver's own dismiss rules and brightness (including schedules) apply unless an "use normal brightness" override is set. When playback ends, the screensaver expands to full screen.
- A screensaver schedule entry can override this per time window: follow global, force beside, or suppress Now Playing (playback continues; no auto-launch, brightness kept).
- When Now Playing ends a screensaver session, the dashboard returns to a chosen target: the home view (default behavior), the view shown when the screensaver started, or a fixed view.
- Precedence: ringing alarms and timers, and other higher-priority interrupts, come before Now Playing. If Now Playing is the center of a directional navigation layout, it cannot be dismissed by tap, key or motion (it would reopen).
HA: device settings; per-schedule override lives with screensaver schedules.

### F-MED-06 Hardware volume buttons steer the followed player
Option: the device's volume keys control the followed player instead of the device, either only while Now Playing is up or whenever it plays. Each press moves volume by a configurable step (range: constant, architect picks); mute key mutes it. Lockdown or a "volume keys disabled" setting still wins (keys do nothing). While Now Playing is up, a press briefly reveals its volume slider.
HA: device settings.

### F-MED-07 Speaker grouping
If the followed player supports HA grouping, the Now Playing view offers a group menu: the current lead player, current members, and players that can join, each with a checkbox; changes show pending until HA reports the new group. Where per-member volume is available, each member gets its own slider. A per-device allowlist limits which players the menu offers (members already in the group are always shown so they can be removed). Players with no grouping support show no menu.
HA: uses HA's standard media player join/unjoin; allowlist is a per-device setting.

### F-MED-08 Music library browser
A full-screen browser to find music and play it on the followed player (or another chosen speaker), backed by HA's media browsing for that player.
- Lists what the player's integration offers via HA; search appears when the integration supports it. Image, camera and TTS media sources are omitted.
- Opening items; play (replace queue), play from a chosen track in an album/playlist, play next, add to queue, where supported. Feedback while a play is starting; queue additions confirm with a link to the queue.
- A speaker picker: shows the current speaker and its group with volume; choosing another speaker makes it the followed player (F-MED-02). A play with no speaker chosen first asks for one.
- A bottom player bar mirrors the followed player (controls only where supported); tapping it opens Now Playing, closing that returns to the library.
- Queue panel: current and upcoming items; reorder, remove, jump, clear (with confirmation) where the player exposes a queue; hidden otherwise.
- Opens from the device menu, a dashboard link, a gesture, a button in Now Playing, or HA. Closes after inactivity (constant: architect picks); the screensaver waits while it is open. It respects kiosk-mode allowed actions.
- Per-device options: default source/speaker, hide the source picker, hide the speaker picker, text scale, tile density.
HA: `tabula.*` service/button to open it; device settings.
Open: how queue management works for players without a generic HA queue API.

### F-MED-09 Ducking and stop
During TTS, notifications with sound, alarms, timers and other announcements, the followed player's volume is lowered to a fraction of its current level (constant: architect picks) and restored after the last such interruption ends. For the device's own player this is a local gain change; for another HA player it is a temporary volume command. Changing followed player restores the old one first.

## Alarms (F-ALM)

### F-ALM-01 Alarms live on the device
Alarms are stored and scheduled on the tablet itself, using the OS alarm-clock facility, so they fire with no HA connection, no network and no dashboard loaded. HA can see and control them (F-ALM-15..17) but is never required for one to ring. Each alarm keeps its own data on its own device; fleet profiles carry only the alarm defaults (F-ALM-18), never the alarm list.
HA: alarm list is mirrored to HA for visibility and control.

### F-ALM-02 Alarm list and entry points
A native alarm list shows every alarm with its time, label, repeat days and an on/off toggle that works without opening the alarm. A snoozed alarm shows when it will ring again and offers a stop control. The list can be opened from the side menu, from device settings, from the next-alarm screensaver widget (F-ALM-14), from a touch gesture action, and from the dashboard via the `window.tabula` bridge or a dashboard link. A second entry point opens the details of whichever alarm rings next. While the alarm list or an alarm's details are open, the screensaver does not start. In kiosk/lockdown mode, whether the restricted menu offers alarms is a device setting.
HA: the side-menu entry and lockdown allowance are device settings.

### F-ALM-03 Creating and editing an alarm
The user picks a time on a time picker (12- or 24-hour per F-ALM-19) and saves; the new alarm is enabled and a brief message says how long until it rings. The details view then lets the user set: repeat days, a label, a tone, optional wake-up media (F-ALM-11), sunrise (F-ALM-09), gradual volume (F-ALM-08) and spoken phrase (F-ALM-10). The user can change the time, save, or delete the alarm.
- An alarm is unique by time plus repeat days. Creating one that matches an existing alarm opens and enables the existing one instead of making a duplicate. This rule applies to every creation path (device, HA, settings import).
- Turning on or editing an alarm whose time has already passed today schedules its next occurrence; it never rings late for the missed one.

### F-ALM-04 Repeat and one-shot alarms
An alarm with repeat days rings on each of those weekdays. An alarm with no repeat days rings at the next time the clock reaches its time, then disables itself but stays in the list. Disabling a repeating alarm stops all its future rings until re-enabled.

### F-ALM-05 Ringing
At its time an alarm wakes the screen, comes in front of the dashboard (or any other app) and plays its sound on the OS alarm audio stream, so muted media volume does not silence it. It sets the alarm stream to the configured alarm volume while ringing and restores the previous level afterwards, independent of media and other volumes. The ringing screen shows the time, the label, and two controls: snooze and stop. Any other touch on the ringing screen does nothing, so an accidental touch cannot end an alarm.
- Two alarms due on the same minute ring as one, showing both labels; stopping, snoozing or skipping acts on both.
- Ringing ends by stop, snooze, an HA command, or auto-silence (F-ALM-06).
- In lockdown mode where touch is disabled, the alarm can still be stopped or snoozed from HA.
- Alarm ringing takes precedence over the screensaver, notifications and timer views for the screen; a ringing alarm is never hidden by the screensaver starting (precedence over notifications and timers: independent design).
HA: binary sensor for "alarm ringing".

### F-ALM-06 Snooze, stop and auto-silence
- Snooze silences the alarm for the snooze length (constant: architect picks; user-adjustable within a range the architect picks), then it rings again in the same way. Snoozing can repeat.
- Stop ends the ring and any pending snooze. A one-shot alarm then stays disabled; a repeating one waits for its next day.
- If nobody acts, the alarm goes quiet by itself after a ring limit (constant: architect picks; user-adjustable). This counts as stopped, not snoozed.
- If the device was off or restarting for the whole ring window of an alarm, the alarm is skipped on next boot rather than rung late. If it comes back within the window, it rings for the remainder (independent design).
HA: buttons for stop and snooze; a timestamp sensor for when a snooze ends (unknown when none).

### F-ALM-07 Skip the next ring
Within a lead window before an alarm (constant: architect picks; user-adjustable, including "never"), the clock and weather screensavers show a skip control with the upcoming alarm time. Using it cancels only that one ring: a repeating alarm keeps its later days, a one-shot alarm disables. The screensaver stays up and the control disappears. Alarms sharing the minute are skipped together. Skipping during a sunrise also ends the sunrise. Editing a skipped alarm restores its next ring.
HA: a skip is reported as an alarm event (F-ALM-17). Open: whether HA also gets a "skip next" control (independent design).

### F-ALM-08 Gradual volume
When enabled for an alarm, its sound (tone or media) starts quiet and rises to the alarm volume over a ramp duration (constant: architect picks; user-adjustable, one global setting). Each alarm follows the global on/off default until the user sets its own value; once set, it keeps its own.

### F-ALM-09 Sunrise pre-brightening
A sunrise alarm begins a configurable time before its ring (constant: architect picks; user-adjustable). On the alarm's own full-screen view the screen rises from its lowest to full brightness while a full-screen color moves from a deep warm red to a warm white; the ring then starts on the final color. A touch during the sunrise briefly reveals a stop control; stopping ends the sunrise and cancels that ring. If the alarm is shown on a takeover screensaver (F-ALM-12), the screensaver stays visible and only brightness ramps; a touch that dismisses the screensaver ends the sunrise but the alarm still rings at its time.
HA: sunrise start is reported as an alarm event; "stopped" covers a stop during sunrise.

### F-ALM-10 Spoken phrase
An alarm can speak a phrase between rings: the tone plays a few times, the phrase is spoken, the tone plays again, in a repeating pattern (constant: architect picks pattern counts). The phrase is a template with placeholders for the alarm label, the HA user's display name, the time, and separate date and time parts (hour, minute, weekday, day of month, month, year) so a TTS engine that reads combined values badly can be fed parts. Placeholders that are empty are dropped along with their adjoining punctuation. There is a global default phrase; an alarm that never changed its phrase follows later edits to the default. Speech is generated by HA TTS with the configured engine, language and voice (same mechanism as announcements). The tone starts immediately regardless; if HA is slow or unreachable, the alarm rings without speech, and a name that cannot be fetched in time is omitted. Spoken phrases apply only to tone alarms, not media alarms.
HA: uses HA TTS; engine/language/voice are device settings. Open: placeholder syntax.

### F-ALM-11 Wake-up media
Instead of a tone, an alarm can start media from an HA media source on a chosen player (this device's own player by default, or an HA media player), with options for a random pick and shuffle, and a preview while choosing. A global default media choice applies to alarms set to follow the default; an alarm can opt out to tone only. While ringing, the media plays at alarm volume (with ramp if enabled), repeats until stopped, and is not ducked by the alarm itself. Stop or snooze stops it, removes it from the player queue, and restores the player's prior volume, shuffle and repeat state. If the source or player is unreachable, or playback does not start within a short time (constant: architect picks), the alarm falls back to its tone.
HA: media browsing and playback via HA. Open: whether this ships in the first alarm release or later with media playback.

### F-ALM-12 Ringing over the screensaver
When the active screensaver is the clock or weather screensaver and its "alarms take over" option is on (default: architect picks), an alarm rings on top of that screensaver instead of its own view: the screensaver keeps all its styling and widgets, the alarm label replaces the date line, and snooze/stop controls appear in the screensaver's colors (on the weather variant they replace the weather strip). A screensaver dimmed by its own brightness setting returns to normal brightness while ringing and re-dims after stop or snooze. If something other than such a screensaver is on screen, the alarm starts that screensaver first and rings on it. Otherwise the alarm uses its own full-screen view.

### F-ALM-13 Alarm tone and volume
Each alarm picks a tone: "follow the default", a built-in tone, or a sound from the device's sound library (the same library used for notification sounds; sounds can be added there). Picking a tone previews it once at alarm volume. Alarm volume is a device setting separate from media volume.
HA: alarm volume and default tone are device settings.

### F-ALM-14 Next-alarm screensaver widget
A screensaver corner widget shows the next alarm when one is due within a horizon (constant: architect picks), or the snooze end while snoozed, and is empty otherwise. Tapping it opens the alarm list; this is an exception to "any touch dismisses the screensaver".

### F-ALM-15 Alarm state in HA
The device reports to HA: the time of the next alarm (including alarms scheduled by other apps through the OS alarm clock), whether an alarm is ringing, and when a snooze ends. A morning routine can trigger on ringing turning off.
HA: timestamp sensor (next alarm), binary sensor (ringing), timestamp sensor (snooze until).

### F-ALM-16 Controlling alarms from HA
HA can stop or snooze a ringing alarm, and can list, create, update, enable/disable and delete alarms on a specific device (time, repeat days, label and the other alarm fields). Creation follows the duplicate rule of F-ALM-03. A list call returns each alarm and when it next rings. This is how automations (e.g. set tomorrow's wake-up from a calendar event) and any later voice feature set alarms. Commands sent while the device is offline: Open.
HA: stop/snooze buttons; `tabula.*` services for alarm CRUD and listing (list returns a response). Open: whether a pending command queues for an offline device or fails.

### F-ALM-17 Alarm lifecycle events
Every alarm reports its lifecycle to HA, one event per alarm per change, carrying the device, a stable alarm identifier, label, local time, repeat days and enabled state. Lifecycle points: created (from any source), updated (including enable/disable and a one-shot disabling itself after ringing), deleted, sunrise started, ringing (again after each snooze), snoozed (with when it rings next, in UTC), stopped (during ring, snooze or sunrise), skipped (before ringing), and silenced (rang out unattended). Events that happen offline: Open.
HA: a `tabula_*` alarm event. Open: whether events raised while disconnected are replayed on reconnect.

### F-ALM-18 Alarm settings
Device settings (owned by HA, cached on device): 12/24-hour display for all alarm surfaces (first run takes the OS clock format, then independent), alarm volume, default tone, default wake-up media, gradual-volume default and ramp time, snooze length, auto-silence limit, sunrise length, skip lead window, default phrase, TTS engine/language/voice, side-menu visibility. Fleet profiles sync these defaults but not the alarms.
HA: device settings; fleet profile category for alarms.

### F-ALM-19 Time format
The 12/24-hour setting governs every place an alarm time appears: list, picker, ringing screen, widget, HA-side editors in Tabularium, and the time/hour placeholders in the spoken phrase.

## Timers (F-TMR)

### F-TMR-01 Timer model
A timer has an optional name, a start length, time remaining, and a state: running, paused, finished (alerting) or gone. A device owns each timer it starts. HA knows every timer on every device. Timers can be started by hand on the device or from HA; a later voice feature may also create them. Unnamed timers are identified by their start length; two timers with the same name on one device are ambiguous to name-based commands.
Open: whether the device or HA is the source of truth for a running timer, and how a timer behaves while the device is disconnected from HA (Tabula requirement: a running timer must still count down and alert locally).

### F-TMR-02 Setting and changing by hand
In the timers view, a "new timer" control opens a duration picker (hours, minutes, seconds) and an optional name; starting it creates a running timer. Tapping a timer's remaining time lets the user set a new remaining time or delete it. A timer's name cannot be changed after creation. Each timer also has pause/resume and cancel controls.
HA: the same changes are made through `tabula.*` services (F-TMR-08).

### F-TMR-03 Full-screen timers view
A native full-screen view lists timers. The one finishing first is shown large with name, remaining time, a draining progress bar, and pause and cancel. Others are listed beneath (or beside it on wide, short screens) with the same information; the list scrolls when long. A close control hides the view until the next trigger (a timer starts, nears its end or finishes). The view closes on its own once no timers remain. It can be opened from the side menu, settings, a gesture action, a dashboard link / `window.tabula` call, or as a page of the swipe navigation layout. Whether lockdown mode allows it is a device setting.

### F-TMR-04 When the view appears; screensaver interaction
- Timer starts: the view opens and dismisses the screensaver if "show on start" is on (default: architect picks).
- Timer nears its end: when a running timer reaches a lead time (constant: architect picks; user-adjustable), the view opens again if it was closed or covered, once per timer; the screensaver is dismissed and held off.
- Timer finishes: the view opens and the screensaver is dismissed and held off at full brightness.
- Merely running does not block the screensaver, unless "hold screensaver while a timer runs" is on (default: architect picks), which blocks it only while the view is in front showing a running (not paused) timer.
- After the view is closed or the timer is gone, the screensaver may start again on its normal idle timeout.
- A ringing alarm (F-ALM-05) takes precedence over the timers view.

### F-TMR-05 Finished timer alert
A finished timer moves to the top in an alert color with a "time's up" state and a dismiss control. Its sound repeats until dismissed. Settings: alert sound (built-in or from the device sound library), alert volume, mute alerts (show without sound), and two optional unattended limits (each "never" or a duration; ranges: architect picks):
- auto-silence: stops the sound but keeps the finished timer on screen until dismissed; media ducked or paused for the alert resumes.
- auto-dismiss: dismisses it exactly as a tap would, closing the alert and reporting dismissal to HA.
Both limits count from the most recent timer to finish, so a second timer finishing while the first is silenced rings again. Optionally HA TTS speaks a phrase between sounds, with a configurable phrase for unnamed and for named timers (the name inserted); engine/language/voice are settings, falling back to HA's first available engine.
HA: mute is an HA switch; other alert settings are device settings.

### F-TMR-06 Floating timer badges (alternative display)
As an alternative to the full-screen view, timers can display as small draggable floating badges over whatever is on screen, with options for showing the name, size, and whether finished timers show a badge and name. Tap pauses/resumes, double-tap cancels. In this mode the view opens only on explicit request, and the view-on-start / near-end / hold-screensaver settings do not apply.

### F-TMR-07 Shared timers across devices
A device can be configured to share its timers with a chosen set of other Tabula devices; each device has its own share list, and each device has a setting to accept or refuse shared timers. Sharing goes through HA as the directory and relay; devices never talk to each other directly. On receiving devices, shared timers appear in the view or as badges labelled with the owning device's name, with countdown and alert kept in step with the owner. Pause, cancel, change and dismiss work from any receiving device and are applied by the owner, then reflected everywhere. Mute, auto-silence, speech and badge settings are per receiving device; auto-dismiss follows the owner, and an owner dismissal closes the alert everywhere. A shared timer whose owner stops reporting is dropped after a staleness limit (constant: architect picks). HA-side identity (entities, events, service targets) stays with the owning device.
HA: share list and accept setting are device settings. Open: how the integration relays and how stale detection works.

### F-TMR-08 Timers in HA
Per device: a sensor counting running or paused timers, a timestamp sensor for when the soonest running timer ends, and the mute switch. Services to start, list (returns a response), pause, resume, cancel, and add or remove time. An event per timer on start, change, cancel, finish and dismissal.
HA: sensors, switch, `tabula.*` timer services, a `tabula_*` timer event.

### F-TMR-09 Disabling timers
A device setting turns timers off entirely: the view, badges and sharing (both directions) disappear, menu entries, gesture actions, links and the swipe-layout page are hidden or skipped (a layout centered on timers falls back to the start dashboard), and the device's timer entities and services are removed from HA. Turning it off cancels running timers and stops a ringing one. Other timer settings are retained for when it is turned back on. Alarms are unaffected.

### F-TMR-10 Timer settings
Device settings: enable timers, show view on start, near-end lead time, hold screensaver while running, side-menu visibility, sharing (share, share list, accept shared), alert sound/volume/mute/speech/phrases/TTS, auto-silence, auto-dismiss, badge mode and badge options. Fleet profiles sync these except the share list and alert sound, which stay per device.

## Calendar (F-CAL)

### F-CAL-01 Family calendar from HA calendars
A native calendar view shows events from a chosen list of HA calendar entities (any HA calendar integration). Each list entry has a display name (initially the entity's name), a color (from a fixed palette with automatic next-free assignment, or a custom color), and an optional HA person whose picture replaces the name initial in avatars and chips. Entries can be edited, removed, reordered (order matters, F-CAL-05) and individually disabled without removal.
HA: calendar list is a device setting, synced by fleet profiles.

### F-CAL-02 Opening and closing
Opened from the side menu, settings, the "up next" screensaver widget, a gesture action, a dashboard link / `window.tabula` call, or from HA (a control that opens the calendar in a chosen view). It closes back to the dashboard after an idle period without touch (constant: architect picks; user-adjustable, including never), and also when the screensaver starts. In the swipe navigation layout, the layout's own return rule applies instead and the calendar may remain under the screensaver. Lockdown-mode allowance is a device setting.
HA: an HA select (or equivalent) to open a view; idle close is a device setting.

### F-CAL-03 Views
Four views; the opening view is a setting (default: architect picks). Controls go to today and step back/forward by the view's unit; horizontal swipe does the same.
- Day: one lane per calendar with avatar and name, an all-day strip, then an hour grid with a current-time line; overlapping events in a lane sit side by side.
- Week: seven day columns, all-day items first, today highlighted; tapping a day header opens that day.
- Month: as many events per day as fit, then an overflow count; tapping a day opens it.
- Agenda: today's date, weather, next event and remaining events on one side; upcoming days over a window (constant: architect picks) on the other.
On narrow screens chips collapse to avatars and scroll sideways; on phone-sized screens week and month collapse to a day strip / dot grid above the selected day's events. Week start day (or locale default) and 12/24-hour time are settings (first run takes the OS clock format).

### F-CAL-04 Per-device calendar filtering
A chip per calendar in the header toggles that calendar's visibility on this device only. Hidden calendars stay hidden until toggled back, and are also left out of the up-next widget. This choice is per device and is not synced by fleet profiles.

### F-CAL-05 Shared events
An event present on several listed calendars is drawn once, its background split diagonally into one band per calendar in list order. Two copies are the same event when HA reports the same event identifier; otherwise when title, start and end all match. If some of its calendars are hidden, it still shows for the visible ones, without the hidden calendars' colors. The detail view lists every calendar it belongs to.
Open: matching rule when identifiers differ but timing matches imprecisely (independent design).

### F-CAL-06 Event details
Tapping an event shows who it belongs to (calendars/people), date and time, recurrence description, location and description. With editing enabled (F-CAL-07), edit and delete controls appear.

### F-CAL-07 Editing (opt-in)
Read-only unless enabled. Two independent settings: allow add/edit, and allow delete (defaults: architect picks). With add/edit: a new-event control, tap-to-place in a day lane, and edit in details. The form covers title, target calendars (several = one shared event; changing the set adds to new and removes from cleared), all-day, start, end, recurrence (common simple rules; an existing complex rule is kept as an option), location and notes. Saving a change to a recurring event asks "this one" or "this and following"; deleting asks "this one", "this and following" or "all", after a confirmation. A shared event is deleted from all its calendars. Calendars whose HA integration cannot accept changes are excluded from creation and their events show no edit/delete. The calendar screensaver is always read-only.
HA: writes go through HA calendar services.

### F-CAL-08 Weather on the calendar
An optional HA weather entity adds a daily forecast to week and agenda days and the phone day heading. The forecast is fetched only while the calendar is open.

### F-CAL-09 Data freshness and offline
Calendars are read from HA only while something visible needs them (the open calendar, or the up-next widget while shown). Updates arrive live where HA supports subscribing to calendar changes, otherwise by polling at an interval (constant: architect picks) and on navigation. Recurring events arrive already expanded from HA. If HA is unreachable, the last-loaded events stay visible with a "last updated" indication. Non-admin HA users suffice for viewing and editing.

### F-CAL-10 Calendar as a screensaver; up-next widget
The calendar can be selected as a screensaver mode (read-only, no idle close); see the screensavers area. An "up next" screensaver widget shows upcoming events from visible calendars and opens the calendar when tapped.

### F-CAL-11 Calendar settings
Device settings: calendar list (name, color, person, enabled, order), default view, week start, 12/24-hour, weather entity, allow add/edit, allow delete, idle close, side-menu visibility. Fleet profiles carry all of these except per-device hidden chips.

## Kiosk and lockdown (F-KSK)

### F-KSK-01 Two layers of protection
- Kiosk mode: persistent, survives reboot, stays on until turned off; a configurable set of protections.
- Touch lock: a temporary state on top that makes the screen untouchable (F-KSK-11). Ending it returns exactly to the previous protection state.
Everything is limited to what a non-device-owner app can do on Android/Fire OS. Fire tablets cannot be device owners (F-KSK-21), so on the first target lockdown is: start on boot + screen pinning + overlay-based reclaim + optional accessibility guard.
HA: a switch for kiosk mode, a switch for touch lock. Example use: lock at bedtime, unlock in the morning.

### F-KSK-02 Kiosk mode master switch
Turning kiosk mode on: the edge-swipe menu is replaced by the exit gesture (F-KSK-03); back can no longer leave the app; each enabled protection (F-KSK-05) is armed.

### F-KSK-03 Exit gesture
A run of rapid taps anywhere on screen (tap count chosen from a small fixed set: constant, architect picks), optionally with the final tap held down. It opens the full menu (after the PIN if set). It can be disabled entirely, in which case settings are reachable only from HA (see F-KSK-17 for the offline risk). The exit gesture counts taps regardless of position and works alongside custom gestures (F-GST-05).

### F-KSK-04 Kiosk PIN
Optional. After a correct exit gesture, a PIN prompt must be passed before the menu opens. The PIN is a device setting owned by HA and cached on the device, so it works offline. Wrong entries simply fail.
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
Optional. With kiosk mode on, a reduced menu can still be opened by the normal edge swipe (or the controls view in swipe-map style), showing only actions the user explicitly allowed. Device settings, clear web cache, restart app/device and exit app never appear there. The full menu stays behind the exit gesture and PIN. Which entries the menu offers at all, and their order, come from F-DSH-08; the allow list filters further. A touch-lock entry can be allowed here so anyone can lock the screen before cleaning it.

### F-KSK-07 Allowed apps
When the app launcher is an allowed action, a second list chooses which of the launcher's apps are reachable with kiosk mode on. While kiosk mode is on, the launcher shows only those, however it is opened (menu, gesture, link, HA). Apps added to the launcher later default to not allowed. Order follows the launcher's order.

### F-KSK-08 Screen pinning and staying in front
- Pinning blocks home and recents. On a non-managed device, Android asks for consent the first time ("got it" once; some devices ask again every boot). Holding the home role (F-LNC) makes pinning unnecessary.
- Kiosk mode does not depend on that consent: if pinning is declined or lost, the app brings itself back to front shortly after losing focus (constant: architect picks). Swiping the app out of recents causes an immediate relaunch.
- Exceptions where the app does not pull itself back: an app launched through the app launcher/gesture/HA (auto-return F-NAV-07 governs that), and Android permission screens the app itself opened.
- Needs the overlay permission.

### F-KSK-09 Persistent service and self-heal
A foreground service runs on every install, regardless of features. It keeps the HA connection alive while the screen is off or another app is in front, relaunches the app after a crash or after it is swiped from recents, and carries an ongoing notification whose text says what it is doing. While the screen is off it holds a Wi-Fi lock and, optionally (device setting; default: architect picks), a CPU wake lock so keepalives fire on time; turning the CPU lock off is meant for battery-only devices. Relaunching needs the overlay permission. On Fire OS, where the system restarts a dead service slowly, a periodic watchdog alarm (interval: architect picks) also checks and relaunches.
HA: an "app uptime"/last-start diagnostic so restarts are visible.

### F-KSK-10 Start on boot
Device setting. The app launches itself after the device boots. Needs the overlay permission on recent Android versions; the requirement is shown beside the setting. Redundant (and stands down) while the app holds the home role.

### F-KSK-11 Touch lock
A temporary mode in which the dashboard stays visible and live but cannot be touched: every touch lands on a full-screen shield and briefly shows a "screen locked" notice. While it holds:
- All kiosk protections are armed at runtime without changing the stored kiosk settings; ending it restores the previous state exactly.
- All gestures (F-GST) are silenced, and so are bridge-triggered actions.
- The screensaver is suppressed unless a setting allows it; if allowed, wake-on-presence/motion style dismissals are disabled for the duration (so passers-by do not wake it), and touch, power-button and double-tap wakes leave the screensaver up.
HA: a switch.

### F-KSK-12 Starting and ending touch lock
- Start: from HA, from an optional menu entry (default: architect picks), or via the restricted menu if allowed there.
- End: HA, or the touch-lock exit gesture followed by the PIN if set. The touch-lock exit gesture is a separate setting with the same options as F-KSK-03. Only one exit gesture is armed at a time (touch lock's replaces kiosk's while it holds), so both may use the same tap count. If its exit gesture is disabled, only HA can end it.
- Optional auto-unlock after a configured time (default: architect picks; zero = stay locked) applies only to locks started from the on-device menu (the screen-cleaning case). Locks started from HA hold until HA ends them. The exit gesture can end either early.

### F-KSK-13 Touch lock blackout
Optional: the shield is solid black and dashboard rendering pauses underneath to save power. The panel stays powered and the device stays fully connected and controllable from HA.

### F-KSK-14 Shield is system-level
The shield is a system overlay window covering the whole display, so it stays effective if another app comes forward. Without the overlay permission it falls back to covering only the app's own window; the permission status shows as missing (F-KSK-18).

### F-KSK-15 Shade guard (optional accessibility service)
An optional accessibility service that, only while touch lock or matching kiosk protections are active, collapses the notification shade the moment it opens and bounces the recents screen. Otherwise it is idle. It observes window-change events only and declares that it cannot read screen content. Android requires a person at the device (or ADB) to enable it.

### F-KSK-16 Known gaps without device ownership
Without device-owner lock-task mode, some system surfaces cannot be fully claimed: the navigation bar may flash before hiding; a determined swipe can show the shade for an instant before the guard closes it; pinning needs consent at least once; the app cannot reboot the device. The settings screen states these limits so users are not surprised.

### F-KSK-17 Recovery and the offline case
Kiosk mode and touch lock can always be turned off from HA, and since settings (including the PIN and exit gesture) are owned by HA, a forgotten PIN or disabled exit gesture is fixed from HA. Risk: HA unreachable + exit gesture disabled leaves no way in on the device.
Open: whether the app refuses to disable the exit gesture unless an alternative path exists, re-enables it after HA has been unreachable for a period (constant: architect picks), or relies on the later off-by-default recovery page.

### F-KSK-18 Permission status
The app keeps a master list of every special permission it uses, each with a one-line purpose and a status: granted; missing (an enabled feature needs it); or not granted (nothing enabled needs it, can be granted ahead of time). Each feature's settings also show just the permissions that feature needs, next to the feature's switch. Each row has a button that opens the right Android dialog or settings screen on the device; the request can be started from HA, but a person must accept on the device. The list notes that vendor battery managers outside Android's view may still kill the app. ADB provisioning commands are documented for setting up from a computer.
HA: a diagnostic entity (or HA repair issue) listing missing permissions; a `tabula.*` service or button to open a given permission's grant screen on the device.

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
Camera and microphone grants belong to deferred features.

### F-KSK-20 Foreground-app reporting
The device reports the package currently in front (needs usage access; without it, only reports the kiosk when it is in front).
HA: a sensor.

### F-KSK-21 Fire OS 7 (Fire HD 10 Plus) specifics
- No home role: Fire OS forbids replacing its launcher (F-LNC-03). Lockdown relies on start on boot + kiosk protections.
- No device owner: Fire's parental-controls component already holds the profile-owner role, so device-owner provisioning always fails. No lock-task mode, no silent self-update, no device reboot by the app; self-update needs one confirmation tap (see self-update area).
- Lock screen: turning the panel off via device admin arms Fire's swipe lock screen; on Fire OS 7 the app can dismiss an unsecured lock screen when it wakes the panel, so wake lands on the kiosk directly.
- The system restarts a dead service slowly; the watchdog alarm (F-KSK-09) compensates.
- The system WebView can crash the whole process in its audio path; self-heal relaunch covers it.
- The system WebView lacks some real-time media support; features using it must fall back (relevant to deferred camera features).
- Setup: sideload over ADB; grants for overlay, write settings, usage access, battery exemption and device admin can be applied by ADB in one sitting; accessibility guard optionally by ADB.

### F-KSK-22 Kiosk state on the bridge
`window.tabula` exposes kiosk mode, touch lock, header/sidebar hiding, screensaver state, view hold and rotation state, with a change event whenever any of them moves.

## Home launcher (F-LNC)

### F-LNC-01 Act as the home screen
Device setting (master switch) that asks the OS to make Tabula the default home app. A live status line shows whether the role is held, awaiting the user's confirmation, or blocked, and where to finish if the OS needs the user to act. A second option keeps screen pinning on even while holding the role (blocks recents natively, but brings back the pin consent prompt on non-managed devices).

### F-LNC-02 Acquisition per OS
- Recent Android: the system role dialog is shown; if the OS stops showing it after repeated denials, the status line opens the system's default-home settings instead.
- Older Android versions: the system default-home settings open for the user to choose.
- Fire OS: not supported (F-LNC-03).

### F-LNC-03 Fire OS
Fire OS silently denies every attempt to replace its launcher (and even ADB role grants do not change the home button), so on Fire tablets the switch is not offered; the page explains why and points to start on boot and kiosk protections. On the first target this whole area is therefore dormant.

### F-LNC-04 While the role is held
- The device boots straight into the app; start on boot stands down.
- Home always returns to the app; kiosk-mode pinning is skipped (unless the keep-pinning option is on) since the role already covers home.
- Pressing home while the app is already in front closes the menu, settings, app launcher, overlays and screensaver and lands on the dashboard.
- "Exit app" is hidden (the OS would relaunch it); "restart app" stays.

### F-LNC-05 Zero footprint when off
With the switch off, the app does not appear as a home candidate anywhere (launcher chooser, default-apps list).

### F-LNC-06 Getting out
Releasing never depends on app state: turning the switch off (on device or from HA) hands home back to the remaining launcher immediately; a release command from HA works even if the app's UI is unresponsive, as long as the process runs; uninstalling resolves it automatically.
HA: a switch for the role, or a release button plus a status sensor.

### F-LNC-07 Crash fuse
If the app starts several times in a row without ever drawing its first frame, within a short window (count and window: constants, architect picks), it gives the home role back to the stock launcher automatically. This check runs at the earliest point of process start so it fires even if the rest of the app is broken. Afterwards the switch reads off and the status line and log say why; turning it back on re-arms it. A healthy start resets the count, so ordinary restarts, updates and reboots never trip it.

## Gestures and actions (F-GST)

### F-GST-01 Gesture bindings
A device setting holds a list of bindings, each one gesture shape → one action. Bindings work whenever the app is running, regardless of kiosk mode (except as silenced by F-GST-06). Adding a binding asks for the shape, then the action, then only the parameters that action needs. The list is part of the settings synced across a fleet (except bindings to device-local plugins, deferred).

### F-GST-02 Touch gesture shapes
- Corner taps: a quick run of taps entirely inside one of the four corners (tap counts: small fixed set, constant, architect picks).
- Corner hold: press and hold a corner for a configured duration (range: constant, architect picks).
- Multi-finger tap: single or double tap with more than one finger anywhere (finger counts: architect picks).
- Multi-finger hold: several fingers held anywhere.
- Corner sequence: an ordered sequence of corner taps, like a knock code; the most discreet option for sensitive actions.
Corner hit areas are a fixed physical size (constant: architect picks), not a fraction of the screen.

### F-GST-03 Observe, never intercept
Touches used for gestures still reach the dashboard underneath. Shapes are chosen (corners, extra fingers, holds) so they do not collide with ordinary card taps and swipes. Long holds in the page can still select text or open context menus unless that is suppressed (F-KSK-05).

### F-GST-04 Timing and disambiguation
Taps chain only while each follows the last within a short window; a pause too long or a tap outside the corner resets the run. Corner sequences allow a slower rhythm. A hold fires while the finger is still down, at the configured duration. When two bindings share a corner with different tap counts, the shorter one fires only after a brief wait confirms no further tap is coming. (All windows: constants, architect picks.)

### F-GST-05 Coexistence with other touch features
- The kiosk exit gesture's rapid-tap counter is position-blind and unaffected by bindings; its hold variant (single finger anywhere after a tap run) cannot collide with corner holds or multi-finger holds.
- A gesture counts as a touch: it resets the idle timer and pauses rotation.
- The first tap on a screensaver both dismisses it and counts toward a gesture, so a corner double-tap on a sleeping screen wakes it and fires the action.

### F-GST-06 Silencing
With kiosk mode on and its "disable gestures" protection selected, all bindings are dormant until kiosk mode is off. Touch lock silences every binding and also actions requested through the bridge. Actions run from the HA side are never silenced by either.

### F-GST-07 Action registry
A single registry of named actions, each with typed parameters, used by gestures, menu entries, dashboard links (F-DSH-09), HA services/buttons, and the `window.tabula` bridge. Every caller gets the same behavior and availability rules (an action whose feature is disabled refuses with a toast stating why). In-scope actions:
- App: go to a catalog page; open a web page as overlay; reload; start screensaver; stop screensaver; toggle view hold; toggle HA header/sidebar hiding; open app launcher; start/stop touch lock.
- Android: open an app by package; open a URI handled by another app; open system Settings.
- HA: call a service (domain, service, optional entity and data); run a script; trigger an automation; fire an event (type, optional data).
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

## Device presence in Home Assistant (F-HA)

### F-HA-01 One device per tablet
Each paired tablet appears in HA as a single device that owns all of its entities and services. Entity IDs and history stay attached to that device across app restarts, app updates and a rename of the tablet; a rename changes display names only, never what automations target.
HA: one device in the `tabula` integration per tablet.
Open: whether a factory reset or reinstall re-pairs as the same device or a new one.

### F-HA-02 Entities follow capability
An entity exists only when the tablet can back it: no battery entity on a tablet without a battery, no light-sensor entity without a light sensor, no restart-device button when the app lacks the privilege to reboot. Feature-dependent entities (for example media transport, calendar view) appear once the feature is set up and disappear when it is turned off. Nothing is shown as a permanently dead entity.
HA: entity set is reported by the device and updated in place.

### F-HA-03 Hiding entities
A device setting lists every entity the tablet can offer and lets the user exclude any of them from HA. All are offered unless excluded. Changing the list updates the HA entity set without the user re-pairing.
HA: device setting.

### F-HA-04 Connection loss
When the tablet's connection to HA drops, every entity of that device goes unavailable rather than keeping stale values or reading "off". When the connection returns, all entities refresh to current device state.
HA: all `tabula` entities of the device.
Open: whether the connectivity sensor (F-HA-09) reads "off" (it lives in HA and can) or goes unavailable with the rest.

### F-HA-05 Diagnostic sensors
Read-only entities in HA's diagnostic category:
- Battery level (only with a battery), battery temperature (only where reported), charging/power connected.
- CPU load (where the OS lets an app measure it), CPU clock, CPU temperature (where reported).
- Memory available and total; internal storage free and total (refreshed periodically, constant: architect picks; a full disk reports zero, not unknown).
- Device model, OS version and build, Tabula app version.
- Current page: the URL showing in the dashboard, tracking in-page (single-page-app) navigation, not just full loads.
- Foreground app: which app is in front (Tabula or another).
- Panel brightness: the brightness the panel is actually at, read back from the device, not the requested value.
- Network addresses (IPv4, IPv6) of the active Wi-Fi/Ethernet link, preferring a routable IPv6 over link-local, and still correct while a VPN is up; plus a per-interface breakdown so automations can tell wired from wireless. Re-read shortly after any network change.
- App start time and network-up time (timestamps).
- Last seen: time of the last successful exchange with HA.
HA: sensor / binary_sensor entities, diagnostic category.

### F-HA-06 Ambient light sensor
Where the tablet has a light sensor, its reading is reported. A genuine change is sent at once; a reading that keeps flapping is throttled to at most one update per interval (constant: architect picks) so it cannot flood HA's recorder.
HA: illuminance sensor.

### F-HA-07 Activity timestamps
- Last interaction: when someone last touched the screen or otherwise woke it by user action. Survives app restarts.
- Next screensaver: the moment the idle countdown will start the screensaver. It changes on touch, on dismissal and when the idle timeout changes, so automations can trigger on it or count down to it without per-second updates. It is unknown whenever nothing is counting down (screensaver off or already showing, hold active, another app in front, etc.). Under continuous touching it is republished no more often than a minimum interval (constant: architect picks), but always before the previously published moment passes.
HA: timestamp sensors.

### F-HA-08 Controls (buttons)
Momentary actions, each doing what the same action on the device does:
- Reload page; go to the home dashboard; clear web cache; restart the app; bring Tabula to the front.
- Close overlays: closes anything shown over the dashboard (screensaver, calendar, app launcher, overlay web pages, media library and similar) and leaves the dashboard where it was. It does not close a ringing alarm, notifications or settings. If the screensaver was up, the screen ends up on.
- Restart device: only offered where the app has the privilege to reboot (e.g. device-owner provisioning).
- Postpone screensaver (restart the idle countdown); slideshow next/previous (step a photo screensaver by one and hold the new slide for a full interval; no effect in other screensaver modes).
- Dismiss all notifications (F-NTF-08).
- Open app launcher (when the launcher is enabled).
- Media transport for the followed player (when media is set up), and stop/snooze alarm (covered by the alarms area).
- Take screenshot (F-HA-12).
HA: button entities.

### F-HA-09 Connectivity
A binary sensor shows whether the tablet is currently connected to HA.
HA: connectivity binary_sensor.

### F-HA-10 Selects
- Dashboard view: one option per view of each HA dashboard, then each sidebar panel, then built-in HA pages, then the user's own pages. Choosing an option navigates there now. The list is refreshed when HA reports a dashboard or panel was added, removed or edited, and when the connection returns after an outage, not on a timer. The last list is cached so the select works immediately at startup even while HA is still coming up. Updating the list must not disturb the device's other entities.
- Default dashboard: same options; sets where the tablet starts, returns to on "go home" and reloads after a crash. Changing it does not navigate.
- Calendar view: closed or one of the calendar layouts; choosing opens or closes it.
- Theme: automatic, light or dark. Light or dark pins the dashboard theme, overriding any on-device theme schedule or sync while pinned.
- Screensaver mode and clock style (detailed in the screensaver area).
HA: select entities.

### F-HA-11 Switches and numbers (configuration)
Each mirrors a device setting both ways: changing it in HA changes the device and vice versa, and HA always shows the device's current value.
- Switches: screensaver active (start/stop now), screensaver enabled, hold (keep current view, suppress screensaver), dashboard view rotation, kiosk mode, lockdown, HA's own kiosk (hide header/sidebar) mode, keep screen on, adaptive brightness, screensaver dimming, screensaver schedule, screensaver widgets, now-playing view and its layout options.
- Numbers: media, assistant, notification and alarm volumes (F-TTS-07); screensaver brightness level; idle timeout (a write restarts the idle countdown at the new value; zero means no idle start, only switch/schedule/gesture); screensaver-to-screen-off delay (zero means never; a write while the screensaver runs restarts that countdown; an active schedule entry with its own value wins).
- Text: clock screensaver background image (path or URL; every write reloads, even if unchanged).
Screen brightness/power (a light entity) and screensaver details belong to their own areas.
HA: switch, number, text entities, configuration category.

### F-HA-12 Screenshot
A still image of what the screen shows, refreshed when the take-screenshot button is pressed (or when HA fetches it, per architect). A timestamp records the last capture triggered by the button and survives restarts; a plain fetch of the image does not move it.
HA: image entity, button, timestamp sensor.

### F-HA-13 Update entity
Shows installed and latest app version with release notes; install can be started from HA. Asking HA to refresh the entity checks for a release immediately instead of waiting for the periodic check.
HA: update entity (details in the self-update area).

### F-HA-14 Device services
Beyond entities, the device offers `tabula.*` services for actions that need parameters: show/dismiss notification (F-NTF), announce/stop announcement (F-TTS), launch an installed app by package name (reports an error if the package is missing or not launchable; respects lockdown re-pinning and auto-return rules), open a web page as an overlay above the dashboard with an optional "hold until closed" flag and close it, set brightness (screen area), choose the followed media player (media area). Every service reports a clear error to the caller when it cannot act rather than failing silently.
HA: `tabula.*` services targeted at a device.

## Events reported to HA (F-EVT)

### F-EVT-01 Event basics
The tablet reports state changes as HA bus events named `tabula_*`, one event name per subject, with a field saying what happened. Every event identifies the originating tablet (HA device) so automations can react to one tablet only. Events need no entity to be enabled.
Open: whether events raised while disconnected are dropped or queued and replayed with their original time on reconnect.

### F-EVT-02 `tabula_notification`
Fired when a notification card leaves the screen, once per card: tapped by a person, expired, dismissed by HA (by ID, clear-all or the dismiss-all button), or pushed off by a newer card. Carries the card's ID and its title, message and severity. Clearing several cards fires one event each.

### F-EVT-03 `tabula_screensaver`
Fired when the screensaver starts and when it stops.

### F-EVT-04 `tabula_screen`
Fired when the screen turns on or off, with the source: Tabula itself (screensaver turning it off, HA screen control, a wake by touch or schedule) or the system (power button, OS display timeout; the OS does not say which). With keep-screen-on active, a system "off" is effectively the power button.

### F-EVT-05 `tabula_alarm`
Fired for each alarm when it is created, changed (including enabled/disabled), deleted, begins its wake-up light ramp, rings (again after each snooze), is snoozed, stopped, or auto-silenced after ringing unanswered. Carries the alarm's ID, label, time, repeat days and enabled state, plus the snooze end on snooze. Details in the alarms area.

### F-EVT-06 `tabula_timer`
Fired when a timer starts, changes (time added/removed, paused/resumed), is cancelled, finishes and starts ringing, or its ringing is dismissed. Carries the timer's ID, name, total and remaining time and whether it is running. Details in the timers area.

### F-EVT-07 `tabula_announcement`
Fired when an announcement starts playing and when it ends (finished, replaced, stopped or failed), so automations can, for example, lower another speaker meanwhile. Independent design, adapted from the source's voice-interaction events.

## Management from HA (F-MGT)

### F-MGT-01 Per-device settings edited in HA
Every device setting is editable from HA (options flow now, Tabularium later); HA is the owner and the device caches the values.
- Changes apply live on the device without a restart where possible; unchanged values are not re-applied.
- Each write is validated; rejected keys are reported back with reasons and the rest still apply.
- Secret values are write-only in the UI (shown as set/unset, never echoed).
- A device offline at edit time receives the current settings when it reconnects; while offline it runs on its cache.
HA: options flow / Tabularium; `tabula.*` service to set one or more settings.
Open: whether some settings may also be changed on the device's own settings screen, and how a device-side edit made offline is reconciled with HA's copy.

### F-MGT-02 Shared profiles
A profile is a named set of setting values applied to several devices. Each device has at most one profile; a device's effective settings are its profile's values for the profile's keys and its own values for everything else.
- A built-in empty profile shares nothing (devices keep all their own settings; useful when only coordinated updates are wanted); it cannot be edited or deleted but can be copied as a starting point.
- A profile is defined by setting groups it includes, plus a list of individually excluded keys, plus switches for shared credentials (per credential kind) and for including the start page/dashboard.
- Profile names are unique. Deleting a profile moves its devices to the default profile.
- Changing a profile pushes to all its online devices; offline devices get it on reconnect; only differing values are applied.
HA: Tabularium profile editor; profile assignment as a per-device setting; `tabula.*` services for assign/sync now.
Open: what happens when someone edits, for one device, a key its profile controls (become a per-device override, or be refused/overwritten). Recommend explicit per-device override with a visible marker.

### F-MGT-03 Keys never shared by profiles
Some keys are always per device and are dropped even if a profile names them: device identity (name, IDs), the device's HA pairing credentials, hardware choices (audio devices, rendering fallbacks, UI scale), the followed media player and speaker allowlist, per-device runtime state (alarm list and ringing state, hidden calendars, hold mode on/off, lockdown on/off, saved positions of floating elements), interface language, and update-installer choices (e.g. use the privileged helper).
HA: enforced by the integration; such keys are not offered in the profile editor.

### F-MGT-04 Keys excluded from new profiles by default
Settings that depend on the physical panel or room are excluded from every new profile by default but can be added back: zoom and scale settings, brightness levels (default, adaptive range, screensaver brightness and dim level), all volume levels, screen orientation and display cutout handling, which HA entity drives adaptive brightness or motion wake, per-device media/photo selections and local file picks, notification sound file, and the allowed-apps list.
Files referenced by settings never travel; only the reference does. A device that lacks the file falls back as for any missing file.

### F-MGT-05 Backup and restore
- Export one device: all settings (secrets included, clearly warned) plus the dashboard page's own saved browser data, named and dated.
- Export everything: all devices and profiles in one file; an unreachable device is included from HA's copy (settings are owned by HA).
- Import onto a device in one of two modes: replace (the device takes over the backup's identity, for a replaced or reset tablet) or clone (keep the target's own identity; optionally skip the page's saved data so it does not act as the original).
- Device private keys/certificates and pairing secrets of another device are never exported or imported.
HA: Tabularium actions; optionally `tabula.*` services. HA's own backups cover integration-held settings.

### F-MGT-06 Logs and diagnostics
HA can retrieve the device's recent app log and the dashboard's JavaScript console output (bounded buffers, size: constant, architect picks), view them live in Tabularium, and include them in HA's diagnostics download for the device. Diagnostic sensors report device health: battery and charging (absent without a battery), screen size/orientation, memory, storage, CPU load and temperature, WebView package and version, network link type and Wi-Fi signal (never the network name), app/device/network uptimes, app version.
HA: HA diagnostics download; diagnostic sensors; live view in Tabularium.

### F-MGT-07 Screenshots
HA can capture the current screen on demand; the latest capture is shown as an HA image entity. When the screen is off, a placeholder is returned instead of a black frame. Tabularium may re-capture shortly after a screen-state change (screen off, screensaver, Now Playing) and label the image with that state.
HA: an HA image entity plus a button or `tabula.*` service to capture.

### F-MGT-08 Device commands as HA services and buttons
These device actions are available from HA (buttons for argument-free actions, `tabula.*` services for ones with arguments):
- Navigation: open a URL; open an HA dashboard/view; return to the start page; reload.
- Screen: on, off; set brightness (sets the normal level, or the adaptive maximum when adaptive brightness is on; option to set only the current session's ceiling).
- Screensaver: start, stop, postpone (reset idle timer, dismissing if shown), next/previous slide for photo modes (no-op otherwise, result says whether it moved).
- Overlay page: show a page over the dashboard with a close control, optionally holding the view while it is up; hide it.
- Apps: launch another installed app over Tabula; bring Tabula back to the front; list launchable apps; show/hide the app launcher (refused when the launcher is disabled; limited to allowed apps in kiosk mode).
- Launcher role: show status, take the home role, give it back (the give-back works even with no Tabula screen showing, as a recovery lever).
- Speech and notices: speak text; show and dismiss on-screen notifications (covered in the notifications area).
- Media: show/hide Now Playing; open the music library.
- Process: restart the app; reboot the device only where the OS allows it (device owner or privileged helper), with HA told whether reboot is supported and why not.
Each returns success/failure to the caller.
HA: buttons and `tabula.*` services.

### F-MGT-09 Device problems surfaced in HA
Conditions that need a person are raised in HA: an update available or waiting for an on-screen tap, a feature switched on whose OS permission is missing, an incompatible app version, the device unable to apply settings. They clear themselves when resolved.
HA: HA repair issues and/or problem binary sensors.

### F-MGT-10 Trusting HA's certificate
When HA is served over HTTPS with a certificate from a private certificate authority, the user installs that CA in Android's settings; Tabula then trusts user-installed CAs for everything it fetches from HA (dashboard, HA connection, media, downloads). CAs are read at app start, so a restart is needed after installing one; the app log lists trusted CAs and any refused certificate with its host and issuer. A per-device "accept invalid certificates from HA" option exists for self-signed setups.
HA: device setting for the invalid-certificate option (default: architect picks).
Open: whether the accept-invalid option should be allowed at all, or limited to certificate pinning on first pairing.

### F-MGT-11 Bootstrapping and recovery
- Provisioning over ADB: a settings payload (same format as an export) can be delivered through an ADB-only entry point, e.g. to set the HA address or pairing details on a fresh device; other apps on the device cannot use it.
- With HA unreachable the device keeps running on cached settings and keeps retrying.
- A local recovery page (off by default) lets someone on the LAN fix connection settings when HA cannot be reached.
- Giving back the home launcher role (F-MGT-08) and the ADB path are always available as escape hatches.
HA: n/a (device-side); recovery page enable is a device setting.

## Dashboard optimizations (F-OPT)

### F-OPT-01 Keep the dashboard's HA session alive while hidden
HA's frontend normally drops its live connection some time after the page stops being visible. On a wall panel the screen is often off or covered, so Tabula keeps the dashboard's frontend connection open regardless of visibility, re-applying this on every page load, so waking the panel shows current state without a reconnect wait. It can be turned off for users who want HA's stock behavior.
HA: a device setting (default: architect picks).

### F-OPT-02 Stop drawing the dashboard while it is fully covered
When something opaque covers the whole dashboard, Tabula stops the WebView from painting (animations, charts, image refreshes) until it is uncovered, then shows it again at once.
- Covers that trigger it: any full-overlay screensaver, the on-device settings screen, full-screen overlay pages (e.g. a tapped external link, an external page in view rotation), a blackout-style lockdown screen.
- Not triggered by: the dim screensaver and the HA-dashboard-view screensaver (the dashboard stays visible), and overlay pages served from the HA server's own origin (treated as part of the dashboard).
- Only painting stops. Page scripts keep running, the HA connection stays up, entity state keeps flowing, and timers, alarms, notifications and TTS work normally, so the page is live the instant it is revealed.
- Turning this on forces F-OPT-01 on, since a paused page must not be disconnected.
- Must be switchable off for WebViews that redraw incorrectly after resuming.
HA: a device setting (default: architect picks).

### F-OPT-03 Release dashboard camera streams while covered
While an opaque screensaver covers the dashboard, or the display is off during a screensaver, live camera players inside HA's standard camera card are released (stream players and repeating snapshot images alike) and recreated when the cover goes away; a short delay before video returns is expected. Exceptions: streams playing with sound are left running (audio may be intentional), cards with their own custom players are untouched, and the dim screensaver keeps streams playing. Works independently of F-OPT-02. Switching the setting off restores streams immediately without a page reload.
HA: a device setting (default: architect picks).

### F-OPT-04 Feed the dashboard only the entities it shows
Tabula works out which entities the currently shown dashboard view uses and drops state updates for all other entities before the page processes them.
- Recomputed on every view change.
- If a view contains cards whose entity use cannot be determined reliably (e.g. templated or dynamic custom cards), filtering is bypassed for that view; the worst case is stock, unfiltered behavior, never a broken card.
- If a component on the view enumerates the full state table, the view is treated as reading everything, and a one-time diagnostic (a call stack captured once per visit to that view, size-bounded, constant: architect picks) is recorded so the user can find the culprit. Capturing must not happen on normal reads.
- Not applied to custom pages (user-authored pages that are not HA dashboards).
- Live status: number of entities tracked, share of updates dropped, and the list of tracked entities, viewable for verification; plus a state of "filtering", "unfiltered (view reads all states)" or "not applicable (custom page)".
HA: a device setting (default: architect picks); the filter status and tracked count as an HA diagnostic sensor, refreshed at a bounded rate (constant: architect picks).
Open: whether the filter is done by intercepting the frontend's subscription inside the WebView or by another mechanism; behavior must be as above either way.

### F-OPT-05 Precedence and interaction with other features
- Anything that keeps the dashboard visible (dim screensaver, dashboard-view screensaver, Now Playing shown beside a dashboard-view screensaver) disables F-OPT-02 and F-OPT-03 for that period.
- F-OPT-04 is independent of covering: it keeps running behind a cover so the revealed page is current.

## Self-update (F-UPD)

### F-UPD-01 Update discovery
The app learns whether a newer release exists: once shortly after start (delay: constant, architect picks) and then periodically (interval: constant, architect picks), and on demand. A newer release is shown in three places: an HA update entity for the device (installed version, latest version, release notes, install action, progress), an "update available" entry in the on-device menu (hidden in the restricted/locked menu), and the Tabularium device page. If release notes cannot be retrieved, the update is still offered without notes.
HA: an HA update entity per device; HA's standard "update entity" refresh triggers an immediate check.
Open: where the check runs. Since the device talks only to HA, the integration (not the device) should check the release source and tell each device what is available.

### F-UPD-02 Release channels
A device follows a release channel (e.g. stable or pre-release). Only releases on that channel are offered. Switching to a channel whose newest release is older than the installed one offers nothing (no downgrade).
HA: a device setting (default: architect picks).
Open: channel set and whether the channel is per device or integration-wide. (Independent design; not described by the source behavior beyond a single release stream plus an alternative source.)

### F-UPD-03 Installing
Install can be started from the HA update entity, from Tabularium, or on the device. All start the same flow:
1. Re-confirm the newest release just before fetching, so the newest is always installed.
2. Pick the package for the device's CPU architecture, preferring the device's supported architectures in order; fall back to a universal package when no matching one exists or architecture is unknown (a 64-bit CPU running a 32-bit OS gets the 32-bit package).
3. Download with progress visible on the device, in HA (update entity progress) and in Tabularium; the download is cancellable from the device and from HA/Tabularium.
4. Verify it is a Tabula package, not older than the installed build, and that there is enough free space for the installer to stage it (the installer needs headroom beyond the package size, ratio: constant, architect picks; refuse with a clear message otherwise).
5. Hand it to the installer path from F-UPD-04.
The user always decides when to install; nothing installs automatically unless F-UPD-07 is enabled.
HA: install action on the update entity; a `tabula_*` event or entity attribute for the outcome (installed, cancelled, failed with reason, uncertain).
Open: package transport. With HA as the only peer, the device must fetch the package through HA (integration-proxied download, or HA-hosted file). Architect decides.

### F-UPD-04 Installer paths and confirmation
The app picks the quietest path the device allows, in this order:
- OS-native silent install when available (device owner; or on newer Android, when the app is its own installer of record after one confirmed in-app update; an external sideload resets that and the next in-app update needs one confirmation again).
- A privileged helper the user has set up (e.g. Shizuku), if enabled and authorized. Its availability is checked before download and again before install; if it is unavailable or fails, report the error rather than silently switching to the confirmation dialog.
- Otherwise the system install confirmation dialog on screen, which someone must tap.
Before the first confirmation-based install, the OS requires the "install unknown apps" grant for Tabula. If it is missing, the user is taken to that system toggle (once granted it persists); the grant can also be applied over ADB.
HA: the update entity reports when an install is waiting for on-screen confirmation, so the person using HA knows to walk to the tablet.

### F-UPD-05 Fire OS constraints
On Fire tablets (target Fire OS 7) the OS-native silent path is unavailable: ordinary installs always need on-screen confirmation, and device ownership usually cannot be assigned because the OS reserves a profile owner from initial setup. Silent install on Fire OS therefore requires the privileged-helper path; otherwise every update needs a tap. The unknown-sources grant lives in Fire OS's own security settings screen.

### F-UPD-06 Lockdown during install and relaunch after
- The install confirmation dialog would be hidden by kiosk lockdown, so lockdown is lifted just for the install and re-armed when the install completes, fails or is cancelled.
- The OS kills the app during replacement. The app must come back by itself afterwards; on Android versions that require it, this needs the "display over other apps" permission. Without it the update succeeds but the app stays closed until opened; setup must request this permission.
- After relaunch the device reconnects to HA and the update entity shows the new installed version.
- If contact is lost after the install was committed, the outcome is reported as uncertain and is not retried automatically; the user checks the installed version first.

### F-UPD-07 Coordinated updates from HA
HA can update many devices as one operation: devices are updated one after another (or in bounded parallel batches, constant: architect picks), each reporting its outcome; a device that requires an on-screen confirmation prompts on its own display and the operation records it as waiting. An optional per-device or integration-wide policy keeps devices on the newest release automatically: when a device is behind, the install is started for it without a person pressing install.
HA: a `tabula.*` service to update a set of devices; the auto-keep-current policy as a setting (default: architect picks).

### F-UPD-08 Version compatibility between app and integration
The integration knows which app versions it can talk to. A device on an incompatible version is flagged (HA repair issue or problem sensor) and offered the update; settings or profile pushes that the device's version cannot understand are held until it is updated, then applied automatically.
Open: compatibility rule (protocol version vs app version) is the architect's.

### F-UPD-09 Installing a supplied package
For devices with no path to a release source, a package file supplied by the user (via Tabularium, through HA) can be installed. The device reads its identity and version first and shows what will replace what, and refuses: non-packages, packages of other apps, builds older than the installed one, and packages it lacks space to stage. Installation then uses F-UPD-04. A supplied package can also be pushed to several devices at once; devices already on that build are skipped.
HA: Tabularium action; outcome reported as in F-UPD-03.

### F-UPD-10 Manual sideload
A manual sideload over ADB always works and preserves all settings and the HA pairing (see F-UPD-04 for its effect on silent installs).

## JavaScript bridge (F-JSB)

### F-JSB-01 Presence and shape
`window.tabula` exists synchronously from document start in the dashboard WebView, so a plain presence check works. It carries plain properties identifying the platform, app version and OS. Every method returns a promise; failures resolve to `null` (queries) or `false` (commands) rather than rejecting, except the HA-connection methods (F-JSB-08). Device-to-page signals are DOM custom events on `window` with a `tabula:` prefix.
Open: which origins get the bridge (recommend: HA's origin and explicitly allowed custom pages only, not arbitrary pages).

### F-JSB-02 Device and screen
Read device identity and status (ID, name, model, OS and version, app version, battery, charging). Read and set brightness on a normalized scale (range: architect picks) (same semantics as the HA brightness command, F-MGT-08). Turn the screen on or off and read whether it is on. Events: screen turned on, screen turned off.

### F-JSB-03 Theme
Read the app's light/dark mode (setting and what it currently resolves to) and HA's theme state as Tabula applies it (setting, current light/dark or "HA decides", whether it is time-scheduled, whether it follows the app). An event fires with the same shape whenever either changes.

### F-JSB-04 Kiosk state
Read one snapshot of modes and overlays: kiosk mode on, lockdown on, HA's header/sidebar hiding state, screensaver active and which kind, screen on, hold (pin current view) mode, view rotation on, and the followed media player (state playing/paused/idle, title, artist, player name, whether Now Playing is shown). An event fires with the full snapshot whenever any field changes.

### F-JSB-05 Interaction holds and screensaver
- A page can bracket an interaction (start/end, with an optional reason). While held, ambient behaviors pause: screensaver, view rotation, return-to-home. A "media" reason holds only the screensaver and lets rotation and return-to-home continue.
- One-shot dismiss of the screensaver.
- A simpler suppress/release of the screensaver, equivalent to an unreasoned interaction hold.
- A query telling the page that Tabula owns the screen, so the page should not run its own screensaver.
- Events: screensaver started, screensaver stopped.
Open: what clears a hold if the page navigates away or crashes without releasing (recommend: page unload releases its holds; plus a safety timeout, constant: architect picks).

### F-JSB-06 Native sound playback
A page can ask the app to play a sound URL natively (bypassing WebView autoplay limits, using the app's network stack so HA certificates trusted by the app work). Options: cache for instant replay of fixed assets, stream while downloading for generated audio such as TTS. Loudness is always the app's own volume setting; any volume value from the page is ignored. Also: prefetch a URL, stop a sound by ID. Events: sound actually started (time UI off this, not off the call resolving), playback level for visualizers (best effort, rate-bounded, constant: architect picks; absent where the device cannot measure it), sound ended (exactly once per sound, with an error when it failed or after stop).

### F-JSB-07 Timers
Read the device's timers (running, paused, then ringing; each with ID, name, total, remaining, active, end time, finished) and control one (pause, resume, cancel, add or remove time; an empty ID targets the only timer). An event delivers the full list after every change; it does not tick, so pages count down from the end time themselves.

### F-JSB-08 HA connection for custom pages
A custom page (not HA's own dashboard) can talk to HA's WebSocket API through Tabula without holding a token: send one command, subscribe (re-sent automatically after reconnect), or obtain a socket object compatible with HA's standard JS WebSocket client library. The page acts as the HA user Tabula is paired as, and owns its message IDs.
- Allowed only when a per-device setting enables it and the page is a custom page; a page's connections close when it navigates away.
- Credential and user-management commands (token creation, auth commands other than reading the current user and signing a path, user/password admin) are refused with a not-allowed error, so the page cannot mint its own token.
- These methods reject on failure with a code: not allowed, not configured, auth invalid, unreachable, too many sockets (bounded per page, constant: architect picks), connection lost.
- Events: connection closed (with reason; pending commands reject), connection restored (subscriptions re-sent).
HA: the enable switch is a device setting (default: architect picks).
Open: whether this rides the device's single HA connection or a separate one; the single-connection rule suggests multiplexing.

## Deferred and dropped

One-line hooks only. Each is out of scope until a later packet brings it in.

- Bluetooth proxy and its sensors (nearby/connected/max connections): dropped.
- Voice satellite entities, voice-interaction events and speaker-identification events: deferred with the voice satellite.
- Ambient noise sensor: deferred with the microphone (see F-TTS-09 Open).
- Intercom entities, services and events: deferred.
- Camera, camera snapshot, motion/person sensors, camera views, RTSP: deferred.
- LED control entities: deferred.
- GPS location sensors: deferred (no target hardware has GPS).
- Shell-command service via privileged helper: dropped.
- Remote-admin URL sensor and device-side admin server link: dropped (only the later recovery page exists).
- Local Android broadcasts of the same events to on-device automation apps: deferred.
- Firmware update entity for custom device images: dropped (Fire OS only).
- MQTT or other device-side transports, node naming and generated network identity: not applicable; the device talks to HA only through the `tabula` integration.
- Stopping/snoozing by spoken stop word or by voice request: deferred with voice satellite.
- Setting alarms by voice through an LLM agent: deferred; a later voice feature would call the F-ALM-16 services.
- Clap and hand/finger gestures to stop or snooze: dropped (camera/microphone).
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
- Fire OS microphone revocation for sideloaded apps (relevant only when microphone features return).
- Camera/microphone/nearby-devices/log-access/all-files permissions (their features are deferred).
- Android 14 Wi-Fi lock downgrade workaround and MediaTek background-killer workaround (not on target hardware).
- Remote-admin recovery path (replaced by HA; later off-by-default recovery page).
- Voice wake-word muting during touch lock (voice dropped).
- Silent home-role acquisition on custom device images (device-owner only).
- Camera hand gestures (finger counting).
- Clap gestures via microphone.
- Plugin triggers and plugin-hosted action settings.
- Hardware-button gestures on screenless devices.
- Actions for intercom, camera views, music library/assistant, voice satellite.
- Benchmarks and comparisons against other kiosk browsers: not a behavior, dropped.
- Pausing the device's own camera / camera views behind the screensaver: device camera dropped.
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
- Camera motion, face and proximity events: dropped with the device camera; revisit if HA-entity-based presence events are wanted.
- Voice-assistant chime durations and voice-timer pill hand-off: dropped with voice.
- Intercom status and microphone-yield events: dropped.
- Plugin APIs on the bridge: deferred; the bridge is the seam they will extend.
- Night-sky / astronomy screensaver: dropped for v1.
- Immich photo source: dropped (possible future plugin).
- Camera-stream screensaver and camera-based motion/face wake: deferred with camera work.
- Proximity and hardware person-sensor wake: deferred.
- Voice wake-word dismissal and voice-timer pills: parked with voice.
