package net.lockney.tabula.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detector's control flow on recorded-shaped PCM (P7.1): windowing, cutoff,
 * cooldown and the reported probability. The model itself is exercised by the
 * desk harness (tools/desk/wake_word.py) and the owner's microphone run, so
 * these tests use a stub scorer and never need a model file.
 */
class WakeWordDetectorTest {
    /** A scorer that returns a scripted probability per window and counts calls. */
    private class ScriptedScorer(
        private val rows: Int = 3,
        private val bins: Int = 40,
        private val script: (Int) -> Int,
    ) : WakeWordDetector.Scorer {
        var calls = 0
        var lastWindow: ByteArray? = null
        var closed = false

        override fun rows() = rows
        override fun bins() = bins
        override fun score(window: ByteArray): Int {
            lastWindow = window.copyOf()
            return script(calls++)
        }

        override fun close() {
            closed = true
        }
    }

    /** 10 ms of a 1 kHz tone at 16 kHz, the shape AudioRecord delivers. */
    private fun pcm(ms: Int, amplitude: Double = 8000.0): ShortArray {
        val samples = 16 * ms
        return ShortArray(samples) { i ->
            (amplitude * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / 16000.0)).toInt().toShort()
        }
    }

    @Test
    fun `one inference per new feature row once the window is full`() {
        // 30 ms window, 10 ms hop: three rows fill the model's window at 50 ms
        // and the window is consumed, so 300 ms is nine inferences (one per 30 ms).
        val scorer = ScriptedScorer { 0 }
        val detector = WakeWordDetector(scorer) {}
        detector.feed(pcm(30))
        assertEquals(0, scorer.calls, "one row is not a window")
        detector.feed(pcm(20))
        assertEquals(1, scorer.calls, "three rows fill the window")
        detector.feed(pcm(250))
        assertEquals(9, scorer.calls)
        assertEquals(9, detector.inferences)
    }

    @Test
    fun `a window is rows times bins bytes, oldest row first`() {
        val scorer = ScriptedScorer { 0 }
        val detector = WakeWordDetector(scorer) {}
        detector.feed(pcm(50))
        // three rows of real features: not all zero, and the last row is the
        // newest audio (the ring shifts by the hop each frame)
        val window = scorer.lastWindow!!
        assertEquals(3 * 40, window.size)
        assertTrue(window.any { it != 0.toByte() }, "features must carry the audio")
    }

    @Test
    fun `a probability at the cutoff detects and one below does not`() {
        val detections = mutableListOf<WakeWordDetector.Detection>()
        val scorer = ScriptedScorer { inference -> if (inference == 5) 200 else 199 }
        val detector = WakeWordDetector(scorer) { detections += it }
        detector.feed(pcm(300))
        assertEquals(listOf(WakeWordDetector.Detection("okay_nabu", 200)), detections)
    }

    @Test
    fun `the cooldown collapses a run above the cutoff into one detection`() {
        // Above the cutoff for 30 windows (0.3 s), the shape of one phrase.
        val detections = mutableListOf<WakeWordDetector.Detection>()
        val scorer = ScriptedScorer { inference -> if (inference in 10..40) 250 else 0 }
        val detector = WakeWordDetector(scorer) { detections += it }
        detector.feed(pcm(500))
        assertEquals(1, detections.size)
        assertEquals(250, detections.single().probability)
    }

    @Test
    fun `the cooldown expires and a second phrase is reported`() {
        val detections = mutableListOf<WakeWordDetector.Detection>()
        // two runs 4.5 s apart: 450+ windows of quiet between them
        // 13 s of audio is 432 inferences; the second phrase is 410 of them
        // after the first, past the 133-window cooldown.
        val scorer = ScriptedScorer { inference ->
            when (inference) {
                in 10..20 -> 240
                in 420..425 -> 245
                else -> 0
            }
        }
        val detector = WakeWordDetector(scorer) { detections += it }
        detector.feed(pcm(13000))
        assertEquals(listOf(240, 245), detections.map { it.probability })
        assertEquals("okay_nabu", detections.first().phrase)
    }

    @Test
    fun `a single detection frame is one inference and one event`() {
        val detections = mutableListOf<WakeWordDetector.Detection>()
        val scorer = ScriptedScorer { 255 }
        val detector = WakeWordDetector(scorer) { detections += it }
        detector.feed(pcm(30))
        assertEquals(0, detections.size, "no window yet")
        detector.feed(pcm(20))
        assertEquals(1, detections.size)
        assertEquals(1, detector.detections)
    }

    @Test
    fun `reset drops the window so a reopen does not fire on stale audio`() {
        val detections = mutableListOf<WakeWordDetector.Detection>()
        val scorer = ScriptedScorer { 255 }
        val detector = WakeWordDetector(scorer) { detections += it }
        detector.feed(pcm(80))
        assertEquals(1, detections.size)
        detector.reset()
        val before = scorer.calls
        detector.feed(pcm(10))
        assertEquals(before, scorer.calls, "no window after reset until three rows arrive")
    }

    @Test
    fun `close closes the scorer`() {
        val scorer = ScriptedScorer { 0 }
        WakeWordDetector(scorer) {}.close()
        assertTrue(scorer.closed)
    }

    @Test
    fun `the frontend emits int8 rows at the model's width`() {
        val frontend = FeatureFrontend()
        var rows = 0
        var bins = 0
        frontend.feed(pcm(100), pcm(100).size) { row ->
            rows++
            bins = row.size
        }
        assertEquals(8, rows) // 100 ms of audio: 30 ms window, 10 ms hop
        assertEquals(40, bins)
    }
}
