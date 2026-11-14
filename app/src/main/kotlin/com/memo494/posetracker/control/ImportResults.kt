package com.memo494.posetracker.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The newest finished import, shared by the whole process (M0c review R1-2). An import is not tied to the
 * activity that started it: the work finishes on a worker thread even when the activity is recreated (rotation),
 * so the result goes through this holder and every activity collects it; the one that is alive when the import
 * ends, or the next one to start, learns the result and clears its `importing` flag.
 */
internal object ImportResults {
    /**
     * One finished import. [sequence] grows with every [publish]; a collector remembers the last one it handled
     * (a `StateFlow` replays its newest value to a new collector). [summary] is `null` only before the first import.
     */
    data class Result(
        val sequence: Long,
        val summary: ImportSummary?,
    )

    private val flow = MutableStateFlow(Result(0L, null))

    /** The newest result; never `null`. */
    val latest: StateFlow<Result> = flow.asStateFlow()

    /** Publishes [summary] as the newest result; safe to call from any thread. */
    fun publish(summary: ImportSummary) {
        flow.update { Result(it.sequence + 1, summary) }
    }
}
