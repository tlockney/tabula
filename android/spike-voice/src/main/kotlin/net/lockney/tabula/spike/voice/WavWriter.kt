package net.lockney.tabula.spike.voice

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes 16-bit PCM WAV. The 44-byte header is written first with zero sizes
 * and patched on [close], so a capture that is cut short still leaves a
 * readable file for whatever was written.
 *
 * The format details (little-endian, RIFF/WAVE, a "fmt " chunk followed by a
 * "data" chunk) are the plain WAV container; the byte layout is in [header].
 */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")

    var dataBytes: Long = 0
        private set

    init {
        out.setLength(0)
        out.write(header(sampleRate, channels, BITS, 0))
    }

    fun write(pcm: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = pcm[i].toInt()
            bytes[2 * i] = (s and 0xff).toByte()
            bytes[2 * i + 1] = ((s ushr 8) and 0xff).toByte()
        }
        out.write(bytes)
        dataBytes += bytes.size.toLong()
    }

    override fun close() {
        out.seek(0)
        out.write(header(sampleRate, channels, BITS, dataBytes.toInt()))
        out.close()
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val BITS = 16

        /** The 44-byte RIFF/WAVE header for 16-bit PCM. */
        fun header(sampleRate: Int, channels: Int, bits: Int, dataBytes: Int): ByteArray {
            val byteRate = sampleRate * channels * bits / 8
            val blockAlign = channels * bits / 8
            val out = ByteArray(HEADER_BYTES)
            fun putAscii(offset: Int, value: String) {
                value.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
            }

            fun putIntLe(offset: Int, value: Int) {
                out[offset] = (value and 0xff).toByte()
                out[offset + 1] = ((value ushr 8) and 0xff).toByte()
                out[offset + 2] = ((value ushr 16) and 0xff).toByte()
                out[offset + 3] = ((value ushr 24) and 0xff).toByte()
            }

            fun putShortLe(offset: Int, value: Int) {
                out[offset] = (value and 0xff).toByte()
                out[offset + 1] = ((value ushr 8) and 0xff).toByte()
            }

            putAscii(0, "RIFF")
            putIntLe(4, HEADER_BYTES - 8 + dataBytes)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putIntLe(16, 16) // fmt chunk size
            putShortLe(20, 1) // PCM
            putShortLe(22, channels)
            putIntLe(24, sampleRate)
            putIntLe(28, byteRate)
            putShortLe(32, blockAlign)
            putShortLe(34, bits)
            putAscii(36, "data")
            putIntLe(40, dataBytes)
            return out
        }
    }
}
