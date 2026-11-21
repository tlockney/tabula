package net.lockney.tabula.core.settings

import net.lockney.tabula.core.TestRepo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated settings classes against the golden fixtures in
 * schema/fixtures/settings: the same known-good and known-bad documents the
 * schema tests in Python use (P0.5, P3.1, plan section 8).
 *
 * v1 fixtures must still decode: the schema v2 bump is additive, so a v1
 * document decodes to the v2 classes with every v2 key at its default.
 */
class SettingsFixturesTest {
    private val v1 = File(TestRepo.root(), "schema/fixtures/settings/v1")
    private val v2 = File(TestRepo.root(), "schema/fixtures/settings/v2")

    @Test
    fun `defaults fixture decodes to the generated defaults`() {
        assertEquals(Settings(), SettingsCodec.decode(File(v2, "defaults.json").readText()))
    }

    @Test
    fun `an empty document equals the defaults`() {
        assertEquals(Settings(), SettingsCodec.decode(File(v2, "empty.json").readText()))
    }

    @Test
    fun `v1 fixtures still decode with v2 defaults filled`() {
        // defaults and empty v1 documents equal the generated defaults; the
        // v1 full document keeps its v1 values and gets v2 keys at defaults
        for (name in listOf("defaults.json", "empty.json")) {
            assertEquals(Settings(), SettingsCodec.decode(File(v1, name).readText()), name)
        }
        val old = SettingsCodec.decode(File(v1, "full.json").readText())
        assertEquals(ScreensaverMode.Weather, old.screensaver.mode)
        assertEquals(120, old.screensaver.idleTimeout)
        assertEquals(35, old.screensaver.brightness)
        // the v2 keys the v1 document never had decode at their defaults
        assertEquals(10, old.screensaver.dimBrightness)
        assertEquals(ScreensaverClockFace.Digital, old.screensaver.clock.face)
        assertEquals(true, old.widgets.shown)
        assertEquals(emptyList<ScheduleSlots>(), old.schedule.slots)
    }

    @Test
    fun `full fixture decodes with every value`() {
        val s = SettingsCodec.decode(File(v2, "full.json").readText())
        assertEquals("Hall tablet", s.identity.name)
        assertEquals("https://ha.example.com:8123", s.connection.haUrl)
        assertEquals(80, s.screen.brightness)
        assertEquals(true, s.screen.adaptiveBrightness)
        assertEquals(7, s.screensaver.dimBrightness)
        assertEquals(ScreensaverClockFace.Analog, s.screensaver.clock.face)
        assertEquals(700, s.screensaver.clock.fontWeight)
        assertEquals("#FFEEEEEE", s.screensaver.clock.textColor)
        assertEquals(1800, s.screensaver.clock.backgroundRefresh)
        assertEquals(true, s.screensaver.nightMode.enabled)
        assertEquals(15, s.screensaver.nightMode.lightThreshold)
        assertEquals("weather.home", s.screensaver.weather.entityId)
        assertEquals(ScreensaverPhotosSource.Url, s.screensaver.photos.source)
        assertEquals(ScreensaverPhotosFit.Fill, s.screensaver.photos.fit)
        assertEquals(125, s.screensaver.website.zoom)
        assertEquals(true, s.screensaver.pixelShift.enabled)
        assertEquals(true, s.screensaver.fade.enabled)
        assertEquals(
            listOf("binary_sensor.front_door", "lock.garage", "cover.shop"),
            s.atAGlance.entities,
        )
        assertEquals(AtAGlanceStyle.Plain, s.atAGlance.style)
        assertEquals(true, s.widgets.shown)
        assertEquals(40, s.widgets.vignette)
        assertEquals(true, s.widgets.smallClock.enabled)
        assertEquals(WidgetsBatteryCorner.Bl, s.widgets.battery.corner)
        assertEquals(listOf("open", "opening"), s.widgets.entity.visibleValues)
        assertEquals(0, s.notifications.defaultDuration)
        assertEquals(2, s.notifications.maxVisible)
        assertEquals(80, s.audio.mediaVolume)
        assertEquals(AudioAnnounceVolumeBoost.None, s.audio.announceVolumeBoost)
        // the schedule: slots in order, with nulls meaning "inherit"
        assertEquals(2, s.schedule.slots.size)
        val slot = s.schedule.slots.first()
        assertEquals("07:00", slot.start)
        assertEquals(ScheduleSlotsMode.Photos, slot.mode)
        assertEquals(40, slot.brightness)
        assertEquals(0, slot.powerOffDelay)
        assertEquals(true, slot.presenceDismiss)
        assertEquals(true, slot.widgets)
        assertEquals(false, slot.atAGlance)
        val night = s.schedule.slots[1]
        assertEquals(null, night.brightness)
        assertEquals(null, night.widgets)
    }

    @Test
    fun `length bounds count code points, not UTF-16 units`() {
        // One emoji is two UTF-16 units but one code point; the schema's
        // minLength/maxLength count code points, as must the generated check.
        val name = "😀".repeat(64) // 64 code points, 128 UTF-16 units
        val settings = SettingsCodec.decode("""{"identity":{"name":"$name"}}""")
        assertEquals(name, settings.identity.name)
        assertFailsWith<Exception> {
            SettingsCodec.decode("""{"identity":{"name":"${"😀".repeat(65)}"}}""")
        }
    }

    @Test
    fun `encode round-trips every valid fixture`() {
        for (dir in listOf(v1, v2)) {
            for (name in listOf("defaults.json", "empty.json", "full.json")) {
                val decoded = SettingsCodec.decode(File(dir, name).readText())
                assertEquals(decoded, SettingsCodec.decode(SettingsCodec.encode(decoded)), name)
            }
        }
    }

    @Test
    fun `every invalid fixture is rejected`() {
        for (dir in listOf(v1, v2)) {
            val files = File(dir, "invalid").listFiles { f -> f.isFile && f.name != "EXPECTED.json" }!!
                .sortedBy { it.name }
            assertTrue(files.size >= 15, "expected the invalid settings fixtures, found ${files.size}")
            for (file in files) {
                assertFailsWith<Exception>("${file.name} must be rejected") {
                    SettingsCodec.decode(file.readText())
                }
            }
        }
    }

    @Test
    fun `a slot without a start is rejected`() {
        // "start" is required (F-SSV-18): the generated class has no default
        // for it, so decoding fails on a missing key.
        assertFailsWith<Exception> {
            SettingsCodec.decode("""{"schedule": {"slots": [{"mode": "clock"}]}}""")
        }
    }

    @Test
    fun `slot bounds are enforced per item`() {
        assertFailsWith<Exception> {
            SettingsCodec.decode("""{"schedule": {"slots": [{"start": "07:00", "brightness": 101}]}}""")
        }
        assertFailsWith<Exception> {
            SettingsCodec.decode("""{"schedule": {"slots": [{"start": "7:5"}]}}""")
        }
    }

    @Test
    fun `array bounds are enforced`() {
        val many = (1..7).joinToString(",") { """"binary_sensor.b$it"""" }
        assertFailsWith<Exception> {
            SettingsCodec.decode("""{"at_a_glance": {"entities": [$many]}}""")
        }
        val badList = """{"at_a_glance": {"entities": ["Front Door"]}}"""
        assertFailsWith<Exception> {
            SettingsCodec.decode(badList)
        }
    }

    @Test
    fun `schema version matches the schema`() {
        val schema = File(TestRepo.root(), "schema/settings.schema.json").readText()
        assertTrue(schema.contains("\"x-tabula-schema-version\": 2"))
        assertEquals(2, SCHEMA_VERSION)
    }
}
