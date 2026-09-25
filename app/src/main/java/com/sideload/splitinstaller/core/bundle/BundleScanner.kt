package com.sideload.splitinstaller.core.bundle

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.sideload.splitinstaller.core.Prefs
import com.sideload.splitinstaller.core.install.BackendResolver
import java.io.File

data class FoundBundle(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
    val path: String?,
) {
    /** Stable enough to remember across rescans without re-reading the file. */
    val key: String get() = (path ?: uri.toString()) + "|" + size
}

/**
 * Finds bundles already sitting in the download folders.
 *
 * Two routes, because Android 11 closed the direct one: a plain directory walk when
 * All-files access is granted, and a MediaStore query otherwise. The MediaStore route
 * returns `content://` URIs, which the rest of the app reads exactly like files.
 */
object BundleScanner {

    val EXTENSIONS = listOf("xapk", "apkm", "apks", "apk", "apkx", "zip")
    private val PREFERRED = setOf("xapk", "apkm", "apks")

    fun isBundleName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in EXTENSIONS
    }

    fun defaultDirs(context: Context): List<File> {
        val dirs = LinkedHashSet<File>()
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let(dirs::add)
        val root = Environment.getExternalStorageDirectory()
        dirs += File(root, "Download")
        // Where the in-app browser saves bundles and where backups go.
        dirs += File(File(root, "Download"), "SplitSideloader")
        dirs += File(root, "Downloads")
        dirs += File(root, "Documents")
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let(dirs::add)
        Prefs.get(context).extraWatchDirs.forEach { dirs += File(it) }
        return dirs.filter { it.isDirectory }
    }

    /**
     * Folders the watcher reacts to. The app's own folder is left out on purpose: bundles
     * downloaded in-app are opened for installing directly, and backups written there
     * before an update must never be picked up and installed back over it.
     */
    fun watchDirs(context: Context): List<File> {
        val own = File(File(Environment.getExternalStorageDirectory(), "Download"), "SplitSideloader")
        return defaultDirs(context).filterNot { it.absoluteFile == own.absoluteFile }
    }

    fun scan(context: Context, includeZipAndApk: Boolean = true): List<FoundBundle> {
        val found = if (BackendResolver.hasAllFilesAccess(context)) {
            scanFiles(context)
        } else {
            scanMediaStore(context)
        }
        return found
            .filter { includeZipAndApk || it.name.substringAfterLast('.', "").lowercase() in PREFERRED }
            .distinctBy { it.key }
            .sortedByDescending { it.modified }
    }

    private fun scanFiles(context: Context): List<FoundBundle> =
        defaultDirs(context).flatMap { dir ->
            dir.listFiles().orEmpty().asSequence()
                .filter { it.isFile && isBundleName(it.name) && it.length() > 0 }
                .map { FoundBundle(it.toUri(), it.name, it.length(), it.lastModified(), it.absolutePath) }
                .toList()
        }

    private fun scanMediaStore(context: Context): List<FoundBundle> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        val out = ArrayList<FoundBundle>()
        runCatching {
            context.contentResolver.query(
                collection, projection, null, null,
                MediaStore.Downloads.DATE_MODIFIED + " DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!isBundleName(name)) continue
                    val size = c.getLong(sizeCol)
                    if (size <= 0) continue
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(idCol))
                    out += FoundBundle(
                        uri = uri,
                        name = name,
                        size = size,
                        modified = c.getLong(dateCol) * 1000L,
                        path = c.getString(pathCol)?.plus(name),
                    )
                }
            }
        }
        return out
    }
}
