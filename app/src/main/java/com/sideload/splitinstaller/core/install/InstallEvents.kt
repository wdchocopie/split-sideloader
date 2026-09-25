package com.sideload.splitinstaller.core.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes `PackageInstaller` session callbacks back to whoever is awaiting them.
 *
 * A commit can report twice: once asking for the confirmation dialog, then again with the
 * real verdict. A per-session channel keeps both, in order, instead of racing a single
 * completion.
 */
object InstallEvents {

    const val ACTION = "com.sideload.splitinstaller.INSTALL_STATUS"
    const val EXTRA_SESSION = "session_id"

    private val channels = ConcurrentHashMap<Int, Channel<Intent>>()
    private val pseudoSessions = java.util.concurrent.atomic.AtomicInteger(-1000)

    /**
     * An uninstall has no session of its own, but reports through the same receiver, so it
     * borrows a negative id that cannot collide with a real session.
     */
    fun nextPseudoSession(): Int = pseudoSessions.decrementAndGet()

    fun register(sessionId: Int): Channel<Intent> =
        Channel<Intent>(Channel.BUFFERED).also { channels[sessionId] = it }

    fun unregister(sessionId: Int) {
        channels.remove(sessionId)?.close()
    }

    fun deliver(intent: Intent) {
        // Pseudo sessions are negative, so the "absent" sentinel cannot be -1.
        val id = intent.getIntExtra(EXTRA_SESSION, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?: intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, Int.MIN_VALUE)
        channels[id]?.trySend(intent)
    }

    fun statusMessage(intent: Intent): String {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val label = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
            PackageInstaller.STATUS_FAILURE -> "FAILURE"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
            PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
            else -> "status $status"
        }
        return if (raw.isBlank()) label else "$label: $raw"
    }

    /** Turns installer jargon into the thing you actually have to do about it. */
    fun explain(intent: Intent): String? {
        val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        return when {
            "INSTALL_FAILED_MISSING_SPLIT" in raw ->
                "The device already has a partial install of this package. Uninstall it first, " +
                    "then install the full bundle in one go."
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in raw || "INCONSISTENT_CERTIFICATES" in raw ->
                "Signature does not match the copy already installed. This bundle was re-signed " +
                    "by whoever repacked it, or it is a different build channel. Uninstall the " +
                    "existing copy, or get the bundle from a source that re-hosts it verbatim."
            "INSTALL_FAILED_VERSION_DOWNGRADE" in raw ->
                "Older than what is installed. Enable downgrade in settings, or uninstall first."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" in raw ->
                "Not enough free space for the install itself."
            "INSTALL_FAILED_NO_MATCHING_ABIS" in raw ->
                "The selected APKs carry no native code this device can run."
            "INSTALL_PARSE_FAILED" in raw ->
                "An APK would not parse. The download is probably truncated — re-download it."
            "INSTALL_FAILED_INVALID_APK" in raw ->
                "An APK was rejected as invalid, which usually means a truncated or repacked file."
            else -> null
        }
    }
}

class InstallEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        InstallEvents.deliver(intent)
    }
}
