package com.sideload.splitinstaller.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Work in this process that must not be cut off halfway: installs, and backups being written.
 *
 * A self-update ends the process, so it waits while anything here is running, and can watch
 * [count] to continue as soon as it drops to zero.
 */
object Busy {

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    val any: Boolean get() = _count.value > 0

    fun enter() {
        _count.update { it + 1 }
    }

    fun exit() {
        _count.update { (it - 1).coerceAtLeast(0) }
    }
}
