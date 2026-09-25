package com.sideload.splitinstaller.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sideload.splitinstaller.core.install.BackendKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How often pinned sources are asked what they have. */
enum class UpdateInterval { OFF, DAILY, WEEKLY }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+). */
    val dynamicColor: Boolean = false,
    /** Pure black surfaces in dark mode, for OLED panels. */
    val amoled: Boolean = false,
)

/** User settings. Deliberately synchronous, because the watch service reads them off a callback. */
class Prefs private constructor(private val sp: SharedPreferences) {

    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeSettings> = _theme

    fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_theme.value)
        sp.edit {
            putString(KEY_THEME_MODE, next.mode.name)
            putBoolean(KEY_DYNAMIC, next.dynamicColor)
            putBoolean(KEY_AMOLED, next.amoled)
        }
        _theme.value = next
    }

    private fun readTheme() = ThemeSettings(
        mode = sp.getString(KEY_THEME_MODE, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = sp.getBoolean(KEY_DYNAMIC, false),
        amoled = sp.getBoolean(KEY_AMOLED, false),
    )

    /** Which language the app itself uses, whatever the system is set to. */
    var language: AppLanguage
        get() = AppLanguage.of(sp.getString(KEY_LANGUAGE, null))
        set(v) = sp.edit { putString(KEY_LANGUAGE, v.name) }

    var backend: BackendKind?
        get() = sp.getString(KEY_BACKEND, null)?.let { name ->
            runCatching { BackendKind.valueOf(name) }.getOrNull()
        }
        set(value) = sp.edit { if (value == null) remove(KEY_BACKEND) else putString(KEY_BACKEND, value.name) }

    /** Watch the download folders and react to new bundles. */
    var watchEnabled: Boolean
        get() = sp.getBoolean(KEY_WATCH, false)
        set(v) = sp.edit { putBoolean(KEY_WATCH, v) }

    /** Install a detected bundle outright, rather than only posting a notification. */
    var autoInstall: Boolean
        get() = sp.getBoolean(KEY_AUTO_INSTALL, false)
        set(v) = sp.edit { putBoolean(KEY_AUTO_INSTALL, v) }

    /** Only auto-install an update to something already on the device. */
    var autoInstallUpdatesOnly: Boolean
        get() = sp.getBoolean(KEY_UPDATES_ONLY, true)
        set(v) = sp.edit { putBoolean(KEY_UPDATES_ONLY, v) }

    var installObb: Boolean
        get() = sp.getBoolean(KEY_OBB, true)
        set(v) = sp.edit { putBoolean(KEY_OBB, v) }

    var allowDowngrade: Boolean
        get() = sp.getBoolean(KEY_DOWNGRADE, true)
        set(v) = sp.edit { putBoolean(KEY_DOWNGRADE, v) }

    var grantAllPermissions: Boolean
        get() = sp.getBoolean(KEY_GRANT_ALL, false)
        set(v) = sp.edit { putBoolean(KEY_GRANT_ALL, v) }

    var verifyChecksums: Boolean
        get() = sp.getBoolean(KEY_VERIFY, true)
        set(v) = sp.edit { putBoolean(KEY_VERIFY, v) }

    /** Export the installed copy as .apks before an update replaces it. */
    var backupBeforeUpdate: Boolean
        get() = sp.getBoolean(KEY_BACKUP, false)
        set(v) = sp.edit { putBoolean(KEY_BACKUP, v) }

    var showSystemApps: Boolean
        get() = sp.getBoolean(KEY_SYSTEM_APPS, false)
        set(v) = sp.edit { putBoolean(KEY_SYSTEM_APPS, v) }

    // ---- updates ---------------------------------------------------------------

    var updateInterval: UpdateInterval
        get() = sp.getString(KEY_UPDATE_INTERVAL, null)
            ?.let { runCatching { UpdateInterval.valueOf(it) }.getOrNull() } ?: UpdateInterval.DAILY
        set(v) = sp.edit { putString(KEY_UPDATE_INTERVAL, v.name) }

    var updateWifiOnly: Boolean
        get() = sp.getBoolean(KEY_UPDATE_WIFI, true)
        set(v) = sp.edit { putBoolean(KEY_UPDATE_WIFI, v) }

    /** Fetch the file by itself, where the source publishes a direct link. */
    var updateAutoDownload: Boolean
        get() = sp.getBoolean(KEY_UPDATE_DOWNLOAD, false)
        set(v) = sp.edit { putBoolean(KEY_UPDATE_DOWNLOAD, v) }

    /** Install a downloaded bundle without asking. Needs Shizuku or root. */
    var updateAutoInstall: Boolean
        get() = sp.getBoolean(KEY_UPDATE_INSTALL, false)
        set(v) = sp.edit { putBoolean(KEY_UPDATE_INSTALL, v) }

    /** How long a pinned page may go unchecked before the app nudges you. 0 turns it off. */
    var updateReminderDays: Int
        get() = sp.getInt(KEY_UPDATE_REMINDER, 14)
        set(v) = sp.edit { putInt(KEY_UPDATE_REMINDER, v) }

    var lastManualReminder: Long
        get() = sp.getLong(KEY_LAST_REMINDER, 0)
        set(v) = sp.edit { putLong(KEY_LAST_REMINDER, v) }

    // ---- this app's own updates (OTA) -------------------------------------------

    /** "owner/repo" or an https URL to a manifest. Empty turns OTA off. */
    var otaChannel: String
        get() = sp.getString(KEY_OTA_CHANNEL, "").orEmpty()
        set(v) = sp.edit { putString(KEY_OTA_CHANNEL, v.trim()) }

    var otaAutoDownload: Boolean
        get() = sp.getBoolean(KEY_OTA_DOWNLOAD, true)
        set(v) = sp.edit { putBoolean(KEY_OTA_DOWNLOAD, v) }

    /** Replace the running copy without asking. Needs Shizuku or root. */
    var otaAutoInstall: Boolean
        get() = sp.getBoolean(KEY_OTA_INSTALL, false)
        set(v) = sp.edit { putBoolean(KEY_OTA_INSTALL, v) }

    var extraWatchDirs: Set<String>
        get() = sp.getStringSet(KEY_DIRS, emptySet()).orEmpty()
        set(v) = sp.edit { putStringSet(KEY_DIRS, v) }

    /** Bundles already handled, so a folder rescan does not reinstall them. */
    fun markHandled(key: String) {
        val current = sp.getStringSet(KEY_HANDLED, emptySet()).orEmpty().toMutableSet()
        current += key
        // Keep the list from growing without bound.
        val trimmed = if (current.size > 200) current.toList().takeLast(200).toSet() else current
        sp.edit { putStringSet(KEY_HANDLED, trimmed) }
    }

    fun isHandled(key: String): Boolean =
        key in sp.getStringSet(KEY_HANDLED, emptySet()).orEmpty()

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC = "theme_dynamic"
        private const val KEY_AMOLED = "theme_amoled"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_BACKEND = "backend"
        private const val KEY_WATCH = "watch_enabled"
        private const val KEY_AUTO_INSTALL = "auto_install"
        private const val KEY_UPDATES_ONLY = "auto_updates_only"
        private const val KEY_OBB = "install_obb"
        private const val KEY_DOWNGRADE = "allow_downgrade"
        private const val KEY_GRANT_ALL = "grant_all"
        private const val KEY_VERIFY = "verify_checksums"
        private const val KEY_BACKUP = "backup_before_update"
        private const val KEY_SYSTEM_APPS = "show_system_apps"
        private const val KEY_DIRS = "watch_dirs"
        private const val KEY_HANDLED = "handled"
        private const val KEY_UPDATE_INTERVAL = "update_interval"
        private const val KEY_UPDATE_WIFI = "update_wifi_only"
        private const val KEY_UPDATE_DOWNLOAD = "update_auto_download"
        private const val KEY_UPDATE_INSTALL = "update_auto_install"
        private const val KEY_UPDATE_REMINDER = "update_reminder_days"
        private const val KEY_LAST_REMINDER = "update_last_reminder"
        private const val KEY_OTA_CHANNEL = "ota_channel"
        private const val KEY_OTA_DOWNLOAD = "ota_auto_download"
        private const val KEY_OTA_INSTALL = "ota_auto_install"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
