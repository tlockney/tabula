package net.lockney.tabula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.lockney.tabula.core.intercom.CallEngine
import net.lockney.tabula.core.intercom.EngineEvent
import net.lockney.tabula.core.intercom.StartOutcome
import net.lockney.tabula.core.intercom.StartRequest
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.EventFrame
import net.lockney.tabula.core.tlp.IntercomEndArgs
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomRingArgs
import net.lockney.tabula.core.tlp.IntercomSignalArgs
import net.lockney.tabula.core.tlp.IntercomState
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler

/**
 * What the TLP session needs from the intercom (packet P9.3 item 6): the three
 * commands, the connection guards and the state key.
 */
interface IntercomControl {
    /** `intercom_ring`: an offered call or broadcast. */
    fun ring(args: IntercomRingArgs): CommandHandler.Reply

    /** `intercom_signal`: one relayed offer, answer or candidate. */
    fun signal(args: IntercomSignalArgs): CommandHandler.Reply

    /** `intercom_end`: stop the call in any state; another call's id changes nothing. */
    fun end(args: IntercomEndArgs): CommandHandler.Reply

    /** The TLP session registered: the disconnect grace is off. */
    fun onTransportUp()

    /** The TLP session dropped: the call ends after the grace (guard 3). */
    fun onTransportDown()

    /** The value `intercom_state` carries, for the registration's state. */
    fun state(): IntercomState

    /** Releases the engine and the event stream. */
    fun close()
}

/**
 * The intercom's wiring between the call engine and TLP (packet P9.3, PROTOCOL
 * 4.5 and 4.7): each engine event becomes one event frame, each state change
 * one `intercom_state` delta, and each command one result. It also owns the two
 * connection-side guards' entry points and the microphone hand-off signal.
 *
 * The call id is the engine's, so a call this device starts and a call HA starts
 * are the same shape on the wire.
 */
class IntercomManager(
    private val engine: CallEngine,
    scope: CoroutineScope,
    private val publishState: (StateDelta) -> Unit,
    private val onMicrophoneHold: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
) : IntercomControl {
    private val channel = Channel<(Int) -> EventFrame>(EVENT_BUFFER)

    /** The event frames the TLP session sends (PROTOCOL 4.7). */
    val events: ReceiveChannel<(Int) -> EventFrame> = channel

    init {
        scope.launch {
            for (event in engine.events) route(event)
        }
        scope.launch {
            engine.state.collect { state -> publishState(StateDelta(intercomState = state)) }
        }
        scope.launch {
            // The wake-word detector releases the microphone to a call that
            // captures, and only to that kind of call (P9.3 item 8)
            combine(engine.state, engine.info) { state, info ->
                (state == IntercomState.IN_CALL || state == IntercomState.BROADCASTING) &&
                    info?.usesMicrophone == true
            }
                .distinctUntilChanged()
                .collect { holds -> onMicrophoneHold(holds) }
        }
    }

    /**
     * A call started on this device's own screen (ADR 0025 decision 6; the
     * screen itself is P9.4). The engine mints the call id and rings nobody:
     * the `intercom_start_request` event tells HA, which resolves the target.
     */
    fun start(
        mode: IntercomMode,
        targetDeviceId: String? = null,
        targetAreaId: String? = null,
        peerName: String = "",
    ): StartOutcome {
        val outcome = engine.start(
            StartRequest(
                mode = mode,
                targetDeviceId = targetDeviceId,
                targetAreaId = targetAreaId,
                peerName = peerName,
            ),
        )
        when (outcome) {
            is StartOutcome.Started -> log("intercom: starting ${outcome.callId} ($mode)")
            StartOutcome.Busy -> log("intercom: a call is already up; not starting another")
            StartOutcome.CannotTalk -> log("intercom: no microphone; this device cannot start a call")
            StartOutcome.Unavailable -> log("intercom: the audio connection could not be created")
        }
        return outcome
    }

    /** Answers the ringing call, as the incoming-call screen's button does (P9.4). */
    fun answer(): Boolean = engine.answer()

    /** Declines the ringing call. */
    fun decline(): Boolean = engine.decline()

    /** Hangs up the call this device is in, from the call screen's button. */
    fun hangUp(): Boolean {
        val call = engine.info.value ?: return false
        return engine.end(call.callId, net.lockney.tabula.core.tlp.IntercomEndReason.HANGUP)
    }

    override fun ring(args: IntercomRingArgs): CommandHandler.Reply {
        val accepted = engine.ring(
            net.lockney.tabula.core.intercom.RingRequest(
                callId = args.callId,
                callerDeviceId = args.callerDeviceId,
                callerName = args.callerName,
                mode = args.mode,
            ),
        )
        return if (accepted) {
            CommandHandler.Reply.Ok()
        } else {
            // PROTOCOL 4.5: a ring during a call is refused `busy`, and the
            // integration ends the caller's side with that reason
            CommandHandler.Reply.Error(ErrorCode.BUSY, "already in a call")
        }
    }

    override fun signal(args: IntercomSignalArgs): CommandHandler.Reply =
        if (engine.signal(args.callId, args.kind, args.payload)) {
            CommandHandler.Reply.Ok()
        } else {
            // The mock's rule: a signal for a call this device is not in
            CommandHandler.Reply.Error(ErrorCode.INVALID_ARGS, "not in that call")
        }

    override fun end(args: IntercomEndArgs): CommandHandler.Reply {
        // A device not in that call answers ok and changes nothing (PROTOCOL 4.5)
        engine.end(args.callId, args.reason)
        return CommandHandler.Reply.Ok()
    }

    override fun onTransportUp() = engine.onTransportUp()

    override fun onTransportDown() = engine.onTransportDown()

    override fun state(): IntercomState = engine.state.value

    /** The current call, for the screen (P9.4) and the mic hand-off. */
    fun call(): net.lockney.tabula.core.intercom.CallInfo? = engine.info.value

    override fun close() {
        engine.close()
        channel.close()
    }

    private fun route(event: EngineEvent) {
        val frame: (Int) -> EventFrame = when (event) {
            is EngineEvent.Answered -> ({ id: Int -> EventFrame.intercomAnswer(id, event.callId) })
            is EngineEvent.Declined -> ({ id: Int -> EventFrame.intercomDecline(id, event.callId) })
            is EngineEvent.Signal ->
                ({ id: Int -> EventFrame.intercomSignal(id, event.callId, event.kind, event.payload) })

            is EngineEvent.Ended -> ({ id: Int -> EventFrame.intercomEnded(id, event.callId, event.reason) })
            is EngineEvent.StartRequested -> ({ id: Int ->
                EventFrame.intercomStartRequest(
                    id = id,
                    callId = event.callId,
                    mode = event.mode,
                    targetDeviceId = event.targetDeviceId,
                    targetAreaId = event.targetAreaId,
                )
            })
        }
        channel.trySend(frame)
    }

    companion object {
        /** Events kept while the TLP session drains them; a signal burst fits. */
        private const val EVENT_BUFFER = 32
    }
}
