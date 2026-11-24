package net.lockney.tabula.core.intercom

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomSignalKind
import net.lockney.tabula.core.tlp.IntercomState

/**
 * The call engine's whole surface (packet P9.3, ADR 0028): the intercom UI, the
 * TLP wiring and the unit tests all talk to this, and only the engine's own
 * implementation imports WebRTC.
 *
 * One call at a time, in one of the wire's four states (PROTOCOL 4.4). The
 * engine never starts or answers a call by itself: [start] is the device's own
 * action (a screen button, a voice intent or a schedule, ADR 0028 guard 1) and
 * [ring] is HA's command. A device that is online does not ring because it is
 * online.
 */
interface CallEngine {
    /** Where the device is in a call; `intercom_state` on the wire. */
    val state: StateFlow<IntercomState>

    /** Who the call is with and what it is; null when [state] is idle. */
    val info: StateFlow<CallInfo?>

    /**
     * What the engine wants sent to HA: answers, declines, signals and ends.
     * The wiring turns each of these into one TLP event.
     */
    val events: ReceiveChannel<EngineEvent>

    /** Starts a two-way call or a broadcast on this device's own initiative. */
    fun start(request: StartRequest): StartOutcome

    /** One `intercom_ring` from HA; false when this device is already in a call. */
    fun ring(request: RingRequest): Boolean

    /** Answers the ringing call; false when nothing is ringing. */
    fun answer(): Boolean

    /** Declines the ringing call; false when nothing is ringing. */
    fun decline(): Boolean

    /** Ends the call named by [callId], for any reason; other ids are ignored. */
    fun end(callId: String, reason: IntercomEndReason): Boolean

    /** One relayed signal; false when it belongs to another call. */
    fun signal(callId: String, kind: IntercomSignalKind, payload: String): Boolean

    /** The TLP session registered: a call that waited out the grace stays up. */
    fun onTransportUp()

    /** The TLP session dropped: the call ends after the disconnect grace. */
    fun onTransportDown()

    /** Releases everything; nothing is reported after this. */
    fun close()
}

/** Which side of the call this device is (P9.3). */
enum class CallDirection { INCOMING, OUTGOING }

/**
 * One call, as the UI and the wiring see it. [peerName] is the caller's name for
 * an incoming ring and the target's label for an outgoing call; [peerDeviceId]
 * is the caller's TLP id, or the HA target a device-started call asked for.
 */
data class CallInfo(
    val callId: String,
    val mode: IntercomMode,
    val direction: CallDirection,
    val peerName: String,
    val peerDeviceId: String?,
    val startedAtMs: Long,
    /**
     * True while this call captures once it is up. A listen-only device, a
     * broadcast's receiver and a ring nobody has answered yet are all false,
     * which is what the microphone arbiter reads (ADR 0030, P9.3 item 8).
     */
    val usesMicrophone: Boolean,
)

/** What the device asked for when it started a call on its own screen. */
data class StartRequest(
    val mode: IntercomMode,
    val targetDeviceId: String? = null,
    val targetAreaId: String? = null,
    val peerName: String = "",
)

/** One `intercom_ring`: who is calling, what kind of call it is and its id. */
data class RingRequest(
    val callId: String,
    val callerDeviceId: String,
    val callerName: String,
    val mode: IntercomMode,
)

/** The result of [CallEngine.start]; one call at a time, and talk needs a microphone. */
sealed interface StartOutcome {
    data class Started(val callId: String) : StartOutcome

    /** A call is already up (PROTOCOL 4.5: a second call is refused `busy`). */
    data object Busy : StartOutcome

    /** `intercom_talk` is not declared: this device has no usable microphone. */
    data object CannotTalk : StartOutcome

    /** The audio connection could not be created; the call is already over. */
    data object Unavailable : StartOutcome

    /**
     * The request does not name a legal target: a call takes exactly one of a
     * device and an area, a broadcast neither (PROTOCOL 4.5).
     */
    data object InvalidTarget : StartOutcome
}

/** Everything the engine reports upward, one TLP event each. */
sealed interface EngineEvent {
    data class Answered(val callId: String) : EngineEvent

    data class Declined(val callId: String) : EngineEvent

    data class Signal(
        val callId: String,
        val kind: IntercomSignalKind,
        val payload: String,
    ) : EngineEvent

    data class Ended(val callId: String, val reason: IntercomEndReason) : EngineEvent

    /** A call this device started: HA resolves the target and rings it. */
    data class StartRequested(
        val callId: String,
        val mode: IntercomMode,
        val targetDeviceId: String?,
        val targetAreaId: String?,
    ) : EngineEvent
}
