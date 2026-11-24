package net.lockney.tabula.core.screensaver.glance

import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.AtAGlance
import net.lockney.tabula.core.tlp.ScreensaverMode
import kotlin.math.max
import kotlin.math.min

/**
 * One entity's contribution to the At a Glance row (F-AAG-01 to F-AAG-03),
 * already formatted the way HA presents it: the feed (`GlanceFeed` in
 * `android/ha`) fills these from the live subscription and the display data.
 *
 * [value] is what the chip shows; a blank one drops the chip out until the
 * entity has a value (F-AAG-03). [icon] is the `mdi:` name HA would show, or
 * null, and [active] is the state HA's tiles light up for.
 */
data class GlanceValue(
    val entityId: String,
    val name: String,
    val value: String,
    val icon: String? = null,
    val active: Boolean = false,
)

/**
 * The glyph a chip draws (ADR 0032 item 1). HA's icons are `mdi:` names; Tabula
 * draws a curated vector set for the things the row is for — doors, locks,
 * garage, lights and the common sensors — and falls back to the entity
 * domain's initial for everything else. No icon font is bundled.
 */
enum class GlanceGlyph {
    Lock,
    Unlock,
    Door,
    Garage,
    Light,
    Power,
    Motion,
    Thermometer,
    Battery,
    Water,
    Alert,

    /** The fallback: the entity domain's initial, drawn in [GlanceChip.initial]. */
    Initial,
}

/** Where the row sits for the mode on screen (F-AAG-01, ADR 0032 item 4). */
enum class GlancePlacement {
    /** Centered on black mode. */
    Centered,

    /** Under the clock face's date line. */
    UnderDate,

    /** Near the bottom, over the modes built later (photos, website). */
    Bottom,
}

/** One chip in the row, sized and resolved for drawing. */
data class GlanceChip(
    val entityId: String,
    val name: String,
    val value: String,
    val glyph: GlanceGlyph,
    /** The fallback glyph's letter (the domain's initial); "" for a drawn glyph. */
    val initial: String,
    val active: Boolean,
)

/**
 * What the row draws for the current mode, settings and data (ADR 0032 item 5).
 *
 * [lines] holds at most two lines (one in landscape) of [GlanceChip]s in
 * display order; [textScale] is the shrink factor applied to the text after
 * fitting, and [truncateNames] says the names were ellipsized after the
 * shrink floor was reached. [omitted] counts chips the lines could not hold.
 */
data class GlanceRow(
    val visible: Boolean = false,
    val placement: GlancePlacement = GlancePlacement.UnderDate,
    val lines: List<List<GlanceChip>> = emptyList(),
    val textScale: Float = 1f,
    val truncateNames: Boolean = false,
    val night: Boolean = false,
    val omitted: Int = 0,
) {
    val chips: List<GlanceChip> get() = lines.flatten()

    companion object {
        /** The row draws nothing (no entities, a mode that does not show it, or night). */
        val HIDDEN = GlanceRow()
    }
}

/** The row's per-scale pixel metrics (ADR 0002's At a Glance constants; ADR 0032 item 2). */
data class GlanceMetrics(
    val valuePx: Float,
    val namePx: Float,
    val iconPx: Float,
    val chipPaddingPx: Float,
    val chipGapPx: Float,
    val lineGapPx: Float,
) {
    companion object {
        /**
         * The metrics for an `at_a_glance.scale` percent on a panel of
         * [density]. Sizes are sp × density × scale: the row is laid out for
         * the panel, so Android's user font scale is deliberately not applied
         * (ADR 0032 item 2).
         */
        fun of(scalePercent: Int, density: Float): GlanceMetrics {
            val scale = (scalePercent / 100f) * density
            return GlanceMetrics(
                valuePx = Constants.GLANCE_VALUE_BASE_SP * scale,
                namePx = Constants.GLANCE_NAME_BASE_SP * scale,
                iconPx = Constants.GLANCE_ICON_BASE_SP * scale,
                chipPaddingPx = Constants.GLANCE_CHIP_PADDING_SP * scale,
                chipGapPx = Constants.GLANCE_CHIP_GAP_SP * scale,
                lineGapPx = Constants.GLANCE_LINE_GAP_SP * scale,
            )
        }
    }
}

/**
 * The At a Glance row's layout (F-AAG-01 to F-AAG-03, packet P3.6; ADR 0032).
 *
 * Plain JVM: the view supplies a text [measure] (text width in px at a px
 * size) and the panel's width, so ordering, placement, wrapping, shrinking
 * and truncation are all testable without a device.
 *
 * The fitting order is FEATURES' own: the row wraps onto two lines in
 * portrait; past that the text shrinks in steps down to
 * [Constants.GLANCE_MIN_TEXT_SCALE]; only at the floor are names truncated
 * (with an ellipsis), and a row that still does not fit drops its tail, which
 * [GlanceRow.omitted] reports so the view can log it.
 */
class AtAGlanceRow(private val measure: (text: String, sizePx: Float) -> Float) {
    /**
     * The row for [values] under [settings] on [mode]. [night] is the
     * `NightState` flag; [hideAtNight] is `screensaver.night_mode`'s
     * hide-overlays option (F-SSV-10). [mode] null (nothing showing) draws
     * nothing.
     */
    fun plan(
        settings: AtAGlance,
        values: List<GlanceValue>,
        mode: ScreensaverMode?,
        night: Boolean = false,
        hideAtNight: Boolean = true,
        portrait: Boolean = false,
        widthPx: Float = 0f,
        density: Float = 1f,
    ): GlanceRow {
        val placement = placement(mode) ?: return GlanceRow.HIDDEN
        if (night && hideAtNight) return GlanceRow.HIDDEN
        val chips = chips(settings, values)
        if (chips.isEmpty() || widthPx <= 0f) return GlanceRow.HIDDEN
        val metrics = GlanceMetrics.of(settings.scale, density)
        val maxLines = if (portrait) Constants.GLANCE_MAX_LINES_PORTRAIT else Constants.GLANCE_MAX_LINES_LANDSCAPE
        // text shrinks before names are truncated (F-AAG-01)
        var scale = 1f
        while (true) {
            val lines = pack(chips, metrics, scale, widthPx, settings.hideNames)
            if (lines.size <= maxLines) {
                return GlanceRow(true, placement, lines, scale, false, night)
            }
            if (scale <= Constants.GLANCE_MIN_TEXT_SCALE) break
            scale = max(Constants.GLANCE_MIN_TEXT_SCALE, scale - Constants.GLANCE_SHRINK_STEP)
        }
        // the floor: truncate names to each chip's share of their line. With
        // names hidden there is nothing to shorten, and the flag reports only a
        // name that actually changed (the hardware check reads it)
        val share = sharePx(chips.size, maxLines, widthPx, metrics.chipGapPx)
        val truncated = if (settings.hideNames) {
            chips
        } else {
            chips.map { chip -> chip.copy(name = ellipsize(chip, metrics, share)) }
        }
        val lines = pack(truncated, metrics, Constants.GLANCE_MIN_TEXT_SCALE, widthPx, settings.hideNames)
        val kept = lines.take(maxLines)
        return GlanceRow(
            visible = kept.any { it.isNotEmpty() },
            placement = placement,
            lines = kept,
            textScale = Constants.GLANCE_MIN_TEXT_SCALE,
            truncateNames = chips.zip(truncated).any { (before, after) -> before.name != after.name },
            night = night,
            omitted = chips.size - kept.sumOf { it.size },
        )
    }

    /** Where [mode] draws the row, or null for the modes that never show it (F-AAG-01, ADR 0032 item 4). */
    fun placement(mode: ScreensaverMode?): GlancePlacement? = when (mode) {
        null, ScreensaverMode.CALENDAR, ScreensaverMode.DIM -> null
        ScreensaverMode.BLACK -> GlancePlacement.Centered
        ScreensaverMode.CLOCK -> GlancePlacement.UnderDate
        ScreensaverMode.PHOTOS, ScreensaverMode.WEBSITE, ScreensaverMode.WEATHER, ScreensaverMode.DASHBOARD ->
            GlancePlacement.Bottom
    }

    /** The chips in the settings' own order, with entities the settings do not list dropped (F-AAG-01). */
    private fun chips(settings: AtAGlance, values: List<GlanceValue>): List<GlanceChip> {
        val byId = values.associateBy { it.entityId }
        return settings.entities.mapNotNull { id ->
            val value = byId[id] ?: return@mapNotNull null
            // an entity with an empty value drops out until it has one (F-AAG-03)
            if (value.value.isBlank()) return@mapNotNull null
            val domain = id.substringBefore('.')
            val glyph = GlanceIcons.glyph(value.icon, domain)
            GlanceChip(
                entityId = id,
                name = value.name,
                value = value.value,
                glyph = glyph,
                initial = if (glyph == GlanceGlyph.Initial) GlanceIcons.initial(domain) else "",
                active = value.active,
            )
        }.take(Constants.GLANCE_MAX_CHIPS)
    }

    /** Greedy packing of [chips] at [textScale] into lines no wider than [widthPx]. */
    private fun pack(
        chips: List<GlanceChip>,
        metrics: GlanceMetrics,
        textScale: Float,
        widthPx: Float,
        hideNames: Boolean,
    ): List<List<GlanceChip>> {
        val lines = mutableListOf<MutableList<GlanceChip>>()
        var current = mutableListOf<GlanceChip>()
        var used = 0f
        for (chip in chips) {
            val width = chipWidth(chip, metrics, textScale, hideNames)
            val gap = if (current.isEmpty()) 0f else metrics.chipGapPx
            if (current.isNotEmpty() && used + gap + width > widthPx) {
                lines += current
                current = mutableListOf()
                used = 0f
            }
            if (current.isNotEmpty()) used += metrics.chipGapPx
            current += chip
            used += width
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun chipWidth(chip: GlanceChip, metrics: GlanceMetrics, textScale: Float, hideNames: Boolean): Float =
        metrics.iconPx +
            (2 * metrics.chipPaddingPx) +
            textWidth(chip, metrics, textScale, hideNames)

    private fun textWidth(chip: GlanceChip, metrics: GlanceMetrics, textScale: Float, hideNames: Boolean): Float {
        val value = measure(chip.value, metrics.valuePx * textScale)
        if (hideNames) return value
        return max(value, measure(chip.name, metrics.namePx * textScale))
    }

    /**
     * The name shortened with an ellipsis until it fits [share] px beside the
     * chip's icon and value (the value itself is never truncated).
     */
    private fun ellipsize(chip: GlanceChip, metrics: GlanceMetrics, share: Float): String {
        val room = share - metrics.iconPx - (2 * metrics.chipPaddingPx)
        val valueWidth = measure(chip.value, metrics.valuePx * Constants.GLANCE_MIN_TEXT_SCALE)
        if (measure(chip.name, metrics.namePx * Constants.GLANCE_MIN_TEXT_SCALE) <= room) return chip.name
        val budget = max(room, valueWidth)
        val ellipsis = Constants.GLANCE_ELLIPSIS
        if (measure(ellipsis, metrics.namePx * Constants.GLANCE_MIN_TEXT_SCALE) > budget) return ""
        var end = chip.name.length
        while (end > 0 && measure(chip.name.take(end) + ellipsis, metrics.namePx * Constants.GLANCE_MIN_TEXT_SCALE) > budget) {
            end -= 1
        }
        return if (end == 0) ellipsis else chip.name.take(end).trimEnd() + ellipsis
    }

    /**
     * The width one chip may take when names are truncated: the two lines'
     * combined width minus the gaps they need, shared evenly.
     */
    private fun sharePx(count: Int, maxLines: Int, widthPx: Float, gapPx: Float): Float {
        if (count <= 0) return widthPx
        val perLine = min(count, (count + maxLines - 1) / maxLines).coerceAtLeast(1)
        val lines = (count + perLine - 1) / perLine
        val gaps = gapPx * (count - lines).coerceAtLeast(0)
        return ((widthPx * lines) - gaps) / count
    }
}

/** `mdi:` names and domains to the drawn glyph set (ADR 0032 item 1). */
object GlanceIcons {
    private val byIcon: Map<String, GlanceGlyph> = mapOf(
        "lock" to GlanceGlyph.Lock,
        "lock-open" to GlanceGlyph.Unlock,
        "lock-open-variant" to GlanceGlyph.Unlock,
        "door" to GlanceGlyph.Door,
        "door-open" to GlanceGlyph.Door,
        "door-closed" to GlanceGlyph.Door,
        "door-closed-lock" to GlanceGlyph.Lock,
        "door-sliding" to GlanceGlyph.Door,
        "garage" to GlanceGlyph.Garage,
        "garage-variant" to GlanceGlyph.Garage,
        "garage-open" to GlanceGlyph.Garage,
        "garage-open-variant" to GlanceGlyph.Garage,
        "lightbulb" to GlanceGlyph.Light,
        "lightbulb-outline" to GlanceGlyph.Light,
        "lightbulb-on" to GlanceGlyph.Light,
        "ceiling-light" to GlanceGlyph.Light,
        "floor-lamp" to GlanceGlyph.Light,
        "toggle-switch" to GlanceGlyph.Power,
        "toggle-switch-outline" to GlanceGlyph.Power,
        "power" to GlanceGlyph.Power,
        "power-plug" to GlanceGlyph.Power,
        "motion-sensor" to GlanceGlyph.Motion,
        "motion-sensor-off" to GlanceGlyph.Motion,
        "walk" to GlanceGlyph.Motion,
        "account" to GlanceGlyph.Motion,
        "thermometer" to GlanceGlyph.Thermometer,
        "battery" to GlanceGlyph.Battery,
        "battery-outline" to GlanceGlyph.Battery,
        "water" to GlanceGlyph.Water,
        "water-alert" to GlanceGlyph.Water,
        "water-pump" to GlanceGlyph.Water,
        "alert" to GlanceGlyph.Alert,
        "alert-circle" to GlanceGlyph.Alert,
        "alert-outline" to GlanceGlyph.Alert,
    )

    private val byDomain: Map<String, GlanceGlyph> = mapOf(
        "lock" to GlanceGlyph.Lock,
        "light" to GlanceGlyph.Light,
        "switch" to GlanceGlyph.Power,
        "input_boolean" to GlanceGlyph.Power,
        "cover" to GlanceGlyph.Door,
        "garage_door" to GlanceGlyph.Garage,
        "fan" to GlanceGlyph.Power,
        "sensor" to GlanceGlyph.Alert,
        "binary_sensor" to GlanceGlyph.Alert,
    )

    /**
     * The glyph for [icon] (an `mdi:` name HA would show) in [domain]: the
     * name's own glyph, else the domain's, else [GlanceGlyph.Initial].
     */
    fun glyph(icon: String?, domain: String): GlanceGlyph {
        icon?.removePrefix("mdi:")?.let { byIcon[it] }?.let { return it }
        byDomain[domain]?.let { return it }
        return GlanceGlyph.Initial
    }

    /** The fallback glyph's letter: the domain's initial, uppercase. */
    fun initial(domain: String): String =
        domain.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
