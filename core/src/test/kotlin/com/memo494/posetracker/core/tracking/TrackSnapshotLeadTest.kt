package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

/** `TrackSnapshot.ofSmoothed` with the display lead (F4): where it applies, where it must stay 0, the cap. */
class TrackSnapshotLeadTest {
    /** 20 px per 50 ms frame = 400 px/s = 2 track heights per second (height 200): above the full-lead speed. */
    private fun warmUp(
        smoother: HeadSmoother,
        build: (Float) -> FakeTrack,
        frames: Int = WARM_FRAMES,
        resultAgeMs: Float = AGE_MS,
    ): TrackSnapshot {
        var snapshot = TrackSnapshot.EMPTY
        for (i in 0 until frames) {
            val list = FakeTrackList(build(START_X + STEP_X * i))
            snapshot = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, smoother, i * FRAME_NS, resultAgeMs)
        }
        return snapshot
    }

    private fun walker(x: Float) =
        FakeTrack(1, headXImg = x, boxLeftImg = x - 20f, boxRightImg = x + 20f, velocityXImgPerS = SPEED_IMG_PER_S)

    private fun withoutLead(build: (Float) -> FakeTrack): TrackSnapshot =
        warmUp(HeadSmoother(TrackerConfig(displayLeadEnabled = false)), build)

    @Test
    fun aFastWalker_isDrawnAheadOfTheSmoothedPoint_byTheVelocityTimesTheLeadTime() {
        val led = warmUp(HeadSmoother(TrackerConfig()), ::walker)
        val plain = withoutLead(::walker)

        // L = 60 + 10 + 25 = 95 ms at 400 px/s = 38 px, under the 0.25 h = 50 px cap.
        assertThat(led.headXImg(0) - plain.headXImg(0)).isWithin(TOLERANCE_IMG).of(38f)
        assertThat(led.headYImg(0)).isEqualTo(plain.headYImg(0))
        assertThat(led.leadMs(0)).isWithin(TOLERANCE_MS).of(95f)
        assertThat(led.speedHps(0)).isGreaterThan(1f)
    }

    @Test
    fun theBoxMovesWithTheLead_soRingAndBracketsStayTogether() {
        val led = warmUp(HeadSmoother(TrackerConfig()), ::walker)

        assertThat(led.boxLeftImg(0) - led.headXImg(0)).isWithin(TOLERANCE_IMG).of(-20f)
        assertThat(led.boxRightImg(0) - led.headXImg(0)).isWithin(TOLERANCE_IMG).of(20f)
    }

    @Test
    fun theLead_isCappedAtTheMaxRatioTimesTheHeight() {
        val led = warmUp(HeadSmoother(TrackerConfig()), ::walker, resultAgeMs = 400f)
        val plain = withoutLead(::walker)

        // 130 ms at 400 px/s = 52 px; the cap is 0.25 * 200 = 50 px.
        assertThat(led.headXImg(0) - plain.headXImg(0)).isWithin(TOLERANCE_IMG).of(50f)
    }

    @Test
    fun aSlowHead_getsNoLead_andIsBitIdenticalToTheSmootherAlone() {
        val slow = { x: Float -> FakeTrack(1, headXImg = 100f + (x - START_X) / SLOW_DIVISOR, velocityXImgPerS = SLOW_IMG_PER_S) }
        val led = warmUp(HeadSmoother(TrackerConfig()), slow)
        val plain = withoutLead(slow)

        assertThat(led.headXImg(0).toRawBits()).isEqualTo(plain.headXImg(0).toRawBits())
        assertThat(led.leadMs(0)).isEqualTo(0f)
    }

    @Test
    fun aLostTrack_getsNoLead() {
        val lost = { x: Float -> walker(x).copy(lost = true) }
        val led = warmUp(HeadSmoother(TrackerConfig()), lost)
        val plain = withoutLead(lost)

        assertThat(led.headXImg(0).toRawBits()).isEqualTo(plain.headXImg(0).toRawBits())
        assertThat(led.leadMs(0)).isEqualTo(0f)
    }

    @Test
    fun aTrackWhoseHeadIsEstimated_getsNoLead() {
        val estimated = { x: Float -> walker(x).copy(headEstimated = true) }
        val led = warmUp(HeadSmoother(TrackerConfig()), estimated)
        val plain = withoutLead(estimated)

        assertThat(led.headXImg(0).toRawBits()).isEqualTo(plain.headXImg(0).toRawBits())
        assertThat(led.leadMs(0)).isEqualTo(0f)
    }

    @Test
    fun theFirstSampleOfAnId_getsNoLead() {
        val led = warmUp(HeadSmoother(TrackerConfig()), ::walker, frames = 1)

        assertThat(led.headXImg(0)).isEqualTo(START_X)
        assertThat(led.leadMs(0)).isEqualTo(0f)
        assertThat(led.speedHps(0)).isEqualTo(0f)
    }

    @Test
    fun whenTheMeasuredHeadHasStopped_theLeadIsOff_beforeTheFilterKnows() {
        val smoother = HeadSmoother(TrackerConfig())
        warmUp(smoother, ::walker)
        val stopped = walker(START_X + STEP_X * WARM_FRAMES).copy(measuredVelocityXImgPerS = 0f)

        val snapshot =
            TrackSnapshot.ofSmoothed(FakeTrackList(stopped), WIDTH_IMG, HEIGHT_IMG, smoother, WARM_FRAMES * FRAME_NS, AGE_MS)

        assertThat(snapshot.leadMs(0)).isEqualTo(0f)
    }

    @Test
    fun whenTheMeasuredStepReverses_theLeadIsOff() {
        val smoother = HeadSmoother(TrackerConfig())
        warmUp(smoother, ::walker)
        val back = walker(START_X + STEP_X * WARM_FRAMES).copy(measuredVelocityXImgPerS = -SPEED_IMG_PER_S)

        val snapshot =
            TrackSnapshot.ofSmoothed(FakeTrackList(back), WIDTH_IMG, HEIGHT_IMG, smoother, WARM_FRAMES * FRAME_NS, AGE_MS)

        assertThat(snapshot.leadMs(0)).isEqualTo(0f)
    }

    @Test
    fun withTheLeadSwitchedOff_everyFrameEqualsTheSmootherAlone() {
        val off = HeadSmoother(TrackerConfig(displayLeadEnabled = false))
        val reference = HeadSmoother(TrackerConfig(displayLeadMaxRatio = 0f))
        for (i in 0 until WARM_FRAMES) {
            val list = FakeTrackList(walker(START_X + STEP_X * i))
            val a = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, off, i * FRAME_NS, AGE_MS)
            val b = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, reference, i * FRAME_NS, AGE_MS)
            assertThat(a.headXImg(0).toRawBits()).isEqualTo(b.headXImg(0).toRawBits())
            assertThat(a.leadMs(0)).isEqualTo(0f)
        }
    }

    @Test
    fun theIdleSnapshot_isNotLed_andKeepsTheSmoothedPoint() {
        val smoother = HeadSmoother(TrackerConfig())
        val last = warmUp(smoother, ::walker)
        val list = FakeTrackList(walker(START_X + STEP_X * (WARM_FRAMES - 1)))

        val idle = TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, 0f, 1000f)

        assertThat(idle.leadMs(0)).isEqualTo(0f)
        assertThat(idle.headXImg(0)).isLessThan(last.headXImg(0))
    }

    private companion object {
        const val WIDTH_IMG = 1356
        const val HEIGHT_IMG = 610
        const val FRAME_NS = 50_000_000L
        const val WARM_FRAMES = 30
        const val START_X = 100f
        const val STEP_X = 20f
        const val SPEED_IMG_PER_S = 400f
        const val AGE_MS = 60f
        const val SLOW_DIVISOR = 20f
        const val SLOW_IMG_PER_S = 10f
        const val TOLERANCE_IMG = 0.5f
        const val TOLERANCE_MS = 0.5f
    }
}
