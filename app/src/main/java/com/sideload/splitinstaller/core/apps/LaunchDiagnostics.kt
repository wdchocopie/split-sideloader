package com.sideload.splitinstaller.core.apps

import android.content.Context
import com.sideload.splitinstaller.core.install.Shell
import com.sideload.splitinstaller.core.install.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LaunchProblem {
    /** `dlopen failed` / `UnsatisfiedLinkError`: the install is missing native code. */
    MISSING_NATIVE_LIB,

    /** An anti-cheat module refused to initialise — root, hooks, or an emulator. */
    ANTI_CHEAT,

    /** A native crash (SIGSEGV and friends) after the libraries did load. */
    NATIVE_CRASH,

    /** An ordinary uncaught Java/Kotlin exception. */
    JAVA_CRASH,

    /** Nothing suspicious in the log during the launch window. */
    NONE_FOUND,

    NO_LAUNCHER,
    SHELL_FAILED,
}

data class LaunchDiagnosis(
    val problem: LaunchProblem,
    val lines: List<String>,
    val detail: String? = null,
)

/**
 * The usual `adb logcat` troubleshooting step, done from the phone.
 *
 * Clears the log, cold-starts the app, waits, and reads back only warnings and errors.
 * That is enough to tell a missing library from anti-cheat refusing to start — two problems
 * with nothing in common except that the app closes immediately.
 *
 * Needs a shell: an ordinary app may only read its own log lines.
 */
object LaunchDiagnostics {

    private val MISSING_LIB = listOf(
        "dlopen failed", "UnsatisfiedLinkError", "couldn't find \"lib", "library \"lib",
        "Failed to load libmain", "failed to load libil2cpp",
    )
    private val ANTI_CHEAT = listOf(
        "anogs", "tersafe", "libtprt", "AntiCheat", "anti-cheat", "ACE-", "libace",
        "mhyprot", "EasyAntiCheat", "xigncode", "NProtect", "gameguard",
    )
    private val NATIVE_CRASH = listOf("Fatal signal", "SIGSEGV", "SIGABRT", "tombstone", "backtrace:")
    private val RELEVANT = MISSING_LIB + ANTI_CHEAT + NATIVE_CRASH + listOf(
        "FATAL EXCEPTION", "AndroidRuntime", "UnityMain", "libmain", "il2cpp", "linker",
        "ActivityManager: Process", "has died", "Force finishing",
    )

    suspend fun run(
        context: Context,
        packageName: String,
        shell: Shell,
        waitSeconds: Int = 10,
    ): LaunchDiagnosis = withContext(Dispatchers.IO) {
        val component = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.component?.flattenToShortString()
            ?: return@withContext LaunchDiagnosis(LaunchProblem.NO_LAUNCHER, emptyList())

        val pkg = shellQuote(packageName)
        val script = buildString {
            append("am force-stop ").append(pkg).append("; ")
            append("logcat -c; ")
            append("am start -n ").append(shellQuote(component)).append(" >/dev/null 2>&1; ")
            append("sleep ").append(waitSeconds).append("; ")
            append("logcat -d -v time -b main,system,crash '*:W' | tail -n 4000")
        }
        val result = shell.run(script, timeoutSeconds = waitSeconds + 40L, maxOutput = 1_500_000)
        if (!result.ok && result.out.isBlank()) {
            return@withContext LaunchDiagnosis(LaunchProblem.SHELL_FAILED, emptyList(), result.text)
        }

        val all = result.out.lines()
        val lines = relevantLines(all, packageName)
        val text = lines.joinToString("\n")

        val problem = when {
            MISSING_LIB.any { text.contains(it, ignoreCase = true) } -> LaunchProblem.MISSING_NATIVE_LIB
            ANTI_CHEAT.any { text.contains(it, ignoreCase = true) } -> LaunchProblem.ANTI_CHEAT
            NATIVE_CRASH.any { text.contains(it, ignoreCase = true) } -> LaunchProblem.NATIVE_CRASH
            text.contains("FATAL EXCEPTION") -> LaunchProblem.JAVA_CRASH
            else -> LaunchProblem.NONE_FOUND
        }
        LaunchDiagnosis(problem, lines.takeLast(200))
    }

    /** Keyword hits, plus the stack trace that follows an uncaught exception. */
    private fun relevantLines(all: List<String>, packageName: String): List<String> {
        val out = ArrayList<String>()
        var trailing = 0
        for (line in all) {
            val hit = line.contains(packageName) || RELEVANT.any { line.contains(it, ignoreCase = true) }
            when {
                hit -> {
                    out += line
                    if (line.contains("FATAL EXCEPTION") || line.contains("Fatal signal")) trailing = 40
                }
                trailing > 0 -> {
                    out += line
                    trailing--
                }
            }
        }
        return out
    }
}
