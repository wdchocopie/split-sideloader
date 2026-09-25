package com.sideload.splitinstaller.core.update

import android.content.Context
import android.os.Build
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.verify.InstallVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What f-droid.org says about one package. */
data class FDroidVersion(val versionName: String?, val versionCode: Long)

/** One APK-ish file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class GitHubRelease(val version: String?, val pageUrl: String?, val assets: List<ReleaseAsset>)

/**
 * Asks a package's pinned source what the newest version is.
 *
 * Only sources that publish this for machines are queried: f-droid.org's package API and
 * GitHub's releases API. A pinned web page is reported as [UpdateState.MANUAL] — APKMirror
 * and APKPure have no version API, and their robots policy puts downloads off limits to
 * automation, so the app opens the page and you take it from there.
 */
object UpdateChecker {

    private const val FDROID_API = "https://f-droid.org/api/v1/packages/"
    private const val FDROID_REPO = "https://f-droid.org/repo/"
    private const val FDROID_PAGE = "https://f-droid.org/packages/"
    private const val GITHUB_API = "https://api.github.com/repos/"

    private val APK_EXTENSIONS = listOf(".apk", ".apks", ".apkm", ".xapk")

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
                UpdateKind.FDROID -> checkFDroid(base, pin)
                UpdateKind.GITHUB -> checkGitHub(base, pin)
                UpdateKind.WEB -> base.copy(state = UpdateState.MANUAL, pageUrl = pin.value)
            }
        } catch (e: Http.HttpError) {
            base.copy(state = UpdateState.ERROR, message = "HTTP ${e.code}", pageUrl = pageFor(pin))
        } catch (t: Throwable) {
            base.copy(state = UpdateState.ERROR, message = t.message ?: "check failed", pageUrl = pageFor(pin))
        }
    }

    private fun pageFor(pin: UpdatePin): String? = when (pin.kind) {
        UpdateKind.FDROID -> FDROID_PAGE + pin.value
        UpdateKind.GITHUB -> "https://github.com/" + pin.value + "/releases"
        UpdateKind.WEB -> pin.value
    }

    // ---- F-Droid --------------------------------------------------------------------

    private fun checkFDroid(base: UpdateResult, pin: UpdatePin): UpdateResult {
        val body = Http.getString(FDROID_API + pin.value, accept = "application/json")
        val version = parseFDroid(body)
            ?: return base.copy(state = UpdateState.ERROR, message = "no versions listed", pageUrl = pageFor(pin))

        val newer = version.versionCode > base.installedVersionCode
        return base.copy(
            state = if (newer) UpdateState.UPDATE else UpdateState.UP_TO_DATE,
            availableVersionName = version.versionName,
            availableVersionCode = version.versionCode,
            downloadUrl = FDROID_REPO + pin.value + "_" + version.versionCode + ".apk",
            fileName = pin.value + "_" + version.versionCode + ".apk",
            pageUrl = FDROID_PAGE + pin.value,
        )
    }

    /** The suggested (stable) build, falling back to the highest versionCode listed. */
    fun parseFDroid(body: String): FDroidVersion? {
        val json = JSONObject(body)
        val packages: JSONArray = json.optJSONArray("packages") ?: return null
        val suggested = json.optLong("suggestedVersionCode", 0L)
        var best: FDroidVersion? = null
        for (i in 0 until packages.length()) {
            val o = packages.optJSONObject(i) ?: continue
            val code = o.optLong("versionCode", 0L)
            if (code <= 0) continue
            val entry = FDroidVersion(o.optString("versionName").ifBlank { null }, code)
            if (code == suggested) return entry
            val current = best
            if (current == null || code > current.versionCode) best = entry
        }
        return best
    }

    // ---- GitHub ---------------------------------------------------------------------

    private fun checkGitHub(base: UpdateResult, pin: UpdatePin): UpdateResult {
        val body = Http.getString(
            GITHUB_API + pin.value + "/releases/latest",
            accept = "application/vnd.github+json",
        )
        val release = parseGitHub(body)
            ?: return base.copy(state = UpdateState.ERROR, message = "no release found", pageUrl = pageFor(pin))
        val asset = pickAsset(release.assets, Build.SUPPORTED_ABIS.toList())

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

    fun parseGitHub(body: String): GitHubRelease? {
        val json = JSONObject(body)
        if (!json.has("tag_name") && !json.has("name")) return null
        val assets = ArrayList<ReleaseAsset>()
        json.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val url = o.optString("browser_download_url")
                if (name.isBlank() || url.isBlank()) continue
                if (APK_EXTENSIONS.none { name.endsWith(it, ignoreCase = true) }) continue
                assets += ReleaseAsset(name, url, o.optLong("size", 0L))
            }
        }
        return GitHubRelease(
            version = json.optString("tag_name").ifBlank { json.optString("name").ifBlank { null } },
            pageUrl = json.optString("html_url").ifBlank { null },
            assets = assets,
        )
    }

    /**
     * Releases often carry one file per ABI plus a universal one. Prefer this device's ABI,
     * then a universal build, and never a debug build when a release build is there too.
     */
    fun pickAsset(assets: List<ReleaseAsset>, deviceAbis: List<String>): ReleaseAsset? {
        if (assets.isEmpty()) return null
        val ranked = assets.sortedWith(
            compareBy(
                { asset -> deviceAbis.indexOfFirst { abi -> asset.name.contains(abi, true) }.takeIf { it >= 0 } ?: 50 },
                { asset -> if (asset.name.contains("universal", true)) 0 else 1 },
                { asset -> if (asset.name.contains("debug", true)) 1 else 0 },
                { asset -> -asset.size },
            )
        )
        return ranked.first()
    }

    // ---- suggesting a source ---------------------------------------------------------

    /** Is this package on f-droid.org? Used to offer a one-tap pin. */
    suspend fun onFDroid(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { parseFDroid(Http.getString(FDROID_API + packageName, "application/json")) != null }
            .getOrDefault(false)
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
