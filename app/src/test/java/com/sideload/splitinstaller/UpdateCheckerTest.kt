package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.update.ReleaseAsset
import com.sideload.splitinstaller.core.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two APIs the app reads on its own. Bodies are trimmed copies of real responses, so a
 * change in how they are shaped shows up here rather than as a silent "up to date".
 */
class UpdateCheckerTest {

    @Test
    fun `fdroid prefers the suggested build over the highest one`() {
        val body = """
            {
              "packageName": "org.fdroid.fdroid",
              "suggestedVersionCode": 1021050,
              "packages": [
                { "versionName": "1.22-alpha0", "versionCode": 1022000 },
                { "versionName": "1.21.1", "versionCode": 1021050 },
                { "versionName": "1.21", "versionCode": 1021000 }
              ]
            }
        """.trimIndent()
        val version = UpdateChecker.parseFDroid(body)!!
        assertEquals(1021050L, version.versionCode)
        assertEquals("1.21.1", version.versionName)
    }

    @Test
    fun `fdroid falls back to the highest versionCode when nothing is suggested`() {
        val body = """
            {
              "packages": [
                { "versionName": "0.9", "versionCode": 90 },
                { "versionName": "1.4", "versionCode": 140 },
                { "versionCode": 0 }
              ]
            }
        """.trimIndent()
        val version = UpdateChecker.parseFDroid(body)!!
        assertEquals(140L, version.versionCode)
        assertEquals("1.4", version.versionName)
    }

    @Test
    fun `fdroid with no versions is not an answer`() {
        assertNull(UpdateChecker.parseFDroid("""{"packageName":"a.b.c","packages":[]}"""))
        assertNull(UpdateChecker.parseFDroid("""{"error":"not found"}"""))
    }

    @Test
    fun `github release keeps only installable assets`() {
        val body = """
            {
              "tag_name": "v1.8.2",
              "html_url": "https://github.com/owner/repo/releases/tag/v1.8.2",
              "assets": [
                { "name": "app-arm64-v8a-release.apk", "browser_download_url": "https://x/1.apk", "size": 42 },
                { "name": "app-release.apks", "browser_download_url": "https://x/2.apks", "size": 43 },
                { "name": "sources.zip", "browser_download_url": "https://x/3.zip", "size": 44 },
                { "name": "checksums.txt", "browser_download_url": "https://x/4.txt", "size": 45 }
              ]
            }
        """.trimIndent()
        val release = UpdateChecker.parseGitHub(body)!!
        assertEquals("v1.8.2", release.version)
        assertEquals("https://github.com/owner/repo/releases/tag/v1.8.2", release.pageUrl)
        assertEquals(listOf("app-arm64-v8a-release.apk", "app-release.apks"), release.assets.map { it.name })
    }

    @Test
    fun `github falls back to the release name when there is no tag`() {
        val release = UpdateChecker.parseGitHub("""{"name":"2026.04.1","assets":[]}""")!!
        assertEquals("2026.04.1", release.version)
        assertTrue(release.assets.isEmpty())
        assertNull(UpdateChecker.parseGitHub("""{"message":"Not Found"}"""))
    }

    @Test
    fun `asset for this device wins over universal, and release over debug`() {
        val assets = listOf(
            ReleaseAsset("app-universal-release.apk", "https://x/u.apk", 90),
            ReleaseAsset("app-armeabi-v7a-release.apk", "https://x/v7.apk", 30),
            ReleaseAsset("app-arm64-v8a-release.apk", "https://x/v8.apk", 40),
            ReleaseAsset("app-arm64-v8a-debug.apk", "https://x/v8d.apk", 41),
        )
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        assertEquals("app-arm64-v8a-release.apk", UpdateChecker.pickAsset(assets, abis)!!.name)
        // A device the release has no slice for still gets the universal build.
        assertEquals("app-universal-release.apk", UpdateChecker.pickAsset(assets, listOf("x86_64"))!!.name)
        assertNull(UpdateChecker.pickAsset(emptyList(), abis))
    }

    @Test
    fun `any github link reduces to owner slash repo`() {
        assertEquals("owner/repo", UpdateChecker.githubRepo("owner/repo"))
        assertEquals("owner/repo", UpdateChecker.githubRepo("  https://github.com/owner/repo/releases/latest  "))
        assertEquals("owner/repo", UpdateChecker.githubRepo("github.com/owner/repo.git"))
        assertEquals("owner/repo", UpdateChecker.githubRepo("https://github.com/owner/repo/"))
        assertNull(UpdateChecker.githubRepo("owner"))
        assertNull(UpdateChecker.githubRepo("https://gitlab.com/owner"))
        assertNull(UpdateChecker.githubRepo("owner/repo;rm -rf"))
    }
}
