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
import kotlinx.coroutines.launch
import net.lockney.tabula.ha.CredentialRevoked
import net.lockney.tabula.ha.OkHttpExchange
import net.lockney.tabula.ha.OkHttpWsTransportFactory
import net.lockney.tabula.ha.PairingClient
import net.lockney.tabula.ha.TokenRefresher
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> {
                val haUrl = intent.getStringExtra(EXTRA_HA_URL)?.trim()?.trimEnd('/')
                if (!haUrl.isNullOrEmpty()) {
                    store.haUrl = haUrl
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
                    Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                    Build.MODEL,
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
            val state = AndroidStateSource(applicationContext, versionName())
            TabulaStatus.phase.value = Phase.CONNECTING
            val client = TlpClient(
                TlpClient.Config(
                    haUrl = haUrl,
                    deviceId = store.deviceId,
                    appVersion = versionName(),
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    osVersion = Build.VERSION.RELEASE ?: "unknown",
                    capabilities = CAPABILITIES,
                    settingsRevision = { store.settingsRevision },
                    refreshToken = { refreshToken },
                    state = state,
                    commands = TabulaCommands(store, onRestartApp = ::restartApp),
                ),
                TokenRefresher(OkHttpExchange()),
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
            try {
                client.run(events)
            } finally {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tabula")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PAIR = "net.lockney.tabula.action.PAIR"
        const val ACTION_UNPAIR = "net.lockney.tabula.action.UNPAIR"
        const val EXTRA_HA_URL = "ha_url"
        private const val TAG = "TabulaService"
        private const val CHANNEL_ID = "tabula_connection"
        private const val NOTIFICATION_ID = 1

        /** The capabilities a P2.1 device declares (PROTOCOL 4.2). */
        private val CAPABILITIES = listOf("battery")

        /** Start the service in the connect state (paired) or nothing (unpaired). */
        fun start(context: Context) {
            val intent = Intent(context, TabulaService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
