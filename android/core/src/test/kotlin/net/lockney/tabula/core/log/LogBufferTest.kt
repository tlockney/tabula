package net.lockney.tabula.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.tlp.LogLevel

class LogBufferTest {
    @Test
    fun `the ring holds its capacity and drops the oldest first`() {
        val buffer = LogBuffer(capacity = 3, clockMs = { 0L })
        repeat(5) { buffer.add(LogLevel.INFO, "app", "line $it") }

        assertEquals(3, buffer.size())
        assertEquals(listOf("line 2", "line 3", "line 4"), buffer.snapshot().map { it.message })
    }

    @Test
    fun `entries keep arrival order and increasing sequence numbers`() {
        val buffer = LogBuffer(capacity = 10, clockMs = { 0L })
        val first = buffer.add(LogLevel.INFO, "app", "first")
        val second = buffer.add(LogLevel.WARNING, "console", "second")

        assertTrue(second.seq > first.seq)
        assertEquals(listOf(first, second), buffer.snapshot())
        assertEquals(second.seq, buffer.lastSeq())
    }

    @Test
    fun `after returns only lines past the cursor`() {
        val buffer = LogBuffer(capacity = 10, clockMs = { 0L })
        val first = buffer.add(LogLevel.INFO, "app", "one")
        buffer.add(LogLevel.INFO, "app", "two")

        assertEquals(listOf("two"), buffer.after(first.seq).map { it.message })
        assertEquals(emptyList(), buffer.after(buffer.lastSeq()))
    }

    @Test
    fun `an empty message is kept as a visible placeholder`() {
        val buffer = LogBuffer(capacity = 2, clockMs = { 0L })
        // A LogLine's message is 1-4000 chars on the wire (PROTOCOL.md 4.8).
        assertEquals("(empty)", buffer.add(LogLevel.DEBUG, "console", "   ").message)
    }

    @Test
    fun `the tag and message are cut to the schema bounds`() {
        val buffer = LogBuffer(capacity = 2, clockMs = { 0L })
        val entry = buffer.add(LogLevel.INFO, "t".repeat(200), "m".repeat(9000))

        assertEquals(LogBuffer.TAG_MAX, entry.tag.length)
        assertEquals(LogBuffer.MESSAGE_MAX, entry.message.length)
    }

    @Test
    fun `the wire form is an RFC 3339 line`() {
        val buffer = LogBuffer(capacity = 2, clockMs = { 1_700_000_000_000L })
        val wire = buffer.add(LogLevel.ERROR, "app", "boom").toWire()

        assertEquals("2023-11-14T22:13:20Z", wire["ts"])
        assertEquals("error", wire["level"])
        assertEquals("app", wire["tag"])
        assertEquals("boom", wire["message"])
    }

    @Test
    fun `the default capacity is the ADR's constant`() {
        val buffer = LogBuffer(clockMs = { 0L })
        repeat(net.lockney.tabula.core.Constants.LOG_BUFFER_LINES + 5) {
            buffer.add(LogLevel.INFO, "app", "line $it")
        }
        assertEquals(net.lockney.tabula.core.Constants.LOG_BUFFER_LINES, buffer.size())
    }
}

class RedactionTest {
    @Test
    fun `an authorization header keeps its key and loses its value`() {
        assertEquals(
            "request Authorization=<redacted> sent",
            Redaction.apply("request Authorization: Bearer abc.def sent"),
        )
        assertEquals(
            "Authorization=<redacted>",
            Redaction.apply("Authorization: Basic opaque-creds")
        )
        // The key keeps its own spelling; only the value goes.
        assertEquals("authorization=<redacted>", Redaction.apply("authorization=xyz"))
    }

    @Test
    fun `token query parameters are redacted`() {
        val line = Redaction.apply("posting access_token=abc and authSig=zzz now")
        assertFalse("abc" in line, line)
        assertFalse("zzz" in line, line)
        assertTrue("access_token=<redacted>" in line, line)
        assertTrue("authsig=<redacted>" in line.lowercase(), line)
    }

    @Test
    fun `a token in a URL query is gone with the query`() {
        val line = Redaction.apply("GET /api/?access_token=abc&x=1")
        assertEquals("GET /api/", line)
    }

    @Test
    fun `refresh tokens are redacted however they are written`() {
        assertFalse("rt-secret" in Redaction.apply("""{"refresh_token": "rt-secret"}"""))
        assertFalse("rt-secret" in Redaction.apply("refresh_token=rt-secret"))
    }

    @Test
    fun `query strings are stripped from URLs and paths`() {
        assertEquals(
            "loading https://ha.example/lovelace/0 now",
            Redaction.apply("loading https://ha.example/lovelace/0?edit=1&x=y now"),
        )
        assertEquals("navigated to /lovelace/1", Redaction.apply("navigated to /lovelace/1?foo=bar"))
        assertEquals("hash kept #top", Redaction.apply("hash kept #top"))
    }

    @Test
    fun `an ordinary line is untouched`() {
        val line = "registered with HA device abc123 after 2.5 s"
        assertEquals(line, Redaction.apply(line))
    }
}

class LogStreamerTest {
    private var now = 10_000L
    private val buffer = LogBuffer(capacity = 10, clockMs = { now })
    private val streamer = LogStreamer(buffer, clockMs = { now })

    private fun line(message: String, level: LogLevel = LogLevel.INFO) =
        buffer.add(level, "app", message)

    @Test
    fun `nothing streams until a subscription is on`() {
        line("before")
        assertFalse(streamer.isEnabled())
        assertNull(streamer.takeBatch())
    }

    @Test
    fun `a fresh enable starts at the ring's end and sends what follows`() {
        line("before")
        streamer.enable(level = null)
        assertTrue(streamer.isEnabled())

        line("after")
        now += 1000
        assertEquals(listOf("after"), streamer.takeBatch()?.map { it.message })
    }

    @Test
    fun `the interval caps one batch per interval and only when something is new`() {
        streamer.enable(level = null)
        line("one")
        now += 1000
        assertEquals(listOf("one"), streamer.takeBatch()?.map { it.message })

        // A line right behind a frame waits for the interval to elapse.
        line("two")
        assertNull(streamer.takeBatch())
        now += 999
        assertNull(streamer.takeBatch())
        now += 1
        assertEquals(listOf("two"), streamer.takeBatch()?.map { it.message })

        // Nothing new: no frame, even though the interval has passed.
        now += 1000
        assertNull(streamer.takeBatch())
    }

    @Test
    fun `a batch is capped at the frame's maximum`() {
        val big = LogBuffer(capacity = 500, clockMs = { now })
        val capped = LogStreamer(big, clockMs = { now })
        capped.enable(level = null)
        repeat(net.lockney.tabula.core.Constants.LOG_MAX_LINES + 50) {
            big.add(LogLevel.INFO, "app", "line $it")
        }
        now += 1000
        assertEquals(net.lockney.tabula.core.Constants.LOG_MAX_LINES, capped.takeBatch()?.size)

        // The rest drains on the next interval rather than being dropped.
        now += 1000
        assertEquals(50, capped.takeBatch()?.size)
    }

    @Test
    fun `the minimum level filters at the source`() {
        streamer.enable(level = LogLevel.WARNING)
        line("chatty", LogLevel.DEBUG)
        line("noted", LogLevel.INFO)
        line("careful", LogLevel.WARNING)
        line("broken", LogLevel.ERROR)

        now += 1000
        assertEquals(listOf("careful", "broken"), streamer.takeBatch()?.map { it.message })
        // The filtered lines are gone, not re-read.
        now += 1000
        assertNull(streamer.takeBatch())
    }

    @Test
    fun `a refresh keeps the cursor and the TTL`() {
        streamer.enable(level = null)
        line("one")
        now += 1000
        assertEquals(1, streamer.takeBatch()?.size)

        now += net.lockney.tabula.core.Constants.LOG_SUBSCRIPTION_TTL_MS - 1
        streamer.enable(level = null) // the integration's refresh

        now += 2000
        assertTrue(streamer.isEnabled())
        line("two")
        now += 1000
        // No replay of "one".
        assertEquals(listOf("two"), streamer.takeBatch()?.map { it.message })
    }

    @Test
    fun `the subscription stops by itself after the TTL`() {
        streamer.enable(level = null)
        line("one")
        now += net.lockney.tabula.core.Constants.LOG_SUBSCRIPTION_TTL_MS

        assertFalse(streamer.isEnabled())
        assertNull(streamer.takeBatch())

        // And a later enable starts a fresh subscription.
        streamer.enable(level = null)
        assertTrue(streamer.isEnabled())
        line("two")
        now += 1000
        assertEquals(listOf("two"), streamer.takeBatch()?.map { it.message })
    }

    @Test
    fun `disable stops streaming and drops the level`() {
        streamer.enable(level = LogLevel.ERROR)
        line("broken", LogLevel.ERROR)
        streamer.disable()

        assertFalse(streamer.isEnabled())
        assertNull(streamer.minimumLevel())
        now += 1000
        assertNull(streamer.takeBatch())
    }
}
