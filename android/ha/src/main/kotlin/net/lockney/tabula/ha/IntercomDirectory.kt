package net.lockney.tabula.ha

import java.io.IOException
import net.lockney.tabula.core.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One tablet HA listed for this device's intercom screen (packet P9.4 item 1).
 *
 * [deviceId] is the HA device registry id an `intercom_start_request` carries
 * as `target_device_id`; [areaName] is the label the screen shows, with [name]
 * standing in for a tablet that has no area. HA leaves out this device and its
 * own area's tablets, because neither a call nor a broadcast reaches them.
 */
data class IntercomPeerEntry(
    val deviceId: String,
    /** The tablet's TLP id, which is how a ring names its caller (review of #176). */
    val tabulaDeviceId: String?,
    val name: String,
    val areaId: String?,
    val areaName: String?,
    val canTalk: Boolean,
    val inCall: Boolean,
)

/**
 * The intercom directory over the device's own HA connection (packet P9.4 item
 * 1, ADR 0017's one socket): `tabula/intercom_directory` is a plain HA
 * WebSocket command the integration registers for a paired tablet, not a TLP
 * frame, so the protocol's frames are exactly as P9.1 merged them.
 *
 * The device cannot read the registries itself - HA's registry commands are
 * admin-only and a tablet's user is not an admin - so the integration answers
 * with what the screen needs and nothing else.
 */
object IntercomDirectory {
    const val TYPE = "tabula/intercom_directory"

    /**
     * How long the screen waits for the list before it says HA is not
     * reachable: `COMMAND_TIMEOUT`, the ADR 0002 value for every command HA
     * answers, because this is one (review of #176).
     */
    internal val timeoutMs: Long get() = Constants.COMMAND_TIMEOUT_S * 1000L

    /** Parse the command's result. An entry that is not a device is skipped, not invented. */
    fun parse(result: JsonElement?): List<IntercomPeerEntry> {
        val devices = (result as? JsonObject)?.get("devices") as? JsonArray ?: return emptyList()
        return devices.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val deviceId = entry.string("device_id") ?: return@mapNotNull null
            IntercomPeerEntry(
                deviceId = deviceId,
                tabulaDeviceId = entry.string("tabula_device_id"),
                name = entry.string("name") ?: deviceId,
                areaId = entry.string("area_id"),
                areaName = entry.string("area_name"),
                canTalk = entry.boolean("can_talk") ?: false,
                inCall = entry.boolean("in_call") ?: false,
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /**
     * Ask HA for the list on [channel]. Null means the question could not be
     * asked or answered (no connection, a refusal, a timeout); an empty list is
     * a real answer - nobody to call.
     */
    suspend fun fetch(channel: HaChannel?): List<IntercomPeerEntry>? {
        if (channel == null) return null
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                channel.call(buildJsonObject { put("type", TYPE) })
            } catch (e: TimeoutCancellationException) {
                // HaChannel's own reply timeout: HA did not answer in time
                null
            } catch (e: IOException) {
                // the connection ended, or HA refused the command
                null
            } catch (e: CancellationException) {
                // this device's connection went away: let the caller cancel
                throw e
            }
        }
        return result?.let(::parse)
    }
}
