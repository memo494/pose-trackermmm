package com.memo494.posetracker.core.decode

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tracking.BoxOverlap

/**
 * The part of the raw-output decoders ([RawPoseDecoder], [RawDetectDecoder]) that does not depend on what a row
 * looks like: collects the candidate boxes that passed the score threshold, decides once per frame whether the
 * coordinates are normalized or pixels, and runs class-agnostic greedy NMS best score first.
 *
 * Use per frame: [begin]; [offerCenterBox] or [offerCornerBox] for every row that passed the threshold; [settle];
 * then [nextKept] until it returns -1. The kept detections are indexed `0 until count`, best first; read them with
 * [rowAt], [scoreOf] and the box accessors, valid until the next [begin].
 *
 * **Coordinates.** A candidate whose box, as left, top, right, bottom, has no coordinate with an absolute value above
 * [TrackerConfig.RAW_NORMALIZED_MAX_COORDINATE] "looks normalized". The frame is read as normalized when MORE
 * candidates look normalized than look like pixels, otherwise as pixels (a tie, or no candidate, is pixels: a
 * normalized row misread as pixels is a box of at most 2 px that later stages drop, whereas a pixel row misread as
 * normalized would become a huge box). In a normalized frame the boxes are multiplied by the input width and height
 * and a candidate that looks like pixels is dropped as an outlier; in a pixel frame every candidate is kept as it
 * is. Boxes are model pixels (Mdl space) afterwards; [scaleX] and [scaleY] are the factors a row's other
 * coordinates (keypoints) need.
 *
 * Allocation-free after construction: every buffer is sized once from [rows]. Not thread-safe.
 *
 * @param rows number of candidate rows `N` of the output, unitless, at least 1.
 * @param inputWidthMdl width of the model input, model pixels (Mdl).
 * @param inputHeightMdl height of the model input, model pixels (Mdl).
 * @param nmsIou a lower-scored box with an IoU above this (unitless) to a kept box is dropped.
 */
internal class RawBoxCandidates(
    private val rows: Int,
    private val inputWidthMdl: Int,
    private val inputHeightMdl: Int,
    private val nmsIou: Float,
) {
    /** Most detections [nextKept] returns per frame: [rows] capped at [TrackerConfig.RAW_MAX_DETECTIONS]. */
    val capacity: Int = minOf(rows, TrackerConfig.RAW_MAX_DETECTIONS)

    // Per candidate row (indexed by row): box in model pixels, filled only for rows that were offered.
    private val left = FloatArray(rows)
    private val top = FloatArray(rows)
    private val right = FloatArray(rows)
    private val bottom = FloatArray(rows)
    private val score = FloatArray(rows)
    private val looksNormalized = BooleanArray(rows)
    private var frameNormalized = false
    private var normalizedVotes = 0
    private val heap = IntArray(rows)
    private var heapSize = 0

    // Per kept detection, best first.
    private val keptRow = IntArray(capacity)

    /** Detections kept so far in this frame; 0 after [begin]. */
    var count: Int = 0
        private set

    /** Factor from a normalized row's x values to model pixels: the input width in a normalized frame, else 1. Valid after [settle]. */
    val scaleX: Float
        get() = if (frameNormalized) inputWidthMdl.toFloat() else 1f

    /** Factor from a normalized row's y values to model pixels: the input height in a normalized frame, else 1. Valid after [settle]. */
    val scaleY: Float
        get() = if (frameNormalized) inputHeightMdl.toFloat() else 1f

    /** Starts a frame: forgets every candidate and kept detection. */
    fun begin() {
        count = 0
        heapSize = 0
        normalizedVotes = 0
        frameNormalized = false
    }

    /**
     * Offers [row] with its box as centre `cx, cy` and size `w, h` (any unit; see the class text). `false` (nothing
     * stored) when the box is not a finite positive rectangle.
     */
    fun offerCenterBox(
        row: Int,
        rowScore: Float,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
    ): Boolean {
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && w > 0f && h > 0f)) return false
        return store(row, rowScore, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** Offers [row] with its box as left, top, right, bottom; `false` (nothing stored) when it is not a finite positive rectangle. */
    fun offerCornerBox(
        row: Int,
        rowScore: Float,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): Boolean {
        if (!(l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite() && r > l && b > t)) return false
        return store(row, rowScore, l, t, r, b)
    }

    private fun store(
        row: Int,
        rowScore: Float,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): Boolean {
        left[row] = l
        top[row] = t
        right[row] = r
        bottom[row] = b
        score[row] = rowScore
        val normalized = maxOf(maxOf(abs(l), abs(t)), maxOf(abs(r), abs(b))) <= TrackerConfig.RAW_NORMALIZED_MAX_COORDINATE
        looksNormalized[row] = normalized
        heap[heapSize++] = row
        if (normalized) normalizedVotes++
        return true
    }

    /** Ends the offers of this frame: decides pixels or normalized, scales the boxes to model pixels, orders the candidates. */
    fun settle() {
        frameNormalized = normalizedVotes * 2 > heapSize
        if (frameNormalized) {
            var kept = 0
            for (i in 0 until heapSize) {
                val row = heap[i]
                if (!looksNormalized[row]) continue
                left[row] *= inputWidthMdl.toFloat()
                right[row] *= inputWidthMdl.toFloat()
                top[row] *= inputHeightMdl.toFloat()
                bottom[row] *= inputHeightMdl.toFloat()
                heap[kept++] = row
            }
            heapSize = kept
        }
        for (i in heapSize / 2 - 1 downTo 0) siftDown(i)
    }

    /**
     * The row of the next kept detection (best score first, not overlapping a kept one above the NMS IoU), which
     * becomes detection `count - 1`; -1 when none is left or [TrackerConfig.RAW_MAX_DETECTIONS] are kept.
     */
    fun nextKept(): Int {
        while (heapSize > 0 && count < capacity) {
            val best = heap[0]
            heapSize--
            if (heapSize > 0) {
                heap[0] = heap[heapSize]
                siftDown(0)
            }
            if (!overlapsKept(best)) {
                keptRow[count++] = best
                return best
            }
        }
        return -1
    }

    /** The output row of kept detection [index]. */
    fun rowAt(index: Int): Int {
        require(index in 0 until count) { "detection $index outside 0 until $count" }
        return keptRow[index]
    }

    /** Score of kept detection [index], as offered. */
    fun scoreOf(index: Int): Float = score[rowAt(index)]

    /** Left edge of kept detection [index], model pixels. */
    fun boxLeftMdl(index: Int): Float = left[rowAt(index)]

    /** Top edge of kept detection [index], model pixels. */
    fun boxTopMdl(index: Int): Float = top[rowAt(index)]

    /** Right edge of kept detection [index], model pixels. */
    fun boxRightMdl(index: Int): Float = right[rowAt(index)]

    /** Bottom edge of kept detection [index], model pixels. */
    fun boxBottomMdl(index: Int): Float = bottom[rowAt(index)]

    private fun overlapsKept(row: Int): Boolean {
        for (i in 0 until count) {
            val other = keptRow[i]
            if (BoxOverlap.iou(left[row], top[row], right[row], bottom[row], left[other], top[other], right[other], bottom[other]) >
                nmsIou
            ) {
                return true
            }
        }
        return false
    }

    /** `true` when row [a] ranks before row [b]: higher score, and for equal scores the lower row. */
    private fun ranksBefore(
        a: Int,
        b: Int,
    ): Boolean = score[a] > score[b] || (score[a] == score[b] && a < b)

    private fun siftDown(start: Int) {
        var parent = start
        while (true) {
            val leftChild = 2 * parent + 1
            if (leftChild >= heapSize) return
            val rightChild = leftChild + 1
            val best = if (rightChild < heapSize && ranksBefore(heap[rightChild], heap[leftChild])) rightChild else leftChild
            if (!ranksBefore(heap[best], heap[parent])) return
            val swap = heap[parent]
            heap[parent] = heap[best]
            heap[best] = swap
            parent = best
        }
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
