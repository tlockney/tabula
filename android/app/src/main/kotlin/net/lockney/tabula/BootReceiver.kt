package net.lockney.tabula

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts the connection service after boot when the device is already paired
 * (FEATURES F-KSK-09). A freshly installed app is in the stopped state and
 * receives no boot broadcast until it has been launched once, so
 * provisioning ends with one manual launch (docs/DEVICE.md).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TabulaStore(context).isPaired) return
        TabulaService.start(context)
    }
}
