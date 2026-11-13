package com.memo494.posetracker.core

/**
 * Every tunable number of the tracker lives here (ARCHITECTURE §7.1, AGENTS rule 5); nothing else in the
 * code base may hard-code a threshold, rate, size or stroke width. Immutable; construction fails fast
 * (`IllegalArgumentException`) when a value is outside its documented [min, max] so a bad slider value
 * can never reach the pipeline. Defaults and bounds are the `DEFAULT_*` / `MIN_*` / `MAX_*` constants in
 * the [Companion].
 *
 * @property scoreThreshold minimum detection confidence, unitless in [0, 1]; lower keeps more people.
 * @property captureScale capture size as a fraction of the display size (unitless); 0.5 = half
 *   resolution in each axis (1220x2712 becomes 610x1356).
 * @property hudRateHz HUD text refresh rate in hertz (the overlay is captured, so a fast HUD would
 *   feed back into the frames; Consult C1-B).
 * @property timestampGuardNs largest accepted age of `image.timestamp`, in nanoseconds
 *   (System.nanoTime() timebase, a duration here); older or future stamps fall back to arrival time.
 * @property overlayAlphaCapFallback overlay window alpha used when the platform maximum obscuring
 *   opacity cannot be queried (API < 31), unitless in (0, 0.8]. 0.8 is Android's limit for touch
 *   pass-through.
 * @property dotStrokeDp stroke width of the head ring, in dp.
 * @property boxStrokeDp stroke width of debug boxes, in dp.
 * @property redrawDeadbandPx smallest on-screen movement, in View pixels, of any box edge or keypoint
 *   that makes the overlay redraw (the overlay is captured, so redraws must be rare; Consult C1-B).
 * @property keypointMinScore keypoints with a lower confidence (unitless in [0, 1]) are not drawn and
 *   do not take part in the head-ring centre.
 * @property hudTextSizeSp text size of the debug HUD, in sp.
 * @property headRingRadiusRatio radius of the head ring as a fraction of the person box width
 *   (unitless).
 * @property modelInputSize side of the square model input, in pixels (Mdl space); only
 *   [MODEL_INPUT_SIZE_SMALL] (320) or [MODEL_INPUT_SIZE_LARGE] (416) is accepted because only those
 *   two model files are shipped.
 */
data class TrackerConfig(
    val scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    val captureScale: Float = DEFAULT_CAPTURE_SCALE,
    val hudRateHz: Float = DEFAULT_HUD_RATE_HZ,
    val timestampGuardNs: Long = DEFAULT_TIMESTAMP_GUARD_NS,
    val overlayAlphaCapFallback: Float = DEFAULT_OVERLAY_ALPHA_CAP_FALLBACK,
    val dotStrokeDp: Float = DEFAULT_DOT_STROKE_DP,
    val boxStrokeDp: Float = DEFAULT_BOX_STROKE_DP,
    val redrawDeadbandPx: Float = DEFAULT_REDRAW_DEADBAND_PX,
    val keypointMinScore: Float = DEFAULT_KEYPOINT_MIN_SCORE,
    val hudTextSizeSp: Float = DEFAULT_HUD_TEXT_SIZE_SP,
    val headRingRadiusRatio: Float = DEFAULT_HEAD_RING_RADIUS_RATIO,
    val modelInputSize: Int = DEFAULT_MODEL_INPUT_SIZE,
) {
    init {
        require(scoreThreshold in MIN_SCORE_THRESHOLD..MAX_SCORE_THRESHOLD) {
            "scoreThreshold $scoreThreshold outside [$MIN_SCORE_THRESHOLD, $MAX_SCORE_THRESHOLD]"
        }
        require(captureScale in MIN_CAPTURE_SCALE..MAX_CAPTURE_SCALE) {
            "captureScale $captureScale outside [$MIN_CAPTURE_SCALE, $MAX_CAPTURE_SCALE]"
        }
        require(hudRateHz in MIN_HUD_RATE_HZ..MAX_HUD_RATE_HZ) {
            "hudRateHz $hudRateHz outside [$MIN_HUD_RATE_HZ, $MAX_HUD_RATE_HZ]"
        }
        require(timestampGuardNs in MIN_TIMESTAMP_GUARD_NS..MAX_TIMESTAMP_GUARD_NS) {
            "timestampGuardNs $timestampGuardNs outside [$MIN_TIMESTAMP_GUARD_NS, $MAX_TIMESTAMP_GUARD_NS]"
        }
        require(overlayAlphaCapFallback in MIN_OVERLAY_ALPHA_CAP_FALLBACK..MAX_OVERLAY_ALPHA_CAP_FALLBACK) {
            "overlayAlphaCapFallback $overlayAlphaCapFallback outside " +
                "[$MIN_OVERLAY_ALPHA_CAP_FALLBACK, $MAX_OVERLAY_ALPHA_CAP_FALLBACK]"
        }
        require(dotStrokeDp in MIN_STROKE_DP..MAX_STROKE_DP) {
            "dotStrokeDp $dotStrokeDp outside [$MIN_STROKE_DP, $MAX_STROKE_DP]"
        }
        require(boxStrokeDp in MIN_STROKE_DP..MAX_STROKE_DP) {
            "boxStrokeDp $boxStrokeDp outside [$MIN_STROKE_DP, $MAX_STROKE_DP]"
        }
        require(redrawDeadbandPx in MIN_REDRAW_DEADBAND_PX..MAX_REDRAW_DEADBAND_PX) {
            "redrawDeadbandPx $redrawDeadbandPx outside [$MIN_REDRAW_DEADBAND_PX, $MAX_REDRAW_DEADBAND_PX]"
        }
        require(keypointMinScore in MIN_KEYPOINT_MIN_SCORE..MAX_KEYPOINT_MIN_SCORE) {
            "keypointMinScore $keypointMinScore outside [$MIN_KEYPOINT_MIN_SCORE, $MAX_KEYPOINT_MIN_SCORE]"
        }
        require(hudTextSizeSp in MIN_HUD_TEXT_SIZE_SP..MAX_HUD_TEXT_SIZE_SP) {
            "hudTextSizeSp $hudTextSizeSp outside [$MIN_HUD_TEXT_SIZE_SP, $MAX_HUD_TEXT_SIZE_SP]"
        }
        require(headRingRadiusRatio in MIN_HEAD_RING_RADIUS_RATIO..MAX_HEAD_RING_RADIUS_RATIO) {
            "headRingRadiusRatio $headRingRadiusRatio outside " +
                "[$MIN_HEAD_RING_RADIUS_RATIO, $MAX_HEAD_RING_RADIUS_RATIO]"
        }
        require(modelInputSize == MODEL_INPUT_SIZE_SMALL || modelInputSize == MODEL_INPUT_SIZE_LARGE) {
            "modelInputSize $modelInputSize is neither $MODEL_INPUT_SIZE_SMALL nor $MODEL_INPUT_SIZE_LARGE"
        }
    }

    /** Minimum time between two HUD text updates, in nanoseconds (duration, not a timestamp). */
    val hudIntervalNs: Long
        get() = (NANOS_PER_SECOND / hudRateHz).toLong()

    /**
     * Capture rate, in frames per second, below which the HUD shows 0 fps and does not refresh its numbers.
     * The HUD's own refreshes redraw the captured overlay and so cause up to `hudRateHz + 1` frames per
     * second on a still screen; below this value the capture counts as idle.
     */
    val hudIdleFps: Float
        get() = hudRateHz + HUD_IDLE_FPS_MARGIN

    companion object {
        /** Default [scoreThreshold]: unitless confidence. */
        const val DEFAULT_SCORE_THRESHOLD = 0.15f

        /** Lowest accepted [scoreThreshold]. */
        const val MIN_SCORE_THRESHOLD = 0.01f

        /** Highest accepted [scoreThreshold]. */
        const val MAX_SCORE_THRESHOLD = 0.95f

        /** Default [captureScale]: half resolution. */
        const val DEFAULT_CAPTURE_SCALE = 0.5f

        /** Lowest accepted [captureScale]. */
        const val MIN_CAPTURE_SCALE = 0.25f

        /** Highest accepted [captureScale] (full resolution). */
        const val MAX_CAPTURE_SCALE = 1.0f

        /** Default [hudRateHz], in hertz. */
        const val DEFAULT_HUD_RATE_HZ = 2.0f

        /** Lowest accepted [hudRateHz], in hertz. */
        const val MIN_HUD_RATE_HZ = 0.5f

        /** Highest accepted [hudRateHz], in hertz. */
        const val MAX_HUD_RATE_HZ = 10.0f

        /** Margin, in frames per second, added to [hudRateHz] to get [hudIdleFps]. */
        const val HUD_IDLE_FPS_MARGIN = 2f

        /** Default [timestampGuardNs]: 100 ms, in nanoseconds. */
        const val DEFAULT_TIMESTAMP_GUARD_NS = 100_000_000L

        /** Lowest accepted [timestampGuardNs]: 10 ms, in nanoseconds. */
        const val MIN_TIMESTAMP_GUARD_NS = 10_000_000L

        /** Highest accepted [timestampGuardNs]: 1 s, in nanoseconds. */
        const val MAX_TIMESTAMP_GUARD_NS = 1_000_000_000L

        /** Default [overlayAlphaCapFallback]: Android's maximum obscuring opacity for touch. */
        const val DEFAULT_OVERLAY_ALPHA_CAP_FALLBACK = 0.8f

        /** Lowest accepted [overlayAlphaCapFallback]. */
        const val MIN_OVERLAY_ALPHA_CAP_FALLBACK = 0.1f

        /** Highest accepted [overlayAlphaCapFallback]; never above Android's 0.8 touch limit. */
        const val MAX_OVERLAY_ALPHA_CAP_FALLBACK = 0.8f

        /** Default [dotStrokeDp], in dp (thin: the overlay is part of the capture). */
        const val DEFAULT_DOT_STROKE_DP = 1.5f

        /** Default [boxStrokeDp], in dp. */
        const val DEFAULT_BOX_STROKE_DP = 1.0f

        /** Lowest accepted stroke width ([dotStrokeDp], [boxStrokeDp]), in dp. */
        const val MIN_STROKE_DP = 0.5f

        /** Highest accepted stroke width ([dotStrokeDp], [boxStrokeDp]), in dp. */
        const val MAX_STROKE_DP = 6.0f

        /** Default [redrawDeadbandPx]: half a View pixel. */
        const val DEFAULT_REDRAW_DEADBAND_PX = 0.5f

        /** Lowest accepted [redrawDeadbandPx], in View pixels. */
        const val MIN_REDRAW_DEADBAND_PX = 0.1f

        /** Highest accepted [redrawDeadbandPx], in View pixels. */
        const val MAX_REDRAW_DEADBAND_PX = 4.0f

        /** Default [keypointMinScore]: unitless confidence. */
        const val DEFAULT_KEYPOINT_MIN_SCORE = 0.5f

        /** Lowest accepted [keypointMinScore]. */
        const val MIN_KEYPOINT_MIN_SCORE = 0.05f

        /** Highest accepted [keypointMinScore]. */
        const val MAX_KEYPOINT_MIN_SCORE = 0.95f

        /** Default [hudTextSizeSp], in sp. */
        const val DEFAULT_HUD_TEXT_SIZE_SP = 12.0f

        /** Lowest accepted [hudTextSizeSp], in sp. */
        const val MIN_HUD_TEXT_SIZE_SP = 8.0f

        /** Highest accepted [hudTextSizeSp], in sp. */
        const val MAX_HUD_TEXT_SIZE_SP = 24.0f

        /** Default [headRingRadiusRatio]: unitless fraction of the box width. */
        const val DEFAULT_HEAD_RING_RADIUS_RATIO = 0.12f

        /** Lowest accepted [headRingRadiusRatio]. */
        const val MIN_HEAD_RING_RADIUS_RATIO = 0.03f

        /** Highest accepted [headRingRadiusRatio]. */
        const val MAX_HEAD_RING_RADIUS_RATIO = 0.4f

        /** Small model input side, in pixels (Mdl): the thermal fallback size. */
        const val MODEL_INPUT_SIZE_SMALL = 320

        /** Large model input side, in pixels (Mdl): small people need the extra pixels. */
        const val MODEL_INPUT_SIZE_LARGE = 416

        /** Default [modelInputSize], in pixels (the thermal policy steps 416 to 320). */
        const val DEFAULT_MODEL_INPUT_SIZE = MODEL_INPUT_SIZE_LARGE

        // Fixed constants (not user-tunable) used by the pure helpers, kept here so no number lives
        // elsewhere (AGENTS rule 5).

        /** Keypoints 0-4 (COCO nose, eyes, ears) are the head keypoints used for the head ring. */
        const val HEAD_KEYPOINT_COUNT = 5

        /** Radius of a drawn keypoint dot, in dp. */
        const val KEYPOINT_RADIUS_DP = 2.0f

        /** Padding inside the HUD strip and its distance from the screen corner, in dp. */
        const val HUD_PADDING_DP = 4.0f

        /** Capacity, in characters, of one reused HUD line buffer; longer text is cut. */
        const val HUD_LINE_CAPACITY_CHARS = 64

        /** Most people one frame can hold; a detector keeps at most this many detections per frame. */
        const val MAX_DETECTIONS_PER_FRAME = 30

        /** Deepest array/object nesting the JSON reader accepts (`[[1]]` has depth 2); deeper input is rejected. */
        const val JSON_MAX_DEPTH = 32

        /** Nanoseconds per second. */
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** Bytes per pixel of `PixelFormat.RGBA_8888`, the capture format. */
        const val RGBA_BYTES_PER_PIXEL = 4

        /** Capture dimensions are multiples of this (encoder/GPU friendly), in pixels. */
        const val EVEN_DIMENSION_MULTIPLE = 2

        /** Largest per-axis deviation, in pixels, accepted to keep the capture aspect exactly equal to the display's. */
        const val CAPTURE_EXACT_ASPECT_TOLERANCE_PX = 1.0f

        /**
         * Capacity of the capture `ImageReader`: one image being copied while the next one is acquired
         * (Consult C1-D); a larger value only adds latency.
         */
        const val CAPTURE_MAX_IMAGES = 2

        /** Length of the window the capture fps is counted over: 1 s, in nanoseconds (a duration). */
        const val FPS_WINDOW_NS = 1_000_000_000L

        /**
         * Number of frame times the fps meter remembers (a ring). It caps the measurable rate at this many
         * frames per [FPS_WINDOW_NS]: 256 frames in the 1 s window, far above any display refresh rate.
         */
        const val FPS_RING_CAPACITY = 256

        /** A repeating frame failure is logged for the first failure of a streak and then every this many failures. */
        const val FAILURE_LOG_INTERVAL = 100

        /** Number of log lines the in-app diagnostics log keeps (a ring: the oldest line is dropped when full). */
        const val LOG_RING_CAPACITY = 500

        /** Longest diagnostics log line, in characters; a longer line is cut. */
        const val LOG_LINE_MAX_CHARS = 2000

        /** Most stack-trace lines the diagnostics log keeps after an error line. */
        const val LOG_STACK_LINES = 8

        /** Most log lines the in-app log screen shows (the newest ones). */
        const val LOG_SCREEN_LINES = 200

        /** Ultralytics `LetterBox` leading/trailing pad rounding bias, in pixels (padX = round(pad/2 - bias)). */
        const val LETTERBOX_PAD_BIAS_MDL = 0.1f

        /** Size, in bytes, of the one buffer used to stream a model file through SHA-256 or into a copy (64 KiB). */
        const val MODEL_IO_BUFFER_BYTES = 65_536

        /** Length, in characters, of a SHA-256 digest written as lowercase hex. */
        const val SHA256_HEX_LENGTH = 64

        /** Leading characters of the model's SHA-256 that go into the staged file name (`<base>-<prefix>.<ext>`). */
        const val STAGED_MODEL_SHA_PREFIX_LENGTH = 16
    }
}
