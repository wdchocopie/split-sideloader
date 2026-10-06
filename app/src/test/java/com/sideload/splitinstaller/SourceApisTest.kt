package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.update.ReleaseAsset
import com.sideload.splitinstaller.core.update.SourceRef
import com.sideload.splitinstaller.core.update.UpdateChecker
import com.sideload.splitinstaller.core.update.UpdateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sources added in 1.5.5: IzzyOnDroid, GitLab, Forgejo (Codeberg), the F-Droid search, and
 * recognising any of them from a link. Bodies are trimmed copies of what the APIs answered on
 * 2026-10-06.
 */
class SourceApisTest {

    @Test
    fun `izzyondroid writes version codes as strings and still parses`() {
        val body = """{"packageName":"com.example.app","suggestedVersionCode":"780",
            "packages":[{"versionName":"0.7.8","versionCode":"780"},{"versionName":"0.7.7","versionCode":"770"}]}"""
        val version = UpdateChecker.parseFDroid(body)!!
        assertEquals(780L, version.versionCode)
        assertEquals("0.7.8", version.versionName)
    }

    @Test
    fun `the file named after the project beats a bigger flavour`() {
        val assets = listOf(
            ReleaseAsset("banglejs-0.94.0.apk", "https://x/b.apk", 20_310_133),
            ReleaseAsset("gadgetbridge-0.94.0.apk", "https://x/g.apk", 20_301_941),
        )
        assertEquals("gadgetbridge-0.94.0.apk", UpdateChecker.pickAsset(assets, listOf("arm64-v8a"), nameHint = "Gadgetbridge")!!.name)
        // Without a hint, nothing has changed: the bigger file.
        assertEquals("banglejs-0.94.0.apk", UpdateChecker.pickAsset(assets, listOf("arm64-v8a"))!!.name)
    }

    @Test
    fun `an installed app keeps its own abi build of a version`() {
        val body = """{"suggestedVersionCode":1570020,"packages":[
            {"versionName":"157.0","versionCode":1570020},{"versionName":"157.0","versionCode":1570010},
            {"versionName":"157.0","versionCode":1570000},{"versionName":"156.0","versionCode":1560020}]}"""
        assertEquals(1570020L, UpdateChecker.parseFDroid(body)!!.versionCode)
        assertEquals(1570000L, UpdateChecker.parseFDroid(body, installedVersionCode = 1560000)!!.versionCode)
        assertEquals(1570010L, UpdateChecker.parseFDroid(body, installedVersionCode = 1560010)!!.versionCode)
    }

    @Test
    fun `codeberg answers in github's shape`() {
        val body = """{"tag_name":"v2.3.0","name":"2.3.0","html_url":"https://codeberg.org/o/r/releases/tag/v2.3.0",
            "draft":false,"prerelease":false,"assets":[
              {"name":"app-release.apk","size":5000,"browser_download_url":"https://codeberg.org/o/r/releases/download/v2.3.0/app-release.apk"},
              {"name":"checksums.txt","size":90,"browser_download_url":"https://codeberg.org/o/r/releases/download/v2.3.0/checksums.txt"}]}"""
        val release = UpdateChecker.parseGitHub(body)!!
        assertEquals("v2.3.0", release.version)
        assertEquals(listOf("app-release.apk"), release.assets.map { it.name })
    }

    @Test
    fun `a json null is never taken for a link`() {
        val body = """{"tag_name":"v1","html_url":null,"assets":[{"name":"a.apk","size":1,"browser_download_url":null}]}"""
        val release = UpdateChecker.parseGitHub(body)!!
        assertNull(release.pageUrl)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `gitlab takes apk links and skips signatures and upcoming releases`() {
        val body = """[
          {"tag_name":"v9","upcoming_release":true,"assets":{"links":[{"name":"next.apk","url":"https://x/next.apk"}]}},
          {"tag_name":"v8.1","upcoming_release":false,"_links":{"self":"https://gitlab.com/g/p/-/releases/v8.1"},
           "description_html":null,
           "assets":{"links":[
             {"name":"App-8.1.apk","url":"https://gitlab.com/g/p/-/package_files/286373490/download","direct_asset_url":null},
             {"name":"App-8.1.apk.asc","url":"https://gitlab.com/g/p/-/package_files/286373491/download"}]}}
        ]"""
        val release = UpdateChecker.parseGitLab(body, "gitlab.com")!!
        assertEquals("v8.1", release.version)
        assertEquals("https://gitlab.com/g/p/-/releases/v8.1", release.pageUrl)
        assertEquals(1, release.assets.size)
        assertEquals("App-8.1.apk", release.assets[0].name)
        assertEquals("https://gitlab.com/g/p/-/package_files/286373490/download", release.assets[0].url)
    }

    @Test
    fun `gitlab apks mentioned only in the description are found`() {
        val body = """[{"tag_name":"4.6.1","assets":{"links":[]},
          "description_html":"<p><a href=\"/-/project/6922885/uploads/ab12/AuroraStore-4.6.1.apk\">AuroraStore-4.6.1.apk</a> and <a href=\"https://example.org/notes\">notes</a></p>"}]"""
        val release = UpdateChecker.parseGitLab(body, "gitlab.com")!!
        assertEquals(1, release.assets.size)
        assertEquals("AuroraStore-4.6.1.apk", release.assets[0].name)
        assertEquals("https://gitlab.com/-/project/6922885/uploads/ab12/AuroraStore-4.6.1.apk", release.assets[0].url)
    }

    @Test
    fun `gitlab's newest release that is out names the one to read`() {
        val list = """[{"tag_name":"5.0","upcoming_release":true},{"tag_name":"4.8.3"},{"tag_name":"4.8.1"}]"""
        assertEquals("4.8.3", UpdateChecker.gitlabLatestTag(list))
        assertNull(UpdateChecker.gitlabLatestTag("[]"))
    }

    @Test
    fun `fdroid search gives package names from the page urls`() {
        val body = """{"apps":[
          {"name":"Termux:API","summary":"Access Android functions","icon":"https://f-droid.org/repo/icons/a.png","url":"https://f-droid.org/en/packages/com.termux.api"},
          {"name":"Not an app","url":"https://f-droid.org/en/about"},
          {"name":"Termux","summary":null,"url":"https://f-droid.org/en/packages/com.termux/"}]}"""
        val hits = UpdateChecker.parseFDroidSearch(body)
        assertEquals(listOf("com.termux.api", "com.termux"), hits.map { it.packageName })
        assertNull(hits[1].summary)
    }

    @Test
    fun `links are recognised by host before the path is read`() {
        assertEquals(SourceRef(UpdateKind.FDROID, "org.fdroid.fdroid"), UpdateChecker.sourceFromLink("https://f-droid.org/en/packages/org.fdroid.fdroid/"))
        assertEquals(SourceRef(UpdateKind.IZZYONDROID, "com.example.app"), UpdateChecker.sourceFromLink("https://apt.izzysoft.de/fdroid/index/apk/com.example.app"))
        assertEquals(SourceRef(UpdateKind.GITHUB, "owner/repo"), UpdateChecker.sourceFromLink("https://github.com/owner/repo/releases/latest"))
        assertEquals(SourceRef(UpdateKind.FORGEJO, "codeberg.org/owner/repo"), UpdateChecker.sourceFromLink("codeberg.org/owner/repo"))
        assertEquals(SourceRef(UpdateKind.GITLAB, "gitlab.com/group/sub/project"), UpdateChecker.sourceFromLink("https://gitlab.com/group/sub/project/-/releases"))
        assertEquals(SourceRef(UpdateKind.GITHUB, "owner/repo"), UpdateChecker.sourceFromLink("http://www.github.com/owner/repo?tab=readme#install"))
        assertNull(UpdateChecker.sourceFromLink("https://example.org/files/app.apk"))
        assertNull(UpdateChecker.sourceFromLink("https://f-droid.org/en/about"))
    }

    @Test
    fun `a codeberg or gitlab link is never pinned as a github repo`() {
        assertNull(UpdateChecker.releaseSource(UpdateKind.GITHUB, "codeberg.org/owner/repo"))
        assertNull(UpdateChecker.releaseSource(UpdateKind.GITHUB, "https://gitlab.com/a/b"))
        assertEquals("owner/repo", UpdateChecker.releaseSource(UpdateKind.GITHUB, "owner/repo"))
    }

    @Test
    fun `short forms get the default host, full forms keep their own`() {
        assertEquals("gitlab.com/group/project", UpdateChecker.releaseSource(UpdateKind.GITLAB, "group/project"))
        assertEquals("git.example.org/team/app", UpdateChecker.releaseSource(UpdateKind.GITLAB, "https://git.example.org/team/app/-/releases"))
        assertEquals("codeberg.org/owner/repo", UpdateChecker.releaseSource(UpdateKind.FORGEJO, "owner/repo"))
        assertEquals("git.example.org/owner/repo", UpdateChecker.releaseSource(UpdateKind.FORGEJO, "git.example.org/owner/repo"))
        assertNull(UpdateChecker.releaseSource(UpdateKind.FORGEJO, "just-a-word"))
    }
}
