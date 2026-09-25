package com.sideload.splitinstaller.core.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sideload.splitinstaller.core.Languages
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.UpdateInterval
import com.sideload.splitinstaller.core.log.EventLog
import com.sideload.splitinstaller.core.ota.OtaChannel
import com.sideload.splitinstaller.core.ota.OtaFlow
import com.sideload.splitinstaller.core.sources.DownloadRequest
import com.sideload.splitinstaller.core.sources.Downloads
import com.sideload.splitinstaller.core.watch.Notifications
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Asks every pinned source what it has, once a day or once a week.
 *
 * Sideloaded apps cannot update themselves, and a stale build of an online game often just
 * refuses to start and points at a store the device cannot use. This is the part that
 * notices before the app does.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = Languages.wrap(applicationContext)
        val prefs = Prefs.get(context)
        UpdatePins.load(context)
        UpdateStore.load(context)

        // This app is not on a store either, so the same schedule asks about it.
        runCatching { OtaFlow.check(context, auto = true) }

        // The schedule also runs for OTA alone, so pinned sources are only asked when
        // checking them is actually switched on.
        if (prefs.updateInterval == UpdateInterval.OFF) return Result.success()

        val pins = UpdatePins.pins.value.values.toList()
        if (pins.isEmpty()) return Result.success()

        EventLog.rule("checking " + pins.size + " update source(s)")
        UpdateStore.setChecking(true)
        val updates = ArrayList<UpdateResult>()
        try {
            pins.forEachIndexed { index, pin ->
                if (index > 0) delay(POLITE_GAP_MS)
                val result = UpdateChecker.check(context, pin)
                UpdateChecker.log(result)
                UpdateStore.put(context, result)
                if (result.hasUpdate) updates += result
            }
        } finally {
            UpdateStore.setChecking(false)
        }

        if (updates.isNotEmpty()) {
            Notifications.updatesAvailable(context, updates.size, updates.first().label ?: updates.first().packageName)
            if (prefs.updateAutoDownload) startDownloads(context, prefs, updates)
        }
        remindAboutManualSources(context, prefs)
        return Result.success()
    }

    /** Only sources that publish a direct file; a pinned page needs your tap by design. */
    private fun startDownloads(context: Context, prefs: Prefs, updates: List<UpdateResult>) {
        val existing = Downloads.list(context).map { it.fileName }.toSet()
        val autoInstall = prefs.updateAutoInstall
        updates.filter { it.canDownload }.forEach { result ->
            val name = result.fileName
            if (name != null && name in existing) return@forEach
            EventLog.info("auto-downloading " + (result.label ?: result.packageName) + " " + result.availableVersionName)
            Downloads.start(
                context,
                DownloadRequest(
                    url = result.downloadUrl!!,
                    userAgent = Http.USER_AGENT,
                    contentDisposition = null,
                    mimeType = null,
                    referer = null,
                ),
                autoInstall = autoInstall,
                expectedPackage = result.packageName,
            )
        }
    }

    /**
     * Pages the app cannot read a version from get a nudge instead, no more often than the
     * interval you chose.
     */
    private fun remindAboutManualSources(context: Context, prefs: Prefs) {
        val days = prefs.updateReminderDays
        if (days <= 0) return
        val stale = UpdateStore.staleManual(days)
        if (stale.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastManualReminder < days * 86_400_000L) return
        prefs.lastManualReminder = now
        Notifications.manualCheckReminder(context, stale.size, stale.first().label ?: stale.first().packageName)
    }

    companion object {
        private const val POLITE_GAP_MS = 1_200L
        private const val PERIODIC = "update-check"
        private const val ONE_SHOT = "update-check-now"

        fun apply(context: Context) {
            val prefs = Prefs.get(context)
            val manager = WorkManager.getInstance(context)
            val interval = prefs.updateInterval
            // An OTA channel is a reason to keep the schedule even with pinned sources off.
            val otaOn = OtaChannel.parse(prefs.otaChannel).isOn
            if (interval == UpdateInterval.OFF && !otaOn) {
                manager.cancelUniqueWork(PERIODIC)
                return
            }
            val hours = if (interval == UpdateInterval.WEEKLY) 24L * 7 else 24L
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (prefs.updateWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
