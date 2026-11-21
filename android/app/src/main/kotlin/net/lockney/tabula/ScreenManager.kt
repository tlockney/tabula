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
    private var fadeJob: Job? = null
    private var recheckJob: Job? = null

    /** Full screen state for registration (PROTOCOL 4.2). */
    fun current(): StateDelta {
        val state = controller.current()
        val level = if (controller.configured) state.brightness else panel.readLevel() ?: state.brightness
        return state.copy(brightness = level, screenOn = panel.isOn)
    }

    override fun applySettings(settings: Settings) {
        val decision = controller.applySettings(settings.screen, now())
        panel.setKeepOn(controller.keepOn)
        carryOut(decision)
    }

    /** A light reading from the sensor. */
    fun onLux(lux: Double) = carryOut(controller.onLux(lux, now()))

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
        if (brightness != null) carryOut(controller.onManualBrightness(brightness))
        when (on) {
            true -> if (!panel.isOn) panel.powerOn()
            false -> if (panel.isOn) panel.powerOff()
            null -> Unit
        }
        return CommandHandler.Reply.Ok()
    }

    private fun carryOut(decision: ScreenController.Decision) {
        decision.level?.let { target ->
            val gen = generation.incrementAndGet()
            fadeJob?.cancel()
            val from = synchronized(this) { onPanel }
            if (decision.fade && from != null) {
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
                carryOut(controller.recheck(now()))
            }
        }
    }

    /** Write [level] if [gen] is still current. Returns false when a newer decision took over. */
    @Synchronized
    private fun write(gen: Int, level: Int): Boolean {
        if (gen != generation.get()) return false
        panel.setLevel(level)
        onPanel = level
        return true
    }

    private fun publish(delta: StateDelta) {
        if (delta.isNotEmpty) listener?.invoke(delta)
    }
}
