package com.sideload.splitinstaller.core.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.update.UpdateWorker

/**
 * Brings the watcher back after a reboot.
 *
 * Note that Shizuku does not survive a reboot on an unrooted device: it has to be started
 * again over ADB. Until it is, the watcher falls back to notifying rather than installing.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = Prefs.get(context)
                if (prefs.watchEnabled) {
                    runCatching { WatchService.start(context) }
                }
                runCatching { UpdateWorker.apply(context) }

                // Silent installs die with Shizuku at every reboot on an unrooted device.
                // Say so once, rather than letting automatic updates quietly stop working.
                val needsSilent = prefs.updateAutoInstall || (prefs.watchEnabled && prefs.autoInstall)
                if (needsSilent && intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    val silent = BackendResolver.probe(context).silentAvailable
                    if (!silent) runCatching { Notifications.shizukuGone(context) }
                }
            }
        }
    }
}
