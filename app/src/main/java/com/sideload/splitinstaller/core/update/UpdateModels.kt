package com.sideload.splitinstaller.core.update

/** Where an installed package's updates come from. */
enum class UpdateKind {
    /** f-droid.org's per-package API: exact versionCode and a direct APK link. */
    FDROID,

    /** A GitHub repository's latest release and its APK assets. */
    GITHUB,

    /**
     * A page you pinned yourself, such as an app's APKMirror or APKPure page. Those sites
     * do not offer a version API and their robots policy rules out fetching downloads with
     * a machine, so the app reminds you and opens the page; the download stays your tap.
     */
    WEB,
}

data class UpdatePin(
    val packageName: String,
    val kind: UpdateKind,
    /** "owner/repo" for GitHub, the package name for F-Droid, a URL for a web page. */
    val value: String,
    val label: String? = null,
    val pinnedAt: Long = System.currentTimeMillis(),
)

enum class UpdateState {
    /** A newer version exists. */
    UPDATE,
    UP_TO_DATE,

    /** Pinned to a page: the app cannot read a version from it, so you check it. */
    MANUAL,

    /** The source answered, but the versions could not be compared. */
    UNKNOWN,
    ERROR,
}

data class UpdateResult(
    val packageName: String,
    val kind: UpdateKind,
    val state: UpdateState,
    val label: String? = null,
    val installedVersionName: String? = null,
    val installedVersionCode: Long = 0,
    val availableVersionName: String? = null,
    val availableVersionCode: Long? = null,
    /** Present only for sources that publish a direct file link. */
    val downloadUrl: String? = null,
    val fileName: String? = null,
    val size: Long = 0,
    /** The page to open — the source's own listing for this app. */
    val pageUrl: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    val message: String? = null,
) {
    val hasUpdate: Boolean get() = state == UpdateState.UPDATE
    val canDownload: Boolean get() = downloadUrl != null
}
