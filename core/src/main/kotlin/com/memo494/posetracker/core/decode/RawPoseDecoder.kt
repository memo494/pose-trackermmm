package com.memo494.posetracker.core.decode

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.PoseOutputLayout
import com.memo494.posetracker.core.tracking.BoxOverlap

/**
 * Decodes the RAW pose output of a YOLOv8 / YOLO11 / YOLO26 export made without built-in suppression
 * (Ultralytics `nms=None`): `[1, 56, N]` ([PoseOutputLayout.RAW_CHANNELS_FIRST]) or `[1, N, 56]`
 * ([PoseOutputLayout.RAW_CHANNELS_LAST]). Each of the `N` candidate rows holds `cx, cy, w, h`, the person score and
 * 17 keypoints of `x, y, conf`.
 *
 * Steps per frame: drop rows whose score is below the threshold (a NaN score never passes) and rows whose box is
 * not a finite positive rectangle; turn `cx, cy, w, h` into left, top, right, bottom; scale a row that is
 * normalized to the model input size (see below); suppress with class-agnostic greedy NMS (a lower-scored box with
 * an IoU above [TrackerConfig.rawNmsIou] to a kept box is dropped); keep at most [TrackerConfig.RAW_MAX_DETECTIONS],
 * best score first.
 *
 * **Coordinates.** Current Ultralytics LiteRT exports write model pixels; some exports write 0..1. The decoder uses
 * the rule of the vendored end-to-end path ([TrackerConfig.RAW_NORMALIZED_MAX_COORDINATE]), per row: when no
 * coordinate of the row's box has an absolute value above 2, the row (box and keypoints) is normalized and is
 * multiplied by the input width and height; otherwise it is in model pixels. The results are always model pixels
 * (Mdl space); mapping to image space is the caller's job (letterbox), exactly as for the end-to-end path.
 *
 * Allocation-free after construction: every buffer is sized once from [rows]. Not thread-safe; use it from the
 * one inference thread. Read the results with the accessors, valid until the next [decode].
 *
 * @param layout one of the two raw layouts; [PoseOutputLayout.END_TO_END] is refused.
 * @param rows number of candidate rows `N` of the output, unitless, at least 1.
 * @param inputWidthMdl width of the model input, model pixels (Mdl).
 * @param inputHeightMdl height of the model input, model pixels (Mdl).
 * @param config supplies [TrackerConfig.rawNmsIou].
 */
class RawPoseDecoder(
    private val layout: PoseOutputLayout,
    private val rows: Int,
    private val inputWidthMdl: Int,
    private val inputHeightMdl: Int,
    config: TrackerConfig = TrackerConfig(),
) {
    init {
        require(layout.isRaw) { "RawPoseDecoder needs a raw layout, not $layout" }
        require(rows >= 1) { "rows $rows is below 1" }
        require(inputWidthMdl >= 1 && inputHeightMdl >= 1) { "input size ${inputWidthMdl}x$inputHeightMdl is below 1x1" }
    }

    private val channelsFirst = layout == PoseOutputLayout.RAW_CHANNELS_FIRST
    private val nmsIou = config.rawNmsIou
    private val capacity = minOf(rows, TrackerConfig.RAW_MAX_DETECTIONS)

    // Per candidate row (indexed by row): box in model pixels, filled only for rows that pass the threshold.
    private val left = FloatArray(rows)
    private val top = FloatArray(rows)
    private val right = FloatArray(rows)
    private val bottom = FloatArray(rows)
    private val score = FloatArray(rows)
    private val normalized = BooleanArray(rows)
    private val heap = IntArray(rows)
    private var heapSize = 0

    // Per kept detection, best first.
    private val keptRow = IntArray(capacity)
    private val keptKeypoints = FloatArray(capacity * KEYPOINT_FLOATS)

    /** Detections kept by the last [decode]; 0 before the first call. */
    var count: Int = 0
        private set

    /**
     * Decodes [flat], the output tensor read as a flat array in the layout given to the constructor, keeping rows
     * with a score of at least [scoreThreshold] (unitless). Returns [count].
     *
     * @throws IllegalArgumentException when [flat] does not have `56 * rows` values.
     */
    fun decode(
        flat: FloatArray,
        scoreThreshold: Float,
    ): Int {
        require(flat.size == rows * TrackerConfig.POSE_RAW_CHANNELS) {
            "output has ${flat.size} values, expected ${rows * TrackerConfig.POSE_RAW_CHANNELS} ($rows rows, $layout)"
        }
        count = 0
        heapSize = 0
        for (row in 0 until rows) {
            val s = value(flat, CH_SCORE, row)
            if (!(s >= scoreThreshold)) continue
            if (addCandidate(flat, row, s)) heap[heapSize++] = row
        }
        for (i in heapSize / 2 - 1 downTo 0) siftDown(i)
        while (heapSize > 0 && count < capacity) {
            val best = heap[0]
            heapSize--
            if (heapSize > 0) {
                heap[0] = heap[heapSize]
                siftDown(0)
            }
            if (!overlapsKept(best)) keep(flat, best)
        }
        return count
    }

    /** Person score of detection [index], unitless. */
    fun scoreOf(index: Int): Float = score[rowOf(index)]

    /** Left edge of the box of detection [index], model pixels. */
    fun boxLeftMdl(index: Int): Float = left[rowOf(index)]

    /** Top edge of the box of detection [index], model pixels. */
    fun boxTopMdl(index: Int): Float = top[rowOf(index)]

    /** Right edge of the box of detection [index], model pixels. */
    fun boxRightMdl(index: Int): Float = right[rowOf(index)]

    /** Bottom edge of the box of detection [index], model pixels. */
    fun boxBottomMdl(index: Int): Float = bottom[rowOf(index)]

    /** X of keypoint [keypoint] of detection [index], model pixels. */
    fun keypointXMdl(
        index: Int,
        keypoint: Int,
    ): Float = keptKeypoints[keypointBase(index, keypoint)]

    /** Y of keypoint [keypoint] of detection [index], model pixels. */
    fun keypointYMdl(
        index: Int,
        keypoint: Int,
    ): Float = keptKeypoints[keypointBase(index, keypoint) + 1]

    /** Confidence of keypoint [keypoint] of detection [index], unitless. */
    fun keypointScore(
        index: Int,
        keypoint: Int,
    ): Float = keptKeypoints[keypointBase(index, keypoint) + 2]

    private fun rowOf(index: Int): Int {
        require(index in 0 until count) { "detection $index outside 0 until $count" }
        return keptRow[index]
    }

    private fun keypointBase(
        index: Int,
        keypoint: Int,
    ): Int {
        require(index in 0 until count) { "detection $index outside 0 until $count" }
        require(keypoint in 0 until TrackerConfig.POSE_KEYPOINT_COUNT) { "keypoint $keypoint outside the 17 keypoints" }
        return index * KEYPOINT_FLOATS + keypoint * TrackerConfig.POSE_VALUES_PER_KEYPOINT
    }

    private fun value(
        flat: FloatArray,
        channel: Int,
        row: Int,
    ): Float = if (channelsFirst) flat[channel * rows + row] else flat[row * TrackerConfig.POSE_RAW_CHANNELS + channel]

    /** Fills the buffers of [row]; `false` (nothing stored) when the box is not a finite positive rectangle. */
    private fun addCandidate(
        flat: FloatArray,
        row: Int,
        rowScore: Float,
    ): Boolean {
        val cx = value(flat, CH_CX, row)
        val cy = value(flat, CH_CY, row)
        val w = value(flat, CH_W, row)
        val h = value(flat, CH_H, row)
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && w > 0f && h > 0f)) return false
        val l = cx - w / 2f
        val t = cy - h / 2f
        val r = cx + w / 2f
        val b = cy + h / 2f
        val isNormalized = maxOf(maxOf(abs(l), abs(t)), maxOf(abs(r), abs(b))) <= TrackerConfig.RAW_NORMALIZED_MAX_COORDINATE
        val sx = if (isNormalized) inputWidthMdl.toFloat() else 1f
        val sy = if (isNormalized) inputHeightMdl.toFloat() else 1f
        left[row] = l * sx
        top[row] = t * sy
        right[row] = r * sx
        bottom[row] = b * sy
        score[row] = rowScore
        normalized[row] = isNormalized
        return true
    }

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

    private fun keep(
        flat: FloatArray,
        row: Int,
    ) {
        val sx = if (normalized[row]) inputWidthMdl.toFloat() else 1f
        val sy = if (normalized[row]) inputHeightMdl.toFloat() else 1f
        val base = count * KEYPOINT_FLOATS
        for (k in 0 until TrackerConfig.POSE_KEYPOINT_COUNT) {
            val channel = CH_KEYPOINTS + k * TrackerConfig.POSE_VALUES_PER_KEYPOINT
            val at = base + k * TrackerConfig.POSE_VALUES_PER_KEYPOINT
            keptKeypoints[at] = value(flat, channel, row) * sx
            keptKeypoints[at + 1] = value(flat, channel + 1, row) * sy
            keptKeypoints[at + 2] = value(flat, channel + 2, row)
        }
        keptRow[count++] = row
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

    private companion object {
        const val CH_CX = 0
        const val CH_CY = 1
        const val CH_W = 2
        const val CH_H = 3
        const val CH_SCORE = 4
        const val CH_KEYPOINTS = 5
        const val KEYPOINT_FLOATS = TrackerConfig.POSE_KEYPOINT_COUNT * TrackerConfig.POSE_VALUES_PER_KEYPOINT
    }
}
