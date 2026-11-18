package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tracking.AssociationMiss
import com.memo494.posetracker.core.tracking.AssociationMissReason
import com.memo494.posetracker.core.tracking.RemovalCause
import com.memo494.posetracker.core.tracking.TrackState
import java.util.Locale

/**
 * Writes one log line per association miss (a track born next to a LOST one, M4e), at most [perMinute] lines per
 * window of [TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS]; the tracker's counters count every miss, written or not. The
 * line is `track: miss id=L -> new id=N lostMs=… score=… stage=high|low gateChi2=… iou=… headRatio=… hRatio=…
 * cost=… reason=gate|cost|taken`, numbers with two decimals (the lost time whole milliseconds), `n/a` when a value
 * is not a number. A birth next to a track that is not LOST ends with `reason=live state=confirmed|tentative
 * detIou=…`, one next to a track removed in the same frame with `reason=removed cause=lostTime|exit|other`. When a
 * window ends after lines were dropped, the first report of the next window writes `track: miss suppressed N` first. Capture thread only; allocates only when it writes a line (a rare event).
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
    private var suppressedInWindow = 0

    /** Writes the line for [miss] when the window still has room; [nowNs] is the `System.nanoTime()` clock. */
    fun report(
        miss: AssociationMiss,
        nowNs: Long,
    ) {
        if (perMinute <= 0) return
        if (!started || nowNs - windowStartNs >= TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS) {
            val suppressed = suppressedInWindow
            started = true
            windowStartNs = nowNs
            linesInWindow = 0
            suppressedInWindow = 0
            if (suppressed > 0) log("track: miss suppressed $suppressed")
        }
        if (linesInWindow >= perMinute) {
            suppressedInWindow++
            return
        }
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
                "cost=${number(miss.cost, TWO_DECIMALS)} reason=${reasonText(miss.reason)}${extra(miss)}"

        private const val WHOLE = "%.0f"
        private const val TWO_DECIMALS = "%.2f"

        private fun number(
            value: Float,
            pattern: String,
        ): String = if (value.isFinite()) String.format(Locale.ROOT, pattern, value) else "n/a"

        private fun extra(miss: AssociationMiss): String =
            when (miss.reason) {
                AssociationMissReason.LIVE ->
                    " state=${if (miss.trackState == TrackState.TENTATIVE) "tentative" else "confirmed"} " +
                        "detIou=${number(miss.detIou, TWO_DECIMALS)}"
                AssociationMissReason.REMOVED -> " cause=${causeText(miss.cause)}"
                else -> ""
            }

        private fun causeText(cause: RemovalCause): String =
            when (cause) {
                RemovalCause.LOST_TIME -> "lostTime"
                RemovalCause.EXIT -> "exit"
                RemovalCause.OTHER -> "other"
                RemovalCause.NONE -> "none"
            }

        private fun reasonText(reason: AssociationMissReason): String =
            when (reason) {
                AssociationMissReason.GATE -> "gate"
                AssociationMissReason.COST -> "cost"
                AssociationMissReason.TAKEN -> "taken"
                AssociationMissReason.LIVE -> "live"
                AssociationMissReason.REMOVED -> "removed"
            }
    }
}
