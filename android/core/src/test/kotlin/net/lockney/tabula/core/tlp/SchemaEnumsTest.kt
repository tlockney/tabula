package net.lockney.tabula.core.tlp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.TestRepo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every hand-written enum's wire names against the schema's enums.
 *
 * The codec has no generator (P9.1's Result, deviation 1), so nothing but this
 * test stops an enum from drifting: `DismissReason` briefly gained a `busy`
 * value the schema's `notification_dismissed` data does not admit, which the
 * decoder would have accepted while any frame it built was invalid.
 */
class SchemaEnumsTest {
    private val schema = Json.parseToJsonElement(
        File(TestRepo.root(), "schema/tlp-v1.schema.json").readText(),
    ).jsonObject

    private fun definition(name: String) = schema.getValue("\$defs").jsonObject.getValue(name).jsonObject

    private fun enumOf(node: kotlinx.serialization.json.JsonObject, vararg path: String) =
        path.fold(node) { at, key -> at.getValue(key).jsonObject }.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }

    private fun <E> wireNames(serializer: KSerializer<E>, values: List<E>): List<String> =
        values.map { Tlp.strict.encodeToJsonElement(serializer, it).jsonPrimitive.content }

    private fun <E> assertMatches(serializer: KSerializer<E>, values: List<E>, expected: List<String>) {
        assertEquals(expected.sorted(), wireNames(serializer, values).sorted())
    }

    /** One event's `data.reason` enum, from the schema's per-event allOf block. */
    private fun eventDataEnum(event: String, prop: String): List<String> {
        val blocks = schema.getValue("\$defs").jsonObject.getValue("event").jsonObject
            .getValue("allOf").jsonArray
        val block = blocks.first {
            it.jsonObject.getValue("if").jsonObject.getValue("properties").jsonObject
                .getValue("event").jsonObject.getValue("const").jsonPrimitive.content == event
        }
        return enumOf(
            block.jsonObject.getValue("then").jsonObject
                .getValue("properties").jsonObject
                .getValue("data").jsonObject,
            "properties",
            prop,
        )
    }

    @Test
    fun `capability names match the schema`() {
        assertMatches(
            Capability.serializer(),
            enumValues<Capability>().toList(),
            enumOf(definition("capability")),
        )
    }

    @Test
    fun `event names match the schema`() {
        assertMatches(
            EventName.serializer(),
            enumValues<EventName>().toList(),
            enumOf(definition("event"), "properties", "event"),
        )
    }

    @Test
    fun `intercom enums match the schema`() {
        assertMatches(
            IntercomState.serializer(),
            enumValues<IntercomState>().toList(),
            enumOf(definition("state"), "properties", "intercom_state"),
        )
        assertMatches(
            IntercomMode.serializer(),
            enumValues<IntercomMode>().toList(),
            enumOf(definition("intercom_mode")),
        )
        assertMatches(
            IntercomSignalKind.serializer(),
            enumValues<IntercomSignalKind>().toList(),
            enumOf(definition("intercom_signal_kind")),
        )
        assertMatches(
            IntercomEndReason.serializer(),
            enumValues<IntercomEndReason>().toList(),
            enumOf(definition("intercom_end_reason")),
        )
    }

    @Test
    fun `event data enums match the schema`() {
        assertMatches(
            DismissReason.serializer(),
            enumValues<DismissReason>().toList(),
            eventDataEnum("notification_dismissed", "reason"),
        )
        assertMatches(
            MediaFinishedReason.serializer(),
            enumValues<MediaFinishedReason>().toList(),
            eventDataEnum("media_finished", "reason"),
        )
        assertMatches(
            ScreensaverStartTrigger.serializer(),
            enumValues<ScreensaverStartTrigger>().toList(),
            eventDataEnum("screensaver_started", "trigger"),
        )
        assertMatches(
            ScreensaverStopTrigger.serializer(),
            enumValues<ScreensaverStopTrigger>().toList(),
            eventDataEnum("screensaver_stopped", "trigger"),
        )
    }

    @Test
    fun `state and log enums match the schema`() {
        assertMatches(
            NetworkType.serializer(),
            enumValues<NetworkType>().toList(),
            enumOf(definition("state"), "properties", "network"),
        )
        assertMatches(
            MediaState.serializer(),
            enumValues<MediaState>().toList(),
            enumOf(definition("state"), "properties", "media_state"),
        )
        assertMatches(
            LogLevel.serializer(),
            enumValues<LogLevel>().toList(),
            enumOf(definition("log_line"), "properties", "level"),
        )
    }

    @Test
    fun `command enums match the schema`() {
        assertMatches(
            MediaType.serializer(),
            enumValues<MediaType>().toList(),
            enumOf(
                definition("command_play_media"),
                "properties", "event", "properties", "args", "properties", "media_type",
            ),
        )
        assertMatches(
            MediaControlAction.serializer(),
            enumValues<MediaControlAction>().toList(),
            enumOf(
                definition("command_media_control"),
                "properties", "event", "properties", "args", "properties", "action",
            ),
        )
        assertMatches(
            NotificationLevel.serializer(),
            enumValues<NotificationLevel>().toList(),
            enumOf(
                definition("command_notify"),
                "properties", "event", "properties", "args", "properties", "level",
            ),
        )
    }
}
