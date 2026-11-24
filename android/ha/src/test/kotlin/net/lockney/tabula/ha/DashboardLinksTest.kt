package net.lockney.tabula.ha

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard's link rules (packet P2.2): same-origin links stay in the
 * WebView, `tabula://` runs a known app action without navigating,
 * `app://` opens another app by package name, everything else is blocked
 * with a reason, and navigate paths never leave the HA origin.
 */
class DashboardLinksTest {
    private val ha = URI("http://homeassistant.local:8123")

    @Test
    fun `same-origin links load in the webview`() {
        assertEquals(LinkDecision.LoadInWebView, DashboardLinks.route("http://homeassistant.local:8123/lovelace/1", ha))
        assertEquals(LinkDecision.LoadInWebView, DashboardLinks.route("http://homeassistant.local:8123/config/dashboard", ha))
        // scheme-relative and port-mismatched URLs are not the same origin
        assertTrue(DashboardLinks.route("//homeassistant.local:8123/x", ha) is LinkDecision.Blocked)
        assertTrue(DashboardLinks.route("http://homeassistant.local:9000/x", ha) is LinkDecision.Blocked)
    }

    @Test
    fun `foreign origins are blocked`() {
        val decision = DashboardLinks.route("https://example.com/path", ha)
        assertTrue(decision is LinkDecision.Blocked)
        assertTrue("origin" in decision.reason)
    }

    @Test
    fun `tabula actions run without navigating the page`() {
        assertEquals(
            LinkDecision.RunTabulaAction("reload", null),
            DashboardLinks.route("tabula://reload", ha),
        )
        assertEquals(
            LinkDecision.RunTabulaAction("clear-cache", null),
            DashboardLinks.route("tabula://clear-cache", ha),
        )
    }

    @Test
    fun `the intercom links open the screen and ring one target`() {
        // P9.4 item 4, ADR 0025 decision 6: the screen, a fixed device, a
        // fixed area and a broadcast, so a dashboard button or a gesture can
        // reach the intercom without going through the list.
        assertEquals(
            LinkDecision.RunTabulaAction("intercom", null),
            DashboardLinks.route("tabula://intercom", ha),
        )
        assertEquals(
            LinkDecision.RunTabulaAction("intercom-call", "7d3f1c9e2b4a4f0e9c1d2b3a4f5e6d7c"),
            DashboardLinks.route("tabula://intercom-call/7d3f1c9e2b4a4f0e9c1d2b3a4f5e6d7c", ha),
        )
        assertEquals(
            LinkDecision.RunTabulaAction("intercom-area", "kitchen"),
            DashboardLinks.route("tabula://intercom-area/kitchen", ha),
        )
        assertEquals(
            LinkDecision.RunTabulaAction("intercom-broadcast", null),
            DashboardLinks.route("tabula://intercom-broadcast", ha),
        )
    }

    @Test
    fun `an intercom link that names no target, or a bad one, is refused`() {
        val bare = DashboardLinks.route("tabula://intercom-call", ha)
        assertTrue(bare is LinkDecision.Blocked)
        assertTrue("intercom-call" in bare.reason)

        // A path can carry several segments; an area id or device id is one.
        val twoSegments = DashboardLinks.route("tabula://intercom-call/a/b", ha)
        assertTrue(twoSegments is LinkDecision.Blocked)

        val spaces = DashboardLinks.route("tabula://intercom-area/kit chen", ha)
        assertTrue(spaces is LinkDecision.Blocked)
    }

    @Test
    fun `unknown tabula actions are refused with a reason`() {
        val decision = DashboardLinks.route("tabula://exit-app", ha)
        assertTrue(decision is LinkDecision.Blocked)
        assertTrue("exit-app" in decision.reason)
        assertTrue(DashboardLinks.route("tabula://", ha) is LinkDecision.Blocked)
    }

    @Test
    fun `app links carry a package name`() {
        assertEquals(
            LinkDecision.OpenApp("com.example.app"),
            DashboardLinks.route("app://com.example.app", ha),
        )
        assertTrue(DashboardLinks.route("app://not a package", ha) is LinkDecision.Blocked)
        assertTrue(DashboardLinks.route("app://singleword", ha) is LinkDecision.Blocked)
    }

    @Test
    fun `unsupported schemes are blocked`() {
        assertTrue(DashboardLinks.route("intent://#Intent;action=x;end", ha) is LinkDecision.Blocked)
        assertTrue(DashboardLinks.route("market://details?id=x", ha) is LinkDecision.Blocked)
        assertTrue(DashboardLinks.route("javascript:alert(1)", ha) is LinkDecision.Blocked)
        assertTrue(DashboardLinks.route("about:blank", ha) is LinkDecision.Blocked)
    }

    @Test
    fun `navigate paths resolve against the HA origin only`() {
        assertEquals(
            "http://homeassistant.local:8123/lovelace/1",
            DashboardLinks.resolveNavigatePath("/lovelace/1", ha),
        )
        // protocol-relative and absolute URLs leave the origin: refused
        assertNull(DashboardLinks.resolveNavigatePath("//example.com/x", ha))
        assertNull(DashboardLinks.resolveNavigatePath("http://example.com/x", ha))
        assertNull(DashboardLinks.resolveNavigatePath("lovelace/1", ha))
        assertNull(DashboardLinks.resolveNavigatePath("", ha))
    }

    // --- start target (P3.15, F-DSH-02) -------------------------------------

    @Test
    fun `a start path with a fragment resolves with the fragment kept`() {
        assertEquals(
            "http://homeassistant.local:8123/lovelace/0#popup",
            DashboardLinks.resolveNavigatePath("/lovelace/0#popup", ha),
        )
        // the start path carries no query string (schema pattern); a fragment
        // is all F-DSH-02's landing needs
        assertNull(DashboardLinks.resolveNavigatePath("//example.com/x#f", ha))
    }

    @Test
    fun `withParam appends before the fragment, joining an existing query`() {
        assertEquals(
            "http://homeassistant.local:8123/lovelace/0?external_auth=1#popup",
            DashboardLinks.withParam("http://homeassistant.local:8123/lovelace/0#popup", "external_auth=1"),
        )
        assertEquals(
            "http://homeassistant.local:8123/?external_auth=1",
            DashboardLinks.withParam("http://homeassistant.local:8123/", "external_auth=1"),
        )
        assertEquals(
            "http://homeassistant.local:8123/lovelace/0?a=1&external_auth=1",
            DashboardLinks.withParam("http://homeassistant.local:8123/lovelace/0?a=1", "external_auth=1"),
        )
        // a URL that does not parse is returned unchanged
        assertEquals("not a url", DashboardLinks.withParam("not a url", "external_auth=1"))
    }

    @Test
    fun `current page carries the path without query or fragment`() {
        assertEquals("/", DashboardLinks.pagePath("http://homeassistant.local:8123/?external_auth=1"))
        assertEquals("/lovelace/1", DashboardLinks.pagePath("http://homeassistant.local:8123/lovelace/1?x=1#frag"))
        assertNull(DashboardLinks.pagePath("not a url"))
    }

    @Test
    fun `sameOrigin matches scheme host and effective port`() {
        assertTrue(DashboardLinks.sameOrigin("http://homeassistant.local:8123/home", ha))
        assertTrue(DashboardLinks.sameOrigin("http://HOMEASSISTANT.local:8123/home", ha))
        assertTrue(!DashboardLinks.sameOrigin("https://homeassistant.local:8123/home", ha))
        assertTrue(!DashboardLinks.sameOrigin("http://other.local:8123/home", ha))
    }

    @Test
    fun `an off-origin page start is stopped`() {
        // POST navigations and some scripted location changes never reach
        // shouldOverrideUrlLoading, so the page-start callback decides
        assertTrue(DashboardLinks.offOriginLoad("http://evil.example/x", ha))
        assertTrue(DashboardLinks.offOriginLoad("https://homeassistant.local:8123/", ha))
        assertTrue(DashboardLinks.offOriginLoad("http://homeassistant.local:8124/", ha))
        assertTrue(DashboardLinks.offOriginLoad("not a url", ha))
    }

    @Test
    fun `an on-origin page start and teardown blank are left alone`() {
        assertTrue(!DashboardLinks.offOriginLoad("http://homeassistant.local:8123/profile", ha))
        assertTrue(!DashboardLinks.offOriginLoad("http://HOMEASSISTANT.local:8123/", ha))
        // query strings and traversal stay on the origin; the origin is the
        // whole rule, not the path
        assertTrue(!DashboardLinks.offOriginLoad("http://homeassistant.local:8123/redirect?to=http://evil", ha))
        assertTrue(!DashboardLinks.offOriginLoad("http://homeassistant.local:8123/../..//x", ha))
        assertTrue(!DashboardLinks.offOriginLoad("about:blank", ha)) // loaded on teardown
    }

    // --- play_media (P2.5) ---------------------------------------------------

    @Test
    fun `play_media paths resolve against the HA base URL`() {
        assertEquals(
            "http://homeassistant.local:8123/api/tts_proxy/clip.mp3",
            DashboardLinks.resolvePlayUrl("/api/tts_proxy/clip.mp3", ha),
        )
    }

    @Test
    fun `absolute play_media urls pass through from anywhere`() {
        assertEquals(
            "http://elsewhere.example:9000/a.mp3",
            DashboardLinks.resolvePlayUrl("http://elsewhere.example:9000/a.mp3", ha),
        )
        assertEquals(
            "https://example.org/a.mp3",
            DashboardLinks.resolvePlayUrl("https://example.org/a.mp3", ha),
        )
    }

    @Test
    fun `play_media refuses credentials, odd schemes and network-path references`() {
        assertNull(DashboardLinks.resolvePlayUrl("http://user:pass@homeassistant.local:8123/a.mp3", ha))
        assertNull(DashboardLinks.resolvePlayUrl("ftp://homeassistant.local/a.mp3", ha))
        assertNull(DashboardLinks.resolvePlayUrl("//evil.example/a.mp3", ha))
        assertNull(DashboardLinks.resolvePlayUrl("a.mp3", ha))
        assertNull(DashboardLinks.resolvePlayUrl("http://", ha))
    }
}
