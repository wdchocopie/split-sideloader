package com.sideload.splitinstaller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sideload.splitinstaller.core.install.RomQuirks
import com.sideload.splitinstaller.core.install.ShizukuShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What only a real phone can show. Every Shizuku command failed from the first release to
 * 1.5.2 ("process hasn't exited") and no JVM test could have noticed, because the bug was in
 * how a remote process is waited for. These run the same calls the installer makes.
 *
 * The Shizuku tests are skipped when Shizuku is not running. When it runs but has not
 * allowed this app they fail, so a green run always means they really ran: install the debug
 * app, allow it in Shizuku once, and keep it installed between runs (see README).
 */
@RunWith(AndroidJUnit4::class)
class ShellDeviceTest {

    private fun needShizuku() {
        assumeTrue("Shizuku is not running", ShizukuShell.isRunning())
        assertTrue("Shizuku is running but has not allowed this app; allow it in Shizuku", ShizukuShell.isGranted())
    }

    @Test
    fun shizukuRunsACommandAndWaitsForIt() {
        needShizuku()
        val r = ShizukuShell.run("echo split-sideloader", timeoutSeconds = 20)
        assertEquals(r.text, 0, r.code)
        assertEquals("split-sideloader", r.out.trim())
    }

    @Test
    fun shizukuDeliversStdinAsInstallWriteNeedsIt() {
        needShizuku()
        val payload = ByteArray(3 shl 20) { (it % 251).toByte() }
        val r = ShizukuShell.run("wc -c", timeoutSeconds = 60) { out -> out.write(payload) }
        assertEquals(r.text, 0, r.code)
        assertEquals(payload.size.toString(), r.out.trim())
    }

    @Test
    fun shizukuGivesUpOnACommandThatHangs() {
        needShizuku()
        val r = ShizukuShell.run("sleep 30", timeoutSeconds = 2)
        assertNotEquals(0, r.code)
        assertTrue(r.text, "timed out" in r.err)
    }

    @Test
    fun shizukuReportsAFailingCommand() {
        needShizuku()
        val r = ShizukuShell.run("exit 3", timeoutSeconds = 20)
        assertEquals(3, r.code)
    }

    @Test
    fun theSystemInstallerIsFoundAndIsNotTheChooser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = RomQuirks.systemInstaller(context)
        assertNotNull(installer)
        assertNotEquals("android", installer)
    }
}
