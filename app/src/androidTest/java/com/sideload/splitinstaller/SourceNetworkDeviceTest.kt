package com.sideload.splitinstaller

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sideload.splitinstaller.core.sources.LinkProbe
import com.sideload.splitinstaller.core.update.Http
import com.sideload.splitinstaller.core.update.SourceRef
import com.sideload.splitinstaller.core.update.UpdateChecker
import com.sideload.splitinstaller.core.update.UpdateKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The source APIs, asked for real, on a phone: Android's own org.json reads a JSON null as the
 * text "null", which the JVM tests cannot show. Needs a network; the apps named here are
 * long-lived ones on each source.
 */
@RunWith(AndroidJUnit4::class)
class SourceNetworkDeviceTest {

    private val abis = android.os.Build.SUPPORTED_ABIS.toList()

    @Test
    fun githubReleaseResolvesToAnApkAndTheLinkServesAZip() = runBlocking<Unit> {
        val latest = UpdateChecker.resolveLatest(SourceRef(UpdateKind.GITHUB, "wdchocopie/split-sideloader"), abis)
        assertNotNull(latest)
        assertTrue(latest!!.url, latest.url.startsWith("https://") && latest.fileName.endsWith(".apk"))
        val probe = LinkProbe.probe(latest.url, Http.USER_AGENT)
        assertTrue("not a zip from ${probe.finalUrl}", probe.isFile)
        assertTrue(probe.finalUrl.startsWith("https://"))
        assertTrue((probe.size ?: 0) > 1_000_000)
    }

    @Test
    fun izzyOnDroidResolvesWithAVersionCode() = runBlocking<Unit> {
        val latest = UpdateChecker.resolveLatest(SourceRef(UpdateKind.IZZYONDROID, "com.machiav3lli.backup"), abis)
        assertNotNull(latest)
        assertTrue((latest!!.versionCode ?: 0) > 0)
        assertEquals("com.machiav3lli.backup", latest.packageName)
    }

    @Test
    fun codebergReleaseHasAnApk() = runBlocking<Unit> {
        val latest = UpdateChecker.resolveLatest(SourceRef(UpdateKind.FORGEJO, "codeberg.org/Freeyourgadget/Gadgetbridge"), abis)
        assertNotNull(latest)
        // Gadgetbridge's release also carries Bangle.js Gadgetbridge, a different app.
        assertTrue(latest!!.fileName, latest.fileName.startsWith("gadgetbridge-") && latest.fileName.endsWith(".apk"))
        assertTrue(latest.url, !latest.url.contains("null"))
    }

    @Test
    fun gitlabReleaseIsReadWithoutNullLinks() = runBlocking<Unit> {
        // The newest AuroraStore release may carry no APK at all; what matters is that nothing
        // reads as the text "null".
        val latest = UpdateChecker.resolveLatest(SourceRef(UpdateKind.GITLAB, "gitlab.com/AuroraOSS/AuroraStore"), abis)
        latest?.let { assertTrue(it.url, !it.url.contains("null") && it.url.startsWith("https://gitlab.com/")) }
    }

    @Test
    fun fdroidSearchFindsAnAppByName() = runBlocking<Unit> {
        val hits = UpdateChecker.searchFDroid("Termux")
        assertTrue(hits.joinToString { it.packageName }, hits.any { it.packageName == "com.termux" })
        assertTrue(hits.none { it.summary == "null" })
    }
}
