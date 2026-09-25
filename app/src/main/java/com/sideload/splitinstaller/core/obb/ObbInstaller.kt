package com.sideload.splitinstaller.core.obb

import android.content.Context
import android.os.Environment
import com.sideload.splitinstaller.core.bundle.ObbFile
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.install.BackendResolver
import com.sideload.splitinstaller.core.install.InstallEvent
import com.sideload.splitinstaller.core.install.Shell
import com.sideload.splitinstaller.core.install.shellQuote
import com.sideload.splitinstaller.core.zip.ZipReader
import java.io.File

/**
 * Places expansion files under `Android/obb/<pkg>/`.
 *
 * Since Android 11 that directory is off limits to ordinary apps, but deliberately still
 * reachable to holders of All-files access — precisely so an installer can put OBBs where
 * the game will look for them. A shell backend is the fallback when that access is refused.
 */
object ObbInstaller {

    fun install(
        context: Context,
        zip: ZipReader,
        obbs: List<ObbFile>,
        shell: Shell?,
        emit: (InstallEvent) -> Unit,
    ) {
        if (obbs.isEmpty()) return
        val root = Environment.getExternalStorageDirectory()
        val direct = BackendResolver.hasAllFilesAccess(context)

        emit(InstallEvent.Log(
            Severity.INFO,
            "installing ${obbs.size} expansion file(s) into ${root.absolutePath}/Android/obb",
        ))

        if (!direct && shell == null) {
            emit(InstallEvent.Log(
                Severity.ERROR,
                "cannot write Android/obb: grant All files access, or use a shell backend. " +
                    "The app will install but start with no expansion data.",
            ))
            return
        }

        for (obb in obbs) {
            val entry = zip[obb.entryName]
            if (entry == null) {
                emit(InstallEvent.Log(Severity.WARN, "${obb.fileName} is not in the bundle any more"))
                continue
            }
            val target = File(root, obb.targetPath)
            val ok = if (direct) writeDirect(zip, entry, target, emit) else writeViaShell(zip, entry, target, shell!!, emit)
            if (ok) {
                emit(InstallEvent.Log(Severity.INFO, "placed ${obb.fileName} (${fmt(obb.size)})"))
            }
        }
    }

    private fun writeDirect(
        zip: ZipReader,
        entry: com.sideload.splitinstaller.core.zip.ZipEntryInfo,
        target: File,
        emit: (InstallEvent) -> Unit,
    ): Boolean = try {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        zip.open(entry).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (tmp.length() != entry.size) {
            tmp.delete()
            throw java.io.IOException("wrote ${tmp.length()} of ${entry.size} bytes")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw java.io.IOException("could not move it into place")
        true
    } catch (t: Throwable) {
        emit(InstallEvent.Log(Severity.ERROR, "${target.name}: ${t.message}"))
        false
    }

    private fun writeViaShell(
        zip: ZipReader,
        entry: com.sideload.splitinstaller.core.zip.ZipEntryInfo,
        target: File,
        shell: Shell,
        emit: (InstallEvent) -> Unit,
    ): Boolean {
        val dir = target.parent ?: return false
        shell.run("mkdir -p " + shellQuote(dir), timeoutSeconds = 30)
        val result = shell.run("cat > " + shellQuote(target.absolutePath), timeoutSeconds = 3600) { out ->
            zip.open(entry).use { it.copyTo(out, 1 shl 20) }
        }
        if (!result.ok) {
            emit(InstallEvent.Log(Severity.ERROR, "${target.name}: ${result.text}"))
            return false
        }
        // Written as shell or root; make sure the game itself can read it.
        shell.run("chmod 0644 " + shellQuote(target.absolutePath), timeoutSeconds = 30)
        return true
    }

    private fun fmt(bytes: Long) = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
        else -> "$bytes B"
    }
}
