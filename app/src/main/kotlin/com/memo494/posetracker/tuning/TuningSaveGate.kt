package com.memo494.posetracker.tuning

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Orders the saves of the tuning file. A save takes a ticket (in the order it was asked for, on the caller's thread); the
 * ones that reach the disk later than a newer one are skipped, so the file always ends with the newest value however the
 * worker threads are scheduled. A read waits (a bounded time) for the saves that were asked for before it. One process-wide
 * [PROCESS] gate is shared by every store and every activity, so a recreated activity cannot read a file that an older
 * save is still writing. [lock] is the lock for the file work itself.
 */
internal class TuningSaveGate {
    /** Held while the file is read or written. */
    val lock = ReentrantLock()
    private val settledChanged = lock.newCondition()
    private val reserved = AtomicLong(0)

    /** The newest ticket that was written, failed or skipped; guarded by [lock]. */
    private var settled = 0L

    /** A ticket for a save that is about to be asked for; any thread, no file work. */
    fun reserve(): Long = reserved.incrementAndGet()

    /** Call with [lock] held: `true` when a newer ticket than [ticket] was already settled, so this save is out of date. */
    fun isStale(ticket: Long): Boolean = ticket < settled

    /** Call with [lock] held, when the save of [ticket] ended (written, failed or skipped). */
    fun settle(ticket: Long) {
        if (ticket > settled) settled = ticket
        settledChanged.signalAll()
    }

    /** Call with [lock] held: waits up to [maxWaitMs] until every ticket reserved so far has been settled (or a newer one has). */
    fun awaitPending(maxWaitMs: Long) {
        val target = reserved.get()
        var left = TimeUnit.MILLISECONDS.toNanos(maxWaitMs)
        while (settled < target && left > 0) left = settledChanged.awaitNanos(left)
    }

    companion object {
        /** The gate of the app process. */
        val PROCESS = TuningSaveGate()
    }
}
