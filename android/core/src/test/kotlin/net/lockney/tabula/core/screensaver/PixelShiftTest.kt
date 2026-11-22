package net.lockney.tabula.core.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.lockney.tabula.core.settings.ScreensaverPixelShift
import net.lockney.tabula.core.tlp.ScreensaverMode

/** The pixel-shift offsets and their bounds (F-SSV-22, ADR 0022). Distances in px. */
class PixelShiftTest {
    @Test
    fun `the offsets cycle the eight neighbors and wrap`() {
        val shift = PixelShift(enabled = true, amplitudePx = 4)
        val expected = listOf(
            PixelShift.Offset(0, -4),
            PixelShift.Offset(4, -4),
            PixelShift.Offset(4, 0),
            PixelShift.Offset(4, 4),
            PixelShift.Offset(0, 4),
            PixelShift.Offset(-4, 4),
            PixelShift.Offset(-4, 0),
            PixelShift.Offset(-4, -4),
        )
        // two full cycles, then the wrap seen from a negative step
        (0 until 16).forEach { step -> assertEquals(expected[step % 8], shift.offset(step)) }
        assertEquals(expected[0], shift.offset(-8))
        assertEquals(expected[1], shift.offset(-7), "floorMod keeps late steps inside the cycle")
    }

    @Test
    fun `every step moves content and stays within the amplitude`() {
        val shift = PixelShift(enabled = true, amplitudePx = 16)
        val seen = mutableSetOf<PixelShift.Offset>()
        (0 until 8).forEach { step ->
            val offset = shift.offset(step)
            assertTrue(offset.x in -16..16 && offset.y in -16..16, "within ±amplitude: $offset")
            assertTrue(offset.x != 0 || offset.y != 0, "no step rests on the origin: $offset")
            seen += offset
        }
        assertEquals(8, seen.size, "the cycle covers all eight neighbors")
    }

    @Test
    fun `black never shifts and a disabled shift applies nowhere`() {
        val on = PixelShift(enabled = true)
        assertTrue(on.appliesTo(ScreensaverMode.CLOCK), "clock content shifts")
        assertTrue(on.appliesTo(ScreensaverMode.PHOTOS), "photo content shifts")
        assertFalse(on.appliesTo(ScreensaverMode.BLACK), "black never shifts (F-SSV-22)")
        val off = PixelShift(enabled = false)
        ScreensaverMode.entries.forEach { mode -> assertFalse(off.appliesTo(mode), "$mode") }
    }

    @Test
    fun `interval and amplitude come from the settings`() {
        val shift = PixelShift(ScreensaverPixelShift(enabled = true, interval = 10, amplitude = 24))
        assertEquals(10_000L, shift.intervalMs, "screensaver.pixel_shift.interval, in ms")
        assertEquals(24, shift.amplitudePx)
        assertEquals(PixelShift.Offset(0, -24), shift.offset(0), "screensaver.pixel_shift.amplitude scales the cycle")
    }
}
