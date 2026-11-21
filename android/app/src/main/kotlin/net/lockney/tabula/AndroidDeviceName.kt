package net.lockney.tabula

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The OS's user-visible device name: the Bluetooth name (Fire OS shows
 * "Fire Tablet"), else the global device name (API 25+), else null.
 * On Android 12+ a non-system app reading Secure "bluetooth_name" gets a
 * SecurityException; runCatching turns that into a fallthrough, so keep it.
 */
internal fun osDeviceName(context: Context): String? {
    val resolver = context.contentResolver
    val bluetooth = runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull()
    if (!bluetooth.isNullOrBlank()) return bluetooth
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        val global = runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        if (!global.isNullOrBlank()) return global
    }
    return null
}

/** The pairing screen's prefilled name for this tablet (issue #79). */
internal fun defaultPairingName(context: Context, deviceId: String): String =
    PairingName.default(osDeviceName(context), Build.MODEL, deviceId)
