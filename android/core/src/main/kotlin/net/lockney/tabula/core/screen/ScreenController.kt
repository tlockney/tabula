package net.lockney.tabula.core.screen

import kotlin.math.abs
import kotlin.math.roundToInt
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.Screen
import net.lockney.tabula.core.tlp.StateDelta

/**
 * Panel brightness decisions (plan 5.5, FEATURES F-SCR-04 to F-SCR-08), kept
 * free of Android so the ownership rule is tested on a plain JVM. The app feeds
 * it settings, `screen` commands and light readings, and applies each
 * [Decision] to the panel.
 *
 * Brightness ownership (plan 5.5, ADR 0010 item 5, ADR 0011):
 * - While adaptive brightness is on, the curve sets the level from the light
 *   sensor, with the change threshold, minimum interval and fade of F-SCR-08.
 * - A manual level from HA (a `screen` command with `brightness`) becomes the
 *   stored base level and turns adaptive off on the device. Both keys go out
 *   in one state delta, and the changed screen settings are returned so the
 *   app can keep them across restarts.
 * - While adaptive is off, the base level holds.
 * - The reported `brightness` is always the level on the panel.
 *
 * With adaptive on and no reading yet (or no sensor), the panel holds its level.
 *
 * Thread-safe: the light sensor and the command loop call in from different
 * threads.
 */
class ScreenController(
    private val curve: BrightnessCurve = BrightnessCurve.DEFAULT,
    private val threshold: Int = Constants.ADAPTIVE_CHANGE_THRESHOLD,
    private val minIntervalMs: Long = Constants.ADAPTIVE_MIN_INTERVAL_MS,
) {
    /**
     * What to do with the panel. [level] null means leave the panel alone.
     * [retryInMs] is set when a reading moved the curve but arrived inside the
     * minimum interval: light sensors report on change only, so the app offers
     * the last reading again ([recheck]) after that delay, or a steady room
     * would never be followed.
     */
    data class Decision(
        val level: Int? = null,
        val fade: Boolean = false,
        val state: StateDelta? = null,
        val settings: Screen? = null,
        val retryInMs: Long? = null,
    )

    private var base = Screen().brightness
    private var adaptive = Screen().adaptiveBrightness
    private var keepScreenOn = Screen().keepScreenOn
    private var level: Int? = null
    private var lastLux: Double? = null
    private var lastAdaptiveAt: Long? = null
    private var settingsApplied = false

    /** The full screen state, for registration (PROTOCOL 4.2). */
    @Synchronized
    fun current(): StateDelta = StateDelta(
        brightness = level ?: base,
        adaptiveBrightness = adaptive,
        keepScreenOn = keepScreenOn,
    )

    @get:Synchronized
    val keepOn: Boolean
        get() = keepScreenOn

    /** False until settings or a manual level arrive; until then the app leaves the backlight alone (F-SCR-02). */
    @get:Synchronized
    val configured: Boolean
        get() = settingsApplied

    /** Apply the screen part of a settings document (startup, set_settings). */
    @Synchronized
    fun applySettings(screen: Screen, nowMs: Long): Decision {
        val first = !settingsApplied
        settingsApplied = true
        val wasAdaptive = adaptive
        val wasKeepOn = keepScreenOn
        base = screen.brightness
        adaptive = screen.adaptiveBrightness
        keepScreenOn = screen.keepScreenOn

        val target = when {
            !adaptive -> base
            // adaptive stays on: the curve keeps the panel; readings move it, not unrelated settings
            wasAdaptive && !first -> level ?: base
            else -> lastLux?.let(curve::levelFor) ?: level ?: base
        }
        if (adaptive && (first || !wasAdaptive)) lastAdaptiveAt = nowMs
        val changed = target != level
        level = target
        val state = StateDelta(
            brightness = if (first || changed) target else null,
            adaptiveBrightness = if (first || adaptive != wasAdaptive) adaptive else null,
            keepScreenOn = if (first || keepScreenOn != wasKeepOn) keepScreenOn else null,
        )
        return Decision(
            level = if (first || changed) target else null,
            // turning adaptive on fades to the curve; a settings change to the base level is a manual level
            fade = adaptive && !first,
            state = state.takeIf { it.isNotEmpty },
        )
    }

    /** A `screen` command's `brightness`: the manual level wins and turns adaptive off (plan 5.5). */
    @Synchronized
    fun onManualBrightness(percent: Int): Decision {
        require(percent in 0..100) { "brightness must be 0..100" }
        settingsApplied = true
        base = percent
        adaptive = false
        level = percent
        lastAdaptiveAt = null
        return Decision(
            level = percent,
            fade = false,
            state = StateDelta(brightness = percent, adaptiveBrightness = false),
            settings = Screen(brightness = percent, adaptiveBrightness = false, keepScreenOn = keepScreenOn),
        )
    }

    /** A light reading. Changes the panel only while adaptive is on, past the threshold and interval. */
    @Synchronized
    fun onLux(lux: Double, nowMs: Long): Decision {
        val firstReading = lastLux == null
        lastLux = lux
        if (!adaptive) return Decision()
        val target = curve.levelFor(lux)
        val current = level
        if (current != null && !firstReading) {
            if (abs(target - current) <= threshold) return Decision()
            val wait = lastAdaptiveAt?.let { minIntervalMs - (nowMs - it) } ?: 0L
            if (wait > 0) return Decision(retryInMs = wait)
        }
        level = target
        lastAdaptiveAt = nowMs
        return Decision(level = target, fade = current != null, state = StateDelta(brightness = target))
    }

    /** Offer the last reading again (see [Decision.retryInMs]). */
    @Synchronized
    fun recheck(nowMs: Long): Decision = lastLux?.let { lux ->
        // not a first reading any more, so threshold and interval apply
        onLux(lux, nowMs)
    } ?: Decision()

    companion object {
        /**
         * Percent to the platform's 0-255 backlight scale. 0 % maps to the
         * lowest non-zero level: on many panels 0 switches the backlight off,
         * and powering the panel off is a separate, explicit action (F-SCR-14).
         */
        fun toSystemLevel(percent: Int): Int = (percent.coerceIn(0, 100) * 255.0 / 100).roundToInt().coerceAtLeast(1)

        /** The platform's 0-255 backlight scale back to percent. */
        fun fromSystemLevel(level: Int): Int = (level.coerceIn(0, 255) * 100.0 / 255).roundToInt()

        /**
         * The intermediate levels of a fade from [from] to [to], ending at [to]
         * (F-SCR-08). At most one level per [frameMs] over [durationMs], and no
         * more levels than there are percent steps. Empty when there is nothing
         * to fade.
         */
        fun fadeSteps(
            from: Int,
            to: Int,
            durationMs: Long = Constants.ADAPTIVE_FADE_MS,
            frameMs: Long = Constants.FADE_FRAME_MS,
        ): List<Int> {
            if (from == to) return emptyList()
            val frames = (durationMs / frameMs).coerceAtLeast(1L).toInt()
            val steps = minOf(frames, abs(to - from))
            return (1..steps).map { k -> (from + (to - from) * k.toDouble() / steps).roundToInt() }
        }
    }
}
