package net.lockney.tabula.core.screensaver.clock

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.ScreensaverClock

/**
 * The clock face's layout logic (F-SSV-08, packet P3.3), free of Android so
 * it is tested on a plain JVM. The view feeds it the time and the v2 clock
 * settings; it answers the pieces to draw and the sizes to draw them at.
 *
 * - Digital: the time string (12- or 24-hour, seconds when asked), the AM/PM
 *   marker (12-hour only, its own option), and the date line — weekday, month
 *   and day, no year (a wall clock knows the year; ADR 0018) — in the date
 *   locale (F-SSV-08: app language, system language when the app carries no
 *   translation for it).
 * - Analog: a dial is 12-hour by nature, so `hour_24` does not apply; the
 *   AM/PM marker and the date line are drawn under the dial, and `seconds`
 *   is the second hand (ADR 0018).
 *
 * Font family and weight, the size percent (against the mode's base size,
 * ADR 0002) and the text color pass through unchanged; the view resolves
 * them.
 */

/** The digital face: the strings to draw plus the resolved typography (F-SSV-08). */
data class DigitalFace(
    /** The time, with seconds when the settings ask for them. */
    val time: String,
    /** The AM/PM marker, or null while hidden (24-hour time or the option is off). */
    val amPm: String?,
    /** The date line, or null while the option is off. */
    val date: String?,
    /** System font family (F-SSV-08). */
    val fontFamily: String,
    /** Android's 100-900 weight axis (F-SSV-08). */
    val fontWeight: Int,
    /** Time text size in sp at the configured size percent. */
    val timeSizeSp: Float,
    /** Date text size in sp at the configured size percent. */
    val dateSizeSp: Float,
    /** Text color, `#RRGGBB` or `#AARRGGBB` (F-SSV-08). */
    val textColor: String,
)

/** The analog face's texts (F-SSV-08): what draws under the dial. */
data class AnalogTexts(
    /** The date line, or null while the option is off. */
    val date: String?,
    /** The AM/PM marker (a dial is always 12-hour), or null while hidden. */
    val amPm: String?,
)

/** The analog hands' angles, in degrees clockwise from 12 o'clock (F-SSV-08). */
data class AnalogHands(
    val hour: Float,
    val minute: Float,
    /** The second hand, or null while `show_seconds` is off. */
    val second: Float?,
)

/**
 * The locale the date line uses (F-SSV-08): the app language when the app
 * ships a translation for it, the system language otherwise. [shipped]
 * is the set of language codes the app has resources for; the view reads it
 * from its assets' locale list, so no hand-kept list can drift.
 */
fun dateLocale(appLocale: Locale?, shipped: Set<String>, systemLocale: Locale): Locale =
    appLocale?.takeIf { it.language.isNotBlank() && it.language in shipped } ?: systemLocale

/** Whether a background source is a URL rather than a local file path (F-SSV-09). */
fun isUrl(source: String): Boolean =
    source.startsWith("http://") || source.startsWith("https://")

/**
 * The digital face for one moment (F-SSV-08): the option combination decides
 * the time pattern (`HH:mm`/`h:mm`, `:ss` appended when seconds are on), the
 * marker and the date. [timeLanguage] formats the time, [dateLanguage] the
 * date (app language with system fallback).
 */
fun digitalFace(
    now: LocalDateTime,
    settings: ScreensaverClock,
    timeLanguage: Locale,
    dateLanguage: Locale,
): DigitalFace {
    val pattern = buildString {
        append(if (settings.hour24) "HH:mm" else "h:mm")
        if (settings.showSeconds) append(":ss")
    }
    val stamp = Date.from(now.atZone(java.time.ZoneId.systemDefault()).toInstant())
    val time = SimpleDateFormat(pattern, timeLanguage).format(stamp)
    val amPm =
        if (!settings.hour24 && settings.showAmPm) SimpleDateFormat("a", timeLanguage).format(stamp) else null
    return DigitalFace(
        time = time,
        amPm = amPm,
        date = if (settings.showDate) dateLine(now, dateLanguage) else null,
        fontFamily = settings.fontFamily,
        fontWeight = settings.fontWeight,
        timeSizeSp = Constants.CLOCK_TIME_BASE_SP * settings.size / 100f,
        dateSizeSp = Constants.CLOCK_DATE_BASE_SP * settings.size / 100f,
        textColor = settings.textColor,
    )
}

/** The analog face's texts for one moment (F-SSV-08). */
fun analogTexts(now: LocalDateTime, settings: ScreensaverClock, dateLanguage: Locale): AnalogTexts =
    AnalogTexts(
        date = if (settings.showDate) dateLine(now, dateLanguage) else null,
        amPm = if (settings.showAmPm) {
            val stamp = Date.from(now.atZone(java.time.ZoneId.systemDefault()).toInstant())
            SimpleDateFormat("a", Locale.getDefault()).format(stamp)
        } else {
            null
        },
    )

/**
 * The analog hands' angles for one moment (F-SSV-08): the minute hand moves
 * with the seconds so it never sits still, the second hand exists only when
 * `show_seconds` is on.
 */
fun analogHands(time: LocalTime, showSeconds: Boolean): AnalogHands {
    val minute = time.minute + time.second / 60f
    return AnalogHands(
        hour = (time.hour % 12 + minute / 60f) * 30f,
        minute = minute * 6f,
        second = if (showSeconds) time.second * 6f else null,
    )
}

/** The date line: full weekday, month and day, no year (ADR 0018). */
internal fun dateLine(now: LocalDateTime, locale: Locale): String =
    DATE_LINE.withLocale(locale).format(now)

private val DATE_LINE = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")
