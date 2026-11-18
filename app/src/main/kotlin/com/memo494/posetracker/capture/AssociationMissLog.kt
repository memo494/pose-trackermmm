package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tracking.AssociationMiss
import com.memo494.posetracker.core.tracking.AssociationMissReason
import java.util.Locale

/**
 * Writes one log line per association miss (a track born next to a LOST one, M4e), at most [perMinute] lines per
 * window of [TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS]; the tracker's counters count every miss, written or not. The
 * line is `track: miss id=L -> new id=N lostMs=… score=… stage=high|low gateChi2=… iou=… headRatio=… hRatio=…
 * cost=… reason=gate|cost|taken`, numbers with two decimals (the lost time whole milliseconds), `n/a` when a value
 * is not a number. Capture thread only; allocates only when it writes a line (a rare event).
 *
 * @param perMinute most lines per window; 0 writes none.
 * @param log receives the line.
 */
internal class AssociationMissLog(
    private val perMinute: Int,
    private val log: (String) -> Unit,
) {
    private var started = false
    private var windowStartNs = 0L
    private var linesInWindow = 0

    /** Writes the line for [miss] when the window still has room; [nowNs] is the `System.nanoTime()` clock. */
    fun report(
        miss: AssociationMiss,
        nowNs: Long,
    ) {
        if (perMinute <= 0) return
        if (!started || nowNs - windowStartNs >= TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS) {
            started = true
            windowStartNs = nowNs
            linesInWindow = 0
        }
        if (linesInWindow >= perMinute) return
        linesInWindow++
        log(format(miss))
    }

    companion object {
        /** The log line of [miss]. */
        fun format(miss: AssociationMiss): String =
            "track: miss id=${miss.lostId} -> new id=${miss.newId} lostMs=${number(miss.lostMs, WHOLE)} " +
                "score=${number(miss.score, TWO_DECIMALS)} stage=${if (miss.highStage) "high" else "low"} " +
                "gateChi2=${number(miss.gateChi2, TWO_DECIMALS)} iou=${number(miss.iou, TWO_DECIMALS)} " +
                "headRatio=${number(miss.headRatio, TWO_DECIMALS)} hRatio=${number(miss.hRatio, TWO_DECIMALS)} " +
                "cost=${number(miss.cost, TWO_DECIMALS)} reason=${reasonText(miss.reason)}"

        private const val WHOLE = "%.0f"
        private const val TWO_DECIMALS = "%.2f"

        private fun number(
            value: Float,
            pattern: String,
        ): String = if (value.isFinite()) String.format(Locale.ROOT, pattern, value) else "n/a"

        private fun reasonText(reason: AssociationMissReason): String =
            when (reason) {
                AssociationMissReason.GATE -> "gate"
                AssociationMissReason.COST -> "cost"
                AssociationMissReason.TAKEN -> "taken"
            }
    }
}
