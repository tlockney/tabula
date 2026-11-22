package net.lockney.tabula.core.screensaver

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import net.lockney.tabula.core.settings.Schedule
import net.lockney.tabula.core.settings.ScheduleSlots
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.core.tlp.ScreensaverStartTrigger
import net.lockney.tabula.core.tlp.ScreensaverStopTrigger
import net.lockney.tabula.core.tlp.StateDelta

/**
 * The screensaver's idle timer and lifecycle (FEATURES F-SSV-01 to F-SSV-05,
 * F-SSV-18, F-SSV-26), free of Android so it is tested on a plain JVM. The
 * app feeds it settings, touches, `screensaver` commands, pause changes and
 * the clock, and carries out each [Outcome].
 *
 * - The timer runs from the last activity. It is paused while the panel is off
 *   or another app is in front (F-SSV-02), and starts over when it resumes.
 * - Touch dismisses a running screensaver and restarts the timer (F-SSV-04).
 * - Turning the master switch off dismisses a running screensaver (F-SSV-01).
 *   An explicit start from HA works with the switch off: it is a direct
 *   request, and the switch only governs the idle start (ADR 0013).
 * - Modes this build cannot show yet are shown as the clock; [shownMode] says
 *   what is on screen, and the reported `screensaver_mode` stays the
 *   configured one (PROTOCOL 4.4: the mode used next time).
 * - The schedule (F-SSV-18, packet P3.12) drives which mode that is: the
 *   active slot's mode is the mode used next time, a crossing into a slot
 *   with a different mode restarts a running screensaver (trigger `schedule`)
 *   and edits re-resolve live - one rule for crossings and edits (ADR 0023).
 *   The schedule never starts or stops a screensaver on its own; the idle
 *   timer and HA's commands still do that.
 *
 * Times are wall-clock epoch milliseconds, because `next_screensaver` is a
 * wall-clock date-time. Thread-safe.
 */
class ScreensaverController(
    /** Modes the app can render; anything else falls back to [FALLBACK]. */
    private val supported: Set<ScreensaverMode> = BUILT_MODES,
    /** Slot starts are local wall times (F-SSV-18); the zone that reads them. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** A lifecycle change for the app to carry out and report (F-SSV-26). */
    sealed interface Transition {
        data class Started(val mode: ScreensaverMode, val trigger: ScreensaverStartTrigger) : Transition

        data class Stopped(val mode: ScreensaverMode, val trigger: ScreensaverStopTrigger) : Transition
    }

    data class Outcome(val transitions: List<Transition> = emptyList(), val state: StateDelta? = null)

    /**
     * The power-off countdown's state (F-SSV-18: crossing into a slot with a
     * different delay restarts it). The countdown itself is F-SSV-19's and
     * does not exist yet; this is the state it will consume.
     */
    data class PowerOff(val delayMinutes: Int, val sinceMs: Long)

    private var enabled = Screensaver().enabled
    private var timeoutMs = Screensaver().idleTimeout * 1000L
    private var mode = fromSettings(Screensaver().mode)
    private var brightness = Screensaver().brightness
    private var brightnessEnabled = Screensaver().brightnessEnabled
    private var dimBrightness = Screensaver().dimBrightness
    private var engine = ScheduleEngine(zone = zone)
    private var lastActivityMs: Long? = null
    private var pauses = 0
    private var shown: ScreensaverMode? = null
    private var reportedNext: String? = null
    private var reportedMode: ScreensaverMode? = null
    private var reportedBrightness: Int? = null

    /** The slot whose overrides are in effect, resolved at the last tick or settings push. */
    private var appliedSlot: ScheduleSlots? = null

    /** The effective panel power-off delay of the showing run, minutes, 0 = never; null inherits. */
    private var powerOffDelay: Int? = null

    /** When [powerOffDelay] came into effect for this run; null while nothing shows. */
    private var powerOffSinceMs: Long? = null

    /** The mode on screen, or null when no screensaver shows. */
    @get:Synchronized
    val shownMode: ScreensaverMode?
        get() = shown

    /** The slot whose overrides apply right now; the entity subscription reads its turn-offs. */
    @get:Synchronized
    val activeSlot: ScheduleSlots?
        get() = appliedSlot

    /** The power-off countdown state while a slot set a delay and a run is showing (F-SSV-18). */
    @get:Synchronized
    val powerOff: PowerOff?
        get() = powerOffSinceMs?.let { since -> powerOffDelay?.let { PowerOff(it, since) } }

    /**
     * The active slot's presence dismissal (F-SSV-18), resolved now; null
     * inherits the global value, and the presence source itself (F-SSV-24)
     * is out of scope, so a configured-but-unbuilt feature is a no-op: the
     * flag is carried as state for the packet that consumes it (P3.12 item 3).
     */
    @get:Synchronized
    val presenceDismiss: Boolean?
        get() = appliedSlot?.presenceDismiss

    /**
     * The backlight level while the screensaver shows (F-SSV-17); black uses
     * the lowest, dim holds its own `dim_brightness` (F-SSV-07, P3.14) and the
     * rest hold the active slot's fixed level (F-SSV-18) or, when the slot
     * inherits, the separate level - which with `brightness_enabled` off
     * (P3.2) holds nothing, so the panel keeps its current level.
     */
    @get:Synchronized
    val brightnessWhileShown: Int?
        get() = when (shown) {
            null -> null
            ScreensaverMode.BLACK -> 0
            ScreensaverMode.DIM -> dimBrightness
            else -> appliedSlot?.brightness ?: brightness.takeIf { brightnessEnabled }
        }

    /** The mode the schedule or the settings pick for the next start (PROTOCOL 4.4). */
    private val resolvedMode: ScreensaverMode
        get() = appliedSlot?.let { slotMode(it) } ?: mode

    /** Full screensaver state for registration (PROTOCOL 4.2). */
    @Synchronized
    fun current(nowMs: Long): StateDelta = StateDelta(
        screensaverActive = shown != null,
        screensaverMode = resolvedMode,
        screensaverBrightness = brightness,
        nextScreensaver = next(nowMs).also { reportedNext = it },
    ).also {
        reportedMode = resolvedMode
        reportedBrightness = brightness
    }

    /** When the idle timer fires, or null while it cannot (disabled, paused, showing). */
    @Synchronized
    fun deadlineMs(nowMs: Long): Long? {
        if (!enabled || shown != null || pauses > 0) return null
        return (lastActivityMs ?: nowMs.also { lastActivityMs = it }) + timeoutMs
    }

    /**
     * The next moment the clock owner must call [onTick]: the idle deadline
     * or the next slot's start, whichever comes first (F-SSV-18). A boundary
     * matters while the screensaver shows (the restart), while the tablet is
     * idle (the mode used next time changes) and while it is paused.
     */
    @Synchronized
    fun nextEventMs(nowMs: Long): Long? {
        val idle = deadlineMs(nowMs)
        val boundary = engine.nextBoundaryMs(nowMs)
        return when {
            idle == null -> boundary
            boundary == null -> idle
            else -> minOf(idle, boundary)
        }
    }

    @Synchronized
    fun applySettings(settings: Screensaver, nowMs: Long, schedule: Schedule = Schedule()): Outcome {
        val wasEnabled = enabled
        val oldTimeoutMs = timeoutMs
        enabled = settings.enabled
        timeoutMs = settings.idleTimeout * 1000L
        mode = fromSettings(settings.mode)
        brightness = settings.brightness
        brightnessEnabled = settings.brightnessEnabled
        dimBrightness = settings.dimBrightness
        engine = ScheduleEngine(schedule, zone)
        // switching on, or a new idle timeout, starts the countdown over (F-HA-11)
        if ((enabled && !wasEnabled) || timeoutMs != oldTimeoutMs) lastActivityMs = nowMs
        val transitions = resolveSlot(nowMs, restart = enabled).toMutableList()
        if (!enabled && shown != null) transitions += stop(ScreensaverStopTrigger.COMMAND, nowMs)
        // a schedule restart keeps the screensaver active; the master switch
        // is the only settings path here that dismisses one
        val dismissed = transitions.isNotEmpty() && transitions.none { it is Transition.Started }
        // a delta holds only the keys that changed (PROTOCOL 4.4)
        val state = StateDelta(
            screensaverActive = if (dismissed) false else null,
            screensaverBrightness = brightness.takeIf { it != reportedBrightness }?.also { reportedBrightness = it },
        ).withNext(nowMs)
        val withMode = modeDelta()?.let { state.copy(screensaverMode = it.screensaverMode) } ?: state
        return Outcome(transitions, withMode.takeIf { it.isNotEmpty })
    }

    /** A touch or key press on the kiosk (F-SSV-02, F-SSV-04). */
    @Synchronized
    fun onActivity(nowMs: Long): Outcome {
        lastActivityMs = nowMs
        if (shown == null) return Outcome(state = StateDelta().withNext(nowMs).takeIf { it.isNotEmpty })
        val stopped = stop(ScreensaverStopTrigger.TOUCH, nowMs)
        return Outcome(listOf(stopped), StateDelta(screensaverActive = false).withNext(nowMs))
    }

    /** The `screensaver` command (PROTOCOL 4.5): start (optionally in [requested] mode) or stop. */
    @Synchronized
    fun onCommand(active: Boolean, requested: ScreensaverMode?, nowMs: Long): Outcome {
        if (!active) {
            if (shown == null) return Outcome()
            lastActivityMs = nowMs
            val stopped = stop(ScreensaverStopTrigger.COMMAND, nowMs)
            return Outcome(listOf(stopped), StateDelta(screensaverActive = false).withNext(nowMs))
        }
        val target = render(requested ?: resolvedMode)
        val current = shown
        if (current == target) return Outcome()
        val transitions = buildList {
            if (current != null) add(stop(ScreensaverStopTrigger.COMMAND, nowMs))
            add(start(target, ScreensaverStartTrigger.COMMAND, nowMs))
        }
        return Outcome(transitions, StateDelta(screensaverActive = true).withNext(nowMs))
    }

    /** Pause or resume the idle timer (panel off, another app in front). Calls nest. */
    @Synchronized
    fun setPaused(paused: Boolean, nowMs: Long): Outcome {
        pauses = if (paused) pauses + 1 else (pauses - 1).coerceAtLeast(0)
        if (pauses == 0) lastActivityMs = nowMs // the timer starts over (F-SSV-02)
        return Outcome(state = StateDelta().withNext(nowMs).takeIf { it.isNotEmpty })
    }

    /**
     * The clock moved on: first a slot boundary that has passed, then the idle
     * start when its deadline has - so a boundary landing on the deadline
     * starts the new slot's mode, not the old one's.
     */
    @Synchronized
    fun onTick(nowMs: Long): Outcome {
        val transitions = resolveSlot(nowMs, restart = true).toMutableList()
        val deadline = deadlineMs(nowMs)
        if (deadline != null && nowMs >= deadline) {
            transitions += start(render(resolvedMode), ScreensaverStartTrigger.IDLE, nowMs)
            val state = (modeDelta() ?: StateDelta()).copy(screensaverActive = true).withNext(nowMs)
            return Outcome(transitions, state.takeIf { it.isNotEmpty })
        }
        val state = (modeDelta() ?: StateDelta()).withNext(nowMs)
        return Outcome(transitions, state.takeIf { it.isNotEmpty })
    }

    /**
     * Re-resolve the schedule at [nowMs] (F-SSV-18). A change - a crossing, or
     * an edit that landed since the last resolution - applies live: a running
     * screensaver restarts when the mode it should show changed, both events
     * carrying trigger `schedule` and the started one the mode actually shown
     * (ADR 0013 item 3's stand-in rule). The same mode shown keeps the run;
     * the slot's other overrides are read where they apply. The global mode
     * setting is not this path (F-SSV-05: the mode used next time, without a
     * restart) - only the schedule's side of the resolution is. When a
     * [restart] would only race a dismissal in the same push, it is skipped.
     *
     * Also keeps the power-off countdown state (F-SSV-18): a change of the
     * effective delay restarts it, and so does a restart, being a new run.
     */
    private fun resolveSlot(nowMs: Long, restart: Boolean): List<Transition> {
        val slot = engine.slotAt(nowMs)
        if (slot == appliedSlot) return emptyList()
        appliedSlot = slot
        val wasShowing = shown
        val transitions = mutableListOf<Transition>()
        if (restart && wasShowing != null && render(resolvedMode) != wasShowing) {
            transitions += Transition.Stopped(wasShowing, ScreensaverStopTrigger.SCHEDULE)
            transitions += Transition.Started(render(resolvedMode), ScreensaverStartTrigger.SCHEDULE)
            shown = render(resolvedMode)
            powerOffSinceMs = nowMs
        }
        if (slot?.powerOffDelay != powerOffDelay) {
            powerOffDelay = slot?.powerOffDelay
            if (wasShowing != null) powerOffSinceMs = nowMs
        }
        return transitions
    }

    /** The mode-used-next-time delta (PROTOCOL 4.4), or null when it did not change. */
    private fun modeDelta(): StateDelta? =
        resolvedMode.takeIf { it != reportedMode }?.let {
            reportedMode = it
            StateDelta(screensaverMode = it)
        }

    private fun start(target: ScreensaverMode, trigger: ScreensaverStartTrigger, nowMs: Long): Transition {
        shown = target
        powerOffSinceMs = nowMs
        return Transition.Started(target, trigger)
    }

    private fun stop(trigger: ScreensaverStopTrigger, nowMs: Long): Transition {
        val was = shown ?: error("nothing to stop")
        shown = null
        powerOffSinceMs = null
        lastActivityMs = nowMs
        return Transition.Stopped(was, trigger)
    }

    private fun render(wanted: ScreensaverMode) = if (wanted in supported) wanted else FALLBACK

    /**
     * RFC 3339 time of the next idle start in whole seconds, or the explicit
     * null (PROTOCOL 4.4: null while active, disabled or paused).
     */
    private fun next(nowMs: Long): String =
        deadlineMs(nowMs)?.let { Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS).toString() }
            ?: StateDelta.NO_NEXT_SCREENSAVER

    /** Add next_screensaver when it changed since it was last reported. */
    private fun StateDelta.withNext(nowMs: Long): StateDelta {
        val value = next(nowMs)
        if (value == reportedNext) return this
        reportedNext = value
        return copy(nextScreensaver = value)
    }

    companion object {
        /** The modes the app renders: P2.4's clock, black and dim, and photos (P3.8); the rest arrive in Phase 3. */
        val BUILT_MODES: Set<ScreensaverMode> =
            setOf(ScreensaverMode.CLOCK, ScreensaverMode.BLACK, ScreensaverMode.DIM, ScreensaverMode.PHOTOS)

        val FALLBACK = ScreensaverMode.CLOCK

        /** The settings schema's enum and the protocol's share their wire names. */
        fun fromSettings(mode: net.lockney.tabula.core.settings.ScreensaverMode): ScreensaverMode =
            ScreensaverMode.valueOf(mode.name.uppercase())

        /** A slot's mode, same wire names as the global mode's enum. */
        private fun slotMode(slot: ScheduleSlots): ScreensaverMode =
            ScreensaverMode.valueOf(slot.mode.name.uppercase())
    }
}
