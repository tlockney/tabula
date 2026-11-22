package net.lockney.tabula.spike.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WAV container the spike writes: sizes in the header, the format fields,
 * and the size patched on close. A wrong header makes every measurement's
 * clip unreadable by the host scorer, so the byte layout is checked directly.
 */
class WavWriterTest {
    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun shortAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    @Test
    fun headerDescribesSixteenBitMonoAtTheGivenRate() {
        val header = WavWriter.header(sampleRate = 16000, channels = 1, bits = 16, dataBytes = 32000)
        assertEquals(WavWriter.HEADER_BYTES, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, intAt(header, 16))
        assertEquals(1, shortAt(header, 20))
        assertEquals(1, shortAt(header, 22))
        assertEquals(16000, intAt(header, 24))
        assertEquals(32000, intAt(header, 28))
        assertEquals(2, shortAt(header, 32))
        assertEquals(16, shortAt(header, 34))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(32000, intAt(header, 40))
        // the RIFF size covers everything after its own two fields
        assertEquals(WavWriter.HEADER_BYTES - 8 + 32000, intAt(header, 4))
    }

    @Test
    fun closePatchesTheDataSizeToWhatWasWritten() {
        val file = File.createTempFile("spike", ".wav")
        try {
            val writer = WavWriter(file, sampleRate = 16000, channels = 1)
            writer.write(ShortArray(100) { 1000 }, 100)
            assertEquals(200L, writer.dataBytes)
            writer.close()
            val bytes = file.readBytes()
            assertEquals(WavWriter.HEADER_BYTES + 200, bytes.size)
            assertEquals(200, intAt(bytes, 40))
            assertEquals(WavWriter.HEADER_BYTES - 8 + 200, intAt(bytes, 4))
            // little-endian signed samples, in order
            assertEquals(1000, shortAt(bytes, WavWriter.HEADER_BYTES))
        } finally {
            file.delete()
        }
    }
}
