package net.lockney.tabula

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import net.lockney.tabula.core.screen.ScreenController

/**
 * The real panel (packet P2.3).
 *
 * - Backlight (F-SCR-03): with WRITE_SETTINGS (granted over ADB, docs/DEVICE.md)
 *   the system brightness is written and the system's auto-brightness is
 *   switched to manual so it does not drift away. Without it, only the app's
 *   own window is dimmed, through [TabulaStatus.windowBrightness].
 * - Power (F-SCR-14): off is DevicePolicyManager.lockNow, which needs the
 *   [ScreenAdminReceiver] admin to be active. On is a short wake lock that
 *   wakes the device, then the dashboard is brought to the front.
 * - [onPower] hears screen on/off broadcasts for the `screen_on` state key.
 */
class AndroidPanel(
    context: Context,
    private val onPower: (Boolean) -> Unit,
) : Panel {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val policy = appContext.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(appContext, ScreenAdminReceiver::class.java)

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onPower(true)
                Intent.ACTION_SCREEN_OFF -> onPower(false)
            }
        }
    }

    init {
        appContext.registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
    }

    fun close() {
        runCatching { appContext.unregisterReceiver(powerReceiver) }
    }

    private fun canWriteSystem(): Boolean = Settings.System.canWrite(appContext)

    override fun setLevel(percent: Int) {
        if (canWriteSystem()) {
            val resolver = appContext.contentResolver
            val mode = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, ScreenController.toSystemLevel(percent))
            TabulaStatus.windowBrightness.value = null
        } else {
            TabulaStatus.windowBrightness.value = ScreenController.toSystemLevel(percent) / 255f
        }
    }

    override fun readLevel(): Int? = runCatching {
        ScreenController.fromSystemLevel(
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
        )
    }.getOrNull()

    override val isOn: Boolean
        get() = power?.isInteractive ?: true

    override val canPowerOff: Boolean
        get() = policy?.isAdminActive(admin) == true

    override fun powerOff() {
        policy?.lockNow()
    }

    @Suppress("DEPRECATION") // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP is still the way to wake from a service
    override fun powerOn() {
        power?.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "tabula:panel-on",
        )?.apply {
            // wakes the panel on acquire; ON_AFTER_RELEASE restarts the idle timer on release
            setReferenceCounted(false)
            try {
                acquire()
            } finally {
                release()
            }
        }
        val intent = Intent(appContext, MainActivity::class.java)
            // singleTask in the manifest brings the one existing window
            // forward (SINGLE_TOP only restates it); REORDER_TO_FRONT from
            // the application context stacked a new instance on every wake
            // (issue #96)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { appContext.startActivity(intent) }
            .onFailure { TabulaLog.w(TAG, "could not bring the dashboard to the front", it) }
    }

    override fun setKeepOn(keep: Boolean) {
        TabulaStatus.keepScreenOn.value = keep
    }

    private companion object {
        const val TAG = "TabulaScreen"
    }
}

/**
 * The tablet's ambient light sensor (F-SCR-05). [available] is false on
 * hardware without one; the app then does not declare adaptive_brightness.
 */
class LightSensor(context: Context, private val onLux: (Double) -> Unit) {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean
        get() = sensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            event.values.firstOrNull()?.let { onLux(it.toDouble()) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        sensor?.let { manager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        manager?.unregisterListener(listener)
    }
}
