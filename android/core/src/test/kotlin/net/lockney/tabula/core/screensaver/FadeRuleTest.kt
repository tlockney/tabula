package net.lockney.tabula.core.screensaver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.lockney.tabula.core.tlp.ScreensaverMode

/** Which transitions get the fade (F-SSV-23, ADR 0022). */
class FadeRuleTest {
    @Test
    fun `a disabled fade never applies`() {
        val rule = FadeRule(enabled = false)
        ScreensaverMode.entries.forEach { mode ->
            assertFalse(rule.onShow(mode), "shows $mode at once")
            assertFalse(rule.onDismiss(mode), "hides $mode at once")
        }
    }

    @Test
    fun `the own-content modes fade in on start and out on dismissal`() {
        val rule = FadeRule(enabled = true)
        listOf(ScreensaverMode.CLOCK, ScreensaverMode.BLACK, ScreensaverMode.PHOTOS, ScreensaverMode.WEATHER).forEach { mode ->
            assertTrue(rule.onShow(mode), "$mode fades in (or its stand-in does)")
            assertTrue(rule.onDismiss(mode), "$mode fades out")
        }
    }

    @Test
    fun `the live page and web content switch without a fade`() {
        val rule = FadeRule(enabled = true)
        // dim shows the live dashboard (F-SSV-07); the dashboard view, when it
        // ships, and website content are live pages too (F-SSV-23)
        listOf(ScreensaverMode.DIM, ScreensaverMode.DASHBOARD, ScreensaverMode.WEBSITE).forEach { mode ->
            assertFalse(rule.onShow(mode), "$mode shows at once")
            assertFalse(rule.onDismiss(mode), "$mode hides at once")
        }
    }
}
