package com.sideload.splitinstaller.core.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sideload.splitinstaller.MainActivity
import com.sideload.splitinstaller.R

object Notifications {

    const val CHANNEL_WATCH = "watch"
    const val CHANNEL_EVENTS = "events"

    const val ID_WATCH = 1

    /** Workers get their own, so they never take over the watcher's foreground notification. */
    const val ID_WORK = 2
    const val ID_OTA_WORK = 3
    private var nextEventId = 100

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATCH,
                context.getString(R.string.channel_watch),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_watch_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                context.getString(R.string.channel_events),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_events_desc) }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun watching(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.watch_running))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, null))
            .build()

    fun found(context: Context, name: String, uri: Uri) {
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_found_title))
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(name))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, uri))
                .build()
        )
    }

    fun result(context: Context, title: String, text: String, uri: Uri?) {
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, uri))
                .build()
        )
    }

    fun updatesAvailable(context: Context, count: Int, firstLabel: String) {
        val text = if (count == 1) firstLabel else context.getString(R.string.notif_updates_more, firstLabel, count - 1)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.resources.getQuantityString(R.plurals.notif_updates_title, count, count))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openUpdates(context))
                .build()
        )
    }

    /** For pinned pages: the app cannot read a version from them, so it nudges instead. */
    fun manualCheckReminder(context: Context, count: Int, firstLabel: String) {
        val text = context.getString(R.string.notif_manual_body, firstLabel)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.resources.getQuantityString(R.plurals.notif_manual_title, count, count))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openUpdates(context))
                .build()
        )
    }

    /** After a reboot: Shizuku is gone until it is started again over ADB. */
    fun shizukuGone(context: Context) {
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_shizuku_gone_title))
                .setContentText(context.getString(R.string.notif_shizuku_gone_body))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_shizuku_gone_body)))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, null))
                .build()
        )
    }

    fun downloaded(context: Context, name: String, uri: Uri) {
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_downloaded_title))
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(name))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, uri))
                .build()
        )
    }

    fun downloadFailed(context: Context, name: String, reason: String) {
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_download_failed_title))
                .setContentText("$name · $reason")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n$reason"))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, null))
                .build()
        )
    }

    /** A new build of this app exists on the channel it was told to watch. */
    fun otaAvailable(context: Context, versionName: String) {
        val text = context.getString(R.string.ota_notif_body, versionName)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.ota_notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openOta(context))
                .build()
        )
    }

    /** Downloaded and checked, but Android needs a tap to replace a running app. */
    fun otaReady(context: Context, versionName: String) {
        val text = context.getString(R.string.ota_ready_body, versionName)
        post(
            context,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.ota_ready_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openOta(context))
                .build()
        )
    }

    private fun post(context: Context, notification: Notification) {
        if (!canPost(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(nextEventId++, notification)
        }
    }

    private fun openUpdates(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATES, true)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 7001, intent, flags)
    }

    private fun openOta(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_OTA, true)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 7002, intent, flags)
    }

    private fun openApp(context: Context, uri: Uri?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (uri != null) intent.setAction(Intent.ACTION_VIEW).setData(uri)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, uri?.hashCode() ?: 0, intent, flags)
    }
}
