package com.ggumtak.readeraplus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S §3.3 / §3.9: fresh-install detection. */
class InstallStateTest {

    @Test
    fun decide() {
        // Empty settings prefs: a fresh install with nothing of its own → the offer is pending.
        assertTrue(InstallState.decide(emptyList()))
        // An existing user updating (or prefs restored by Android Auto Backup): done.
        assertFalse(InstallState.decide(listOf("r.fontId")))
        assertFalse(InstallState.decide(listOf("lastScan")))
    }

    @Test
    fun verifyReplacesACopiedIdAndLeavesTheOfferAlone() {
        val changes = InstallState.verified("old-id", storedAt = 1000, firstInstallTime = 2000) { "new-id" }!!
        assertEquals("new-id", changes[InstallState.KEY_INSTALL_ID])
        assertEquals(2000L, changes[InstallState.KEY_INSTALLED_AT])
        assertFalse(changes.containsKey(InstallState.KEY_OFFER))
        // This install's own id: nothing.
        assertNull(InstallState.verified("id", 2000, 2000) { error("not needed") })
        // Unknown install time, or no id yet: nothing.
        assertNull(InstallState.verified("id", 1000, 0) { error("not needed") })
        assertNull(InstallState.verified(null, 1000, 2000) { error("not needed") })
        assertNull(InstallState.verified("", 1000, 2000) { error("not needed") })
    }

    @Test
    fun newIdsAreLowerHexAndDistinct() {
        val a = InstallState.newId()
        val b = InstallState.newId()
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
        assertFalse(a == b)
        // id8 is the prefix of the auto file names.
        assertEquals(a.take(8), AutoBackup.parseAutoName(AutoBackup.autoName(a.take(8), 0L))!!.first)
    }

    @Test
    fun keysAreTransient() {
        for (k in listOf(InstallState.KEY_INSTALL_ID, InstallState.KEY_INSTALLED_AT, InstallState.KEY_OFFER,
                AutoBackup.PREF_CHECKED_AT, AutoBackup.PREF_WRITTEN_AT, AutoBackup.PREF_HASH, AutoBackup.PREF_SUMMARY,
                AutoBackup.PREF_OWNER)) {
            assertTrue(k, SettingsJson.isTransient(k))
        }
        assertFalse(SettingsJson.isTransient("a.autoBackup"))
    }
}
