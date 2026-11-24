package net.lockney.tabula.core.tlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Enumerations of schema/tlp-v1.schema.json. Wire names are the [SerialName]s. */

/** Screensaver mode: state key, command argument, event data and settings all use this set. */
@Serializable
enum class ScreensaverMode {
    @SerialName("clock") CLOCK,
    @SerialName("weather") WEATHER,
    @SerialName("photos") PHOTOS,
    @SerialName("website") WEBSITE,
    @SerialName("dashboard") DASHBOARD,
    @SerialName("calendar") CALENDAR,
    @SerialName("dim") DIM,
    @SerialName("black") BLACK,
}

@Serializable
enum class NetworkType {
    @SerialName("wifi") WIFI,
    @SerialName("ethernet") ETHERNET,
    @SerialName("none") NONE,
}

@Serializable
enum class MediaState {
    @SerialName("idle") IDLE,
    @SerialName("playing") PLAYING,
    @SerialName("paused") PAUSED,
}

/** Log line and log subscription level. */
@Serializable
enum class LogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

/** Notification card severity (command argument and event data). */
@Serializable
enum class NotificationLevel {
    @SerialName("info") INFO,
    @SerialName("success") SUCCESS,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

/** Capabilities the device declares at registration (register $defs; open set on the wire). */
@Serializable
enum class Capability {
    @SerialName("screen") SCREEN,
    @SerialName("brightness") BRIGHTNESS,
    @SerialName("adaptive_brightness") ADAPTIVE_BRIGHTNESS,
    @SerialName("screensaver") SCREENSAVER,
    @SerialName("notifications") NOTIFICATIONS,
    @SerialName("audio") AUDIO,
    @SerialName("battery") BATTERY,
    @SerialName("navigation") NAVIGATION,
    @SerialName("logs") LOGS,
    @SerialName("screenshot") SCREENSHOT,
    // P7.1: declared only while voice.wake_word_enabled is on and RECORD_AUDIO
    // is granted (ADR 0030 item 2).
    @SerialName("wake_word") WAKE_WORD,
    // P9.1: intercom (ADR 0025 decision 7). listen can receive audio, talk can
    // capture; a device with neither is left out of call targets.
    @SerialName("intercom.listen") INTERCOM_LISTEN,
    @SerialName("intercom.talk") INTERCOM_TALK,
}

/** Tabula error codes (command_result.error.code, docs/PROTOCOL.md 6). */
@Serializable
enum class ErrorCode {
    @SerialName("invalid_frame") INVALID_FRAME,
    @SerialName("unsupported_protocol") UNSUPPORTED_PROTOCOL,
    @SerialName("unknown_device") UNKNOWN_DEVICE,
    @SerialName("not_registered") NOT_REGISTERED,
    @SerialName("unsupported_command") UNSUPPORTED_COMMAND,
    @SerialName("invalid_args") INVALID_ARGS,
    @SerialName("stale_revision") STALE_REVISION,
    @SerialName("superseded") SUPERSEDED,
    @SerialName("busy") BUSY,
    @SerialName("failed") FAILED,
}

/** play_media media_type. */
@Serializable
enum class MediaType {
    @SerialName("music") MUSIC,
    @SerialName("audio") AUDIO,
}

/** media_control action. */
@Serializable
enum class MediaControlAction {
    @SerialName("pause") PAUSE,
    @SerialName("resume") RESUME,
    @SerialName("stop") STOP,
    @SerialName("seek") SEEK,
    @SerialName("next") NEXT,
    @SerialName("previous") PREVIOUS,
    @SerialName("set_volume") SET_VOLUME,
    @SerialName("mute") MUTE,
    @SerialName("unmute") UNMUTE,
}

/** notification_dismissed reason. */
@Serializable
enum class DismissReason {
    @SerialName("touch") TOUCH,
    @SerialName("timeout") TIMEOUT,
    @SerialName("replaced") REPLACED,
    @SerialName("command") COMMAND,
}

/** screensaver_started trigger. */
@Serializable
enum class ScreensaverStartTrigger {
    @SerialName("idle") IDLE,
    @SerialName("command") COMMAND,
    @SerialName("schedule") SCHEDULE,
}

/** screensaver_stopped trigger. */
@Serializable
enum class ScreensaverStopTrigger {
    @SerialName("touch") TOUCH,
    @SerialName("command") COMMAND,
    @SerialName("schedule") SCHEDULE,
}

/** intercom_state (docs/PROTOCOL.md 4.4, P9.1). */
@Serializable
enum class IntercomState {
    @SerialName("idle") IDLE,
    @SerialName("ringing") RINGING,
    @SerialName("in_call") IN_CALL,

    /** This device is sending a broadcast; a device playing one is [IN_CALL]. */
    @SerialName("broadcasting") BROADCASTING,
}

/** intercom_ring mode and intercom_start_request mode (ADR 0025 decisions 1, 2). */
@Serializable
enum class IntercomMode {
    @SerialName("call") CALL,
    @SerialName("broadcast") BROADCAST,
}

/** Which WebRTC signal an intercom_signal frame carries. */
@Serializable
enum class IntercomSignalKind {
    @SerialName("offer") OFFER,
    @SerialName("answer") ANSWER,
    @SerialName("candidate") CANDIDATE,
}

/** Why a call ended (intercom_end argument and intercom_ended data). */
@Serializable
enum class IntercomEndReason {
    @SerialName("hangup") HANGUP,
    @SerialName("decline") DECLINE,
    @SerialName("timeout") TIMEOUT,
    @SerialName("limit") LIMIT,
    @SerialName("error") ERROR,
    @SerialName("disconnect") DISCONNECT,
    @SerialName("command") COMMAND,
}

/** media_finished reason. */
@Serializable
enum class MediaFinishedReason {
    @SerialName("ended") ENDED,
    @SerialName("stopped") STOPPED,
    @SerialName("error") ERROR,
}

/** tabula/event names (docs/PROTOCOL.md 4.7). */
@Serializable
enum class EventName {
    @SerialName("notification_shown") NOTIFICATION_SHOWN,
    @SerialName("notification_dismissed") NOTIFICATION_DISMISSED,
    @SerialName("notification_action") NOTIFICATION_ACTION,
    @SerialName("screensaver_started") SCREENSAVER_STARTED,
    @SerialName("screensaver_stopped") SCREENSAVER_STOPPED,
    @SerialName("media_finished") MEDIA_FINISHED,
    @SerialName("page_changed") PAGE_CHANGED,
    @SerialName("wake_word_detected") WAKE_WORD_DETECTED,
    @SerialName("intercom_answer") INTERCOM_ANSWER,
    @SerialName("intercom_decline") INTERCOM_DECLINE,
    @SerialName("intercom_signal") INTERCOM_SIGNAL,
    @SerialName("intercom_ended") INTERCOM_ENDED,
    @SerialName("intercom_start_request") INTERCOM_START_REQUEST,
}

/** http_error error names (docs/PROTOCOL.md 2, 5). */
@Serializable
enum class HttpErrorName {
    @SerialName("invalid_request") INVALID_REQUEST,
    @SerialName("rate_limited") RATE_LIMITED,
    @SerialName("too_many_pending") TOO_MANY_PENDING,
    @SerialName("already_pending") ALREADY_PENDING,
    @SerialName("expired") EXPIRED,
    @SerialName("bad_nonce") BAD_NONCE,
    @SerialName("unsupported_protocol") UNSUPPORTED_PROTOCOL,
    @SerialName("forbidden") FORBIDDEN,
    @SerialName("no_pending_screenshot") NO_PENDING_SCREENSHOT,
    @SerialName("too_large") TOO_LARGE,
}
