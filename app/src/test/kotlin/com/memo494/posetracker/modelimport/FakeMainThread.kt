package com.memo494.posetracker.modelimport

/** A stand-in for the main-thread check of the store and the importer: [onMainThread] decides what [check] does. */
internal class FakeMainThread(
    var onMainThread: Boolean = false,
) {
    /** The injected check: throws [IllegalStateException] while [onMainThread] is `true`. */
    fun check() = WorkerThreadCheck.require(onMainThread)
}
