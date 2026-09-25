package com.sideload.splitinstaller.core.install

import rikka.shizuku.Shizuku
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
    val text: String get() = (out + "\n" + err).trim()
}

/**
 * A way to run a command as something other than this app.
 *
 * Both implementations end up in the same place: `pm install-create / install-write /
 * install-commit`, which is exactly the sequence `adb install-multiple` performs. That is
 * the point — it is the only path that hands the package manager every split in one
 * transaction, which is what makes it rescan and set `primaryCpuAbi`.
 */
interface Shell {
    val id: String

    fun run(
        command: String,
        timeoutSeconds: Long = 600,
        maxOutput: Int = 64_000,
        stdin: ((OutputStream) -> Unit)? = null,
    ): ShellResult
}

private fun drive(
    process: Process,
    timeoutSeconds: Long,
    maxOutput: Int,
    stdin: ((OutputStream) -> Unit)?,
): ShellResult {
    val out = StringBuilder()
    val err = StringBuilder()

    // stdout and stderr must be drained while we write, or a full pipe buffer deadlocks us.
    val outReader = Thread {
        runCatching {
            process.inputStream.bufferedReader().forEachLine { if (out.length < maxOutput) out.appendLine(it) }
        }
    }
    val errReader = Thread {
        runCatching {
            process.errorStream.bufferedReader().forEachLine { if (err.length < 64_000) err.appendLine(it) }
        }
    }
    outReader.isDaemon = true
    errReader.isDaemon = true
    outReader.start()
    errReader.start()

    var writeError: Throwable? = null
    try {
        process.outputStream.use { os -> stdin?.invoke(os) }
    } catch (t: Throwable) {
        writeError = t
    }

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return ShellResult(-1, out.toString(), "timed out after ${timeoutSeconds}s")
    }
    outReader.join(5_000)
    errReader.join(5_000)

    val code = process.exitValue()
    if (writeError != null && code == 0) {
        return ShellResult(-1, out.toString(), "failed writing to the command: " + writeError.message)
    }
    return ShellResult(code, out.toString().trim(), err.toString().trim())
}

object RootShell : Shell {
    override val id = "root"

    override fun run(
        command: String,
        timeoutSeconds: Long,
        maxOutput: Int,
        stdin: ((OutputStream) -> Unit)?,
    ): ShellResult = try {
        drive(ProcessBuilder("su", "-c", command).start(), timeoutSeconds, maxOutput, stdin)
    } catch (t: Throwable) {
        ShellResult(-1, "", "su unavailable: " + (t.message ?: t.javaClass.simpleName))
    }

    /** Present *and* granted. A prompt the user dismisses shows up here as unavailable. */
    fun probe(): ShellResult = run("id", timeoutSeconds = 12)
}

object ShizukuShell : Shell {
    override val id = "shizuku"

    override fun run(
        command: String,
        timeoutSeconds: Long,
        maxOutput: Int,
        stdin: ((OutputStream) -> Unit)?,
    ): ShellResult = try {
        drive(newProcess(arrayOf("sh", "-c", command)), timeoutSeconds, maxOutput, stdin)
    } catch (t: Throwable) {
        ShellResult(-1, "", "shizuku unavailable: " + (t.message ?: t.javaClass.simpleName))
    }

    /**
     * `Shizuku.newProcess` is hidden in the API artifact but is the documented way to get a
     * shell. Reflection keeps this compiling across Shizuku releases.
     */
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** uid 0 means Shizuku was started by root, 2000 means it was started over ADB. */
    fun serviceUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun versionName(): String = runCatching {
        "v" + Shizuku.getVersion() + " (uid " + Shizuku.getUid() + ")"
    }.getOrDefault("")
}

/** Single-quote a value for `sh -c`, so spaces and apostrophes survive. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
