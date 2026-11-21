package net.lockney.tabula.ha

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.lockney.tabula.core.screensaver.EntityNeeds

/**
 * The screensaver's HA entity data (F-AAG-04): an explicit-list
 * `subscribe_entities` on the device's own HA connection, opened while the
 * screensaver shows and closed when it is dismissed. At a Glance (P3.6), the
 * weather scene (P3.7) and the corner widgets (P3.11) read [states],
 * [forecasts] and [display].
 *
 * It rides the TLP socket (ADR 0017): [channels] is the TLP client's
 * per-connection [HaChannel]. A reconnect is the TLP client's (bounded
 * backoff, PROTOCOL 7); when a new connection registers while this is open,
 * the subscriptions are made again on it. While no connection exists the
 * consumers keep the last-known data - nothing here ever blocks the
 * screensaver.
 *
 * An empty entity list subscribes to nothing: HA's `subscribe_entities`
 * without `entity_ids` means every entity.
 */
class EntityDataConnection(
    private val channels: StateFlow<HaChannel?>,
    private val scope: CoroutineScope,
    private val language: () -> String = { "en" },
    private val log: (String) -> Unit = {},
) {
    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    private val _forecasts = MutableStateFlow<Map<EntityNeeds.Forecast, JsonArray>>(emptyMap())
    private val _display = MutableStateFlow(DisplayData())
    private val _subscribed = MutableStateFlow(false)

    /** Entity id -> its latest state, in the spec's order; missing entities included. */
    val states: StateFlow<Map<String, EntityState>> = _states.asStateFlow()

    /** The latest forecast list per request (HA's own forecast objects). */
    val forecasts: StateFlow<Map<EntityNeeds.Forecast, JsonArray>> = _forecasts.asStateFlow()

    /** Icons, translated states and display precision (F-AAG-03). */
    val display: StateFlow<DisplayData> = _display.asStateFlow()

    /** True while the entity subscription is live on a connection. */
    val subscribed: StateFlow<Boolean> = _subscribed.asStateFlow()

    private var job: Job? = null
    private var spec: EntityNeeds? = null

    /** Open (or re-target) the subscriptions; a no-op for what is already open, [stop] for empty needs. */
    @Synchronized
    fun start(spec: EntityNeeds) {
        if (spec == this.spec && job?.isActive == true) return
        stopLocked()
        if (spec.isEmpty) return
        this.spec = spec
        // keep last-known data only for what is still wanted
        _states.value = _states.value.filterKeys { it in spec.entityIds }
        _forecasts.value = _forecasts.value.filterKeys { it in spec.forecasts }
        job = scope.launch {
            channels.collectLatest { channel ->
                if (channel != null) serve(channel, spec)
            }
        }
    }

    /** Close everything (the screensaver was dismissed). */
    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        job?.cancel()
        job = null
        spec = null
    }

    /** All subscriptions for [spec] on one connection, until it or this is closed. */
    private suspend fun serve(channel: HaChannel, spec: EntityNeeds) {
        val open = mutableListOf<HaSubscription>()
        try {
            if (spec.entityIds.isNotEmpty()) {
                val wanted = spec.entityIds
                val wantedSet = wanted.toSet()
                var initialized = false
                open += channel.subscribe(
                    buildJsonObject {
                        put("type", "subscribe_entities")
                        putJsonArray("entity_ids") { wanted.forEach { add(JsonPrimitive(it)) } }
                    },
                ) { event ->
                    _states.value = if (!initialized) {
                        initialized = true
                        CompressedStates.initial(event, wanted)
                    } else {
                        CompressedStates.apply(_states.value, event, wantedSet)
                    }
                }
                _subscribed.value = true
                log("entity data: subscribed to ${wanted.size} entities")
            }
            for (request in spec.forecasts) {
                try {
                    open += channel.subscribe(
                        buildJsonObject {
                            put("type", "weather/subscribe_forecast")
                            put("entity_id", request.entityId)
                            put("forecast_type", request.type)
                        },
                    ) { event ->
                        val list = event["forecast"] as? JsonArray ?: return@subscribe
                        _forecasts.value = _forecasts.value + (request to list)
                    }
                } catch (e: HaCommandError) {
                    // forecast_not_supported, invalid_entity_id: the widget shows nothing
                    log("entity data: forecast ${request.entityId}/${request.type} refused: ${e.code}")
                }
            }
            // display data last: live states and forecasts come first
            if (spec.entityIds.isNotEmpty()) fetchDisplay(channel, spec.entityIds)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the connection ended or HA refused; keep last-known data and wait
            // for the next connection (the TLP client reconnects with backoff)
            log("entity data: subscription ended: ${e.javaClass.simpleName}")
        } finally {
            _subscribed.value = false
            // unsubscribe only on a connection that is still live; a dropped
            // one took its subscriptions with it
            if (channels.value === channel) {
                withContext(NonCancellable) {
                    for (subscription in open) subscription.cancel()
                }
            }
            if (open.isNotEmpty()) log("entity data: closed")
        }
    }

    /** F-AAG-03's display data; failures leave the previous data in place. */
    private suspend fun fetchDisplay(channel: HaChannel, wanted: List<String>) {
        val wantedSet = wanted.toSet()
        val domains = wanted.map { it.substringBefore('.') }.distinct()
        try {
            val registry = DisplayData.registryFrom(
                channel.call(buildJsonObject { put("type", "config/entity_registry/list_for_display") }) as? JsonObject,
                wantedSet,
            )
            val platforms = registry.values.mapNotNull { it.platform }.distinct()
            val lang = language()
            val translations = DisplayData.translationsFrom(translations(channel, lang, "entity_component", domains)) +
                if (platforms.isEmpty()) emptyMap() else DisplayData.translationsFrom(translations(channel, lang, "entity", platforms))
            val componentIcons = DisplayData.iconsFrom(icons(channel, "entity_component", domains))
            val entityIcons = if (platforms.isEmpty()) JsonObject(emptyMap()) else DisplayData.iconsFrom(icons(channel, "entity", platforms))
            _display.value = DisplayData(registry, translations, componentIcons, entityIcons)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("entity data: display data unavailable: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun translations(channel: HaChannel, language: String, category: String, integrations: List<String>) =
        channel.call(
            buildJsonObject {
                put("type", "frontend/get_translations")
                put("language", language)
                put("category", category)
                putJsonArray("integration") { integrations.forEach { add(JsonPrimitive(it)) } }
            },
        ) as? JsonObject

    private suspend fun icons(channel: HaChannel, category: String, integrations: List<String>) =
        channel.call(
            buildJsonObject {
                put("type", "frontend/get_icons")
                put("category", category)
                putJsonArray("integration") { integrations.forEach { add(JsonPrimitive(it)) } }
            },
        ) as? JsonObject
}
