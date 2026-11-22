package net.lockney.tabula.core.screensaver

import net.lockney.tabula.core.settings.ScreensaverPixelShift
import net.lockney.tabula.core.tlp.ScreensaverMode

/**
 * Pixel shift (F-SSV-22): the periodic small offsets the screensaver's
 * content is moved by, so no area of the panel carries the same picture for
 * hours on end.
 *
 * The offsets cycle through the eight positions one amplitude away from the
 * resting position, clockwise from the top, and wrap back to the first. The
 * walk never drifts (retention relief needs no bookkeeping), every step moves
 * content, and each axis stays within ±amplitude of rest, so content that
 * fills the panel can be oversized once by the amplitude and no shift ever
 * bares the panel's edge (ADR 0022).
 *
 * Black mode never shifts (F-SSV-22); dim mode draws nothing, so a view with
 * no content simply has nothing to move. Bounds, defaults and the interval
 * are the settings' own (ADR 0002, schema v2).
 */
class PixelShift(
    /** Whether the user asked for shifting (screensaver.pixel_shift.enabled). */
    private val enabled: Boolean = false,
    /** Seconds between shifts (screensaver.pixel_shift.interval). */
    intervalS: Int = 300,
    /** Shift distance, pixels on each axis (screensaver.pixel_shift.amplitude). */
    val amplitudePx: Int = 4,
) {
    constructor(settings: ScreensaverPixelShift) : this(settings.enabled, settings.interval, settings.amplitude)

    /** Milliseconds between two shifts. */
    val intervalMs: Long = intervalS * 1000L

    /** Whether the content of [mode] shifts; black never does (F-SSV-22). */
    fun appliesTo(mode: ScreensaverMode): Boolean = enabled && mode != ScreensaverMode.BLACK

    /** The offset of the [step]-th shift; the cycle wraps after eight steps. */
    fun offset(step: Int): Offset {
        val (dx, dy) = CYCLE[Math.floorMod(step, CYCLE.size)]
        return Offset(dx * amplitudePx, dy * amplitudePx)
    }

    /** A content offset, pixels from the resting position. */
    data class Offset(val x: Int, val y: Int)

    private companion object {
        /** The eight neighbors of rest, clockwise from the top, as unit steps. */
        val CYCLE = listOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)
    }
}
