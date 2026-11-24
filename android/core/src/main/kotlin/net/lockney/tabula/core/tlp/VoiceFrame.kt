package net.lockney.tabula.core.tlp

/**
 * A binary voice frame (docs/PROTOCOL.md 4.10, ADR 0032): the first byte is the
 * HA binary handler id that `voice_start` named, the rest is exactly
 * [CHUNK_BYTES] of 16 kHz, 16-bit signed little-endian, mono PCM (10 ms).
 *
 * The frame has no JSON schema entry, so it lives outside [Tlp]: this class is
 * the whole codec, and the transport sends [encode]'s bytes as one WebSocket
 * binary message while a turn is open.
 */
data class VoiceFrame(val handlerId: Int, val payload: ByteArray) {
    init {
        require(handlerId in 1..255) { "handler_id must be 1..255, is $handlerId" }
        require(payload.size == CHUNK_BYTES) {
            "a voice frame carries $CHUNK_BYTES PCM bytes, got ${payload.size}"
        }
    }

    /** The wire form: handler byte, then the PCM. */
    fun encode(): ByteArray = ByteArray(payload.size + 1).also {
        it[0] = handlerId.toByte()
        payload.copyInto(it, destinationOffset = 1)
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceFrame && other.handlerId == handlerId && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * handlerId + payload.contentHashCode()

    override fun toString(): String = "VoiceFrame(handlerId=$handlerId, bytes=${payload.size})"

    companion object {
        /** `VOICE_CHUNK_BYTES` (ADR 0002): 160 samples of 16 kHz, 16-bit mono. */
        const val CHUNK_BYTES = 320

        /** Split a received binary message; rejects a short or long frame. */
        fun decode(bytes: ByteArray): VoiceFrame {
            require(bytes.isNotEmpty()) { "a voice frame carries a handler byte" }
            return VoiceFrame(bytes[0].toInt() and 0xFF, bytes.copyOfRange(1, bytes.size))
        }
    }
}
