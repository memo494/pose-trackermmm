package com.memo494.posetracker.modelimport

import android.os.Looper

/**
 * The opposite of `OverlayWindow.checkMainThread`: the model import code reads, writes and hashes files, so it
 * must never run on the main thread (AGENTS.md hard rule 2).
 */
internal object WorkerThreadCheck {
    /** Throws [IllegalStateException] when called on the main looper's thread. */
    fun require() {
        val main = Looper.getMainLooper()
        // main is null only in JVM unit tests (stubbed Android); there is no main thread to protect then.
        check(main == null || Looper.myLooper() !== main) { MESSAGE }
    }

    /** The decision itself, for tests: throws [IllegalStateException] when [onMainThread] is true. */
    fun require(onMainThread: Boolean) {
        check(!onMainThread) { MESSAGE }
    }

    private const val MESSAGE = "Model import storage and import must run off the main thread"
}
