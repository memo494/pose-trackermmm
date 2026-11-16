package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection

/**
 * Drops duplicate detections of one person before association (Consult C1 decision 6). Detections are taken
 * from best to worst score (equal scores: lower index first); a detection is dropped when its box overlaps an
 * already kept detection with an IoU of at least [TrackerConfig.dedupeIou] (greedy suppression, so a dropped
 * detection never suppresses another one). Two people standing side by side (IoU about 0.3) are both kept.
 * A box without area never counts as a duplicate. `O(n^2)` on at most [maxDetections]; all scratch arrays are
 * allocated here, [dedupe] allocates nothing.
 */
class DetectionDedupe(
    val maxDetections: Int,
    private val config: TrackerConfig,
) {
    private val rankOrder = IntArray(maxDetections)
    private val isKept = BooleanArray(maxDetections)

    init {
        require(maxDetections >= 1) { "maxDetections $maxDetections must be at least 1" }
    }

    /**
     * Writes the indices of the kept detections of [detections], in input order, into the first entries of
     * [keptOut] and returns how many were kept. Throws `IllegalArgumentException` when there are more than
     * [maxDetections] detections or [keptOut] is shorter than the detection list.
     */
    fun dedupe(
        detections: List<PoseDetection>,
        keptOut: IntArray,
    ): Int = dedupe(detections, detections.size, keptOut)

    /**
     * Like the two-argument overload, but looks only at the first [count] entries of [detections] (the tracker
     * uses it to cap an oversized frame without copying the list). Throws `IllegalArgumentException` when
     * [count] is negative, above [maxDetections] or above the list size, or [keptOut] is shorter than [count].
     */
    fun dedupe(
        detections: List<PoseDetection>,
        count: Int,
        keptOut: IntArray,
    ): Int {
        require(count in 0..detections.size) { "count $count outside 0..${detections.size}" }
        require(count <= maxDetections) { "$count detections exceed the capacity $maxDetections" }
        require(keptOut.size >= count) { "keptOut has ${keptOut.size} entries for $count detections" }
        rankByScore(detections, count)
        for (rank in 0 until count) {
            val index = rankOrder[rank]
            isKept[index] = !overlapsAKeptOne(detections, index, rank)
        }
        var kept = 0
        for (index in 0 until count) {
            if (isKept[index]) {
                keptOut[kept] = index
                kept++
            }
        }
        return kept
    }

    /** Insertion sort of the indices by score, best first; equal scores keep their input order. */
    private fun rankByScore(
        detections: List<PoseDetection>,
        count: Int,
    ) {
        for (index in 0 until count) {
            var slot = index
            while (slot > 0 && rankKey(detections[index]) > rankKey(detections[rankOrder[slot - 1]])) {
                rankOrder[slot] = rankOrder[slot - 1]
                slot--
            }
            rankOrder[slot] = index
        }
    }

    /** The score used for ranking: a non-finite score (`NaN`, `+inf`, `-inf`) ranks last, so it can never suppress a real one. */
    private fun rankKey(detection: PoseDetection): Float = if (detection.score.isFinite()) detection.score else Float.NEGATIVE_INFINITY

    private fun overlapsAKeptOne(
        detections: List<PoseDetection>,
        index: Int,
        rank: Int,
    ): Boolean {
        val candidate = detections[index]
        for (better in 0 until rank) {
            val other = rankOrder[better]
            if (isKept[other] && iouOf(candidate, detections[other]) >= config.dedupeIou) return true
        }
        return false
    }

    private fun iouOf(
        a: PoseDetection,
        b: PoseDetection,
    ): Float =
        BoxOverlap.iou(
            a.boxLeftImg,
            a.boxTopImg,
            a.boxRightImg,
            a.boxBottomImg,
            b.boxLeftImg,
            b.boxTopImg,
            b.boxRightImg,
            b.boxBottomImg,
        )
}
