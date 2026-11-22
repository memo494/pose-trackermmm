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
 * @property modelInputSize side of the square model input, in pixels (Mdl space), of the bundled SQUARE
 *   models: the safety fallback when the model of an orientation cannot be loaded (416) and the thermal
 *   fallback (320); only [MODEL_INPUT_SIZE_SMALL] (320) or [MODEL_INPUT_SIZE_LARGE] (416) is accepted. The
 *   built-in model of an orientation is the rectangular one of `BuiltInModels` (448x960 / 960x448). An imported model brings its own size, limited by
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
 * @property modelLibraryMax most imported models the model library holds, count, in
 *   [[MIN_MODEL_LIBRARY_MAX], [MAX_MODEL_LIBRARY_MAX]]; an import into a full library is refused until a model is deleted.
 * @property removeAllConfirmMs how long, in milliseconds, "Remove all imported models" waits for the second tap that
 *   confirms it, in [[MIN_REMOVE_ALL_CONFIRM_MS], [MAX_REMOVE_ALL_CONFIRM_MS]].
 * @property timingWindowFrames number of the newest processed frames each timing statistic (copy, pre, inference,
 *   post, frame, result age) is computed over, in frames, in [[MIN_TIMING_WINDOW_FRAMES], [MAX_TIMING_WINDOW_FRAMES]].
 * @property thermalSampleIntervalMs shortest time between two reads of the device's thermal status and headroom,
 *   in milliseconds, in [[MIN_THERMAL_SAMPLE_INTERVAL_MS], [MAX_THERMAL_SAMPLE_INTERVAL_MS]]; the platform
 *   answers `NaN` to a headroom request that comes too often.
 * @property thermalHotHeadroom thermal headroom (unitless, 1.0 = throttling) at or above which the device counts as hot
 *   (with a platform status of `MODERATE` or worse), in [[MIN_THERMAL_HEADROOM], [MAX_THERMAL_HEADROOM]]; the
 *   thermal step-down (ARCHITECTURE §7.2) then runs the small model.
 * @property thermalCoolHeadroom headroom at or below which the device counts as cool again (with a status of `LIGHT`
 *   or better), in [[MIN_THERMAL_HEADROOM], [MAX_THERMAL_HEADROOM]] and strictly below [thermalHotHeadroom]: the gap
 *   is the hysteresis that keeps the model from flapping.
 * @property thermalHotConfirmMs how long the hot reading must last, in milliseconds, before the step-down starts,
 *   in [[MIN_THERMAL_HOT_CONFIRM_MS], [MAX_THERMAL_HOT_CONFIRM_MS]].
 * @property thermalCoolDownMs how long the cool reading must last, in milliseconds, before the normal model comes
 *   back, in [[MIN_THERMAL_COOL_DOWN_MS], [MAX_THERMAL_COOL_DOWN_MS]].
 * @property minBoxHeightImg lowest person box height, Img pixels, that gives a head point, in
 *   [[MIN_MIN_BOX_HEIGHT_IMG], [MAX_MIN_BOX_HEIGHT_IMG]]; a lower box is unusable (no head point, ignored by the
 *   tracker). Head offsets are learned in units of the box height, so a box far below a pixel would make them explode.
 * @property maxTracks most tracks the tracker holds at once, in tracks, in [[MIN_MAX_TRACKS], [MAX_MAX_TRACKS]];
 *   the assignment matrix has this many rows. A detection that would start a track beyond it is not tracked.
 * @property trackGateChi2 association gate: a detection is a candidate for a track only when the squared
 *   Mahalanobis distance of its head point from the track's predicted head point (the two axes taken
 *   independently, using the innovation variances) is at most this value; chi-square with 2 degrees of
 *   freedom, unitless, in [[MIN_TRACK_GATE_CHI2], [MAX_TRACK_GATE_CHI2]]; 9.21 is the 99 % point.
 * @property trackGateMinSigmaRatio floor of the gate's per-axis standard deviation, as a fraction of the track box
 *   height (unitless), in [[MIN_TRACK_GATE_MIN_SIGMA_RATIO], [MAX_TRACK_GATE_MIN_SIGMA_RATIO]]: in the gate only, each
 *   axis variance is `max(P + R, (ratio * h)^2)`, because the filter's variance is far below the real jitter of the
 *   detector (M4f). The cost and its limits are unchanged; 0 switches the floor off (the M3 gate).
 * @property assocIouWeight weight of `1 - IoU` of the track box and the detection box in the association cost,
 *   unitless in [[MIN_ASSOC_WEIGHT], [MAX_ASSOC_WEIGHT]].
 * @property assocHeadWeight weight of the head distance term in the association cost, unitless in
 *   [[MIN_ASSOC_WEIGHT], [MAX_ASSOC_WEIGHT]].
 * @property assocHeadScaleRatio head distance, as a fraction of the track box height `h` (unitless), at which
 *   the head distance term reaches 1 and stops growing, in [[MIN_ASSOC_HEAD_SCALE_RATIO],
 *   [MAX_ASSOC_HEAD_SCALE_RATIO]]; 0.5 means half a person height.
 * @property dedupeIou two detections of one frame whose boxes overlap with at least this IoU (unitless) are
 *   one person: the lower score is dropped before association, in [[MIN_DEDUPE_IOU], [MAX_DEDUPE_IOU]].
 * @property rawNmsIou class-agnostic non-maximum suppression of a RAW pose output (`[1, 56, N]` or `[1, N, 56]`): of two
 *   boxes with an IoU above this (unitless), the lower score is dropped, in [[MIN_RAW_NMS_IOU], [MAX_RAW_NMS_IOU]].
 *   The end-to-end output (`[1, N, 57]`) has its suppression built into the model and does not use it.
 * @property trackHighScore a detection with at least this score (unitless) takes part in the first, strict matching
 *   stage of the tracker, in [[MIN_TRACK_SCORE], [MAX_TRACK_SCORE]]; lower ones only in the second stage.
 * @property trackBirthScore a detection that matches no track starts a new track only with at least this score
 *   (unitless), in [[MIN_TRACK_SCORE], [MAX_TRACK_SCORE]]; weaker detections can keep a track alive but never start one.
 * @property trackBirthSuppressIou an unmatched detection whose box overlaps, with at least this IoU (unitless), a
 *   detection that a confirmed or lost track took in the same frame is a near duplicate of that person and starts no
 *   track, in [[MIN_TRACK_BIRTH_SUPPRESS_IOU], [MAX_TRACK_BIRTH_SUPPRESS_IOU]]; it is looser than [dedupeIou], which
 *   drops the pair earlier.
 * @property trackBirthSuppressHeadRatio the head distance, as a fraction of the followed track's box height `h`
 *   (unitless), within which an overlapping box (see [trackBirthSuppressIou]) is one person and not another: a box
 *   that overlaps with the IoU limit but whose head is farther away may be a second person and is not suppressed;
 *   also decides which tentative tracks are shadows of an older track, in [[MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO],
 *   [MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO]].
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
 * @property trackAccelRefDtMs frame interval in milliseconds at and below which [trackAccelSigmaRatio] applies as it is;
 *   for a lower frame rate the head's acceleration sigma (so the noise variance by the square of it) is scaled by
 *   `(ref / interval)^2`, no lower than [trackAccelMinScale], with `interval` a moving average of the frame interval
 *   (see [trackAccelIntervalTauMs]); a track that coasts (LOST, or after a miss) is not scaled, so the filter's smoothing of a still head stays near its 20 fps level (S3 at 12 fps, F4); in
 *   [[MIN_TRACK_ACCEL_REF_DT_MS], [MAX_TRACK_ACCEL_REF_DT_MS]]. A gap step (longer than [trackMaxDtMs]) is not scaled.
 * @property trackAccelIntervalTauMs time constant in milliseconds of the moving average of the frame interval that
 *   the scaling reads (so one slow frame does not change the scale), in [[MIN_TRACK_ACCEL_INTERVAL_TAU_MS],
 *   [MAX_TRACK_ACCEL_INTERVAL_TAU_MS]]; gap steps are not averaged.
 * @property trackAccelMinScale lowest factor of that scaling, unitless, in [[MIN_TRACK_ACCEL_MIN_SCALE],
 *   [MAX_TRACK_ACCEL_MIN_SCALE]]; 1 = no scaling.
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
 * @property trackTentativeMaxMisses number of consecutive frames a tentative (not yet shown) track may go
 *   without a matching detection and still live, in frames, in [[MIN_TRACK_TENTATIVE_MAX_MISSES],
 *   [MAX_TRACK_TENTATIVE_MAX_MISSES]]; the track coasts (velocity decays, never shown, no lost time), a hit resets
 *   the count and does not undo hits already counted; 0 removes a tentative track on its first miss.
 * @property smoothEnabled `true` (default): the shown head points are passed through a display smoother (a OneEuro
 *   filter per track, `HeadSmoother`) before they are drawn, and the box moves with the smoothed head; `false` draws
 *   the tracker's head points as they are.
 * @property smoothMinCutoffHz cutoff frequency of the smoother for a head point that does not move, in hertz, in
 *   [[MIN_SMOOTH_MIN_CUTOFF_HZ], [MAX_SMOOTH_MIN_CUTOFF_HZ]]; lower = steadier at rest, more lag.
 * @property smoothBeta how fast the smoother's cutoff grows with the head point's speed, unitless (hertz per
 *   track height per second), in [[MIN_SMOOTH_BETA], [MAX_SMOOTH_BETA]]; cutoff = [smoothMinCutoffHz] + beta *
 *   speed / track height; 0 = a fixed cutoff.
 * @property smoothDerivativeCutoffHz cutoff frequency, in hertz, of the filter that measures the head point's speed
 *   for the smoother, in [[MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ], [MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ]].
 * @property displayLeadEnabled `true` (default): a shown head that moves fast is drawn a little ahead of its smoothed
 *   point (a forward lead of `speed * L`, display only; the tracker, the association and the lock never see it) to
 *   make up the age of the result; `false` draws the smoothed point as it is.
 * @property displayLeadMaxMs longest lead time `L` in milliseconds, in [[MIN_DISPLAY_LEAD_MAX_MS],
 *   [MAX_DISPLAY_LEAD_MAX_MS]]; `L = min(result age + displayLeadExtraMs + half the frame interval, this)`; 0 = no lead.
 * @property displayLeadExtraMs milliseconds added to the result age for what happens after publishing (the draw and
 *   the screen), in [[MIN_DISPLAY_LEAD_EXTRA_MS], [MAX_DISPLAY_LEAD_EXTRA_MS]].
 * @property displayLeadSpeedLowHps filtered speed in track heights per second at and below which there is no lead,
 *   in [[MIN_DISPLAY_LEAD_SPEED_HPS], [MAX_DISPLAY_LEAD_SPEED_HPS]]; keeps a still or trembling head exactly where
 *   the smoother puts it.
 * @property displayLeadSpeedHighHps filtered speed in heights per second at and above which the lead is full, in
 *   [[MIN_DISPLAY_LEAD_SPEED_HPS], [MAX_DISPLAY_LEAD_SPEED_HPS]], not below [displayLeadSpeedLowHps]; the lead grows
 *   linearly in between.
 * @property displayLeadStopFullRatio share of the tracker's speed that the measured head must still have along the
 *   tracker's direction for the lead to be full, in [[MIN_DISPLAY_LEAD_STOP_FULL_RATIO],
 *   [MAX_DISPLAY_LEAD_STOP_FULL_RATIO]]; below it the lead shrinks in proportion (a stop gives 0). A value under 1 keeps
 *   the noise of the measured step from making the dot of a steady walker tremble.
 * @property displayLeadFadeMs time constant in milliseconds with which the lead that is on screen decays when it is
 *   switched off (a miss, an estimated head, a lost track), in [[MIN_DISPLAY_LEAD_FADE_MS], [MAX_DISPLAY_LEAD_FADE_MS]],
 *   about one frame interval at low rates, so the dot does not jump back by the whole lead; 0 = cut at once. A fade
 *   only lowers the lead: it never grows and never exceeds `displayLeadMaxRatio`.
 * @property displayLeadMaxRatio longest lead as a multiple of the track height (unitless), in
 *   [[MIN_DISPLAY_LEAD_MAX_RATIO], [MAX_DISPLAY_LEAD_MAX_RATIO]].
 * @property fadeMinAlpha lowest opacity, unitless, of the marks of a `LOST` track: the opacity falls from 1 to
 *   this as the lost time grows from 0 to [trackMaxLostMs] (`max(fadeMinAlpha, 1 - lostMs / trackMaxLostMs)`), in
 *   [[MIN_FADE_MIN_ALPHA], [MAX_FADE_MIN_ALPHA]]; 1 = no fading.
 * @property fadeStepAlpha smallest change of a track's opacity, unitless, that redraws the overlay (so the fade is
 *   drawn in steps, not on every frame), in [[MIN_FADE_STEP_ALPHA], [MAX_FADE_STEP_ALPHA]].
 * @property lockHandleSizeDp side of the small touchable handle window used to lock a person (tap-to-lock), in dp,
 *   in [[MIN_LOCK_HANDLE_SIZE_DP], [MAX_LOCK_HANDLE_SIZE_DP]].
 * @property lockMaxTapDistanceRatio farthest a tap may be from a track's head point to lock it, as a fraction of that
 *   track's box height (unitless), in [[MIN_LOCK_MAX_TAP_DISTANCE_RATIO], [MAX_LOCK_MAX_TAP_DISTANCE_RATIO]]; a tap
 *   farther from every track locks nothing.
 * @property lockArmTimeoutMs how long, in milliseconds, the overlay stays touchable after the handle was tapped
 *   (waiting for the tap on a person), in [[MIN_LOCK_ARM_TIMEOUT_MS], [MAX_LOCK_ARM_TIMEOUT_MS]]; see [lockArmTimeoutNs].
 * @property lockRingStrokeDp stroke width of the locked person's rings, in dp, in [[MIN_STROKE_DP], [MAX_STROKE_DP]].
 * @property lockOuterRingRatio radius of the second ring around the locked person's head ring, as a multiple of the
 *   head ring's radius (unitless), in [[MIN_LOCK_OUTER_RING_RATIO], [MAX_LOCK_OUTER_RING_RATIO]].
 * @property relockHueBins hue bins of the appearance signature (re-locking a person by clothing colours), in
 *   [[MIN_RELOCK_HUE_BINS], [MAX_RELOCK_HUE_BINS]].
 * @property relockSatBins saturation bins per hue bin, in [[MIN_RELOCK_SAT_BINS], [MAX_RELOCK_SAT_BINS]]; they split
 *   the saturation range from [relockMinSaturation] to 1.
 * @property relockGreyBins value bins for the pixels that are too dark or too grey to have a hue, in
 *   [[MIN_RELOCK_GREY_BINS], [MAX_RELOCK_GREY_BINS]].
 * @property relockMinValue a pixel whose value (brightness, unitless 0..1) is below this counts as grey, in
 *   [[MIN_RELOCK_COLOUR_LIMIT], [MAX_RELOCK_COLOUR_LIMIT]].
 * @property relockMinSaturation a pixel whose saturation (unitless 0..1) is below this counts as grey, in
 *   [[MIN_RELOCK_COLOUR_LIMIT], [MAX_RELOCK_COLOUR_LIMIT]].
 * @property relockMaxSamplePixels most pixels one signature sample reads (a grid over the torso), count, in
 *   [[MIN_RELOCK_SAMPLE_PIXELS], [MAX_RELOCK_SAMPLE_PIXELS]].
 * @property relockSignatureAlpha weight of a new sample in the exponential average of the locked person's signature,
 *   unitless, in [[MIN_RELOCK_SIGNATURE_ALPHA], [MAX_RELOCK_SIGNATURE_ALPHA]]; 1 = the newest sample replaces it.
 * @property relockTorsoShrink fraction of the torso rectangle's width and of its height cut from every side before
 *   sampling (edges hold background), unitless, in [0, [MAX_RELOCK_TORSO_SHRINK]].
 * @property relockBoxTopRatio top of the torso, as a fraction of the person box's height from its top, used when the
 *   shoulder and hip keypoints are not all confident, in [0, [MAX_RELOCK_BOX_TOP_RATIO]]; below [relockBoxBottomRatio].
 * @property relockBoxBottomRatio bottom of that torso, same unit, in [[MIN_RELOCK_BOX_BOTTOM_RATIO], 1].
 * @property relockBoxWidthRatio width of that torso as a fraction of the person box's width (centred), in
 *   [[MIN_RELOCK_BOX_WIDTH_RATIO], 1].
 * @property relockKeypointMinScore least confidence, unitless, of each of the four shoulder and hip keypoints for
 *   the torso to be taken from them, in [[MIN_KEYPOINT_MIN_SCORE], [MAX_KEYPOINT_MIN_SCORE]].
 * @property relockMemoryMs how long, in milliseconds of frame time, a lock whose track was removed waits for the
 *   person to come back, in [[MIN_RELOCK_MEMORY_MS], [MAX_RELOCK_MEMORY_MS]]; see [relockMemoryNs].
 * @property relockMaxDistance largest Bhattacharyya distance (unitless, 0 = same colours, 1 = nothing in common)
 *   between a new track and the remembered signature for the lock to move to it, in
 *   [[MIN_RELOCK_MAX_DISTANCE], [MAX_RELOCK_MAX_DISTANCE]].
 * @property relockMinMargin how much farther every other shown track must be than the candidate, in the same
 *   distance unit, in [0, [MAX_RELOCK_MIN_MARGIN]]; closer than that = doubt = no re-lock.
 * @property relockMinSamplePixels fewest pixels a torso sample must read, count, in
 *   [[MIN_RELOCK_MIN_SAMPLE_PIXELS], [MAX_RELOCK_MIN_SAMPLE_PIXELS]], not above
 *   [relockMaxSamplePixels] / [RELOCK_SAMPLE_GRID_SLACK]; fewer = no torso (too small to tell colours).
 * @property relockFullBodyMinAspect least height-to-width ratio (unitless) of a person box for its fixed torso part to be
 *   used when the keypoints are not all confident, in [[MIN_RELOCK_FULL_BODY_ASPECT], [MAX_RELOCK_FULL_BODY_ASPECT]]; a
 *   wider box (a half-visible or sitting person) gives no torso.
 * @property relockJudgeFrames how many of its first matched frames, count, a new track is compared with a pending lock
 *   (each frame is judged until one is accepted), in [[MIN_RELOCK_JUDGE_FRAMES], [MAX_RELOCK_JUDGE_FRAMES]]; one reject
 *   is counted and logged when the window ends without an acceptance.
 * @property relockJudgeMaxFrames how many matched frames, count, a new track may be looked at for a re-lock in all,
 *   frames with no readable torso included (those do not count toward [relockJudgeFrames]); a track that has not been
 *   accepted or judged on [relockJudgeFrames] readable frames after that many is rejected once, in
 *   [[relockJudgeFrames], [MAX_RELOCK_JUDGE_MAX_FRAMES]].
 * @property relockMinSourceSamples fewest frames, count, a signature bucket (whole torso or upper body) must hold
 *   before candidates are compared with it: one frame may be a bad one (an occluder, a stranger), so a bucket below this
 *   is "not comparable" (never a match), in [[MIN_RELOCK_MIN_SOURCE_SAMPLES], [MAX_RELOCK_MIN_SOURCE_SAMPLES]].
 * @property relockOccluderMaxOverlap largest share, unitless, of the locked person's torso rectangle that another
 *   detected person's box may cover for the frame to still be added to the signature (the pixels would be that
 *   person's clothes), in [[MIN_RELOCK_OCCLUDER_MAX_OVERLAP], 1].
 * @property relockUpperTorsoRatio height of the upper-body torso, as a multiple of the horizontal distance between the
 *   shoulders, below the lower shoulder, used when the hips are not confident, the box is not a whole body and both
 *   shoulders are, unitless, in [[MIN_RELOCK_UPPER_TORSO_RATIO], [MAX_RELOCK_UPPER_TORSO_RATIO]].
 * @property assocMissNearRatio diagnostics only (no effect on the tracks): a track born while a LOST track's predicted
 *   head is within this multiple of the lost track's height of the new head is recorded as an association miss,
 *   unitless, in [[MIN_ASSOC_MISS_NEAR_RATIO], [MAX_ASSOC_MISS_NEAR_RATIO]].
 * @property assocMissLogPerMin most association-miss log lines the app writes per minute (the counters always
 *   count), count, in [[MIN_ASSOC_MISS_LOG_PER_MIN], [MAX_ASSOC_MISS_LOG_PER_MIN]]; 0 writes none.
 * @property relockEarlyMaxHeadRatio early re-lock (M4f), while the held track is only LOST: a new track may be
 *   accepted only when its head is within this multiple of the held track's height of the held track's predicted
 *   head, unitless, in [[MIN_RELOCK_EARLY_MAX_HEAD_RATIO], [MAX_RELOCK_EARLY_MAX_HEAD_RATIO]]; and no other track
 *   (tentative ones too) may be within the same radius. Otherwise the lock waits for the removal of the held track.
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
    val modelLibraryMax: Int = DEFAULT_MODEL_LIBRARY_MAX,
    val removeAllConfirmMs: Long = DEFAULT_REMOVE_ALL_CONFIRM_MS,
    val timingWindowFrames: Int = DEFAULT_TIMING_WINDOW_FRAMES,
    val thermalSampleIntervalMs: Long = DEFAULT_THERMAL_SAMPLE_INTERVAL_MS,
    val thermalHotHeadroom: Float = DEFAULT_THERMAL_HOT_HEADROOM,
    val thermalCoolHeadroom: Float = DEFAULT_THERMAL_COOL_HEADROOM,
    val thermalHotConfirmMs: Long = DEFAULT_THERMAL_HOT_CONFIRM_MS,
    val thermalCoolDownMs: Long = DEFAULT_THERMAL_COOL_DOWN_MS,
    val headKeypointEnterScore: Float = DEFAULT_HEAD_KEYPOINT_ENTER_SCORE,
    val headKeypointLeaveScore: Float = DEFAULT_HEAD_KEYPOINT_LEAVE_SCORE,
    val headOffsetAlpha: Float = DEFAULT_HEAD_OFFSET_ALPHA,
    val headFallbackTopRatio: Float = DEFAULT_HEAD_FALLBACK_TOP_RATIO,
    val headMeasurementNoiseRatio: Float = DEFAULT_HEAD_MEASUREMENT_NOISE_RATIO,
    val headFallbackNoiseRatio: Float = DEFAULT_HEAD_FALLBACK_NOISE_RATIO,
    val headMinMeanConfidence: Float = DEFAULT_HEAD_MIN_MEAN_CONFIDENCE,
    val minBoxHeightImg: Float = DEFAULT_MIN_BOX_HEIGHT_IMG,
    val maxTracks: Int = DEFAULT_MAX_TRACKS,
    val trackGateChi2: Float = DEFAULT_TRACK_GATE_CHI2,
    val trackGateMinSigmaRatio: Float = DEFAULT_TRACK_GATE_MIN_SIGMA_RATIO,
    val assocIouWeight: Float = DEFAULT_ASSOC_WEIGHT,
    val assocHeadWeight: Float = DEFAULT_ASSOC_WEIGHT,
    val assocHeadScaleRatio: Float = DEFAULT_ASSOC_HEAD_SCALE_RATIO,
    val dedupeIou: Float = DEFAULT_DEDUPE_IOU,
    val rawNmsIou: Float = DEFAULT_RAW_NMS_IOU,
    val trackHighScore: Float = DEFAULT_TRACK_HIGH_SCORE,
    val trackBirthScore: Float = DEFAULT_TRACK_BIRTH_SCORE,
    val trackBirthSuppressIou: Float = DEFAULT_TRACK_BIRTH_SUPPRESS_IOU,
    val trackBirthSuppressHeadRatio: Float = DEFAULT_TRACK_BIRTH_SUPPRESS_HEAD_RATIO,
    val trackConfirmHits: Int = DEFAULT_TRACK_CONFIRM_HITS,
    val trackIdlePromoteMs: Long = DEFAULT_TRACK_IDLE_PROMOTE_MS,
    val trackMaxLostMs: Float = DEFAULT_TRACK_MAX_LOST_MS,
    val trackMaxDtMs: Float = DEFAULT_TRACK_MAX_DT_MS,
    val trackGapNoiseInflate: Float = DEFAULT_TRACK_GAP_NOISE_INFLATE,
    val trackMissVelocityTauMs: Float = DEFAULT_TRACK_MISS_VELOCITY_TAU_MS,
    val trackAccelSigmaRatio: Float = DEFAULT_TRACK_ACCEL_SIGMA_RATIO,
    val trackAccelRefDtMs: Float = DEFAULT_TRACK_ACCEL_REF_DT_MS,
    val trackAccelMinScale: Float = DEFAULT_TRACK_ACCEL_MIN_SCALE,
    val trackAccelIntervalTauMs: Float = DEFAULT_TRACK_ACCEL_INTERVAL_TAU_MS,
    val trackHeightAccelSigmaRatio: Float = DEFAULT_TRACK_HEIGHT_ACCEL_SIGMA_RATIO,
    val trackHeightNoiseRatio: Float = DEFAULT_TRACK_HEIGHT_NOISE_RATIO,
    val trackInitVelocitySigmaRatio: Float = DEFAULT_TRACK_INIT_VELOCITY_SIGMA_RATIO,
    val trackMatchMaxCost: Float = DEFAULT_TRACK_MATCH_MAX_COST,
    val trackLowMatchMaxCost: Float = DEFAULT_TRACK_LOW_MATCH_MAX_COST,
    val trackExitMarginRatio: Float = DEFAULT_TRACK_EXIT_MARGIN_RATIO,
    val trackTentativeMaxMisses: Int = DEFAULT_TRACK_TENTATIVE_MAX_MISSES,
    val smoothEnabled: Boolean = DEFAULT_SMOOTH_ENABLED,
    val smoothMinCutoffHz: Float = DEFAULT_SMOOTH_MIN_CUTOFF_HZ,
    val smoothBeta: Float = DEFAULT_SMOOTH_BETA,
    val smoothDerivativeCutoffHz: Float = DEFAULT_SMOOTH_DERIVATIVE_CUTOFF_HZ,
    val displayLeadEnabled: Boolean = DEFAULT_DISPLAY_LEAD_ENABLED,
    val displayLeadMaxMs: Float = DEFAULT_DISPLAY_LEAD_MAX_MS,
    val displayLeadExtraMs: Float = DEFAULT_DISPLAY_LEAD_EXTRA_MS,
    val displayLeadSpeedLowHps: Float = DEFAULT_DISPLAY_LEAD_SPEED_LOW_HPS,
    val displayLeadSpeedHighHps: Float = DEFAULT_DISPLAY_LEAD_SPEED_HIGH_HPS,
    val displayLeadStopFullRatio: Float = DEFAULT_DISPLAY_LEAD_STOP_FULL_RATIO,
    val displayLeadFadeMs: Float = DEFAULT_DISPLAY_LEAD_FADE_MS,
    val displayLeadMaxRatio: Float = DEFAULT_DISPLAY_LEAD_MAX_RATIO,
    val fadeMinAlpha: Float = DEFAULT_FADE_MIN_ALPHA,
    val fadeStepAlpha: Float = DEFAULT_FADE_STEP_ALPHA,
    val lockHandleSizeDp: Float = DEFAULT_LOCK_HANDLE_SIZE_DP,
    val lockMaxTapDistanceRatio: Float = DEFAULT_LOCK_MAX_TAP_DISTANCE_RATIO,
    val lockArmTimeoutMs: Long = DEFAULT_LOCK_ARM_TIMEOUT_MS,
    val lockRingStrokeDp: Float = DEFAULT_LOCK_RING_STROKE_DP,
    val lockOuterRingRatio: Float = DEFAULT_LOCK_OUTER_RING_RATIO,
    val relockHueBins: Int = DEFAULT_RELOCK_HUE_BINS,
    val relockSatBins: Int = DEFAULT_RELOCK_SAT_BINS,
    val relockGreyBins: Int = DEFAULT_RELOCK_GREY_BINS,
    val relockMinValue: Float = DEFAULT_RELOCK_MIN_VALUE,
    val relockMinSaturation: Float = DEFAULT_RELOCK_MIN_SATURATION,
    val relockMaxSamplePixels: Int = DEFAULT_RELOCK_SAMPLE_PIXELS,
    val relockSignatureAlpha: Float = DEFAULT_RELOCK_SIGNATURE_ALPHA,
    val relockTorsoShrink: Float = DEFAULT_RELOCK_TORSO_SHRINK,
    val relockBoxTopRatio: Float = DEFAULT_RELOCK_BOX_TOP_RATIO,
    val relockBoxBottomRatio: Float = DEFAULT_RELOCK_BOX_BOTTOM_RATIO,
    val relockBoxWidthRatio: Float = DEFAULT_RELOCK_BOX_WIDTH_RATIO,
    val relockKeypointMinScore: Float = DEFAULT_RELOCK_KEYPOINT_MIN_SCORE,
    val relockMemoryMs: Long = DEFAULT_RELOCK_MEMORY_MS,
    val relockMaxDistance: Float = DEFAULT_RELOCK_MAX_DISTANCE,
    val relockMinMargin: Float = DEFAULT_RELOCK_MIN_MARGIN,
    val relockMinSamplePixels: Int = DEFAULT_RELOCK_MIN_SAMPLE_PIXELS,
    val relockFullBodyMinAspect: Float = DEFAULT_RELOCK_FULL_BODY_ASPECT,
    val relockJudgeFrames: Int = DEFAULT_RELOCK_JUDGE_FRAMES,
    val relockUpperTorsoRatio: Float = DEFAULT_RELOCK_UPPER_TORSO_RATIO,
    val relockJudgeMaxFrames: Int = DEFAULT_RELOCK_JUDGE_MAX_FRAMES,
    val relockMinSourceSamples: Int = DEFAULT_RELOCK_MIN_SOURCE_SAMPLES,
    val relockOccluderMaxOverlap: Float = DEFAULT_RELOCK_OCCLUDER_MAX_OVERLAP,
    val assocMissNearRatio: Float = DEFAULT_ASSOC_MISS_NEAR_RATIO,
    val assocMissLogPerMin: Int = DEFAULT_ASSOC_MISS_LOG_PER_MIN,
    val relockEarlyMaxHeadRatio: Float = DEFAULT_RELOCK_EARLY_MAX_HEAD_RATIO,
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
        require(modelLibraryMax in MIN_MODEL_LIBRARY_MAX..MAX_MODEL_LIBRARY_MAX) {
            "modelLibraryMax $modelLibraryMax outside [$MIN_MODEL_LIBRARY_MAX, $MAX_MODEL_LIBRARY_MAX]"
        }
        require(removeAllConfirmMs in MIN_REMOVE_ALL_CONFIRM_MS..MAX_REMOVE_ALL_CONFIRM_MS) {
            "removeAllConfirmMs $removeAllConfirmMs outside [$MIN_REMOVE_ALL_CONFIRM_MS, $MAX_REMOVE_ALL_CONFIRM_MS]"
        }
        require(timingWindowFrames in MIN_TIMING_WINDOW_FRAMES..MAX_TIMING_WINDOW_FRAMES) {
            "timingWindowFrames $timingWindowFrames outside [$MIN_TIMING_WINDOW_FRAMES, $MAX_TIMING_WINDOW_FRAMES]"
        }
        require(thermalSampleIntervalMs in MIN_THERMAL_SAMPLE_INTERVAL_MS..MAX_THERMAL_SAMPLE_INTERVAL_MS) {
            "thermalSampleIntervalMs $thermalSampleIntervalMs outside " +
                "[$MIN_THERMAL_SAMPLE_INTERVAL_MS, $MAX_THERMAL_SAMPLE_INTERVAL_MS]"
        }
        require(thermalHotHeadroom in MIN_THERMAL_HEADROOM..MAX_THERMAL_HEADROOM) {
            "thermalHotHeadroom $thermalHotHeadroom outside [$MIN_THERMAL_HEADROOM, $MAX_THERMAL_HEADROOM]"
        }
        require(thermalCoolHeadroom in MIN_THERMAL_HEADROOM..MAX_THERMAL_HEADROOM) {
            "thermalCoolHeadroom $thermalCoolHeadroom outside [$MIN_THERMAL_HEADROOM, $MAX_THERMAL_HEADROOM]"
        }
        require(thermalCoolHeadroom < thermalHotHeadroom) {
            "thermalCoolHeadroom $thermalCoolHeadroom must be below thermalHotHeadroom $thermalHotHeadroom"
        }
        require(thermalHotConfirmMs in MIN_THERMAL_HOT_CONFIRM_MS..MAX_THERMAL_HOT_CONFIRM_MS) {
            "thermalHotConfirmMs $thermalHotConfirmMs outside [$MIN_THERMAL_HOT_CONFIRM_MS, $MAX_THERMAL_HOT_CONFIRM_MS]"
        }
        require(thermalCoolDownMs in MIN_THERMAL_COOL_DOWN_MS..MAX_THERMAL_COOL_DOWN_MS) {
            "thermalCoolDownMs $thermalCoolDownMs outside [$MIN_THERMAL_COOL_DOWN_MS, $MAX_THERMAL_COOL_DOWN_MS]"
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
        require(minBoxHeightImg in MIN_MIN_BOX_HEIGHT_IMG..MAX_MIN_BOX_HEIGHT_IMG) {
            "minBoxHeightImg $minBoxHeightImg outside [$MIN_MIN_BOX_HEIGHT_IMG, $MAX_MIN_BOX_HEIGHT_IMG]"
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
        require(trackGateMinSigmaRatio in MIN_TRACK_GATE_MIN_SIGMA_RATIO..MAX_TRACK_GATE_MIN_SIGMA_RATIO) {
            "trackGateMinSigmaRatio $trackGateMinSigmaRatio outside " +
                "[$MIN_TRACK_GATE_MIN_SIGMA_RATIO, $MAX_TRACK_GATE_MIN_SIGMA_RATIO]"
        }
        require(assocHeadScaleRatio in MIN_ASSOC_HEAD_SCALE_RATIO..MAX_ASSOC_HEAD_SCALE_RATIO) {
            "assocHeadScaleRatio $assocHeadScaleRatio outside [$MIN_ASSOC_HEAD_SCALE_RATIO, $MAX_ASSOC_HEAD_SCALE_RATIO]"
        }
        require(dedupeIou in MIN_DEDUPE_IOU..MAX_DEDUPE_IOU) {
            "dedupeIou $dedupeIou outside [$MIN_DEDUPE_IOU, $MAX_DEDUPE_IOU]"
        }
        require(rawNmsIou in MIN_RAW_NMS_IOU..MAX_RAW_NMS_IOU) {
            "rawNmsIou $rawNmsIou outside [$MIN_RAW_NMS_IOU, $MAX_RAW_NMS_IOU]"
        }
        require(trackHighScore in MIN_TRACK_SCORE..MAX_TRACK_SCORE) {
            "trackHighScore $trackHighScore outside [$MIN_TRACK_SCORE, $MAX_TRACK_SCORE]"
        }
        require(trackBirthScore in MIN_TRACK_SCORE..MAX_TRACK_SCORE) {
            "trackBirthScore $trackBirthScore outside [$MIN_TRACK_SCORE, $MAX_TRACK_SCORE]"
        }
        require(trackBirthSuppressIou in MIN_TRACK_BIRTH_SUPPRESS_IOU..MAX_TRACK_BIRTH_SUPPRESS_IOU) {
            "trackBirthSuppressIou $trackBirthSuppressIou outside [$MIN_TRACK_BIRTH_SUPPRESS_IOU, $MAX_TRACK_BIRTH_SUPPRESS_IOU]"
        }
        require(trackBirthSuppressHeadRatio in MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO..MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO) {
            "trackBirthSuppressHeadRatio $trackBirthSuppressHeadRatio outside " +
                "[$MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO, $MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO]"
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
        require(trackAccelRefDtMs in MIN_TRACK_ACCEL_REF_DT_MS..MAX_TRACK_ACCEL_REF_DT_MS) {
            "trackAccelRefDtMs $trackAccelRefDtMs outside [$MIN_TRACK_ACCEL_REF_DT_MS, $MAX_TRACK_ACCEL_REF_DT_MS]"
        }
        require(trackAccelMinScale in MIN_TRACK_ACCEL_MIN_SCALE..MAX_TRACK_ACCEL_MIN_SCALE) {
            "trackAccelMinScale $trackAccelMinScale outside [$MIN_TRACK_ACCEL_MIN_SCALE, $MAX_TRACK_ACCEL_MIN_SCALE]"
        }
        require(trackAccelIntervalTauMs in MIN_TRACK_ACCEL_INTERVAL_TAU_MS..MAX_TRACK_ACCEL_INTERVAL_TAU_MS) {
            "trackAccelIntervalTauMs $trackAccelIntervalTauMs outside " +
                "[$MIN_TRACK_ACCEL_INTERVAL_TAU_MS, $MAX_TRACK_ACCEL_INTERVAL_TAU_MS]"
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
        require(trackTentativeMaxMisses in MIN_TRACK_TENTATIVE_MAX_MISSES..MAX_TRACK_TENTATIVE_MAX_MISSES) {
            "trackTentativeMaxMisses $trackTentativeMaxMisses outside " +
                "[$MIN_TRACK_TENTATIVE_MAX_MISSES, $MAX_TRACK_TENTATIVE_MAX_MISSES]"
        }
        require(smoothMinCutoffHz in MIN_SMOOTH_MIN_CUTOFF_HZ..MAX_SMOOTH_MIN_CUTOFF_HZ) {
            "smoothMinCutoffHz $smoothMinCutoffHz outside [$MIN_SMOOTH_MIN_CUTOFF_HZ, $MAX_SMOOTH_MIN_CUTOFF_HZ]"
        }
        require(smoothBeta in MIN_SMOOTH_BETA..MAX_SMOOTH_BETA) {
            "smoothBeta $smoothBeta outside [$MIN_SMOOTH_BETA, $MAX_SMOOTH_BETA]"
        }
        require(smoothDerivativeCutoffHz in MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ..MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ) {
            "smoothDerivativeCutoffHz $smoothDerivativeCutoffHz outside " +
                "[$MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ, $MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ]"
        }
        require(displayLeadMaxMs in MIN_DISPLAY_LEAD_MAX_MS..MAX_DISPLAY_LEAD_MAX_MS) {
            "displayLeadMaxMs $displayLeadMaxMs outside [$MIN_DISPLAY_LEAD_MAX_MS, $MAX_DISPLAY_LEAD_MAX_MS]"
        }
        require(displayLeadExtraMs in MIN_DISPLAY_LEAD_EXTRA_MS..MAX_DISPLAY_LEAD_EXTRA_MS) {
            "displayLeadExtraMs $displayLeadExtraMs outside [$MIN_DISPLAY_LEAD_EXTRA_MS, $MAX_DISPLAY_LEAD_EXTRA_MS]"
        }
        require(displayLeadSpeedLowHps in MIN_DISPLAY_LEAD_SPEED_HPS..MAX_DISPLAY_LEAD_SPEED_HPS) {
            "displayLeadSpeedLowHps $displayLeadSpeedLowHps outside [$MIN_DISPLAY_LEAD_SPEED_HPS, $MAX_DISPLAY_LEAD_SPEED_HPS]"
        }
        require(displayLeadSpeedHighHps in displayLeadSpeedLowHps..MAX_DISPLAY_LEAD_SPEED_HPS) {
            "displayLeadSpeedHighHps $displayLeadSpeedHighHps outside [$displayLeadSpeedLowHps (the low speed), $MAX_DISPLAY_LEAD_SPEED_HPS]"
        }
        require(displayLeadStopFullRatio in MIN_DISPLAY_LEAD_STOP_FULL_RATIO..MAX_DISPLAY_LEAD_STOP_FULL_RATIO) {
            "displayLeadStopFullRatio $displayLeadStopFullRatio outside " +
                "[$MIN_DISPLAY_LEAD_STOP_FULL_RATIO, $MAX_DISPLAY_LEAD_STOP_FULL_RATIO]"
        }
        require(displayLeadFadeMs in MIN_DISPLAY_LEAD_FADE_MS..MAX_DISPLAY_LEAD_FADE_MS) {
            "displayLeadFadeMs $displayLeadFadeMs outside [$MIN_DISPLAY_LEAD_FADE_MS, $MAX_DISPLAY_LEAD_FADE_MS]"
        }
        require(displayLeadMaxRatio in MIN_DISPLAY_LEAD_MAX_RATIO..MAX_DISPLAY_LEAD_MAX_RATIO) {
            "displayLeadMaxRatio $displayLeadMaxRatio outside [$MIN_DISPLAY_LEAD_MAX_RATIO, $MAX_DISPLAY_LEAD_MAX_RATIO]"
        }
        require(fadeMinAlpha in MIN_FADE_MIN_ALPHA..MAX_FADE_MIN_ALPHA) {
            "fadeMinAlpha $fadeMinAlpha outside [$MIN_FADE_MIN_ALPHA, $MAX_FADE_MIN_ALPHA]"
        }
        require(fadeStepAlpha in MIN_FADE_STEP_ALPHA..MAX_FADE_STEP_ALPHA) {
            "fadeStepAlpha $fadeStepAlpha outside [$MIN_FADE_STEP_ALPHA, $MAX_FADE_STEP_ALPHA]"
        }
        require(lockHandleSizeDp in MIN_LOCK_HANDLE_SIZE_DP..MAX_LOCK_HANDLE_SIZE_DP) {
            "lockHandleSizeDp $lockHandleSizeDp outside [$MIN_LOCK_HANDLE_SIZE_DP, $MAX_LOCK_HANDLE_SIZE_DP]"
        }
        require(lockMaxTapDistanceRatio in MIN_LOCK_MAX_TAP_DISTANCE_RATIO..MAX_LOCK_MAX_TAP_DISTANCE_RATIO) {
            "lockMaxTapDistanceRatio $lockMaxTapDistanceRatio outside " +
                "[$MIN_LOCK_MAX_TAP_DISTANCE_RATIO, $MAX_LOCK_MAX_TAP_DISTANCE_RATIO]"
        }
        require(lockArmTimeoutMs in MIN_LOCK_ARM_TIMEOUT_MS..MAX_LOCK_ARM_TIMEOUT_MS) {
            "lockArmTimeoutMs $lockArmTimeoutMs outside [$MIN_LOCK_ARM_TIMEOUT_MS, $MAX_LOCK_ARM_TIMEOUT_MS]"
        }
        require(lockRingStrokeDp in MIN_STROKE_DP..MAX_STROKE_DP) {
            "lockRingStrokeDp $lockRingStrokeDp outside [$MIN_STROKE_DP, $MAX_STROKE_DP]"
        }
        require(lockOuterRingRatio in MIN_LOCK_OUTER_RING_RATIO..MAX_LOCK_OUTER_RING_RATIO) {
            "lockOuterRingRatio $lockOuterRingRatio outside [$MIN_LOCK_OUTER_RING_RATIO, $MAX_LOCK_OUTER_RING_RATIO]"
        }
        require(relockHueBins in MIN_RELOCK_HUE_BINS..MAX_RELOCK_HUE_BINS) {
            "relockHueBins $relockHueBins outside [$MIN_RELOCK_HUE_BINS, $MAX_RELOCK_HUE_BINS]"
        }
        require(relockSatBins in MIN_RELOCK_SAT_BINS..MAX_RELOCK_SAT_BINS) {
            "relockSatBins $relockSatBins outside [$MIN_RELOCK_SAT_BINS, $MAX_RELOCK_SAT_BINS]"
        }
        require(relockGreyBins in MIN_RELOCK_GREY_BINS..MAX_RELOCK_GREY_BINS) {
            "relockGreyBins $relockGreyBins outside [$MIN_RELOCK_GREY_BINS, $MAX_RELOCK_GREY_BINS]"
        }
        require(relockMinValue in MIN_RELOCK_COLOUR_LIMIT..MAX_RELOCK_COLOUR_LIMIT) {
            "relockMinValue $relockMinValue outside [$MIN_RELOCK_COLOUR_LIMIT, $MAX_RELOCK_COLOUR_LIMIT]"
        }
        require(relockMinSaturation in MIN_RELOCK_COLOUR_LIMIT..MAX_RELOCK_COLOUR_LIMIT) {
            "relockMinSaturation $relockMinSaturation outside [$MIN_RELOCK_COLOUR_LIMIT, $MAX_RELOCK_COLOUR_LIMIT]"
        }
        require(relockMaxSamplePixels in MIN_RELOCK_SAMPLE_PIXELS..MAX_RELOCK_SAMPLE_PIXELS) {
            "relockMaxSamplePixels $relockMaxSamplePixels outside [$MIN_RELOCK_SAMPLE_PIXELS, $MAX_RELOCK_SAMPLE_PIXELS]"
        }
        require(relockSignatureAlpha in MIN_RELOCK_SIGNATURE_ALPHA..MAX_RELOCK_SIGNATURE_ALPHA) {
            "relockSignatureAlpha $relockSignatureAlpha outside [$MIN_RELOCK_SIGNATURE_ALPHA, $MAX_RELOCK_SIGNATURE_ALPHA]"
        }
        require(relockTorsoShrink in 0f..MAX_RELOCK_TORSO_SHRINK) {
            "relockTorsoShrink $relockTorsoShrink outside [0, $MAX_RELOCK_TORSO_SHRINK]"
        }
        require(relockBoxTopRatio in 0f..MAX_RELOCK_BOX_TOP_RATIO) {
            "relockBoxTopRatio $relockBoxTopRatio outside [0, $MAX_RELOCK_BOX_TOP_RATIO]"
        }
        require(relockBoxBottomRatio in MIN_RELOCK_BOX_BOTTOM_RATIO..1f) {
            "relockBoxBottomRatio $relockBoxBottomRatio outside [$MIN_RELOCK_BOX_BOTTOM_RATIO, 1]"
        }
        require(relockBoxTopRatio < relockBoxBottomRatio) {
            "relockBoxTopRatio $relockBoxTopRatio must be below relockBoxBottomRatio $relockBoxBottomRatio"
        }
        require(relockBoxWidthRatio in MIN_RELOCK_BOX_WIDTH_RATIO..1f) {
            "relockBoxWidthRatio $relockBoxWidthRatio outside [$MIN_RELOCK_BOX_WIDTH_RATIO, 1]"
        }
        require(relockKeypointMinScore in MIN_KEYPOINT_MIN_SCORE..MAX_KEYPOINT_MIN_SCORE) {
            "relockKeypointMinScore $relockKeypointMinScore outside [$MIN_KEYPOINT_MIN_SCORE, $MAX_KEYPOINT_MIN_SCORE]"
        }
        require(relockMemoryMs in MIN_RELOCK_MEMORY_MS..MAX_RELOCK_MEMORY_MS) {
            "relockMemoryMs $relockMemoryMs outside [$MIN_RELOCK_MEMORY_MS, $MAX_RELOCK_MEMORY_MS]"
        }
        require(relockMaxDistance in MIN_RELOCK_MAX_DISTANCE..MAX_RELOCK_MAX_DISTANCE) {
            "relockMaxDistance $relockMaxDistance outside [$MIN_RELOCK_MAX_DISTANCE, $MAX_RELOCK_MAX_DISTANCE]"
        }
        require(relockMinMargin in 0f..MAX_RELOCK_MIN_MARGIN) {
            "relockMinMargin $relockMinMargin outside [0, $MAX_RELOCK_MIN_MARGIN]"
        }
        require(relockMinSamplePixels in MIN_RELOCK_MIN_SAMPLE_PIXELS..MAX_RELOCK_MIN_SAMPLE_PIXELS) {
            "relockMinSamplePixels $relockMinSamplePixels outside [$MIN_RELOCK_MIN_SAMPLE_PIXELS, $MAX_RELOCK_MIN_SAMPLE_PIXELS]"
        }
        require(relockMinSamplePixels <= relockMaxSamplePixels / RELOCK_SAMPLE_GRID_SLACK) {
            "relockMinSamplePixels $relockMinSamplePixels above relockMaxSamplePixels $relockMaxSamplePixels " +
                "/ $RELOCK_SAMPLE_GRID_SLACK (a grid sample may hold fewer points than its limit)"
        }
        require(relockFullBodyMinAspect in MIN_RELOCK_FULL_BODY_ASPECT..MAX_RELOCK_FULL_BODY_ASPECT) {
            "relockFullBodyMinAspect $relockFullBodyMinAspect outside " +
                "[$MIN_RELOCK_FULL_BODY_ASPECT, $MAX_RELOCK_FULL_BODY_ASPECT]"
        }
        require(relockJudgeFrames in MIN_RELOCK_JUDGE_FRAMES..MAX_RELOCK_JUDGE_FRAMES) {
            "relockJudgeFrames $relockJudgeFrames outside [$MIN_RELOCK_JUDGE_FRAMES, $MAX_RELOCK_JUDGE_FRAMES]"
        }
        require(relockJudgeMaxFrames in relockJudgeFrames..MAX_RELOCK_JUDGE_MAX_FRAMES) {
            "relockJudgeMaxFrames $relockJudgeMaxFrames outside [relockJudgeFrames $relockJudgeFrames, $MAX_RELOCK_JUDGE_MAX_FRAMES]"
        }
        require(relockMinSourceSamples in MIN_RELOCK_MIN_SOURCE_SAMPLES..MAX_RELOCK_MIN_SOURCE_SAMPLES) {
            "relockMinSourceSamples $relockMinSourceSamples outside " +
                "[$MIN_RELOCK_MIN_SOURCE_SAMPLES, $MAX_RELOCK_MIN_SOURCE_SAMPLES]"
        }
        require(relockOccluderMaxOverlap in MIN_RELOCK_OCCLUDER_MAX_OVERLAP..1f) {
            "relockOccluderMaxOverlap $relockOccluderMaxOverlap outside [$MIN_RELOCK_OCCLUDER_MAX_OVERLAP, 1]"
        }
        require(relockUpperTorsoRatio in MIN_RELOCK_UPPER_TORSO_RATIO..MAX_RELOCK_UPPER_TORSO_RATIO) {
            "relockUpperTorsoRatio $relockUpperTorsoRatio outside " +
                "[$MIN_RELOCK_UPPER_TORSO_RATIO, $MAX_RELOCK_UPPER_TORSO_RATIO]"
        }
        require(assocMissNearRatio in MIN_ASSOC_MISS_NEAR_RATIO..MAX_ASSOC_MISS_NEAR_RATIO) {
            "assocMissNearRatio $assocMissNearRatio outside [$MIN_ASSOC_MISS_NEAR_RATIO, $MAX_ASSOC_MISS_NEAR_RATIO]"
        }
        require(relockEarlyMaxHeadRatio in MIN_RELOCK_EARLY_MAX_HEAD_RATIO..MAX_RELOCK_EARLY_MAX_HEAD_RATIO) {
            "relockEarlyMaxHeadRatio $relockEarlyMaxHeadRatio outside " +
                "[$MIN_RELOCK_EARLY_MAX_HEAD_RATIO, $MAX_RELOCK_EARLY_MAX_HEAD_RATIO]"
        }
        require(assocMissLogPerMin in MIN_ASSOC_MISS_LOG_PER_MIN..MAX_ASSOC_MISS_LOG_PER_MIN) {
            "assocMissLogPerMin $assocMissLogPerMin outside [$MIN_ASSOC_MISS_LOG_PER_MIN, $MAX_ASSOC_MISS_LOG_PER_MIN]"
        }
    }

    /** Minimum time between two HUD text updates, in nanoseconds (duration, not a timestamp). */
    val hudIntervalNs: Long
        get() = (NANOS_PER_SECOND / hudRateHz).toLong()

    /** Shortest time between two thermal reads, in nanoseconds (duration, not a timestamp). */
    val thermalSampleIntervalNs: Long
        get() = thermalSampleIntervalMs * NANOS_PER_MILLI

    /** How long a hot reading must last before the step-down, in nanoseconds (a duration). */
    val thermalHotConfirmNs: Long
        get() = thermalHotConfirmMs * NANOS_PER_MILLI

    /** How long a cool reading must last before the normal model comes back, in nanoseconds (a duration). */
    val thermalCoolDownNs: Long
        get() = thermalCoolDownMs * NANOS_PER_MILLI

    /** How long the overlay stays touchable after the lock handle was tapped, in nanoseconds (a duration). */
    val lockArmTimeoutNs: Long
        get() = lockArmTimeoutMs * NANOS_PER_MILLI

    /** How long a lock whose track was removed waits for the person, in nanoseconds of frame time (a duration). */
    val relockMemoryNs: Long
        get() = relockMemoryMs * NANOS_PER_MILLI

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

        /** Width of the built-in model of the portrait orientation, in pixels (Mdl): `yolo26n-pose_448x960`. */
        const val BUILT_IN_PORTRAIT_WIDTH = 448

        /** Height of the built-in model of the portrait orientation, in pixels (Mdl). */
        const val BUILT_IN_PORTRAIT_HEIGHT = 960

        /** Width of the built-in model of the landscape orientation, in pixels (Mdl): `yolo26n-pose_960x448`. */
        const val BUILT_IN_LANDSCAPE_WIDTH = 960

        /** Height of the built-in model of the landscape orientation, in pixels (Mdl). */
        const val BUILT_IN_LANDSCAPE_HEIGHT = 448

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

        /** 99th percentile, as a fraction in [0, 1], for the filtered-speed statistics in the diagnostics. */
        const val TIMING_P99_FRACTION = 0.99

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

        /** Default [minBoxHeightImg], Img pixels. */
        const val DEFAULT_MIN_BOX_HEIGHT_IMG = 2.0f

        /** Lowest accepted [minBoxHeightImg], Img pixels (a head offset in box heights stays far from overflow). */
        const val MIN_MIN_BOX_HEIGHT_IMG = 0.5f

        /** Highest accepted [minBoxHeightImg], Img pixels. */
        const val MAX_MIN_BOX_HEIGHT_IMG = 20.0f

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

        /** Default [trackGateMinSigmaRatio], unitless (M4f: 0.2 of the track height). */
        const val DEFAULT_TRACK_GATE_MIN_SIGMA_RATIO = 0.2f

        /** Lowest accepted [trackGateMinSigmaRatio]; 0 = no floor. */
        const val MIN_TRACK_GATE_MIN_SIGMA_RATIO = 0.0f

        /** Highest accepted [trackGateMinSigmaRatio]. */
        const val MAX_TRACK_GATE_MIN_SIGMA_RATIO = 1.0f

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

        /** Default [rawNmsIou]: unitless (the Ultralytics default for pose NMS is 0.7). */
        const val DEFAULT_RAW_NMS_IOU = 0.7f

        /** Lowest accepted [rawNmsIou]. */
        const val MIN_RAW_NMS_IOU = 0.3f

        /** Highest accepted [rawNmsIou]. */
        const val MAX_RAW_NMS_IOU = 0.95f

        /** Default [trackHighScore]: unitless confidence. */
        const val DEFAULT_TRACK_HIGH_SCORE = 0.5f

        /** Default [trackBirthScore]: unitless confidence. */
        const val DEFAULT_TRACK_BIRTH_SCORE = 0.25f

        /** Default [trackBirthSuppressIou]: unitless. */
        const val DEFAULT_TRACK_BIRTH_SUPPRESS_IOU = 0.5f

        /** Lowest accepted [trackBirthSuppressIou]. */
        const val MIN_TRACK_BIRTH_SUPPRESS_IOU = 0.2f

        /** Highest accepted [trackBirthSuppressIou]. */
        const val MAX_TRACK_BIRTH_SUPPRESS_IOU = 1.0f

        /** Default [trackBirthSuppressHeadRatio]: fraction of the track height (owner's log: 14 of 19 duplicates <= 0.12). */
        const val DEFAULT_TRACK_BIRTH_SUPPRESS_HEAD_RATIO = 0.15f

        /** Lowest accepted [trackBirthSuppressHeadRatio]. */
        const val MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO = 0.05f

        /** Highest accepted [trackBirthSuppressHeadRatio]. */
        const val MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO = 0.5f

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

        /** Default [trackAccelRefDtMs], in milliseconds (20 fps). */
        const val DEFAULT_TRACK_ACCEL_REF_DT_MS = 50f

        /** Lowest accepted [trackAccelRefDtMs], in milliseconds. */
        const val MIN_TRACK_ACCEL_REF_DT_MS = 10f

        /** Highest accepted [trackAccelRefDtMs], in milliseconds. */
        const val MAX_TRACK_ACCEL_REF_DT_MS = 200f

        /** Default [trackAccelMinScale], unitless (F4: S3 at 12 fps measured 0.698, needs below 0.60). */
        const val DEFAULT_TRACK_ACCEL_MIN_SCALE = 0.5f

        /** Lowest accepted [trackAccelMinScale]. */
        const val MIN_TRACK_ACCEL_MIN_SCALE = 0.05f

        /** Highest accepted [trackAccelMinScale]: no scaling. */
        const val MAX_TRACK_ACCEL_MIN_SCALE = 1f

        /** Default [trackAccelIntervalTauMs], in milliseconds. */
        const val DEFAULT_TRACK_ACCEL_INTERVAL_TAU_MS = 1000f

        /** Lowest accepted [trackAccelIntervalTauMs], in milliseconds. */
        const val MIN_TRACK_ACCEL_INTERVAL_TAU_MS = 100f

        /** Highest accepted [trackAccelIntervalTauMs], in milliseconds. */
        const val MAX_TRACK_ACCEL_INTERVAL_TAU_MS = 10_000f

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

        /** Default [trackTentativeMaxMisses], in frames (M4a: measured on the reference model). */
        const val DEFAULT_TRACK_TENTATIVE_MAX_MISSES = 1

        /** Lowest accepted [trackTentativeMaxMisses], in frames (0 = a miss removes a tentative track). */
        const val MIN_TRACK_TENTATIVE_MAX_MISSES = 0

        /** Highest accepted [trackTentativeMaxMisses], in frames. */
        const val MAX_TRACK_TENTATIVE_MAX_MISSES = 5

        /** Default [smoothEnabled]: the display smoother is on (M4a: measured on the reference model). */
        const val DEFAULT_SMOOTH_ENABLED = true

        /** Default [smoothMinCutoffHz], in hertz. */
        const val DEFAULT_SMOOTH_MIN_CUTOFF_HZ = 1.0f

        /** Lowest accepted [smoothMinCutoffHz], in hertz. */
        const val MIN_SMOOTH_MIN_CUTOFF_HZ = 0.05f

        /** Highest accepted [smoothMinCutoffHz], in hertz. */
        const val MAX_SMOOTH_MIN_CUTOFF_HZ = 30.0f

        /** Default [smoothBeta], unitless. */
        const val DEFAULT_SMOOTH_BETA = 3.0f

        /** Lowest accepted [smoothBeta]. */
        const val MIN_SMOOTH_BETA = 0.0f

        /** Highest accepted [smoothBeta]. */
        const val MAX_SMOOTH_BETA = 100.0f

        /** Default [smoothDerivativeCutoffHz], in hertz. */
        const val DEFAULT_SMOOTH_DERIVATIVE_CUTOFF_HZ = 1.0f

        /** Lowest accepted [smoothDerivativeCutoffHz], in hertz. */
        const val MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ = 0.05f

        /** Highest accepted [smoothDerivativeCutoffHz], in hertz. */
        const val MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ = 30.0f

        /** Default [displayLeadEnabled]: the forward lead is on (F4). */
        const val DEFAULT_DISPLAY_LEAD_ENABLED = true

        /** Default [displayLeadMaxMs], in milliseconds. */
        const val DEFAULT_DISPLAY_LEAD_MAX_MS = 130f

        /** Lowest accepted [displayLeadMaxMs], in milliseconds (0 = no lead). */
        const val MIN_DISPLAY_LEAD_MAX_MS = 0f

        /** Highest accepted [displayLeadMaxMs], in milliseconds. */
        const val MAX_DISPLAY_LEAD_MAX_MS = 400f

        /** Default [displayLeadExtraMs], in milliseconds. */
        const val DEFAULT_DISPLAY_LEAD_EXTRA_MS = 10f

        /** Lowest accepted [displayLeadExtraMs], in milliseconds. */
        const val MIN_DISPLAY_LEAD_EXTRA_MS = 0f

        /** Highest accepted [displayLeadExtraMs], in milliseconds. */
        const val MAX_DISPLAY_LEAD_EXTRA_MS = 100f

        /** Default [displayLeadSpeedLowHps], in track heights per second. */
        const val DEFAULT_DISPLAY_LEAD_SPEED_LOW_HPS = 0.5f

        /** Default [displayLeadSpeedHighHps], in track heights per second. */
        const val DEFAULT_DISPLAY_LEAD_SPEED_HIGH_HPS = 1.0f

        /** Lowest accepted [displayLeadSpeedLowHps] / [displayLeadSpeedHighHps], in track heights per second. */
        const val MIN_DISPLAY_LEAD_SPEED_HPS = 0f

        /** Highest accepted [displayLeadSpeedLowHps] / [displayLeadSpeedHighHps], in track heights per second. */
        const val MAX_DISPLAY_LEAD_SPEED_HPS = 10f

        /** Default [displayLeadStopFullRatio], unitless. */
        const val DEFAULT_DISPLAY_LEAD_STOP_FULL_RATIO = 0.8f

        /** Lowest accepted [displayLeadStopFullRatio]. */
        const val MIN_DISPLAY_LEAD_STOP_FULL_RATIO = 0.1f

        /** Highest accepted [displayLeadStopFullRatio]: the measured step must match the tracker's speed. */
        const val MAX_DISPLAY_LEAD_STOP_FULL_RATIO = 1f

        /** Default [displayLeadFadeMs], in milliseconds. */
        const val DEFAULT_DISPLAY_LEAD_FADE_MS = 80f

        /** Lowest accepted [displayLeadFadeMs], in milliseconds (0 = cut at once). */
        const val MIN_DISPLAY_LEAD_FADE_MS = 0f

        /** Highest accepted [displayLeadFadeMs], in milliseconds. */
        const val MAX_DISPLAY_LEAD_FADE_MS = 1000f

        /** Default [displayLeadMaxRatio], in track heights. */
        const val DEFAULT_DISPLAY_LEAD_MAX_RATIO = 0.25f

        /** Lowest accepted [displayLeadMaxRatio] (0 = no lead). */
        const val MIN_DISPLAY_LEAD_MAX_RATIO = 0f

        /** Highest accepted [displayLeadMaxRatio], in track heights. */
        const val MAX_DISPLAY_LEAD_MAX_RATIO = 1f

        /** Default [fadeMinAlpha], unitless. */
        const val DEFAULT_FADE_MIN_ALPHA = 0.25f

        /** Lowest accepted [fadeMinAlpha]: invisible at the end of the fade. */
        const val MIN_FADE_MIN_ALPHA = 0f

        /** Highest accepted [fadeMinAlpha]: no fading. */
        const val MAX_FADE_MIN_ALPHA = 1f

        /** Default [fadeStepAlpha], unitless. */
        const val DEFAULT_FADE_STEP_ALPHA = 0.1f

        /** Lowest accepted [fadeStepAlpha]. */
        const val MIN_FADE_STEP_ALPHA = 0.01f

        /** Highest accepted [fadeStepAlpha]. */
        const val MAX_FADE_STEP_ALPHA = 1f

        /** Default [lockHandleSizeDp], in dp. */
        const val DEFAULT_LOCK_HANDLE_SIZE_DP = 40.0f

        /** Lowest accepted [lockHandleSizeDp], in dp. */
        const val MIN_LOCK_HANDLE_SIZE_DP = 16.0f

        /** Highest accepted [lockHandleSizeDp], in dp. */
        const val MAX_LOCK_HANDLE_SIZE_DP = 96.0f

        /** Default [lockMaxTapDistanceRatio], unitless: one box height. */
        const val DEFAULT_LOCK_MAX_TAP_DISTANCE_RATIO = 1.0f

        /** Lowest accepted [lockMaxTapDistanceRatio]. */
        const val MIN_LOCK_MAX_TAP_DISTANCE_RATIO = 0.1f

        /** Highest accepted [lockMaxTapDistanceRatio]. */
        const val MAX_LOCK_MAX_TAP_DISTANCE_RATIO = 3.0f

        /** Default [lockArmTimeoutMs], in milliseconds. */
        const val DEFAULT_LOCK_ARM_TIMEOUT_MS = 5000L

        /** Lowest accepted [lockArmTimeoutMs], in milliseconds. */
        const val MIN_LOCK_ARM_TIMEOUT_MS = 1000L

        /** Highest accepted [lockArmTimeoutMs], in milliseconds. */
        const val MAX_LOCK_ARM_TIMEOUT_MS = 30_000L

        /** Default [lockRingStrokeDp], in dp (thicker than the ordinary ring, [DEFAULT_DOT_STROKE_DP]). */
        const val DEFAULT_LOCK_RING_STROKE_DP = 3.0f

        /** Default [lockOuterRingRatio], unitless. */
        const val DEFAULT_LOCK_OUTER_RING_RATIO = 1.3f

        /** Lowest accepted [lockOuterRingRatio]: the second ring must lie outside the first. */
        const val MIN_LOCK_OUTER_RING_RATIO = 1.05f

        /** Highest accepted [lockOuterRingRatio]. */
        const val MAX_LOCK_OUTER_RING_RATIO = 2.0f

        /** Default [relockHueBins]. */
        const val DEFAULT_RELOCK_HUE_BINS = 16

        /** Lowest accepted [relockHueBins]. */
        const val MIN_RELOCK_HUE_BINS = 4

        /** Highest accepted [relockHueBins]. */
        const val MAX_RELOCK_HUE_BINS = 64

        /** Default [relockSatBins]. */
        const val DEFAULT_RELOCK_SAT_BINS = 4

        /** Lowest accepted [relockSatBins]. */
        const val MIN_RELOCK_SAT_BINS = 1

        /** Highest accepted [relockSatBins]. */
        const val MAX_RELOCK_SAT_BINS = 16

        /** Default [relockGreyBins]. */
        const val DEFAULT_RELOCK_GREY_BINS = 4

        /** Lowest accepted [relockGreyBins]. */
        const val MIN_RELOCK_GREY_BINS = 1

        /** Highest accepted [relockGreyBins]. */
        const val MAX_RELOCK_GREY_BINS = 16

        /** Default [relockMinValue], unitless. */
        const val DEFAULT_RELOCK_MIN_VALUE = 0.15f

        /** Default [relockMinSaturation], unitless. */
        const val DEFAULT_RELOCK_MIN_SATURATION = 0.15f

        /** Lowest accepted [relockMinValue] / [relockMinSaturation]. */
        const val MIN_RELOCK_COLOUR_LIMIT = 0.01f

        /** Highest accepted [relockMinValue] / [relockMinSaturation]. */
        const val MAX_RELOCK_COLOUR_LIMIT = 0.5f

        /** Default [relockMaxSamplePixels], count. */
        const val DEFAULT_RELOCK_SAMPLE_PIXELS = 2000

        /** Lowest accepted [relockMaxSamplePixels]. */
        const val MIN_RELOCK_SAMPLE_PIXELS = 100

        /** Highest accepted [relockMaxSamplePixels]. */
        const val MAX_RELOCK_SAMPLE_PIXELS = 20_000

        /** Default [relockSignatureAlpha], unitless. */
        const val DEFAULT_RELOCK_SIGNATURE_ALPHA = 0.1f

        /** Lowest accepted [relockSignatureAlpha]. */
        const val MIN_RELOCK_SIGNATURE_ALPHA = 0.01f

        /** Highest accepted [relockSignatureAlpha]. */
        const val MAX_RELOCK_SIGNATURE_ALPHA = 1f

        /** Default [relockTorsoShrink], unitless. */
        const val DEFAULT_RELOCK_TORSO_SHRINK = 0.1f

        /** Highest accepted [relockTorsoShrink]: less than half, so something is left. */
        const val MAX_RELOCK_TORSO_SHRINK = 0.4f

        /** Default [relockBoxTopRatio], unitless. */
        const val DEFAULT_RELOCK_BOX_TOP_RATIO = 0.25f

        /** Highest accepted [relockBoxTopRatio]. */
        const val MAX_RELOCK_BOX_TOP_RATIO = 0.9f

        /** Default [relockBoxBottomRatio], unitless. */
        const val DEFAULT_RELOCK_BOX_BOTTOM_RATIO = 0.6f

        /** Lowest accepted [relockBoxBottomRatio]. */
        const val MIN_RELOCK_BOX_BOTTOM_RATIO = 0.1f

        /** Default [relockBoxWidthRatio], unitless. */
        const val DEFAULT_RELOCK_BOX_WIDTH_RATIO = 0.6f

        /** Lowest accepted [relockBoxWidthRatio]. */
        const val MIN_RELOCK_BOX_WIDTH_RATIO = 0.1f

        /** Default [relockKeypointMinScore], unitless. */
        const val DEFAULT_RELOCK_KEYPOINT_MIN_SCORE = 0.5f

        /** Default [relockMemoryMs], in milliseconds: one minute. */
        const val DEFAULT_RELOCK_MEMORY_MS = 60_000L

        /** Lowest accepted [relockMemoryMs], in milliseconds. */
        const val MIN_RELOCK_MEMORY_MS = 1000L

        /** Highest accepted [relockMemoryMs], in milliseconds: ten minutes. */
        const val MAX_RELOCK_MEMORY_MS = 600_000L

        /** Default [relockMaxDistance], unitless; tuned on the first phone test (re-locks 0.14-0.30, rejects from 0.3x). */
        const val DEFAULT_RELOCK_MAX_DISTANCE = 0.40f

        /** Lowest accepted [relockMaxDistance]. */
        const val MIN_RELOCK_MAX_DISTANCE = 0.01f

        /** Highest accepted [relockMaxDistance]. */
        const val MAX_RELOCK_MAX_DISTANCE = 1f

        /** Default [relockMinMargin], unitless; a guess like [DEFAULT_RELOCK_MAX_DISTANCE]. */
        const val DEFAULT_RELOCK_MIN_MARGIN = 0.10f

        /** Highest accepted [relockMinMargin]. */
        const val MAX_RELOCK_MIN_MARGIN = 1f

        /** Default [relockMinSamplePixels], count. */
        const val DEFAULT_RELOCK_MIN_SAMPLE_PIXELS = 150

        /** Lowest accepted [relockMinSamplePixels]. */
        const val MIN_RELOCK_MIN_SAMPLE_PIXELS = 10

        /** Highest accepted [relockMinSamplePixels]. */
        const val MAX_RELOCK_MIN_SAMPLE_PIXELS = 5000

        /** Default [relockFullBodyMinAspect], unitless. */
        const val DEFAULT_RELOCK_FULL_BODY_ASPECT = 1.8f

        /** Lowest accepted [relockFullBodyMinAspect]. */
        const val MIN_RELOCK_FULL_BODY_ASPECT = 1f

        /** Highest accepted [relockFullBodyMinAspect]. */
        const val MAX_RELOCK_FULL_BODY_ASPECT = 4f

        /** Default [relockJudgeFrames], count. */
        const val DEFAULT_RELOCK_JUDGE_FRAMES = 10

        /** Lowest accepted [relockJudgeFrames]. */
        const val MIN_RELOCK_JUDGE_FRAMES = 1

        /** Highest accepted [relockJudgeFrames]. */
        const val MAX_RELOCK_JUDGE_FRAMES = 100

        /** Default [relockJudgeMaxFrames], count (owner log 2026-11-22: a person entering clipped needs well over 10 frames). */
        const val DEFAULT_RELOCK_JUDGE_MAX_FRAMES = 30

        /** Highest accepted [relockJudgeMaxFrames]. */
        const val MAX_RELOCK_JUDGE_MAX_FRAMES = 1000

        /** Default [relockMinSourceSamples], count. */
        const val DEFAULT_RELOCK_MIN_SOURCE_SAMPLES = 3

        /** Lowest accepted [relockMinSourceSamples]. */
        const val MIN_RELOCK_MIN_SOURCE_SAMPLES = 1

        /** Highest accepted [relockMinSourceSamples]. */
        const val MAX_RELOCK_MIN_SOURCE_SAMPLES = 50

        /** Default [relockOccluderMaxOverlap], unitless. */
        const val DEFAULT_RELOCK_OCCLUDER_MAX_OVERLAP = 0.25f

        /** Lowest accepted [relockOccluderMaxOverlap]. */
        const val MIN_RELOCK_OCCLUDER_MAX_OVERLAP = 0.05f

        /** Default [relockUpperTorsoRatio], unitless. */
        const val DEFAULT_RELOCK_UPPER_TORSO_RATIO = 0.8f

        /** Lowest accepted [relockUpperTorsoRatio]. */
        const val MIN_RELOCK_UPPER_TORSO_RATIO = 0.2f

        /** Highest accepted [relockUpperTorsoRatio]. */
        const val MAX_RELOCK_UPPER_TORSO_RATIO = 2f

        /** Default [assocMissNearRatio], unitless (M4e: one track height). */
        const val DEFAULT_ASSOC_MISS_NEAR_RATIO = 1.0f

        /** Lowest accepted [assocMissNearRatio]. */
        const val MIN_ASSOC_MISS_NEAR_RATIO = 0.1f

        /** Highest accepted [assocMissNearRatio]. */
        const val MAX_ASSOC_MISS_NEAR_RATIO = 5.0f

        /** Default [relockEarlyMaxHeadRatio], unitless (M4f: 0.6 track heights; phone jumps were about 0.3 h, p75 0.5 h). */
        const val DEFAULT_RELOCK_EARLY_MAX_HEAD_RATIO = 0.6f

        /** Lowest accepted [relockEarlyMaxHeadRatio]. */
        const val MIN_RELOCK_EARLY_MAX_HEAD_RATIO = 0.1f

        /** Highest accepted [relockEarlyMaxHeadRatio]. */
        const val MAX_RELOCK_EARLY_MAX_HEAD_RATIO = 3.0f

        /** Length of the window [assocMissLogPerMin] counts in, nanoseconds (one minute). */
        const val ASSOC_MISS_LOG_WINDOW_NS = 60_000_000_000L

        /** Default [assocMissLogPerMin], lines per minute. */
        const val DEFAULT_ASSOC_MISS_LOG_PER_MIN = 30

        /** Lowest accepted [assocMissLogPerMin] (0 = no log lines). */
        const val MIN_ASSOC_MISS_LOG_PER_MIN = 0

        /** Highest accepted [assocMissLogPerMin]. */
        const val MAX_ASSOC_MISS_LOG_PER_MIN = 600

        /**
         * [relockMinSamplePixels] may be at most [relockMaxSamplePixels] divided by this: a grid over a rectangle that
         * is just big enough holds up to this many times fewer points than the limit, and a sample must still pass.
         */
        const val RELOCK_SAMPLE_GRID_SLACK = 4

        /** COCO keypoint index of the left shoulder. */
        const val KEYPOINT_LEFT_SHOULDER = 5

        /** COCO keypoint index of the right shoulder. */
        const val KEYPOINT_RIGHT_SHOULDER = 6

        /** COCO keypoint index of the left hip. */
        const val KEYPOINT_LEFT_HIP = 11

        /** COCO keypoint index of the right hip. */
        const val KEYPOINT_RIGHT_HIP = 12

        /** Default [thermalHotHeadroom]. */
        const val DEFAULT_THERMAL_HOT_HEADROOM = 0.9f

        /** Default [thermalCoolHeadroom]. */
        const val DEFAULT_THERMAL_COOL_HEADROOM = 0.75f

        /** Lowest accepted [thermalHotHeadroom] and [thermalCoolHeadroom]. */
        const val MIN_THERMAL_HEADROOM = 0.3f

        /** Highest accepted [thermalHotHeadroom] and [thermalCoolHeadroom] (a forecast may exceed 1.0). */
        const val MAX_THERMAL_HEADROOM = 1.5f

        /** Default [thermalHotConfirmMs]: 3 s, in milliseconds. */
        const val DEFAULT_THERMAL_HOT_CONFIRM_MS = 3_000L

        /** Lowest accepted [thermalHotConfirmMs], in milliseconds. */
        const val MIN_THERMAL_HOT_CONFIRM_MS = 1_000L

        /** Highest accepted [thermalHotConfirmMs]: 1 minute, in milliseconds. */
        const val MAX_THERMAL_HOT_CONFIRM_MS = 60_000L

        /** Default [thermalCoolDownMs]: 60 s, in milliseconds. */
        const val DEFAULT_THERMAL_COOL_DOWN_MS = 60_000L

        /** Lowest accepted [thermalCoolDownMs], in milliseconds. */
        const val MIN_THERMAL_COOL_DOWN_MS = 5_000L

        /** Highest accepted [thermalCoolDownMs]: 10 minutes, in milliseconds. */
        const val MAX_THERMAL_COOL_DOWN_MS = 600_000L

        /** Platform thermal status (`PowerManager.THERMAL_STATUS_*`) from which the device counts as hot: `MODERATE`. */
        const val THERMAL_STATUS_HOT_MIN = 2

        /** Highest platform thermal status that counts as cool again: `LIGHT`. */
        const val THERMAL_STATUS_COOL_MAX = 1

        /** Highest value of the platform thermal status (`THERMAL_STATUS_SHUTDOWN`). */
        const val THERMAL_STATUS_MAX = 6

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

        /** Default [modelLibraryMax], count: portrait and landscape each pick one, so four leaves room to compare. */
        const val DEFAULT_MODEL_LIBRARY_MAX = 4

        /** Lowest accepted [modelLibraryMax]. */
        const val MIN_MODEL_LIBRARY_MAX = 1

        /** Highest accepted [modelLibraryMax]. */
        const val MAX_MODEL_LIBRARY_MAX = 16

        /** Default [removeAllConfirmMs], in milliseconds. */
        const val DEFAULT_REMOVE_ALL_CONFIRM_MS = 4000L

        /** Lowest accepted [removeAllConfirmMs], in milliseconds. */
        const val MIN_REMOVE_ALL_CONFIRM_MS = 1000L

        /** Highest accepted [removeAllConfirmMs], in milliseconds. */
        const val MAX_REMOVE_ALL_CONFIRM_MS = 30_000L

        /** An imported model's input width and height must each be a multiple of this, in pixels (the largest YOLO stride). */
        const val IMPORT_INPUT_SIZE_STEP = 32

        /**
         * Most rows (`N`) of an imported model's `[1, N, 57]` output: the vendored `PoseEstimator` treats 57
         * columns as an end-to-end head only up to its `END_TO_END_MAX_ROWS` (300, the Ultralytics default
         * `max_det`); a model with more rows would import and then fail at every Start. Also the sanity cap
         * of stored metadata.
         */
        const val IMPORT_MAX_OUTPUT_ROWS = 300

        /**
         * Most rows (`N`) of an imported model's RAW output (`[1, 56, N]` or `[1, N, 56]`): 33 600 rows are what a
         * 1280 x 1280 input has (the default [DEFAULT_IMPORT_MAX_INPUT_SIZE]); anything above would also be a
         * lot of memory per frame. Also the sanity cap of stored metadata.
         */
        const val IMPORT_MAX_RAW_OUTPUT_ROWS = 50_000

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

        /** Channels per output row of a RAW pose model: 4 box (cx, cy, w, h) + 1 person score + 17 * 3 keypoint values = 56. */
        const val POSE_RAW_CHANNELS = POSE_BOX_VALUES + POSE_SCORE_VALUES + POSE_KEYPOINT_COUNT * POSE_VALUES_PER_KEYPOINT

        /** Most detections the RAW pose decoder returns per frame (the Ultralytics default `max_det`). */
        const val RAW_MAX_DETECTIONS = 300

        /**
         * A RAW output row whose box, written as left, top, right, bottom, has no coordinate with an absolute value
         * above this is read as normalized (0..1, scaled by the model input size); otherwise as model pixels. The same
         * rule as the vendored end-to-end path, applied per row.
         */
        const val RAW_NORMALIZED_MAX_COORDINATE = 2f

        /** Columns per output row of an accepted pose model: 4 box + 1 score + 1 class + 17 * 3 keypoint values = 57. */
        const val POSE_OUTPUT_COLUMNS =
            POSE_BOX_VALUES + POSE_SCORE_VALUES + POSE_CLASS_VALUES + POSE_KEYPOINT_COUNT * POSE_VALUES_PER_KEYPOINT
    }
}
