package com.memo494.posetracker.core.tracking

/** Why a new detection next to a LOST track was not matched with it (diagnostics only, ARCHITECTURE §6.3). */
enum class AssociationMissReason {
    /** The Mahalanobis value of the head innovation was above `trackGateChi2` (or not a number): the pair was gated. */
    GATE,

    /** The pair passed the gate but its cost was at or above the limit of the detection's stage. */
    COST,

    /** The matcher would have accepted the pair (gate and cost limit) and still did not make it. */
    TAKEN,
    ;

    companion object {
        /**
         * The reason for a pair with the Mahalanobis value [gateValue] and the cost [cost], where [gateLimit] is
         * `trackGateChi2` and [maxCost] the cost limit of the detection's stage. The gate is checked first, as in the
         * matcher; a pair is eligible when the gate value is at most [gateLimit] and the cost is below [maxCost].
         */
        fun classify(
            gateValue: Float,
            cost: Float,
            maxCost: Float,
            gateLimit: Float,
        ): AssociationMissReason =
            when {
                !(gateValue <= gateLimit) -> GATE
                !(cost < maxCost) -> COST
                else -> TAKEN
            }
    }
}

/**
 * One association miss: a track was born while a CONFIRMED track was LOST and the new detection's head lay within
 * `assocMissNearRatio` track heights of the lost track's predicted head. The numbers are the ones the matcher used
 * for that pair (`AssociationCost`), computed only for it. A record is a reused object owned by the [Tracker]; read it
 * before the next [Tracker.update].
 */
class AssociationMiss {
    /** Id of the LOST track. */
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

    /** Why the pair was not made. */
    var reason: AssociationMissReason = AssociationMissReason.GATE
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
    }
}
