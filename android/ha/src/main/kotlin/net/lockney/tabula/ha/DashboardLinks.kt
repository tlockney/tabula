package net.lockney.tabula.ha

import java.net.URI

/**
 * What the dashboard WebView should do with a link (packet P2.2).
 *
 * `tabula://` and `app://` are Tabula's private schemes (plan section 2):
 * `tabula://<action>[/<argument>]` runs an app action without the page
 * navigating (F-DSH-09), and `app://<package>` opens another installed app
 * (F-DSH-10); generic browser intent links are refused. Ordinary http(s)
 * links stay in the WebView only while they remain on the HA origin (ADR
 * 0001 item 3: navigation never leaves it); anything else is blocked, which
 * is the kiosk-safe reading of the ADR's "blocked or opened outside".
 *
 * Pure Kotlin: the Android layer turns [LinkDecision]s into WebView calls,
 * toasts and intents, and these rules are unit-tested on the JVM.
 */
sealed interface LinkDecision {
    /** A same-origin http(s) link: load it in the WebView. */
    data object LoadInWebView : LinkDecision

    /** `tabula://action[/argument]`: run the action; the page does not navigate. */
    data class RunTabulaAction(val action: String, val argument: String?) : LinkDecision

    /** `app://package`: hand off to another installed app. */
    data class OpenApp(val packageName: String) : LinkDecision

    /** Do nothing, with a reason the UI can show in a toast (F-DSH-09). */
    data class Blocked(val reason: String) : LinkDecision
}

object DashboardLinks {
    /**
     * The `tabula://` actions P2.2 can run. The full action registry
     * (F-GST-07) and the scheme's manifest registration arrive with Phase 4's
     * navigation work; `restart-app` and `exit-app` are not linkable by design
     * (F-DSH-09), so they are absent here even though the app can run them.
     */
    val TABULA_ACTIONS: Set<String> = setOf("reload", "clear-cache")

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Decide what a top-level navigation to [url] means for a WebView on the [haOrigin]. */
    fun route(url: String, haOrigin: URI): LinkDecision {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return LinkDecision.Blocked("not a URL")
        return when (uri.scheme?.lowercase()) {
            SCHEME_TABULA -> {
                val action = uri.host.orEmpty()
                val argument = uri.path?.trim('/')?.takeIf { it.isNotEmpty() }
                when {
                    action.isEmpty() -> LinkDecision.Blocked("empty tabula action")
                    action !in TABULA_ACTIONS -> LinkDecision.Blocked("Tabula has no '$action' action")
                    else -> LinkDecision.RunTabulaAction(action, argument)
                }
            }
            SCHEME_APP -> {
                val packageName = uri.host.orEmpty()
                if (PACKAGE.matches(packageName)) LinkDecision.OpenApp(packageName)
                else LinkDecision.Blocked("not an app package name")
            }
            "http", "https" ->
                if (sameOrigin(uri, haOrigin)) LinkDecision.LoadInWebView
                else LinkDecision.Blocked("the link left the Home Assistant origin")
            else -> LinkDecision.Blocked("unsupported scheme '${uri.scheme ?: ""}'")
        }
    }

    /** Whether [url] is on the HA origin — the gate for every external-auth answer. */
    fun sameOrigin(url: String, haOrigin: URI): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return sameOrigin(uri, haOrigin)
    }

    /**
     * Resolve a `navigate` command's [path] against [haOrigin] (PROTOCOL 4.5).
     * Returns the absolute URL to load, or null when the path is not a single
     * leading-slash path or the result leaves the origin (ADR 0001 item 3).
     * The wire codec has already validated the path's shape (Wire.navigatePath);
     * this re-checks because the command handler may be fed by other callers.
     */
    fun resolveNavigatePath(path: String, haOrigin: URI): String? {
        if (!path.startsWith("/") || path.startsWith("//")) return null
        val resolved = runCatching { haOrigin.resolve(path) }.getOrNull() ?: return null
        if (!resolved.isAbsolute || !sameOrigin(resolved, haOrigin)) return null
        return resolved.toString()
    }

    /**
     * A page's path for the `current_page` state key: the URL's path and query
     * are the frontend's business, but the protocol carries the path only,
     * without query string or fragment (PROTOCOL 4.4; lines never carry
     * credentials, and neither does this key).
     */
    fun pagePath(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.isAbsolute) return null
        val path = uri.path.orEmpty().ifEmpty { "/" }
        return path
    }

    private fun sameOrigin(uri: URI, haOrigin: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != haOrigin.scheme?.lowercase()) return false
        val host = uri.host?.lowercase() ?: return false
        if (host != haOrigin.host?.lowercase()) return false
        return effectivePort(uri) == effectivePort(haOrigin)
    }

    private fun effectivePort(uri: URI): Int =
        if (uri.port != -1) uri.port else defaultPort(uri.scheme?.lowercase())

    private fun defaultPort(scheme: String?): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }

    private const val SCHEME_TABULA = "tabula"
    private const val SCHEME_APP = "app"
}
