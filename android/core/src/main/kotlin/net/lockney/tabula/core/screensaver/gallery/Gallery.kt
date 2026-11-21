package net.lockney.tabula.core.screensaver.gallery

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.random.Random
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.ScreensaverPhotosFit
import net.lockney.tabula.core.settings.ScreensaverPhotosTransition

/** How one slide changes into the next (F-SSV-12). Pan-and-zoom is for stills; every source here is stills (videos arrive with P3.9). */
enum class SlideTransition { NONE, CROSSFADE, SLIDE, ZOOM, PAN_ZOOM }

/**
 * The transition one slide advance uses (F-SSV-12). `random` picks per slide
 * from all five; determinism is injectable through [Random], the same seam
 * the playlist's shuffle uses.
 */
fun transitionFor(setting: ScreensaverPhotosTransition, random: Random): SlideTransition = when (setting) {
    ScreensaverPhotosTransition.None -> SlideTransition.NONE
    ScreensaverPhotosTransition.Crossfade -> SlideTransition.CROSSFADE
    ScreensaverPhotosTransition.Slide -> SlideTransition.SLIDE
    ScreensaverPhotosTransition.Zoom -> SlideTransition.ZOOM
    ScreensaverPhotosTransition.PanZoom -> SlideTransition.PAN_ZOOM
    ScreensaverPhotosTransition.Random -> SlideTransition.entries[random.nextInt(SlideTransition.entries.size)]
}

/** How one photo is placed on the panel (F-SSV-12). */
data class FitPlan(val kind: Kind) {
    enum class Kind {
        /** The whole photo, centered, black bars. */
        LETTERBOX,

        /** The whole photo over bars filled with a blurred, darkened enlargement of itself (smart fill, shapes differ a lot). */
        BLUR_BARS,

        /** Cover the panel, cropping (always fill, or smart fill with nearly-matching shapes). */
        CROP,
    }
}

/**
 * The fit plan for one photo (F-SSV-12). Smart fill crops only when the
 * shapes nearly match, within the tolerance chosen in ADR 0002
 * (`GALLERY_SMART_FILL_TOLERANCE`); otherwise the whole photo stays visible
 * over blurred, darkened bars.
 */
fun fitFor(setting: ScreensaverPhotosFit, photoW: Int, photoH: Int, panelW: Int, panelH: Int): FitPlan {
    if (photoW <= 0 || photoH <= 0 || panelW <= 0 || panelH <= 0) return FitPlan(FitPlan.Kind.LETTERBOX)
    return when (setting) {
        ScreensaverPhotosFit.Letterbox -> FitPlan(FitPlan.Kind.LETTERBOX)
        ScreensaverPhotosFit.Fill -> FitPlan(FitPlan.Kind.CROP)
        ScreensaverPhotosFit.SmartFill -> {
            val photo = photoW.toDouble() / photoH
            val panel = panelW.toDouble() / panelH
            if (kotlin.math.abs(photo - panel) <= Constants.GALLERY_SMART_FILL_TOLERANCE * panel) {
                FitPlan(FitPlan.Kind.CROP)
            } else {
                FitPlan(FitPlan.Kind.BLUR_BARS)
            }
        }
    }
}

/** Which edge zone a tap falls in (F-SSV-12): left steps back, right steps forward. */
enum class EdgeZone { LEFT, RIGHT }

/**
 * Classify a tap's horizontal position, as a fraction of the panel's width,
 * as an edge-zone tap (F-SSV-12). The zones are `GALLERY_EDGE_ZONE_FRACTION`
 * wide (ADR 0002); a tap outside both is a dismissal (F-SSV-04's exception).
 */
fun edgeZoneAt(xFraction: Float): EdgeZone? = when {
    xFraction < Constants.GALLERY_EDGE_ZONE_FRACTION -> EdgeZone.LEFT
    xFraction > 1f - Constants.GALLERY_EDGE_ZONE_FRACTION -> EdgeZone.RIGHT
    else -> null
}

/**
 * The slideshow's photo order (F-SSV-12): built once when the screensaver
 * starts, so new files appear the next time. Shuffle is Fisher-Yates over
 * the injectable [Random], so a test can pin the order (the app passes a
 * differently seeded random at every start).
 */
class PhotoPlaylist(entries: List<String>, shuffle: Boolean, random: Random) {
    private val entries = if (shuffle) entries.shuffled(random) else entries
    private var index = 0

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The photo at the cursor, or null when the playlist is empty. */
    fun current(): String? = entries.getOrNull(index)

    /** Move one photo forward or back, wrapping around, and return the new current photo (F-SSV-12). */
    fun step(forward: Boolean): String? {
        if (entries.isEmpty()) return null
        index = Math.floorMod(index + if (forward) 1 else -1, entries.size)
        return entries[index]
    }
}

/** The EXIF details the overlay shows (F-SSV-12): date taken and camera; no location lookup. */
data class PhotoDetails(val dateTaken: String?, val make: String?, val model: String?) {
    /**
     * The overlay's two lines — a locale-formatted date, then the camera —
     * or null when the photo carries neither (F-SSV-12).
     */
    fun format(locale: Locale): String? {
        val date = parseDate(dateTaken)?.let { DATE_SHOWN.withLocale(locale).format(it) }
        val camera = camera()
        return listOfNotNull(date, camera).joinToString("\n").ifEmpty { null }
    }

    /** "Make Model", collapsing a model that already names its make. */
    fun camera(): String? {
        val make = make?.trim().orEmpty()
        val model = model?.trim().orEmpty()
        if (model.isNotEmpty() && make.isEmpty()) return model
        if (model.isEmpty()) return make.ifEmpty { null }
        return if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
    }

    companion object {
        /** EXIF's date format: "yyyy:MM:dd HH:mm:ss" (DateTimeOriginal / DateTime). */
        private val DATE_RAW: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        /** EXIF dates carry no seconds-precision value a viewer needs; minutes read cleaner from across a room. */
        private val DATE_SHOWN: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

        /** Parse an EXIF date, or null when it is missing or malformed. */
        fun parseDate(raw: String?): LocalDateTime? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
                try {
                    LocalDateTime.parse(it, DATE_RAW)
                } catch (e: DateTimeParseException) {
                    null
                }
            }
    }
}

/** A corner of the panel (F-WGT-01). */
enum class PanelCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * The corner the photo-details overlay takes (F-SSV-12, F-WGT-02): the first
 * free one, bottom corners first (a caption reads best at the bottom), with
 * the corners widgets occupy passed in — the widget set grows in later
 * packets and the overlay moves out of its way.
 */
fun detailsCorner(occupied: Set<PanelCorner>): PanelCorner =
    listOf(PanelCorner.BOTTOM_LEFT, PanelCorner.BOTTOM_RIGHT, PanelCorner.TOP_RIGHT, PanelCorner.TOP_LEFT)
        .first { it !in occupied }
