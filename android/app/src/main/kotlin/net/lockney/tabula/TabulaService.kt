package net.lockney.tabula

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
            log = { Log.i(TAG, it) },
        )
    }
    private lateinit var notifications: NotificationsManager
    private lateinit var chime: NotificationChime
    private lateinit var audio: MediaManager
    private lateinit var deviceEvents: EventFanIn
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
        startNotifications()
        startAudio()
    }

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
        scope.launch { TabulaStatus.userActivity.collect { screensaver.onActivity() } }
        scope.launch { TabulaStatus.foreground.collect { screensaver.setForeground(it) } }
    }

    /**
     * Notifications (packet P2.5) run for the service's lifetime too. Cards
     * arrive only from HA, but the ones already up keep timing out while HA
     * is down, and the chime is a synthesized tone on the media stream at
     * `audio.chime_volume` (F-NTF-09, ADR 0023). A card's tap travels back through
     * [TabulaStatus.cardDismiss]; it removes only that card (F-NTF-08).
     */
    private fun startNotifications() {
        chime = NotificationChime(volume = { store.settings.audio.chimeVolume })
        notifications = NotificationsManager(
            controller = net.lockney.tabula.core.notifications.NotificationCards(),
            show = { cards -> TabulaStatus.notifications.value = cards },
            chime = chime::play,
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
        // one event stream for the TLP session from every source (P2.4, P2.5)
        deviceEvents = EventFanIn(scope, listOf(screensaver.events, notifications.events, audio.events))
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
                TabulaStatus.phase.value = Phase.UNPAIRED
                TabulaStatus.pairing.value = null
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
        entityData.stop()
        notifications.stop()
        chime.release()
        audio.close()
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
            TabulaStatus.phase.value = Phase.UNPAIRED
        }
    }

    private fun startPairing() {
        val haUrl = store.haUrl ?: return
        job?.cancel()
        job = scope.launch {
            TabulaStatus.phase.value = Phase.PAIRING
            TabulaStatus.pairing.value = null
            TabulaStatus.pairingError.value = null
            try {
                val http = OkHttpExchange()
                val paired = PairingClient(http).pair(
                    haUrl,
                    store.deviceId,
                    // the typed or default name (issue #79), never the bare
                    // manufacturer: every Fire tablet would pair as "Amazon"
                    store.nameForPairing(defaultPairingName(this@TabulaService, store.deviceId)),
                    PairingName.clip(Build.MODEL),
                ) { status ->
                    TabulaStatus.pairing.value =
                        TabulaStatus.PairingView(status.code, status.deviceId.take(8))
                }
                // The claim released the credential exactly once (PROTOCOL 2.5):
                // savePairing commits synchronously, so it is durable before the
                // claim is treated as complete. If the write fails, do not start
                // a session with a credential that will be gone next boot - the
                // user pairs again and deletes the HA entry this claim created.
                if (!store.savePairing(paired.credential.refreshToken, paired.haDeviceId)) {
                    Log.e(TAG, "could not persist the pairing credential; pair again")
                    TabulaStatus.pairingError.value = "Pairing failed: the credential could not be saved on this device. Pair again."
                    TabulaStatus.phase.value = Phase.UNPAIRED
                    TabulaStatus.pairing.value = null
                    return@launch
                }
                TabulaStatus.pairing.value = null
                startSession()
            } catch (e: CredentialRevoked) {
                TabulaStatus.phase.value = Phase.REVOKED
            } catch (e: net.lockney.tabula.ha.PairingException) {
                // Terminal pairing failure (bad nonce, refused request): show it
                // as a pairing error, PROTOCOL 2.5, then back to the pairing form.
                TabulaStatus.pairingError.value = "Pairing failed: ${e.message ?: e.name.name}. Pair again."
                TabulaStatus.phase.value = Phase.UNPAIRED
                TabulaStatus.pairing.value = null
            } catch (e: Exception) {
                // A restart cancels the running pairing job, and that surfaces
                // here as CancellationException; rethrow it (the block below is
                // for environment failures) or the restart leaves a stale
                // pairing error behind.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Anything else escaping pair() is an environment failure or a bug;
                // it must surface as a pairing error, never an uncaught exception
                // in a coroutine that kills the app process.
                Log.e(TAG, "pairing failed", e)
                TabulaStatus.pairingError.value =
                    "Pairing failed: ${e.message ?: e.javaClass.simpleName}. Pair again."
                TabulaStatus.phase.value = Phase.UNPAIRED
                TabulaStatus.pairing.value = null
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
            screen.listener = state::publish
            screensaver.listener = state::publish
            audio.listener = state::publish
            illuminanceListener = state::publish
            TabulaStatus.phase.value = Phase.CONNECTING
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
                    ),
                    events = deviceEvents.events,
                ),
                (application as TabulaApp).tokens,
                OkHttpWsTransportFactory(),
            )
            val events = object : TlpClient.Events {
                override fun onRegistered(result: net.lockney.tabula.core.tlp.RegisterResultData) {
                    Log.i(TAG, "registered with HA device ${result.haDeviceId}")
                    TabulaStatus.phase.value = Phase.CONNECTED
                }

                override fun onDisconnected() {
                    Log.i(TAG, "connection lost; reconnecting with backoff")
                    if (TabulaStatus.phase.value == Phase.CONNECTED) {
                        TabulaStatus.phase.value = Phase.CONNECTING
                    }
                }

                override fun onRevoked() {
                    Log.w(TAG, "credential revoked in HA; back to pairing")
                    store.clearCredential()
                    TabulaStatus.phase.value = Phase.REVOKED
                }

                override fun onRejected(code: String, message: String) {
                    Log.w(TAG, "registration refused: $code: $message")
                    TabulaStatus.rejection.value = code to message
                    TabulaStatus.phase.value = Phase.REJECTED
                }
            }
            val channelJob = launch { client.channel.collect { haChannel.value = it } }
            try {
                client.run(events)
            } finally {
                channelJob.cancel()
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
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
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
    }

    /**
     * The capabilities this device declares (PROTOCOL 4.2): battery (P2.1),
     * navigation (P2.2, the dashboard WebView follows a navigate command and
     * reports the current page), screen and brightness (P2.3),
     * adaptive_brightness and illuminance only when the tablet has a light
     * sensor (P3.16), screensaver (P2.4), notifications and audio (P2.5),
     * and screenshot (P3.13, the capture-and-upload flow).
     */
    private fun capabilities(): List<String> = buildList {
        add("battery")
        add("navigation")
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
                Log.i(TAG, "phase: $phase")
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


        /** Start the service in the connect state (paired) or nothing (unpaired). */
        fun start(context: Context) {
            val intent = Intent(context, TabulaService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
