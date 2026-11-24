package net.lockney.tabula.core.tlp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The binary voice frame (PROTOCOL.md 4.10, ADR 0031). */
class VoiceFrameTest {
    private val pcm = ByteArray(VoiceFrame.CHUNK_BYTES) { (it % 251).toByte() }

    @Test
    fun `encode prefixes the handler byte`() {
        val bytes = VoiceFrame(7, pcm).encode()
        assertEquals(VoiceFrame.CHUNK_BYTES + 1, bytes.size)
        assertEquals(7, bytes[0].toInt())
        assertContentEquals(pcm, bytes.copyOfRange(1, bytes.size))
    }

    @Test
    fun `decode round-trips and reads the byte unsigned`() {
        assertEquals(VoiceFrame(255, pcm), VoiceFrame.decode(VoiceFrame(255, pcm).encode()))
        assertEquals(VoiceFrame(1, pcm), VoiceFrame.decode(VoiceFrame(1, pcm).encode()))
    }

    @Test
    fun `bounds are enforced on both sides`() {
        for (bad in listOf(0, 256, -1)) {
            assertFailsWith<IllegalArgumentException> { VoiceFrame(bad, pcm) }
        }
        assertFailsWith<IllegalArgumentException> { VoiceFrame(1, ByteArray(VoiceFrame.CHUNK_BYTES - 1)) }
        assertFailsWith<IllegalArgumentException> { VoiceFrame(1, ByteArray(VoiceFrame.CHUNK_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { VoiceFrame.decode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { VoiceFrame.decode(byteArrayOf(7)) }
        // A frame whose handler byte is 0 names no handler.
        assertFailsWith<IllegalArgumentException> {
            VoiceFrame.decode(byteArrayOf(0) + pcm)
        }
    }
}
