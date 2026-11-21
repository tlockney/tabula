package net.lockney.tabula.core.screensaver

import java.time.Instant
import java.time.temporal.ChronoUnit
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.core.tlp.ScreensaverStartTrigger
import net.lockney.tabula.core.tlp.ScreensaverStopTrigger
import net.lockney.tabula.core.tlp.StateDelta

/**
 * The screensaver's idle timer and lifecycle (FEATURES F-SSV-01 to F-SSV-05,
 * F-SSV-26), free of Android so it is tested on a plain JVM. The app feeds it
 * settings, touches, `screensaver` commands, pause changes and the clock, and
 * carries out each [Outcome].
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
 *
 * Times are wall-clock epoch milliseconds, because `next_screensaver` is a
 * wall-clock date-time. Thread-safe.
 */
class ScreensaverController(
    /** Modes the app can render; anything else falls back to [FALLBACK]. */
    private val supported: Set<ScreensaverMode> = BUILT_MODES,
) {
    /** A lifecycle change for the app to carry out and report (F-SSV-26). */
    sealed interface Transition {
        data class Started(val mode: ScreensaverMode, val trigger: ScreensaverStartTrigger) : Transition

        data class Stopped(val mode: ScreensaverMode, val trigger: ScreensaverStopTrigger) : Transition
    }

    data class Outcome(val transitions: List<Transition> = emptyList(), val state: StateDelta? = null)

    private var enabled = Screensaver().enabled
    private var timeoutMs = Screensaver().idleTimeout * 1000L
    private var mode = fromSettings(Screensaver().mode)
    private var brightness = Screensaver().brightness
    private var brightnessEnabled = Screensaver().brightnessEnabled
    private var lastActivityMs: Long? = null
    private var pauses = 0
    private var shown: ScreensaverMode? = null
    private var reportedNext: String? = null
    private var reportedMode: ScreensaverMode? = null
    private var reportedBrightness: Int? = null

    /** The mode on screen, or null when no screensaver shows. */
    @get:Synchronized
    val shownMode: ScreensaverMode?
        get() = shown

    /**
     * The backlight level while the screensaver shows (F-SSV-17); black uses
     * the lowest and dim always dims (F-SSV-07). With the separate level
     * switched off (brightness_enabled, P3.2) the other modes hold nothing, so
     * the panel keeps its current level.
     */
    @get:Synchronized
    val brightnessWhileShown: Int?
        get() = when (shown) {
            null -> null
            ScreensaverMode.BLACK -> 0
            ScreensaverMode.DIM -> brightness
            else -> brightness.takeIf { brightnessEnabled }
        }

    /** Full screensaver state for registration (PROTOCOL 4.2). */
    @Synchronized
    fun current(nowMs: Long): StateDelta = StateDelta(
        screensaverActive = shown != null,
        screensaverMode = mode,
        screensaverBrightness = brightness,
        nextScreensaver = next(nowMs).also { reportedNext = it },
    ).also {
        reportedMode = mode
        reportedBrightness = brightness
    }

    /** When the idle timer fires, or null while it cannot (disabled, paused, showing). */
    @Synchronized
    fun deadlineMs(nowMs: Long): Long? {
        if (!enabled || shown != null || pauses > 0) return null
        return (lastActivityMs ?: nowMs.also { lastActivityMs = it }) + timeoutMs
    }

    @Synchronized
    fun applySettings(settings: Screensaver, nowMs: Long): Outcome {
        val wasEnabled = enabled
        val oldTimeoutMs = timeoutMs
        enabled = settings.enabled
        timeoutMs = settings.idleTimeout * 1000L
        mode = fromSettings(settings.mode)
        brightness = settings.brightness
        brightnessEnabled = settings.brightnessEnabled
        // switching on, or a new idle timeout, starts the countdown over (F-HA-11)
        if ((enabled && !wasEnabled) || timeoutMs != oldTimeoutMs) lastActivityMs = nowMs
        val transitions = if (!enabled && shown != null) listOf(stop(ScreensaverStopTrigger.COMMAND, nowMs)) else emptyList()
        return Outcome(
            transitions,
            // a delta holds only the keys that changed (PROTOCOL 4.4)
            StateDelta(
                screensaverActive = if (transitions.isNotEmpty()) false else null,
                screensaverMode = mode.takeIf { it != reportedMode }?.also { reportedMode = it },
                screensaverBrightness = brightness.takeIf { it != reportedBrightness }?.also { reportedBrightness = it },
            ).withNext(nowMs),
        )
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
        val target = render(requested ?: mode)
        val current = shown
        if (current == target) return Outcome()
        val transitions = buildList {
            if (current != null) add(stop(ScreensaverStopTrigger.COMMAND, nowMs))
            add(start(target, ScreensaverStartTrigger.COMMAND))
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

    /** The clock moved on: start the screensaver when the idle deadline has passed. */
    @Synchronized
    fun onTick(nowMs: Long): Outcome {
        val deadline = deadlineMs(nowMs) ?: return Outcome()
        if (nowMs < deadline) return Outcome()
        val started = start(render(mode), ScreensaverStartTrigger.IDLE)
        return Outcome(listOf(started), StateDelta(screensaverActive = true).withNext(nowMs))
    }

    private fun start(target: ScreensaverMode, trigger: ScreensaverStartTrigger): Transition {
        shown = target
        return Transition.Started(target, trigger)
    }

    private fun stop(trigger: ScreensaverStopTrigger, nowMs: Long): Transition {
        val was = shown ?: error("nothing to stop")
        shown = null
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
        /** The modes P2.4 renders; the others arrive in Phase 3. */
        val BUILT_MODES: Set<ScreensaverMode> = setOf(ScreensaverMode.CLOCK, ScreensaverMode.BLACK, ScreensaverMode.DIM)

        val FALLBACK = ScreensaverMode.CLOCK

        /** The settings schema's enum and the protocol's share their wire names. */
        fun fromSettings(mode: net.lockney.tabula.core.settings.ScreensaverMode): ScreensaverMode =
            ScreensaverMode.valueOf(mode.name.uppercase())
    }
}
