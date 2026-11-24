package net.lockney.tabula.core.tlp

import java.time.OffsetDateTime

/**
 * Wire-name patterns and bounds from schema/tlp-v1.schema.json (bounds chosen
 * in docs/decisions/0002-constants.md). One place, so the codec and its tests
 * agree with tools/validate_frames.py.
 */
internal object Wire {
    val deviceId = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    val commandId = Regex("^[0-9A-Za-z_-]{8,64}$")

    /** Opaque voice-turn ID chosen by the integration (P7.2). */
    val turnId = Regex("^[0-9A-Za-z_-]{8,64}$")
    val capability = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** Frontend path with no query string or fragment, single leading slash (state, page_changed). */
    val pagePath = Regex("^/(?![/\\\\])[^\\s\\\\?#]*$")

    /** Like [pagePath] but without the query/fragment exclusions (navigate). */
    val navigatePath = Regex("^/(?![/\\\\])[^\\s\\\\]*$")

    /** Wake-word identifier: lowercase, digits and underscores (P7.1). */
    val wakeWordPhrase = Regex("^[a-z0-9_]{1,32}$")

    /** Absolute http(s) URL without userinfo, or a path resolved against the HA base URL. */
    val playUrl = Regex("^(https?://[^/@\\s?#]+(/[^\\s]*)?|/(?![/\\\\])[^\\s\\\\]*)$")

    val mdiIcon = Regex("^mdi:[a-z0-9-]+$")
    val pairingCode = Regex("^[0-9]{6}$")
    val nonceHash = Regex("^[0-9a-f]{64}$")
    val nonce = Regex("^[A-Za-z0-9_-]{43}$")

    fun requireDeviceId(value: String) {
        require(deviceId.matches(value)) { "device_id must be a lowercase UUIDv4: $value" }
    }

    fun requireCommandId(value: String) {
        require(commandId.matches(value)) { "command_id must be 8-64 chars of [0-9A-Za-z_-]: $value" }
    }

    fun requireTurnId(value: String) {
        require(turnId.matches(value)) { "turn_id must be 8-64 chars of [0-9A-Za-z_-]: $value" }
    }

    fun requireLength(path: String, value: String, min: Int, max: Int) {
        // JSON Schema lengths count code points; String.length counts UTF-16 units.
        val codePoints = value.codePointCount(0, value.length)
        require(codePoints in min..max) { "$path must be $min-$max characters, is $codePoints" }
    }

    fun requireRange(path: String, value: Int, min: Int, max: Int) {
        require(value in min..max) { "$path must be $min-$max, is $value" }
    }

    fun requireRange(path: String, value: Double, min: Double, max: Double) {
        require(value in min..max) { "$path must be $min-$max, is $value" }
    }

    fun requireMin(path: String, value: Double, min: Double) {
        require(value >= min) { "$path must be >= $min, is $value" }
    }

    fun requirePattern(path: String, value: String, pattern: Regex) {
        require(pattern.matches(value)) { "$path does not match ${pattern.pattern}: $value" }
    }

    /**
     * RFC 3339 date-time check (format "date-time" in the schema). Parsed with
     * OffsetDateTime, not Instant: HA sends numeric offsets like +00:00, which
     * Instant.parse accepts only from JDK 12 / API 34 — the desugared API 28
     * runtime rejects them. OffsetDateTime.parse takes both Z and numeric
     * offsets from JDK 8.
     */
    fun requireDateTime(path: String, value: String) {
        require(runCatching { OffsetDateTime.parse(value) }.isSuccess) {
            "$path must be an RFC 3339 date-time: $value"
        }
    }
}
