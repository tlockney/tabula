package net.lockney.tabula.core.intercom

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomSignalKind
import net.lockney.tabula.core.tlp.IntercomState

/**
 * A scripted engine for the TLP wiring's tests (packet P9.3 item 9). It records
 * what the wiring asked for, moves between states the way the real one does for
 * the transitions the wiring cares about, and lets a test push an event or set a
 * state. It has no timers, no peer and no audio, so nothing here can ring, play
 * or capture.
 */
class FakeCallEngine : CallEngine {
    override val state = MutableStateFlow(IntercomState.IDLE)
    override val info = MutableStateFlow<CallInfo?>(null)

    private val channel = Channel<EngineEvent>(64)
    override val events: ReceiveChannel<EngineEvent> = channel

    /** Whether this device has a usable microphone (`intercom_talk`). */
    var canTalk = true

    val starts = mutableListOf<StartRequest>()
    val rings = mutableListOf<RingRequest>()
    val answers = mutableListOf<String>()
    val declines = mutableListOf<String>()
    val ends = mutableListOf<Pair<String, IntercomEndReason>>()
    val signals = mutableListOf<Triple<String, IntercomSignalKind, String>>()
    var transportUps = 0
        private set
    var transportDowns = 0
        private set
    var closes = 0
        private set

    private var sequence = 0

    override fun start(request: StartRequest): StartOutcome {
        if (state.value != IntercomState.IDLE) return StartOutcome.Busy
        if (!canTalk) return StartOutcome.CannotTalk
        starts += request
        val callId = "call-fake-${++sequence}"
        info.value = CallInfo(
            callId = callId,
            mode = request.mode,
            direction = CallDirection.OUTGOING,
            peerName = request.peerName,
            peerDeviceId = request.targetDeviceId,
            startedAtMs = 0,
            usesMicrophone = true,
        )
        state.value =
            if (request.mode == IntercomMode.BROADCAST) IntercomState.BROADCASTING else IntercomState.IN_CALL
        channel.trySend(
            EngineEvent.StartRequested(callId, request.mode, request.targetDeviceId, request.targetAreaId),
        )
        return StartOutcome.Started(callId)
    }

    override fun ring(request: RingRequest): Boolean {
        if (state.value != IntercomState.IDLE) return false
        rings += request
        info.value = CallInfo(
            callId = request.callId,
            mode = request.mode,
            direction = CallDirection.INCOMING,
            peerName = request.callerName,
            peerDeviceId = request.callerDeviceId,
            startedAtMs = 0,
            usesMicrophone = request.mode == IntercomMode.CALL && canTalk,
        )
        state.value =
            if (request.mode == IntercomMode.BROADCAST) IntercomState.IN_CALL else IntercomState.RINGING
        return true
    }

    override fun answer(): Boolean {
        val current = info.value ?: return false
        if (state.value != IntercomState.RINGING) return false
        answers += current.callId
        info.value = current.copy(answered = true)
        state.value = IntercomState.IN_CALL
        channel.trySend(EngineEvent.Answered(current.callId))
        return true
    }

    override fun decline(): Boolean {
        val current = info.value ?: return false
        if (state.value != IntercomState.RINGING) return false
        declines += current.callId
        info.value = null
        state.value = IntercomState.IDLE
        channel.trySend(EngineEvent.Declined(current.callId))
        return true
    }

    override fun end(callId: String, reason: IntercomEndReason): Boolean {
        val current = info.value ?: return false
        if (current.callId != callId) return false
        ends += callId to reason
        info.value = null
        state.value = IntercomState.IDLE
        channel.trySend(EngineEvent.Ended(callId, reason))
        return true
    }

    override fun signal(callId: String, kind: IntercomSignalKind, payload: String): Boolean {
        if (info.value?.callId != callId) return false
        signals += Triple(callId, kind, payload)
        return true
    }

    override fun onTransportUp() {
        transportUps++
    }

    override fun onTransportDown() {
        transportDowns++
    }

    override fun close() {
        closes++
    }

    /** Test hook: push one engine event as if the call had produced it. */
    fun emit(event: EngineEvent) {
        channel.trySend(event)
    }

    /** Test hook: put the engine in a state without a transition. */
    fun setState(value: IntercomState, call: CallInfo? = info.value) {
        info.value = call
        state.value = value
    }
}
