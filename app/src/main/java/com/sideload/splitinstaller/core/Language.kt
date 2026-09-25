package com.sideload.splitinstaller.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language the app speaks, independent of the phone's.
 *
 * Worth having on its own: the device this was written for runs a Vietnamese system, while
 * half the things it reports — `primaryCpuAbi`, `INSTALL_FAILED_MISSING_SPLIT`, logcat lines
 * — are English anyway, and a bug report is easier to read when the screen around them
 * matches. Per-app languages are a system feature from Android 13; this works from 8.0.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    VI("vi"),
    EN("en"),
    ;

    companion object {
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.name == tag } ?: SYSTEM
    }
}

object Languages {

    /**
     * Called from `attachBaseContext`, before anything has read a resource. Returns [base]
     * unchanged when the choice is to follow the system, so nothing is overridden by default.
     */
    fun wrap(base: Context): Context {
        val tag = runCatching { Prefs.get(base).language.tag }.getOrNull() ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
