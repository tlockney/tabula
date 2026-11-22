package net.lockney.tabula.spike.voice

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The level arithmetic and the frontend's two numeric pieces: a known FFT peak
 * and the mel band layout. These are the numbers every Q3/Q4 line prints, so a
 * silent bug here would misreport every measurement.
 */
class LevelsTest {
    @Test
    fun rmsOfFullScaleIsZeroDb() {
        val pcm = ShortArray(1000) { if (it % 2 == 0) 32767 else -32768 }
        assertEquals(0.0, Levels.rmsDb(pcm), 0.1)
    }

    @Test
    fun silenceFloorsAtTheDocumentedDb() {
        assertEquals(Levels.FLOOR_DB, Levels.rmsDb(ShortArray(100)))
    }

    @Test
    fun aTenthOfFullScaleIsMinusTwentyDb() {
        val pcm = ShortArray(1000) { if (it % 2 == 0) 3277 else -3277 }
        assertEquals(-20.0, Levels.rmsDb(pcm), 0.2)
    }

    @Test
    fun activeFractionCountsSamplesAboveTheThreshold() {
        val pcm = ShortArray(100) { if (it % 4 == 0) 2000 else 10 }
        assertEquals(0.25, Levels.activeFraction(pcm), 0.001)
    }

    @Test
    fun peakFindsTheLargestMagnitudeEitherSign() {
        val pcm = shortArrayOf(100, -900, 500)
        assertEquals(900, Levels.peak(pcm))
    }

    @Test
    fun erleIsPositiveWhenTheCancellerRemovesEcho() {
        assertEquals(12.0, Levels.erleDb(offDb = -30.0, onDb = -42.0), 0.001)
    }
}

class FftTest {
    @Test
    fun aOneKiloHertzSinePeaksInItsBin() {
        val size = 512
        val sampleRate = 16000
        val re = DoubleArray(size) { sin(2.0 * Math.PI * 1000.0 * it / sampleRate) }
        val im = DoubleArray(size)
        Fft(size).transform(re, im)
        var peakBin = 0
        var peakPower = -1.0
        for (k in 0 until size / 2) {
            val power = re[k] * re[k] + im[k] * im[k]
            if (power > peakPower) {
                peakPower = power
                peakBin = k
            }
        }
        // 1000 Hz at 16 kHz over 512 points: bin 32, with its mirror at 480
        assertEquals(32, peakBin)
    }

    @Test
    fun aConstantSignalLandsInBinZero() {
        val size = 256
        val re = DoubleArray(size) { 1.0 }
        val im = DoubleArray(size)
        Fft(size).transform(re, im)
        assertEquals(size.toDouble(), re[0], 1e-6)
        for (k in 1 until size / 2) {
            assertTrue(abs(re[k]) < 1e-6 && abs(im[k]) < 1e-6, "bin $k should be empty")
        }
    }
}

class FeatureFrontendTest {
    private fun feed(frontend: FeatureFrontend, samples: ShortArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        // the frontend reuses one array, so copy every frame it hands out
        frontend.feed(samples, samples.size) { frames.add(it.copyOf()) }
        return frames
    }

    @Test
    fun oneFramePerTenMilliseconds() {
        val frontend = FeatureFrontend()
        val frames = feed(frontend, ShortArray(16000))
        // 1 s of audio, a 10 ms hop: the first frame needs 30 ms of history
        assertEquals(98, frames.size)
        assertEquals(98, frontend.frames)
        assertTrue(frames.all { it.size == FeatureFrontend.BINS })
    }

    @Test
    fun theMelBandsRunFromLowToHighFrequency() {
        val low = FeatureFrontend.hzToMel(125.0)
        val high = FeatureFrontend.hzToMel(7500.0)
        assertTrue(high > low)
        assertEquals(125.0, FeatureFrontend.melToHz(low), 0.001)
        assertEquals(7500.0, FeatureFrontend.melToHz(high), 0.01)
    }

    @Test
    fun theLoudestBinReachesTheTopOfTheInt8Range() {
        val frontend = FeatureFrontend()
        val sine = ShortArray(4800) { (12000 * sin(2.0 * Math.PI * 1000.0 * it / 16000)).toInt().toShort() }
        val frames = feed(frontend, sine)
        assertTrue(frames.isNotEmpty())
        val loudest = frames.last().maxOf { it.toInt() }
        assertEquals(127, loudest)
        // and quiet audio still spans a lower range, not nothing at all
        val quiet = feed(FeatureFrontend(), ShortArray(4800) { (20 * sin(2.0 * Math.PI * 1000.0 * it / 16000)).toInt().toShort() })
        assertTrue(quiet.last().maxOf { it.toInt() } <= loudest)
    }
}
