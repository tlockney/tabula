package net.lockney.tabula

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The pairing screen (packet P2.1): before pairing it asks for the HA base
 * URL, then shows the code and the device ID to confirm in HA
 * (docs/PROTOCOL.md 2.1). Once paired it shows the connection status. The
 * dashboard WebView that replaces this surface arrives in P2.2.
 *
 * Plain framework views on purpose: the pinned compileSdk 34 toolchain
 * (ADR 0008) predates current Compose runtimes, so the native UI layers that
 * need Compose (P2.4) should bump compileSdk with their own ADR first.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: TabulaStore
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = (application as TabulaApp).store
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        setContentView(root)
        observe()
        TabulaService.start(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
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
                val codeText = root.findViewWithTag<TextView>("code")
                codeText?.text = view?.let { "${it.code}" } ?: ""
                val idText = root.findViewWithTag<TextView>("device")
                idText?.text = view?.let { "Device ${it.deviceIdShort}" } ?: ""
            }
        }
    }

    private fun render(phase: Phase) {
        root.removeAllViews()
        when (phase) {
            Phase.UNPAIRED, Phase.REVOKED -> pairingForm(phase)
            Phase.PAIRING -> waitingForConfirm()
            Phase.CONNECTING -> status("Connecting to Home Assistant…")
            Phase.CONNECTED -> connected()
            Phase.REJECTED -> rejected()
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
        root.addView(input, LinearLayout.LayoutParams(
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
                    .putExtra(TabulaService.EXTRA_HA_URL, url),
            )
        }
        root.addView(button)
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

    private fun connected() {
        text("Connected", bold = true, size = 34f)
        text(store.haUrl.orEmpty(), bold = false)
        text("Device ${store.deviceId.take(8)}", small = true)
        val unpair = Button(this).apply { text = "Unpair" }
        unpair.setOnClickListener {
            startService(
                Intent(this, TabulaService::class.java).setAction(TabulaService.ACTION_UNPAIR),
            )
        }
        root.addView(unpair)
    }

    private fun rejected() {
        text("Home Assistant refused this device", bold = true, size = 28f)
        val rejection = TabulaStatus.rejection.value
        text(rejection?.let { "${it.first}: ${it.second}" } ?: "", bold = false)
        text("The device may need an app update, or re-pairing.", small = true)
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
        root.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(if (small) 4 else 16) })
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
}
