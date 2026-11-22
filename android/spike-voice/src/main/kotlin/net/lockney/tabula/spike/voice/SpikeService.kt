package net.lockney.tabula.spike.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * The spike's microphone foreground service. Everything is driven over adb:
 *
 *   adb shell am start-foreground-service -n net.lockney.tabula.spike.voice/.SpikeService \
 *       --es cmd <sources|effects|aec|capture|cost|wake|play|stop> ...
 *
 * Each job writes structured `SPIKE ...` lines to logcat under [SPIKE_TAG];
 * tools/device/check_p70.sh collects them.
 */
class SpikeService : Service() {
    private var worker: Thread? = null

    @Volatile
    private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice spike", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val cmd = intent?.getStringExtra("cmd") ?: "noop"
        Log.i(SPIKE_TAG, "SPIKE service cmd=$cmd")
        if (cmd == "stop") {
            cancelled = true
            stopSelf()
            return START_NOT_STICKY
        }
        startJob(cmd, intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelled = true
        worker?.join(3000)
        super.onDestroy()
    }

    private fun startJob(cmd: String, intent: Intent?) {
        cancelled = true
        worker?.join(3000)
        cancelled = false
        val extras = intent ?: Intent()
        val thread = Thread({
            val started = System.nanoTime()
            try {
                dispatch(cmd, extras)
            } catch (e: Throwable) {
                Log.e(SPIKE_TAG, "SPIKE job failed cmd=$cmd", e)
            } finally {
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(SPIKE_TAG, "SPIKE done cmd=$cmd ms=$ms")
            }
        }, "spike-$cmd")
        worker = thread
        thread.start()
    }

    private fun dispatch(cmd: String, intent: Intent) {
        when (cmd) {
            "sources" -> probeSources()
            "effects" -> probeEffects()
            "aec" -> aecTest(intent.getIntExtra("seconds", 3))
            "capture" -> captureToFile(
                intent.getIntExtra("seconds", 10),
                intent.getStringExtra("name") ?: "clip",
                intent.getIntExtra("source", MediaRecorder.AudioSource.VOICE_RECOGNITION),
            )
            "cost" -> costRun(intent.getIntExtra("seconds", 60), intent.getStringExtra("model"), false)
            "wake" -> costRun(intent.getIntExtra("seconds", 60), intent.getStringExtra("model"), true)
            "play" -> {
                val player = ClipPlayer()
                player.play(ClipPlayer.speechBandClip(intent.getIntExtra("seconds", 5)))
                player.stop()
            }
            else -> Log.i(SPIKE_TAG, "SPIKE unknown cmd=$cmd")
        }
    }

    // --- question 3: which AudioSource gives usable 16 kHz mono -------------

    private fun probeSources() {
        val sources = linkedMapOf(
            "mic" to MediaRecorder.AudioSource.MIC,
            "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
            "voice_call" to MediaRecorder.AudioSource.VOICE_CALL,
            "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sources["voice_performance"] = MediaRecorder.AudioSource.VOICE_PERFORMANCE
        }
        for ((name, source) in sources) {
            if (cancelled) return
            val cap = Capture(source)
            if (!cap.open()) {
                Log.i(SPIKE_TAG, "SPIKE source=$name result=unavailable error=${cap.openError}")
                continue
            }
            val rate = cap.actualSampleRate
            val channels = cap.actualChannels
            val format = cap.actualFormat
            val pcm = record(cap, 2.0)
            cap.close()
            Log.i(
                SPIKE_TAG,
                "SPIKE source=$name result=ok sr=$rate ch=$channels " +
                    "fmt=$format samples=${pcm.size} rms_db=${fmt(Levels.rmsDb(pcm))} " +
                    "peak=${Levels.peak(pcm)} active=${fmt(Levels.activeFraction(pcm), 2)}",
            )
        }
    }

    // --- question 4: is there an echo canceller ----------------------------

    private fun probeEffects() {
        Log.i(
            SPIKE_TAG,
            "SPIKE effects aec=${AcousticEchoCanceler.isAvailable()} " +
                "ns=${NoiseSuppressor.isAvailable()} agc=${AutomaticGainControl.isAvailable()}",
        )
        val descriptors = try {
            AudioEffect.queryEffects().asList()
        } catch (e: Exception) {
            Log.w(SPIKE_TAG, "SPIKE effects query failed: $e")
            emptyList()
        }
        for (descriptor in descriptors) {
            Log.i(
                SPIKE_TAG,
                "SPIKE effect type=${descriptor.type} name=${descriptor.name} " +
                    "implementor=${descriptor.implementor}",
            )
        }
    }

    /**
     * Plays the generated clip through the speakers while recording it back,
     * once with the platform echo canceller off and once with it on, plus a
     * silent pass for the room's own noise floor. The difference between the
     * two echo windows is the echo return loss enhancement.
     */
    private fun aecTest(clipSeconds: Int) {
        Log.i(
            SPIKE_TAG,
            "SPIKE aec available=${AcousticEchoCanceler.isAvailable()} " +
                "ns=${NoiseSuppressor.isAvailable()}",
        )
        val clip = ClipPlayer.speechBandClip(clipSeconds)
        val leadMs = 400L
        val tailMs = 800L
        val measured = linkedMapOf<String, Double>()
        for ((name, canceller) in listOf("silent" to null, "off" to false, "on" to true)) {
            if (cancelled) return
            val cap = Capture(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                echoCancel = canceller ?: false,
                noiseSuppress = false,
            )
            if (!cap.open()) {
                Log.i(SPIKE_TAG, "SPIKE aec pass=$name result=unavailable error=${cap.openError}")
                continue
            }
            val rate = cap.actualSampleRate
            val span = (clipSeconds + 1.6).toDouble()
            val recorder = Recorder(cap, (rate * span).toInt())
            val player = ClipPlayer(rate)
            recorder.start()
            Thread.sleep(leadMs)
            val playFrom = recorder.written
            if (canceller != null) player.play(clip)
            Thread.sleep((clipSeconds * 1000L) + tailMs)
            recorder.stop()
            if (canceller != null) player.stop()
            cap.close()

            // The echo window: the clip's body, with a margin at each end so
            // the player's start and stop transients stay out of it.
            val from = playFrom + (rate * 0.1).toInt()
            val to = minOf(playFrom + (rate * (clipSeconds - 0.1)).toInt(), recorder.written)
            if (to <= from) {
                Log.i(SPIKE_TAG, "SPIKE aec pass=$name result=short window from=$from to=$to")
                continue
            }
            val window = recorder.pcm.copyOfRange(from, to)
            val db = Levels.rmsDb(window)
            measured[name] = db
            Log.i(
                SPIKE_TAG,
                "SPIKE aec pass=$name sr=$rate window=${window.size} " +
                    "rms_db=${fmt(db)} peak=${Levels.peak(window)} " +
                    "active=${fmt(Levels.activeFraction(window), 2)} aec_enabled=${cap.echoCancellerEnabled}",
            )
        }
        val off = measured["off"]
        val on = measured["on"]
        if (off != null && on != null) {
            Log.i(SPIKE_TAG, "SPIKE aec erle_db=${fmt(off - on)} off_db=${fmt(off)} on_db=${fmt(on)}")
        }
        val silence = measured["silent"]
        if (silence != null) Log.i(SPIKE_TAG, "SPIKE aec room_floor_db=${fmt(silence)}")
    }

    // --- recording clips for the host scorer and for question 2 -------------

    private fun captureToFile(seconds: Int, name: String, source: Int) {
        val dir = File(getExternalFilesDir(null), "clips")
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.i(SPIKE_TAG, "SPIKE capture result=unavailable error=no_directory path=$dir")
            return
        }
        val cap = Capture(source)
        if (!cap.open()) {
            Log.i(SPIKE_TAG, "SPIKE capture result=unavailable error=${cap.openError}")
            return
        }
        val rate = cap.actualSampleRate
        val file = File(dir, "$name.wav")
        var sumOfSquares = 0.0
        var samples = 0L
        var active = 0L
        val buffer = ShortArray(1600)
        cap.start()
        WavWriter(file, rate.coerceAtLeast(8000), 1).use { writer ->
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (!cancelled && System.nanoTime() < deadline) {
                val n = cap.read(buffer)
                if (n <= 0) continue
                writer.write(buffer, n)
                for (i in 0 until n) {
                    val v = if (buffer[i] < 0) -buffer[i].toInt() else buffer[i].toInt()
                    sumOfSquares += v.toDouble() * v
                    if (v >= 500) active++
                }
                samples += n
            }
        }
        cap.close()
        val rms = if (samples > 0) Math.sqrt(sumOfSquares / samples) else 0.0
        Log.i(
            SPIKE_TAG,
            "SPIKE capture name=$name path=$file sr=$rate samples=$samples " +
                "seconds=${fmt(if (rate > 0) samples / rate.toDouble() else 0.0, 2)} " +
                "rms_db=${fmt(Levels.dbfs(rms))} " +
                "active=${fmt(if (samples > 0) active.toDouble() / samples else 0.0, 2)}",
        )
    }

    // --- question 5: what continuous detection costs -----------------------

    /**
     * Continuous 16 kHz capture with the feature frontend and, when a model
     * path is given, the wake-word model, logging a cost line every 5 s.
     *
     * The numbers are split by stage (capture, frontend, inference) so the
     * inference cost alone is readable, and [busy_pct] is the share of one core
     * the work took; [cpu_pct] is the process's own CPU time from the kernel's
     * accounting, which also covers threads that are not timed here.
     */
    private fun costRun(seconds: Int, modelPath: String?, verbose: Boolean) {
        val cap = Capture(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (!cap.open()) {
            Log.i(SPIKE_TAG, "SPIKE cost result=unavailable error=${cap.openError}")
            return
        }
        val model = modelPath?.let {
            try {
                WakeWord(File(it))
            } catch (e: Exception) {
                Log.e(SPIKE_TAG, "SPIKE cost model failed: $e")
                null
            }
        }
        val frontend = FeatureFrontend()
        val rows = model?.rowsPerInference() ?: 3
        val bins = model?.binsPerRow() ?: FeatureFrontend.BINS
        val pending = ByteArray(rows * bins)
        var pendingRows = 0
        val pcm = ShortArray(1600)

        Log.i(
            SPIKE_TAG,
            "SPIKE cost start seconds=$seconds ${cap.describe()} model=${model?.describe ?: "none"}",
        )
        val startedAt = System.nanoTime()
        val cpuAtStart = Process.getElapsedCpuTime()
        val deadline = startedAt + seconds * 1_000_000_000L
        var captureNs = 0L
        var frontendNs = 0L
        var invokeNs = 0L
        var frames = 0L
        var invocations = 0L
        var bytes = 0L
        var maxProbability = 0
        var detections = 0
        var nextReport = startedAt + 5_000_000_000L

        cap.start()
        while (!cancelled && System.nanoTime() < deadline) {
            val readStart = System.nanoTime()
            val n = cap.read(pcm)
            captureNs += System.nanoTime() - readStart
            if (n <= 0) continue
            bytes += n * 2L
            val framesBefore = frontend.frames
            val feStart = System.nanoTime()
            val invokeBefore = invokeNs
            frontend.feed(pcm, n) { frame ->
                if (model != null) {
                    System.arraycopy(frame, 0, pending, pendingRows * bins, bins)
                    pendingRows++
                    // Every frame is consumed, so this is the cost of the model
                    // at the rate a real detector would run it (one window per
                    // 30 ms), not a third of it.
                    if (pendingRows == rows) {
                        val invokeStart = System.nanoTime()
                        val probability = model.probability(pending)
                        invokeNs += System.nanoTime() - invokeStart
                        invocations++
                        pendingRows = 0
                        if (probability > maxProbability) maxProbability = probability
                        if (verbose && probability >= DETECTION_CUTOFF) {
                            detections++
                            Log.i(SPIKE_TAG, "SPIKE wake prob=$probability detections=$detections")
                        }
                    }
                }
            }
            frames += frontend.frames - framesBefore
            // The model runs inside the frame callback, so its time is inside
            // the feed's; take it back out to keep the two costs apart.
            frontendNs += (System.nanoTime() - feStart) - (invokeNs - invokeBefore)
            val now = System.nanoTime()
            if (now >= nextReport) {
                nextReport += 5_000_000_000L
                val elapsedMs = (now - startedAt) / 1_000_000.0
                val cpuMs = Process.getElapsedCpuTime() - cpuAtStart
                Log.i(
                    SPIKE_TAG,
                    "SPIKE cost t=${fmt(elapsedMs / 1000.0)}s frames=$frames invocations=$invocations " +
                        "capture_ms=${fmt(captureNs / 1e6)} frontend_ms=${fmt(frontendNs / 1e6)} " +
                        "invoke_ms=${fmt(invokeNs / 1e6)} " +
                        "busy_pct=${pct(frontendNs + invokeNs, now - startedAt)} " +
                        "cpu_pct=${pct(cpuMs * 1_000_000L, now - startedAt)} bytes=$bytes",
                )
            }
        }
        cap.close()
        model?.close()
        val elapsedNs = System.nanoTime() - startedAt
        val elapsedS = elapsedNs / 1e9
        val cpuMs = Process.getElapsedCpuTime() - cpuAtStart
        Log.i(
            SPIKE_TAG,
            "SPIKE cost done seconds=${fmt(elapsedS)} frames=$frames invocations=$invocations " +
                "bytes=$bytes bytes_per_hour=${(bytes / elapsedS * 3600).toLong()} " +
                "frontend_us_per_frame=${fmt(if (frames > 0) frontendNs / 1e3 / frames else 0.0)} " +
                "invoke_us=${fmt(if (invocations > 0) invokeNs / 1e3 / invocations else 0.0)} " +
                "busy_pct=${pct(frontendNs + invokeNs, elapsedNs)} " +
                "cpu_pct=${pct(cpuMs * 1_000_000L, elapsedNs)} max_prob=$maxProbability " +
                "detections=$detections",
        )
    }

    // --- helpers -----------------------------------------------------------

    private fun record(cap: Capture, seconds: Double): ShortArray {
        val rate = if (cap.actualSampleRate > 0) cap.actualSampleRate else 16000
        val recorder = Recorder(cap, (rate * seconds).toInt())
        recorder.start()
        Thread.sleep((seconds * 1000).toLong() + 100)
        recorder.stop()
        return recorder.pcm.copyOf(recorder.written)
    }

    private fun pct(partNs: Long, totalNs: Long): String =
        if (totalNs <= 0) "0.0" else fmt(partNs * 100.0 / totalNs)

    private fun fmt(value: Double, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Tabula voice spike")
            .setContentText("Microphone capture is running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "spike"
        private const val NOTIFICATION_ID = 700

        /** The model's probability above which the spike counts a detection. */
        private const val DETECTION_CUTOFF = 200
    }
}
