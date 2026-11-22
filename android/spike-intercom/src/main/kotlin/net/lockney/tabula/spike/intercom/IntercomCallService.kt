package net.lockney.tabula.spike.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import org.webrtc.RTCStats
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The P9.0 spike's throwaway call engine, in its own debug-only app (ADR 0028).
 *
 * One device is the caller (`role=call`): it offers and sends audio. The other
 * answers (`role=answer`). Signaling is the LAN relay (RelayClient); audio is
 * WebRTC with the library's echo cancellation and noise suppression, host
 * candidates only, no STUN or TURN (packet question 2).
 *
 * Everything the packet measures is logged under the tag `TabulaIntercomSpike`
 * with a `spike:` prefix, so the device checks can grep it:
 *
 *   spike: call role=... room=... aec=... listenOnly=...
 *   spike: call offer-sent elapsedMs=... answer-received elapsedMs=...
 *   spike: call connected fromOfferMs=...
 *   spike: call first-audio fromOfferMs=... side=in|out
 *   spike: local-candidate ... sdp=candidate:... typ host ...
 *   spike: stats ...            (every 2 s)
 *   spike: time-sync serverMs=... elapsedMs=... rttMs=...   (every 5 s)
 *   spike: tone-start ... / spike: tone-onset ...
 *   spike: call ended durationMs=... packetsIn=... packetsOut=...
 *
 * It runs as a foreground service because that is what production needs: the
 * call must survive the intercom UI closing and the panel going off
 * (packet question 4).
 */
class IntercomCallService : Service() {
    private lateinit var worker: ScheduledExecutorService
    private lateinit var relay: RelayClient
    private lateinit var factory: PeerConnectionFactory
    private lateinit var adm: JavaAudioDeviceModule
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var recorder: AudioProbe.Recorder? = null

    private val outbox = ConcurrentLinkedQueue<String>()
    private val earlyCandidates = ArrayList<IceCandidate>()
    private var remoteSet = false

    private var role = "call"
    private var room = "spike"
    private var toneAfterS = 0.0
    private var toneLocalAtS = 0.0
    private var recordS = 0
    private var durationS = 60L
    private var listenOnly = false
    private var aec = true

    private var startedMs = 0L
    private var offerSentMs = 0L
    private var offerSeenMs = 0L
    private var answerMs = 0L
    private var connectedMs = -1L
    private var toneStartMs = 0L
    private var firstInMs = -1L
    private var firstOutMs = -1L
    private var tick = 0L
    private var packetsIn = 0.0
    private var packetsOut = 0.0
    private var tonePlayed = false
    private var answering = false
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "spike-worker") }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tabula:intercom-spike")
        wakeLock.setReferenceCounted(false)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tabula:intercom-spike")
        wifiLock.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "spike: stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        if (startedMs != 0L) {
            // One call per process: a second start would inherit the first one's
            // timers and connection state. The checks force-stop between runs.
            Log.i(TAG, "spike: already running role=$role room=$room; ignoring the new start")
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        wakeLock.acquire()
        wifiLock.acquire()

        role = intent?.getStringExtra("role") ?: "call"
        room = intent?.getStringExtra("room") ?: "spike"
        listenOnly = intent?.getStringExtra("listenOnly")?.let { it == "1" || it == "true" } ?: false
        aec = intent?.getStringExtra("aec")?.let { !(it == "0" || it == "off" || it == "false") } ?: true
        toneAfterS = intent?.getStringExtra("toneAfter")?.toDoubleOrNull() ?: 0.0
        toneLocalAtS = intent?.getStringExtra("toneLocalAt")?.toDoubleOrNull() ?: 0.0
        recordS = intent?.getStringExtra("recordSeconds")?.toIntOrNull() ?: 0
        durationS = intent?.getStringExtra("durationSeconds")?.toLongOrNull() ?: 60L
        val relayUrl = intent?.getStringExtra("relay")
            ?: "http://127.0.0.1:8099"

        startedMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "spike: call role=$role room=$room aec=$aec listenOnly=$listenOnly toneAfter=$toneAfterS " +
                "recordSeconds=$recordS durationSeconds=$durationS relay=$relayUrl " +
                "sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL}",
        )

        if (toneLocalAtS > 0) {
            // Diagnostic only: plays the tone without a call, so a recorder on
            // the same device can show whether the speaker reaches the mic.
            Log.i(TAG, "spike: local tone in ${toneLocalAtS}s (no call)")
            worker.schedule({ AudioProbe.playTone(0.35, 2.0) }, toneLocalAtS.toLong(), TimeUnit.SECONDS)
        }

        relay = RelayClient(relayUrl, room, role)
        worker.execute { startCall(relayUrl) }
        worker.scheduleAtFixedRate({ tick() }, 0, POLL_MS, TimeUnit.MILLISECONDS)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val now = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "spike: call ended durationMs=${now - startedMs} packetsIn=${packetsIn.toInt()} " +
                "packetsOut=${packetsOut.toInt()} role=$role room=$room",
        )
        stopped = true
        try {
            worker.shutdownNow()
        } catch (_: Exception) {
        }
        closeCall()
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        super.onDestroy()
    }

    // --- WebRTC ------------------------------------------------------------------

    private fun startCall(relayUrl: String) {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions(),
            )
            adm = JavaAudioDeviceModule.builder(applicationContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(aec)
                .setUseHardwareNoiseSuppressor(aec)
                .createAudioDeviceModule()
            Log.i(
                TAG,
                "spike: audio adm hardwareAec=${JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()} " +
                    "hardwareNs=${JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()} aec=$aec",
            )
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
            if (role == "call") {
                newPeerConnection()
                makeOffer()
            } else {
                if (recordS > 0) {
                    val file = File(getExternalFilesDir(null), "rec-${role}-${SystemClock.elapsedRealtime()}.wav")
                    recorder = AudioProbe.startRecorder(file, recordS)
                }
                Log.i(TAG, "spike: waiting for an offer room=$room relay=$relayUrl")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "spike: setup failed: ${e.message}", e)
            stopSelf()
        }
    }

    /**
     * The audio-source constraints. `aec=off` has to reach WebRTC's own echo
     * canceller, not just the hardware one: the Fire HD 10 Plus reports no
     * hardware AEC, so the software canceller is what a spike run hears. Turning
     * it off is what lets a tone played by this device's own speaker reach the
     * call at all (packet questions 3 and 7).
     */
    private fun audioSourceConstraints(): MediaConstraints {
        val constraints = MediaConstraints()
        if (!aec) {
            constraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            constraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            constraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            constraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            Log.i(TAG, "spike: software echo cancellation, noise suppression and gain disabled")
        }
        return constraints
    }

    private fun newPeerConnection() {
        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL
        val connection = factory.createPeerConnection(config, observer())
        if (connection == null) {
            Log.e(TAG, "spike: createPeerConnection returned null")
            stopSelf()
            return
        }
        pc = connection
        if (listenOnly) {
            // Packet question 5: a receiver that never opens the microphone.
            adm.setAudioRecordEnabled(false)
            Log.i(TAG, "spike: listen-only (microphone disabled)")
            if (role == "call") {
                connection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
            }
        } else {
            // Added before the remote offer is applied: WebRTC associates it with
            // the offered audio m-line, so the answer carries audio both ways.
            val s = factory.createAudioSource(audioSourceConstraints())
            val t = factory.createAudioTrack("audio0", s)
            connection.addTrack(t, listOf("tabula-intercom-spike"))
            source = s
            track = t
            Log.i(TAG, "spike: local audio track added")
        }
    }

    private fun makeOffer() {
        val connection = pc ?: return
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                connection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        offerSentMs = SystemClock.elapsedRealtime()
                        logOffer()
                        post(JSONObject().put("t", "offer").put("sdp", sdp.description).toString())
                    }

                    override fun onSetFailure(error: String?) = sdpFailure("setLocalDescription(offer)", error)
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(error: String?) = sdpFailure("createOffer", error)
                }, sdp)
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = sdpFailure("setLocalDescription", error)
            override fun onCreateFailure(error: String?) = sdpFailure("createOffer", error)
        }, MediaConstraints())
    }

    private fun logOffer() {
        val sdp = pc?.localDescription?.description ?: return
        val sections = sdp.lines().filter { it.startsWith("m=") || it.startsWith("a=rtpmap") || it.startsWith("a=fmtp") }
        Log.i(TAG, "spike: call offer-sent elapsedMs=$offerSentMs sdpLines=${sdp.lines().size}")
        sections.forEach { Log.i(TAG, "spike: offer-line $it") }
    }

    private fun answerOffer(offer: SessionDescription) {
        if (answering) return
        answering = true
        newPeerConnection()
        val connection = pc ?: return
        connection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                if (listenOnly) {
                    connection.transceivers.forEach {
                        it.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                    }
                    Log.i(TAG, "spike: answer direction forced recvonly")
                }
                connection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        connection.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                answerMs = SystemClock.elapsedRealtime()
                                Log.i(TAG, "spike: call answer-sent elapsedMs=$answerMs fromOfferMs=${answerMs - offerSeenMs}")
                                post(JSONObject().put("t", "answer").put("sdp", sdp.description).toString())
                            }

                            override fun onSetFailure(error: String?) = sdpFailure("setLocalDescription(answer)", error)
                            override fun onCreateSuccess(p0: SessionDescription?) = Unit
                            override fun onCreateFailure(error: String?) = sdpFailure("createAnswer", error)
                        }, sdp)
                    }

                    override fun onCreateFailure(error: String?) = sdpFailure("createAnswer", error)
                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(error: String?) = sdpFailure("setRemoteDescription", error)
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String?) = sdpFailure("setRemoteDescription(offer)", error)
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = sdpFailure("setRemoteDescription", error)
        }, offer)
    }

    private fun applyAnswer(answer: SessionDescription) {
        val connection = pc ?: return
        connection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                answerMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "spike: call answer-received elapsedMs=$answerMs fromOfferMs=${answerMs - offerSentMs}")
            }

            override fun onSetFailure(error: String?) = sdpFailure("setRemoteDescription(answer)", error)
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = sdpFailure("setRemoteDescription", error)
        }, answer)
    }

    private fun sdpFailure(what: String, error: String?) {
        Log.e(TAG, "spike: $what failed: $error")
    }

    private fun flushCandidates() {
        val connection = pc ?: return
        val pending = ArrayList(earlyCandidates)
        earlyCandidates.clear()
        pending.forEach { connection.addIceCandidate(it) }
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Log.i(TAG, "spike: ice-state $newState")
            if (newState == PeerConnection.IceConnectionState.CONNECTED && connectedMs < 0) {
                connectedMs = SystemClock.elapsedRealtime()
                val from = if (role == "call") offerSentMs else offerSeenMs
                Log.i(TAG, "spike: call connected fromOfferMs=${connectedMs - from}")
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Log.i(TAG, "spike: ice-gathering $newState")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val typ = Regex("typ ([a-z]+)").find(candidate.sdp)?.groupValues?.get(1) ?: "?"
            Log.i(TAG, "spike: local-candidate type=$typ mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex} sdp=${candidate.sdp}")
            post(
                JSONObject()
                    .put("t", "cand")
                    .put("mid", candidate.sdpMid)
                    .put("mline", candidate.sdpMLineIndex)
                    .put("sdp", candidate.sdp)
                    .toString(),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>?) = Unit
    }

    // --- relay loop ---------------------------------------------------------------

    private fun post(body: String) {
        outbox.add(body)
    }

    private fun tick() {
        if (stopped) return
        tick++
        val now = SystemClock.elapsedRealtime()
        if (now - startedMs > durationS * 1000L) {
            Log.i(TAG, "spike: duration reached (${durationS}s)")
            Handler(mainLooper).post { stopSelf() }
            return
        }
        while (true) {
            val body = outbox.poll() ?: break
            relay.send(body)
        }
        val (serverMs, messages, rtt) = relay.poll()
        if (serverMs > 0 && tick % 25 == 0L) {
            Log.i(TAG, "spike: time-sync serverMs=$serverMs elapsedMs=${SystemClock.elapsedRealtime()} rttMs=$rtt")
        }
        for (body in messages) {
            try {
                handle(JSONObject(body))
            } catch (e: Exception) {
                Log.w(TAG, "spike: bad relay message: ${e.message}")
            }
        }
        if (tick % 10 == 0L) collectStats()
        if (role == "call" && toneAfterS > 0 && !tonePlayed && connectedMs > 0 &&
            now - connectedMs > (toneAfterS * 1000).toLong()
        ) {
            tonePlayed = true
            toneStartMs = AudioProbe.playTone(0.35, 2.0)
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "offer" -> {
                if (role != "answer") return
                offerSeenMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "spike: call offer-received elapsedMs=$offerSeenMs")
                answerOffer(SessionDescription(SessionDescription.Type.OFFER, msg.getString("sdp")))
            }

            "answer" -> {
                if (role != "call") return
                applyAnswer(SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp")))
            }

            "cand" -> {
                val candidate = IceCandidate(
                    msg.optString("mid", "0"),
                    msg.optInt("mline", 0),
                    msg.getString("sdp"),
                )
                val connection = pc
                if (connection != null && remoteSet) {
                    connection.addIceCandidate(candidate)
                } else {
                    earlyCandidates.add(candidate)
                }
            }

            "bye" -> {
                Log.i(TAG, "spike: peer said bye")
                Handler(mainLooper).post { stopSelf() }
            }
        }
    }

    private fun collectStats() {
        val connection = pc ?: return
        connection.getStats { report ->
            for ((_, s: RTCStats) in report.statsMap) {
                when (s.type) {
                    "outbound-rtp" -> {
                        val sent = num(s, "packetsSent")
                        if (sent > 0 && firstOutMs < 0) {
                            firstOutMs = SystemClock.elapsedRealtime()
                            val from = if (role == "call") offerSentMs else offerSeenMs
                            Log.i(TAG, "spike: call first-audio side=out fromOfferMs=${firstOutMs - from}")
                        }
                        packetsOut = maxOf(packetsOut, sent)
                        Log.i(
                            TAG,
                            "spike: stats out kind=${s.members["kind"]} packets=${sent.toInt()} " +
                                "bytes=${num(s, "bytesSent").toInt()} level=${s.members["audioLevel"]}",
                        )
                    }

                    "inbound-rtp" -> {
                        val received = num(s, "packetsReceived")
                        if (received > 0 && firstInMs < 0) {
                            firstInMs = SystemClock.elapsedRealtime()
                            val from = if (role == "call") offerSentMs else offerSeenMs
                            Log.i(TAG, "spike: call first-audio side=in fromOfferMs=${firstInMs - from}")
                        }
                        packetsIn = maxOf(packetsIn, received)
                        Log.i(
                            TAG,
                            "spike: stats in kind=${s.members["kind"]} packets=${received.toInt()} " +
                                "bytes=${num(s, "bytesReceived").toInt()} lost=${num(s, "packetsLost").toInt()} " +
                                "jitter=${s.members["jitter"]} level=${s.members["audioLevel"]}",
                        )
                    }

                    "candidate-pair" -> {
                        if (s.members["state"] == "succeeded" && s.members["nominated"] == true) {
                            Log.i(
                                TAG,
                                "spike: stats pair rtt=${num(s, "currentRoundTripTime")} " +
                                    "local=${s.members["localCandidateId"]} remote=${s.members["remoteCandidateId"]}",
                            )
                        }
                    }

                    "local-candidate", "remote-candidate" -> Log.i(
                        TAG,
                        "spike: stats ${s.type} type=${s.members["candidateType"]} " +
                            "protocol=${s.members["protocol"]} address=${s.members["address"]} " +
                            "port=${s.members["port"]}",
                    )

                    "codec" -> Log.i(
                        TAG,
                        "spike: stats codec mime=${s.members["mimeType"]} clock=${s.members["clockRate"]} " +
                            "channels=${s.members["channels"]}",
                    )

                    "transport" -> Log.i(TAG, "spike: stats transport dtls=${s.members["dtlsState"]}")

                    "media-source" -> Log.i(
                        TAG,
                        "spike: stats source level=${s.members["audioLevel"]} " +
                            "totalEnergy=${s.members["totalAudioEnergy"]}",
                    )
                }
            }
        }
    }

    private fun num(s: RTCStats, key: String): Double {
        val v = s.members[key]
        return if (v is Number) v.toDouble() else 0.0
    }

    private fun closeCall() {
        try {
            post(JSONObject().put("t", "bye").toString())
            relay.send(JSONObject().put("t", "bye").toString())
        } catch (_: Exception) {
        }
        try {
            pc?.close()
            pc?.dispose()
        } catch (_: Exception) {
        }
        pc = null
        try {
            source?.dispose()
            track?.dispose()
        } catch (_: Exception) {
        }
        source = null
        track = null
        try {
            factory.dispose()
        } catch (_: Exception) {
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Intercom spike", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Intercom spike")
            .setContentText("role=$role room=$room")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "TabulaIntercomSpike"
        const val ACTION_START = "net.lockney.tabula.spike.intercom.START"
        const val ACTION_STOP = "net.lockney.tabula.spike.intercom.STOP"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "spike"
        private const val POLL_MS = 200L
    }
}
