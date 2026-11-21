package net.lockney.tabula

import android.content.Context
import android.content.SharedPreferences
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
 * from backups via allowBackup=false in the manifest). [SharedPreferences] and
 * [CredentialCrypto] are injected so JVM unit tests can drive the pairing
 * write path with fakes.
 */
class TabulaStore(
    private val prefs: SharedPreferences,
    private val crypto: CredentialCrypto,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("tabula_device", Context.MODE_PRIVATE),
        KeystoreCredentialCrypto,
    )

    /** UUIDv4, lowercase, generated on first access and persisted (PROTOCOL 2.1). */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).commit()
    }

    var haUrl: String?
        get() = prefs.getString(KEY_HA_URL, null)
        set(value) = prefs.edit().putString(KEY_HA_URL, value).apply()

    /** HA's device id for this tablet, written once at pairing (see [savePairing]). */
    val haDeviceId: String?
        get() = prefs.getString(KEY_HA_DEVICE_ID, null)

    var settingsRevision: Int
        get() = prefs.getInt(KEY_SETTINGS_REVISION, 0)
        set(value) = prefs.edit().putInt(KEY_SETTINGS_REVISION, value).apply()

    /** The applied settings document; defaults until HA pushes one (plan 5.6). */
    var settings: Settings
        get() = prefs.getString(KEY_SETTINGS, null)?.let { runCatching { SettingsCodec.decode(it) }.getOrNull() }
            ?: Settings()
        set(value) = prefs.edit().putString(KEY_SETTINGS, SettingsCodec.encode(value)).apply()

    /**
     * The name last typed on the pairing screen (F-MGT-03, issue #100). Kept in
     * its own key because HA's set_settings replaces the whole settings document,
     * and HA keeps the pairing name on its device record, not in identity.name.
     */
    var pairingName: String?
        get() = prefs.getString(KEY_PAIRING_NAME, null)
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_PAIRING_NAME) else edit.putString(KEY_PAIRING_NAME, value)
            edit.apply()
        }

    /**
     * The name to prefill and send at pairing: identity.name when HA's settings
     * set one (a rename in the settings editor), else the last pairing name,
     * else [fallback]. HA's identity.name wins even over a newer typed name:
     * HA's next push would bring it back, and it is the name HA shows.
     */
    fun nameForPairing(fallback: String): String =
        settings.identity.name ?: pairingName ?: fallback

    /** The pairing credential, or null while the device is unpaired. */
    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)?.let { blob ->
            runCatching { crypto.decrypt(blob) }.getOrNull()
        }
        private set(value) {
            // commit, not apply: the credential must be durable the moment it is
            // written, because the claim released it exactly once (PROTOCOL 2.5)
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_REFRESH_TOKEN) else edit.putString(KEY_REFRESH_TOKEN, crypto.encrypt(value))
            edit.commit()
        }

    val isPaired: Boolean
        get() = refreshToken != null

    /**
     * Persist the pairing outcome synchronously: [SharedPreferences.Editor.commit],
     * not apply. The claim releases the credential exactly once (PROTOCOL 2.5),
     * so if the process died before an asynchronous write flushed, the tablet
     * would lose a credential HA has already issued - an orphaned device user
     * and a forced re-pair. Returns false if either write failed, in which case
     * the caller must not treat the claim as complete.
     */
    fun savePairing(credential: String, haDeviceId: String): Boolean =
        prefs.edit().putString(KEY_REFRESH_TOKEN, crypto.encrypt(credential)).commit() and
            prefs.edit().putString(KEY_HA_DEVICE_ID, haDeviceId).commit()

    /** Drop the credential (revoked in HA, or the user unpaired): back to pairing. */
    fun clearCredential() {
        refreshToken = null
    }

    internal companion object {
        internal const val KEY_DEVICE_ID = "device_id"
        internal const val KEY_HA_URL = "ha_url"
        internal const val KEY_HA_DEVICE_ID = "ha_device_id"
        internal const val KEY_SETTINGS = "settings"
        internal const val KEY_SETTINGS_REVISION = "settings_revision"
        internal const val KEY_REFRESH_TOKEN = "refresh_token_encrypted"
        internal const val KEY_PAIRING_NAME = "pairing_name"
    }
}

/** Encryption of the credential blob; the app uses the Android Keystore. */
interface CredentialCrypto {
    fun encrypt(plainText: String): String

    fun decrypt(blob: String): String
}

/** AES-256-GCM under a non-exportable AndroidKeyStore key, random IV per encryption. */
private object KeystoreCredentialCrypto : CredentialCrypto {
    private const val KEYSTORE_ALIAS = "tabula_credential"

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // IV (12 bytes) || ciphertext+tag, base64 in one blob
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > 12) { "credential blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

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
}
