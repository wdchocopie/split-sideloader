package com.sideload.splitinstaller.core.ota

/**
 * Where this app's own updates come from.
 *
 * It is not on any store — that is the whole point of it — so the channel is whatever you
 * publish to: a GitHub repository's releases, or a small JSON file on any host.
 */
enum class OtaChannelKind { OFF, GITHUB, MANIFEST }

data class OtaChannel(val kind: OtaChannelKind, val value: String) {

    val isOn: Boolean get() = kind != OtaChannelKind.OFF

    /** What to show, and what the settings field holds. */
    val text: String get() = if (kind == OtaChannelKind.OFF) "" else value

    val pageUrl: String?
        get() = when (kind) {
            OtaChannelKind.GITHUB -> "https://github.com/$value/releases"
            OtaChannelKind.MANIFEST -> value
            OtaChannelKind.OFF -> null
        }

    companion object {
        val OFF = OtaChannel(OtaChannelKind.OFF, "")

        /**
         * `owner/repo` or a github.com link is a release feed; any other https URL is a
         * manifest. Anything else turns the channel off rather than guessing.
         */
        fun parse(raw: String?): OtaChannel {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return OFF
            val lower = text.lowercase()
            // A JSON file is a manifest wherever it is hosted — including a release asset on
            // github.com, which would otherwise be read as a repository link and lose the
            // versionCode and SHA-256 the manifest carries.
            if (lower.startsWith("https://") && lower.substringBefore('?').substringBefore('#').endsWith(".json")) {
                return OtaChannel(OtaChannelKind.MANIFEST, text)
            }
            if (lower.startsWith("https://github.com/") || lower.startsWith("github.com/") ||
                (!lower.startsWith("http") && text.count { it == '/' } == 1)
            ) {
                val repo = com.sideload.splitinstaller.core.update.UpdateChecker.githubRepo(text)
                return if (repo != null) OtaChannel(OtaChannelKind.GITHUB, repo) else OFF
            }
            if (lower.startsWith("https://")) return OtaChannel(OtaChannelKind.MANIFEST, text)
            // Plain http would let anyone on the path hand this app its next version.
            return OFF
        }
    }
}

/** One published build of this app. */
data class OtaRelease(
    val versionName: String?,
    /** 0 when the channel publishes no versionCode; the downloaded APK is then the authority. */
    val versionCode: Long,
    val url: String,
    val fileName: String,
    val sha256: String? = null,
    val size: Long = 0,
    val notes: String? = null,
    val pageUrl: String? = null,
    val minSdk: Int = 0,
) {
    /**
     * What a refusal is remembered by. The URL alone is not enough: a channel may publish a
     * fixed build under the same file name.
     */
    val identity: String get() = url + "#" + (sha256 ?: versionName ?: versionCode.toString())
}

enum class OtaState {
    /** No channel configured. */
    OFF,
    UP_TO_DATE,
    UPDATE,

    /** A file is on the device, checked, and waiting to be installed. */
    READY,

    /** Versions could not be compared; the channel published no code and no usable name. */
    UNKNOWN,
    ERROR,
}

data class OtaStatus(
    val state: OtaState = OtaState.OFF,
    val release: OtaRelease? = null,
    val checkedAt: Long = 0,
    val message: String? = null,
    /** Set once the file is downloaded and verified. */
    val fileUri: String? = null,
    /** The URL [fileUri] was downloaded from, so the file is never mistaken for another build. */
    val fileSourceUrl: String? = null,
    /** versionCode of the last install this app started on itself. */
    val attemptedVersion: Long = 0,
    val attemptFailures: Int = 0,
    /** [OtaRelease.identity] of a build whose file failed the checks; not fetched again by itself. */
    val refusedKey: String? = null,
    /** [OtaRelease.identity] of the build the "ready, tap to install" notification was posted for. */
    val readyNotifiedKey: String? = null,
) {
    val hasUpdate: Boolean get() = state == OtaState.UPDATE || state == OtaState.READY

    /** A build is known and still to be installed. */
    val pending: Boolean get() = release != null && hasUpdate

    /** Two self-installs in a row failed; from then on it waits to be asked. */
    val stoppedRetrying: Boolean get() = attemptFailures >= 2
}

/** Why a downloaded OTA file was refused. Codes, so the UI can say it in either language. */
object OtaProblem {
    const val WRONG_PACKAGE = "ota_wrong_package"
    const val NOT_NEWER = "ota_not_newer"
    const val SIGNER_MISMATCH = "ota_signer_mismatch"
    const val SIGNER_UNKNOWN = "ota_signer_unknown"
    const val DIGEST_MISMATCH = "ota_digest_mismatch"
    const val UNREADABLE = "ota_unreadable"
}
