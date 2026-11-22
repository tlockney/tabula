# 0023: Screensaver schedule resolution

Status: accepted
Date: 2026-11-21
Packet: P3.12

## Context

F-SSV-18 adds a daily schedule: slots with a start time and a mode, the last
wrapping past midnight. The settings schema (P3.1) and HA's options flow
(P3.2) already carry it; the `schedule` trigger value already exists in TLP
v1. What the engine side must decide is how a slot's mode and overrides meet
the existing screensaver engine (ADR 0013): an idle timer that starts the
screensaver, HA commands that override it, a mode setting that only names the
mode used next time (F-SSV-05), and overlays (brightness, F-SSV-17; widgets
and At a Glance) that are configured globally.

The questions: when the active slot changes - a crossing, or an edit landing
on the running tablet - what restarts? Who resolves a slot's overrides
against which global settings? And what wakes the app at a slot start?

## Decision

1. **The engine answers only which slot is active when.** `ScheduleEngine`
   (plain JVM) maps a wall-clock time to the active slot and to the next
   slot's start. It knows nothing of settings, overlays or rendering;
   every consumer resolves a slot's overrides against its own global
   configuration. That keeps one pure time source and lets each consumer
   enforce F-SSV-18's rule: a slot can only turn off overlays the global
   configuration has, never invent them.

2. **One rule for crossings and edits.** The active slot is re-resolved on
   every tick and on every settings push. A running screensaver restarts
   only when the mode it should show changed - both events carrying trigger
   `schedule`, the started one the mode actually shown (ADR 0013 item 3's
   stand-in rule for unbuilt modes). The same mode showing keeps the run;
   the slot's other overrides take effect where their consumers read them,
   with no restart. Before the schedule existed there was no restart to
   reason about; now every slot change, from a boundary crossing or a live
   edit, follows this one path.

3. **The schedule never starts or stops a screensaver.** The idle timer and
   HA's commands still do that; the schedule only supplies the mode used
   next time (F-SSV-05's setting re-read through the active slot). A tick
   where a slot boundary and the idle deadline have both passed resolves
   the boundary first, so an idle start landing on a slot's start uses the
   new slot's mode, not the old one's.

4. **The manager waits on the earlier of the idle deadline and the next
   slot's start.** `nextEventMs` returns that moment; the app's existing
   timer loop sleeps until it. Crossings therefore fire without any
   settings push, and a boundary matters in every controller state -
   showing (the restart), idle (the next start's mode) and paused (it
   applies on resume, because the next tick re-resolves).

5. **Slot starts are local wall times in the tablet's current zone.** The
   engine reads them through a `ZoneId` the app owns, so the schedule
   follows the tablet through timezone changes with no reconfiguration. A
   DST night needs no special case: a start minute that does not exist, or
   exists twice, resolves like any other minute in the zone's rules.

6. **Overrides are read, not pushed.** The resolved slot is controller
   state (`activeSlot`), and each consumer resolves it live: the backlight
   re-holds on every settings push (so editing the running slot's
   brightness applies without a restart), the entity subscription
   recomputes its needs (a slot's turn-offs drop subscriptions), and the
   power-off countdown and presence dismissal are carried as state for
   F-SSV-19 and F-SSV-24, which do not exist yet - a configured but unbuilt
   feature is a no-op. A slot's fixed brightness applies when the slot sets
   one, regardless of the separate `brightness_enabled` switch, which
   governs only the global separate level (F-SSV-17): the slot's value is
   its own explicit configuration, while `null` inherits the global path
   switch and all. A restart - any new run - restarts the power-off
   countdown, and so does a change of its effective delay while showing.

## Consequences

- A crossing into a slot whose mode equals the showing one is invisible:
  no flicker, no events. Only the state and the consumers' reads change.
- The idle start, the `screensaver` command and the schedule all meet in
  one place (`resolvedMode`), so their tests share the resolution rule and
  each trigger's semantics stay where they were (ADR 0013).
- The unbuilt-mode stand-in composes: a slot's weather (unbuilt) shows the
  clock, the reported `screensaver_mode` stays weather, and a later built
  crossing restarts into the real mode.
- F-SSV-19 and F-SSV-24 land against `controller.powerOff` and
  `controller.presenceDismiss` without touching the schedule again.
- The schedule is immutable per resolution: the engine is rebuilt on every
  settings push, so a mid-run edit is just a new engine with the same zone.

## Alternatives considered

- **Restart on every crossing or edit.** Rejected: a same-mode crossing
  would stop and start the overlay for nothing - a visible flicker - and
  F-SSV-18 asks for live application, not for restarts.
- **A settings-side override table the engine writes into.** Rejected: the
  engine would need every consumer's global configuration to resolve a
  slot, or every consumer would need to know the schedule's shape. State
  plus consumer-side reads keeps the coupling one-way.
- **Wake with the platform's alarm scheduler.** Rejected: the controller
  is plain JVM (ADR 0013); the app already runs a clock for the idle
  timer, and `nextEventMs` extends that loop with no new platform moving
  part to test.
- **Day-of-week variation.** Out of F-SSV-18's scope; the engine's
  daily slots make a later weekly extension a wrapper, not a rewrite.
