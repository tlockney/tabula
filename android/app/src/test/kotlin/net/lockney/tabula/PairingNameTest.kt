package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pairing name's default and its bounds (F-MGT-03, issue #79). */
class PairingNameTest {
    private val id = "78b2c4d0-0000-4000-8000-000000000001"

    @Test
    fun `the default is the OS device name plus the id prefix`() {
        assertEquals("Fire Tablet 78b2", PairingName.default("Fire Tablet", "KFTRPWI", id))
    }

    @Test
    fun `without an OS name the model stands in, then a generic name`() {
        assertEquals("KFTRPWI 78b2", PairingName.default(null, "KFTRPWI", id))
        assertEquals("KFTRPWI 78b2", PairingName.default("  ", "KFTRPWI", id))
        assertEquals("Tabula 78b2", PairingName.default(null, "", id))
    }

    @Test
    fun `two identical tablets get different defaults`() {
        val other = "0f0e0d0c-0000-4000-8000-000000000002"
        assertEquals("Fire Tablet 0f0e", PairingName.default("Fire Tablet", "KFTRPWI", other))
    }

    @Test
    fun `the typed name wins, trimmed, and blank falls back to the default`() {
        assertEquals("Office Kiosk", PairingName.resolve("  Office Kiosk ", "Fire Tablet 78b2"))
        assertEquals("Fire Tablet 78b2", PairingName.resolve("   ", "Fire Tablet 78b2"))
        assertEquals("Fire Tablet 78b2", PairingName.resolve(null, "Fire Tablet 78b2"))
    }

    @Test
    fun `names stay within the schema bound, counted in code points`() {
        val long = "x".repeat(70)
        assertEquals(64, PairingName.resolve(long, "d").length)
        val emoji = "📱".repeat(70) // 70 code points, 140 chars
        val clipped = PairingName.resolve(emoji, "d")
        assertEquals(64, clipped.codePointCount(0, clipped.length))
        assertEquals(64, PairingName.default("y".repeat(80), null, id).length)
    }

    @Test
    fun `the field filter bounds input in code points, not UTF-16 units`() {
        val full = "📱".repeat(63) // 63 code points, 126 chars
        assertEquals(null, PairingName.limitInsert(full, full.length, full.length, "📱"))
        assertEquals("", PairingName.limitInsert(full + "x", full.length + 1, full.length + 1, "y"))
        assertEquals("ab", PairingName.limitInsert(full.dropLast(2), 124, 124, "abc"))
        // replacing a range frees its code points
        assertEquals(null, PairingName.limitInsert(full + "x", 0, 2, "z"))
        assertEquals("z", PairingName.limitInsert(full + "x", 0, 2, "zz"))
    }

    @Test
    fun `clip bounds the model sent at pairing`() {
        assertEquals("KFTRPWI", PairingName.clip("KFTRPWI"))
        assertEquals(64, PairingName.clip("m".repeat(90)).length)
    }
}
