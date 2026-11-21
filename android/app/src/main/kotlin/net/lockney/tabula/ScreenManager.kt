package net.lockney.tabula

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screen.ScreenController
import net.lockney.tabula.core.settings.Screen
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler

/** The panel hardware, behind an interface so [ScreenManager] runs on a plain JVM in tests. */
interface Panel {
    /** Write a level, percent, to the backlight (or the window fallback). */
    fun setLevel(percent: Int)

    /** The current backlight level, percent, or null when it cannot be read. */
    fun readLevel(): Int?

    /** Whether the panel is on (the device is interactive). */
    val isOn: Boolean

    /** Whether [powerOff] can work (device admin is active, F-SCR-14). */
    val canPowerOff: Boolean

    fun powerOff()

    fun powerOn()

    fun setKeepOn(keep: Boolean)
}

/** What [TabulaCommands] needs from screen control. */
interface ScreenControl {
    /** The `screen` command (PROTOCOL 4.5). */
    fun command(on: Boolean?, brightness: Int?): CommandHandler.Reply

    /** A settings document was applied (startup or set_settings). */
    fun applySettings(settings: Settings)
}

/**
 * Screen control (packet P2.3): carries out [ScreenController]'s decisions on
 * the [Panel], fades adaptive changes, rechecks a held reading, keeps the
 * screen settings across restarts and publishes screen state.
 *
 * Runs for the service's lifetime, independent of the HA connection, so
 * adaptive brightness keeps working while HA is down (F-SCR-05).
 *
 * Panel writes are ordered by a generation counter: each decision starts a new
 * generation, and a fade step from an older one is dropped, so a manual level
 * can never be overwritten by the tail of an earlier fade.
 *
 * While the screensaver shows, [setOverride] holds the panel at its level
 * (F-SSV-17, packet P2.4). Decisions keep running and update the reported
 * state, but their level is only remembered; it returns when the override
 * is cleared. The screensaver level is never persisted, so a restart during
 * the night does not adopt it as the day level.
 */
class ScreenManager(
    private val controller: ScreenController,
    private val panel: Panel,
    private val persist: (Screen) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : ScreenControl {
    /** Receives every screen state change; the session sets it while connected. */
    @Volatile
    var listener: ((StateDelta) -> Unit)? = null

    private val generation = AtomicInteger(0)
    private var onPanel: Int? = null

    /** The screensaver's level while it holds the panel (F-SSV-17), else null. */
    private var override: Int? = null

    /** The level to return to when the override is cleared. */
    private var restore: Int? = null

    /** The level the latest decision aims for; a fade may not have reached it yet. */
    private var decided: Int? = null
    private var fadeJob: Job? = null
    private var recheckJob: Job? = null

    /** Full screen state for registration (PROTOCOL 4.2). */
    fun current(): StateDelta {
        val state = controller.current()
        val level = when {
            controller.configured -> state.brightness
            else -> heldLevel() ?: panel.readLevel() ?: state.brightness
        }
        return state.copy(brightness = level, screenOn = panel.isOn)
    }

    override fun applySettings(settings: Settings) = decide {
        controller.applySettings(settings.screen, now()).also { panel.setKeepOn(controller.keepOn) }
    }

    /** A light reading from the sensor. */
    fun onLux(lux: Double) = decide { controller.onLux(lux, now()) }

    /** The panel went on or off (screen broadcasts). */
    fun onPanelPower(on: Boolean) = publish(StateDelta(screenOn = on))

    override fun command(on: Boolean?, brightness: Int?): CommandHandler.Reply {
        if (on == false && !panel.canPowerOff) {
            // F-SCR-14: without device admin the panel stays on; nothing else changes
            return CommandHandler.Reply.Error(
                ErrorCode.FAILED,
                "panel power-off needs the device admin component; see docs/DEVICE.md",
            )
        }
        if (brightness != null) decide { controller.onManualBrightness(brightness) }
        when (on) {
            true -> if (!panel.isOn) panel.powerOn()
            false -> if (panel.isOn) panel.powerOff()
            null -> Unit
        }
        return CommandHandler.Reply.Ok()
    }

    /**
     * Hold the panel at [level] while the screensaver shows, or release it with
     * null: the level decided meanwhile (or the one from before) comes back.
     */
    @Synchronized
    fun setOverride(level: Int?) {
        if (level == override) return
        generation.incrementAndGet()
        fadeJob?.cancel()
        // the decided level, not a mid-fade one: the cancelled fade's target comes back
        if (override == null) restore = decided ?: onPanel ?: panel.readLevel()
        override = level
        val target = level ?: restore ?: return
        panel.setLevel(target)
        onPanel = target
    }

    @Synchronized
    private fun heldLevel(): Int? = if (override != null) restore else null

    /**
     * Compute a decision and start carrying it out under one monitor (the same
     * one [write] takes), so a decision computed before another one can never
     * take a newer generation after it: a reading decided just before a manual
     * command cannot overwrite the manual level.
     */
    @Synchronized
    private fun decide(compute: () -> ScreenController.Decision) = carryOut(compute())

    private fun carryOut(decision: ScreenController.Decision) {
        decision.level?.let { target ->
            decided = target
            val gen = generation.incrementAndGet()
            fadeJob?.cancel()
            val from = onPanel
            if (decision.fade && from != null && override == null) {
                val steps = ScreenController.fadeSteps(from, target)
                val stepMs = Constants.ADAPTIVE_FADE_MS / steps.size.coerceAtLeast(1)
                fadeJob = scope.launch {
                    for (level in steps) {
                        if (!write(gen, level)) return@launch
                        if (level != target) delay(stepMs)
                    }
                }
            } else {
                write(gen, target)
            }
        }
        decision.state?.let(::publish)
        decision.settings?.let(persist)
        decision.retryInMs?.let { wait ->
            recheckJob?.cancel()
            recheckJob = scope.launch {
                delay(wait)
                decide { controller.recheck(now()) }
            }
        }
    }

    /** Write [level] if [gen] is still current. Returns false when a newer decision took over. */
    @Synchronized
    private fun write(gen: Int, level: Int): Boolean {
        if (gen != generation.get()) return false
        if (override != null) {
            restore = level // shown when the screensaver lets go
            return true
        }
        panel.setLevel(level)
        onPanel = level
        return true
    }

    private fun publish(delta: StateDelta) {
        if (delta.isNotEmpty) listener?.invoke(delta)
    }
}
