package net.lockney.tabula.core.tlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.Constants
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Codec behavior beyond the fixtures: strict senders, tolerant receivers (PROTOCOL 4.0). */
class TlpCodecTest {
    private val state = StateDelta(screenOn = true, brightness = 60, appVersion = "0.1.0")

    private fun register(id: Int = 1) = RegisterFrame(
        id = id,
        deviceId = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b",
        protocolVersion = 1,
        schemaVersion = 1,
        appVersion = "0.1.0",
        manufacturer = "Test",
        model = "T1",
        osVersion = "1",
        capabilities = listOf("screen", "notifications"),
        settingsRevision = 0,
        state = state,
    )

    @Test
    fun `encoding always writes the type member`() {
        val encoded = Tlp.encodeFrame("register", register())
        assertTrue("\"type\":\"tabula/register\"" in encoded.replace(" ", ""), encoded)
        val back = Tlp.strict.decodeFromString<RegisterFrame>(encoded)
        assertEquals(register(), back)
    }

    @Test
    fun `subscribe and ping frames are minimal`() {
        assertEquals("""{"id":2,"type":"tabula/subscribe"}""", Tlp.encodeFrame("subscribe", SubscribeFrame(2)))
        assertEquals("""{"id":3,"type":"ping"}""", Tlp.encodeFrame("ping", PingFrame(3)))
    }

    @Test
    fun `a frame over FRAME_MAX_BYTES is refused`() {
        val line = LogLine(ts = "2026-11-21T14:03:12Z", level = LogLevel.INFO, tag = "t", message = "x".repeat(4000))
        val small = LogFrame(id = 1, lines = listOf(line))
        val big = LogFrame(id = 1, lines = List(200) { line }) // ~800 KiB, valid per the schema
        assertTrue(Tlp.encodeFrame("log", small).length < Constants.FRAME_MAX_BYTES)
        assertFailsWith<IllegalArgumentException> { Tlp.encodeFrame("log", big) }
    }

    @Test
    fun `strict decode rejects unknown members, tolerant decode ignores them`() {
        val text = """
            {"id":1,"type":"tabula/subscribe","future_member":{"a":1}}
        """.trimIndent()
        assertFailsWith<Exception> { Tlp.strict.decodeFromString<SubscribeFrame>(text) }
        val tolerant = Tlp.json.decodeFromString<SubscribeFrame>(text)
        assertEquals(1, tolerant.id)
    }

    @Test
    fun `tolerant command args ignore unknown members, strict ones reject them`() {
        val args = buildJsonObject {
            put("enabled", true)
            put("level", "info")
            put("future_key", 7)
        }
        val event = CommandEvent(commandId = "c-0001-logs", command = "log_subscription", args = args)
        assertEquals(LogLevel.INFO, event.logSubscriptionArgs(Tlp.json).level)
        assertFailsWith<Exception> { event.logSubscriptionArgs(Tlp.strict) }
    }

    @Test
    fun `an unknown enum value in command args is a decode failure`() {
        // PROTOCOL 4.8: an unknown command argument value is invalid_args; the
        // command layer turns this exception into that result code.
        val args = buildJsonObject { put("action", "shuffle") }
        val event = CommandEvent(commandId = "c-0007-media", command = "media_control", args = args)
        assertFailsWith<Exception> { event.mediaControlArgs(Tlp.json) }
        assertFailsWith<Exception> { event.mediaControlArgs(Tlp.strict) }
    }

    @Test
    fun `an unknown command name is rejected by the frame registry`() {
        val event = CommandEvent(commandId = "c-0001-x", command = "no_such_command", args = buildJsonObject { })
        val frame = CommandFrame(id = 2, event = event)
        val text = Tlp.strict.encodeToString(CommandFrame.serializer(), frame)
        assertFailsWith<IllegalArgumentException> { Tlp.decodeFrame("command_screen", text) }
    }

    @Test
    fun `register_result with a numeric UTC offset decodes`() {
        // HA's Python side emits isoformat() timestamps, which use +00:00, not
        // Z. OffsetDateTime.parse accepts both; Instant.parse would reject
        // numeric offsets on the desugared API 28 runtime.
        val text = """
            {"id":1,"type":"result","success":true,"result":{
              "ha_device_id":"9c1e4b7f2a3d4c5e8f9a0b1c2d3e4f50",
              "protocol_version":1,"settings_revision":3,
              "server_time":"2026-11-21T14:03:12+00:00",
              "capabilities":["screen"]}}
        """.trimIndent()
        val frame = Tlp.decodeFrame("register_result", text) as RegisterResultFrame
        assertEquals("2026-11-21T14:03:12+00:00", frame.result.serverTime)
    }

    @Test
    fun `length bounds count code points, not UTF-16 units`() {
        // Schema minLength/maxLength count code points; one emoji is two
        // UTF-16 units but one code point (tag max is 64).
        val tag64 = "a".repeat(63) + "😀" // 64 code points, 65 UTF-16 units
        NotifyArgs(message = "x", tag = tag64)
        assertFailsWith<IllegalArgumentException> {
            NotifyArgs(message = "x", tag = "a".repeat(64) + "😀")
        }
    }

    @Test
    fun `command result invariants hold`() {
        val ack = CommandResultFrame.ack(7, "c-0002-screen")
        val nack = CommandResultFrame.nack(7, "c-0002-screen", TlpError(ErrorCode.FAILED, "boom"))
        assertEquals("""{"id":7,"type":"tabula/result","command_id":"c-0002-screen","ok":true}""", Tlp.encodeFrame("command_result", ack))
        assertTrue(""""ok":false""" in Tlp.encodeFrame("command_result", nack))
        assertFailsWith<IllegalArgumentException> { CommandResultFrame(id = 7, commandId = "c-0002-screen", ok = false) }
        assertFailsWith<IllegalArgumentException> {
            CommandResultFrame(id = 7, commandId = "c-0002-screen", ok = true, error = TlpError(ErrorCode.FAILED, "x"))
        }
    }

    @Test
    fun `event factories produce strict-valid frames`() {
        val frames = listOf(
            EventFrame.notificationShown(9, tag = "door"),
            EventFrame.notificationDismissed(9, DismissReason.TIMEOUT, tag = "door"),
            EventFrame.notificationAction(9, tag = "door", action = "snooze"),
            EventFrame.screensaverStarted(9, ScreensaverMode.CLOCK, ScreensaverStartTrigger.IDLE),
            EventFrame.screensaverStopped(9, ScreensaverMode.BLACK, ScreensaverStopTrigger.TOUCH),
            EventFrame.mediaFinished(9, MediaFinishedReason.ENDED),
            EventFrame.pageChanged(9, "/lovelace/0"),
        )
        for (frame in frames) {
            val encoded = Tlp.encodeFrame("event", frame)
            // Factories validate; re-decoding strictly must also hold (PROTOCOL 4.0).
            val back = Tlp.decodeFrame("event", encoded) as EventFrame
            assertEquals(frame.data, back.data)
        }
        assertFailsWith<IllegalArgumentException> { EventFrame.pageChanged(9, "https://evil.example/") }
    }

    @Test
    fun `ack result must be null`() {
        val text = """{"id":2,"type":"result","success":true,"result":null}"""
        val ack = Tlp.decodeFrame("ack", text) as AckFrame
        assertEquals("""{"id":2,"type":"result","success":true,"result":null}""", Tlp.encodeFrame("ack", ack))
        assertFailsWith<Exception> {
            Tlp.decodeFrame("ack", """{"id":2,"type":"result","success":true,"result":{"x":1}}""")
        }
    }

    @Test
    fun `device identity bounds are enforced`() {
        assertFailsWith<IllegalArgumentException> { register().copy(deviceId = "not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { register().copy(capabilities = listOf("screen", "screen")) }
        assertFailsWith<IllegalArgumentException> { register().copy(capabilities = listOf("Screen")) }
        assertFailsWith<IllegalArgumentException> { register().copy(capabilities = (1..65).map { "cap_$it" }) }
        assertFailsWith<IllegalArgumentException> { register().copy(state = StateDelta()) }
    }
}
