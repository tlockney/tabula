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
import net.lockney.tabula.core.Constants

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

        EventName.WAKE_WORD_DETECTED ->
            json.decodeFromJsonElement(WakeWordDetected.serializer(), data)

        EventName.VOICE_TURN_STARTED ->
            json.decodeFromJsonElement(VoiceTurnStarted.serializer(), data)

        EventName.VOICE_TURN_ENDED ->
            json.decodeFromJsonElement(VoiceTurnEnded.serializer(), data)
        EventName.INTERCOM_ANSWER ->
            json.decodeFromJsonElement(IntercomAnswer.serializer(), data)

        EventName.INTERCOM_DECLINE ->
            json.decodeFromJsonElement(IntercomDecline.serializer(), data)

        EventName.INTERCOM_SIGNAL ->
            json.decodeFromJsonElement(IntercomSignal.serializer(), data)

        EventName.INTERCOM_ENDED ->
            json.decodeFromJsonElement(IntercomEnded.serializer(), data)

        EventName.INTERCOM_START_REQUEST ->
            json.decodeFromJsonElement(IntercomStartRequest.serializer(), data)
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

        fun intercomAnswer(id: Int, callId: String) =
            frame(id, EventName.INTERCOM_ANSWER, buildJsonObject { put("call_id", callId) })

        fun intercomDecline(id: Int, callId: String) =
            frame(id, EventName.INTERCOM_DECLINE, buildJsonObject { put("call_id", callId) })

        fun intercomSignal(id: Int, callId: String, kind: IntercomSignalKind, payload: String) =
            frame(
                id,
                EventName.INTERCOM_SIGNAL,
                buildJsonObject {
                    put("call_id", callId)
                    put("kind", wireName(kind))
                    put("payload", payload)
                },
            )

        fun intercomEnded(id: Int, callId: String, reason: IntercomEndReason) =
            frame(
                id,
                EventName.INTERCOM_ENDED,
                buildJsonObject {
                    put("call_id", callId)
                    put("reason", wireName(reason))
                },
            )

        fun intercomStartRequest(
            id: Int,
            callId: String,
            mode: IntercomMode,
            targetDeviceId: String? = null,
            targetAreaId: String? = null,
        ) =
            frame(
                id,
                EventName.INTERCOM_START_REQUEST,
                buildJsonObject {
                    put("call_id", callId)
                    put("mode", wireName(mode))
                    targetDeviceId?.let { put("target_device_id", it) }
                    targetAreaId?.let { put("target_area_id", it) }
                },
            )

        fun wakeWordDetected(id: Int, phrase: String, probability: Int) =
            frame(
                id,
                EventName.WAKE_WORD_DETECTED,
                buildJsonObject {
                    put("phrase", phrase)
                    put("probability", probability)
                },
            )
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

/**
 * One accepted wake-word detection (P7.1, PROTOCOL 4.7). ``probability`` is the
 * model's quantised score 0-255 at the moment of detection; the threshold is a
 * constant (ADR 0002), not part of the frame.
 */
@Serializable
data class WakeWordDetected(
    val phrase: String,
    val probability: Int,
) : EventData {
    init {
        Wire.requireLength("event.phrase", phrase, 1, 32)
        Wire.requirePattern("event.phrase", phrase, Wire.wakeWordPhrase)
        Wire.requireRange("event.probability", probability, 0, 255)
    }
}

@Serializable
data class PageChanged(
    val path: String,
) : EventData {
    init {
        Wire.requireLength("event.path", path, 1, 2048)
        Wire.requirePattern("event.path", path, Wire.pagePath)
    }
}

/**
 * A voice turn opened and its PCM is streaming on the binary channel (P7.2).
 * ``phrase`` is present only when the device's own wake word started the turn.
 */
@Serializable
data class VoiceTurnStarted(
    @SerialName("turn_id") val turnId: String,
    val phrase: String? = null,
) : EventData {
    init {
        Wire.requireTurnId(turnId)
        phrase?.let {
            Wire.requireLength("event.phrase", it, 1, 32)
            Wire.requirePattern("event.phrase", it, Wire.wakeWordPhrase)
        }
    }
}

/** A voice turn closed; the device says why (command, its own limit, or a failure). */
@Serializable
data class VoiceTurnEnded(
    @SerialName("turn_id") val turnId: String,
    val reason: VoiceTurnEndReason,
) : EventData {
    init {
        Wire.requireTurnId(turnId)
    }
}

/**
 * One call ringing and answered on this device (P9.1, PROTOCOL 4.7). The call
 * id is the one the ring carried; HA relays the answer to the caller.
 */
@Serializable
data class IntercomAnswer(
    @SerialName("call_id") val callId: String,
) : EventData {
    init {
        Wire.requireCallId(callId)
    }
}

@Serializable
data class IntercomDecline(
    @SerialName("call_id") val callId: String,
) : EventData {
    init {
        Wire.requireCallId(callId)
    }
}

/** The device's own offer, answer or candidate for a call (the other direction of the command). */
@Serializable
data class IntercomSignal(
    @SerialName("call_id") val callId: String,
    val kind: IntercomSignalKind,
    val payload: String,
) : EventData {
    init {
        Wire.requireCallId(callId)
        Wire.requireSignalPayload("event.payload", payload, Constants.INTERCOM_SIGNAL_MAX_BYTES)
    }
}

/** A call this device was in has ended, and why (PROTOCOL 4.7). */
@Serializable
data class IntercomEnded(
    @SerialName("call_id") val callId: String,
    val reason: IntercomEndReason,
) : EventData {
    init {
        Wire.requireCallId(callId)
    }
}

/**
 * A call started on this device's screen (ADR 0025 decision 6): HA resolves the
 * target and rings it. A `call` needs exactly one target; the two targets
 * exclude each other; a broadcast may carry neither (every device but this one).
 */
@Serializable
data class IntercomStartRequest(
    @SerialName("call_id") val callId: String,
    val mode: IntercomMode,
    @SerialName("target_device_id") val targetDeviceId: String? = null,
    @SerialName("target_area_id") val targetAreaId: String? = null,
) : EventData {
    init {
        Wire.requireCallId(callId)
        require(!(targetDeviceId != null && targetAreaId != null)) {
            "a call has one target, a device or an area"
        }
        if (mode == IntercomMode.CALL) {
            require(targetDeviceId != null || targetAreaId != null) { "a call needs one target" }
        }
        targetDeviceId?.let { Wire.requirePattern("event.target_device_id", it, Wire.haDeviceId) }
        targetAreaId?.let { Wire.requirePattern("event.target_area_id", it, Wire.haAreaId) }
    }
}
