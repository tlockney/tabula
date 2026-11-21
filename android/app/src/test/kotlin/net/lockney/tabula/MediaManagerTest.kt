package net.lockney.tabula

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import net.lockney.tabula.core.media.MediaController
import net.lockney.tabula.core.tlp.EventName
import net.lockney.tabula.core.tlp.MediaControlAction
import net.lockney.tabula.core.tlp.MediaType
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler

/**
 * The media manager against fakes (packet P2.5): URL resolution, the
 * fetch-without-credential path, the player drive, events and state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaManagerTest {
    private val scope = TestScope()
    private val mediaDir = kotlin.io.path.createTempDirectory("p25-manager").toFile()
    private val downloaded = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val volumeWrites = mutableListOf<Int>()
    private val published = mutableListOf<StateDelta>()
    private val fakePlayer = FakePlayer()
    private val manager = MediaManager(
        controller = MediaController(),
        haUrl = { "http://ha:8123" },
        download = { url ->
            downloaded += url
            File.createTempFile("tabula-media", null, mediaDir).also { files += it }
        },
        player = fakePlayer,
        audio = object : AudioVolume {
            override fun setVolume(percent: Int) {
                volumeWrites += percent
            }

            override fun setMuted(muted: Boolean) {}
        },
        scope = scope,
    ).also { it.listener = { delta -> published += delta } }

    private fun eventText(frame: net.lockney.tabula.core.tlp.EventFrame): String {
        val reason = (frame.data["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        return "${frame.event.name}:$reason"
    }

    private fun events(): List<String> = buildList {
        while (true) {
            val build = manager.events.tryReceive().getOrNull() ?: break
            add(eventText(build(1)))
        }
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        mediaDir.deleteRecursively()
    }

    @Test
    fun `plays an HA path resolved against the base URL`() {
        manager.current() // registration reported the initial state
        val reply = manager.play("/api/tts_proxy/clip.mp3", MediaType.AUDIO, announce = true, volume = null)
        assertTrue(reply is CommandHandler.Reply.Ok, reply.toString())
        scope.runCurrent()
        assertEquals(listOf("http://ha:8123/api/tts_proxy/clip.mp3"), downloaded)
        assertTrue(fakePlayer.started.isNotEmpty(), "prepared audio starts at once")
        assertEquals(net.lockney.tabula.core.tlp.MediaState.PLAYING, published.last { it.mediaState != null }.mediaState)
    }

    @Test
    fun `plays an absolute URL as-is and applies the per-call volume`() {
        manager.play("http://elsewhere.example/a.mp3", MediaType.MUSIC, announce = false, volume = 70)
        scope.runCurrent()
        assertEquals(listOf("http://elsewhere.example/a.mp3"), downloaded)
        assertEquals(listOf(70), volumeWrites)
    }

    @Test
    fun `a bad URL is invalid_args and nothing is fetched`() {
        val reply = manager.play("ftp://ha/a.mp3", MediaType.AUDIO, false, null)
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(
            net.lockney.tabula.core.tlp.ErrorCode.INVALID_ARGS,
            (reply as CommandHandler.Reply.Error).code,
        )
        assertTrue(downloaded.isEmpty())
    }

    @Test
    fun `the audio played to the end and media_finished carries the reason`() {
        manager.current() // registration reported the initial state
        manager.play("/a.mp3", MediaType.AUDIO, true, null)
        scope.runCurrent()
        fakePlayer.started.last().ended()
        assertEquals(listOf("${EventName.MEDIA_FINISHED.name}:ended"), events())
        assertEquals(net.lockney.tabula.core.tlp.MediaState.IDLE, published.last { it.mediaState != null }.mediaState)
    }

    @Test
    fun `a playback's file is deleted when it ends (review must-fix 1)`() {
        manager.current()
        manager.play("/a.mp3", MediaType.AUDIO, true, null)
        scope.runCurrent()
        assertTrue(files.single().exists(), "the downloaded audio is there while it plays")
        fakePlayer.started.last().ended()
        assertTrue(!files.single().exists(), "a terminal state deletes the file")
    }

    @Test
    fun `a playback's file is deleted when the player reports an error`() {
        manager.current()
        manager.play("/a.mp3", MediaType.AUDIO, true, null)
        scope.runCurrent()
        fakePlayer.started.last().error()
        assertEquals(listOf("${EventName.MEDIA_FINISHED.name}:error"), events())
        assertTrue(!files.single().exists(), "a terminal state deletes the file")
    }

    @Test
    fun `a failed fetch is a terminal error`() {
        val failing = MediaManager(
            controller = MediaController(),
            haUrl = { "http://ha:8123" },
            download = { throw java.io.IOException("HA is down") },
            player = fakePlayer,
            audio = object : AudioVolume {
                override fun setVolume(percent: Int) {}
                override fun setMuted(muted: Boolean) {}
            },
            scope = scope,
        )
        val reply = failing.play("/a.mp3", MediaType.AUDIO, true, null)
        assertTrue(reply is CommandHandler.Reply.Ok, "the command is accepted; the failure is an event (F-TTS-10)")
        scope.runCurrent()
        assertEquals(listOf("${EventName.MEDIA_FINISHED.name}:error"), events(failing))
        failing.close()
    }

    @Test
    fun `the startup sweep removes only leftover tabula-media files`() {
        val stale = File(mediaDir, "tabula-media-crashed.tmp").apply { writeText("partial") }
        val staleTwo = File(mediaDir, "tabula-media-older.tmp").apply { writeText("partial") }
        val other = File(mediaDir, "unrelated.txt").apply { writeText("keep me") }
        MediaManager.sweepStale(mediaDir)
        assertTrue(!stale.exists())
        assertTrue(!staleTwo.exists())
        assertTrue(other.exists(), "sweeping never touches files it does not own")
    }

    @Test
    fun `controls reach the player and unsupported ones fail`() {
        manager.play("/a.mp3", MediaType.AUDIO, true, null)
        scope.runCurrent()
        assertTrue(manager.control(MediaControlAction.PAUSE, null, null) is CommandHandler.Reply.Ok)
        assertEquals(1, fakePlayer.paused)
        assertTrue(manager.control(MediaControlAction.SEEK, null, 2.0) is CommandHandler.Reply.Ok)
        assertEquals(2.0, fakePlayer.sought.last())

        val noQueue = manager.control(MediaControlAction.NEXT, null, null)
        assertTrue(noQueue is CommandHandler.Reply.Error)
        assertTrue((noQueue as CommandHandler.Reply.Error).message.contains("no queue"))
    }

    @Test
    fun `a second play stops the first and its late callbacks are dropped`() {
        manager.current() // registration reported the initial state
        manager.play("/a.mp3", MediaType.AUDIO, true, null)
        scope.runCurrent()
        val prepared = fakePlayer.started.last()

        manager.play("/b.mp3", MediaType.AUDIO, true, null)
        scope.runCurrent()
        assertEquals(listOf("${EventName.MEDIA_FINISHED.name}:stopped"), events())
        assertTrue(prepared.stopped, "the replaced playback was released")
        assertTrue(!files[0].exists(), "the replaced playback's file was deleted")

        // the replaced playback's decoder answers after the stop: dropped, not started
        prepared.lateEnded()
        assertTrue(events().isEmpty(), "a superseded playback's late callback reaches no one")
    }

    private fun events(manager: MediaManager): List<String> {
        val builds = mutableListOf<String>()
        while (true) {
            val build = manager.events.tryReceive().getOrNull() ?: break
            builds += eventText(build(1))
        }
        return builds
    }

    /** Records what the manager drives; `late` paths replay after a stop. */
    private class FakePlayer : MediaPlayerHandle {
        val started = mutableListOf<FakePrepared>()
        var paused = 0
        val sought = mutableListOf<Double>()

        override fun prepare(file: File, onEnded: () -> Unit, onError: (String) -> Unit): MediaPlayerHandle.Prepared =
            FakePrepared(onEnded, onError).also { started += it }

        inner class FakePrepared(
            private val onEnded: () -> Unit,
            private val onError: (String) -> Unit,
        ) : MediaPlayerHandle.Prepared {
            var stopped = false

            /** The playback's own completion: skipped once stopped. */
            fun ended() {
                if (!stopped) onEnded()
            }

            /** A mid-playback decoder error: a terminal state too (F-TTS-10). */
            fun error() {
                if (!stopped) onError("decode failed")
            }

            /** A callback racing a release; the manager's generation drops it. */
            fun lateEnded() {
                onEnded()
            }

            override fun start() {}

            override fun pause() {
                paused++
            }

            override fun resume() {}

            override fun seekTo(seconds: Double) {
                sought += seconds
            }

            override fun stop() {
                stopped = true
            }
        }
    }
}
