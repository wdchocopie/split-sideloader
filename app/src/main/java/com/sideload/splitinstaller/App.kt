package com.sideload.splitinstaller

import android.app.Application
import android.content.Context
import com.sideload.splitinstaller.core.AppVisibility
import com.sideload.splitinstaller.core.Languages
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.history.InstallHistory
import com.sideload.splitinstaller.core.ota.OtaStore
import com.sideload.splitinstaller.core.update.UpdatePins
import com.sideload.splitinstaller.core.update.UpdateStore
import com.sideload.splitinstaller.core.update.UpdateWorker
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.watch.Notifications
import com.sideload.splitinstaller.core.watch.WatchService
import android.os.Build

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Languages.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppVisibility.register(this)
        Prefs.get(this).migrate()
        Notifications.ensureChannels(this)
        InstallHistory.load(this)
        UpdatePins.load(this)
        UpdateStore.load(this)
        OtaStore.load(this)
        // An app cannot watch itself being replaced, so it finds out here.
        runCatching { OtaStore.reportAttempt(this) }
        // Whatever route the new build came by, a stored build that is not newer is done with.
        runCatching { OtaStore.reconcile(this) }
        // Re-applies the schedule every launch, so a changed interval always takes.
        runCatching { UpdateWorker.apply(this) }
        EventLog.info(
            "SplitSideloader " + BuildConfig.VERSION_NAME + " on " +
                Build.MANUFACTURER + " " + Build.MODEL +
                " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
                " · " + Build.SUPPORTED_ABIS.joinToString()
        )
        if (Prefs.get(this).watchEnabled) {
            runCatching { WatchService.start(this) }
        }
    }
}
