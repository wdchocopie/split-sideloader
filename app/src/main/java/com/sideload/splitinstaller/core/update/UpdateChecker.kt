package com.sideload.splitinstaller.core.update

import android.content.Context
import android.os.Build
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.verify.InstallVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** What f-droid.org says about one package. */
data class FDroidVersion(val versionName: String?, val versionCode: Long)

/** One APK-ish file attached to a release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** A release on GitHub, GitLab or Forgejo, reduced to what an update needs. */
data class GitHubRelease(val version: String?, val pageUrl: String?, val assets: List<ReleaseAsset>)

/** A source a link or a typed name points at: a kind and its pin value. */
data class SourceRef(val kind: UpdateKind, val value: String)

/** The newest build a source offers, for an app that may not be installed yet. */
data class LatestBuild(
    val versionName: String?,
    val versionCode: Long?,
    val url: String,
    val fileName: String,
    val size: Long,
    val pageUrl: String?,
    /** Known only for package repositories; a release does not say what it contains. */
    val packageName: String?,
)

/** One app found by name in the F-Droid catalogue. */
data class FDroidHit(val name: String, val summary: String?, val packageName: String, val iconUrl: String?)

/**
 * Asks a package's pinned source what the newest version is.
 *
 * Only sources that publish this for client apps are queried: the package APIs of f-droid.org
 * and IzzyOnDroid, and the release APIs of GitHub, GitLab and Forgejo (Codeberg). Each is asked
 * at most once a day per pinned app, a few kilobytes of JSON. A pinned web page is reported as
 * [UpdateState.MANUAL] — APKMirror and APKPure have no version API, and their robots policy puts
 * downloads off limits to automation, so the app opens the page and you take it from there.
 */
object UpdateChecker {

    private val FDROID = Repo(
        api = "https://f-droid.org/api/v1/packages/",
        files = "https://f-droid.org/repo/",
        page = "https://f-droid.org/packages/",
    )
    private val IZZY = Repo(
        api = "https://apt.izzysoft.de/fdroid/api/v1/packages/",
        files = "https://apt.izzysoft.de/fdroid/repo/",
        page = "https://apt.izzysoft.de/fdroid/index/apk/",
    )
    private const val GITHUB_API = "https://api.github.com/repos/"
    private const val FDROID_SEARCH = "https://search.f-droid.org/api/search_apps?q="

    private val APK_EXTENSIONS = listOf(".apk", ".apks", ".apkm", ".xapk")
    private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** An F-Droid-style repository: the package API, the folder of APKs, the app pages. */
    private data class Repo(val api: String, val files: String, val page: String)

    private fun repoOf(kind: UpdateKind): Repo? = when (kind) {
        UpdateKind.FDROID -> FDROID
        UpdateKind.IZZYONDROID -> IZZY
        else -> null
    }

    suspend fun check(context: Context, pin: UpdatePin): UpdateResult = withContext(Dispatchers.IO) {
        val installed = InstallVerifier.installedVersion(context, pin.packageName)
        val label = pin.label ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pin.packageName, 0)).toString()
        }.getOrNull()
        val base = UpdateResult(
            packageName = pin.packageName,
            kind = pin.kind,
            state = UpdateState.UNKNOWN,
            label = label,
            installedVersionName = installed?.second,
            installedVersionCode = installed?.first ?: 0,
        )
        if (installed == null) {
            return@withContext base.copy(state = UpdateState.ERROR, message = "not installed")
        }

        try {
            when (pin.kind) {
                UpdateKind.FDROID, UpdateKind.IZZYONDROID -> checkRepo(base, pin, repoOf(pin.kind)!!)
                UpdateKind.GITHUB, UpdateKind.GITLAB, UpdateKind.FORGEJO -> checkRelease(base, pin)
                UpdateKind.WEB -> base.copy(state = UpdateState.MANUAL, pageUrl = pin.value)
            }
        } catch (e: Http.HttpError) {
            base.copy(state = UpdateState.ERROR, message = "HTTP ${e.code}", pageUrl = pageFor(pin))
        } catch (t: Throwable) {
            base.copy(state = UpdateState.ERROR, message = t.message ?: "check failed", pageUrl = pageFor(pin))
        }
    }

    private fun pageFor(pin: UpdatePin): String? = when (pin.kind) {
        UpdateKind.FDROID, UpdateKind.IZZYONDROID -> repoOf(pin.kind)!!.page + pin.value
        UpdateKind.GITHUB -> "https://github.com/" + pin.value + "/releases"
        UpdateKind.GITLAB -> "https://" + pin.value + "/-/releases"
        UpdateKind.FORGEJO -> "https://" + pin.value + "/releases"
        UpdateKind.WEB -> pin.value
    }

    // ---- F-Droid and IzzyOnDroid ----------------------------------------------------

    private fun checkRepo(base: UpdateResult, pin: UpdatePin, repo: Repo): UpdateResult {
        val body = Http.getString(repo.api + pin.value, accept = "application/json")
        val version = parseFDroid(body, base.installedVersionCode)
            ?: return base.copy(state = UpdateState.ERROR, message = "no versions listed", pageUrl = pageFor(pin))

        val newer = version.versionCode > base.installedVersionCode
        val file = pin.value + "_" + version.versionCode + ".apk"
        return base.copy(
            state = if (newer) UpdateState.UPDATE else UpdateState.UP_TO_DATE,
            availableVersionName = version.versionName,
            availableVersionCode = version.versionCode,
            downloadUrl = repo.files + file,
            fileName = file,
            pageUrl = repo.page + pin.value,
        )
    }

    /**
     * The suggested (stable) build, falling back to the highest versionCode listed. IzzyOnDroid
     * writes the codes as strings ("780"); optLong reads those as numbers too.
     *
     * An app built once per ABI lists the same version several times, the ABI encoded in the
     * last digits of the code (Fennec: …20 arm64, …10 x86_64, …00 armeabi-v7a), and the API does
     * not say which is which. For an installed app the build ending like [installedVersionCode]
     * is the one for this phone; for a new install the suggested one is taken, and the install's
     * own ABI check stops it if it does not fit.
     */
    fun parseFDroid(body: String, installedVersionCode: Long? = null): FDroidVersion? {
        val json = JSONObject(body)
        val packages: JSONArray = json.optJSONArray("packages") ?: return null
        val suggested = json.optLong("suggestedVersionCode", 0L)
        val all = ArrayList<FDroidVersion>()
        for (i in 0 until packages.length()) {
            val o = packages.optJSONObject(i) ?: continue
            val code = o.optLong("versionCode", 0L)
            if (code > 0) all += FDroidVersion(o.str("versionName"), code)
        }
        val chosen = all.firstOrNull { it.versionCode == suggested } ?: all.maxByOrNull { it.versionCode } ?: return null
        if (installedVersionCode == null || installedVersionCode <= 0) return chosen
        val variants = all.filter { it.versionName != null && it.versionName == chosen.versionName }
        if (variants.size < 2) return chosen
        return variants.firstOrNull { it.versionCode % 100 == installedVersionCode % 100 } ?: chosen
    }

    // ---- releases: GitHub, GitLab, Forgejo ------------------------------------------

    private fun checkRelease(base: UpdateResult, pin: UpdatePin): UpdateResult {
        val release = latestRelease(pin.kind, pin.value)
            ?: return base.copy(state = UpdateState.ERROR, message = "no release found", pageUrl = pageFor(pin))
        val asset = pickAsset(release.assets, Build.SUPPORTED_ABIS.toList(), nameHint = pin.value.substringAfterLast('/'))

        val newer = VersionCompare.isNewer(release.version, base.installedVersionName)
        return base.copy(
            state = when (newer) {
                true -> UpdateState.UPDATE
                false -> UpdateState.UP_TO_DATE
                null -> UpdateState.UNKNOWN
            },
            availableVersionName = release.version,
            downloadUrl = asset?.url,
            fileName = asset?.name,
            size = asset?.size ?: 0,
            pageUrl = release.pageUrl ?: pageFor(pin),
            message = if (asset == null) "release has no APK asset" else null,
        )
    }

    /** The newest published release of a GitHub, GitLab or Forgejo project. */
    private fun latestRelease(kind: UpdateKind, value: String): GitHubRelease? {
        return when (kind) {
            UpdateKind.GITHUB -> parseGitHub(
                Http.getString(GITHUB_API + value + "/releases/latest", accept = "application/vnd.github+json")
            )
            UpdateKind.FORGEJO -> {
                val (host, repo) = splitHost(value) ?: return null
                // Forgejo and Gitea answer in GitHub's shape.
                parseGitHub(Http.getString("https://$host/api/v1/repos/$repo/releases/latest", accept = "application/json"))
            }
            UpdateKind.GITLAB -> {
                val (host, project) = splitHost(value) ?: return null
                val api = "https://$host/api/v4/projects/" + URLEncoder.encode(project, "UTF-8") + "/releases"
                // The list names the newest release; only the release's own page renders its
                // description, where many projects keep their APKs.
                val tag = gitlabLatestTag(Http.getString("$api?per_page=5", accept = "application/json"))
                    ?: return null
                val one = Http.getString(
                    "$api/" + URLEncoder.encode(tag, "UTF-8") + "?include_html_description=true",
                    accept = "application/json",
                )
                parseGitLab("[$one]", host)
            }
            else -> null
        }
    }

    fun parseGitHub(body: String): GitHubRelease? {
        val json = JSONObject(body)
        if (!json.has("tag_name") && !json.has("name")) return null
        val assets = ArrayList<ReleaseAsset>()
        json.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.str("name") ?: continue
                val url = o.str("browser_download_url") ?: continue
                if (!isApkName(name)) continue
                assets += ReleaseAsset(name, url, o.optLong("size", 0L))
            }
        }
        return GitHubRelease(
            version = json.str("tag_name") ?: json.str("name"),
            pageUrl = json.str("html_url"),
            assets = assets,
        )
    }

    /**
     * GitLab's release list, newest first. APKs come as release links, and in many projects only
     * as uploads mentioned in the description; the rendered description carries those as links
     * that work, of the form /-/project/<id>/uploads/<hash>/<file>.apk.
     */
    fun parseGitLab(body: String, host: String): GitHubRelease? {
        val list = JSONArray(body)
        for (i in 0 until list.length()) {
            val json = list.optJSONObject(i) ?: continue
            if (json.optBoolean("upcoming_release", false)) continue
            val assets = ArrayList<ReleaseAsset>()
            json.optJSONObject("assets")?.optJSONArray("links")?.let { links ->
                for (j in 0 until links.length()) {
                    val o = links.optJSONObject(j) ?: continue
                    val url = o.str("direct_asset_url") ?: o.str("url") ?: continue
                    val name = o.str("name") ?: url.substringBefore('?').substringAfterLast('/')
                    // A link's name may lack the extension its file has, or the other way round.
                    if (!isApkName(name) && !isApkName(url.substringBefore('?'))) continue
                    val file = if (isApkName(name)) name else url.substringBefore('?').substringAfterLast('/')
                    assets += ReleaseAsset(file, absolute(host, url), 0)
                }
            }
            json.str("description_html")?.let { html ->
                Regex("""href="([^"]+\.(?:apk|apks|apkm|xapk))"""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                    val href = m.groupValues[1].replace("&amp;", "&")
                    // Only the project's own uploads: a description can link anywhere.
                    if (!href.startsWith("/") && !href.startsWith("https://$host/")) return@forEach
                    val url = absolute(host, href)
                    if (assets.none { it.url == url }) assets += ReleaseAsset(url.substringAfterLast('/'), url, 0)
                }
            }
            return GitHubRelease(
                version = json.str("tag_name") ?: json.str("name"),
                pageUrl = json.optJSONObject("_links")?.str("self"),
                assets = assets,
            )
        }
        return null
    }

    /** The newest release in GitLab's list that is out already. */
    fun gitlabLatestTag(body: String): String? {
        val list = JSONArray(body)
        for (i in 0 until list.length()) {
            val json = list.optJSONObject(i) ?: continue
            if (json.optBoolean("upcoming_release", false)) continue
            json.str("tag_name")?.let { return it }
        }
        return null
    }

    private fun absolute(host: String, href: String): String = when {
        href.startsWith("https://") || href.startsWith("http://") -> href
        href.startsWith("/") -> "https://$host$href"
        else -> "https://$host/$href"
    }

    private fun isApkName(name: String) = APK_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /**
     * Releases often carry one file per ABI plus a universal one. Prefer this device's ABI,
     * then a universal build, and never a debug build when a release build is there too.
     * A release can also carry other apps or flavours (Gadgetbridge ships Bangle.js alongside),
     * so the file named after the project ([nameHint]) wins over a merely bigger one.
     */
    fun pickAsset(assets: List<ReleaseAsset>, deviceAbis: List<String>, nameHint: String? = null): ReleaseAsset? {
        if (assets.isEmpty()) return null
        val hint = nameHint?.let(::squash)?.takeIf { it.isNotEmpty() }
        val ranked = assets.sortedWith(
            compareBy(
                { asset -> deviceAbis.indexOfFirst { abi -> asset.name.contains(abi, true) }.takeIf { it >= 0 } ?: 50 },
                { asset -> if (asset.name.contains("universal", true)) 0 else 1 },
                { asset -> if (asset.name.contains("debug", true)) 1 else 0 },
                { asset -> if (hint != null && squash(asset.name).startsWith(hint)) 0 else 1 },
                { asset -> -asset.size },
            )
        )
        return ranked.first()
    }

    /** "Gadgetbridge" and "gadgetbridge-0.94.0.apk" compare alike once case and punctuation go. */
    private fun squash(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    // ---- apps that are not installed yet: search, links ------------------------------

    /**
     * The newest build behind [ref], with a direct file link, for an app that need not be
     * installed. Null when the source has no build, or no file this app can install.
     */
    suspend fun resolveLatest(ref: SourceRef, deviceAbis: List<String>): LatestBuild? = withContext(Dispatchers.IO) {
        val repo = repoOf(ref.kind)
        if (repo != null) {
            val version = parseFDroid(Http.getString(repo.api + ref.value, accept = "application/json"))
                ?: return@withContext null
            val file = ref.value + "_" + version.versionCode + ".apk"
            return@withContext LatestBuild(
                versionName = version.versionName,
                versionCode = version.versionCode,
                url = repo.files + file,
                fileName = file,
                size = 0,
                pageUrl = repo.page + ref.value,
                packageName = ref.value,
            )
        }
        val release = latestRelease(ref.kind, ref.value) ?: return@withContext null
        val asset = pickAsset(release.assets, deviceAbis, nameHint = ref.value.substringAfterLast('/'))
            ?: return@withContext null
        LatestBuild(release.version, null, asset.url, asset.name, asset.size, release.pageUrl, null)
    }

    /** Apps on F-Droid whose name matches; the exact name first, then F-Droid's own order. */
    suspend fun searchFDroid(query: String): List<FDroidHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val hits = parseFDroidSearch(Http.getString(FDROID_SEARCH + URLEncoder.encode(q, "UTF-8"), "application/json"))
        hits.sortedBy { if (it.name.equals(q, ignoreCase = true)) 0 else 1 }
    }

    /** search.f-droid.org gives no package name; it is the last part of each app's page URL. */
    fun parseFDroidSearch(body: String): List<FDroidHit> {
        val apps = JSONObject(body).optJSONArray("apps") ?: return emptyList()
        val hits = ArrayList<FDroidHit>()
        for (i in 0 until apps.length()) {
            val o = apps.optJSONObject(i) ?: continue
            val name = o.str("name") ?: continue
            val url = o.str("url") ?: continue
            val pkg = url.trimEnd('/').substringAfter("/packages/", "").substringBefore('/')
            if (!PACKAGE.matches(pkg)) continue
            hits += FDroidHit(name, o.str("summary"), pkg, o.str("icon"))
        }
        return hits
    }

    /** Is this package in the F-Droid-style repository [kind]? Used before pinning it. */
    suspend fun inRepo(kind: UpdateKind, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val repo = repoOf(kind) ?: return@withContext false
        runCatching { parseFDroid(Http.getString(repo.api + packageName, "application/json")) != null }
            .getOrDefault(false)
    }

    // ---- suggesting a source ---------------------------------------------------------

    /** Is this package on f-droid.org? Used to offer a one-tap pin. */
    suspend fun onFDroid(packageName: String): Boolean = inRepo(UpdateKind.FDROID, packageName)

    /**
     * The source a link points at: an app's page on F-Droid or IzzyOnDroid, or a project on
     * GitHub, GitLab or Codeberg. Null for anything else, a plain file link included.
     */
    fun sourceFromLink(input: String): SourceRef? {
        val text = input.trim()
        val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val parts = (uri.path ?: "").split('/').filter { it.isNotBlank() }
        return when {
            host == "f-droid.org" -> after(parts, "packages")?.let { SourceRef(UpdateKind.FDROID, it) }
            host == "apt.izzysoft.de" -> after(parts, "apk")?.let { SourceRef(UpdateKind.IZZYONDROID, it) }
            // From the parsed path, so a query, a fragment, "www." or plain http do not get in the way.
            host == "github.com" -> ownerRepo(parts)?.let { SourceRef(UpdateKind.GITHUB, it) }
            host == "codeberg.org" -> ownerRepo(parts)?.let { SourceRef(UpdateKind.FORGEJO, "$host/$it") }
            host == "gitlab.com" -> gitlabPath(parts)?.let { SourceRef(UpdateKind.GITLAB, "$host/$it") }
            else -> null
        }
    }

    /**
     * What to pin for a release source typed into the pin dialog: a link, or a short form —
     * "owner/repo" for GitHub, "group/project" (gitlab.com) or "host/group/project" for GitLab,
     * "owner/repo" (codeberg.org) or "host/owner/repo" for Forgejo.
     */
    fun releaseSource(kind: UpdateKind, input: String): String? {
        val text = input.trim().removeSuffix("/")
        if (text.isEmpty()) return null
        // A link to another kind of source is not this kind, whatever its path looks like.
        sourceFromLink(text)?.let { return if (it.kind == kind) it.value else null }
        return when (kind) {
            UpdateKind.GITHUB -> githubRepo(text)
            UpdateKind.GITLAB -> hostAndPath(text, "gitlab.com") { gitlabPath(it) }
            UpdateKind.FORGEJO -> hostAndPath(text, "codeberg.org") { ownerRepo(it) }
            else -> null
        }
    }

    /** "https://host/a/b" or "host/a/b" with a host, else "a/b" on [defaultHost]. */
    private fun hostAndPath(text: String, defaultHost: String, path: (List<String>) -> String?): String? {
        val hasScheme = "://" in text
        val parts = text.substringAfter("://").split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val first = parts[0]
        val looksLikeHost = hasScheme || ('.' in first && parts.size >= 3)
        return if (looksLikeHost) {
            val host = first.lowercase().removePrefix("www.")
            if (!Regex("^[a-z0-9.-]+(:[0-9]+)?$").matches(host)) return null
            path(parts.drop(1))?.let { "$host/$it" }
        } else {
            path(parts)?.let { "$defaultHost/$it" }
        }
    }

    /** The segment after [marker], when it is a package name. */
    private fun after(parts: List<String>, marker: String): String? {
        val i = parts.indexOf(marker)
        if (i < 0) return null
        return parts.getOrNull(i + 1)?.takeIf { PACKAGE.matches(it) }
    }

    private fun ownerRepo(parts: List<String>): String? {
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        return if (SEGMENT.matches(owner) && SEGMENT.matches(repo)) "$owner/$repo" else null
    }

    /** A GitLab project is every segment up to "/-/", so subgroups come along. */
    private fun gitlabPath(parts: List<String>): String? {
        val path = parts.takeWhile { it != "-" }.map { it.removeSuffix(".git") }
        if (path.size < 2 || path.any { !SEGMENT.matches(it) }) return null
        return path.joinToString("/")
    }

    /** "host/a/b" into the host and the rest. */
    private fun splitHost(value: String): Pair<String, String>? {
        val host = value.substringBefore('/')
        val rest = value.substringAfter('/', "")
        return if (host.isBlank() || rest.isBlank()) null else host to rest
    }

    /** "https://github.com/owner/repo/releases" and friends all reduce to "owner/repo". */
    fun githubRepo(input: String): String? {
        val text = input.trim().removeSuffix("/")
        val path = when {
            text.startsWith("https://github.com/") -> text.removePrefix("https://github.com/")
            text.startsWith("github.com/") -> text.removePrefix("github.com/")
            else -> text
        }
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        val ok = Regex("^[A-Za-z0-9._-]+$")
        return if (ok.matches(owner) && ok.matches(repo)) "$owner/$repo" else null
    }

    fun log(result: UpdateResult) {
        val name = result.label ?: result.packageName
        when (result.state) {
            UpdateState.UPDATE -> EventLog.info(
                "update available: $name ${result.installedVersionName} -> ${result.availableVersionName}"
            )
            UpdateState.ERROR -> EventLog.warn("update check failed for $name: ${result.message}")
            else -> EventLog.info("update check: $name is ${result.state.name.lowercase()}")
        }
    }
}

/**
 * The string at [key], or null when it is missing, blank or a JSON null. Android's org.json
 * returns the text "null" from optString for a JSON null; the one in the unit tests does not.
 */
internal fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
