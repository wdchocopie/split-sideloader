package com.sideload.splitinstaller

import com.sideload.splitinstaller.core.update.IzzyIndex
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
        assertEquals(listOf("com.termux.api", "com.termux"), hits.map { it.source.value })
        assertEquals(SourceRef(UpdateKind.FDROID, "com.termux"), hits[1].source)
        assertEquals("https://f-droid.org/packages/com.termux", hits[1].pageUrl)
        assertNull(hits[1].summary)
    }

    @Test
    fun `github search gives repositories to resolve releases from`() {
        val body = """{"total_count":2,"items":[
          {"name":"NewPipe","full_name":"TeamNewPipe/NewPipe","description":"A libre lightweight streaming front-end for Android.",
           "stargazers_count":38512,"html_url":"https://github.com/TeamNewPipe/NewPipe"},
          {"name":"tiny","full_name":"someone/tiny","description":null,"stargazers_count":7},
          {"name":"broken","full_name":null}]}"""
        val hits = UpdateChecker.parseGitHubSearch(body)
        assertEquals(2, hits.size)
        assertEquals(SourceRef(UpdateKind.GITHUB, "TeamNewPipe/NewPipe"), hits[0].source)
        assertEquals("https://github.com/TeamNewPipe/NewPipe", hits[0].pageUrl)
        assertEquals("TeamNewPipe/NewPipe · ★ 38k", hits[0].detail)
        assertNull(hits[1].summary)
        assertEquals("someone/tiny · ★ 7", hits[1].detail)
    }

    @Test
    fun `codeberg search keeps the host and leaves forks out`() {
        val body = """{"ok":true,"data":[
          {"name":"Gadgetbridge","full_name":"Freeyourgadget/Gadgetbridge","description":"Use your gadget without the vendor app",
           "stars_count":1520,"fork":false},
          {"name":"Gadgetbridge","full_name":"someone/Gadgetbridge","fork":true,"stars_count":2}]}"""
        val hits = UpdateChecker.parseForgejoSearch(body, "codeberg.org")
        assertEquals(1, hits.size)
        assertEquals(SourceRef(UpdateKind.FORGEJO, "codeberg.org/Freeyourgadget/Gadgetbridge"), hits[0].source)
        assertEquals("https://codeberg.org/Freeyourgadget/Gadgetbridge", hits[0].pageUrl)
        assertEquals("Freeyourgadget/Gadgetbridge · ★ 1.5k", hits[0].detail)
    }

    @Test
    fun `izzyondroid's catalogue is searched on the phone, best name first`() {
        val apps = listOf(
            IzzyIndex.Entry("com.machiav3lli.backup", "Neo Backup", "Backup and restore your apps"),
            IzzyIndex.Entry("org.example.neo", "Neo Launcher Extra", null),
            IzzyIndex.Entry("com.example.files", "Files", "Manage backups, photos and more"),
            IzzyIndex.Entry("com.example.backup.tool", "Saver", null),
        )
        assertEquals(listOf("Neo Backup"), IzzyIndex.rank(apps, "neo backup").map { it.name })
        // Name first, then package, then summary.
        assertEquals(listOf("com.machiav3lli.backup", "com.example.backup.tool", "com.example.files"),
            IzzyIndex.rank(apps, "backup").map { it.packageName })
        assertTrue(IzzyIndex.rank(apps, "   ").isEmpty())
        assertTrue(IzzyIndex.rank(apps, "nothing like this").isEmpty())
    }

    @Test
    fun `the cached catalogue line survives tabs and line breaks in a summary`() {
        val entry = IzzyIndex.Entry("com.example", "Example", "Line one\nline\ttwo")
        val back = IzzyIndex.fromTsv(IzzyIndex.toTsv(entry))!!
        assertEquals("com.example", back.packageName)
        assertEquals("Line one line two", back.summary)
        assertNull(IzzyIndex.fromTsv(IzzyIndex.toTsv(IzzyIndex.Entry("com.example", "Example", null)))!!.summary)
        assertNull(IzzyIndex.fromTsv(""))
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
