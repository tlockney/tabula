package net.lockney.tabula

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.settings.SettingsCodec

/**
 * The device's persisted state: device_id (generated once on first launch,
 * plan 5.2), the HA base URL, HA's device id, the applied settings and their
 * revision, and the pairing credential.
 *
 * The refresh token is encrypted with an AES-GCM key that lives in the Android
 * Keystore and never leaves it (plan 5.2: Keystore-backed storage, excluded
 * from backups via allowBackup=false in the manifest).
 */
class TabulaStore(context: Context) {
    private val prefs = context.getSharedPreferences("tabula_device", Context.MODE_PRIVATE)

    /** UUIDv4, lowercase, generated on first access and persisted (PROTOCOL 2.1). */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).commit()
    }

    var haUrl: String?
        get() = prefs.getString(KEY_HA_URL, null)
        set(value) = prefs.edit().putString(KEY_HA_URL, value).apply()

    var haDeviceId: String?
        get() = prefs.getString(KEY_HA_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_HA_DEVICE_ID, value).apply()

    var settingsRevision: Int
        get() = prefs.getInt(KEY_SETTINGS_REVISION, 0)
        set(value) = prefs.edit().putInt(KEY_SETTINGS_REVISION, value).apply()

    /** The applied settings document; defaults until HA pushes one (plan 5.6). */
    var settings: Settings
        get() = prefs.getString(KEY_SETTINGS, null)?.let { runCatching { SettingsCodec.decode(it) }.getOrNull() }
            ?: Settings()
        set(value) = prefs.edit().putString(KEY_SETTINGS, SettingsCodec.encode(value)).apply()

    /** The pairing credential, or null while the device is unpaired. */
    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)?.let { blob ->
            runCatching { decrypt(blob) }.getOrNull()
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove(KEY_REFRESH_TOKEN)
            } else {
                edit.putString(KEY_REFRESH_TOKEN, encrypt(value))
            }
            edit.apply()
        }

    val isPaired: Boolean
        get() = refreshToken != null

    /** Drop the credential (revoked in HA, or the user unpaired): back to pairing. */
    fun clearCredential() {
        refreshToken = null
    }

    // --- Keystore-backed encryption ------------------------------------------

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        // IV (12 bytes) || ciphertext+tag, base64 in one blob
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > 12) { "credential blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HA_URL = "ha_url"
        const val KEY_HA_DEVICE_ID = "ha_device_id"
        const val KEY_SETTINGS = "settings"
        const val KEY_SETTINGS_REVISION = "settings_revision"
        const val KEY_REFRESH_TOKEN = "refresh_token_encrypted"
        const val KEYSTORE_ALIAS = "tabula_credential"
    }
}
