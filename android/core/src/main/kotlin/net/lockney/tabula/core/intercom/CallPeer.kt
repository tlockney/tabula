package net.lockney.tabula.core.intercom

import java.security.SecureRandom
import net.lockney.tabula.core.tlp.IntercomSignalKind

/**
 * Which way audio flows over one call's connection (ADR 0028): a two-way call
 * both ways, a broadcast's sender only out, a broadcast's receiver or a
 * listen-only answer only in.
 */
enum class PeerDirection { SEND_RECEIVE, SEND_ONLY, RECEIVE_ONLY }

/**
 * One call's connection, as the engine sees it. No WebRTC type appears here:
 * the production implementation is the app's, and the tests' fake records what
 * it was asked to do instead of opening a speaker or a microphone.
 */
interface CallPeer {
    /** What the connection reports back to the engine's state machine. */
    interface Listener {
        /** The peer's own offer, answer or candidate, to relay unchanged. */
        fun onLocalSignal(kind: IntercomSignalKind, payload: String)

        /** ICE reached an established connection: audio is flowing. */
        fun onConnected()

        /** The connection could not be set up; the call ends with reason `error`. */
        fun onFailed(message: String)
    }

    /** Caller: build the connection and send an offer. */
    fun offer()

    /** Answerer: apply [offerSdp] and send an answer. */
    fun answer(offerSdp: String)

    /** Caller: apply the answer. */
    fun answerReceived(answerSdp: String)

    /** One relayed ICE candidate, in the form the peer itself encoded. */
    fun candidate(payload: String)

    /** Releases the connection, its audio source and its audio device. */
    fun close()
}

/** Builds the connection for one call (`WebRtcPeerFactory` in the app). */
fun interface CallPeerFactory {
    fun create(info: CallInfo, direction: PeerDirection, listener: CallPeer.Listener): CallPeer
}

/** The dead-reckoning the engine needs, driven by a virtual clock in the tests. */
fun interface CallScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancelable

    /** One scheduled action; cancelling it after it ran is harmless. */
    fun interface Cancelable {
        fun cancel()
    }
}

/**
 * The power and Wi-Fi locks a live call holds (ADR 0028): a partial wake lock
 * and a Wi-Fi high-performance lock, taken when a call begins - ringing counts -
 * and released when it ends, whatever ended it.
 */
interface CallLocks {
    fun acquire()

    fun release()

    /** The tests' and the no-lock case: a call that holds nothing. */
    object None : CallLocks {
        override fun acquire() = Unit

        override fun release() = Unit
    }
}

/** The call ids this device mints for the calls it starts itself (PROTOCOL 4.5). */
object CallIdMint {
    private val random = SecureRandom()

    /** `call-` and 16 hex digits: the schema's 8-64 chars of `[0-9A-Za-z_-]`. */
    fun random(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return "call-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
