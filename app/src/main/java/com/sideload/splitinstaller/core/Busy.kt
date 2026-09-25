package com.sideload.splitinstaller.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Work in this process that must not be cut off halfway: installs, and backups being written.
 *
 * A self-update ends the process, so it waits while anything here is running.
 */
object Busy {

    private val count = AtomicInteger(0)

    val any: Boolean get() = count.get() > 0

    fun enter() {
        count.incrementAndGet()
    }

    fun exit() {
        count.decrementAndGet()
    }
}
