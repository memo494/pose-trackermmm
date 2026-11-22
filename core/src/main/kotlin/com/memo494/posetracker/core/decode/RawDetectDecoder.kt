package com.memo494.posetracker.core.decode

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.PoseOutputLayout

/**
 * Decodes the output of an imported DETECT-ONLY model (no keypoints) into person boxes:
 * - [PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST] `[1, 4 + C, N]` and [PoseOutputLayout.DETECT_RAW_CHANNELS_LAST]
 *   `[1, N, 4 + C]`: each of the `N` candidate rows holds `cx, cy, w, h` then `C` class scores (already sigmoid, no
 *   objectness column). The person score is the score of class [personClass].
 * - [PoseOutputLayout.DETECT_END_TO_END] `[1, N, 6]`: `x1, y1, x2, y2, score, class` per row; only rows whose class
 *   value is [personClass] are kept. The row is read as it is (the model suppressed duplicates itself); the shared
 *   NMS still runs and only matters for a model that leaves duplicates.
 *
 * Steps per frame, shared with [RawPoseDecoder] in [RawBoxCandidates]: drop rows whose person score is below the
 * threshold (a NaN score never passes) and rows whose box is not a finite positive rectangle; read the coordinates
 * as normalized or model pixels once per frame ([TrackerConfig.RAW_NORMALIZED_MAX_COORDINATE]); class-agnostic greedy
 * NMS with [TrackerConfig.rawNmsIou]; keep at most [TrackerConfig.RAW_MAX_DETECTIONS], best score first. Boxes are
 * model pixels (Mdl space); mapping to image space is the caller's job (letterbox).
 *
 * Allocation-free after construction. Not thread-safe; use it from the one inference thread. Read the results with
 * the accessors, valid until the next [decode].
 *
 * @param layout one of the three detect-only layouts.
 * @param rows number of candidate rows `N` of the output, unitless, at least 1.
 * @param classes class score columns `C` of a raw layout, in 1..[TrackerConfig.DETECT_MAX_CLASSES]; ignored for
 *   [PoseOutputLayout.DETECT_END_TO_END].
 * @param personClass index of the person class, count from 0 ([TrackerConfig.detectPersonClass]); a raw layout needs
 *   `personClass < classes`, else the model is unusable (fail closed).
 * @param inputWidthMdl width of the model input, model pixels (Mdl).
 * @param inputHeightMdl height of the model input, model pixels (Mdl).
 * @param config supplies [TrackerConfig.rawNmsIou].
 */
class RawDetectDecoder(
    private val layout: PoseOutputLayout,
    private val rows: Int,
    private val classes: Int,
    private val personClass: Int,
    inputWidthMdl: Int,
    inputHeightMdl: Int,
    config: TrackerConfig = TrackerConfig(),
) {
    init {
        require(layout.isDetectOnly) { "RawDetectDecoder needs a detect-only layout, not $layout" }
        require(rows >= 1) { "rows $rows is below 1" }
        require(inputWidthMdl >= 1 && inputHeightMdl >= 1) { "input size ${inputWidthMdl}x$inputHeightMdl is below 1x1" }
        require(personClass >= 0) { "personClass $personClass is below 0" }
        if (layout != PoseOutputLayout.DETECT_END_TO_END) {
            require(classes in TrackerConfig.DETECT_MIN_CLASSES..TrackerConfig.DETECT_MAX_CLASSES) {
                "classes $classes outside ${TrackerConfig.DETECT_MIN_CLASSES}..${TrackerConfig.DETECT_MAX_CLASSES}"
            }
            require(personClass < classes) { "person class $personClass does not exist in a model with $classes classes" }
        }
    }

    private val channelsFirst = layout == PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
    private val columns =
        if (layout ==
            PoseOutputLayout.DETECT_END_TO_END
        ) {
            TrackerConfig.DETECT_END_TO_END_COLUMNS
        } else {
            TrackerConfig.POSE_BOX_VALUES + classes
        }
    private val candidates = RawBoxCandidates(rows, inputWidthMdl, inputHeightMdl, config.rawNmsIou)

    /** Detections kept by the last [decode]; 0 before the first call. */
    val count: Int
        get() = candidates.count

    /**
     * Decodes [flat], the output tensor read as a flat array in the layout given to the constructor, keeping rows
     * with a person score of at least [scoreThreshold] (unitless). Returns [count].
     *
     * @throws IllegalArgumentException when [flat] does not have `columns * rows` values.
     */
    fun decode(
        flat: FloatArray,
        scoreThreshold: Float,
    ): Int {
        require(flat.size == rows * columns) { "output has ${flat.size} values, expected ${rows * columns} ($rows rows, $layout)" }
        candidates.begin()
        if (layout == PoseOutputLayout.DETECT_END_TO_END) offerEndToEnd(flat, scoreThreshold) else offerRaw(flat, scoreThreshold)
        candidates.settle()
        while (candidates.nextKept() >= 0) {
            // Nothing per detection to copy: a detect-only row carries no keypoints.
        }
        return count
    }

    /** Person score of detection [index], unitless. */
    fun scoreOf(index: Int): Float = candidates.scoreOf(index)

    /** Left edge of the box of detection [index], model pixels. */
    fun boxLeftMdl(index: Int): Float = candidates.boxLeftMdl(index)

    /** Top edge of the box of detection [index], model pixels. */
    fun boxTopMdl(index: Int): Float = candidates.boxTopMdl(index)

    /** Right edge of the box of detection [index], model pixels. */
    fun boxRightMdl(index: Int): Float = candidates.boxRightMdl(index)

    /** Bottom edge of the box of detection [index], model pixels. */
    fun boxBottomMdl(index: Int): Float = candidates.boxBottomMdl(index)

    private fun offerRaw(
        flat: FloatArray,
        scoreThreshold: Float,
    ) {
        val scoreChannel = TrackerConfig.POSE_BOX_VALUES + personClass
        for (row in 0 until rows) {
            val s = rawValue(flat, scoreChannel, row)
            if (!(s >= scoreThreshold)) continue
            candidates.offerCenterBox(
                row,
                s,
                rawValue(flat, CH_CX, row),
                rawValue(flat, CH_CY, row),
                rawValue(flat, CH_W, row),
                rawValue(flat, CH_H, row),
            )
        }
    }

    private fun offerEndToEnd(
        flat: FloatArray,
        scoreThreshold: Float,
    ) {
        for (row in 0 until rows) {
            val base = row * columns
            val s = flat[base + COL_SCORE]
            if (!(s >= scoreThreshold)) continue
            if (!isPersonClass(flat[base + COL_CLASS])) continue
            candidates.offerCornerBox(row, s, flat[base + COL_X1], flat[base + COL_Y1], flat[base + COL_X2], flat[base + COL_Y2])
        }
    }

    /** `true` when the class value of an end-to-end row rounds to [personClass]; a NaN is never a person. */
    private fun isPersonClass(classValue: Float): Boolean = classValue >= personClass - HALF && classValue < personClass + HALF

    private fun rawValue(
        flat: FloatArray,
        channel: Int,
        row: Int,
    ): Float = if (channelsFirst) flat[channel * rows + row] else flat[row * columns + channel]

    private companion object {
        const val CH_CX = 0
        const val CH_CY = 1
        const val CH_W = 2
        const val CH_H = 3
        const val COL_X1 = 0
        const val COL_Y1 = 1
        const val COL_X2 = 2
        const val COL_Y2 = 3
        const val COL_SCORE = 4
        const val COL_CLASS = 5

        /**
         * Half a class step: an end-to-end class value is a whole number written as a float. Also the structural
         * centre-to-corner factor (a box corner is half a width or height from the centre); not a tunable.
         */
        const val HALF = 0.5f
    }
}
