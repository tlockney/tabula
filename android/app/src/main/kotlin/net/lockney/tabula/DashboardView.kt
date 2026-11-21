package net.lockney.tabula

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.ha.DashboardLinks
import net.lockney.tabula.ha.ExternalAuthBridge
import net.lockney.tabula.ha.LinkDecision
import net.lockney.tabula.ha.TokenRefresher

/**
 * The full-screen dashboard WebView (packet P2.2, FEATURES F-DSH-01): the HA
 * frontend loaded with `external_auth=1`, authenticated through the V1
 * external-auth bridge instead of a login page or an injected token, with
 * top-level navigation held on the HA origin, `tabula://` links running app
 * actions without navigating (F-DSH-09) and `app://` links opening another
 * installed app (F-DSH-10). The WebView is never paused by the activity, so
 * the screen turning off does not reload the dashboard (F-DSH-01); what the
 * frontend does while the page is hidden is ADR 0012, and no shim is built.
 *
 * The thread-safe decision logic lives in :ha (`ExternalAuthBridge`,
 * `DashboardLinks`) and is unit-tested on the JVM; this class is the Android
 * glue around it.
 */
class DashboardView(
    private val activity: Activity,
    private val store: TabulaStore,
    private val tokens: TokenRefresher,
    private val bus: DashboardBus,
) : DashboardBus.Handle {
    /** The WebView; add [view] to the activity's layout, once. */
    val view: WebView

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridge: ExternalAuthBridge

    /** The main-frame URL as of the last navigation start; read from any thread. */
    @Volatile
    private var currentUrl: String? = null

    private val haUri: URI?
        get() = runCatching { URI(store.haUrl.orEmpty()) }.getOrNull()?.takeIf { it.isAbsolute }

    init {
        bridge = ExternalAuthBridge(
            access = { force ->
                val url = store.haUrl ?: error("no HA URL")
                tokens.accessWithRemaining(url, { store.refreshToken ?: error("not paired") }, force)
            },
            externalConfig = buildJsonObject {
                put("hasSettingsScreen", false)
                put("appVersion", appVersion())
            },
        )
        view = WebView(activity).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true // the frontend is a JS app; external auth is its bridge
            settings.domStorageEnabled = true
            webViewClient = Client()
            webChromeClient = Console()
            addJavascriptInterface(Bridge(), ExternalAuthBridge.JS_INTERFACE)
        }
        bus.attach(this)
    }

    /** Load the dashboard with the external-auth bootstrap (F-DSH-01). */
    fun load() {
        val url = store.haUrl ?: return
        Log.i(TAG, "dashboard: loading $url")
        view.loadUrl("$url/?external_auth=1")
    }

    /** The activity is done with it: detach first so a dying handle is never used. */
    fun destroy() {
        bus.detach(this)
        scope.cancel()
        main.post {
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
    }

    // --- DashboardBus.Handle (called from the service's session thread) -------

    override fun reload() {
        main.post { view.reload() }
    }

    override fun clearCache() {
        main.post { view.clearCache(true) }
    }

    override fun load(url: String) {
        main.post { view.loadUrl(url) }
    }

    override fun currentPage(): String? = currentUrl?.let(DashboardLinks::pagePath)

    // --- the V1 JavaScript interface -------------------------------------------

    /**
     * The frontend's contract (external_auth.ts in home-assistant/frontend,
     * Apache-2.0; the shape follows the Companion app's FrontendJsBridge.kt).
     * Every answer is evaluated asynchronously on the main thread.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun getExternalAuth(payload: String) {
            scope.launch { answer("getExternalAuth") { bridge.getExternalAuth(payload, onHaOrigin()) } }
        }

        @JavascriptInterface
        fun revokeExternalAuth(payload: String) {
            scope.launch { answer("revokeExternalAuth") { bridge.revokeExternalAuth(payload, onHaOrigin()) } }
        }

        @JavascriptInterface
        fun externalBus(message: String) {
            scope.launch { answer("externalBus") { bridge.externalBus(message, onHaOrigin()) } }
        }
    }

    private suspend fun answer(what: String, js: suspend () -> String?) {
        val script = js() ?: return // refused: off origin, bad payload, or nothing to say
        Log.i(TAG, "bridge $what: answered")
        main.post { runCatching { view.evaluateJavascript(script, null) } }
    }

    private fun onHaOrigin(): Boolean {
        val origin = haUri ?: return false
        return DashboardLinks.sameOrigin(currentUrl.orEmpty(), origin)
    }

    // --- navigation ------------------------------------------------------------

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val origin = haUri ?: return true // no HA origin to hold: block everything
            return when (val decision = DashboardLinks.route(request.url.toString(), origin)) {
                LinkDecision.LoadInWebView -> false
                is LinkDecision.RunTabulaAction -> {
                    runAction(decision.action)
                    true // the page itself does not navigate (F-DSH-09)
                }
                is LinkDecision.OpenApp -> {
                    openApp(decision.packageName)
                    true
                }
                is LinkDecision.Blocked -> {
                    Log.i(TAG, "dashboard: blocked ${request.url} - ${decision.reason}")
                    toast(decision.reason)
                    true
                }
            }
        }

        override fun onPageStarted(webView: WebView, url: String, favicon: android.graphics.Bitmap?) {
            currentUrl = url
        }

        override fun onPageFinished(webView: WebView, url: String) {
            currentUrl = url
            val path = DashboardLinks.pagePath(url) ?: url
            Log.i(TAG, "dashboard: loaded $path")
            bus.pageChanged(DashboardLinks.pagePath(url))
        }

        override fun onReceivedError(
            webView: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (request.isForMainFrame) {
                Log.w(TAG, "dashboard: ${request.url} failed: ${error.description}")
            }
        }
    }

    private inner class Console : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "console: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return true
        }
    }

    // --- tabula:// actions ------------------------------------------------------

    private fun runAction(action: String) {
        Log.i(TAG, "dashboard: tabula action $action")
        when (action) {
            "reload" -> view.reload()
            "clear-cache" -> view.clearCache(true)
            else -> toast("Tabula has no '$action' action") // unreachable: route() filtered it
        }
    }

    private fun openApp(packageName: String) {
        Log.i(TAG, "dashboard: opening app $packageName")
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            toast("$packageName is not installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { Log.w(TAG, "dashboard: could not open $packageName: $it"); toast("could not open $packageName") }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun appVersion(): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull()
            ?: "unknown"

    companion object {
        private const val TAG = "TabulaDashboard"
    }
}
