package net.lockney.tabula.core.screensaver

import net.lockney.tabula.core.tlp.ScreensaverMode

/**
 * Which screensaver transitions get the F-SSV-23 fade. Modes that show the
 * live page itself (dim, the HA dashboard view when it ships) and web content
 * (the website view) switch without one: fading the page the user is looking
 * at would flash it (F-SSV-23).
 *
 * The rule keys on the mode on screen, not the one configured: an unbuilt
 * mode's clock stand-in (ADR 0013 item 3) is Tabula's own overlay and fades,
 * and the exemption is already right for the day website mode renders for
 * real. The fade itself is an alpha animation of the overlay view,
 * SCREENSAVER_FADE_MS long (ADR 0002), applied where the overlay is drawn
 * (ADR 0022).
 */
class FadeRule(
    /** Whether the user asked for the fade (screensaver.fade.enabled). */
    private val enabled: Boolean = false,
) {
    /** Fade the overlay in when [mode] starts showing, or show it at once. */
    fun onShow(mode: ScreensaverMode): Boolean = applies(mode)

    /** Fade the overlay out when [mode] stops showing, or hide it at once. */
    fun onDismiss(mode: ScreensaverMode): Boolean = applies(mode)

    private fun applies(mode: ScreensaverMode) = enabled && mode !in SWITCH_AT_ONCE

    private companion object {
        /** The modes that switch without a fade (F-SSV-23). */
        val SWITCH_AT_ONCE = setOf(ScreensaverMode.DIM, ScreensaverMode.DASHBOARD, ScreensaverMode.WEBSITE)
    }
}
