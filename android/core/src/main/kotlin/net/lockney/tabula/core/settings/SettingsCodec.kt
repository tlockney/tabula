package net.lockney.tabula.core.settings

import kotlinx.serialization.json.Json

/**
 * Settings document codec (schema v1, generated classes in Settings.kt).
 *
 * Decoding is strict — unknown keys are rejected and the generated classes
 * enforce bounds, lengths, patterns and enum values. The schema sets
 * additionalProperties false, and a device only ever receives documents at or
 * below its own schema version (HA translates down, plan 5.4), so an unknown
 * key means the peer is not speaking this schema and the document is refused.
 *
 * Encoding omits members equal to their defaults, so decode(encode(x)) is the
 * identity on decoded values, and a group HA did not send round-trips as its
 * default instance — "a key HA does not send keeps the device default"
 * (docs/PROTOCOL.md 4.5).
 */
object SettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
    }

    fun decode(text: String): Settings = json.decodeFromString<Settings>(text)

    fun encode(settings: Settings): String = json.encodeToString<Settings>(settings)
}
