package net.lockney.tabula

import android.content.Context
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import net.lockney.tabula.core.intercom.CallEngine
import net.lockney.tabula.core.intercom.CallIdMint
import net.lockney.tabula.core.intercom.CallInfo
import net.lockney.tabula.core.intercom.CallLocks
import net.lockney.tabula.core.intercom.CallMachine
import net.lockney.tabula.core.intercom.CallPeer
import net.lockney.tabula.core.intercom.CallPeerFactory
import net.lockney.tabula.core.intercom.CallScheduler
import net.lockney.tabula.core.intercom.PeerDirection
import net.lockney.tabula.core.tlp.IntercomSignalKind
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The production call engine (packet P9.3, ADR 0028): the core state machine
 * with the WebRTC connection, the Android clock and the call's power locks.
 *
 * This is the only file in the app that imports WebRTC, and [CallEngine] is
 * what everything else sees. Nothing here starts on its own: a connection is
 * opened only when the machine begins a call, and the machine begins one only
 * on an explicit start or an HA ring.
 */
class WebRtcCallEngine(
    context: Context,
    canTalk: () -> Boolean,
    locks: CallLocks = AndroidCallLocks(context),
    timings: CallMachine.Timings = CallMachine.Timings(),
    newCallId: () -> String = CallIdMint::random,
) : CallEngine by CallMachine(
    peers = WebRtcPeerFactory(context.applicationContext),
    scheduler = AndroidCallScheduler(),
    clockMs = SystemClock::elapsedRealtime,
    canTalk = canTalk,
    locks = locks,
    newCallId = newCallId,
    timings = timings,
)

/**
 * The intercom capabilities this device declares (PROTOCOL 4.2 and 4.5, ADR
 * 0025 decision 7): hearing a call needs only a speaker, so `intercom_listen`
 * is always there; talking needs the microphone grant at registration time,
 * which is re-read every session because Fire OS can withdraw it (F-KSK-21).
 */
fun intercomCapabilities(canTalk: Boolean): List<String> = buildList {
    add("intercom_listen")
    if (canTalk) add("intercom_talk")
}

/**
 * A live call's power and Wi-Fi locks (ADR 0028): held from the ring or the
 * start until the call ends, whatever ended it. The service's own long-lived
 * Wi-Fi lock is separate; this one exists so the requirement that a call holds
 * both locks is a property of the call rather than of the service's lifetime.
 */
class AndroidCallLocks(context: Context) : CallLocks {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tabula:intercom")
        ?.apply { setReferenceCounted(false) }

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tabula:intercom")
        ?.apply { setReferenceCounted(false) }

    private var held = false

    @Synchronized
    override fun acquire() {
        if (held) return
        held = true
        runCatching { power?.acquire() }
        runCatching { wifi?.acquire() }
    }

    @Synchronized
    override fun release() {
        if (!held) return
        held = false
        runCatching { power?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
    }
}

/** The engine's deadlines on the main looper, where the service's state lives. */
class AndroidCallScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CallScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): CallScheduler.Cancelable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return CallScheduler.Cancelable { handler.removeCallbacks(runnable) }
    }
}

/**
 * One call's WebRTC connection with the ADR 0028 settings: audio only, Opus
 * (the library's default), mono capture from `VOICE_COMMUNICATION`, software
 * echo cancellation, noise suppression and gain, and no STUN or TURN - host
 * candidates on the LAN only.
 */
class WebRtcPeerFactory(private val context: Context) : CallPeerFactory {
    init {
        // One initialization per process; calling it again is a no-op in the
        // library and keeps a first call after a process restart working.
        runCatching {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions(),
            )
        }
    }

    override fun create(info: CallInfo, direction: PeerDirection, listener: CallPeer.Listener): CallPeer =
        WebRtcPeer(context, direction, listener)
}

private class WebRtcPeer(
    context: Context,
    private val direction: PeerDirection,
    private val listener: CallPeer.Listener,
) : CallPeer {
    private val audio: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val connection: PeerConnection
    private var source: AudioSource? = null
    private var track: AudioTrack? = null

    /**
     * Candidates that arrived before the remote description did. `candidate()`
     * runs on the TLP coroutine and the flush runs on WebRTC's signaling
     * thread, so the list and the flags are guarded (review of #171, finding 3).
     */
    private val earlyCandidates = ArrayList<IceCandidate>()
    @Volatile private var remoteSet = false
    @Volatile private var closed = false

    init {
        audio = JavaAudioDeviceModule.builder(context)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            // Hardware cancellation when the device has it; WebRTC's software
            // path is what the Fire HD 10 Plus actually uses (ADR 0028)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        if (direction == PeerDirection.RECEIVE_ONLY) {
            // Listen-only: the record path never opens (ADR 0028)
            audio.setAudioRecordEnabled(false)
        }
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL
        connection = factory.createPeerConnection(config, observer())
            ?: throw IllegalStateException("createPeerConnection returned null")

        when (direction) {
            PeerDirection.RECEIVE_ONLY -> connection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )

            PeerDirection.SEND_ONLY -> {
                addLocalAudio()
                connection.transceivers.forEach { it.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY) }
            }

            PeerDirection.SEND_RECEIVE -> addLocalAudio()
        }
    }

    /**
     * A mono audio track. Echo cancellation, noise suppression and gain are the
     * audio device module's software defaults, which is the path ADR 0028
     * measured; `setUseHardwareAcousticEchoCanceler`/`NoiseSuppressor` above
     * only *prefer* hardware where it exists, and the Fire HD 10 Plus has none.
     * The `goog*` constraints are kept for parity with the spike's constraints
     * object, not because they switch the software path on (review of #171, 5).
     */
    private fun addLocalAudio() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val created = factory.createAudioSource(constraints)
        val createdTrack = factory.createAudioTrack("tabula-intercom-audio", created)
        connection.addTrack(createdTrack, listOf("tabula-intercom"))
        source = created
        track = createdTrack
    }

    override fun offer() {
        connection.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    connection.setLocalDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                listener.onLocalSignal(IntercomSignalKind.OFFER, sdp.description)
                            }

                            override fun onSetFailure(error: String?) = fail("setLocalDescription(offer)", error)
                            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                            override fun onCreateFailure(error: String?) = fail("createOffer", error)
                        },
                        sdp,
                    )
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = fail("setLocalDescription", error)
                override fun onCreateFailure(error: String?) = fail("createOffer", error)
            },
            MediaConstraints(),
        )
    }

    override fun answer(offerSdp: String) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        connection.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    remoteSet = true
                    flushCandidates()
                    connection.createAnswer(
                        object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                connection.setLocalDescription(
                                    object : SdpObserver {
                                        override fun onSetSuccess() {
                                            listener.onLocalSignal(IntercomSignalKind.ANSWER, sdp.description)
                                        }

                                        override fun onSetFailure(error: String?) =
                                            fail("setLocalDescription(answer)", error)

                                        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                                        override fun onCreateFailure(error: String?) = fail("createAnswer", error)
                                    },
                                    sdp,
                                )
                            }

                            override fun onCreateFailure(error: String?) = fail("createAnswer", error)
                            override fun onSetSuccess() = Unit
                            override fun onSetFailure(error: String?) = fail("setRemoteDescription", error)
                        },
                        MediaConstraints(),
                    )
                }

                override fun onSetFailure(error: String?) = fail("setRemoteDescription(offer)", error)
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = fail("setRemoteDescription", error)
            },
            offer,
        )
    }

    override fun answerReceived(answerSdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        connection.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    remoteSet = true
                    flushCandidates()
                }

                override fun onSetFailure(error: String?) = fail("setRemoteDescription(answer)", error)
                override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = fail("setRemoteDescription", error)
            },
            answer,
        )
    }

    /**
     * A relayed candidate in the form this peer encoded it: `mid`, `mline` and
     * the candidate's own `sdp` text. A payload from another implementation is
     * ignored rather than allowed to end the call.
     */
    override fun candidate(payload: String) {
        if (closed) return
        val candidate = runCatching {
            val json = JSONObject(payload)
            IceCandidate(
                if (json.isNull("mid")) null else json.optString("mid"),
                json.optInt("mline", 0),
                json.getString("sdp"),
            )
        }.getOrNull() ?: return
        val buffered = synchronized(this) {
            if (remoteSet) {
                false
            } else {
                earlyCandidates += candidate
                true
            }
        }
        if (!buffered) connection.addIceCandidate(candidate)
    }

    private fun flushCandidates() {
        val pending = synchronized(this) {
            val copy = ArrayList(earlyCandidates)
            earlyCandidates.clear()
            copy
        }
        pending.forEach { connection.addIceCandidate(it) }
    }

    private fun fail(what: String, error: String?) {
        if (closed) return
        listener.onFailed("$what failed: ${error ?: "unknown"}")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.close() }
        runCatching { connection.dispose() }
        runCatching { source?.dispose() }
        runCatching { track?.dispose() }
        runCatching { factory.dispose() }
        runCatching { audio.release() }
    }

    private fun observer(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> listener.onConnected()

                PeerConnection.IceConnectionState.FAILED -> fail("ICE", "connection failed")
                else -> Unit
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            if (closed) return
            val payload = JSONObject()
                .put("mid", candidate.sdpMid)
                .put("mline", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
                .toString()
            listener.onLocalSignal(IntercomSignalKind.CANDIDATE, payload)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>?) = Unit
    }
}
