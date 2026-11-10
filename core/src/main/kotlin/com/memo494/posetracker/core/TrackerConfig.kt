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
 */
data class TrackerConfig(
    val scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    val captureScale: Float = DEFAULT_CAPTURE_SCALE,
    val hudRateHz: Float = DEFAULT_HUD_RATE_HZ,
    val timestampGuardNs: Long = DEFAULT_TIMESTAMP_GUARD_NS,
    val overlayAlphaCapFallback: Float = DEFAULT_OVERLAY_ALPHA_CAP_FALLBACK,
    val dotStrokeDp: Float = DEFAULT_DOT_STROKE_DP,
    val boxStrokeDp: Float = DEFAULT_BOX_STROKE_DP,
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
    }

    /** Minimum time between two HUD text updates, in nanoseconds (duration, not a timestamp). */
    val hudIntervalNs: Long
        get() = (NANOS_PER_SECOND / hudRateHz).toLong()

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

        // Fixed constants (not user-tunable) used by the pure helpers, kept here so no number lives
        // elsewhere (AGENTS rule 5).

        /** Nanoseconds per second. */
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** Bytes per pixel of `PixelFormat.RGBA_8888`, the capture format. */
        const val RGBA_BYTES_PER_PIXEL = 4

        /** Capture dimensions are multiples of this (encoder/GPU friendly), in pixels. */
        const val EVEN_DIMENSION_MULTIPLE = 2

        /** Largest per-axis deviation, in pixels, accepted to keep the capture aspect exactly equal to the display's. */
        const val CAPTURE_EXACT_ASPECT_TOLERANCE_PX = 1.0f

        /** Ultralytics `LetterBox` leading/trailing pad rounding bias, in pixels (padX = round(pad/2 - bias)). */
        const val LETTERBOX_PAD_BIAS_MDL = 0.1f
    }
}
