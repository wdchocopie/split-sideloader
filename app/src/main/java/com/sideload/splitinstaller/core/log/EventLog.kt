package com.sideload.splitinstaller.core.log

import android.util.Log
import com.sideload.splitinstaller.core.bundle.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(
    val at: Long,
    val severity: Severity,
    val message: String,
) {
    fun format(): String = TIME.format(Date(at)) + "  " + tag() + "  " + message

    private fun tag() = when (severity) {
        Severity.INFO -> "[..]"
        Severity.WARN -> "[!!]"
        Severity.ERROR -> "[XX]"
    }

    private companion object {
        val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/**
 * One shared transcript of everything the app did, so a failed install can be read back
 * afterwards instead of being reconstructed from memory.
 */
object EventLog {

    private const val TAG = "SplitSideloader"
    private const val LIMIT = 2000

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    fun add(severity: Severity, message: String) {
        Log.println(
            when (severity) {
                Severity.INFO -> Log.INFO
                Severity.WARN -> Log.WARN
                Severity.ERROR -> Log.ERROR
            },
            TAG,
            message,
        )
        _lines.update { current ->
            val next = current + LogLine(System.currentTimeMillis(), severity, message)
            if (next.size > LIMIT) next.takeLast(LIMIT) else next
        }
    }

    fun info(message: String) = add(Severity.INFO, message)
    fun warn(message: String) = add(Severity.WARN, message)
    fun error(message: String) = add(Severity.ERROR, message)

    fun rule(title: String) {
        add(Severity.INFO, "──── " + title + " ────")
    }

    fun clear() = _lines.update { emptyList() }

    fun dump(): String = _lines.value.joinToString("\n") { it.format() }
}
