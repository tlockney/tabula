package net.lockney.tabula.core.voice

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped frontend's numerics on synthesised PCM (P7.1).
 *
 * This is the one component the desk harness cannot cover: the harness scores
 * with the reference frontend (`pymicro_features`), so nothing else checks that
 * the Kotlin port's FFT, mel filterbank and quantisation agree with what the
 * model was trained on. There is no golden file to compare against - producing
 * one would mean committing reference features the harness does not emit - so
 * these tests pin the properties a wrong transform breaks: the mel band a pure
 * tone lands in, the ordering of bands as frequency rises, and the per-frame
 * level normalisation. They do not measure accuracy; the owner's microphone run
 * does, and ADR 0030 item 5 says so.
 */
class FeatureFrontendTest {
    private fun tone(hz: Double, amplitude: Double = 8000.0, ms: Int = 30): ShortArray {
        val samples = FeatureFrontend.SAMPLE_RATE * ms / 1000
        return ShortArray(samples) { i ->
            (amplitude * sin(2.0 * Math.PI * hz * i / FeatureFrontend.SAMPLE_RATE))
                .toInt()
                .toShort()
        }
    }

    /** The quantised frame the frontend produces for exactly one 30 ms window. */
    private fun oneFrame(pcm: ShortArray): ByteArray {
        val frontend = FeatureFrontend()
        var frame: ByteArray? = null
        frontend.feed(pcm, pcm.size) { row -> frame = row.copyOf() }
        assertEquals(1, frontend.frames, "one 30 ms window must produce one frame")
        return frame ?: error("no frame produced")
    }

    /**
     * The bin whose mel centre sits closest to [hz]: bands are equally spaced
     * in mel, so band b's centre is melToHz(low + (high - low) * (b + 1) / (BINS + 1)).
     */
    private fun expectedBin(hz: Double): Int {
        val melLow = FeatureFrontend.hzToMel(FeatureFrontend.LOW_HZ)
        val melHigh = FeatureFrontend.hzToMel(FeatureFrontend.HIGH_HZ)
        return (0 until FeatureFrontend.BINS).minByOrNull { b ->
            val centre = FeatureFrontend.melToHz(
                melLow + (melHigh - melLow) * (b + 1) / (FeatureFrontend.BINS + 1),
            )
            abs(centre - hz)
        } ?: error("no bins")
    }

    private fun peakBin(frame: ByteArray): Int =
        frame.indices.maxByOrNull { frame[it].toInt() } ?: error("empty frame")

    @Test
    fun `a pure tone peaks in the mel band that contains it`() {
        for (hz in listOf(300.0, 1000.0, 2500.0, 5000.0)) {
            val frame = oneFrame(tone(hz))
            // the tone's own band, give or take one: a triangular filterbank
            // centred on equal mel steps puts a tone between two centres, and
            // the 30 ms Hann window widens each bin by a few hertz
            assertTrue(
                abs(peakBin(frame) - expectedBin(hz)) <= 1,
                "a $hz Hz tone must land near its own mel band " +
                    "(bin ${peakBin(frame)}, expected ${expectedBin(hz)})",
            )
        }
    }

    @Test
    fun `the peak band rises with frequency`() {
        val peaks = listOf(300.0, 1000.0, 2500.0, 5000.0).map { peakBin(oneFrame(tone(it))) }
        assertEquals(peaks.sorted(), peaks, "mel bands are ordered by frequency, got $peaks")
        assertEquals(peaks.distinct(), peaks, "neighbouring test tones must not share a band")
    }

    @Test
    fun `a frame is normalised onto the model's int8 range`() {
        val frame = oneFrame(tone(1000.0))
        val values = frame.map { it.toInt() }
        assertTrue(values.max() <= 127, "quantised values stay inside the tensor's range")
        assertTrue(values.min() >= -128, "quantised values stay inside the tensor's range")
        // the loudest bin is pinned to the top of the range: 26 / 0.10196 = 255,
        // stored as 255 - 128 = 127
        assertEquals(127, values.max(), "the loudest bin sits at TOP_VALUE")
    }

    @Test
    fun `a tone twenty decibels below the peak lands at the frame floor`() {
        // The frame's dynamic range is 20 dB: the loudest bin sits at TOP_VALUE
        // and anything 20 dB below it quantises to the bottom of the range,
        // 0 on the tensor's scale, which is -128 as a signed byte. This is the
        // property that ties DYNAMIC_RANGE_DB, TOP_VALUE and the quantisation
        // together, and it is what makes a quiet room look flat rather than
        // random.
        val both = ShortArray(FeatureFrontend.WINDOW_SAMPLES) { i ->
            val t = 2.0 * Math.PI * i / FeatureFrontend.SAMPLE_RATE
            (8000.0 * sin(t * 1000.0) + 800.0 * sin(t * 3000.0)).toInt().toShort()
        }
        val frame = oneFrame(both)
        val loudBin = expectedBin(1000.0)
        val quietBin = expectedBin(3000.0)
        assertEquals(127, frame[loudBin].toInt(), "the loud tone is the frame's peak")
        // the loud tone's spectral leakage lifts the quiet band a couple of
        // decibels, so this is "a few dB of the floor", not exactly -128
        assertTrue(
            frame[quietBin].toInt() <= -100,
            "20 dB down sits near the floor, got ${frame[quietBin].toInt()}",
        )
    }

    @Test
    fun `reset drops buffered audio so the next frame starts clean`() {
        val frontend = FeatureFrontend()
        var frames = 0
        // half a window of tone, then a reset, then a full window of tone:
        // the reset must discard the partial window, so exactly one frame lands
        frontend.feed(tone(1000.0, ms = 15), FeatureFrontend.SAMPLE_RATE * 15 / 1000) { frames++ }
        assertEquals(0, frames, "half a window makes no frame")
        frontend.reset()
        frontend.feed(tone(1000.0), FeatureFrontend.WINDOW_SAMPLES) { frames++ }
        assertEquals(1, frames, "the reset window is gone, not merged with the new audio")
    }
}
