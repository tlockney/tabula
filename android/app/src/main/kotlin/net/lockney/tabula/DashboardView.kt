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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.Constants
import net.lockney.tabula.ha.BridgeAnswer
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

    /** Load the dashboard with the external-auth bootstrap (F-DSH-01), on the
     * start target when one is set (F-DSH-02: launch, crash restart and
     * restart_app land there; a fragment target keeps its fragment).
     *
     * Always posted to the main thread: this is also the in-session fallback
     * for an unset, invalid or off-origin start target ([loadStart]), whose
     * callers run on the service's timer scope or the command thread, and
     * WebView throws when touched off the main thread. */
    fun load() {
        val url = store.haUrl ?: return
        val target = startTargetUrl()?.let { DashboardLinks.withParam(it, "external_auth=1") }
        main.post {
            if (target == null) {
                Log.i(TAG, "dashboard: loading $url")
                view.loadUrl("$url/?external_auth=1")
            } else {
                Log.i(TAG, "dashboard: loading start target $target")
                view.loadUrl(target)
            }
        }
    }

    /**
     * The resolved start target (F-DSH-02), or null when none is set or the
     * value is invalid or off-origin - the caller then loads HA's default
     * page. Validation is `navigate`'s own (single leading slash, resolved
     * against the HA base URL, refused off the origin, ADR 0001 item 3).
     */
    private fun startTargetUrl(): String? {
        val path = store.settings.dashboard.startPath
        if (path.isEmpty()) return null
        val origin = haUri ?: return null
        val resolved = DashboardLinks.resolveNavigatePath(path, origin)
        if (resolved == null) {
            Log.w(TAG, "dashboard: start path '$path' is not a valid HA frontend path; falling back to HA's default page")
        }
        return resolved
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

    override fun loadStart() {
        // In-session (go_home, the idle return): the frontend keeps its own
        // session, so no external-auth bootstrap is appended - same as
        // navigate. Unset, invalid or off-origin falls back to HA's default
        // page (F-DSH-02); load() main-posts its WebView call, so neither the
        // timer scope nor the command thread touches the WebView here.
        val resolved = startTargetUrl() ?: return load()
        main.post { view.loadUrl(resolved) }
    }

    override fun currentPage(): String? = currentUrl?.let(DashboardLinks::pagePath)

    // --- screenshot (P3.13, F-HA-12) ----------------------------------------------

    /**
     * Draw the activity's window and JPEG-encode the bitmap on a worker: the
     * draw must happen on the thread that owns the views, and the encode is
     * the slow half, so it must not hold the main thread. The packet's Do
     * item 1 says "the app's window (dashboard WebView and overlays alike)":
     * the decor view holds the whole stack (the dashboard WebView at the
     * bottom, the screensaver overlay and the notification cards above it,
     * MainActivity.onCreate/ensureDashboard), so a capture while an overlay
     * is showing shows the screen as it actually is (review blocker) - a
     * window that has not laid out yet captures nothing.
     *
     * Some Fire OS WebView builds draw their hardware-accelerated layers to a
     * blank bitmap via `View.draw`; if the hardware check (check_p313.sh step
     * "panel on") reports the capture is blank, PixelCopy on the window is
     * the fallback (review note, P3.13).
     */
    override suspend fun capture(quality: Int?): ByteArray? {
        val bitmap = withContext(Dispatchers.Main) {
            val decor = activity.window.decorView
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return@withContext null
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also {
                decor.draw(android.graphics.Canvas(it))
            }
        } ?: return null
        try {
            return withContext(Dispatchers.Default) {
                java.io.ByteArrayOutputStream().use { out ->
                    val ok = bitmap.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,
                        quality ?: Constants.SCREENSHOT_JPEG_QUALITY,
                        out,
                    )
                    if (ok) out.toByteArray() else null
                }
            }
        } finally {
            bitmap.recycle() // however the encode ends, the bitmap is released
        }
    }

    // --- the V1 JavaScript interface -------------------------------------------

    /**
     * The frontend's contract (external_auth.ts in home-assistant/frontend,
     * Apache-2.0; the shape follows the Companion app's FrontendJsBridge.kt).
     * Every answer is gated twice: at arrival on the page's URL, and again
     * on the main thread against the WebView's own URL right before the
     * script is evaluated, because building the answer (a token refresh) can
     * suspend and the main frame may navigate in that gap.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun getExternalAuth(payload: String) {
            scope.launch { answer("getExternalAuth") { bridgeCall { o, u -> bridge.getExternalAuth(payload, o, u) } } }
        }

        @JavascriptInterface
        fun revokeExternalAuth(payload: String) {
            scope.launch { answer("revokeExternalAuth") { bridgeCall { o, u -> bridge.revokeExternalAuth(payload, o, u) } } }
        }

        @JavascriptInterface
        fun externalBus(message: String) {
            scope.launch { answer("externalBus") { bridgeCall { o, u -> bridge.externalBus(message, o, u) } } }
        }
    }

    /** The arrival gate: the origin and the page's URL as of the call. */
    private suspend fun <T> bridgeCall(call: suspend (origin: URI, pageUrl: String) -> T): T? {
        val origin = haUri ?: return null
        return call(origin, currentUrl.orEmpty())
    }

    private suspend fun answer(what: String, build: suspend () -> BridgeAnswer?) {
        val answer = build() ?: return // refused at arrival: off origin or bad payload
        main.post {
            // Re-check against the WebView's own URL, on this thread: an
            // answer built for one page must not be evaluated on another that
            // navigated in while the answer was being built.
            if (!answer.deliverTo(view.url)) {
                Log.i(TAG, "bridge $what: dropped; the page left the origin while the answer was built")
                return@post
            }
            Log.i(TAG, "bridge $what: answered")
            runCatching { view.evaluateJavascript(answer.script, null) }
        }
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
            // POST navigations (a form submit) and some scripted location
            // changes never reach shouldOverrideUrlLoading, so the origin
            // rule is enforced here too: an off-origin main-frame load is
            // stopped and the dashboard returns to the HA root (ADR 0001
            // item 3). The bridge also refuses such a page any answer.
            val origin = haUri ?: return
            if (DashboardLinks.offOriginLoad(url, origin)) {
                Log.w(TAG, "dashboard: navigation to $url left the origin; returning to the dashboard")
                webView.stopLoading()
                load()
            }
        }

        override fun onPageFinished(webView: WebView, url: String) {
            currentUrl = url
            val path = DashboardLinks.pagePath(url) ?: url
            Log.i(TAG, "dashboard: loaded $path")
            bus.pageChanged(DashboardLinks.pagePath(url))
        }

        override fun doUpdateVisitedHistory(webView: WebView, url: String, isReload: Boolean) {
            // The frontend routes client-side (history pushState/replaceState),
            // which never reaches onPageStarted/Finished but does reach here.
            // Feeding currentUrl and the page-change listeners from it keeps
            // current_page (F-NAV-02) and the return-to-start countdown's
            // compare and reset (F-DSH-15, ADR 0021 item 3) in step with what
            // the dashboard really shows after a client-side move. A full load
            // reports first through onPageStarted, so the equality check keeps
            // it to one event per page.
            val origin = haUri ?: return
            if (DashboardLinks.offOriginLoad(url, origin)) return
            if (url == currentUrl) return
            currentUrl = url
            val path = DashboardLinks.pagePath(url)
            Log.i(TAG, "dashboard: routed to ${path ?: url}")
            bus.pageChanged(path)
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
