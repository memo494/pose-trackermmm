// Ultralytics 🚀 AGPL-3.0 License - https://ultralytics.com/license

package com.ultralytics.yolo

import android.content.Context
import android.graphics.*
import android.util.Log
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.decode.RawDetectDecoder
import com.memo494.posetracker.core.decode.RawPoseDecoder
import com.memo494.posetracker.core.modelimport.PoseOutputLayout
import kotlin.math.max
import kotlin.math.min

class PoseEstimator(
    context: Context,
    modelPath: String,
    override var labels: List<String>,
    private val useGpu: Boolean = true,
    private var confidenceThreshold: Float = 0.25f,   // Can be changed as needed
    private var numItemsThreshold: Int = 30,
    rawNmsIou: Float = TrackerConfig.DEFAULT_RAW_NMS_IOU,
    personClass: Int = TrackerConfig.DEFAULT_DETECT_PERSON_CLASS // pose-tracker: person class of a detect-only model
) : BasePredictor() {

    private enum class OutputLayout {
        FEATURES_FIRST,
        ANCHORS_FIRST
    }

    companion object {
        private const val BOX_CONF_FEATURES = 5 // Normal pose prefix: box x/y/w/h plus confidence.
        private const val KEYPOINT_FEATURES = 3 // One keypoint triplet: x, y, and confidence.
        private const val END_TO_END_MAX_ROWS = 300 // Ultralytics default max_det for capped end-to-end outputs.
        private const val END_TO_END_CLASS_FEATURE = 5 // Class value column of an end-to-end detect row (x1, y1, x2, y2, score, class).
        private const val CLASS_HALF = 0.5f // Half a class step: a class value is a whole number written as a float.
    }

    // Reusable float input for the CompiledModel input buffer.
    private lateinit var floatInput: FloatArray
    private lateinit var inputBitmap: Bitmap
    private lateinit var intValues: IntArray

    // Reuse output arrays to reduce allocations
    private var outDim2 = 0

    // Output dimensions
    private var batchSize = 0
    private var numAnchors = 0
    private var keypointStart = BOX_CONF_FEATURES
    private var keypointCount = 0
    private var isEndToEnd = false
    // pose-tracker: raw [1, 56, N] / [1, N, 56] output (nms=None exports), decoded by core's RawPoseDecoder; null otherwise.
    private var rawDecoder: RawPoseDecoder? = null
    // pose-tracker: detect-only output (no keypoints), decoded by core's RawDetectDecoder; null for a pose model.
    private var detectDecoder: RawDetectDecoder? = null
    /** The detect-only layout the model file has (`null` for a pose model); the app compares it with what the import recorded. */
    var detectLayout: PoseOutputLayout? = null
        private set
    /** Class score columns of a raw detect-only output, 0 otherwise. */
    var detectClasses: Int = 0
        private set
    // Feature index of the person score for the diagnostics scan: the person class column of a raw detect head, else 4.
    private var scoreFeature = BOX_CONF_FEATURES - 1
    // End-to-end detect head only: the class value (column 5) whose rows the diagnostics scan counts; -1 = every row.
    private var scanClassValue = -1
    // One shared, never-changing keypoint list for every detection of a detect-only model (the lists are only read).
    private val noKeypointsXy: List<Pair<Float, Float>> = List(TrackerConfig.POSE_KEYPOINT_COUNT) { 0f to 0f }
    private val noKeypointsConf: List<Float> = List(TrackerConfig.POSE_KEYPOINT_COUNT) { 0f }
    private lateinit var outputLayout: OutputLayout

    // Diagnostics of the last predict (pose-tracker change, see third_party/ULTRALYTICS.md); -1 = not measured yet.
    /** Highest raw score over all output rows of the last predict, before any threshold; NaN rows are skipped. */
    var lastMaxRawScore: Float = -1f
        private set
    /** Output rows of the last predict whose raw score passed [confidenceThreshold]. */
    var lastRawRowsAboveThreshold: Int = -1
        private set
    /** Output rows of the last predict whose raw score was NaN. */
    var lastNanRawRows: Int = -1
        private set

    init {
        if (this.labels.isEmpty()) {
            Log.w("PoseEstimator", "No labels provided; detections may lack class names.")
        }

        rtModel = InferenceModel.create(context, modelPath, useGpu, "PoseEstimator")

        try { // pose-tracker: any failure below (an Error such as OOM included) must not leak the model
            val inDims = rtModel.inputDims
            val inHeight = if (inDims.size >= 4) inDims[1] else 640
            val inWidth = if (inDims.size >= 4) inDims[2] else 640
            inputSize = com.ultralytics.yolo.Size(inWidth, inHeight)
            modelInputSize = Pair(inWidth, inHeight)

            val outputShape = rtModel.outputDims.getOrNull(0) ?: IntArray(0)
            require(outputShape.size >= 3) {
                "Unexpected pose output shape. Expected [batch, features, anchors] or [batch, anchors, features], Actual=${outputShape.contentToString()}"
            }

            batchSize = outputShape[0]
            val rawLayout = PoseOutputLayout.rawOf(outputShape) // pose-tracker: same rule as the import check
            detectLayout = if (rawLayout == null) PoseOutputLayout.detectOf(outputShape, inWidth, inHeight) else null
            val detect = detectLayout
            if (detect != null) {
                detectClasses = PoseOutputLayout.detectClassesOf(outputShape, inWidth, inHeight)
                initDetectOnly(detect, outputShape, inWidth, inHeight, personClass, rawNmsIou)
            } else {
                initPose(outputShape, rawLayout, inWidth, inHeight, rawNmsIou)
            }

            floatInput = FloatArray(inWidth * inHeight * 3)
            inputBitmap = Bitmap.createBitmap(inWidth, inHeight, Bitmap.Config.ARGB_8888)
            intValues = IntArray(inWidth * inHeight)
        } catch (e: Throwable) {
            rtModel.close()
            throw e
        }
    }

    /**
     * pose-tracker: a detect-only output ([PoseOutputLayout.detectOf], same rule as the import check). Throws, and so
     * makes the model unusable, when [personClass] is not one of the model's classes.
     */
    private fun initDetectOnly(
        detect: PoseOutputLayout,
        outputShape: IntArray,
        inWidth: Int,
        inHeight: Int,
        personClass: Int,
        rawNmsIou: Float
    ) {
        val channelsFirst = detect == PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
        outputLayout = if (channelsFirst) OutputLayout.FEATURES_FIRST else OutputLayout.ANCHORS_FIRST
        numAnchors = if (channelsFirst) outputShape[2] else outputShape[1]
        outDim2 = outputShape[2]
        keypointCount = TrackerConfig.POSE_KEYPOINT_COUNT
        if (detect != PoseOutputLayout.DETECT_END_TO_END) {
            scoreFeature = TrackerConfig.POSE_BOX_VALUES + personClass
        } else {
            scanClassValue = personClass
        }
        detectDecoder = RawDetectDecoder(
            detect,
            numAnchors,
            detectClasses,
            personClass,
            inWidth,
            inHeight,
            TrackerConfig(rawNmsIou = rawNmsIou)
        )
    }

    private fun initPose(
        outputShape: IntArray,
        rawLayout: PoseOutputLayout?,
        inWidth: Int,
        inHeight: Int,
        rawNmsIou: Float
    ) {
        val featuresFirstPose = rawLayout == PoseOutputLayout.RAW_CHANNELS_FIRST ||
            (rawLayout == null && isPoseFeatureCount(outputShape[1]) && outputShape[2] > END_TO_END_MAX_ROWS)
        val anchorsFirstPose = rawLayout == PoseOutputLayout.RAW_CHANNELS_LAST ||
            (rawLayout == null && isPoseFeatureCount(outputShape[2]) && outputShape[1] > END_TO_END_MAX_ROWS)
        isEndToEnd = !featuresFirstPose &&
            !anchorsFirstPose &&
            outputShape[1] <= END_TO_END_MAX_ROWS &&
            isEndToEndFeatureCount(outputShape[2])
        outputLayout = when {
            featuresFirstPose -> OutputLayout.FEATURES_FIRST
            isEndToEnd || anchorsFirstPose -> OutputLayout.ANCHORS_FIRST
            else -> throw IllegalArgumentException(
                "Unexpected pose output feature size. Expected 5 + keypoints * 3 features or end-to-end rows, Actual=${outputShape.contentToString()}"
            )
        }

        val outFeatures: Int
        when (outputLayout) {
            OutputLayout.FEATURES_FIRST -> {
                outFeatures = outputShape[1]
                numAnchors = outputShape[2]
            }
            OutputLayout.ANCHORS_FIRST -> {
                outFeatures = outputShape[2]
                numAnchors = outputShape[1]
            }
        }

        keypointStart = if (isEndToEnd && (outFeatures - 6) % 3 == 0) 6 else BOX_CONF_FEATURES
        keypointCount = keypointCountFromFeatureCount(outFeatures, keypointStart)
        outDim2 = outputShape[2]
        if (rawLayout != null) {
            rawDecoder = RawPoseDecoder(
                rawLayout,
                numAnchors,
                inWidth,
                inHeight,
                TrackerConfig(rawNmsIou = rawNmsIou)
            )
        }
    }

    override fun predict(bitmap: Bitmap, origWidth: Int, origHeight: Int, rotateForCamera: Boolean, isLandscape: Boolean): YOLOResult {
        t0 = System.nanoTime()
        ImageUtils.prepareBitmapForModel(
            bitmap = bitmap,
            targetBitmap = inputBitmap,
            rotateForCamera = rotateForCamera,
            isLandscape = isLandscape,
            isFrontCamera = isFrontCamera,
            rotationDegrees = cameraRotationDegrees
        )
        ImageUtils.copyRgbBitmapToFloatArray(
            inputBitmap,
            floatInput,
            intValues,
            channelsFirst = rtModel.inputUsesNchw
        )

        val preEnd = System.nanoTime()
        val flat = rtModel.run(floatInput)[0]
        val inferEnd = System.nanoTime()
        // Decode straight from the flat output - featureValue() handles both layouts without a reshape copy.
        val rawDetections = postProcessPose(
            flat = flat,
            numAnchors = numAnchors,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = IOU_THRESHOLD,
            origWidth = origWidth,
            origHeight = origHeight
        )

        // Apply numItemsThreshold limit
        val limitedDetections = rawDetections.take(numItemsThreshold)

        val boxes = limitedDetections.map { it.box }
        val keypointsList = limitedDetections.map { it.keypoints }

        val timing = finishTiming(preEnd, inferEnd)
        // Pack into YOLOResult and return
        return YOLOResult(
            origShape = com.ultralytics.yolo.Size(origWidth, origHeight),
            boxes = boxes,
            keypointsList = keypointsList,
            speed = timing.speedMs,
            fps = timing.fps,
            preMs = timing.preMs,
            inferenceMs = timing.inferenceMs,
            postMs = timing.postMs,
            names = labels
        )
    }

    private fun postProcessPose(
        flat: FloatArray,
        numAnchors: Int,
        confidenceThreshold: Float,
        iouThreshold: Float,
        origWidth: Int,
        origHeight: Int
    ): List<PoseDetection> {
        recordRawScoreStats(flat, confidenceThreshold)
        detectDecoder?.let { return postProcessDetectOnly(it, flat, confidenceThreshold, origWidth, origHeight) }
        rawDecoder?.let { return postProcessRawPose(it, flat, confidenceThreshold, origWidth, origHeight) }
        if (isEndToEnd) {
            return postProcessEndToEndPose(flat, confidenceThreshold, origWidth, origHeight)
        }

        val detections = mutableListOf<PoseDetection>()

        for (j in 0 until numAnchors) {
            val rawX = featureValue(flat, 0, j)
            val rawY = featureValue(flat, 1, j)
            val rawW = featureValue(flat, 2, j)
            val rawH = featureValue(flat, 3, j)
            val conf = featureValue(flat, 4, j)

            if (conf < confidenceThreshold) continue

            val outputRect = RectF(
                rawX - rawW / 2f,
                rawY - rawH / 2f,
                rawX + rawW / 2f,
                rawY + rawH / 2f
            )
            val outputIsNormalized = outputCoordinatesAreNormalized(outputRect)
            val rectF = inputRectFromOutputRect(
                outputRect,
                origWidth,
                origHeight,
                outputIsNormalized
            ) ?: continue
            val normBox = normalizedRectFromInputRect(rectF, origWidth, origHeight)

            val kpArray = mutableListOf<Pair<Float, Float>>()
            val kpConfArray = mutableListOf<Float>()
            for (k in 0 until keypointCount) {
                val rawKx = featureValue(flat, BOX_CONF_FEATURES + k * 3, j)
                val rawKy = featureValue(flat, BOX_CONF_FEATURES + k * 3 + 1, j)
                val kpC = featureValue(flat, BOX_CONF_FEATURES + k * 3 + 2, j)

                val (finalKx, finalKy) = inputPointFromOutputPoint(
                    rawKx,
                    rawKy,
                    origWidth,
                    origHeight,
                    outputIsNormalized
                )

                kpArray.add(finalKx to finalKy)
                kpConfArray.add(kpC)
            }

            val xynList = kpArray.map { (fx, fy) ->
                (fx / origWidth) to (fy / origHeight)
            }
            val boxObj = Box(0, labelName(0), conf, rectF, normBox)

            val keypoints = Keypoints(
                xyn = xynList,
                xy = kpArray,
                conf = kpConfArray
            )

            detections.add(
                PoseDetection(
                    box = boxObj,
                    keypoints = keypoints
                )
            )
        }

        val finalDetections = nmsPoseDetections(detections, iouThreshold)
        return finalDetections
    }

    /**
     * pose-tracker: raw pose output. The core decoder thresholds, runs class-agnostic NMS and caps the rows and gives
     * boxes and keypoints in model pixels; they are mapped to the image exactly like the end-to-end path does
     * (letterbox inverse), in pixel mode.
     */
    private fun postProcessRawPose(
        decoder: RawPoseDecoder,
        flat: FloatArray,
        confidenceThreshold: Float,
        origWidth: Int,
        origHeight: Int
    ): List<PoseDetection> {
        val count = decoder.decode(flat, confidenceThreshold)
        val detections = mutableListOf<PoseDetection>()
        for (i in 0 until count) {
            val outputRect = RectF(
                decoder.boxLeftMdl(i),
                decoder.boxTopMdl(i),
                decoder.boxRightMdl(i),
                decoder.boxBottomMdl(i)
            )
            val rectF = inputRectFromOutputRect(outputRect, origWidth, origHeight, false) ?: continue
            val normBox = normalizedRectFromInputRect(rectF, origWidth, origHeight)

            val kpArray = mutableListOf<Pair<Float, Float>>()
            val kpConfArray = mutableListOf<Float>()
            for (k in 0 until keypointCount) {
                kpArray.add(
                    inputPointFromOutputPoint(
                        decoder.keypointXMdl(i, k),
                        decoder.keypointYMdl(i, k),
                        origWidth,
                        origHeight,
                        false
                    )
                )
                kpConfArray.add(decoder.keypointScore(i, k))
            }

            detections.add(
                PoseDetection(
                    box = Box(0, labelName(0), decoder.scoreOf(i), rectF, normBox),
                    keypoints = Keypoints(
                        xyn = kpArray.map { (fx, fy) -> (fx / origWidth) to (fy / origHeight) },
                        xy = kpArray,
                        conf = kpConfArray
                    )
                )
            )
        }
        return detections
    }

    /**
     * pose-tracker: detect-only output. The core decoder thresholds on the person class, runs class-agnostic NMS and
     * gives boxes in model pixels; they are mapped to the image like the raw pose path does. The 17 keypoints are all
     * at (0, 0) with confidence 0: never visible, so the head point comes from the box.
     */
    private fun postProcessDetectOnly(
        decoder: RawDetectDecoder,
        flat: FloatArray,
        confidenceThreshold: Float,
        origWidth: Int,
        origHeight: Int
    ): List<PoseDetection> {
        val count = decoder.decode(flat, confidenceThreshold)
        val detections = mutableListOf<PoseDetection>()
        for (i in 0 until count) {
            val outputRect = RectF(
                decoder.boxLeftMdl(i),
                decoder.boxTopMdl(i),
                decoder.boxRightMdl(i),
                decoder.boxBottomMdl(i)
            )
            val rectF = inputRectFromOutputRect(outputRect, origWidth, origHeight, false) ?: continue
            val normBox = normalizedRectFromInputRect(rectF, origWidth, origHeight)
            detections.add(
                PoseDetection(
                    box = Box(0, labelName(0), decoder.scoreOf(i), rectF, normBox),
                    keypoints = Keypoints(xyn = noKeypointsXy, xy = noKeypointsXy, conf = noKeypointsConf)
                )
            )
        }
        return detections
    }

    private fun postProcessEndToEndPose(
        flat: FloatArray,
        confidenceThreshold: Float,
        origWidth: Int,
        origHeight: Int
    ): List<PoseDetection> {
        val detections = mutableListOf<PoseDetection>()

        for (j in 0 until numAnchors) {
            val conf = featureValue(flat, 4, j)
            if (conf < confidenceThreshold) continue

            val outputRect = RectF(
                featureValue(flat, 0, j),
                featureValue(flat, 1, j),
                featureValue(flat, 2, j),
                featureValue(flat, 3, j)
            )
            val outputIsNormalized = outputCoordinatesAreNormalized(outputRect)
            val rectF = inputRectFromOutputRect(
                outputRect,
                origWidth,
                origHeight,
                outputIsNormalized
            ) ?: continue
            val normBox = normalizedRectFromInputRect(rectF, origWidth, origHeight)

            val kpArray = mutableListOf<Pair<Float, Float>>()
            val kpConfArray = mutableListOf<Float>()
            for (k in 0 until keypointCount) {
                val rawKx = featureValue(flat, keypointStart + k * 3, j)
                val rawKy = featureValue(flat, keypointStart + k * 3 + 1, j)
                val kpC = featureValue(flat, keypointStart + k * 3 + 2, j)
                kpArray.add(
                    inputPointFromOutputPoint(
                        rawKx,
                        rawKy,
                        origWidth,
                        origHeight,
                        outputIsNormalized
                    )
                )
                kpConfArray.add(kpC)
            }

            detections.add(
                PoseDetection(
                    box = Box(0, labelName(0), conf, rectF, normBox),
                    keypoints = Keypoints(
                        xyn = kpArray.map { (fx, fy) -> (fx / origWidth) to (fy / origHeight) },
                        xy = kpArray,
                        conf = kpConfArray
                    )
                )
            )
        }
        return detections
    }

    /** Allocation-free scan of the score column of every output row (pose-tracker diagnostics). */
    private fun recordRawScoreStats(flat: FloatArray, confidenceThreshold: Float) {
        var maxScore = -1f
        var above = 0
        var nanRows = 0
        for (j in 0 until numAnchors) {
            if (scanClassValue >= 0 && !isScanClass(featureValue(flat, END_TO_END_CLASS_FEATURE, j))) continue
            val conf = featureValue(flat, scoreFeature, j)
            if (conf.isNaN()) {
                nanRows++
                continue
            }
            if (conf > maxScore) maxScore = conf
            if (conf >= confidenceThreshold) above++
        }
        lastMaxRawScore = maxScore
        lastRawRowsAboveThreshold = above
        lastNanRawRows = nanRows
    }

    /** `true` when the class value of an end-to-end detect row rounds to [scanClassValue]; a NaN is no class. */
    private fun isScanClass(classValue: Float): Boolean =
        classValue >= scanClassValue - CLASS_HALF && classValue < scanClassValue + CLASS_HALF

    private fun featureValue(
        flat: FloatArray,
        featureIndex: Int,
        anchorIndex: Int
    ): Float {
        // Row stride is outDim2 for both layouts of the flat [1, d1, d2] output
        return when (outputLayout) {
            OutputLayout.FEATURES_FIRST -> flat[featureIndex * outDim2 + anchorIndex]
            OutputLayout.ANCHORS_FIRST -> flat[anchorIndex * outDim2 + featureIndex]
        }
    }

    private fun isPoseFeatureCount(featureCount: Int): Boolean {
        return featureCount >= BOX_CONF_FEATURES + KEYPOINT_FEATURES &&
            (featureCount - BOX_CONF_FEATURES) % KEYPOINT_FEATURES == 0
    }

    private fun isEndToEndFeatureCount(featureCount: Int): Boolean {
        return isPoseFeatureCount(featureCount) ||
            (featureCount >= 6 + KEYPOINT_FEATURES && (featureCount - 6) % KEYPOINT_FEATURES == 0)
    }

    private fun keypointCountFromFeatureCount(featureCount: Int, keypointStart: Int = BOX_CONF_FEATURES): Int {
        require(featureCount >= keypointStart && (featureCount - keypointStart) % KEYPOINT_FEATURES == 0) {
            "Unexpected pose output feature size. Expected keypoint triplets after $keypointStart box fields, Actual=$featureCount"
        }
        return (featureCount - keypointStart) / KEYPOINT_FEATURES
    }

    private fun nmsPoseDetections(
        detections: List<PoseDetection>,
        iouThreshold: Float
    ): List<PoseDetection> {
        val confidenceThreshold = 0.25f  // Hardcoded second-pass threshold
        val filteredDetections = detections.filter { it.box.conf >= confidenceThreshold }

        if (filteredDetections.size <= 1) {
            return filteredDetections
        }

        val sorted = filteredDetections.sortedByDescending { it.box.conf }
        val picked = mutableListOf<PoseDetection>()
        val used = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (used[i]) continue

            val d1 = sorted[i]
            picked.add(d1)

            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val d2 = sorted[j]
                if (iou(d1.box.xywh, d2.box.xywh) > iouThreshold) {
                    used[j] = true
                }
            }
        }
        return picked
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else (interArea / unionArea)
    }

    override fun setConfidenceThreshold(conf: Double) {
        confidenceThreshold = conf.toFloat()
        super.setConfidenceThreshold(conf)
    }

    override fun setNumItemsThreshold(n: Int) {
        numItemsThreshold = n
        super.setNumItemsThreshold(n)
    }

    override fun getConfidenceThreshold(): Double {
        return confidenceThreshold.toDouble()
    }

    private data class PoseDetection(
        val box: Box,
        val keypoints: Keypoints
    )
}
