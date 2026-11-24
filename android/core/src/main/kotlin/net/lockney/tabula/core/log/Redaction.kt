package net.lockney.tabula.core.log

import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.LogLevel

/**
 * Credential and URL redaction for log lines (PROTOCOL.md 4.8, P5.4).
 *
 * Lines never leave the device with credentials in them, so the rule is applied
 * where a line enters the buffer rather than where it is sent: one place to
 * audit, and nothing unredacted can be read back out of the ring. The patterns
 * mirror the ones the mock device uses on its own lines:
 *
 * - `Authorization` headers, with or without a `Bearer ` prefix, in the forms a
 *   log line actually shows them (`Authorization: Bearer x`, `authorization=x`,
 *   and JSON's `"Authorization": "x"`);
 * - `access_token=`, `authSig=` and `refresh_token` values;
 * - query strings of URLs and absolute paths, which carry the same tokens on
 *   this frontend (the reason `current_page` and `page_changed` carry paths only).
 *
 * The replacement keeps the key so the line still says what was hidden.
 */
object Redaction {
    /** Header or `key=value` forms whose value is a credential. */
    private val SECRET = Regex(
        """(?i)(authorization["']?\s*[:=]\s*["']?)(?:[a-z]+\s+)?[^\s"',;}]+""" +
            """|(?i)(access_token["']?\s*[:=]\s*["']?)[^&\s"',;}]+""" +
            """|(?i)(authsig["']?\s*[:=]\s*["']?)[^&\s"',;}]+""" +
            """|(?i)(refresh_token["']?\s*[:=]\s*["']?)[^&\s"',;}]+""",
    )

    /** A URL or an absolute path, up to its query string or fragment. */
    private val URL_QUERY = Regex("""(https?://[^\s?#]+|(?<![\w/])/[^\s?#]*)[?#][^\s]*""")

    /** [message] with credentials replaced and query strings dropped. */
    fun apply(message: String): String {
        val withoutSecrets = SECRET.replace(message) { match ->
            val key = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            "${key.trimEnd('"', '\'', ' ', ':', '=')}=<redacted>"
        }
        return URL_QUERY.replace(withoutSecrets) { match -> match.groupValues[1] }
    }
}
