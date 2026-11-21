# 0019: Start target as a settings path (P3.15)

Status: accepted
Date: 2026-11-21
Packet: P3.15

## Context

F-DSH-02 gives every device a start target: the page opened on launch, after a crash or restart, and on "go home". F-DSH-15 optionally returns to it after idle. F-HA-10 asks how the start target is picked and which HA entities it gets; F-DSH-03's page catalog — the picker that would list every dashboard view, panel and built-in page — is Phase 4 work (#22) and does not exist yet. Meanwhile `navigate` (PROTOCOL 4.5) already resolves and origin-checks raw frontend paths, and the screensaver engine already tracks the last touch.

## Decision

1. **The start target is a frontend path in a text setting, `dashboard.start_path` (F-DSH-02).** Until the page catalog exists, the user types the path (e.g. `/lovelace/kitchen`, or a fragment target such as `/lovelace/0#popup`). This answers the F-DSH-02 and F-HA-10 open questions for now: the candidate "start dashboard" select becomes a text entity plus an options-flow field, replaced by a catalog-backed select when F-DSH-03 lands (Phase 4 navigation breakdown, #22). The shape matches `navigate` — a single leading slash, an optional fragment, no query string (a query would break the launch load, which appends the external-auth parameter) — and the device validates it exactly as `navigate` validates its path: resolved against the HA base URL, refused if the result leaves the origin (ADR 0001 item 3). An invalid or off-origin value falls back to HA's default page with a warning log, never a broken screen. Changing the setting does not move the page on screen (F-DSH-02); it only changes where the next launch, restart or "go home" lands.

2. **The return after idle is `dashboard.return_home_after`, 0 = never (F-DSH-15).** The default keeps existing tablets exactly as they are: no return until the user opts in.

3. **What the idle timer counts, and what resets it.** The countdown runs from the last touch. It is reset by any touch and by any page change — including a client-side route change inside the frontend — which is this packet's independent-design reading of "reset by HA navigation" (F-DSH-15 does not say what resets it; a page change is the observable form of HA navigating, and a route change the frontend made on its own deserves the same grace). The timer keeps running while a screensaver shows and while the panel is off (F-OPT: the covered dashboard can reset overnight without lighting the screen); it is not paused by the panel or the foreground, because the point of the feature is to fix the page while nobody is looking. When the deadline passes, the device returns only if the current page is not already the start target (path only; a fragment target compares by path), and the countdown then starts over, so it does not fire on every tick.

4. **One pause hook for what does not exist yet.** View freeze (F-DSH-17) and page-declared interactions (F-DSH-18) must pause the return; neither exists yet. The engine has a single `setHeld` hook they will drive when they land, and nothing calls it in the meantime.

5. **A changed delay restarts the countdown; a changed path does not navigate.** Mirrors the screensaver idle-timeout rule (ADR 0015 item 8): the user just changed the contract, so the wait starts over. Changing `start_path` alone never moves the current page (F-DSH-02).

6. **"Go home" is the `go_home` command, capability `go_home`, plus an HA button (F-NAV-03, F-HA-08).** Additive per PROTOCOL 8. `restart_app` already relaunches the process, and the relaunch loads the start target, so F-NAV-03's "restart lands on the start target" needs no separate mechanism; `reload` keeps the current page.

## Consequences

- The catalog-backed select (Phase 4) replaces the text entity and the path typing; the settings key keeps meaning "the start target", so no schema break is expected — only the editor changes.
- The idle return runs alongside the screensaver's own idle timer; they are independent countdowns from the same last touch, and one lighting the screen (screensaver dismissal) resets the other (touch).
- The swipe-map exception (F-NAV-13) and the per-feature landing overrides (F-DSH-15's last two rules) arrive with those features.

## Alternatives considered

- **Wait for the page catalog.** Cleaner picker, but the start target would block on Phase 4 for two settings and a command that `navigate` already makes possible.
- **Gate the return on the screensaver's timer.** One countdown would be simpler, but F-DSH-15's timing is independent of F-SSV-01's, and coupling them would force a screensaver setting change to reset the return contract.
- **A `tabula.go_home` service only, no button.** F-HA-08 lists the button as a candidate; the button is the standard HA surface and costs one entity, and `button.press` already is a service.
