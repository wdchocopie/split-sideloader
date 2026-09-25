package com.sideload.splitinstaller.core.ota

import android.content.Context
import android.net.Uri
import android.os.Build
import com.sideload.splitinstaller.BuildConfig
import com.sideload.splitinstaller.core.bundle.BundleInfo
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.sign.ApkSignatures
import com.sideload.splitinstaller.core.sign.SignatureMatch
import com.sideload.splitinstaller.core.update.Http
import com.sideload.splitinstaller.core.update.UpdateChecker
import com.sideload.splitinstaller.core.update.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The app updating itself.
 *
 * A sideloaded app has nothing watching over it, so this is deliberately suspicious of what
 * it fetches: the file must be this package, must carry a higher versionCode than the copy
 * running, and must be signed by the same certificate as that copy. The last check is the
 * one that matters — a channel that is hijacked, a host that is replaced, a link that is
 * redirected, all end at the same place, and none of them can produce an APK the platform
 * would accept as an update to this one.
 */
object Ota {

    /** As written by `./gradlew :app:otaManifest`. */
    fun parseManifest(body: String): OtaRelease? {
        val json = JSONObject(body)
        val url = json.optString("url").takeIf { it.startsWith("https://") } ?: return null
        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName").ifBlank { null }
        if (versionCode <= 0 && versionName == null) return null
        return OtaRelease(
            versionName = versionName,
            versionCode = versionCode,
            url = url,
            fileName = json.optString("fileName").ifBlank { url.substringAfterLast('/') },
            sha256 = json.optString("sha256").ifBlank { null }?.lowercase(),
            size = json.optLong("size", 0L),
            notes = json.optString("notes").ifBlank { null },
            pageUrl = json.optString("pageUrl").ifBlank { null },
            minSdk = json.optInt("minSdk", 0),
        )
    }

    suspend fun check(context: Context, channel: OtaChannel): OtaStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!channel.isOn) return@withContext OtaStatus(OtaState.OFF, checkedAt = now)
        try {
            val release = when (channel.kind) {
                OtaChannelKind.MANIFEST -> parseManifest(Http.getString(channel.value, "application/json"))
                OtaChannelKind.GITHUB -> fromGitHub(channel.value)
                OtaChannelKind.OFF -> null
            } ?: return@withContext OtaStatus(
                OtaState.ERROR, checkedAt = now, message = "nothing published on this channel",
            )

            if (release.minSdk > 0 && release.minSdk > Build.VERSION.SDK_INT) {
                return@withContext OtaStatus(
                    OtaState.UP_TO_DATE, release, now,
                    message = "the published build needs API " + release.minSdk,
                )
            }

            when (isNewer(release)) {
                true -> OtaStatus(OtaState.UPDATE, release, now)
                false -> OtaStatus(OtaState.UP_TO_DATE, release, now)
                null -> OtaStatus(OtaState.UNKNOWN, release, now)
            }
        } catch (t: Throwable) {
            OtaStatus(OtaState.ERROR, checkedAt = now, message = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun fromGitHub(repo: String): OtaRelease? {
        val body = Http.getString(
            "https://api.github.com/repos/$repo/releases/latest",
            accept = "application/vnd.github+json",
        )
        val release = UpdateChecker.parseGitHub(body) ?: return null
        val asset = UpdateChecker.pickAsset(release.assets, Build.SUPPORTED_ABIS.toList()) ?: return null
        return OtaRelease(
            versionName = release.version,
            versionCode = 0,
            url = asset.url,
            fileName = asset.name,
            size = asset.size,
            notes = null,
            pageUrl = release.pageUrl,
        )
    }

    /**
     * Null when the channel gave neither a versionCode nor a comparable name. The download
     * is still allowed in that case — the APK itself carries the real versionCode, and
     * [verify] reads it before anything is installed.
     */
    fun isNewer(release: OtaRelease): Boolean? = when {
        release.versionCode > 0 -> release.versionCode > BuildConfig.VERSION_CODE
        else -> VersionCompare.isNewer(release.versionName, BuildConfig.VERSION_NAME)
    }

    /**
     * The gate every OTA file passes through, whoever started the download. Returns null
     * when the file may be installed, or the code of the first thing wrong with it.
     */
    fun verify(context: Context, info: BundleInfo, expectedSha256: String?, fileSha256: String?): String? {
        if (info.packageName != BuildConfig.APPLICATION_ID) return OtaProblem.WRONG_PACKAGE
        if (info.versionCode <= BuildConfig.VERSION_CODE) return OtaProblem.NOT_NEWER
        if (expectedSha256 != null && !expectedSha256.equals(fileSha256, ignoreCase = true)) {
            return OtaProblem.DIGEST_MISMATCH
        }
        return when (ApkSignatures.compare(context, info.packageName, info.signerSha256)) {
            SignatureMatch.MATCH -> null
            SignatureMatch.MISMATCH -> OtaProblem.SIGNER_MISMATCH
            // Our own copy is installed by definition, so either of these means the file's
            // signature could not be read — which is reason enough not to install it.
            SignatureMatch.NOT_INSTALLED, SignatureMatch.UNKNOWN -> OtaProblem.SIGNER_UNKNOWN
        }
    }

    fun sha256(context: Context, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun log(status: OtaStatus) {
        val version = status.release?.versionName ?: "?"
        when (status.state) {
            OtaState.UPDATE -> EventLog.info("OTA: $version is newer than ${BuildConfig.VERSION_NAME}")
            OtaState.READY -> EventLog.info("OTA: $version downloaded and verified")
            OtaState.UP_TO_DATE -> EventLog.info("OTA: ${BuildConfig.VERSION_NAME} is current")
            OtaState.UNKNOWN -> EventLog.warn("OTA: could not compare $version with ${BuildConfig.VERSION_NAME}")
            OtaState.ERROR -> EventLog.warn("OTA check failed: " + status.message)
            OtaState.OFF -> Unit
        }
    }
}
