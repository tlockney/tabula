package net.lockney.tabula

import android.app.AlarmManager
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.lockney.tabula.core.intercom.AutoAnswerPolicy
import net.lockney.tabula.core.voice.WakeWordDetector
import net.lockney.tabula.ha.CredentialRevoked
import net.lockney.tabula.ha.EntityDataConnection
import net.lockney.tabula.ha.HaChannel
import net.lockney.tabula.ha.OkHttpExchange
import net.lockney.tabula.ha.OkHttpWsTransportFactory
import net.lockney.tabula.ha.PairingClient
import net.lockney.tabula.ha.TlpClient

/**
 * The app's backbone (plan 5.1, FEATURES F-KSK-09): a foreground service that
 * pairs with HA when asked, then keeps the TLP connection up - auth, register,
 * command loop, ACKs, backoff, heartbeat - and reports battery, charging,
 * connectivity and app-version state. It holds a Wi-Fi wake lock for its
 * lifetime so keepalives fire while the screen is off, and starts on boot
 * when the device is already paired.
 *
 * One job runs at a time: pairing (while unpaired) or the session (once
 * paired). A revocation clears the credential and returns to pairing.
 */
class TabulaService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var store: TabulaStore
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var panel: AndroidPanel
    private lateinit var lightSensor: LightSensor
    private lateinit var screen: ScreenManager
    private lateinit var screensaver: ScreensaverManager

    /**
     * The illuminance state key's rate limit (P3.16, ADR 0020): one filter for
     * the service's lifetime, fed by the same [LightSensor] listener that
     * drives adaptive brightness (no second sensor registration).
     */
    private val illuminance = net.lockney.tabula.core.screen.IlluminanceFilter()

    /** Publishes illuminance to the session's state source; null while disconnected. */
    @Volatile
    private var illuminanceListener: ((net.lockney.tabula.core.tlp.StateDelta) -> Unit)? = null

    /**
     * HA core commands on the live TLP connection, null between connections;
     * the screensaver's entity data rides it (ADR 0017, packet P3.5).
     */
    private val haChannel = MutableStateFlow<HaChannel?>(null)
    private val entityData by lazy {
        EntityDataConnection(
            channels = haChannel,
            scope = scope,
            language = { java.util.Locale.getDefault().language },
            log = { TabulaLog.i(TAG, it) },
        )
    }
    private lateinit var notifications: NotificationsManager
    private lateinit var chime: NotificationChime
    private lateinit var audio: MediaManager
    private lateinit var startPage: StartPageManager
    private lateinit var deviceEvents: EventFanIn
    // The intercom (P9.3, ADR 0028): one engine for the app's lifetime, idle
    // until an explicit action or an HA ring, held by its wiring.
    private lateinit var intercom: IntercomManager
    // The call engine itself, for the flows the screen reads (P9.4).
    private lateinit var callEngine: net.lockney.tabula.core.intercom.CallEngine
    // What the intercom screen draws (P9.4): the brain lives here, next to the
    // engine, and the activity only renders what it publishes.
    private lateinit var intercomUi: net.lockney.tabula.core.intercom.IntercomUi
    // Wake word (P7.1, ADR 0030): null until startVoice, off until the setting
    // says otherwise. The channel is the monitor's event source in the fan-in.
    private var voice: WakeWordMonitor? = null
    private val voiceEvents = Channel<(Int) -> net.lockney.tabula.core.tlp.EventFrame>(2)
    @Volatile private var wakeWordListening = false
    @Volatile private var lastWakeWord: String? = null

    /**
     * Half-duplex (ADR 0029 item 3, ADR 0030 item 7): the gate closes the
     * microphone while the tablet plays and reopens it - detector reset - when
     * the sound stops.
     */
    private var voiceGate: net.lockney.tabula.core.voice.WakeWordGate? = null

    /**
     * The microphone's single owner (P9.3 item 8): the wake-word gate asks it
     * for capture, a call that captures takes it, and the detector re-acquires
     * it when that call ends.
     */
    private var micArbiter: net.lockney.tabula.core.intercom.MicArbiter? = null

    /** The media channel's own state, from its deltas. */
    @Volatile private var mediaPlaying = false

    /** Until when a chime is still sounding, in [System.currentTimeMillis] terms. */
    @Volatile private var chimeUntil = 0L
    private var chimeJob: kotlinx.coroutines.Job? = null

    /** Set while a TLP session exists, so a detection reaches HA at once. */
    @Volatile private var publishState: ((net.lockney.tabula.core.tlp.StateDelta) -> Unit)? = null
    private var stopPageListener: (() -> Unit)? = null
    private val mediaFetcher = net.lockney.tabula.ha.MediaFetcher()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = (application as TabulaApp).store
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tabula")?.apply {
                setReferenceCounted(false)
                acquire()
            } ?: error("no WifiManager")
        createChannel()
        startForeground(NOTIFICATION_ID, notification(phaseText(TabulaStatus.phase.value)))
        startScreenControl()
        startScreensaver()
        startStartPage()
        startNotifications()
        startAudio()
        startVoice()
        startIntercom()
        startEvents()
    }

    /**
     * One event stream for the TLP session from every source (P2.4, P2.5, P7.1,
     * P9.3). Built after the managers, because the intercom's stream is one of
     * them.
     */
    private fun startEvents() {
        deviceEvents = EventFanIn(
            scope,
            listOf(screensaver.events, notifications.events, audio.events, voiceEvents, intercom.events),
        )
    }

    /**
     * The intercom engine and its TLP wiring (packet P9.3, ADR 0028). The engine
     * is constructed idle and opens no microphone and no connection until a
     * call begins, so a device that never makes a call pays only for the class.
     * The microphone it may take from the wake-word detector is declared the
     * same way the detector's is: the grant at the moment of use.
     */
    private fun startIntercom() {
        val engine = WebRtcCallEngine(
            context = applicationContext,
            canTalk = ::canTalk,
            // The setting is read per ring, not cached: turning auto-answer off
            // in HA takes effect on the next call (P9.5)
            autoAnswer = { caller ->
                AutoAnswerPolicy.of(store.settings.intercom).answers(caller)
            },
        )
        callEngine = engine
        intercom = IntercomManager(
            engine = engine,
            scope = scope,
            publishState = { delta -> publishState?.invoke(delta) },
            onMicrophoneHold = { held -> micArbiter?.onCallHoldsMic(held) },
            onRing = ::wakeForIntercom,
            log = { TabulaLog.i(TAG, it) },
        )
        intercomUi = net.lockney.tabula.core.intercom.IntercomUi(
            engine = engine,
            start = intercom::startRequest,
            canTalk = ::canTalk,
            clockMs = android.os.SystemClock::elapsedRealtime,
            hangUp = intercom::hangUp,
        )
        observeIntercom()
    }

    /**
     * The screen's side of the intercom (packet P9.4): every engine change and
     * every button re-renders, the directory is fetched when the screen opens,
     * and the duration ticks once a second while a call is up.
     */
    private fun observeIntercom() {
        scope.launch {
            kotlinx.coroutines.flow.combine(callEngine.state, callEngine.info) { _, _ -> Unit }
                .collect {
                    // a broadcast's reply starts here, once the engine is idle
                    intercomUi.onStateChanged()
                    publishIntercom()
                }
        }
        scope.launch {
            TabulaStatus.intercomAction.collect { action ->
                // Each press runs in its own coroutine: a fetch that waits on
                // HA must never hold up the next press, Hang up included
                // (review of #176)
                scope.launch { handleIntercomAction(action) }
            }
        }
        scope.launch {
            // The visible clock: one refresh a second, and only while a call is
            // up. Idle costs one comparison a second and publishes nothing.
            while (true) {
                kotlinx.coroutines.delay(INTERCOM_TICK_MS)
                if (callEngine.state.value != net.lockney.tabula.core.tlp.IntercomState.IDLE) {
                    publishIntercom(intercomUi.tick())
                }
            }
        }
    }

    /** One press from the screen or a dashboard link (P9.4). */
    private suspend fun handleIntercomAction(
        action: net.lockney.tabula.core.intercom.IntercomAction,
    ) {
        when (action) {
            // Opening the list performs the action first, so the sheet is up
            // at once with no peers and the answer fills it; a failed fetch
            // leaves "HA is not reachable" on screen instead of nothing
            // (review of #176, blocking 1)
            net.lockney.tabula.core.intercom.IntercomAction.OpenDirectory -> {
                intercomUi.perform(action)
                publishIntercom()
                fetchIntercomDirectory()
            }

            // A fixed target resolves its label from the list, so the call
            // screen shows the same name the list would
            is net.lockney.tabula.core.intercom.IntercomAction.CallTarget -> {
                fetchIntercomDirectory()
                intercomUi.perform(action)
                publishIntercom()
            }

            else -> {
                intercomUi.perform(action)
                publishIntercom()
            }
        }
    }

    /** Ask HA for the tablets this device may call, over the device's own connection. */
    private suspend fun fetchIntercomDirectory() {
        val listed = net.lockney.tabula.ha.IntercomDirectory.fetch(haChannel.value)
        if (listed == null) {
            intercomUi.onDirectoryFailed(net.lockney.tabula.core.intercom.IntercomUi.UNREACHABLE)
        } else {
            intercomUi.onDirectory(
                listed.map { entry ->
                    net.lockney.tabula.core.intercom.IntercomPeer(
                        deviceId = entry.deviceId,
                        tabulaDeviceId = entry.tabulaDeviceId,
                        name = entry.name,
                        areaId = entry.areaId,
                        areaName = entry.areaName,
                        canTalk = entry.canTalk,
                        inCall = entry.inCall,
                    )
                },
            )
        }
        publishIntercom()
    }

    private fun publishIntercom(
        screen: net.lockney.tabula.core.intercom.IntercomScreen = intercomUi.screen,
    ) {
        TabulaStatus.intercomScreen.value = screen
    }

    /**
     * An incoming call wakes the panel (P9.4 item 2, F-SCR-14) and draws over
     * whatever is showing, the screensaver included; it does not dismiss the
     * screensaver, so a call at night does not leave the dashboard lit.
     */
    private fun wakeForIntercom() {
        TabulaLog.i(TAG, "intercom: a call arrived; waking the panel")
        screen.command(true, null)
        // The ring names its caller by TLP id, and a broadcast's Reply needs
        // that caller's HA id to call back: the list is what holds the pair
        scope.launch { fetchIntercomDirectory() }
    }

    /** True while the app holds the microphone `intercom_talk` promises. */
    private fun canTalk(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Screen control (packet P2.3) runs for the service's lifetime, not the
     * session's, so adaptive brightness keeps working while HA is down
     * (F-SCR-05). The stored settings are applied at start only once HA has
     * sent a document: a freshly installed app leaves the system brightness
     * alone (F-SCR-02, ADR 0011).
     */
    private fun startScreenControl() {
        panel = AndroidPanel(applicationContext) { on ->
            screen.onPanelPower(on)
            if (::screensaver.isInitialized) screensaver.setPanelOn(on)
        }
        screen = ScreenManager(
            controller = net.lockney.tabula.core.screen.ScreenController(),
            panel = panel,
            persist = { screenSettings -> store.settings = store.settings.copy(screen = screenSettings) },
            scope = scope,
        )
        if (store.settingsRevision > 0) screen.applySettings(store.settings)
        lightSensor = LightSensor(applicationContext) { lux ->
            screen.onLux(lux)
            // the same reading feeds the illuminance state key (P3.16): one
            // listener, two consumers
            illuminance.reading(lux, System.currentTimeMillis())?.let { reported ->
                illuminanceListener?.invoke(
                    net.lockney.tabula.core.tlp.StateDelta(illuminance = reported)
                )
            }
        }
        lightSensor.start()
    }

    /**
     * The screensaver (packet P2.4) runs for the service's lifetime too. It
     * draws through [TabulaStatus.screensaver], holds the backlight through
     * screen control, and hears touches and foreground changes from the
     * activity. Stored settings apply at start, or the defaults on a fresh
     * install (ADR 0002: the first milestone idles to a clock).
     */
    private fun startScreensaver() {
        screensaver = ScreensaverManager(
            controller = net.lockney.tabula.core.screensaver.ScreensaverController(),
            show = { mode -> TabulaStatus.screensaver.value = mode },
            holdBrightness = screen::setOverride,
            scope = scope,
            entityData = entityData::start,
        )
        screensaver.applySettings(store.settings)
        screensaver.setPanelOn(panel.isOn)
        screensaver.start()
        // the overlays draw from the connection's own flows (F-AAG-04, P3.6): the
        // activity reads the live states and HA's display data from here
        scope.launch { entityData.states.collect { TabulaStatus.entityStates.value = it } }
        scope.launch { entityData.display.collect { TabulaStatus.entityDisplay.value = it } }
        scope.launch { TabulaStatus.userActivity.collect { screensaver.onActivity() } }
        scope.launch { TabulaStatus.foreground.collect { screensaver.setForeground(it) } }
    }

    /**
     * The return-to-start timer (packet P3.15, F-DSH-15): runs for the
     * service's lifetime, fed by touches and page changes, independent of the
     * screensaver - it keeps counting behind it and while the panel is off,
     * so a covered dashboard resets without lighting the screen. The
     * settings' delay defaults to never (ADR 0002), so a tablet that never
     * received the keys behaves as before.
     */
    private fun startStartPage() {
        val bus = (application as TabulaApp).dashboard
        startPage = StartPageManager(
            controller = net.lockney.tabula.core.dashboard.StartPageController(),
            dashboard = bus,
            scope = scope,
        )
        startPage.applySettings(store.settings)
        startPage.start()
        scope.launch { TabulaStatus.userActivity.collect { startPage.onActivity() } }
        // a page change - the user's or HA's - resets the countdown (ADR 0021);
        // listeners must be quick, and the controller call is synchronized
        stopPageListener = bus.onPageChanged { startPage.onPageChanged() }
    }

    /**
     * Notifications (packet P2.5) run for the service's lifetime too. Cards
     * arrive only from HA, but the ones already up keep timing out while HA
     * is down, and the chime is a synthesized tone on the media stream at
     * `audio.chime_volume` (F-NTF-09, ADR 0027). A card's tap travels back through
     * [TabulaStatus.cardDismiss]; it removes only that card (F-NTF-08).
     */
    private fun startNotifications() {
        chime = NotificationChime(volume = { store.settings.audio.chimeVolume })
        notifications = NotificationsManager(
            controller = net.lockney.tabula.core.notifications.NotificationCards(),
            show = { cards -> TabulaStatus.notifications.value = cards },
            chime = ::playChime,
            scope = scope,
        )
        notifications.applySettings(store.settings)
        notifications.start()
        scope.launch { TabulaStatus.cardDismiss.collect { notifications.dismiss(it) } }
    }

    /**
     * The media player (packet P2.5) plays HA TTS and media URLs, fetched
     * with no credential and redirects followed within the ADR 0002 bounds
     * (PROTOCOL 4.5, ADR 0001 item 5), on the OS music stream at the
     * settings' media volume (plan 5.6). Files left by an abrupt death are
     * swept at start.
     */
    private fun startAudio() {
        MediaManager.sweepStale(cacheDir)
        audio = MediaManager(
            controller = net.lockney.tabula.core.media.MediaController(),
            haUrl = { store.haUrl },
            download = ::downloadMedia,
            player = AndroidPlayer,
            audio = AndroidAudioVolume(applicationContext),
            scope = scope,
        )
        audio.applySettings(store.settings)
    }

    /**
     * The wake-word monitor (packet P7.1, ADR 0030). Constructed always and
     * started only when the setting asks for it, so turning the setting off and
     * on does not recreate the service. The microphone is opened lazily by
     * [WakeWordMonitor.start], so a device that never enables the feature never
     * holds it.
     */
    private fun startVoice() {
        val monitor = WakeWordMonitor(
            context = applicationContext,
            scope = scope,
            onDetection = ::onWakeWord,
            onListening = { listening ->
                val changed = wakeWordListening != listening
                wakeWordListening = listening
                if (changed) {
                    publishState?.invoke(
                        net.lockney.tabula.core.tlp.StateDelta(wakeWordActive = listening),
                    )
                }
            },
            log = { TabulaLog.i(TAG, it) },
        )
        voice = monitor
        val mic = net.lockney.tabula.core.intercom.MicArbiter(
            object : net.lockney.tabula.core.intercom.MicArbiter.Listener {
                override fun startListening(): Boolean = monitor.start()

                override fun stopListening() {
                    monitor.pause()
                }
            },
        )
        micArbiter = mic
        val gate = net.lockney.tabula.core.voice.WakeWordGate(
            object : net.lockney.tabula.core.voice.WakeWordGate.Listener {
                override fun startListening(): Boolean = mic.onWakeWordWanted(true)

                override fun stopListening() {
                    mic.onWakeWordWanted(false)
                }
            },
        )
        voiceGate = gate
        gate.update(
            enabled = store.settings.voice.wakeWordEnabled,
            permitted = monitor.permitted(),
            playing = playingNow(),
        )
        // a grant arrives after the settings push that asked for it, so the
        // gate hears the activity's answer rather than waiting for the next push
        scope.launch {
            TabulaStatus.wakeWordPermission.collect { granted -> gate.onPermission(granted) }
        }
    }

    /**
     * The settings push's voice part: the gate applies the setting, and re-reads
     * the grant because Fire OS can withdraw it at boot (F-KSK-21).
     */
    private fun applyVoiceSettings(settings: net.lockney.tabula.core.settings.Settings) {
        val gate = voiceGate ?: return
        voice?.let { gate.onPermission(it.permitted()) }
        gate.onSetting(settings.voice.wakeWordEnabled)
    }

    /** True while the tablet is making a sound its own microphone would hear. */
    private fun playingNow(): Boolean =
        mediaPlaying || System.currentTimeMillis() < chimeUntil

    /** Re-applies the playback input after the media state or a chime moved. */
    private fun updatePlaying() {
        voiceGate?.onPlaying(playingNow())
    }

    /**
     * The notification chime: plays the beep and keeps detection closed for
     * [net.lockney.tabula.core.Constants.WAKE_WORD_CHIME_GUARD_MS]. The tone has
     * no completion callback, and half-duplex needs to know when the speaker
     * stops (ADR 0029 item 3).
     */
    private fun playChime() {
        chime.play()
        chimeUntil = System.currentTimeMillis() +
            net.lockney.tabula.core.Constants.WAKE_WORD_CHIME_GUARD_MS
        updatePlaying()
        chimeJob?.cancel()
        chimeJob = scope.launch {
            kotlinx.coroutines.delay(
                net.lockney.tabula.core.Constants.WAKE_WORD_CHIME_GUARD_MS,
            )
            updatePlaying()
        }
    }

    /**
     * One detection: timestamp the state key before the event goes out, so a
     * subscriber that sees the event can read the sensor's new value, and queue
     * the TLP event behind any earlier ones (bounded at two, ADR 0002).
     */
    private fun onWakeWord(detection: WakeWordDetector.Detection) {
        val at = wakeWordTimestamp()
        lastWakeWord = at
        TabulaLog.i(TAG, "wake word: ${detection.phrase} at ${detection.probability}/255")
        publishState?.invoke(
            net.lockney.tabula.core.tlp.StateDelta(
                wakeWordActive = wakeWordListening,
                lastWakeWord = at,
            ),
        )
        voiceEvents.trySend { id ->
            net.lockney.tabula.core.tlp.EventFrame.wakeWordDetected(
                id,
                detection.phrase,
                detection.probability,
            )
        }
    }

    /**
     * Fetch media audio to a uniquely named cache file (no Authorization
     * header, ADR 0001 item 5); the manager deletes it at the playback's
     * terminal state, and [MediaManager.sweepStale] removes any that survived
     * a process death.
     */
    private suspend fun downloadMedia(url: String): java.io.File {
        val file = java.io.File.createTempFile("tabula-media", null, cacheDir)
        mediaFetcher.fetch(url, file)
        return file
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> {
                val haUrl = intent.getStringExtra(EXTRA_HA_URL)?.trim()?.trimEnd('/')
                if (!haUrl.isNullOrEmpty()) {
                    store.haUrl = haUrl
                    intent.getStringExtra(EXTRA_NAME)?.let { typed ->
                        // the name HA shows (F-MGT-03, issue #79); its own key so
                        // HA's settings pushes do not erase it (issue #100)
                        val name = PairingName.resolve(typed, defaultPairingName(this, store.deviceId))
                        store.pairingName = name
                        store.settings = store.settings.copy(identity = store.settings.identity.copy(name = name))
                    }
                    startPairing()
                }
            }
            ACTION_UNPAIR -> {
                store.clearCredential()
                PairingScreen.attemptStarted()
                job?.cancel()
                job = null
            }
            else -> if (job == null) startNext()
        }
        observePhases()
        return START_STICKY
    }

    override fun onDestroy() {
        screensaver.stop()
        startPage.stop()
        stopPageListener?.invoke()
        stopPageListener = null
        entityData.stop()
        notifications.stop()
        chime.release()
        audio.close()
        // a call cannot outlive the service: nothing is resumed on restart
        // (ADR 0028 guard 4)
        intercom.close()
        TabulaStatus.intercomScreen.value = net.lockney.tabula.core.intercom.IntercomScreen.Closed
        screen.setOverride(null)
        TabulaStatus.screensaver.value = null
        TabulaStatus.notifications.value = emptyList()
        lightSensor.stop()
        panel.close()
        job?.cancel()
        scope.cancel()
        runCatching { wifiLock.release() }
        super.onDestroy()
    }

    // --- what runs -----------------------------------------------------------

    /** Start whichever job the stored state calls for. */
    private fun startNext() {
        if (job?.isActive == true) return
        if (store.isPaired) startSession() else {
            // Paired state without a credential can still have an HA URL saved.
            // No attempt runs after a restart, so neither a code nor the
            // "retrying" line from before it may stay on the form (issue #127).
            TabulaStatus.phase.value = Phase.UNPAIRED
            TabulaStatus.pairing.value = null
            TabulaStatus.pairingNotice.value = null
        }
    }

    private fun startPairing() {
        val haUrl = store.haUrl ?: return
        job?.cancel()
        job = scope.launch {
            // The form stays up until HA has issued a code: an attempt that
            // cannot reach HA is not something to confirm, and showing the
            // confirm screen for it left a blank code on screen (issue #127).
            PairingScreen.attemptStarted()
            try {
                val http = OkHttpExchange()
                val paired = PairingClient(http).pair(
                    haUrl,
                    store.deviceId,
                    // the typed or default name (issue #79), never the bare
                    // manufacturer: every Fire tablet would pair as "Amazon"
                    store.nameForPairing(defaultPairingName(this@TabulaService, store.deviceId)),
                    PairingName.clip(Build.MODEL),
                    onStatus = { status ->
                        PairingScreen.codeIssued(status.code, status.deviceId.take(8))
                    },
                    onUnreachable = { PairingScreen.unreachableNow(haUrl) },
                )
                // The claim released the credential exactly once (PROTOCOL 2.5):
                // savePairing commits synchronously, so it is durable before the
                // claim is treated as complete. If the write fails, do not start
                // a session with a credential that will be gone next boot - the
                // user pairs again and deletes the HA entry this claim created.
                if (!store.savePairing(paired.credential.refreshToken, paired.haDeviceId)) {
                    TabulaLog.e(TAG, "could not persist the pairing credential; pair again")
                    PairingScreen.failed("Pairing failed: the credential could not be saved on this device. Pair again.")
                    return@launch
                }
                PairingScreen.paired()
                startSession()
            } catch (e: CredentialRevoked) {
                TabulaStatus.phase.value = Phase.REVOKED
            } catch (e: net.lockney.tabula.ha.PairingException) {
                // Terminal pairing failure (bad nonce, refused request): show it
                // as a pairing error, PROTOCOL 2.5, then back to the pairing form.
                PairingScreen.failed("Pairing failed: ${e.message ?: e.name.name}. Pair again.")
            } catch (e: Exception) {
                // A restart cancels the running pairing job, and that surfaces
                // here as CancellationException; rethrow it (the block below is
                // for environment failures) or the restart leaves a stale
                // pairing error behind.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Anything else escaping pair() is an environment failure or a bug;
                // it must surface as a pairing error, never an uncaught exception
                // in a coroutine that kills the app process.
                TabulaLog.e(TAG, "pairing failed", e)
                PairingScreen.failed("Pairing failed: ${e.message ?: e.javaClass.simpleName}. Pair again.")
            }
        }
    }

    private fun startSession() {
        val haUrl = store.haUrl ?: return
        val refreshToken = store.refreshToken ?: return startPairing()
        job?.cancel()
        job = scope.launch {
            val state = AndroidStateSource(
                applicationContext,
                versionName(),
                (application as TabulaApp).dashboard,
                extra = ::extraState,
            )
            publishState = state::publish
            screen.listener = state::publish
            screensaver.listener = state::publish
            // the media state is also the half-duplex input (ADR 0030 item 7)
            audio.listener = { delta ->
                delta.mediaState?.let { media ->
                    mediaPlaying = media == net.lockney.tabula.core.tlp.MediaState.PLAYING
                    updatePlaying()
                }
                state.publish(delta)
            }
            mediaPlaying =
                audio.current().mediaState == net.lockney.tabula.core.tlp.MediaState.PLAYING
            updatePlaying()
            illuminanceListener = state::publish
            TabulaStatus.phase.value = Phase.CONNECTING
            // P5.4: one ring for the whole app, one subscription for the session.
            val logStream = LogStream()
            val client = TlpClient(
                TlpClient.Config(
                    haUrl = haUrl,
                    deviceId = store.deviceId,
                    appVersion = versionName(),
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    osVersion = Build.VERSION.RELEASE ?: "unknown",
                    capabilities = capabilities(),
                    settingsRevision = { store.settingsRevision },
                    refreshToken = { refreshToken },
                    state = state,
                    commands = TabulaCommands(
                        store,
                        onRestartApp = ::restartApp,
                        dashboard = (application as TabulaApp).dashboard,
                        screen = screen,
                        screensaver = screensaver,
                        notifications = notifications,
                        audio = audio,
                        screenshot = ScreenshotFlow(
                            bus = (application as TabulaApp).dashboard,
                            http = OkHttpExchange(),
                            accessToken = {
                                (application as TabulaApp).tokens
                                    .access(haUrl) { refreshToken }
                            },
                            haUrl = { haUrl },
                            screenOn = { screen.current().screenOn ?: true },
                        ),
                        startPage = startPage,
                        voice = ::applyVoiceSettings,
                        logs = logStream,
                        intercom = intercom,
                    ),
                    events = deviceEvents.events,
                    logs = logStream.frames,
                ),
                (application as TabulaApp).tokens,
                OkHttpWsTransportFactory(),
                logger = { line -> TabulaLog.i(TAG, line) },
            )
            val events = object : TlpClient.Events {
                override fun onRegistered(result: net.lockney.tabula.core.tlp.RegisterResultData) {
                    TabulaLog.i(TAG, "registered with HA device ${result.haDeviceId}")
                    TabulaStatus.phase.value = Phase.CONNECTED
                    // a call that was up when the link went down stays up if
                    // HA came back inside the grace (P9.3 guard 3)
                    intercom.onTransportUp()
                }

                override fun onDisconnected() {
                    TabulaLog.i(TAG, "connection lost; reconnecting with backoff")
                    if (TabulaStatus.phase.value == Phase.CONNECTED) {
                        TabulaStatus.phase.value = Phase.CONNECTING
                    }
                    intercom.onTransportDown()
                }

                override fun onRevoked() {
                    TabulaLog.w(TAG, "credential revoked in HA; back to pairing")
                    store.clearCredential()
                    TabulaStatus.phase.value = Phase.REVOKED
                }

                override fun onRejected(code: String, message: String) {
                    TabulaLog.w(TAG, "registration refused: $code: $message")
                    TabulaStatus.rejection.value = code to message
                    TabulaStatus.phase.value = Phase.REJECTED
                }
            }
            val channelJob = launch { client.channel.collect { haChannel.value = it } }
            try {
                client.run(events)
            } finally {
                channelJob.cancel()
                logStream.close()
                haChannel.value = null
                screen.listener = null
                screensaver.listener = null
                audio.listener = null
                illuminanceListener = null
                state.close()
            }
        }
    }

    /** restart_app: answer first (the client does), then relaunch this process. */
    private fun restartApp() {
        // The activity lives in this process, so starting it here would die
        // with the process below. The relaunch is handed to the system as an
        // alarm that fires after the exit (ADR 0002: RESTART_RELAUNCH_DELAY).
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val relaunch = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val alarms = getSystemService(AlarmManager::class.java)
            val at = android.os.SystemClock.elapsedRealtime() +
                net.lockney.tabula.core.Constants.RESTART_EXIT_DELAY_MS +
                net.lockney.tabula.core.Constants.RESTART_RELAUNCH_DELAY_MS
            alarms?.setExact(AlarmManager.ELAPSED_REALTIME, at, relaunch)
        }
        scope.launch {
            // give the result frame time to reach HA before the process dies
            // (ADR 0002: RESTART_EXIT_DELAY)
            kotlinx.coroutines.delay(net.lockney.tabula.core.Constants.RESTART_EXIT_DELAY_MS)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Screen, screensaver, media and illuminance state for registration (PROTOCOL 4.2). */
    private fun extraState(): net.lockney.tabula.core.tlp.StateDelta {
        val saver = screensaver.current()
        val screenAndSaver = screen.current().copy(
            screensaverActive = saver.screensaverActive,
            screensaverMode = saver.screensaverMode,
            screensaverBrightness = saver.screensaverBrightness,
            nextScreensaver = saver.nextScreensaver,
            // the key exists only once the sensor has reported (P3.16); the
            // filter holds the latest reading across a disconnect
            illuminance = illuminance.value,
        )
        return net.lockney.tabula.ha.mergeState(screenAndSaver, audio.current())
            .copy(
                // The APK always ships the detector, so this key always exists:
                // `wakeWordActive` carries the truth (microphone open and
                // features being computed), and null never goes on the wire
                // while the capability that covers it is declared (ADR 0030
                // items 3 and 7, PROTOCOL 4.4)
                wakeWordActive = wakeWordListening,
                lastWakeWord = lastWakeWord,
                // `intercom_state` always exists because the device always
                // declares `intercom_listen` (PROTOCOL 4.4's gate)
                intercomState = intercom.state(),
            )
    }

    /**
     * The capabilities this device declares (PROTOCOL 4.2): battery (P2.1),
     * navigation (P2.2, the dashboard WebView follows a navigate command and
     * reports the current page), go_home (P3.15, the start target and the
     * go_home command), screen and brightness (P2.3), adaptive_brightness and
     * illuminance only when the tablet has a light sensor (P3.16),
     * screensaver (P2.4), notifications and audio (P2.5), and screenshot
     * (P3.13, the capture-and-upload flow).
     */
    private fun capabilities(): List<String> = buildList {
        add("battery")
        add("navigation")
        add("go_home")
        add("screen")
        add("brightness")
        if (lightSensor.available) {
            add("adaptive_brightness")
            add("illuminance")
        }
        add("screensaver")
        add("notifications")
        add("audio")
        add("screenshot")
        // P5.4: the app log and the dashboard console stream to the Tabularium.
        add("logs")
        // P9.3: every Tabula device can hear a call; only one holding
        // RECORD_AUDIO at registration can capture one, and the list is read
        // once per session, so a withdrawn grant shows up at the next register
        // (PROTOCOL 4.5, ADR 0025 decision 7, F-KSK-21)
        addAll(intercomCapabilities(canTalk()))
        // Declared unconditionally: every build ships the detector, and the
        // capability list is read once per session, so a value that tracked the
        // switch would only reach HA on the next register - and turning the
        // switch off would make the integration delete the entities rather than
        // leave them unavailable. `wake_word_active` says whether the
        // microphone is actually open, which also covers a withdrawn grant
        // (ADR 0030 items 3 and 5, F-KSK-21)
        add("wake_word")
    }

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    // --- notification ----------------------------------------------------------

    private var observing = false

    private fun observePhases() {
        if (observing) return
        observing = true
        scope.launch {
            TabulaStatus.phase.collect { phase ->
                TabulaLog.i(TAG, "phase: $phase")
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification(phaseText(phase)))
            }
        }
    }

    private fun phaseText(phase: Phase): String = when (phase) {
        Phase.UNPAIRED -> "Waiting to pair with Home Assistant"
        Phase.PAIRING -> "Pairing - confirm the code in Home Assistant"
        Phase.CONNECTING -> "Connecting to Home Assistant"
        Phase.CONNECTED -> "Connected to Home Assistant"
        Phase.REVOKED -> "Credential revoked - pair again"
        Phase.REJECTED -> "Home Assistant refused registration"
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tabula connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // the picker source's photo picker (P3.8, F-SSV-12): the only
        // device-side UI surface the kiosk has (ADR 0016)
        val pickPhotos = PendingIntent.getActivity(
            this,
            1,
            Intent(this, PhotoPickActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tabula")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_gallery),
                    "Pick photos",
                    pickPhotos,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_PAIR = "net.lockney.tabula.action.PAIR"
        const val ACTION_UNPAIR = "net.lockney.tabula.action.UNPAIR"
        const val EXTRA_HA_URL = "ha_url"
        const val EXTRA_NAME = "name"
        private const val TAG = "TabulaService"
        private const val CHANNEL_ID = "tabula_connection"
        private const val NOTIFICATION_ID = 1
        /** The call screen's clock: one refresh a second (P9.4 item 3). */
        private const val INTERCOM_TICK_MS = 1_000L


        /** Start the service in the connect state (paired) or nothing (unpaired). */
        fun start(context: Context) {
            val intent = Intent(context, TabulaService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
