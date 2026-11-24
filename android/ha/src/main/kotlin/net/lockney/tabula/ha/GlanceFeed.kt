package net.lockney.tabula.ha

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.glance.GlanceValue

/**
 * At a Glance's entity data (F-AAG-03, packet P3.6): the live subscription's
 * states and the display data turned into the row's values, formatted the way
 * HA presents them.
 *
 * - the name is HA's `friendly_name`, else the entity id's object part with
 *   spaces instead of underscores and a capital first letter;
 * - a numeric state is rounded to HA's display precision for that entity and
 *   followed by its unit, and anything else is HA's own state text
 *   ([DisplayData.stateText]: the translated string, else the raw state with
 *   a capital first letter);
 * - an entity HA does not have shows as `Unavailable`, and a blank state stays
 *   blank, so the row drops that chip until the entity has a value;
 * - the icon is the `mdi:` name HA would show, resolved by
 *   [DisplayData.icon] and drawn by the row's curated glyph set (ADR 0032 item 1);
 * - a state HA's tiles draw as "off" (`off`, `unknown`, `unavailable`,
 *   `locked`, `closed`, and [Constants.GLANCE_INACTIVE_STATES]) is inactive:
 *   the icon box takes the inactive color, the text never changes (F-AAG-02).
 */
object GlanceFeed {
    /** The row's values for [ids], in the caller's order (the settings' order, F-AAG-01). */
    fun of(states: Map<String, EntityState>, display: DisplayData, ids: List<String>): List<GlanceValue> =
        ids.map { id ->
            val entity = states[id] ?: EntityState.missing(id)
            GlanceValue(
                entityId = id,
                name = name(entity, id),
                value = value(entity, display),
                icon = display.icon(entity),
                active = entity.state.lowercase() !in Constants.GLANCE_INACTIVE_STATES,
            )
        }

    private fun name(entity: EntityState, id: String): String =
        entity.attributes.string("friendly_name")?.takeIf { it.isNotBlank() } ?: prettify(id.substringAfter('.'))

    private fun value(entity: EntityState, display: DisplayData): String {
        val raw = entity.state
        if (raw.isBlank()) return ""
        val precision = display.precision(entity.entityId)
        val number = raw.toDoubleOrNull()
        if (number == null || precision == null) return display.stateText(entity)
        // the rounding choice (ADR 0032 item 7): round the parsed double, the way
        // HA's frontend rounds a JS number, so a text value that sits exactly
        // halfway between two decimals goes to its binary neighbour, as HA's
        // display does - "2.675" at precision 2 shows 2.67, not 2.68. Rounding
        // the decimal text instead would disagree with the HA screen next to it
        val rounded = BigDecimal(number).setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val unit = entity.attributes.string("unit_of_measurement")
        return if (unit.isNullOrBlank()) rounded else "$rounded $unit"
    }

    /** `front_door` -> `Front door` (the object id, when the entity has no name). */
    private fun prettify(objectId: String): String {
        val spaced = objectId.replace('_', ' ')
        return spaced.replaceFirstChar { it.uppercaseChar() }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content
}
