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
 * Observable, not polled: consumers register a [Listener] and re-render on
 * each transition instead of re-reading the flag every frame. android/core
 * has no coroutine dependency, so the observation seam is a plain listener
 * registration returning a removal function, matching the module's style.
 * Plain JVM — no Android types — so the default is JVM-testable.
 */
interface NightState {
    /** True while night mode is showing; false otherwise. */
    val isNight: Boolean

    /**
     * Register for night-mode transitions; the returned function removes the
     * listener. A listener sees the new value on every transition, on the
     * thread that made the change (ordering is the implementer's).
     */
    fun addListener(listener: Listener): () -> Unit

    /** Called with the new [NightState.isNight] on every night-mode transition. */
    fun interface Listener {
        fun onNightChanged(isNight: Boolean)
    }

    companion object {
        /**
         * The default before P3.4: night mode never engages. Consumers render
         * their day colors against it, and its listeners never fire — the
         * honest behavior when the night-mode flag is not implemented (or the
         * device has no light sensor, F-SSV-10).
         */
        val NEVER: NightState = object : NightState {
            override val isNight: Boolean = false
            override fun addListener(listener: Listener) = {}
        }
    }
}
