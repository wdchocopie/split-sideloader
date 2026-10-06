package com.sideload.splitinstaller.core.install

/** Why an install that would start without a tap on Install was held back. */
enum class AutoInstallBlock {
    /** The bundle itself failed its checks. */
    BUNDLE,

    /** The file is another app than the one that was asked for. */
    OTHER_PACKAGE,

    /** This app itself: that goes through its own update path, which checks the signer first. */
    OWN_PACKAGE,

    /** The splits picked for this phone raised a problem. */
    SELECTION,

    /** Signed by someone else than the installed copy; the package manager would refuse it anyway. */
    SIGNER,

    /** Older than what is installed. Going back is a choice someone has to make. */
    DOWNGRADE,
}

/**
 * The one rule for installs that start without a tap on Install: a finished download, a link
 * someone asked to install, a folder being watched. Pure, so it is tested off the device.
 */
object AutoInstall {

    fun blockedBy(
        packageName: String?,
        expectedPackage: String?,
        ownPackage: String,
        bundleHasError: Boolean,
        selectionProblems: Boolean,
        signerMismatch: Boolean,
        installedVersionCode: Long?,
        versionCode: Long,
    ): AutoInstallBlock? = when {
        // A package that could not be named cannot be checked against anything below.
        bundleHasError || packageName == null -> AutoInstallBlock.BUNDLE
        expectedPackage != null && packageName != null && packageName != expectedPackage -> AutoInstallBlock.OTHER_PACKAGE
        packageName == ownPackage -> AutoInstallBlock.OWN_PACKAGE
        selectionProblems -> AutoInstallBlock.SELECTION
        signerMismatch -> AutoInstallBlock.SIGNER
        installedVersionCode != null && versionCode > 0 && versionCode < installedVersionCode -> AutoInstallBlock.DOWNGRADE
        else -> null
    }
}
