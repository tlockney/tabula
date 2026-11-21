@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.lockney.tabula.core.tlp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * tabula/event (PROTOCOL 4.7). The data member's shape depends on the event
 * name; [eventData] decodes it into the typed [EventData], and the strict path
 * ([Tlp.decodeFrame]) requires that decoding to succeed, matching the schema's
 * per-event data definitions.
 */
@Serializable
data class EventFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "tabula/event",
    val event: EventName,
    val data: JsonObject,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "tabula/event") { "type must be tabula/event" }
    }

    /** Decode data with the given Json (strict for validation, tolerant for runtime). */
    fun eventData(json: Json): EventData = when (event) {
        EventName.NOTIFICATION_SHOWN ->
            json.decodeFromJsonElement(NotificationShown.serializer(), data)

        EventName.NOTIFICATION_DISMISSED ->
            json.decodeFromJsonElement(NotificationDismissed.serializer(), data)

        EventName.NOTIFICATION_ACTION ->
            json.decodeFromJsonElement(NotificationAction.serializer(), data)

        EventName.SCREENSAVER_STARTED ->
            json.decodeFromJsonElement(ScreensaverStarted.serializer(), data)

        EventName.SCREENSAVER_STOPPED ->
            json.decodeFromJsonElement(ScreensaverStopped.serializer(), data)

        EventName.MEDIA_FINISHED ->
            json.decodeFromJsonElement(MediaFinished.serializer(), data)

        EventName.PAGE_CHANGED ->
            json.decodeFromJsonElement(PageChanged.serializer(), data)
    }

    companion object {
        private fun frame(id: Int, event: EventName, data: JsonObject) =
            EventFrame(id = id, event = event, data = data).apply {
                // Strict senders (PROTOCOL 4.0): the factory validates what it built.
                eventData(Tlp.strict)
            }

        fun notificationShown(id: Int, tag: String? = null) =
            frame(id, EventName.NOTIFICATION_SHOWN, buildJsonObject { tag?.let { put("tag", it) } })

        fun notificationDismissed(id: Int, reason: DismissReason, tag: String? = null) =
            frame(
                id,
                EventName.NOTIFICATION_DISMISSED,
                buildJsonObject {
                    put("reason", wireName(reason))
                    tag?.let { put("tag", it) }
                },
            )

        fun notificationAction(id: Int, tag: String, action: String) =
            frame(
                id,
                EventName.NOTIFICATION_ACTION,
                buildJsonObject {
                    put("tag", tag)
                    put("action", action)
                },
            )

        fun screensaverStarted(id: Int, mode: ScreensaverMode, trigger: ScreensaverStartTrigger) =
            frame(
                id,
                EventName.SCREENSAVER_STARTED,
                buildJsonObject {
                    put("mode", wireName(mode))
                    put("trigger", wireName(trigger))
                },
            )

        fun screensaverStopped(id: Int, mode: ScreensaverMode, trigger: ScreensaverStopTrigger) =
            frame(
                id,
                EventName.SCREENSAVER_STOPPED,
                buildJsonObject {
                    put("mode", wireName(mode))
                    put("trigger", wireName(trigger))
                },
            )

        fun mediaFinished(id: Int, reason: MediaFinishedReason) =
            frame(id, EventName.MEDIA_FINISHED, buildJsonObject { put("reason", wireName(reason)) })

        fun pageChanged(id: Int, path: String) =
            frame(id, EventName.PAGE_CHANGED, buildJsonObject { put("path", path) })
    }
}

/** Wire name of a serializable enum value (its @SerialName). */
private inline fun <reified E : Enum<E>> wireName(value: E): String =
    Tlp.json.encodeToJsonElement(kotlinx.serialization.serializer<E>(), value).jsonPrimitive.content

/** Typed data payloads of tabula/event, one class per event name (PROTOCOL 4.7). */
sealed interface EventData

@Serializable
data class NotificationShown(
    val tag: String? = null,
) : EventData {
    init {
        tag?.let { Wire.requireLength("event.tag", it, 1, 64) }
    }
}

@Serializable
data class NotificationDismissed(
    val tag: String? = null,
    val reason: DismissReason,
) : EventData {
    init {
        tag?.let { Wire.requireLength("event.tag", it, 1, 64) }
    }
}

@Serializable
data class NotificationAction(
    val tag: String,
    val action: String,
) : EventData {
    init {
        Wire.requireLength("event.tag", tag, 1, 64)
        Wire.requireLength("event.action", action, 1, 64)
    }
}

@Serializable
data class ScreensaverStarted(
    val mode: ScreensaverMode,
    val trigger: ScreensaverStartTrigger,
) : EventData

@Serializable
data class ScreensaverStopped(
    val mode: ScreensaverMode,
    val trigger: ScreensaverStopTrigger,
) : EventData

@Serializable
data class MediaFinished(
    val reason: MediaFinishedReason,
) : EventData

@Serializable
data class PageChanged(
    val path: String,
) : EventData {
    init {
        Wire.requireLength("event.path", path, 1, 2048)
        Wire.requirePattern("event.path", path, Wire.pagePath)
    }
}
