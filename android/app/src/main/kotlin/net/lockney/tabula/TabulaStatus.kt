package net.lockney.tabula

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import net.lockney.tabula.core.tlp.ScreensaverMode

/**
 * What the app is doing, shared between the service and the UI. The service is
 * the only writer; the activity collects and renders.
 */
enum class Phase {
    /** No credential: the pairing screen is up. */
    UNPAIRED,

    /**
     * Pairing in progress with a code to confirm: [TabulaStatus.pairing]
     * carries it. An attempt that has not reached HA yet stays UNPAIRED, so
     * the confirm screen never shows an empty code (issue #127).
     */
    PAIRING,

    /** Paired, working towards a registered connection. */
    CONNECTING,

    /** Registered and subscribed; commands and state flow. */
    CONNECTED,

    /** The credential was revoked in HA (ADR 0001): back to pairing. */
    REVOKED,

    /** HA refused registration in a way retrying cannot fix (PROTOCOL 6). */
    REJECTED,
}

object TabulaStatus {
    /** Set by the service when it starts; UNPAIRED until then. */
    val phase = MutableStateFlow(Phase.UNPAIRED)

    /** The pairing code shown on screen, with the device id beside it. */
    val pairing = MutableStateFlow<PairingView?>(null)

    /**
     * The last terminal pairing failure, shown on the pairing form
     * (PROTOCOL 2.5: a bad-nonce device shows a pairing error). Cleared when
     * a new pairing attempt starts.
     */
    val pairingError = MutableStateFlow<String?>(null)

    /**
     * A non-terminal line about the attempt in flight, shown on the pairing
     * form: the pair request cannot reach HA and keeps retrying (issue #127).
     * Cleared as soon as HA answers or a new attempt starts.
     */
    val pairingNotice = MutableStateFlow<String?>(null)

    /** The last rejection (code, message) when [Phase.REJECTED]. */
    val rejection = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * Window brightness override, 0.0-1.0, used when the app cannot write the
     * system backlight (F-SCR-03 fallback); null leaves the window at the
     * system level. Written by [ScreenManager], applied by [ScreenWindow].
     */
    val windowBrightness = MutableStateFlow<Float?>(null)

    /** Keep the panel awake while the app's window is showing (F-SCR-01). */
    val keepScreenOn = MutableStateFlow(true)

    /** The screensaver mode on screen, or null (P2.4). Written by [ScreensaverManager]. */
    val screensaver = MutableStateFlow<ScreensaverMode?>(null)

    /**
     * The applied settings document's revision (P3.3). Written by
     * [TabulaCommands] on every set_settings it applies, startup included;
     * the activity re-applies the clock settings while a clock face shows,
     * so a settings write always reloads the clock background, identical
     * source or not (F-SSV-09: the revision change is the signal).
     */
    val settingsRevision = MutableStateFlow(0)

    /**
     * The notification card stack, newest first (P2.5). Written by
     * [NotificationsManager], rendered by the activity's cards overlay.
     */
    val notifications = MutableStateFlow<List<net.lockney.tabula.core.notifications.NotificationCards.Card>>(emptyList())

    /** A card was tapped; the service dismisses that card only (F-NTF-08). */
    val cardDismiss = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A touch on the app's window; the service restarts the idle timer (F-SSV-02). */
    val userActivity = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Whether the app's window is in front; the idle timer stops while it is not (F-SSV-02). */
    val foreground = MutableStateFlow(false)

    /** Counts the started windows behind [foreground]; MainActivity's onStart and onStop drive it. */
    val windows = StartedWindows { foreground.value = it }

    data class PairingView(val code: String, val deviceIdShort: String)
}

/**
 * Whether any of the app's windows is started (between onStart and onStop).
 * A count, not a flag: when one window opens over another, the new one's
 * onStart runs before the old one's onStop, and a flag would end false with
 * a window in front, pausing the idle timer for good (issue #96).
 */
class StartedWindows(private val onChange: (Boolean) -> Unit) {
    private var started = 0

    // onChange fires only when the answer flips, so a consumer whose pauses
    // nest (ScreensaverController.setPaused) never sees a repeated resume

    @Synchronized
    fun onStart() {
        started += 1
        if (started == 1) onChange(true)
    }

    @Synchronized
    fun onStop() {
        if (started == 0) return
        started -= 1
        if (started == 0) onChange(false)
    }
}
