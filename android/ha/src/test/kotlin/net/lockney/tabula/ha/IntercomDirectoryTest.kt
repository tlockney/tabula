package net.lockney.tabula.ha

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The intercom directory over HA's WebSocket (packet P9.4 item 1). The channel
 * is a fake: nothing here opens a socket, and the audio rule is untouched
 * because no part of the directory involves audio.
 */
class IntercomDirectoryTest {
    /** A channel that answers with [result] and remembers what it was asked. */
    private class FakeChannel(
        private val result: JsonElement? = null,
        private val fail: Exception? = null,
    ) : HaChannel {
        val asked = mutableListOf<JsonObject>()

        override suspend fun call(message: JsonObject): JsonElement? {
            asked += message
            fail?.let { throw it }
            return result
        }

        override suspend fun subscribe(
            message: JsonObject,
            onEvent: (JsonObject) -> Unit,
        ): HaSubscription = error("the directory does not subscribe")
    }

    private fun answer(vararg entries: JsonObject): JsonObject = buildJsonObject {
        putJsonArray("devices") { entries.forEach { add(it) } }
    }

    private fun entry(
        deviceId: String,
        name: String,
        areaId: String? = null,
        areaName: String? = null,
        canTalk: Boolean = true,
        inCall: Boolean = false,
        tabulaDeviceId: String? = null,
    ): JsonObject = buildJsonObject {
        put("device_id", deviceId)
        put("tabula_device_id", tabulaDeviceId)
        put("name", name)
        put("area_id", areaId)
        put("area_name", areaName)
        put("can_talk", canTalk)
        put("in_call", inCall)
    }

    @Test
    fun `the directory is asked for on the device's own connection`() = runBlocking {
        val channel = FakeChannel(
            answer(
                entry(
                    "ha-kitchen",
                    "Kitchen frame",
                    areaId = "kitchen",
                    areaName = "Kitchen",
                    tabulaDeviceId = "tlp-kitchen",
                ),
            ),
        )

        val listed = IntercomDirectory.fetch(channel)

        assertEquals(listOf(buildJsonObject { put("type", "tabula/intercom_directory") }), channel.asked)
        assertEquals(
            listOf(
                IntercomPeerEntry(
                    deviceId = "ha-kitchen",
                    tabulaDeviceId = "tlp-kitchen",
                    name = "Kitchen frame",
                    areaId = "kitchen",
                    areaName = "Kitchen",
                    canTalk = true,
                    inCall = false,
                ),
            ),
            listed,
        )
    }

    @Test
    fun `a tablet with no area keeps its own name and a null area`() = runBlocking {
        val listed = IntercomDirectory.fetch(FakeChannel(answer(entry("ha-hall", "Hall frame"))))

        assertEquals(listOf("Hall frame"), listed?.map { it.name })
        assertNull(listed?.single()?.areaId)
        assertNull(listed?.single()?.areaName)
    }

    @Test
    fun `a listen-only tablet and a busy one are reported as they are`() = runBlocking {
        val listed = IntercomDirectory.fetch(
            FakeChannel(
                answer(
                    entry("ha-study", "Study frame", "study", "Study", canTalk = false),
                    entry("ha-hall", "Hall frame", inCall = true),
                ),
            ),
        )

        assertEquals(listOf(false, true), listed?.map { it.canTalk })
        assertEquals(listOf(false, true), listed?.map { it.inCall })
    }

    @Test
    fun `nobody to call is an answer, not a failure`() = runBlocking {
        assertEquals(emptyList(), IntercomDirectory.fetch(FakeChannel(answer())))
    }

    @Test
    fun `no connection means the screen cannot say who to call`() = runBlocking {
        assertNull(IntercomDirectory.fetch(null))
    }

    @Test
    fun `a refused command leaves the list unknown rather than empty`() = runBlocking {
        val refused = FakeChannel(fail = HaCommandError("not_supported", "no intercom"))
        assertNull(IntercomDirectory.fetch(refused))

        val closed = FakeChannel(fail = IOException("connection closed"))
        assertNull(IntercomDirectory.fetch(closed))
    }

    @Test
    fun `an entry that is not a device is skipped, not invented`() = runBlocking {
        val answer = buildJsonObject {
            putJsonArray("devices") {
                add(entry("ha-kitchen", "Kitchen frame"))
                add(buildJsonObject { put("name", "no id") })
                add(kotlinx.serialization.json.JsonPrimitive("not an entry"))
            }
        }

        val listed = IntercomDirectory.fetch(FakeChannel(answer))

        assertEquals(listOf("ha-kitchen"), listed?.map { it.deviceId })
    }

    @Test
    fun `a result with no devices member is empty`() = runBlocking {
        assertTrue(IntercomDirectory.fetch(FakeChannel(buildJsonObject { }))?.isEmpty() == true)
    }
}
