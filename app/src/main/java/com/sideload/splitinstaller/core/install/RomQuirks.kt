package com.sideload.splitinstaller.core.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit

/** Where some Android skins part ways with AOSP in ways that change how installs behave. */
object RomQuirks {

    /**
     * System installers seen "confirming" a session by copying base.apk out of it and
     * installing that alone: every split is lost, and the session itself never gets an answer.
     */
    private val DROPS_SPLITS = setOf("com.blackshark.packageinstaller")

    private const val ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"

    /** What resolving lands on when several apps tie and none is the default. */
    private const val CHOOSER = "android"

    private const val PREFS = "rom_quirks"
    private const val KEY_TAKEOVER = "installer_takeover"

    /**
     * The app that shows install confirmations on this device. A session's dialog is a
     * CONFIRM_INSTALL intent, which only the system installer may claim with priority, so that is
     * what is resolved. Another APK handler installed alongside would make a plain
     * INSTALL_PACKAGE probe land on the chooser ("android") or on that app instead.
     */
    fun systemInstaller(context: Context): String? = runCatching {
        val pm = context.packageManager
        val confirm = Intent(ACTION_CONFIRM_INSTALL)
        val probe = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(Uri.parse("content://probe/probe.apk"), "application/vnd.android.package-archive")
        sequenceOf(confirm, probe)
            .mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .firstOrNull { it != CHOOSER }
    }.getOrNull()

    /** The app a session's confirmation [intent] opens. */
    fun dialogOf(context: Context, intent: Intent): String? =
        (intent.`package` ?: intent.component?.packageName
            ?: runCatching { intent.resolveActivity(context.packageManager)?.packageName }.getOrNull())
            ?.takeIf { it != CHOOSER }

    /**
     * True when the confirmation dialog cannot be trusted with more than one APK. A remembered
     * takeover counts while that installer is still the one behind the dialog, or is one known to
     * do this.
     */
    fun installerDropsSplits(context: Context): Boolean {
        val dialog = systemInstaller(context)
        val taken = takeover(context)
        return dialog in DROPS_SPLITS || taken in DROPS_SPLITS ||
            (taken != null && (dialog == null || taken == dialog))
    }

    /** The installer that took one of our sessions over, remembered once it has been seen. */
    fun takeover(context: Context): String? = prefs(context).getString(KEY_TAKEOVER, null)

    fun rememberTakeover(context: Context, installer: String) =
        prefs(context).edit { putString(KEY_TAKEOVER, installer) }

    /** Called once the dialog has let a session with splits through after all. */
    fun forgetTakeover(context: Context) {
        if (takeover(context) != null) prefs(context).edit { remove(KEY_TAKEOVER) }
    }

    /**
     * MIUI, and skins built on it such as Black Shark's JoyUI, keep a per-app autostart switch.
     * While it is off, a closed app is never started for background work, and it is not
     * reopened after replacing itself. Null where the screen does not exist.
     */
    fun autostartSettings(context: Context): Intent? {
        val intent = Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        val found = runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        return intent.takeIf { found }
    }

    /**
     * Whether the confirmation dialog installed the package by itself while our session was
     * waiting: its update time moved, and the installer of record is the app behind the dialog
     * ([dialog]) or one known to do this, not this app. A session that really went through
     * records this app as the installer. A store updating the same package meanwhile is not a
     * takeover; our dialog is still up.
     */
    internal fun tookOver(before: Long?, after: Long?, installer: String?, self: String, dialog: String?): Boolean =
        after != null && after != before && installer != null && installer != self &&
            (installer == dialog || installer in DROPS_SPLITS)

    /**
     * Whether the package now installed is the build our session carried: the same version, and
     * a base.apk of the same size where both sizes are known. A takeover installs exactly our
     * base.apk; the user installing some other copy of the app meanwhile does not.
     */
    internal fun isOurBuild(installedVersion: Long?, version: Long, installedBaseSize: Long?, baseSize: Long?): Boolean =
        (version <= 0 || installedVersion == version) &&
            (baseSize == null || baseSize <= 0 || installedBaseSize == null || installedBaseSize == baseSize)

    /**
     * Whether a package that is missing its splits got that way through an installer known to
     * drop them: one on the list, or the one already caught taking over a session here.
     */
    internal fun droppedBy(installer: String, takeover: String?): Boolean =
        installer in DROPS_SPLITS || installer == takeover

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
