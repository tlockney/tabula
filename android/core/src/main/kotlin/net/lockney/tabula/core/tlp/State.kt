package net.lockney.tabula.core.tlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.nullable

/**
 * Device state (docs/PROTOCOL.md 4.4, $defs "state"): every key is optional,
 * and a frame carries only the keys that changed. Values are validated when
 * constructed, so a decoded [StateDelta] that exists is a valid one.
 *
 * The device only ever sends state; the enum-extension rule for received state
 * values (PROTOCOL 4.8) therefore does not apply on this side, and decoding is
 * strict. Null and absent mean the same thing for the nullable keys.
 */
@Serializable
data class StateDelta(
    @SerialName("screen_on") val screenOn: Boolean? = null,
    val brightness: Int? = null,
    @SerialName("adaptive_brightness") val adaptiveBrightness: Boolean? = null,
    @SerialName("keep_screen_on") val keepScreenOn: Boolean? = null,
    @SerialName("screensaver_active") val screensaverActive: Boolean? = null,
    @SerialName("screensaver_mode") val screensaverMode: ScreensaverMode? = null,
    @SerialName("screensaver_brightness") val screensaverBrightness: Int? = null,
    @Serializable(with = NextScreensaverSerializer::class)
    @SerialName("next_screensaver") val nextScreensaver: String? = null,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    val network: NetworkType? = null,
    @SerialName("wifi_rssi") val wifiRssi: Int? = null,
    val illuminance: Double? = null,
    @SerialName("current_page") val currentPage: String? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    @SerialName("media_state") val mediaState: MediaState? = null,
    @SerialName("app_version") val appVersion: String? = null,
) {
    init {
        brightness?.let { Wire.requireRange("state.brightness", it, 0, 100) }
        screensaverBrightness?.let { Wire.requireRange("state.screensaver_brightness", it, 0, 100) }
        batteryLevel?.let { Wire.requireRange("state.battery_level", it, 0, 100) }
        volume?.let { Wire.requireRange("state.volume", it, 0, 100) }
        wifiRssi?.let { Wire.requireRange("state.wifi_rssi", it, -127, 0) }
        illuminance?.let { Wire.requireRange("state.illuminance", it, 0.0, 100000.0) }
        nextScreensaver?.takeIf { it != NO_NEXT_SCREENSAVER }?.let { Wire.requireDateTime("state.next_screensaver", it) }
        currentPage?.let {
            Wire.requireLength("state.current_page", it, 1, 2048)
            Wire.requirePattern("state.current_page", it, Wire.pagePath)
        }
        appVersion?.let { Wire.requireLength("state.app_version", it, 1, 64) }
    }

    companion object {
        /**
         * next_screensaver's explicit null (PROTOCOL 4.4: null while the
         * screensaver is active or disabled). A Kotlin null means "key
         * absent" in a delta, so the clear needs its own value; it is
         * encoded as JSON null.
         */
        const val NO_NEXT_SCREENSAVER = "none"
    }

    /** True when at least one key is set (minProperties in the schema). */
    val isNotEmpty: Boolean
        get() = this != StateDelta()
}

/** Encodes [StateDelta.NO_NEXT_SCREENSAVER] as JSON null; decodes JSON null as an absent key. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object NextScreensaverSerializer : kotlinx.serialization.KSerializer<String?> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "net.lockney.tabula.NextScreensaver",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        ).nullable

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String?) {
        if (value == null || value == StateDelta.NO_NEXT_SCREENSAVER) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String? =
        if (decoder.decodeNotNullMark()) decoder.decodeString() else decoder.decodeNull()
}
