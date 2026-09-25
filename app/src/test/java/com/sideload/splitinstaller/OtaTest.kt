package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.ota.Ota
import com.sideload.splitinstaller.core.ota.OtaChannel
import com.sideload.splitinstaller.core.ota.OtaChannelKind
import com.sideload.splitinstaller.core.ota.OtaRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtaTest {

    private fun release(code: Long = 0, name: String? = null) =
        OtaRelease(versionName = name, versionCode = code, url = "https://x/a.apk", fileName = "a.apk")

    @Test
    fun `a repo is a release feed, an https url is a manifest`() {
        assertEquals(OtaChannelKind.GITHUB, OtaChannel.parse("owner/repo").kind)
        assertEquals("owner/repo", OtaChannel.parse("https://github.com/owner/repo/releases").value)
        assertEquals("owner/repo", OtaChannel.parse(" github.com/owner/repo ").value)
        assertEquals(OtaChannelKind.MANIFEST, OtaChannel.parse("https://example.org/ota.json").kind)
        assertEquals(
            "https://github.com/owner/repo/releases",
            OtaChannel.parse("owner/repo").pageUrl,
        )
    }

    @Test
    fun `anything that is not clearly a channel turns it off`() {
        assertEquals(OtaChannelKind.OFF, OtaChannel.parse("").kind)
        assertEquals(OtaChannelKind.OFF, OtaChannel.parse(null).kind)
        assertEquals(OtaChannelKind.OFF, OtaChannel.parse("   ").kind)
        assertEquals(OtaChannelKind.OFF, OtaChannel.parse("nonsense").kind)
        // Plain http would let anyone on the path choose this app's next version.
        assertEquals(OtaChannelKind.OFF, OtaChannel.parse("http://example.org/ota.json").kind)
    }

    @Test
    fun `reads the manifest the gradle task writes`() {
        val body = """
            {
              "versionCode": 12,
              "versionName": "1.9.0",
              "url": "https://example.org/r/SplitSideloader-1.9.0.apk",
              "fileName": "SplitSideloader-1.9.0.apk",
              "sha256": "A3196FD24E21E129C2CA2B861D6F3371ECE65E7ED7FCEECB9C6087F062ABA4A7",
              "size": 11363719,
              "minSdk": 26,
              "notes": "fixes the thing"
            }
        """.trimIndent()
        val release = Ota.parseManifest(body)!!
        assertEquals(12L, release.versionCode)
        assertEquals("1.9.0", release.versionName)
        assertEquals("SplitSideloader-1.9.0.apk", release.fileName)
        assertEquals(11363719L, release.size)
        assertEquals(26, release.minSdk)
        assertEquals("fixes the thing", release.notes)
        // Lowercased, so it can be compared with what the app computes.
        assertEquals("a3196fd24e21e129c2ca2b861d6f3371ece65e7ed7fceecb9c6087f062aba4a7", release.sha256)
    }

    @Test
    fun `a manifest without an https url is not an answer`() {
        assertNull(Ota.parseManifest("""{"versionCode":12,"versionName":"1.9.0"}"""))
        assertNull(Ota.parseManifest("""{"versionCode":12,"url":"http://example.org/a.apk"}"""))
        // A url with no version at all says nothing about whether it is an update.
        assertNull(Ota.parseManifest("""{"url":"https://example.org/a.apk"}"""))
    }

    @Test
    fun `the file name falls back to the last part of the url`() {
        val release = Ota.parseManifest("""{"versionCode":9,"url":"https://example.org/r/app-release.apk"}""")!!
        assertEquals("app-release.apk", release.fileName)
    }

    @Test
    fun `newer means a higher versionCode, or a higher name when there is no code`() {
        assertEquals(true, Ota.isNewer(release(code = BuildConfig.VERSION_CODE + 1L)))
        assertEquals(false, Ota.isNewer(release(code = BuildConfig.VERSION_CODE.toLong())))
        assertEquals(false, Ota.isNewer(release(code = BuildConfig.VERSION_CODE - 1L)))
        // A versionCode wins over the name, which is only a label.
        assertEquals(false, Ota.isNewer(release(code = 1, name = "99.0.0")))
        assertEquals(false, Ota.isNewer(release(name = BuildConfig.VERSION_NAME)))
        assertEquals(true, Ota.isNewer(release(name = "999.0.0")))
    }

    @Test
    fun `an unreadable version is reported as unknown, not as up to date`() {
        assertNull(Ota.isNewer(release(name = "nightly")))
        assertNull(Ota.isNewer(release()))
    }
}
