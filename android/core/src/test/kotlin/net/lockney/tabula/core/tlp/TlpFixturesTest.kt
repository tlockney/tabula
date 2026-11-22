package net.lockney.tabula.core.tlp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The codec against the golden fixtures in schema/fixtures/tlp/v1, the same
 * contract tools/validate_frames.py enforces (plan section 8): every valid
 * fixture decodes strictly, every invalid fixture is rejected.
 */
class TlpFixturesTest {
    private val fixtures = File(net.lockney.tabula.core.TestRepo.root(), "schema/fixtures/tlp/v1")

    /** Frame types the device puts on the wire; command frames arrive only. */
    private val encodable = setOf(
        "register", "subscribe", "state_delta", "command_result", "event", "log", "ping", "pong",
        "register_result", "ack", "error_result",
        "http_pair_request", "http_pair_response", "http_claim_request", "http_claim_pending",
        "http_claim_response", "http_error", "http_screenshot_response",
    )

    @Test
    fun `every valid fixture decodes strictly`() {
        val files = fixtures.listFiles { f -> f.isFile && f.extension == "json" }!!
            .sortedBy { it.name }
        assertTrue(files.size >= 30, "expected the golden fixtures, found ${files.size}")
        for (file in files) {
            val frame = file.name.substringBefore(".")
            val decoded = Tlp.decodeFrame(frame, file.readText())
            assertTrue(frame in Tlp.frameTypes, "$frame is a known frame type")
            if (frame in encodable) {
                val encoded = Tlp.encodeFrame(frame, decoded)
                assertEqualsJson(file, frame, encoded)
            } else if (frame.startsWith("command_")) {
                assertArgsRoundTrip(frame, decoded as CommandFrame)
            }
        }
    }

    @Test
    fun `every invalid fixture is rejected`() {
        val invalid = File(fixtures, "invalid")
        val files = invalid.listFiles { f -> f.isFile && f.name != "EXPECTED.json" }!!
            .sortedBy { it.name }
        assertTrue(files.size >= 20, "expected the invalid fixtures, found ${files.size}")
        for (file in files) {
            val frame = file.name.substringBefore(".")
            assertFailsWith<Exception>("${file.name} must be rejected as a $frame frame") {
                Tlp.decodeFrame(frame, file.readText())
            }
        }
    }

    /** Typed args must carry every schema member: encode(args) == the raw args. */
    private fun assertArgsRoundTrip(frame: String, command: CommandFrame) {
        val args: JsonElement = when (frame) {
            "command_set_settings" -> typed(command.event.setSettingsArgs(Tlp.strict), SetSettingsArgs.serializer())
            "command_screen" -> typed(command.event.screenArgs(Tlp.strict), ScreenArgs.serializer())
            "command_notify" -> typed(command.event.notifyArgs(Tlp.strict), NotifyArgs.serializer())
            "command_play_media" -> typed(command.event.playMediaArgs(Tlp.strict), PlayMediaArgs.serializer())
            "command_media_control" -> typed(command.event.mediaControlArgs(Tlp.strict), MediaControlArgs.serializer())
            "command_screensaver" -> typed(command.event.screensaverArgs(Tlp.strict), ScreensaverArgs.serializer())
            "command_navigate" -> typed(command.event.navigateArgs(Tlp.strict), NavigateArgs.serializer())
            "command_go_home", "command_reload", "command_restart_app", "command_clear_cache" ->
                typed(command.event.emptyArgs(Tlp.strict), EmptyArgs.serializer())

            "command_log_subscription" ->
                typed(command.event.logSubscriptionArgs(Tlp.strict), LogSubscriptionArgs.serializer())

            "command_screenshot" -> typed(command.event.screenshotArgs(Tlp.strict), ScreenshotArgs.serializer())
            else -> error("unexpected command frame $frame")
        }
        assertTrue(args.canonical() == command.event.args.canonical(), "$frame args must round-trip typed")
    }

    private fun <T> typed(value: T, serializer: KSerializer<T>): JsonElement =
        Tlp.strict.encodeToJsonElement(serializer, value)

    /** The event frame's data member is validated per event name (schema allOf). */
    @Test
    fun `event fixture data decodes typed`() {
        for (file in fixtures.listFiles { f -> f.isFile && f.name.startsWith("event") }!!) {
            val frame = Tlp.decodeFrame("event", file.readText()) as EventFrame
            val data = frame.eventData(Tlp.strict)
            val encoded: JsonElement = when (data) {
                is NotificationShown -> typed(data, NotificationShown.serializer())
                is NotificationDismissed -> typed(data, NotificationDismissed.serializer())
                is NotificationAction -> typed(data, NotificationAction.serializer())
                is ScreensaverStarted -> typed(data, ScreensaverStarted.serializer())
                is ScreensaverStopped -> typed(data, ScreensaverStopped.serializer())
                is MediaFinished -> typed(data, MediaFinished.serializer())
                is PageChanged -> typed(data, PageChanged.serializer())
            }
            assertTrue(
                encoded.canonical() == frame.data.canonical(),
                "${file.name}: event data must round-trip typed",
            )
        }
    }

    @Test
    fun `register state stays complete through the codec`() {
        val frame = Tlp.decodeFrame("register", File(fixtures, "register.json").readText()) as RegisterFrame
        val state = frame.state
        assertTrue(state.screenOn == true && state.brightness == 60 && state.charging == true)
        assertTrue(state.nextScreensaver == "2026-11-21T14:08:12Z")
        assertTrue(state.screensaverMode == ScreensaverMode.CLOCK)
        assertTrue(state.network == NetworkType.WIFI)
    }

    @Test
    fun `log frame bounds hold`() {
        val line = """{"ts":"2026-11-21T14:03:12Z","level":"info","tag":"ws","message":"x"}"""
        val decode = { Tlp.json.decodeFromString<LogLine>(line) }
        assertFailsWith<IllegalArgumentException> { LogFrame(id = 1, lines = List(201) { decode() }) }
        assertFailsWith<IllegalArgumentException> { LogFrame(id = 1, lines = emptyList()) }
    }
}

/** Re-encode must produce the same frame; numbers compare numerically and null == absent. */
internal fun assertEqualsJson(source: File, frame: String, encoded: String) {
    val original = Json.parseToJsonElement(source.readText()).canonical()
    val actual = Json.parseToJsonElement(encoded).canonical()
    assertTrue(original == actual, "${source.name}: re-encoded $frame differs")
}

/**
 * Canonical form: drop members whose value is JSON null (absent and null mean
 * the same thing for optional keys) and compare numbers by value.
 */
internal fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> buildJsonObject {
        for ((key, value) in entries) {
            if (value !is JsonNull) put(key, value.canonical())
        }
    }

    is JsonArray -> buildJsonArray { for (element in this@canonical) add(element.canonical()) }

    is JsonPrimitive ->
        if (content.toDoubleOrNull() != null) {
            JsonPrimitive(BigDecimal(content).stripTrailingZeros().toPlainString())
        } else {
            this
        }

    else -> this
}
