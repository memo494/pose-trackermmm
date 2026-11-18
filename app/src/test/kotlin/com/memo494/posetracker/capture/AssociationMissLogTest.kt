package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tracking.AssociationMiss
import com.memo494.posetracker.core.tracking.AssociationMissReason
import com.memo494.posetracker.core.tracking.RemovalCause
import com.memo494.posetracker.core.tracking.TrackState
import org.junit.Test

class AssociationMissLogTest {
    private val lines = mutableListOf<String>()

    private fun miss(
        reason: AssociationMissReason = AssociationMissReason.GATE,
        highStage: Boolean = true,
        gateChi2: Float = 123.456f,
        trackState: TrackState = TrackState.CONFIRMED,
        detIou: Float = Float.NaN,
        cause: RemovalCause = RemovalCause.NONE,
    ): AssociationMiss {
        val miss = AssociationMiss()
        miss.set(
            lostId = 7,
            newId = 9,
            lostMs = 249.6f,
            score = 0.8765f,
            highStage = highStage,
            gateChi2 = gateChi2,
            iou = 0.004f,
            headRatio = 1.5f,
            hRatio = 1.0456f,
            cost = 0.9999f,
            reason = reason,
            trackState = trackState,
            detIou = detIou,
            cause = cause,
        )
        return miss
    }

    @Test
    fun format_writesTheDocumentedLineWithTwoDecimals() {
        assertThat(AssociationMissLog.format(miss()))
            .isEqualTo(
                "track: miss id=7 -> new id=9 lostMs=250 score=0.88 stage=high gateChi2=123.46 iou=0.00 " +
                    "headRatio=1.50 hRatio=1.05 cost=1.00 reason=gate",
            )
    }

    @Test
    fun format_writesTheLowStageAndTheReason() {
        val text = AssociationMissLog.format(miss(reason = AssociationMissReason.COST, highStage = false))

        assertThat(text).contains(" stage=low ")
        assertThat(text).endsWith(" reason=cost")
        assertThat(AssociationMissLog.format(miss(reason = AssociationMissReason.TAKEN))).endsWith(" reason=taken")
    }

    @Test
    fun format_aValueThatIsNotANumber_isNotAvailable() {
        assertThat(AssociationMissLog.format(miss(gateChi2 = Float.NaN))).contains(" gateChi2=n/a ")
    }

    @Test
    fun format_aLiveBirth_endsWithTheStateAndTheDetectionIou() {
        val text = AssociationMissLog.format(miss(AssociationMissReason.LIVE, trackState = TrackState.TENTATIVE, detIou = 0.2308f))

        assertThat(text).endsWith(" reason=live state=tentative detIou=0.23")
        assertThat(AssociationMissLog.format(miss(AssociationMissReason.LIVE, detIou = Float.NaN)))
            .endsWith(" reason=live state=confirmed detIou=n/a")
    }

    @Test
    fun format_aBirthAfterARemoval_endsWithTheCause() {
        val text = AssociationMissLog.format(miss(AssociationMissReason.REMOVED, cause = RemovalCause.LOST_TIME))

        assertThat(text).endsWith(" reason=removed cause=lostTime")
        assertThat(AssociationMissLog.format(miss(AssociationMissReason.REMOVED, cause = RemovalCause.EXIT)))
            .endsWith(" reason=removed cause=exit")
        assertThat(AssociationMissLog.format(miss(AssociationMissReason.REMOVED, cause = RemovalCause.OTHER)))
            .endsWith(" reason=removed cause=other")
    }

    @Test
    fun report_writesAtMostTheLimitPerMinute_andAnewWindowStartsAfterAMinute() {
        val log = AssociationMissLog(2) { lines.add(it) }

        log.report(miss(), 0L)
        log.report(miss(), 1L)
        log.report(miss(), 2L)
        log.report(miss(), TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS - 1L)
        assertThat(lines).hasSize(2)

        log.report(miss(), TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS)
        log.report(miss(), TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS + 1L)
        log.report(miss(), TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS + 2L)
        assertThat(lines.count { it.startsWith("track: miss id=") }).isEqualTo(4)
    }

    @Test
    fun report_aWindowThatDroppedLines_writesOneSuppressedLineWhenTheNextWindowStarts() {
        val log = AssociationMissLog(1) { lines.add(it) }

        log.report(miss(), 0L)
        log.report(miss(), 1L)
        log.report(miss(), 2L)
        assertThat(lines).hasSize(1)

        log.report(miss(), TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS)

        assertThat(lines).hasSize(3)
        assertThat(lines[1]).isEqualTo("track: miss suppressed 2")
        assertThat(lines[2]).startsWith("track: miss id=")
    }

    @Test
    fun report_aWindowThatDroppedNothing_writesNoSuppressedLine() {
        val log = AssociationMissLog(2) { lines.add(it) }

        log.report(miss(), 0L)
        log.report(miss(), TrackerConfig.ASSOC_MISS_LOG_WINDOW_NS)

        assertThat(lines).hasSize(2)
        assertThat(lines.none { it.contains("suppressed") }).isTrue()
    }

    @Test
    fun report_withZeroPerMinute_writesNothing() {
        val log = AssociationMissLog(0) { lines.add(it) }

        log.report(miss(), 0L)

        assertThat(lines).isEmpty()
    }

    @Test
    fun report_theFirstMissStartsTheWindowEvenWhenTheClockIsLarge() {
        val log = AssociationMissLog(1) { lines.add(it) }

        log.report(miss(), Long.MAX_VALUE / 2)
        log.report(miss(), Long.MAX_VALUE / 2 + 1L)

        assertThat(lines).hasSize(1)
    }
}
