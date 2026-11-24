package net.lockney.tabula.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The At a Glance feed's formatting (F-AAG-03, packet P3.6): names, HA's state
 * text, numeric rounding with the unit, the drop-out, unknown/unavailable and
 * the icon resolution.
 */
class GlanceFeedTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val display = DisplayData(
        registry = mapOf(
            "sensor.temp" to DisplayData.RegistryDisplay(platform = "mqtt", precision = 1),
            "lock.front" to DisplayData.RegistryDisplay(icon = "mdi:lock"),
        ),
        translations = mapOf(
            "component.lock.entity_component._.state.locked" to "Locked",
            "component.lock.entity_component._.state.unlocked" to "Unlocked",
            "component.binary_sensor.entity_component.door.state.on" to "Open",
            "component.binary_sensor.entity_component.door.state.off" to "Closed",
        ),
    )

    private fun state(id: String, value: String, attributes: String = "{}") =
        EntityState(entityId = id, state = value, attributes = obj(attributes))

    @Test
    fun `values follow the requested order and take HA's own state text`() {
        val states = mapOf(
            "lock.front" to state("lock.front", "unlocked", """{"friendly_name":"Front Door"}"""),
            "binary_sensor.garage" to state("binary_sensor.garage", "on", """{"friendly_name":"Garage Door","device_class":"door"}"""),
            "binary_sensor.window" to state("binary_sensor.window", "off", """{"device_class":"door"}"""),
        )
        val values = GlanceFeed.of(states, display, listOf("binary_sensor.garage", "lock.front", "binary_sensor.window"))
        assertEquals(listOf("binary_sensor.garage", "lock.front", "binary_sensor.window"), values.map { it.entityId })
        assertEquals("Garage Door", values[0].name)
        assertEquals("Open", values[0].value)
        assertEquals("Unlocked", values[1].value)
        // no friendly_name: the object id, spaced and capitalized
        assertEquals("Window", values[2].name)
        assertEquals("Closed", values[2].value)
    }

    @Test
    fun `a numeric state is rounded to HA's display precision and carries its unit`() {
        val states = mapOf(
            "sensor.temp" to state("sensor.temp", "21.46", """{"friendly_name":"Temperature","unit_of_measurement":"°C"}"""),
            "sensor.hum" to state("sensor.hum", "47.5", """{"friendly_name":"Humidity","unit_of_measurement":"%"}"""),
            "sensor.count" to state("sensor.count", "3", """{"friendly_name":"Count"}"""),
        )
        val values = GlanceFeed.of(states, display, listOf("sensor.temp", "sensor.hum", "sensor.count"))
        assertEquals("21.5 °C", values[0].value)
        // no precision for these: HA's own presentation, which is the raw state
        assertEquals("47.5", values[1].value)
        assertEquals("3", values[2].value)
    }

    @Test
    fun `a blank value drops out and a missing or unavailable entity still shows`() {
        val states = mapOf(
            "sensor.temp" to state("sensor.temp", ""),
            "lock.front" to EntityState.missing("lock.front"),
            "light.hall" to state("light.hall", "unknown", """{"friendly_name":"Hall Light"}"""),
        )
        val values = GlanceFeed.of(states, display, listOf("sensor.temp", "lock.front", "light.hall", "light.gone"))
        assertEquals("", values[0].value)
        assertEquals("Unavailable", values[1].value)
        assertEquals("Unknown", values[2].value)
        // never subscribed data at all: still a row entry, as unavailable
        assertEquals("Unavailable", values[3].value)
        assertFalse(values[3].active)
    }

    @Test
    fun `active follows the states HA's tiles draw as off`() {
        val states = mapOf(
            "lock.front" to state("lock.front", "locked"),
            "lock.back" to state("lock.back", "unlocked"),
            "binary_sensor.door" to state("binary_sensor.door", "on"),
            "binary_sensor.window" to state("binary_sensor.window", "off"),
            "cover.garage" to state("cover.garage", "open"),
            "light.hall" to state("light.hall", "on"),
            "sensor.temp" to state("sensor.temp", "21.5"),
            "switch.fan" to state("switch.fan", "unavailable"),
        )
        val values = GlanceFeed.of(
            states,
            display,
            listOf("lock.front", "lock.back", "binary_sensor.door", "binary_sensor.window", "cover.garage", "light.hall", "sensor.temp", "switch.fan"),
        )
        assertEquals(
            listOf(false, true, true, false, true, true, true, false),
            values.map { it.active },
        )
    }

    @Test
    fun `the icon is the one HA would show, or null for the domain's fallback`() {
        val registry = display.copy(
            registry = mapOf("sensor.plain" to DisplayData.RegistryDisplay()),
            componentIcons = obj("""{"lock":{"_":{"default":"mdi:lock-outline"}}}"""),
        )
        val states = mapOf(
            "lock.front" to state("lock.front", "locked", """{"friendly_name":"Front"}"""),
            "lock.side" to state("lock.side", "locked", """{"icon":"mdi:door"}"""),
            "sensor.plain" to state("sensor.plain", "1"),
        )
        val values = GlanceFeed.of(states, registry, listOf("lock.front", "lock.side", "sensor.plain"))
        // the component icons' default for the domain
        assertEquals("mdi:lock-outline", values[0].icon)
        // an icon set on the entity wins
        assertEquals("mdi:door", values[1].icon)
        // nothing anywhere: the row falls back to the domain's initial
        assertNull(values[2].icon)
        assertTrue(values[2].value.isNotEmpty())
    }
}
