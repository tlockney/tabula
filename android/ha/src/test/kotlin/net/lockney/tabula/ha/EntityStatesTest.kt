package net.lockney.tabula.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** HA's compressed subscribe_entities format and the frontend's display lookups (ADR 0017). */
class EntityStatesTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val wanted = listOf("lock.front", "sensor.temp", "binary_sensor.gone")

    @Test
    fun `the initial snapshot holds every wanted entity, missing ones unavailable`() {
        val states = CompressedStates.initial(
            obj("""{"a":{"lock.front":{"s":"locked","a":{"friendly_name":"Front"},"c":"x","lc":1700000000.5},
                "sensor.temp":{"s":"21.46","a":{"unit_of_measurement":"°C"},"c":"y","lc":1700000001}}}"""),
            wanted,
        )
        assertEquals(wanted, states.keys.toList(), "the configured order is kept")
        assertEquals("locked", states.getValue("lock.front").state)
        assertEquals(1700000000.5, states.getValue("lock.front").lastChanged)
        assertEquals(EntityState.missing("binary_sensor.gone"), states.getValue("binary_sensor.gone"))
    }

    @Test
    fun `a diff applies state, changed attributes and removed attribute keys`() {
        val start = CompressedStates.initial(
            obj("""{"a":{"lock.front":{"s":"locked","a":{"friendly_name":"Front","battery":80},"c":"x","lc":1}}}"""),
            wanted,
        )
        val next = CompressedStates.apply(
            start,
            obj("""{"c":{"lock.front":{"+":{"s":"unlocked","lc":2,"c":"z","a":{"friendly_name":"Front door"}},"-":{"a":["battery"]}}}}"""),
            wanted.toSet(),
        )
        val lock = next.getValue("lock.front")
        assertEquals("unlocked", lock.state)
        assertEquals(2.0, lock.lastChanged)
        assertEquals(obj("""{"friendly_name":"Front door"}"""), lock.attributes)

        // an attribute-only change (lu, no s/lc) keeps state and last_changed
        val touched = CompressedStates.apply(next, obj("""{"c":{"lock.front":{"+":{"lu":3,"a":{"battery":50}}}}}"""), wanted.toSet())
        assertEquals("unlocked", touched.getValue("lock.front").state)
        assertEquals(2.0, touched.getValue("lock.front").lastChanged)
        assertEquals(JsonPrimitive(50), touched.getValue("lock.front").attributes["battery"])
    }

    @Test
    fun `removals become unavailable, late additions appear, strangers are ignored`() {
        val start = CompressedStates.initial(obj("""{"a":{"lock.front":{"s":"locked","a":{},"c":"x","lc":1}}}"""), wanted)
        val removed = CompressedStates.apply(start, obj("""{"r":["lock.front","light.not_mine"]}"""), wanted.toSet())
        assertTrue(removed.getValue("lock.front").missing)
        assertEquals("unavailable", removed.getValue("lock.front").state)
        assertTrue("light.not_mine" !in removed)

        val back = CompressedStates.apply(
            removed,
            obj("""{"a":{"binary_sensor.gone":{"s":"on","a":{},"c":"x","lc":5},"light.not_mine":{"s":"on","a":{},"c":"x","lc":5}}}"""),
            wanted.toSet(),
        )
        assertEquals("on", back.getValue("binary_sensor.gone").state)
        assertTrue(!back.getValue("binary_sensor.gone").missing)
        assertTrue("light.not_mine" !in back)

        // a change for an entity not held (never added) is dropped
        val stray = CompressedStates.apply(mapOf(), obj("""{"c":{"lock.front":{"+":{"s":"open"}}}}"""), wanted.toSet())
        assertTrue(stray.isEmpty())
    }

    @Test
    fun `display data falls back the way the frontend does`() {
        val display = DisplayData(
            registry = DisplayData.registryFrom(
                obj("""{"entity_categories":{},"entities":[{"ei":"sensor.temp","pl":"demo","tk":"temp","dp":1},
                    {"ei":"lock.front","pl":"zwave","ic":"mdi:door"},{"ei":"light.other","pl":"hue"}]}"""),
                wanted.toSet(),
            ),
            translations = DisplayData.translationsFrom(
                obj("""{"resources":{"component.lock.entity_component._.state.locked":"Locked",
                    "component.binary_sensor.entity_component.door.state.on":"Open",
                    "component.binary_sensor.entity_component._.state.on":"On",
                    "component.demo.entity.sensor.temp.state.hot":"Hot"}}"""),
            ),
            componentIcons = DisplayData.iconsFrom(
                obj("""{"resources":{"lock":{"_":{"default":"mdi:lock","state":{"unlocked":"mdi:lock-open"}}},
                    "binary_sensor":{"door":{"default":"mdi:door-closed","state":{"on":"mdi:door-open"}}}}}"""),
            ),
            entityIcons = DisplayData.iconsFrom(obj("""{"resources":{"demo":{"sensor":{"temp":{"default":"mdi:thermometer"}}}}}""")),
        )
        assertEquals(setOf("sensor.temp", "lock.front"), display.registry.keys, "only wanted entities are kept")
        assertEquals(1, display.precision("sensor.temp"))
        assertNull(display.precision("lock.front"))

        val lock = EntityState("lock.front", "locked")
        assertEquals("Locked", display.stateText(lock))
        assertEquals("mdi:door", display.icon(lock), "the registry's own icon wins over the domain's")

        val door = EntityState("binary_sensor.gone", "on", obj("""{"device_class":"door"}"""))
        assertEquals("Open", display.stateText(door), "device class before the domain default")
        assertEquals("mdi:door-open", display.icon(door))

        val temp = EntityState("sensor.temp", "hot")
        assertEquals("Hot", display.stateText(temp), "the entity's translation key first")
        assertEquals("mdi:thermometer", display.icon(temp))

        val unknown = EntityState("switch.x", "running", obj("""{"icon":"mdi:fan"}"""))
        assertEquals("Running", display.stateText(unknown), "untranslated states come back capitalized")
        assertEquals("mdi:fan", display.icon(unknown), "an icon attribute wins")
        assertNull(display.icon(EntityState("switch.x", "on")))
    }
}
