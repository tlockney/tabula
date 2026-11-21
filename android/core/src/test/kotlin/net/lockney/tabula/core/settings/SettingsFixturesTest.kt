package net.lockney.tabula.core.settings

import net.lockney.tabula.core.TestRepo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated settings classes against the golden fixtures in
 * schema/fixtures/settings/v1: the same known-good and known-bad documents the
 * schema tests in Python use (P0.5, plan section 8).
 */
class SettingsFixturesTest {
    private val dir = File(TestRepo.root(), "schema/fixtures/settings/v1")

    @Test
    fun `defaults fixture decodes to the generated defaults`() {
        assertEquals(Settings(), SettingsCodec.decode(File(dir, "defaults.json").readText()))
    }

    @Test
    fun `an empty document equals the defaults`() {
        assertEquals(Settings(), SettingsCodec.decode(File(dir, "empty.json").readText()))
    }

    @Test
    fun `full fixture decodes with every value`() {
        val s = SettingsCodec.decode(File(dir, "full.json").readText())
        assertEquals("Kitchen tablet", s.identity.name)
        assertEquals("http://homeassistant.local:8123", s.connection.haUrl)
        assertEquals(75, s.screen.brightness)
        assertEquals(true, s.screen.adaptiveBrightness)
        assertEquals(false, s.screen.keepScreenOn)
        assertEquals(120, s.screensaver.idleTimeout)
        assertEquals(Mode.Weather, s.screensaver.mode)
        assertEquals(35, s.screensaver.brightness)
        assertEquals(0, s.notifications.defaultDuration)
        assertEquals(DefaultLevel.Warning, s.notifications.defaultLevel)
        assertEquals(true, s.notifications.chimeByLevel.success)
        assertEquals(2, s.notifications.maxVisible)
        assertEquals(80, s.audio.mediaVolume)
        assertEquals(AnnounceVolumeBoost.None, s.audio.announceVolumeBoost)
    }

    @Test
    fun `encode round-trips every valid fixture`() {
        for (name in listOf("defaults.json", "empty.json", "full.json")) {
            val decoded = SettingsCodec.decode(File(dir, name).readText())
            assertEquals(decoded, SettingsCodec.decode(SettingsCodec.encode(decoded)), name)
        }
    }

    @Test
    fun `every invalid fixture is rejected`() {
        val files = File(dir, "invalid").listFiles { f -> f.isFile && f.name != "EXPECTED.json" }!!
            .sortedBy { it.name }
        assertTrue(files.size >= 15, "expected the invalid settings fixtures, found ${files.size}")
        for (file in files) {
            assertFailsWith<Exception>("${file.name} must be rejected") {
                SettingsCodec.decode(file.readText())
            }
        }
    }

    @Test
    fun `schema version matches the schema`() {
        val schema = File(TestRepo.root(), "schema/settings.schema.json").readText()
        assertTrue(schema.contains("\"x-tabula-schema-version\": 1"))
        assertEquals(1, SCHEMA_VERSION)
    }
}
