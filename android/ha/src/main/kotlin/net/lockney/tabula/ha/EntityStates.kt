package net.lockney.tabula.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * One HA entity as the screensaver overlays see it (F-AAG-03).
 *
 * [missing] marks an entity HA does not have (never added, or removed while
 * subscribed). It still shows, as `unavailable`, so a configured chip never
 * silently disappears (F-AAG-03).
 */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val lastChanged: Double? = null,
    val missing: Boolean = false,
) {
    companion object {
        const val UNAVAILABLE = "unavailable"

        fun missing(entityId: String) = EntityState(entityId, UNAVAILABLE, missing = true)
    }
}

/**
 * HA's compressed `subscribe_entities` event format, applied to a state map.
 *
 * As HA core's websocket_api sends it (Apache-2.0; ADR 0017):
 * - `a`: entity id -> full compressed state `{s, a, c, lc, lu?}`, sent for the
 *   initial snapshot and for entities that appear later;
 * - `c`: entity id -> diff `{"+": {s?, a?, c?, lc?, lu?}, "-": {a: [keys]}}`,
 *   where `+.a` holds only the attributes that were added or changed;
 * - `r`: entity ids that were removed.
 *
 * The context (`c` inside a state) and `lu` are not needed by any overlay and
 * are ignored.
 */
object CompressedStates {
    /**
     * [current] with [event] applied, restricted to [wanted]. A removed entity
     * becomes [EntityState.missing]; a change for an entity not held is
     * dropped (HA only sends changes for entities it has already added).
     */
    fun apply(current: Map<String, EntityState>, event: JsonObject, wanted: Set<String>): Map<String, EntityState> {
        val next = current.toMutableMap()
        (event["a"] as? JsonObject)?.forEach { (entityId, value) ->
            if (entityId !in wanted) return@forEach
            val compressed = value as? JsonObject ?: return@forEach
            next[entityId] = EntityState(
                entityId = entityId,
                state = compressed.string("s") ?: EntityState.UNAVAILABLE,
                attributes = compressed["a"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = compressed.double("lc"),
            )
        }
        (event["c"] as? JsonObject)?.forEach { (entityId, value) ->
            val held = next[entityId] ?: return@forEach
            val diff = value as? JsonObject ?: return@forEach
            next[entityId] = applyDiff(held, diff)
        }
        (event["r"] as? JsonArray)?.forEach { element ->
            val entityId = (element as? JsonPrimitive)?.content ?: return@forEach
            if (entityId in wanted) next[entityId] = EntityState.missing(entityId)
        }
        return next
    }

    /** The initial snapshot: every wanted entity HA did not send is missing. */
    fun initial(event: JsonObject, wanted: List<String>): Map<String, EntityState> {
        val added = apply(emptyMap(), event, wanted.toSet())
        return wanted.associateWith { added[it] ?: EntityState.missing(it) }
    }

    private fun applyDiff(held: EntityState, diff: JsonObject): EntityState {
        val plus = diff["+"] as? JsonObject
        val minus = diff["-"] as? JsonObject
        val attributes = held.attributes.toMutableMap()
        (plus?.get("a") as? JsonObject)?.let { attributes.putAll(it) }
        (minus?.get("a") as? JsonArray)?.forEach { key ->
            (key as? JsonPrimitive)?.content?.let(attributes::remove)
        }
        return held.copy(
            state = plus?.string("s") ?: held.state,
            attributes = JsonObject(attributes),
            lastChanged = plus?.double("lc") ?: held.lastChanged,
            missing = false,
        )
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
}

/**
 * What HA's frontend uses to present a state, fetched once per subscription
 * (F-AAG-03; the calls and their non-admin access are in ADR 0017):
 *
 * - [registry]: `config/entity_registry/list_for_display` entries for the
 *   subscribed entities: platform (`pl`), icon override (`ic`), translation
 *   key (`tk`) and sensor display precision (`dp`);
 * - [translations]: flattened `frontend/get_translations` strings for the
 *   `entity_component` and `entity` categories
 *   (`component.<domain>.entity_component.<device_class|_>.state.<state>`,
 *   `component.<platform>.entity.<domain>.<translation_key>.state.<state>`);
 * - [componentIcons] / [entityIcons]: `frontend/get_icons` resources for the
 *   same two categories (`<domain>.<device_class|_>.{default,state}` and
 *   `<platform>.<domain>.<translation_key>.{default,state}`).
 *
 * Lookups fall back the way the frontend does: the entity's own translation,
 * then its device class, then the domain; with nothing found the raw state
 * (capitalized) and no icon - the renderer's fallback (P3.6) applies.
 */
data class DisplayData(
    val registry: Map<String, RegistryDisplay> = emptyMap(),
    val translations: Map<String, String> = emptyMap(),
    val componentIcons: JsonObject = JsonObject(emptyMap()),
    val entityIcons: JsonObject = JsonObject(emptyMap()),
) {
    data class RegistryDisplay(
        val platform: String? = null,
        val icon: String? = null,
        val translationKey: String? = null,
        val precision: Int? = null,
    )

    /** The sensor's display precision (digits after the point), if HA has one. */
    fun precision(entityId: String): Int? = registry[entityId]?.precision

    /** The state as HA presents it; unknown strings come back capitalized. */
    fun stateText(entity: EntityState): String {
        val domain = entity.entityId.substringBefore('.')
        val entry = registry[entity.entityId]
        val deviceClass = entity.attributes.string("device_class")
        val candidates = buildList {
            if (entry?.platform != null && entry.translationKey != null) {
                add("component.${entry.platform}.entity.$domain.${entry.translationKey}.state.${entity.state}")
            }
            if (deviceClass != null) add("component.$domain.entity_component.$deviceClass.state.${entity.state}")
            add("component.$domain.entity_component._.state.${entity.state}")
        }
        return candidates.firstNotNullOfOrNull { translations[it] }
            ?: entity.state.replaceFirstChar { it.uppercase() }
    }

    /** The `mdi:` icon HA would show, or null (the renderer's fallback applies). */
    fun icon(entity: EntityState): String? {
        entity.attributes.string("icon")?.let { return it }
        val entry = registry[entity.entityId]
        entry?.icon?.let { return it }
        val domain = entity.entityId.substringBefore('.')
        val sources = buildList {
            if (entry?.platform != null && entry.translationKey != null) {
                add(entityIcons.obj(entry.platform)?.obj(domain)?.obj(entry.translationKey))
            }
            entity.attributes.string("device_class")?.let { add(componentIcons.obj(domain)?.obj(it)) }
            add(componentIcons.obj(domain)?.obj("_"))
        }.filterNotNull()
        for (source in sources) {
            source.obj("state")?.string(entity.state)?.let { return it }
            source.string("default")?.let { return it }
        }
        return null
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

    companion object {
        /** Parse a `list_for_display` result, keeping only [wanted] entities. */
        fun registryFrom(result: JsonObject?, wanted: Set<String>): Map<String, RegistryDisplay> {
            val entities = result?.get("entities") as? JsonArray ?: return emptyMap()
            return entities.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val id = (entry["ei"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                if (id !in wanted) return@mapNotNull null
                id to RegistryDisplay(
                    platform = (entry["pl"] as? JsonPrimitive)?.content,
                    icon = (entry["ic"] as? JsonPrimitive)?.content,
                    translationKey = (entry["tk"] as? JsonPrimitive)?.content,
                    precision = (entry["dp"] as? JsonPrimitive)?.content?.toIntOrNull(),
                )
            }.toMap()
        }

        /** Parse a `get_translations` result's flat `resources` map. */
        fun translationsFrom(result: JsonObject?): Map<String, String> {
            val resources = result?.get("resources") as? JsonObject ?: return emptyMap()
            return resources.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
            }.toMap()
        }

        /** A `get_icons` result's `resources` object. */
        fun iconsFrom(result: JsonObject?): JsonObject =
            result?.get("resources") as? JsonObject ?: JsonObject(emptyMap())
    }
}
