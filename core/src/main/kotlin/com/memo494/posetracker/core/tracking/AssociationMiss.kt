package com.memo494.posetracker.core.tracking

/** Why a new detection next to a LOST track was not matched with it (diagnostics only, ARCHITECTURE §6.3). */
enum class AssociationMissReason {
    /** The Mahalanobis value of the head innovation was above `trackGateChi2` (or not a number): the pair was gated. */
    GATE,

    /** The pair passed the gate but its cost was at or above the limit of the detection's stage. */
    COST,

    /** The matcher would have accepted the pair (gate and cost limit) and still did not make it. */
    TAKEN,

    /** The new track was born next to a track that is not LOST (it took another detection, or is tentative). */
    LIVE,

    /** The new track was born next to a track that was removed in the same frame. */
    REMOVED,
    ;

    companion object {
        /**
         * One of [GATE], [COST], [TAKEN] for a pair with the squared Mahalanobis value [gateChi2] and the cost [cost], where [gateLimit] is
         * `trackGateChi2` and [maxCost] the cost limit of the detection's stage. The gate is checked first, as in the
         * matcher; a pair is eligible when the gate value is at most [gateLimit] and the cost is below [maxCost].
         */
        fun classify(
            gateChi2: Float,
            cost: Float,
            maxCost: Float,
            gateLimit: Float,
        ): AssociationMissReason =
            when {
                !(gateChi2 <= gateLimit) -> GATE
                !(cost < maxCost) -> COST
                else -> TAKEN
            }
    }
}

/** Why a track that was shown was removed (the removed-track case of [AssociationMiss]). */
enum class RemovalCause {
    /** Not a removal (the record is another kind). */
    NONE,

    /** Its lost time went above `trackMaxLostMs`. */
    LOST_TIME,

    /** Its head point left the image by more than `trackExitMarginRatio` heights. */
    EXIT,

    /** Its state was not finite or its height not positive. */
    OTHER,
}

/**
 * One association record: a track was born while a CONFIRMED track was LOST and the new detection's head lay within
 * `assocMissNearRatio` track heights of the lost track's predicted head (reason GATE, COST or TAKEN). The same record
 * describes a birth next to a track that is not LOST (reason LIVE: [trackState], [detIou]) or was removed in the same
 * frame (reason REMOVED: [cause]); then the numbers use the track's state after the frame. The numbers are the ones
 * the matcher uses for a pair (`AssociationCost`), computed only for it. A record is a reused object owned by the [Tracker]; read it
 * before the next [Tracker.update].
 */
class AssociationMiss {
    /** Id of the track the new one was born next to (LOST, live or removed, by [reason]). */
    var lostId: Int = 0
        private set

    /** Id of the track born from the detection. */
    var newId: Int = 0
        private set

    /** How long the track had been lost, milliseconds. */
    var lostMs: Float = 0f
        private set

    /** Score of the detection, unitless. */
    var score: Float = 0f
        private set

    /** True when the detection was in the high stage (score of at least `trackHighScore`), false for the low stage. */
    var highStage: Boolean = true
        private set

    /** Squared Mahalanobis distance of the head innovation (compare with `trackGateChi2`); NaN when not computable. */
    var gateChi2: Float = Float.NaN
        private set

    /** IoU of the track box and the detection box, unitless in [0, 1]. */
    var iou: Float = 0f
        private set

    /** Head distance divided by `assocHeadScaleRatio` times the track height (1 = where the head term is capped). */
    var headRatio: Float = Float.NaN
        private set

    /** Detection box height divided by the track height. */
    var hRatio: Float = Float.NaN
        private set

    /** The association cost the pair would have had (compare with `trackMatchMaxCost` / `trackLowMatchMaxCost`). */
    var cost: Float = Float.NaN
        private set

    /** Why the pair was not made, or which kind of birth this is. */
    var reason: AssociationMissReason = AssociationMissReason.GATE
        private set

    /** State of the live track for reason LIVE (CONFIRMED or TENTATIVE); CONFIRMED otherwise. */
    var trackState: TrackState = TrackState.CONFIRMED
        private set

    /** IoU of the new detection's box with the live track's matched detection box (reason LIVE; NaN when it matched none). */
    var detIou: Float = Float.NaN
        private set

    /** Why the track was removed (reason REMOVED); [RemovalCause.NONE] otherwise. */
    var cause: RemovalCause = RemovalCause.NONE
        private set

    /** Overwrites every field; called by the [Tracker] (and by tests of code that reads records). */
    fun set(
        lostId: Int,
        newId: Int,
        lostMs: Float,
        score: Float,
        highStage: Boolean,
        gateChi2: Float,
        iou: Float,
        headRatio: Float,
        hRatio: Float,
        cost: Float,
        reason: AssociationMissReason,
        trackState: TrackState = TrackState.CONFIRMED,
        detIou: Float = Float.NaN,
        cause: RemovalCause = RemovalCause.NONE,
    ) {
        this.lostId = lostId
        this.newId = newId
        this.lostMs = lostMs
        this.score = score
        this.highStage = highStage
        this.gateChi2 = gateChi2
        this.iou = iou
        this.headRatio = headRatio
        this.hRatio = hRatio
        this.cost = cost
        this.reason = reason
        this.trackState = trackState
        this.detIou = detIou
        this.cause = cause
    }
}
