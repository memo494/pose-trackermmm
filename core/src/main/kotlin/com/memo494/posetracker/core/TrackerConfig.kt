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
 * @property minHeadRingRadiusDp smallest radius of the head ring, in dp; the ring is
 *   `max(headRingRadiusRatio x box width, minHeadRingRadiusDp)` so far people still get a ring that
 *   is a real mark (a tiny ring does not keep the model's score on the next frame).
 * @property boxCornerRatio length of each corner-bracket stroke as a fraction of the box side it runs
 *   along (unitless, in the open interval (0, 0.5), so the two strokes of one side never meet).
 * @property debugMarks `false` (default): only the head ring and the corner brackets are drawn, because
 *   the overlay is part of the capture and every extra mark lowers the model's score of the person on the
 *   next frame. `true`: also the full box and the keypoint dots, for debugging.
 * @property modelInputSize side of the square model input, in pixels (Mdl space), of the BUILT-IN
 *   models; only [MODEL_INPUT_SIZE_SMALL] (320) or [MODEL_INPUT_SIZE_LARGE] (416) is accepted because only
 *   those two model files are shipped. An imported model brings its own size, limited by
 *   [importMinInputSize]..[importMaxInputSize] (it is not described by this field).
 * @property importMinBytes smallest accepted size of an imported model file, in bytes (a smaller file
 *   cannot be a pose model).
 * @property importMaxBytes largest accepted size of an imported model file, in bytes (the copy into
 *   app storage is cut off at this size).
 * @property importMinInputSize smallest accepted width or height of an imported model's input, in pixels
 *   (Mdl space); a multiple of [IMPORT_INPUT_SIZE_STEP].
 * @property importMaxInputSize largest accepted width or height of an imported model's input, in pixels
 *   (Mdl space); a multiple of [IMPORT_INPUT_SIZE_STEP].
 * @property importMaxInputAspect largest accepted ratio of the long side to the short side of an imported
 *   model's input, unitless in [[MIN_IMPORT_MAX_INPUT_ASPECT_LIMIT], [MAX_IMPORT_MAX_INPUT_ASPECT_LIMIT]];
 *   1.0 accepts only square inputs, 4.0 (default) accepts 768x192 and refuses 832x192.
 * @property timingWindowFrames number of the newest processed frames each timing statistic (copy, pre, inference,
 *   post, frame, result age) is computed over, in frames, in [[MIN_TIMING_WINDOW_FRAMES], [MAX_TIMING_WINDOW_FRAMES]].
 * @property thermalSampleIntervalMs shortest time between two reads of the device's thermal status and headroom,
 *   in milliseconds, in [[MIN_THERMAL_SAMPLE_INTERVAL_MS], [MAX_THERMAL_SAMPLE_INTERVAL_MS]]; the platform
 *   answers `NaN` to a headroom request that comes too often.
 * @property maxTracks most tracks the tracker holds at once, in tracks, in [[MIN_MAX_TRACKS], [MAX_MAX_TRACKS]];
 *   the assignment matrix has this many rows. A detection that would start a track beyond it is not tracked.
 * @property trackGateChi2 association gate: a detection is a candidate for a track only when the squared
 *   Mahalanobis distance of its head point from the track's predicted head point (the two axes taken
 *   independently, using the innovation variances) is at most this value; chi-square with 2 degrees of
 *   freedom, unitless, in [[MIN_TRACK_GATE_CHI2], [MAX_TRACK_GATE_CHI2]]; 9.21 is the 99 % point.
 * @property assocIouWeight weight of `1 - IoU` of the track box and the detection box in the association cost,
 *   unitless in [[MIN_ASSOC_WEIGHT], [MAX_ASSOC_WEIGHT]].
 * @property assocHeadWeight weight of the head distance term in the association cost, unitless in
 *   [[MIN_ASSOC_WEIGHT], [MAX_ASSOC_WEIGHT]].
 * @property assocHeadScaleRatio head distance, as a fraction of the track box height `h` (unitless), at which
 *   the head distance term reaches 1 and stops growing, in [[MIN_ASSOC_HEAD_SCALE_RATIO],
 *   [MAX_ASSOC_HEAD_SCALE_RATIO]]; 0.5 means half a person height.
 * @property dedupeIou two detections of one frame whose boxes overlap with at least this IoU (unitless) are
 *   one person: the lower score is dropped before association, in [[MIN_DEDUPE_IOU], [MAX_DEDUPE_IOU]].
 * @property trackHighScore a detection with at least this score (unitless) takes part in the first, strict matching
 *   stage of the tracker, in [[MIN_TRACK_SCORE], [MAX_TRACK_SCORE]]; lower ones only in the second stage.
 * @property trackBirthScore a detection that matches no track starts a new track only with at least this score
 *   (unitless), in [[MIN_TRACK_SCORE], [MAX_TRACK_SCORE]]; weaker detections can keep a track alive but never start one.
 * @property trackConfirmHits matched frames, counting the first, after which a tentative track becomes confirmed
 *   and is shown, in frames, in [[MIN_TRACK_CONFIRM_HITS], [MAX_TRACK_CONFIRM_HITS]]; 1 = a new track is confirmed
 *   (and shown) at once, from its first detection.
 * @property trackIdlePromoteMs idle time in milliseconds (time since the newest frame arrived) at which tentative
 *   tracks become confirmed without further frames (a still screen sends no frames), in
 *   [[MIN_TRACK_DURATION_MS], [MAX_TRACK_DURATION_MS]]; see [trackIdlePromoteNs].
 * @property trackMaxLostMs longest time in milliseconds a confirmed track survives without a matching detection,
 *   in [[MIN_TRACK_DURATION_MS], [MAX_TRACK_DURATION_MS]].
 * @property trackMaxDtMs longest time step in milliseconds the tracker predicts over in one frame; a longer step
 *   is predicted as this long and counts as a gap step (see [trackGapNoiseInflate]), in
 *   [[MIN_TRACK_MAX_DT_MS], [MAX_TRACK_MAX_DT_MS]].
 * @property trackGapNoiseInflate factor (unitless) on the process noise of the gap step itself (the predict step of
 *   a frame that came more than [trackMaxDtMs] after the previous one), in
 *   [[MIN_TRACK_GAP_NOISE_INFLATE], [MAX_TRACK_GAP_NOISE_INFLATE]]; 1 = no inflation.
 * @property trackMissVelocityTauMs time constant in milliseconds with which the velocity of a track without a
 *   matching detection decays (`exp(-dt / tau)`), in [[MIN_TRACK_DURATION_MS], [MAX_TRACK_DURATION_MS]].
 * @property trackAccelSigmaRatio standard deviation of the head's acceleration (process noise) as a multiple of the
 *   track height per second squared (h/s²), in [[MIN_TRACK_ACCEL_SIGMA_RATIO], [MAX_TRACK_ACCEL_SIGMA_RATIO]].
 * @property trackHeightAccelSigmaRatio the same for the track height's rate of change, in h/s², in
 *   [[MIN_TRACK_ACCEL_SIGMA_RATIO], [MAX_TRACK_ACCEL_SIGMA_RATIO]].
 * @property trackHeightNoiseRatio standard deviation of the measured box height as a fraction of the predicted
 *   height (unitless), in [[MIN_TRACK_HEIGHT_NOISE_RATIO], [MAX_TRACK_HEIGHT_NOISE_RATIO]].
 * @property trackInitVelocitySigmaRatio standard deviation of the velocity of a new track, on each axis, as a
 *   multiple of its height per second (h/s), in [[MIN_TRACK_INIT_VELOCITY_SIGMA_RATIO],
 *   [MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO]].
 * @property trackMatchMaxCost largest association cost (unitless; the cost is at most 2) of a pair made in the
 *   first matching stage, in [[MIN_TRACK_MATCH_MAX_COST], [MAX_TRACK_MATCH_MAX_COST]].
 * @property trackLowMatchMaxCost the same for the second stage (tracks left over, low-score detections), in
 *   [[MIN_TRACK_MATCH_MAX_COST], [MAX_TRACK_MATCH_MAX_COST]].
 * @property trackExitMarginRatio a lost track whose head point is outside the image by more than this fraction of
 *   its height (unitless) is removed at once, in [[MIN_TRACK_EXIT_MARGIN_RATIO], [MAX_TRACK_EXIT_MARGIN_RATIO]];
 *   0 removes it as soon as the head leaves the image.
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
    val minHeadRingRadiusDp: Float = DEFAULT_MIN_HEAD_RING_RADIUS_DP,
    val boxCornerRatio: Float = DEFAULT_BOX_CORNER_RATIO,
    val debugMarks: Boolean = DEFAULT_DEBUG_MARKS,
    val modelInputSize: Int = DEFAULT_MODEL_INPUT_SIZE,
    val importMinBytes: Long = DEFAULT_IMPORT_MIN_BYTES,
    val importMaxBytes: Long = DEFAULT_IMPORT_MAX_BYTES,
    val importMinInputSize: Int = DEFAULT_IMPORT_MIN_INPUT_SIZE,
    val importMaxInputSize: Int = DEFAULT_IMPORT_MAX_INPUT_SIZE,
    val importMaxInputAspect: Float = DEFAULT_IMPORT_MAX_INPUT_ASPECT,
    val timingWindowFrames: Int = DEFAULT_TIMING_WINDOW_FRAMES,
    val thermalSampleIntervalMs: Long = DEFAULT_THERMAL_SAMPLE_INTERVAL_MS,
    val headKeypointEnterScore: Float = DEFAULT_HEAD_KEYPOINT_ENTER_SCORE,
    val headKeypointLeaveScore: Float = DEFAULT_HEAD_KEYPOINT_LEAVE_SCORE,
    val headOffsetAlpha: Float = DEFAULT_HEAD_OFFSET_ALPHA,
    val headFallbackTopRatio: Float = DEFAULT_HEAD_FALLBACK_TOP_RATIO,
    val headMeasurementNoiseRatio: Float = DEFAULT_HEAD_MEASUREMENT_NOISE_RATIO,
    val headFallbackNoiseRatio: Float = DEFAULT_HEAD_FALLBACK_NOISE_RATIO,
    val headMinMeanConfidence: Float = DEFAULT_HEAD_MIN_MEAN_CONFIDENCE,
    val maxTracks: Int = DEFAULT_MAX_TRACKS,
    val trackGateChi2: Float = DEFAULT_TRACK_GATE_CHI2,
    val assocIouWeight: Float = DEFAULT_ASSOC_WEIGHT,
    val assocHeadWeight: Float = DEFAULT_ASSOC_WEIGHT,
    val assocHeadScaleRatio: Float = DEFAULT_ASSOC_HEAD_SCALE_RATIO,
    val dedupeIou: Float = DEFAULT_DEDUPE_IOU,
    val trackHighScore: Float = DEFAULT_TRACK_HIGH_SCORE,
    val trackBirthScore: Float = DEFAULT_TRACK_BIRTH_SCORE,
    val trackConfirmHits: Int = DEFAULT_TRACK_CONFIRM_HITS,
    val trackIdlePromoteMs: Long = DEFAULT_TRACK_IDLE_PROMOTE_MS,
    val trackMaxLostMs: Float = DEFAULT_TRACK_MAX_LOST_MS,
    val trackMaxDtMs: Float = DEFAULT_TRACK_MAX_DT_MS,
    val trackGapNoiseInflate: Float = DEFAULT_TRACK_GAP_NOISE_INFLATE,
    val trackMissVelocityTauMs: Float = DEFAULT_TRACK_MISS_VELOCITY_TAU_MS,
    val trackAccelSigmaRatio: Float = DEFAULT_TRACK_ACCEL_SIGMA_RATIO,
    val trackHeightAccelSigmaRatio: Float = DEFAULT_TRACK_HEIGHT_ACCEL_SIGMA_RATIO,
    val trackHeightNoiseRatio: Float = DEFAULT_TRACK_HEIGHT_NOISE_RATIO,
    val trackInitVelocitySigmaRatio: Float = DEFAULT_TRACK_INIT_VELOCITY_SIGMA_RATIO,
    val trackMatchMaxCost: Float = DEFAULT_TRACK_MATCH_MAX_COST,
    val trackLowMatchMaxCost: Float = DEFAULT_TRACK_LOW_MATCH_MAX_COST,
    val trackExitMarginRatio: Float = DEFAULT_TRACK_EXIT_MARGIN_RATIO,
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
        require(minHeadRingRadiusDp in MIN_MIN_HEAD_RING_RADIUS_DP..MAX_MIN_HEAD_RING_RADIUS_DP) {
            "minHeadRingRadiusDp $minHeadRingRadiusDp outside " +
                "[$MIN_MIN_HEAD_RING_RADIUS_DP, $MAX_MIN_HEAD_RING_RADIUS_DP]"
        }
        require(boxCornerRatio > BOX_CORNER_RATIO_LOWER_EXCLUSIVE && boxCornerRatio < BOX_CORNER_RATIO_UPPER_EXCLUSIVE) {
            "boxCornerRatio $boxCornerRatio outside ($BOX_CORNER_RATIO_LOWER_EXCLUSIVE, $BOX_CORNER_RATIO_UPPER_EXCLUSIVE)"
        }
        require(modelInputSize == MODEL_INPUT_SIZE_SMALL || modelInputSize == MODEL_INPUT_SIZE_LARGE) {
            "modelInputSize $modelInputSize is neither $MODEL_INPUT_SIZE_SMALL nor $MODEL_INPUT_SIZE_LARGE"
        }
        require(importMinBytes in MIN_IMPORT_BYTES_LIMIT..MAX_IMPORT_BYTES_LIMIT) {
            "importMinBytes $importMinBytes outside [$MIN_IMPORT_BYTES_LIMIT, $MAX_IMPORT_BYTES_LIMIT]"
        }
        require(importMaxBytes in MIN_IMPORT_BYTES_LIMIT..MAX_IMPORT_BYTES_LIMIT) {
            "importMaxBytes $importMaxBytes outside [$MIN_IMPORT_BYTES_LIMIT, $MAX_IMPORT_BYTES_LIMIT]"
        }
        require(importMinBytes <= importMaxBytes) {
            "importMinBytes $importMinBytes is above importMaxBytes $importMaxBytes"
        }
        require(importMinInputSize in MIN_IMPORT_INPUT_SIZE_LIMIT..MAX_IMPORT_INPUT_SIZE_LIMIT) {
            "importMinInputSize $importMinInputSize outside [$MIN_IMPORT_INPUT_SIZE_LIMIT, $MAX_IMPORT_INPUT_SIZE_LIMIT]"
        }
        require(importMaxInputSize in MIN_IMPORT_INPUT_SIZE_LIMIT..MAX_IMPORT_INPUT_SIZE_LIMIT) {
            "importMaxInputSize $importMaxInputSize outside [$MIN_IMPORT_INPUT_SIZE_LIMIT, $MAX_IMPORT_INPUT_SIZE_LIMIT]"
        }
        require(importMinInputSize <= importMaxInputSize) {
            "importMinInputSize $importMinInputSize is above importMaxInputSize $importMaxInputSize"
        }
        require(importMinInputSize % IMPORT_INPUT_SIZE_STEP == 0 && importMaxInputSize % IMPORT_INPUT_SIZE_STEP == 0) {
            "importMinInputSize $importMinInputSize and importMaxInputSize $importMaxInputSize " +
                "must be multiples of $IMPORT_INPUT_SIZE_STEP"
        }
        require(importMaxInputAspect in MIN_IMPORT_MAX_INPUT_ASPECT_LIMIT..MAX_IMPORT_MAX_INPUT_ASPECT_LIMIT) {
            "importMaxInputAspect $importMaxInputAspect outside " +
                "[$MIN_IMPORT_MAX_INPUT_ASPECT_LIMIT, $MAX_IMPORT_MAX_INPUT_ASPECT_LIMIT]"
        }
        require(timingWindowFrames in MIN_TIMING_WINDOW_FRAMES..MAX_TIMING_WINDOW_FRAMES) {
            "timingWindowFrames $timingWindowFrames outside [$MIN_TIMING_WINDOW_FRAMES, $MAX_TIMING_WINDOW_FRAMES]"
        }
        require(thermalSampleIntervalMs in MIN_THERMAL_SAMPLE_INTERVAL_MS..MAX_THERMAL_SAMPLE_INTERVAL_MS) {
            "thermalSampleIntervalMs $thermalSampleIntervalMs outside " +
                "[$MIN_THERMAL_SAMPLE_INTERVAL_MS, $MAX_THERMAL_SAMPLE_INTERVAL_MS]"
        }
        require(headKeypointEnterScore in MIN_HEAD_KEYPOINT_SCORE..MAX_HEAD_KEYPOINT_SCORE) {
            "headKeypointEnterScore $headKeypointEnterScore outside [$MIN_HEAD_KEYPOINT_SCORE, $MAX_HEAD_KEYPOINT_SCORE]"
        }
        require(headKeypointLeaveScore in MIN_HEAD_KEYPOINT_SCORE..MAX_HEAD_KEYPOINT_SCORE) {
            "headKeypointLeaveScore $headKeypointLeaveScore outside [$MIN_HEAD_KEYPOINT_SCORE, $MAX_HEAD_KEYPOINT_SCORE]"
        }
        require(headKeypointLeaveScore <= headKeypointEnterScore) {
            "headKeypointLeaveScore $headKeypointLeaveScore is above headKeypointEnterScore $headKeypointEnterScore"
        }
        require(headOffsetAlpha in MIN_HEAD_OFFSET_ALPHA..MAX_HEAD_OFFSET_ALPHA) {
            "headOffsetAlpha $headOffsetAlpha outside [$MIN_HEAD_OFFSET_ALPHA, $MAX_HEAD_OFFSET_ALPHA]"
        }
        require(headFallbackTopRatio in MIN_HEAD_FALLBACK_TOP_RATIO..MAX_HEAD_FALLBACK_TOP_RATIO) {
            "headFallbackTopRatio $headFallbackTopRatio outside [$MIN_HEAD_FALLBACK_TOP_RATIO, $MAX_HEAD_FALLBACK_TOP_RATIO]"
        }
        require(headMeasurementNoiseRatio in MIN_HEAD_MEASUREMENT_NOISE_RATIO..MAX_HEAD_MEASUREMENT_NOISE_RATIO) {
            "headMeasurementNoiseRatio $headMeasurementNoiseRatio outside " +
                "[$MIN_HEAD_MEASUREMENT_NOISE_RATIO, $MAX_HEAD_MEASUREMENT_NOISE_RATIO]"
        }
        require(headFallbackNoiseRatio in MIN_HEAD_FALLBACK_NOISE_RATIO..MAX_HEAD_FALLBACK_NOISE_RATIO) {
            "headFallbackNoiseRatio $headFallbackNoiseRatio outside " +
                "[$MIN_HEAD_FALLBACK_NOISE_RATIO, $MAX_HEAD_FALLBACK_NOISE_RATIO]"
        }
        require(headMinMeanConfidence in MIN_HEAD_MIN_MEAN_CONFIDENCE..MAX_HEAD_MIN_MEAN_CONFIDENCE) {
            "headMinMeanConfidence $headMinMeanConfidence outside " +
                "[$MIN_HEAD_MIN_MEAN_CONFIDENCE, $MAX_HEAD_MIN_MEAN_CONFIDENCE]"
        }
        require(maxTracks in MIN_MAX_TRACKS..MAX_MAX_TRACKS) {
            "maxTracks $maxTracks outside [$MIN_MAX_TRACKS, $MAX_MAX_TRACKS]"
        }
        require(trackGateChi2 in MIN_TRACK_GATE_CHI2..MAX_TRACK_GATE_CHI2) {
            "trackGateChi2 $trackGateChi2 outside [$MIN_TRACK_GATE_CHI2, $MAX_TRACK_GATE_CHI2]"
        }
        require(assocIouWeight in MIN_ASSOC_WEIGHT..MAX_ASSOC_WEIGHT) {
            "assocIouWeight $assocIouWeight outside [$MIN_ASSOC_WEIGHT, $MAX_ASSOC_WEIGHT]"
        }
        require(assocHeadWeight in MIN_ASSOC_WEIGHT..MAX_ASSOC_WEIGHT) {
            "assocHeadWeight $assocHeadWeight outside [$MIN_ASSOC_WEIGHT, $MAX_ASSOC_WEIGHT]"
        }
        require(assocHeadScaleRatio in MIN_ASSOC_HEAD_SCALE_RATIO..MAX_ASSOC_HEAD_SCALE_RATIO) {
            "assocHeadScaleRatio $assocHeadScaleRatio outside [$MIN_ASSOC_HEAD_SCALE_RATIO, $MAX_ASSOC_HEAD_SCALE_RATIO]"
        }
        require(dedupeIou in MIN_DEDUPE_IOU..MAX_DEDUPE_IOU) {
            "dedupeIou $dedupeIou outside [$MIN_DEDUPE_IOU, $MAX_DEDUPE_IOU]"
        }
        require(trackHighScore in MIN_TRACK_SCORE..MAX_TRACK_SCORE) {
            "trackHighScore $trackHighScore outside [$MIN_TRACK_SCORE, $MAX_TRACK_SCORE]"
        }
        require(trackBirthScore in MIN_TRACK_SCORE..MAX_TRACK_SCORE) {
            "trackBirthScore $trackBirthScore outside [$MIN_TRACK_SCORE, $MAX_TRACK_SCORE]"
        }
        require(trackConfirmHits in MIN_TRACK_CONFIRM_HITS..MAX_TRACK_CONFIRM_HITS) {
            "trackConfirmHits $trackConfirmHits outside [$MIN_TRACK_CONFIRM_HITS, $MAX_TRACK_CONFIRM_HITS]"
        }
        require(trackIdlePromoteMs in MIN_TRACK_DURATION_MS_LONG..MAX_TRACK_DURATION_MS_LONG) {
            "trackIdlePromoteMs $trackIdlePromoteMs outside [$MIN_TRACK_DURATION_MS_LONG, $MAX_TRACK_DURATION_MS_LONG]"
        }
        require(trackMaxLostMs in MIN_TRACK_DURATION_MS..MAX_TRACK_DURATION_MS) {
            "trackMaxLostMs $trackMaxLostMs outside [$MIN_TRACK_DURATION_MS, $MAX_TRACK_DURATION_MS]"
        }
        require(trackMaxDtMs in MIN_TRACK_MAX_DT_MS..MAX_TRACK_MAX_DT_MS) {
            "trackMaxDtMs $trackMaxDtMs outside [$MIN_TRACK_MAX_DT_MS, $MAX_TRACK_MAX_DT_MS]"
        }
        require(trackGapNoiseInflate in MIN_TRACK_GAP_NOISE_INFLATE..MAX_TRACK_GAP_NOISE_INFLATE) {
            "trackGapNoiseInflate $trackGapNoiseInflate outside [$MIN_TRACK_GAP_NOISE_INFLATE, $MAX_TRACK_GAP_NOISE_INFLATE]"
        }
        require(trackMissVelocityTauMs in MIN_TRACK_DURATION_MS..MAX_TRACK_DURATION_MS) {
            "trackMissVelocityTauMs $trackMissVelocityTauMs outside [$MIN_TRACK_DURATION_MS, $MAX_TRACK_DURATION_MS]"
        }
        require(trackAccelSigmaRatio in MIN_TRACK_ACCEL_SIGMA_RATIO..MAX_TRACK_ACCEL_SIGMA_RATIO) {
            "trackAccelSigmaRatio $trackAccelSigmaRatio outside [$MIN_TRACK_ACCEL_SIGMA_RATIO, $MAX_TRACK_ACCEL_SIGMA_RATIO]"
        }
        require(trackHeightAccelSigmaRatio in MIN_TRACK_ACCEL_SIGMA_RATIO..MAX_TRACK_ACCEL_SIGMA_RATIO) {
            "trackHeightAccelSigmaRatio $trackHeightAccelSigmaRatio outside " +
                "[$MIN_TRACK_ACCEL_SIGMA_RATIO, $MAX_TRACK_ACCEL_SIGMA_RATIO]"
        }
        require(trackHeightNoiseRatio in MIN_TRACK_HEIGHT_NOISE_RATIO..MAX_TRACK_HEIGHT_NOISE_RATIO) {
            "trackHeightNoiseRatio $trackHeightNoiseRatio outside [$MIN_TRACK_HEIGHT_NOISE_RATIO, $MAX_TRACK_HEIGHT_NOISE_RATIO]"
        }
        require(trackInitVelocitySigmaRatio in MIN_TRACK_INIT_VELOCITY_SIGMA_RATIO..MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO) {
            "trackInitVelocitySigmaRatio $trackInitVelocitySigmaRatio outside " +
                "[$MIN_TRACK_INIT_VELOCITY_SIGMA_RATIO, $MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO]"
        }
        require(trackMatchMaxCost in MIN_TRACK_MATCH_MAX_COST..MAX_TRACK_MATCH_MAX_COST) {
            "trackMatchMaxCost $trackMatchMaxCost outside [$MIN_TRACK_MATCH_MAX_COST, $MAX_TRACK_MATCH_MAX_COST]"
        }
        require(trackLowMatchMaxCost in MIN_TRACK_MATCH_MAX_COST..MAX_TRACK_MATCH_MAX_COST) {
            "trackLowMatchMaxCost $trackLowMatchMaxCost outside [$MIN_TRACK_MATCH_MAX_COST, $MAX_TRACK_MATCH_MAX_COST]"
        }
        require(trackExitMarginRatio in MIN_TRACK_EXIT_MARGIN_RATIO..MAX_TRACK_EXIT_MARGIN_RATIO) {
            "trackExitMarginRatio $trackExitMarginRatio outside [$MIN_TRACK_EXIT_MARGIN_RATIO, $MAX_TRACK_EXIT_MARGIN_RATIO]"
        }
    }

    /** Minimum time between two HUD text updates, in nanoseconds (duration, not a timestamp). */
    val hudIntervalNs: Long
        get() = (NANOS_PER_SECOND / hudRateHz).toLong()

    /** Shortest time between two thermal reads, in nanoseconds (duration, not a timestamp). */
    val thermalSampleIntervalNs: Long
        get() = thermalSampleIntervalMs * NANOS_PER_MILLI

    /** Idle time at which tentative tracks are promoted, in nanoseconds (duration, not a timestamp). */
    val trackIdlePromoteNs: Long
        get() = trackIdlePromoteMs * NANOS_PER_MILLI

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

        /** Default [minHeadRingRadiusDp], in dp. */
        const val DEFAULT_MIN_HEAD_RING_RADIUS_DP = 12.0f

        /** Lowest accepted [minHeadRingRadiusDp], in dp (0 = no minimum). */
        const val MIN_MIN_HEAD_RING_RADIUS_DP = 0.0f

        /** Highest accepted [minHeadRingRadiusDp], in dp. */
        const val MAX_MIN_HEAD_RING_RADIUS_DP = 48.0f

        /** Default [boxCornerRatio]: unitless fraction of the box side. */
        const val DEFAULT_BOX_CORNER_RATIO = 0.18f

        /** [boxCornerRatio] must be above this (exclusive). */
        const val BOX_CORNER_RATIO_LOWER_EXCLUSIVE = 0.0f

        /** [boxCornerRatio] must be below this (exclusive): at 0.5 the two strokes of one side would meet. */
        const val BOX_CORNER_RATIO_UPPER_EXCLUSIVE = 0.5f

        /** Default [debugMarks]: the box and the keypoint dots are off. */
        const val DEFAULT_DEBUG_MARKS = false

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

        /** Nanoseconds per millisecond. */
        const val NANOS_PER_MILLI = 1_000_000L

        /** Default [timingWindowFrames]: about 17 s of a 30 fps capture. */
        const val DEFAULT_TIMING_WINDOW_FRAMES = 512

        /** Lowest accepted [timingWindowFrames], in frames. */
        const val MIN_TIMING_WINDOW_FRAMES = 16

        /** Highest accepted [timingWindowFrames], in frames. */
        const val MAX_TIMING_WINDOW_FRAMES = 4096

        /** Median, as a fraction in [0, 1], for the timing statistics in the diagnostics. */
        const val TIMING_MEDIAN_FRACTION = 0.5

        /** 95th percentile, as a fraction in [0, 1], for the timing statistics in the diagnostics. */
        const val TIMING_P95_FRACTION = 0.95

        /** Default [thermalSampleIntervalMs], in milliseconds. */
        const val DEFAULT_THERMAL_SAMPLE_INTERVAL_MS = 2000L

        /** Lowest accepted [thermalSampleIntervalMs], in milliseconds (the platform limits headroom reads to about 1 per second). */
        const val MIN_THERMAL_SAMPLE_INTERVAL_MS = 1000L

        /** Highest accepted [thermalSampleIntervalMs]: 1 minute, in milliseconds. */
        const val MAX_THERMAL_SAMPLE_INTERVAL_MS = 60_000L

        /** Default [headKeypointEnterScore]: unitless confidence. */
        const val DEFAULT_HEAD_KEYPOINT_ENTER_SCORE = 0.5f

        /** Default [headKeypointLeaveScore]: unitless confidence. */
        const val DEFAULT_HEAD_KEYPOINT_LEAVE_SCORE = 0.3f

        /** Lowest accepted [headKeypointEnterScore] / [headKeypointLeaveScore]. */
        const val MIN_HEAD_KEYPOINT_SCORE = 0.05f

        /** Highest accepted [headKeypointEnterScore] / [headKeypointLeaveScore]. */
        const val MAX_HEAD_KEYPOINT_SCORE = 0.95f

        /** Default [headOffsetAlpha]: unitless EMA weight. */
        const val DEFAULT_HEAD_OFFSET_ALPHA = 0.2f

        /** Lowest accepted [headOffsetAlpha] (the interval is (0, 1], so 0 itself is refused). */
        const val MIN_HEAD_OFFSET_ALPHA = 0.01f

        /** Highest accepted [headOffsetAlpha]. */
        const val MAX_HEAD_OFFSET_ALPHA = 1.0f

        /** Default [headFallbackTopRatio]: unitless fraction of the box height. */
        const val DEFAULT_HEAD_FALLBACK_TOP_RATIO = 0.10f

        /** Lowest accepted [headFallbackTopRatio]. */
        const val MIN_HEAD_FALLBACK_TOP_RATIO = 0.0f

        /** Highest accepted [headFallbackTopRatio]. */
        const val MAX_HEAD_FALLBACK_TOP_RATIO = 0.5f

        /** Default [headMeasurementNoiseRatio]: unitless fraction of the box height. */
        const val DEFAULT_HEAD_MEASUREMENT_NOISE_RATIO = 0.03f

        /** Lowest accepted [headMeasurementNoiseRatio]. */
        const val MIN_HEAD_MEASUREMENT_NOISE_RATIO = 0.005f

        /** Highest accepted [headMeasurementNoiseRatio]. */
        const val MAX_HEAD_MEASUREMENT_NOISE_RATIO = 0.2f

        /** Default [headFallbackNoiseRatio]: unitless fraction of the box height. */
        const val DEFAULT_HEAD_FALLBACK_NOISE_RATIO = 0.10f

        /** Lowest accepted [headFallbackNoiseRatio]. */
        const val MIN_HEAD_FALLBACK_NOISE_RATIO = 0.02f

        /** Highest accepted [headFallbackNoiseRatio]. */
        const val MAX_HEAD_FALLBACK_NOISE_RATIO = 0.5f

        /** Default [headMinMeanConfidence]: unitless confidence. */
        const val DEFAULT_HEAD_MIN_MEAN_CONFIDENCE = 0.05f

        /** Lowest accepted [headMinMeanConfidence] (above 0 so the variance stays finite). */
        const val MIN_HEAD_MIN_MEAN_CONFIDENCE = 0.01f

        /** Highest accepted [headMinMeanConfidence]. */
        const val MAX_HEAD_MIN_MEAN_CONFIDENCE = 0.5f

        /** Default [maxTracks], in tracks. */
        const val DEFAULT_MAX_TRACKS = 32

        /** Lowest accepted [maxTracks], in tracks. */
        const val MIN_MAX_TRACKS = 1

        /** Highest accepted [maxTracks], in tracks. */
        const val MAX_MAX_TRACKS = 128

        /** Default [trackGateChi2]: the 99 % point of chi-square with 2 degrees of freedom, unitless. */
        const val DEFAULT_TRACK_GATE_CHI2 = 9.21f

        /** Lowest accepted [trackGateChi2]. */
        const val MIN_TRACK_GATE_CHI2 = 1.0f

        /** Highest accepted [trackGateChi2]. */
        const val MAX_TRACK_GATE_CHI2 = 30.0f

        /** Default [assocIouWeight] and [assocHeadWeight]: the two cost terms count equally, unitless. */
        const val DEFAULT_ASSOC_WEIGHT = 0.5f

        /** Lowest accepted [assocIouWeight] / [assocHeadWeight] (above 0 so neither term can be switched off). */
        const val MIN_ASSOC_WEIGHT = 0.05f

        /** Highest accepted [assocIouWeight] / [assocHeadWeight]. */
        const val MAX_ASSOC_WEIGHT = 1.0f

        /** Default [assocHeadScaleRatio]: unitless fraction of the track box height. */
        const val DEFAULT_ASSOC_HEAD_SCALE_RATIO = 0.5f

        /** Lowest accepted [assocHeadScaleRatio]. */
        const val MIN_ASSOC_HEAD_SCALE_RATIO = 0.1f

        /** Highest accepted [assocHeadScaleRatio]. */
        const val MAX_ASSOC_HEAD_SCALE_RATIO = 2.0f

        /** Default [dedupeIou]: unitless. */
        const val DEFAULT_DEDUPE_IOU = 0.7f

        /** Lowest accepted [dedupeIou]. */
        const val MIN_DEDUPE_IOU = 0.3f

        /** Highest accepted [dedupeIou]. */
        const val MAX_DEDUPE_IOU = 0.95f

        /** Default [trackHighScore]: unitless confidence. */
        const val DEFAULT_TRACK_HIGH_SCORE = 0.5f

        /** Default [trackBirthScore]: unitless confidence. */
        const val DEFAULT_TRACK_BIRTH_SCORE = 0.25f

        /** Lowest accepted [trackHighScore] / [trackBirthScore]. */
        const val MIN_TRACK_SCORE = 0.0f

        /** Highest accepted [trackHighScore] / [trackBirthScore]. */
        const val MAX_TRACK_SCORE = 1.0f

        /** Default [trackConfirmHits], in frames. */
        const val DEFAULT_TRACK_CONFIRM_HITS = 3

        /** Lowest accepted [trackConfirmHits], in frames (1 = shown from the first detection). */
        const val MIN_TRACK_CONFIRM_HITS = 1

        /** Highest accepted [trackConfirmHits], in frames. */
        const val MAX_TRACK_CONFIRM_HITS = 10

        /** Default [trackIdlePromoteMs], in milliseconds. */
        const val DEFAULT_TRACK_IDLE_PROMOTE_MS = 400L

        /** Default [trackMaxLostMs], in milliseconds. */
        const val DEFAULT_TRACK_MAX_LOST_MS = 800f

        /** Default [trackMissVelocityTauMs], in milliseconds (Consult C3). */
        const val DEFAULT_TRACK_MISS_VELOCITY_TAU_MS = 300f

        /** Lowest accepted [trackMaxLostMs] / [trackMissVelocityTauMs], in milliseconds. */
        const val MIN_TRACK_DURATION_MS = 1f

        /** Highest accepted [trackMaxLostMs] / [trackMissVelocityTauMs]: 10 s, in milliseconds. */
        const val MAX_TRACK_DURATION_MS = 10_000f

        /** Lowest accepted [trackIdlePromoteMs], in milliseconds. */
        const val MIN_TRACK_DURATION_MS_LONG = 1L

        /** Highest accepted [trackIdlePromoteMs]: 10 s, in milliseconds. */
        const val MAX_TRACK_DURATION_MS_LONG = 10_000L

        /** Default [trackMaxDtMs], in milliseconds (Consult C3). */
        const val DEFAULT_TRACK_MAX_DT_MS = 250f

        /** Lowest accepted [trackMaxDtMs], in milliseconds (a step shorter than ordinary frame intervals would make every step a gap). */
        const val MIN_TRACK_MAX_DT_MS = 20f

        /** Highest accepted [trackMaxDtMs]: 5 s, in milliseconds. */
        const val MAX_TRACK_MAX_DT_MS = 5000f

        /** Default [trackGapNoiseInflate], unitless. */
        const val DEFAULT_TRACK_GAP_NOISE_INFLATE = 10f

        /** Lowest accepted [trackGapNoiseInflate] (1 = off). */
        const val MIN_TRACK_GAP_NOISE_INFLATE = 1f

        /** Highest accepted [trackGapNoiseInflate]. */
        const val MAX_TRACK_GAP_NOISE_INFLATE = 1000f

        /** Default [trackAccelSigmaRatio], in h/s². */
        const val DEFAULT_TRACK_ACCEL_SIGMA_RATIO = 8f

        /** Default [trackHeightAccelSigmaRatio], in h/s². */
        const val DEFAULT_TRACK_HEIGHT_ACCEL_SIGMA_RATIO = 2f

        /** Lowest accepted [trackAccelSigmaRatio] / [trackHeightAccelSigmaRatio], in h/s². */
        const val MIN_TRACK_ACCEL_SIGMA_RATIO = 0.01f

        /** Highest accepted [trackAccelSigmaRatio] / [trackHeightAccelSigmaRatio], in h/s². */
        const val MAX_TRACK_ACCEL_SIGMA_RATIO = 100f

        /** Default [trackHeightNoiseRatio]: unitless fraction of the predicted height. */
        const val DEFAULT_TRACK_HEIGHT_NOISE_RATIO = 0.05f

        /** Lowest accepted [trackHeightNoiseRatio]. */
        const val MIN_TRACK_HEIGHT_NOISE_RATIO = 0.005f

        /** Highest accepted [trackHeightNoiseRatio]. */
        const val MAX_TRACK_HEIGHT_NOISE_RATIO = 1.0f

        /** Default [trackInitVelocitySigmaRatio], in h/s. */
        const val DEFAULT_TRACK_INIT_VELOCITY_SIGMA_RATIO = 2f

        /** Lowest accepted [trackInitVelocitySigmaRatio], in h/s. */
        const val MIN_TRACK_INIT_VELOCITY_SIGMA_RATIO = 0.1f

        /** Highest accepted [trackInitVelocitySigmaRatio], in h/s. */
        const val MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO = 20f

        /** Default [trackMatchMaxCost], unitless (Consult C3). */
        const val DEFAULT_TRACK_MATCH_MAX_COST = 0.7f

        /** Default [trackLowMatchMaxCost], unitless (Consult C3). */
        const val DEFAULT_TRACK_LOW_MATCH_MAX_COST = 0.6f

        /** Lowest accepted [trackMatchMaxCost] / [trackLowMatchMaxCost] (above 0: a limit of 0 would allow no pair). */
        const val MIN_TRACK_MATCH_MAX_COST = 0.05f

        /** Highest accepted [trackMatchMaxCost] / [trackLowMatchMaxCost]: the association cost never exceeds 2. */
        const val MAX_TRACK_MATCH_MAX_COST = 2.0f

        /** Default [trackExitMarginRatio]: unitless fraction of the track height. */
        const val DEFAULT_TRACK_EXIT_MARGIN_RATIO = 0.1f

        /** Lowest accepted [trackExitMarginRatio]. */
        const val MIN_TRACK_EXIT_MARGIN_RATIO = 0.0f

        /** Highest accepted [trackExitMarginRatio]. */
        const val MAX_TRACK_EXIT_MARGIN_RATIO = 2.0f

        /** How far ahead, in seconds, the thermal headroom is forecast; 0 = the current headroom. */
        const val THERMAL_HEADROOM_FORECAST_SECONDS = 0

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

        /** Leading characters of a model's SHA-256 shown in diagnostics and on screen (a short, readable id). */
        const val SHA256_SHORT_CHARS = 12

        /** Leading characters of the model's SHA-256 that go into the staged file name (`<base>-<prefix>.<ext>`). */
        const val STAGED_MODEL_SHA_PREFIX_LENGTH = 16

        /** Default [importMinBytes]: 100 000 bytes; the smallest real pose model is a few megabytes. */
        const val DEFAULT_IMPORT_MIN_BYTES = 100_000L

        /** Default [importMaxBytes]: 200 MiB, in bytes. */
        const val DEFAULT_IMPORT_MAX_BYTES = 209_715_200L

        /** Lowest accepted [importMinBytes] / [importMaxBytes], in bytes. */
        const val MIN_IMPORT_BYTES_LIMIT = 1L

        /** Highest accepted [importMinBytes] / [importMaxBytes]: 1 GiB, in bytes (also the sanity cap of stored metadata). */
        const val MAX_IMPORT_BYTES_LIMIT = 1_073_741_824L

        /** Default [importMinInputSize], in pixels (Mdl). */
        const val DEFAULT_IMPORT_MIN_INPUT_SIZE = 160

        /** Default [importMaxInputSize], in pixels (Mdl). */
        const val DEFAULT_IMPORT_MAX_INPUT_SIZE = 1280

        /** Lowest accepted [importMinInputSize] / [importMaxInputSize], in pixels (Mdl). */
        const val MIN_IMPORT_INPUT_SIZE_LIMIT = 32

        /** Highest accepted [importMinInputSize] / [importMaxInputSize], in pixels (Mdl; also the sanity cap of stored metadata). */
        const val MAX_IMPORT_INPUT_SIZE_LIMIT = 4096

        /** Default [importMaxInputAspect]: long side at most 4 times the short side. */
        const val DEFAULT_IMPORT_MAX_INPUT_ASPECT = 4.0f

        /** Lowest accepted [importMaxInputAspect]: 1.0, square inputs only. */
        const val MIN_IMPORT_MAX_INPUT_ASPECT_LIMIT = 1.0f

        /** Highest accepted [importMaxInputAspect]. */
        const val MAX_IMPORT_MAX_INPUT_ASPECT_LIMIT = 16.0f

        /** An imported model's input width and height must each be a multiple of this, in pixels (the largest YOLO stride). */
        const val IMPORT_INPUT_SIZE_STEP = 32

        /**
         * Most rows (`N`) of an imported model's `[1, N, 57]` output: the vendored `PoseEstimator` treats 57
         * columns as an end-to-end head only up to its `END_TO_END_MAX_ROWS` (300, the Ultralytics default
         * `max_det`); a model with more rows would import and then fail at every Start. Also the sanity cap
         * of stored metadata.
         */
        const val IMPORT_MAX_OUTPUT_ROWS = 300

        /** Longest stored display name of an imported model, in characters. */
        const val IMPORT_NAME_MAX_CHARS = 64

        /** Display name used when a picked file's name has nothing usable left after cleaning. */
        const val IMPORT_FALLBACK_NAME = "model.tflite"

        /** Box values per output row of a pose model: x, y, w, h (or x1, y1, x2, y2). */
        const val POSE_BOX_VALUES = 4

        /** Score values per output row of a pose model: the person confidence. */
        const val POSE_SCORE_VALUES = 1

        /** Class values per output row of a pose model: the class index. */
        const val POSE_CLASS_VALUES = 1

        /** Keypoints per person in the COCO pose layout. */
        const val POSE_KEYPOINT_COUNT = 17

        /** Values per keypoint: x, y and confidence. */
        const val POSE_VALUES_PER_KEYPOINT = 3

        /** Columns per output row of an accepted pose model: 4 box + 1 score + 1 class + 17 * 3 keypoint values = 57. */
        const val POSE_OUTPUT_COLUMNS =
            POSE_BOX_VALUES + POSE_SCORE_VALUES + POSE_CLASS_VALUES + POSE_KEYPOINT_COUNT * POSE_VALUES_PER_KEYPOINT
    }
}
