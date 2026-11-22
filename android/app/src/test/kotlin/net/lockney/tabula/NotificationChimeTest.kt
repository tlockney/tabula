package net.lockney.tabula

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** The card chime's stream and level (F-NTF-09, ADR 0026, issue #121). */
class NotificationChimeTest {
    private class FakeTone(val stream: Int, val volume: Int) : NotificationChime.Tone {
        var beeps = 0
        var released = false

        override fun beep() {
            beeps++
        }

        override fun release() {
            released = true
        }
    }

    private val made = mutableListOf<FakeTone>()
    private var level = 100
    private val chime = NotificationChime(volume = { level }) { stream, volume -> FakeTone(stream, volume).also { made += it } }

    @Test
    fun `the chime plays on the media stream, which silent mode does not mute`() {
        chime.play()
        assertEquals(AudioManager.STREAM_MUSIC, made.single().stream)
        assertEquals(1, made.single().beeps)
    }

    @Test
    fun `the default level is the media volume itself`() {
        chime.play()
        assertEquals(100, made.single().volume)
    }

    @Test
    fun `the generator is kept between chimes and rebuilt when the level changes`() {
        chime.play()
        chime.play()
        assertEquals(1, made.size)
        assertEquals(2, made[0].beeps)

        level = 40
        chime.play()
        assertEquals(2, made.size)
        assertEquals(true, made[0].released)
        assertEquals(40, made[1].volume)
        assertEquals(1, made[1].beeps)
    }

    @Test
    fun `level zero plays nothing`() {
        level = 0
        chime.play()
        assertEquals(0, made.size)
    }

    @Test
    fun `a failing tone source never throws`() {
        val broken = NotificationChime(volume = { 100 }) { _, _ -> error("no audio") }
        broken.play()
        broken.release()
    }
}
