package net.lockney.tabula.core.screensaver

/**
 * Whether clock night mode is currently active (FEATURES F-SSV-10).
 *
 * Pre-committed contract (ADR 0002, "Settings schema v2"): P3.4 implements it
 * from the tablet's own light sensor against `screensaver.night_mode`'s
 * threshold and hysteresis, and the At a Glance row (P3.6) and the corner
 * widgets (P3.11) read it to pick the night color. It landed with the schema
 * so those packets compile against it before the hardware-dependent sensor
 * work exists.
 *
 * Plain JVM — no Android types — so the default is JVM-testable. `isNight`
 * is a property, not an event stream: consumers re-render on their own
 * invalidation, and P3.4 may add change notification as an extension if
 * measurement shows it needed.
 */
interface NightState {
    /** True while night mode is showing; false otherwise. */
    val isNight: Boolean

    companion object {
        /**
         * The default before P3.4: night mode never engages. Consumers render
         * their day colors against it, which is the honest behavior when the
         * night-mode flag is not implemented (or the device has no light
         * sensor, F-SSV-10).
         */
        val NEVER: NightState = object : NightState {
            override val isNight: Boolean = false
        }
    }
}
