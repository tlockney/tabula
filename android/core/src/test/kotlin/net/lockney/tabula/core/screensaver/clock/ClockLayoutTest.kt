package net.lockney.tabula.core.screensaver.clock

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.ScreensaverClock

/**
 * The face-layout logic against the option combinations (F-SSV-08, packet
 * P3.3): the digital pieces, the analog hands and texts, the date locale
 * fallback and the background source classification.
 */
class ClockLayoutTest {
    private fun clock(
        face: net.lockney.tabula.core.settings.ScreensaverClockFace =
            net.lockney.tabula.core.settings.ScreensaverClockFace.Digital,
        hour24: Boolean = false,
        showSeconds: Boolean = false,
        showAmPm: Boolean = true,
        showDate: Boolean = true,
        size: Int = 100,
    ) = ScreensaverClock(
        face = face,
        hour24 = hour24,
        showSeconds = showSeconds,
        showAmPm = showAmPm,
        showDate = showDate,
        size = size,
    )

    /** 2026-11-21, 3:07:42 PM, US locale: every piece is deterministic. */
    private fun afternoon() = LocalDateTime.of(2026, 11, 21, 15, 7, 42)

    @Test
    fun `12-hour with marker and date, no seconds`() {
        val face = digitalFace(afternoon(), clock(), Locale.US, Locale.US)
        assertEquals("3:07", face.time)
        assertEquals("PM", face.amPm)
        assertEquals("Saturday 21 November", face.date)
        assertEquals("sans-serif", face.fontFamily)
        assertEquals(400, face.fontWeight)
        assertEquals(Constants.CLOCK_TIME_BASE_SP, face.timeSizeSp)
        assertEquals(Constants.CLOCK_DATE_BASE_SP, face.dateSizeSp)
        assertEquals("#FFFFFFFF", face.textColor)
    }

    @Test
    fun `24-hour drops the marker`() {
        val face = digitalFace(afternoon(), clock(hour24 = true), Locale.US, Locale.US)
        assertEquals("15:07", face.time)
        assertNull(face.amPm)
        assertEquals("Saturday 21 November", face.date)
    }

    @Test
    fun `seconds append to the time`() {
        val face = digitalFace(afternoon(), clock(showSeconds = true), Locale.US, Locale.US)
        assertEquals("3:07:42", face.time)
        assertEquals("PM", face.amPm)
    }

    @Test
    fun `the marker and the date are their own options`() {
        val face = digitalFace(
            afternoon(),
            clock(showAmPm = false, showDate = false),
            Locale.US,
            Locale.US,
        )
        assertEquals("3:07", face.time)
        assertNull(face.amPm)
        assertNull(face.date)
    }

    @Test
    fun `the size percent scales both texts`() {
        val face = digitalFace(afternoon(), clock(size = 200), Locale.US, Locale.US)
        assertEquals(Constants.CLOCK_TIME_BASE_SP * 2, face.timeSizeSp)
        assertEquals(Constants.CLOCK_DATE_BASE_SP * 2, face.dateSizeSp)
        assertEquals(Constants.CLOCK_DATE_BASE_SP, face.amPmSizeSp, "the marker stays at half the date line's size")
    }

    @Test
    fun `the date follows the date locale`() {
        val french = digitalFace(afternoon(), clock(), Locale.US, Locale.FRANCE)
        assertEquals("samedi 21 novembre", french.date)
        // the time keeps the system language's digits and pattern
        assertEquals("3:07", french.time)
        assertEquals("PM", french.amPm)
    }

    @Test
    fun `the date locale prefers an app language the app ships`() {
        val system = Locale.US
        assertEquals(
            Locale.FRANCE,
            dateLocale(appLocale = Locale.FRANCE, shipped = setOf("fr", "en"), systemLocale = system),
        )
        // no translation for the app language: the system language
        assertEquals(system, dateLocale(appLocale = Locale.FRANCE, shipped = setOf("en"), systemLocale = system))
        // no app language at all: the system language
        assertEquals(system, dateLocale(appLocale = null, shipped = setOf("en"), systemLocale = system))
    }

    @Test
    fun `analog texts mirror the marker and date options`() {
        val texts = analogTexts(afternoon(), clock(), Locale.US, Locale.US)
        assertEquals("PM", texts.amPm)
        assertEquals("Saturday 21 November", texts.date)
        assertEquals(Constants.CLOCK_DATE_BASE_SP / 2, texts.amPmSizeSp)
        val hidden = analogTexts(afternoon(), clock(showAmPm = false, showDate = false), Locale.US, Locale.US)
        assertNull(hidden.amPm)
        assertNull(hidden.date)
    }

    @Test
    fun `analog hands move with the minutes and carry the seconds`() {
        val hands = analogHands(LocalTime.of(3, 30, 0), showSeconds = false)
        assertEquals(105f, hands.hour) // 3:30 -> halfway between 3 and 4
        assertEquals(180f, hands.minute)
        assertNull(hands.second)
        val withSeconds = analogHands(LocalTime.of(6, 30, 45), showSeconds = true)
        // the minute hand moves with the seconds so it never sits still
        assertEquals(30.75f * 6f, withSeconds.minute)
        assertEquals(45f * 6f, withSeconds.second)
        // 12 o'clock is zero degrees
        val noon = analogHands(LocalTime.of(12, 0, 0), showSeconds = true)
        assertEquals(0f, noon.hour)
        assertEquals(0f, noon.minute)
        assertEquals(0f, noon.second)
    }

    @Test
    fun `a background source is a URL only with an http(s) scheme`() {
        assertTrue(isUrl("http://ha.local/cam.jpg"))
        assertTrue(isUrl("https://example.com/img.png"))
        assertFalse(isUrl("/storage/emulated/0/Pictures/wall.jpg"))
        assertFalse(isUrl(""))
        assertFalse(isUrl("ftp://example.com/img.jpg"))
    }
}
