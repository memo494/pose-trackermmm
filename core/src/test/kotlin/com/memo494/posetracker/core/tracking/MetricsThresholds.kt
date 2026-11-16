package com.memo494.posetracker.core.tracking

/**
 * Every number the tracker scenarios of `TrackerScenarioTest` assert (docs/plans/M3.md, T5). Protected: a
 * threshold changes only by a lead decision. "Model worst" is the worst value the reference model
 * (`docs/plans/M3_sim/scenarios.py`, 20/30/60 fps and 30 fps +-30 %, seeds 1..40) reached; the plan's table
 * gives `1.0` for the scenarios whose outcome is a pass/fail count.
 */
object MetricsThresholds {
    /** S1: highest track id ever returned, exactly (two people). Model worst: 1.0 (always 2). */
    const val S1_EXPECTED_IDS = 2

    /** S1: id switches of both people outside the overlap window 2.1 to 2.9 s, exactly. Model worst: 1.0 (always 0). */
    const val S1_EXPECTED_SWITCHES_OUTSIDE_OVERLAP = 0

    /** S1: lowest coverage of either person from frame 2. Model worst: 1.0. */
    const val S1_MIN_COVERAGE = 0.95

    /** S1: ghost frames, exactly. Model worst: 1.0 (always 0). */
    const val S1_EXPECTED_GHOSTS = 0

    /** S2: overshoot past the stop point, in units of h, must stay below this. Model worst: 0.054. */
    const val S2_MAX_OVERSHOOT_H = 0.10

    /** S2: absolute lag in frames between 1 s and 2 s must stay below this. Model worst: 0.034. */
    const val S2_MAX_ABS_LAG_FRAMES = 0.5

    /** S2: lowest coverage from frame 2. Model worst: 1.0. */
    const val S2_MIN_COVERAGE = 0.95

    /** S2: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S2_EXPECTED_IDS = 1

    /** S3: jitter of the track over jitter of the raw series (larger of X and Y) at 20 fps, at most. Model worst: 0.513. */
    const val S3_MAX_JITTER_RATIO_20_FPS = 0.60

    /** S3: the same at 30 fps and at 30 fps +-30 %, at most. Model worst: 0.375. */
    const val S3_MAX_JITTER_RATIO_30_FPS = 0.45

    /** S3: the same at 60 fps, at most. Model worst: 0.202. */
    const val S3_MAX_JITTER_RATIO_60_FPS = 0.25

    /** S4: id switches, exactly. Model worst: 1.0 (always 0). */
    const val S4_EXPECTED_SWITCHES = 0

    /** S4: lowest coverage from frame 2 (shown throughout the dropout). Model worst: 1.0. */
    const val S4_MIN_COVERAGE = 0.95

    /** S4: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S4_EXPECTED_IDS = 1

    /** S5: largest head error from frame 10 in units of h must stay below this. Model worst: 0.020. */
    const val S5_MAX_ERROR_H = 0.05

    /** S5: id switches, exactly. Model worst: 1.0 (always 0). */
    const val S5_EXPECTED_SWITCHES = 0

    /** S5: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S5_EXPECTED_IDS = 1

    /**
     * S6: head error in units of h in each of the first three frames after the gap must stay below this.
     * Model worst: 0.033 (the player's pause; 0.023 without it).
     */
    const val S6_MAX_ERROR_H = 0.10

    /** S6: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S6_EXPECTED_IDS = 1

    /** S6: the track list is never smaller than this from frame index 3 on. Model worst: 1.0 (never empty). */
    const val S6_MIN_LIST_SIZE = 1

    /** S7: tracks shown after the frames and after `onIdle(promote - 1 ms)`, exactly. Model worst: 1.0 (always 0). */
    const val S7_EXPECTED_SHOWN_BEFORE_PROMOTE = 0

    /** S7: tracks shown after `onIdle(promote)`, exactly. Model worst: 1.0 (always 1). */
    const val S7_EXPECTED_SHOWN_AFTER_PROMOTE = 1

    /** S7: head error in units of h right after the promotion must stay below this. Model worst: 0.013. */
    const val S7_MAX_ERROR_H = 0.05

    /** S7: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S7_EXPECTED_IDS = 1

    /** S8: index of the first frame with a shown track, exactly (three hits). Model worst: 1.0 (always 2). */
    const val S8_EXPECTED_FIRST_SHOWN_FRAME = 2

    /** S8: lowest coverage from frame 2. Model worst: 1.0. */
    const val S8_MIN_COVERAGE = 0.95

    /** S8: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S8_EXPECTED_IDS = 1

    /** S8: largest track list size in any frame, at most. Model worst: 1.0 (always 1). */
    const val S8_MAX_LIST_SIZE = 1

    /** S9: ghost frames, exactly. Model worst: 0. */
    const val S9_EXPECTED_GHOSTS = 0

    /** S9: most tracks shown in any frame with only false positives, exactly. Model worst: 0. */
    const val S9_EXPECTED_MAX_SHOWN_ALONE = 0

    /** S9: most tracks shown in any frame beside a still person, exactly (the person's own track). Model worst: 0 ghosts, 1 shown. */
    const val S9_EXPECTED_MAX_SHOWN_WITH_PERSON = 1

    /** S10: largest track list size in any frame, at most (a score below the birth score starts nothing). Model worst: 0. */
    const val S10_MAX_LIST_SIZE = 0

    /** S11: seconds after the end of the path by which the last shown track must be gone, at most. Model worst: 0.056 s. */
    const val S11_MAX_SHOWN_AFTER_END_S = 0.15

    /** S11: lowest coverage of the person from frame 2 (frames after the end of the path do not count). Model worst: 1.0. */
    const val S11_MIN_COVERAGE = 0.95

    /** S11: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S11_EXPECTED_IDS = 1

    /** S12: largest track list size in any frame, at most. Model worst: 1.0 (always 1). */
    const val S12_MAX_LIST_SIZE = 1

    /** S12: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S12_EXPECTED_IDS = 1

    /** S12: lowest coverage from frame 2. Model worst: 1.0. */
    const val S12_MIN_COVERAGE = 0.95

    /** S13: id switches of each of the three people, exactly. Model worst: 1.0 (always 0). */
    const val S13_EXPECTED_SWITCHES_EACH = 0

    /** S13: highest track id ever returned, exactly (three people). Model worst: 1.0 (always 3). */
    const val S13_EXPECTED_IDS = 3

    /** S13: ghost frames, exactly. Model worst: 1.0 (always 0). */
    const val S13_EXPECTED_GHOSTS = 0

    /** S13: lowest coverage of any of the three people from frame 5. Model worst: 1.0. */
    const val S13_MIN_COVERAGE = 0.95

    /** S14: largest head error from frame 10 in units of h must stay below this. Model worst: 0.014. */
    const val S14_MAX_ERROR_H = 0.05

    /** S14: highest track id ever returned, exactly. Model worst: 1.0 (always 1). */
    const val S14_EXPECTED_IDS = 1

    /** S14: lowest coverage from frame 2. Model worst: 1.0. */
    const val S14_MIN_COVERAGE = 0.95
}
