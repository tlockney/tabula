package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabulaStoreTest {
    private fun store(prefs: FakeSharedPreferences) = TabulaStore(prefs, PlainTextCrypto)

    @Test
    fun `savePairing persists the credential and HA device id synchronously`() {
        val prefs = FakeSharedPreferences()
        val store = store(prefs)

        assertTrue(store.savePairing("refresh-1", "ha-device-1"))

        // the writes are durable: they went through commit(), not apply()
        assertTrue(TabulaStore.KEY_REFRESH_TOKEN in prefs.committed)
        assertTrue(TabulaStore.KEY_HA_DEVICE_ID in prefs.committed)
        assertTrue(TabulaStore.KEY_REFRESH_TOKEN !in prefs.applied)
        assertTrue(TabulaStore.KEY_HA_DEVICE_ID !in prefs.applied)

        // and they read back
        assertEquals("refresh-1", store.refreshToken)
        assertEquals("ha-device-1", store.haDeviceId)
        assertTrue(store.isPaired)
    }

    @Test
    fun `savePairing reports a failed commit instead of claiming success`() {
        val prefs = FakeSharedPreferences(failCommit = true)
        val store = store(prefs)

        assertFalse(store.savePairing("refresh-1", "ha-device-1"))
        // the caller must not treat the claim as complete with a lost credential
        assertFalse(store.isPaired)
    }

    @Test
    fun `clearCredential removes the credential synchronously`() {
        val prefs = FakeSharedPreferences()
        val store = store(prefs)
        assertTrue(store.savePairing("refresh-1", "ha-device-1"))

        store.clearCredential()

        assertTrue(TabulaStore.KEY_REFRESH_TOKEN in prefs.committed)
        assertNull(store.refreshToken)
        assertFalse(store.isPaired)
        // revocation must not take the HA device id with it (F-HA-01: the HA
        // entry, and its id, stay until deleted in HA)
        assertEquals("ha-device-1", store.haDeviceId)
    }
}
