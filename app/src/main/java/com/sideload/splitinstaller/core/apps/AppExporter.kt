package com.sideload.splitinstaller.core.apps

import android.content.ContentValues
import android.content.Context
import com.sideload.splitinstaller.core.Busy
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.verify.InstallVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(val displayPath: String, val uri: Uri, val size: Long)

/**
 * Packs an installed app back into a `.apks` bundle.
 *
 * Sideloaded apps cannot self-update, and a new build sometimes turns out worse than the
 * old one. Keeping the last known-good install as a bundle is the cheapest insurance: the
 * APKs in `/data/app` are world-readable, so this needs no special access to read them.
 *
 * APKs are stored uncompressed, like every bundle format does, so this app (and SAI)
 * can reopen the result without inflating anything.
 */
object AppExporter {

    const val FOLDER = "SplitSideloader"

    /** Counted as busy work: a self-update must not end the process halfway through a backup. */
    suspend fun export(
        context: Context,
        packageName: String,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        Busy.enter()
        try {
            return writeExport(context, packageName, onProgress)
        } finally {
            Busy.exit()
        }
    }

    private suspend fun writeExport(
        context: Context,
        packageName: String,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, 0)
        val ai = info.applicationInfo ?: throw IOException("no application info for $packageName")
        val label = pm.getApplicationLabel(ai).toString()
        val splitNames = info.splitNames?.toList().orEmpty()

        val files = buildList {
            add("base.apk" to File(ai.sourceDir))
            ai.splitSourceDirs.orEmpty().forEachIndexed { i, path ->
                val name = splitNames.getOrNull(i) ?: File(path).nameWithoutExtension
                add("split_$name.apk" to File(path))
            }
        }
        files.forEach { (_, f) -> if (!f.canRead()) throw IOException("cannot read ${f.path}") }

        val versionName = info.versionName ?: "?"
        val versionCode = InstallVerifier.versionCodeOf(info)
        val fileName = sanitize("${label}_${versionName}_$versionCode") + ".apks"

        // Two passes per APK: a STORED entry needs its CRC before its bytes.
        val total = files.sumOf { it.second.length() } * 2
        var done = 0L
        val report: (Long) -> Unit = { n ->
            done += n
            if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
        }

        val sink = openDestination(context, fileName)
        try {
            ZipOutputStream(sink.stream.buffered(1 shl 20)).use { zip ->
                for ((name, file) in files) {
                    val crc = CRC32()
                    FileInputStream(file).use { input -> pump(input, null, crc, report) }
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = file.length()
                        compressedSize = file.length()
                        this.crc = crc.value
                    }
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input -> pump(input, zip, null, report) }
                    zip.closeEntry()
                }

                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(metadata(packageName, label, versionName, versionCode, splitNames, info).toByteArray())
                zip.closeEntry()

                iconPng(context, packageName)?.let { png ->
                    zip.putNextEntry(ZipEntry("icon.png"))
                    zip.write(png)
                    zip.closeEntry()
                }
            }
            sink.commit()
        } catch (t: Throwable) {
            sink.abort()
            throw t
        }
        onProgress(1f)
        ExportResult(sink.displayPath, sink.uri, files.sumOf { it.second.length() })
    }

    private fun pump(input: FileInputStream, out: OutputStream?, crc: CRC32?, report: (Long) -> Unit) {
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out?.write(buf, 0, n)
            crc?.update(buf, 0, n)
            report(n.toLong())
        }
    }

    /** XAPK-style metadata, so any tool that understands `manifest.json` can read it back. */
    private fun metadata(
        pkg: String,
        label: String,
        versionName: String,
        versionCode: Long,
        splits: List<String>,
        info: android.content.pm.PackageInfo,
    ): String = JSONObject()
        .put("xapk_version", 2)
        .put("package_name", pkg)
        .put("name", label)
        .put("version_code", versionCode.toString())
        .put("version_name", versionName)
        .put("min_sdk_version", (info.applicationInfo?.minSdkVersion ?: 0).toString())
        .put("target_sdk_version", (info.applicationInfo?.targetSdkVersion ?: 0).toString())
        .put(
            "split_apks",
            JSONArray().apply {
                put(JSONObject().put("file", "base.apk").put("id", "base"))
                splits.forEach { put(JSONObject().put("file", "split_$it.apk").put("id", it)) }
            },
        )
        .put("exported_by", "Split Sideloader")
        .toString(2)

    private fun iconPng(context: Context, pkg: String): ByteArray? = runCatching {
        val bitmap = context.packageManager.getApplicationIcon(pkg).toBitmap(192, 192, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(120)

    // ---- where the file goes ------------------------------------------------

    private class Sink(
        val stream: OutputStream,
        val uri: Uri,
        val displayPath: String,
        val commit: () -> Unit,
        val abort: () -> Unit,
    )

    /**
     * Download/SplitSideloader/, written directly when shared storage is open to us and
     * through MediaStore otherwise — the latter needs no permission at all on Android 10+.
     */
    private fun openDestination(context: Context, fileName: String): Sink {
        if (BackendResolver.hasAllFilesAccess(context)) {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER,
            ).apply { mkdirs() }
            val target = uniqueFile(dir, fileName)
            val part = File(dir, target.name + ".part")
            return Sink(
                stream = part.outputStream(),
                uri = target.toUri(),
                displayPath = target.absolutePath,
                commit = { if (!part.renameTo(target)) throw IOException("could not finish ${target.name}") },
                abort = { part.delete() },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused to create $fileName")
            val stream = resolver.openOutputStream(uri) ?: throw IOException("could not open $uri")
            return Sink(
                stream = stream,
                uri = uri,
                displayPath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + fileName,
                commit = {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                },
                abort = { resolver.delete(uri, null, null) },
            )
        }
        throw IOException("storage permission is needed to save the backup")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        while (f.exists()) {
            f = File(dir, name.removeSuffix(".apks") + " ($i).apks")
            i++
        }
        return f
    }
}
