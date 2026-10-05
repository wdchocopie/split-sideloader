package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.install.RomQuirks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that tells a skin's installer taking over a session from our own install going through. */
class RomQuirksTest {

    private val self = "com.sideload.splitinstaller"
    private val skin = "com.example.romstaller"
    private val store = "com.android.vending"

    @Test
    fun `a new install recorded under the dialog's installer is a takeover`() {
        assertTrue(RomQuirks.tookOver(before = null, after = 1_000L, installer = skin, self = self, dialog = skin))
    }

    @Test
    fun `an update recorded under the dialog's installer is a takeover`() {
        assertTrue(RomQuirks.tookOver(before = 1_000L, after = 2_000L, installer = skin, self = self, dialog = skin))
    }

    @Test
    fun `a known split dropper is a takeover even when the dialog is not resolved`() {
        val blackShark = "com.blackshark.packageinstaller"
        assertTrue(RomQuirks.tookOver(before = 1_000L, after = 2_000L, installer = blackShark, self = self, dialog = null))
    }

    @Test
    fun `our own session going through is not a takeover`() {
        assertFalse(RomQuirks.tookOver(before = 1_000L, after = 2_000L, installer = self, self = self, dialog = skin))
    }

    @Test
    fun `a store updating the package while the dialog is up is not a takeover`() {
        assertFalse(RomQuirks.tookOver(before = 1_000L, after = 2_000L, installer = store, self = self, dialog = skin))
    }

    @Test
    fun `nothing changed yet while the dialog is still up`() {
        assertFalse(RomQuirks.tookOver(before = 1_000L, after = 1_000L, installer = skin, self = self, dialog = skin))
        assertFalse(RomQuirks.tookOver(before = null, after = null, installer = null, self = self, dialog = skin))
    }

    @Test
    fun `an unknown installer is not blamed`() {
        // adb and some stores leave no installer of record; that says nothing about a takeover.
        assertFalse(RomQuirks.tookOver(before = 1_000L, after = 2_000L, installer = null, self = self, dialog = skin))
    }

    @Test
    fun `the build our session carried is ours`() {
        assertTrue(RomQuirks.isOurBuild(installedVersion = 9, version = 9, installedBaseSize = 4_000, baseSize = 4_000))
    }

    @Test
    fun `another copy of the app installed meanwhile is not ours`() {
        assertFalse(RomQuirks.isOurBuild(installedVersion = 8, version = 9, installedBaseSize = 4_000, baseSize = 4_000))
        assertFalse(RomQuirks.isOurBuild(installedVersion = 9, version = 9, installedBaseSize = 3_900, baseSize = 4_000))
    }

    @Test
    fun `what cannot be read does not rule a takeover out`() {
        assertTrue(RomQuirks.isOurBuild(installedVersion = 9, version = 0, installedBaseSize = null, baseSize = 4_000))
        assertTrue(RomQuirks.isOurBuild(installedVersion = 9, version = 9, installedBaseSize = 4_000, baseSize = null))
    }

    @Test
    fun `a skin installer known to drop splits is blamed for them`() {
        assertTrue(RomQuirks.droppedBy("com.blackshark.packageinstaller", takeover = null))
    }

    @Test
    fun `the installer caught taking over a session is blamed`() {
        assertTrue(RomQuirks.droppedBy(skin, takeover = skin))
    }

    @Test
    fun `a store is not blamed for splits it never had`() {
        assertFalse(RomQuirks.droppedBy(store, takeover = null))
        assertFalse(RomQuirks.droppedBy(store, takeover = skin))
    }
}
