package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.ota.Ota
import com.sideload.splitinstaller.core.ota.OtaChannel
import com.sideload.splitinstaller.core.ota.OtaChannelKind
import com.sideload.splitinstaller.core.ota.OtaRelease
import com.sideload.splitinstaller.core.ota.OtaState
import com.sideload.splitinstaller.core.ota.OtaStatus
import com.sideload.splitinstaller.core.ota.OtaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `the channel release builds ship with is a manifest`() {
        val channel = OtaChannel.parse("https://github.com/wdchocopie/split-sideloader/releases/latest/download/ota.json")
        assertEquals(OtaChannelKind.MANIFEST, channel.kind)
        // Debug builds ship with none: they are a different package that no release can update.
        val expected = if (BuildConfig.DEBUG) OtaChannelKind.OFF else OtaChannelKind.MANIFEST
        assertEquals(expected, OtaChannel.parse(BuildConfig.OTA_CHANNEL).kind)
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

class OtaStoreMergeTest {

    private val v2 = OtaRelease(versionName = "2.0", versionCode = 99, url = "https://x/2.apk", fileName = "2.apk")
    private val v3 = OtaRelease(versionName = "3.0", versionCode = 100, url = "https://x/3.apk", fileName = "3.apk")

    @Test
    fun `a failed check keeps a build that is waiting to be installed`() {
        val ready = OtaStatus(OtaState.READY, v2, checkedAt = 1, fileUri = "content://downloads/9")
        val merged = OtaStore.merge(ready, OtaStatus(OtaState.ERROR, checkedAt = 2, message = "offline"))
        assertEquals(OtaState.READY, merged.state)
        assertEquals("content://downloads/9", merged.fileUri)
        assertEquals(v2, merged.release)
        assertEquals(2L, merged.checkedAt)
        assertEquals("offline", merged.message)
    }

    @Test
    fun `finding the same build again keeps it ready`() {
        val ready = OtaStatus(
            OtaState.READY, v2, checkedAt = 1, fileUri = "content://downloads/9", fileSourceUrl = v2.url,
        )
        val merged = OtaStore.merge(ready, OtaStatus(OtaState.UPDATE, v2, checkedAt = 2))
        assertEquals(OtaState.READY, merged.state)
        assertEquals("content://downloads/9", merged.fileUri)
        assertEquals(v2.url, merged.fileSourceUrl)
    }

    @Test
    fun `a newer build replaces the old file`() {
        val ready = OtaStatus(
            OtaState.READY, v2, checkedAt = 1, fileUri = "content://downloads/9", fileSourceUrl = v2.url,
        )
        val merged = OtaStore.merge(ready, OtaStatus(OtaState.UPDATE, v3, checkedAt = 2))
        assertEquals(OtaState.UPDATE, merged.state)
        assertNull(merged.fileUri)
        assertNull(merged.fileSourceUrl)
        assertEquals(v3, merged.release)
    }

    @Test
    fun `device facts survive every check`() {
        val current = OtaStatus(
            OtaState.ERROR, v2, attemptedVersion = 99, attemptFailures = 1,
            refusedKey = v2.identity, readyNotifiedKey = v2.identity,
        )
        val merged = OtaStore.merge(current, OtaStatus(OtaState.UPDATE, v3, checkedAt = 5))
        assertEquals(99L, merged.attemptedVersion)
        assertEquals(1, merged.attemptFailures)
        assertEquals(v2.identity, merged.refusedKey)
        assertEquals(v2.identity, merged.readyNotifiedKey)
    }

    @Test
    fun `with nothing pending, a failed check is recorded as failed`() {
        val merged = OtaStore.merge(OtaStatus(OtaState.UP_TO_DATE, v2), OtaStatus(OtaState.ERROR, message = "HTTP 500"))
        assertEquals(OtaState.ERROR, merged.state)
        assertEquals("HTTP 500", merged.message)
    }
}

class OtaIdentityTest {

    @Test
    fun `a fixed build under the same file name is a different build`() {
        val first = OtaRelease(versionName = "2.0", versionCode = 9, url = "https://x/a.apk", fileName = "a.apk", sha256 = "aa")
        val fixed = first.copy(sha256 = "bb")
        assertNotEquals(first.identity, fixed.identity)
        assertEquals(first.identity, first.copy(notes = "changed notes").identity)
    }

    @Test
    fun `retrying stops only after two failures`() {
        assertEquals(false, OtaStatus(attemptFailures = 1).stoppedRetrying)
        assertEquals(true, OtaStatus(attemptFailures = 2).stoppedRetrying)
    }

    @Test
    fun `only a known build still to install is pending`() {
        val r = OtaRelease(versionName = "2.0", versionCode = 9, url = "https://x/a.apk", fileName = "a.apk")
        assertEquals(true, OtaStatus(OtaState.READY, r).pending)
        assertEquals(true, OtaStatus(OtaState.UPDATE, r).pending)
        assertEquals(false, OtaStatus(OtaState.UP_TO_DATE, r).pending)
        assertEquals(false, OtaStatus(OtaState.UPDATE, null).pending)
    }
}
