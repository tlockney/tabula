package net.lockney.tabula

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.lockney.tabula.core.screensaver.FadeRule
import net.lockney.tabula.core.screensaver.PixelShift
import net.lockney.tabula.core.tlp.ScreensaverMode
import kotlin.math.roundToInt

/**
 * The kiosk's screen (packets P2.1 and P2.2): before pairing it asks for the
 * HA base URL, then shows the code and the device ID to confirm in HA
 * (docs/PROTOCOL.md 2.1). Once connected it hosts the full-screen dashboard
 * WebView (F-DSH-01), which stays loaded across connection blips: a
 * CONNECTING phase with a dashboard up only hides the native views, and the
 * frontend shows its own reconnect banner underneath. A revocation or
 * refusal destroys the WebView and returns to the native screens, since its
 * authenticated session must not outlive the credential.
 *
 * The WebView is never paused here, so the screen turning off does not
 * reload the dashboard; the frontend's own background behavior is ADR 0012.
 *
 * Plain framework views on purpose: the pinned compileSdk 34 toolchain
 * (ADR 0008) predates current Compose runtimes. The screensaver overlay (P2.4)
 * stays with plain views too (ADR 0013).
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: TabulaStore
    private lateinit var root: FrameLayout
    private lateinit var native: LinearLayout
    private var dashboard: DashboardView? = null
    private lateinit var screensaver: ScreensaverView
    private lateinit var gallery: GalleryView
    private lateinit var cards: NotificationCardsView

    /** The touch being dispatched, so [onUserInteraction] can tell a card tap from other activity (F-NTF-12). */
    private var dispatchingTouch: android.view.MotionEvent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = (application as TabulaApp).store
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        root = FrameLayout(this)
        native = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        root.addView(
            native,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        // the screensaver overlay sits above everything else in the window (P2.4);
        // the engine polish reads its settings when the screensaver starts (P3.14)
        screensaver = ScreensaverView(
            this,
            pixelShift = { PixelShift(store.settings.screensaver.pixelShift) },
            fade = { FadeRule(store.settings.screensaver.fade.enabled) },
        )
        addContentView(screensaver, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        // the photo slideshow sits above the clock overlay and below the
        // notification cards (P3.8; cards draw over every mode, F-SSV-21)
        gallery = GalleryView(
            this,
            scope,
            clockStandIn = {
                // a playlist with nothing decodable: the clock stand-in
                // (ADR 0013 item 3, ADR 0016)
                screensaver.show(net.lockney.tabula.core.tlp.ScreensaverMode.CLOCK)
            },
            pixelShift = { PixelShift(store.settings.screensaver.pixelShift) },
            fade = { FadeRule(store.settings.screensaver.fade.enabled) },
        )
        addContentView(gallery, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        // the notification cards sit above the screensaver too (P2.5, F-NTF-12, F-SSV-21)
        cards = NotificationCardsView(this) { id -> TabulaStatus.cardDismiss.tryEmit(id) }
        addContentView(cards, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        ScreenWindow.attach(this, scope)
        observe()
        TabulaService.start(this)
    }

    override fun onStart() {
        super.onStart()
        TabulaStatus.windows.onStart()
    }

    override fun onStop() {
        TabulaStatus.windows.onStop()
        super.onStop()
    }

    /**
     * Every touch or key on the window restarts the idle timer (F-SSV-02) -
     * except a touch that lands on a notification card: it only removes that
     * card and does nothing else, so a card over the screensaver does not
     * dismiss the screensaver (F-NTF-08, F-NTF-12).
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        dispatchingTouch = ev
        try {
            return super.dispatchTouchEvent(ev)
        } finally {
            dispatchingTouch = null
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        val touch = dispatchingTouch
        if (touch != null && cards.cardAt(touch.x, touch.y)) return
        // an edge tap on a photo slideshow steps a slide and restarts the
        // interval instead of dismissing (F-SSV-04's exception, F-SSV-12)
        if (touch != null && gallery.edgeTap(touch.x)) return
        TabulaStatus.userActivity.tryEmit(Unit)
    }

    override fun onResume() {
        super.onResume()
        ScreenWindow.onResume(this)
    }

    override fun onDestroy() {
        scope.cancel()
        destroyDashboard()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            // the slideshow pauses with the window (F-SSV-02): a powered-off
            // panel stops the activity, so no decode or fetch runs in the dark
            TabulaStatus.foreground.collect { gallery.setActive(it) }
        }
        scope.launch {
            TabulaStatus.screensaver.collect { mode ->
                // photos mode draws through the gallery overlay (P3.8); what
                // the gallery cannot render (an unbuilt source, an empty
                // playlist) falls back to the clock stand-in (ADR 0013 item 3)
                if (mode == net.lockney.tabula.core.tlp.ScreensaverMode.PHOTOS &&
                    gallery.show(store.settings.screensaver.photos)
                ) {
                    screensaver.show(null)
                } else {
                    gallery.hide()
                    screensaver.show(mode)
                }
            }
        }
        scope.launch {
            TabulaStatus.notifications.collect { cards.render(it) }
        }
        scope.launch {
            TabulaStatus.phase.collect { render(it) }
        }
        scope.launch {
            // a pairing error arrives while the phase stays UNPAIRED, so it
            // needs its own trigger to re-render the form
            TabulaStatus.pairingError.collect { render(TabulaStatus.phase.value) }
        }
        scope.launch {
            TabulaStatus.pairing.collect { view ->
                val codeText = native.findViewWithTag<TextView>("code")
                codeText?.text = view?.let { "${it.code}" } ?: ""
                val idText = native.findViewWithTag<TextView>("device")
                idText?.text = view?.let { "Device ${it.deviceIdShort}" } ?: ""
            }
        }
    }

    private fun render(phase: Phase) {
        when (phase) {
            Phase.UNPAIRED, Phase.REVOKED, Phase.PAIRING, Phase.REJECTED -> {
                // the native screens replace the dashboard; its authenticated
                // session must not outlive the credential that failed
                destroyDashboard()
                showNative(phase)
            }
            Phase.CONNECTING -> {
                if (dashboard == null) {
                    showNative(phase)
                } else {
                    // the dashboard stays loaded (F-DSH-01); the frontend is
                    // showing its own connection state to the user already
                    native.visibility = View.GONE
                }
            }
            Phase.CONNECTED -> {
                native.visibility = View.GONE
                ensureDashboard()
            }
        }
    }

    // --- the native (pre-pairing) UI -------------------------------------------

    private fun showNative(phase: Phase) {
        native.visibility = View.VISIBLE
        native.removeAllViews()
        when (phase) {
            Phase.UNPAIRED, Phase.REVOKED -> pairingForm(phase)
            Phase.PAIRING -> waitingForConfirm()
            Phase.CONNECTING -> status("Connecting to Home Assistant…")
            Phase.REJECTED -> rejected()
            else -> {} // CONNECTED never lands here
        }
    }

    private fun pairingForm(phase: Phase) {
        if (phase == Phase.REVOKED) {
            text("Home Assistant revoked this device's credential.", bold = false)
        }
        // a terminal pairing failure (PROTOCOL 2.5), e.g. a refused nonce
        TabulaStatus.pairingError.value?.let { text(it, bold = false) }
        text("Enter your Home Assistant address", bold = false)
        val input = EditText(this).apply {
            hint = "http://homeassistant.local:8123"
            store.haUrl?.let { setText(it) }
        }
        native.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) })
        // the name HA shows for this tablet (F-MGT-03); the default tells
        // identical tablets apart (issue #79)
        text("Device name", bold = false)
        val fallbackName = defaultPairingName(this, store.deviceId)
        val nameInput = EditText(this).apply {
            hint = fallbackName
            setText(store.nameForPairing(fallbackName))
            // the bound counts code points, as the schema does, not UTF-16 units
            filters = arrayOf(
                android.text.InputFilter { source, start, end, dest, dstart, dend ->
                    PairingName.limitInsert(dest, dstart, dend, source.subSequence(start, end))
                },
            )
            setSingleLine(true)
        }
        native.addView(nameInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) })
        val button = Button(this).apply { text = "Pair with Home Assistant" }
        button.setOnClickListener {
            val url = input.text.toString().trim().trimEnd('/')
            if (!Regex("^https?://[^/@\\s?#]+(/[^\\s?#]*)?$").matches(url)) {
                input.error = "Use the HA address, e.g. http://homeassistant.local:8123"
                return@setOnClickListener
            }
            startService(
                Intent(this, TabulaService::class.java)
                    .setAction(TabulaService.ACTION_PAIR)
                    .putExtra(TabulaService.EXTRA_HA_URL, url)
                    .putExtra(TabulaService.EXTRA_NAME, PairingName.resolve(nameInput.text.toString(), fallbackName)),
            )
        }
        native.addView(button)
        text("Tabula ${versionName()}", small = true)
    }

    private fun waitingForConfirm() {
        text("Confirm this code in Home Assistant", bold = false)
        val current = TabulaStatus.pairing.value
        text(current?.code.orEmpty(), tag = "code", size = 96f)
        text(current?.let { "Device ${it.deviceIdShort}" }.orEmpty(), tag = "device", size = 20f)
        text("Settings → Devices & services → Tabula", small = true)
    }

    private fun status(message: String) {
        text(message, bold = false)
        text("Device ${store.deviceId.take(8)}", small = true)
        text("Tabula ${versionName()}", small = true)
    }

    private fun rejected() {
        text("Home Assistant refused this device", bold = true, size = 28f)
        val rejection = TabulaStatus.rejection.value
        text(rejection?.let { "${it.first}: ${it.second}" } ?: "", bold = false)
        text("The device may need an app update, or re-pairing.", small = true)
    }

    // --- the dashboard -----------------------------------------------------------

    private fun ensureDashboard() {
        if (dashboard != null) return
        val app = application as TabulaApp
        val view = DashboardView(this, store, app.tokens, app.dashboard)
        dashboard = view
        // at the bottom of the stack: native views and the later overlays
        // (menus, screensavers) sit above the dashboard (F-DSH-01)
        root.addView(view.view, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        view.load()
    }

    private fun destroyDashboard() {
        val view = dashboard ?: return
        dashboard = null
        root.removeView(view.view)
        view.destroy()
    }

    // --- helpers ---------------------------------------------------------------

    private fun text(
        content: String,
        tag: String? = null,
        bold: Boolean = false,
        small: Boolean = false,
        size: Float = 18f,
    ): TextView {
        val view = TextView(this).apply {
            text = content
            this.tag = tag
            textSize = if (small) 14f else size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        native.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(if (small) 4 else 16) })
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
}
