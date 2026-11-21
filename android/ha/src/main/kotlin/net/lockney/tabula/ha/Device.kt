package net.lockney.tabula.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.StateDelta

/**
 * The device's state, sampled by the client: [current] is the full picture sent
 * with every tabula/register; [changes] carries only the keys that changed.
 * [changes] must be an unlimited channel so no change is dropped while a
 * frame is being coalesced (PROTOCOL 4.4).
 */
interface StateSource {
    fun current(): StateDelta
    val changes: kotlinx.coroutines.channels.ReceiveChannel<StateDelta>
}

/**
 * Runs one TLP command. Called once per incoming command event; the client
 * turns the reply into exactly one tabula/result (docs/PROTOCOL.md 4.5-4.6).
 *
 * [onNewConnection] resets per-connection state (the set_settings revision
 * floor, PROTOCOL 4.5) after each successful registration.
 */
interface CommandHandler {
    fun onNewConnection()

    fun handle(command: String, args: JsonObject): Reply

    sealed interface Reply {
        data class Ok(val data: JsonObject? = null) : Reply

        data class Error(val code: ErrorCode, val message: String) : Reply
    }
}

/** Merge the non-null keys of [other] on top of [base] (state coalescing, PROTOCOL 4.4). */
fun mergeState(base: StateDelta, other: StateDelta): StateDelta = StateDelta(
    screenOn = other.screenOn ?: base.screenOn,
    brightness = other.brightness ?: base.brightness,
    adaptiveBrightness = other.adaptiveBrightness ?: base.adaptiveBrightness,
    keepScreenOn = other.keepScreenOn ?: base.keepScreenOn,
    screensaverActive = other.screensaverActive ?: base.screensaverActive,
    screensaverMode = other.screensaverMode ?: base.screensaverMode,
    screensaverBrightness = other.screensaverBrightness ?: base.screensaverBrightness,
    nextScreensaver = other.nextScreensaver ?: base.nextScreensaver,
    batteryLevel = other.batteryLevel ?: base.batteryLevel,
    charging = other.charging ?: base.charging,
    network = other.network ?: base.network,
    wifiRssi = other.wifiRssi ?: base.wifiRssi,
    currentPage = other.currentPage ?: base.currentPage,
    volume = other.volume ?: base.volume,
    muted = other.muted ?: base.muted,
    mediaState = other.mediaState ?: base.mediaState,
    appVersion = other.appVersion ?: base.appVersion,
)
