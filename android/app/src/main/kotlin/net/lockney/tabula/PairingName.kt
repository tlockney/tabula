package net.lockney.tabula

/**
 * The device name sent at pairing (F-MGT-03, issue #79): what the user typed
 * on the pairing screen, or a default that tells identical tablets apart.
 *
 * The default is the OS's user-visible device name (Fire OS: "Fire Tablet"),
 * else the model, plus the first four characters of the device id, so two
 * tablets of one kind never pair under the same name. The manufacturer alone
 * ("Amazon") named every Fire device the same.
 */
object PairingName {
    /** identity.name's bound (settings schema, ADR 0002 identifiers and labels). */
    const val MAX_LENGTH = 64

    private const val ID_SUFFIX = 4

    /** The prefilled name: [osName], else [model], else "Tabula", then the device id's first characters. */
    fun default(osName: String?, model: String?, deviceId: String): String {
        val base = listOf(osName, model).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: "Tabula"
        val suffix = deviceId.filter(Char::isLetterOrDigit).take(ID_SUFFIX)
        return clip(if (suffix.isEmpty()) base else "$base $suffix")
    }

    /** The name to send: the trimmed [input], or [fallback] when it is blank; at most [MAX_LENGTH] characters. */
    fun resolve(input: String?, fallback: String): String =
        clip(input?.trim()?.takeIf(String::isNotEmpty) ?: fallback)

    private fun clip(name: String): String {
        if (name.codePointCount(0, name.length) <= MAX_LENGTH) return name
        return name.substring(0, name.offsetByCodePoints(0, MAX_LENGTH)).trimEnd()
    }
}
