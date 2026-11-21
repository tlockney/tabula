package net.lockney.tabula.ha

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.screensaver.EntityNeeds

/** The screensaver's entity subscription against a scripted HA channel (F-AAG-04, ADR 0017). */
@OptIn(ExperimentalCoroutinesApi::class)
class EntityDataConnectionTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** A connection's HA side: records commands, answers display calls, refuses what it is told to. */
    private inner class FakeChannel : HaChannel {
        val sent = mutableListOf<JsonObject>()
        val open = mutableMapOf<Int, (JsonObject) -> Unit>()
        val refused = mutableMapOf<String, String>() // type -> error code
        var dead = false
        private var nextId = 1

        override suspend fun call(message: JsonObject): JsonElement? {
            if (dead) throw IOException("session ended")
            sent += message
            val type = message.getValue("type").jsonPrimitive.content
            refused[type]?.let { throw HaCommandError(it, "refused") }
            return when (type) {
                "config/entity_registry/list_for_display" ->
                    obj("""{"entity_categories":{},"entities":[{"ei":"sensor.temp","pl":"demo","dp":1}]}""")
                "frontend/get_translations" ->
                    obj("""{"resources":{"component.lock.entity_component._.state.locked":"Locked"}}""")
                "frontend/get_icons" -> obj("""{"resources":{"lock":{"_":{"default":"mdi:lock"}}}}""")
                else -> null
            }
        }

        override suspend fun subscribe(message: JsonObject, onEvent: (JsonObject) -> Unit): HaSubscription {
            call(message)
            val id = nextId++
            open[id] = onEvent
            return object : HaSubscription {
                override val id: Int = id

                override suspend fun cancel() {
                    if (open.remove(id) != null && !dead) {
                        call(obj("""{"type":"unsubscribe_events","subscription":$id}"""))
                    }
                }
            }
        }

        fun types() = sent.map { it.getValue("type").jsonPrimitive.content }

        fun push(event: String) = open.values.first()(obj(event))
    }

    private val scope = TestScope()
    private val channels = MutableStateFlow<HaChannel?>(null)
    private val logs = mutableListOf<String>()
    private val data = EntityDataConnection(channels, scope, log = { logs += it })

    private val needs = EntityNeeds(
        entityIds = listOf("lock.front", "sensor.temp"),
        forecasts = listOf(EntityNeeds.Forecast("weather.home")),
    )

    @Test
    fun `opens the explicit list, applies events, closes on stop`() {
        val ha = FakeChannel()
        channels.value = ha
        data.start(needs)
        scope.runCurrent()

        val subscribe = ha.sent.first()
        assertEquals("subscribe_entities", subscribe["type"]!!.jsonPrimitive.content)
        assertEquals("""["lock.front","sensor.temp"]""", subscribe["entity_ids"].toString())
        assertTrue(data.subscribed.value)
        assertTrue("weather/subscribe_forecast" in ha.types())
        assertEquals("Locked", data.display.value.stateText(EntityState("lock.front", "locked")))
        assertEquals(1, data.display.value.precision("sensor.temp"))

        ha.push("""{"a":{"lock.front":{"s":"locked","a":{},"c":"x","lc":1}}}""")
        assertEquals("locked", data.states.value.getValue("lock.front").state)
        assertTrue(data.states.value.getValue("sensor.temp").missing, "not in HA: unavailable")
        ha.push("""{"c":{"lock.front":{"+":{"s":"unlocked","lc":2}}}}""")
        assertEquals("unlocked", data.states.value.getValue("lock.front").state)

        val forecast = ha.open.values.last()
        forecast(obj("""{"type":"daily","forecast":[{"condition":"rainy"}]}"""))
        assertEquals(1, (data.forecasts.value.getValue(EntityNeeds.Forecast("weather.home")) as JsonArray).size)

        data.stop()
        scope.runCurrent()
        assertEquals(2, ha.types().count { it == "unsubscribe_events" }, "both subscriptions are closed")
        assertTrue(ha.open.isEmpty())
        assertFalse(data.subscribed.value)
    }

    @Test
    fun `empty needs never subscribe - HA would send every entity`() {
        val ha = FakeChannel()
        channels.value = ha
        data.start(EntityNeeds())
        data.start(EntityNeeds(forecasts = listOf(EntityNeeds.Forecast("weather.home"))))
        scope.runCurrent()
        assertTrue("subscribe_entities" !in ha.types())
        assertEquals(listOf("weather/subscribe_forecast"), ha.types())
    }

    @Test
    fun `a new connection resubscribes, and last-known data survives the gap`() {
        val first = FakeChannel()
        channels.value = first
        data.start(needs)
        scope.runCurrent()
        first.push("""{"a":{"lock.front":{"s":"locked","a":{},"c":"x","lc":1}}}""")

        // the connection drops: the TLP client withdraws its channel
        first.dead = true
        channels.value = null
        scope.runCurrent()
        assertFalse(data.subscribed.value)
        assertEquals("locked", data.states.value.getValue("lock.front").state, "last-known data stays")

        val second = FakeChannel()
        channels.value = second
        scope.runCurrent()
        assertTrue(data.subscribed.value)
        assertEquals("subscribe_entities", second.types().first())
        second.push("""{"a":{"lock.front":{"s":"unlocked","a":{},"c":"x","lc":3}}}""")
        assertEquals("unlocked", data.states.value.getValue("lock.front").state)
        assertTrue("unsubscribe_events" !in first.types(), "nothing is sent on a dead connection")
    }

    @Test
    fun `refusals are not fatal`() {
        val ha = FakeChannel()
        ha.refused["weather/subscribe_forecast"] = "forecast_not_supported"
        ha.refused["frontend/get_icons"] = "unknown_command"
        channels.value = ha
        data.start(needs)
        scope.runCurrent()
        assertTrue(data.subscribed.value, "the entity subscription stands")
        assertTrue(data.forecasts.value.isEmpty())
        assertTrue(logs.any { "forecast_not_supported" in it })
        assertTrue(logs.any { "display data unavailable" in it })

        // the entity subscription itself refused: wait for the next connection, no crash
        val refusing = FakeChannel().apply { refused["subscribe_entities"] = "unauthorized" }
        channels.value = refusing
        scope.runCurrent()
        assertFalse(data.subscribed.value)
    }

    @Test
    fun `changing needs re-targets, and the same needs are a no-op`() {
        val ha = FakeChannel()
        channels.value = ha
        data.start(needs)
        scope.runCurrent()
        data.start(needs)
        scope.runCurrent()
        assertEquals(1, ha.types().count { it == "subscribe_entities" })

        ha.push("""{"a":{"lock.front":{"s":"locked","a":{},"c":"x","lc":1}}}""")
        data.start(EntityNeeds(listOf("lock.front")))
        scope.runCurrent()
        assertEquals(2, ha.types().count { it == "subscribe_entities" })
        assertEquals("""["lock.front"]""", ha.sent.last { it["type"]!!.jsonPrimitive.content == "subscribe_entities" }["entity_ids"].toString())
        assertEquals(setOf("lock.front"), data.states.value.keys, "data for unwanted entities is dropped")
    }
}
