package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.capture.FrameImage
import com.memo494.posetracker.capture.FramePipeline
import com.memo494.posetracker.capture.NanoClock
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.stats.ThermalReader
import com.memo494.posetracker.core.stats.ThermalSampler
import com.memo494.posetracker.core.stats.ThermalStepDown
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.core.tracking.TrackSnapshot
import com.memo494.posetracker.detector.Accelerator
import com.memo494.posetracker.detector.FakePoseDetector
import com.memo494.posetracker.overlay.FakeTracks
import com.memo494.posetracker.overlay.OverlayState
import org.junit.After
import org.junit.Test
import java.nio.ByteBuffer

private const val WIDTH = 4
private const val HEIGHT = 2
private const val NOW_NS = 10_000_000_000L
private const val MS_NS = 1_000_000L
private const val FRAME_GAP_NS = 30 * MS_NS
private const val TRACK_WIDTH = 320
private const val TRACK_HEIGHT = 240
private const val THERMAL_STATUS_LIGHT = 1
private const val HEADROOM = 0.75f

class CaptureStatsHolderTest {
    private class SolidImage(
        override val widthImg: Int = WIDTH,
        override val heightImg: Int = HEIGHT,
        override val timestampNs: Long = NOW_NS - 5 * MS_NS,
    ) : FrameImage {
        override val rowStride = widthImg * TrackerConfig.RGBA_BYTES_PER_PIXEL
        override val pixelStride = TrackerConfig.RGBA_BYTES_PER_PIXEL
        override val pixels: ByteBuffer = ByteBuffer.allocate(rowStride * heightImg)

        override fun close() = Unit
    }

    private class FixedThermal : ThermalReader {
        override fun status(): Int = THERMAL_STATUS_LIGHT

        override fun headroom(): Float = HEADROOM
    }

    @After
    fun clearTheHolder() {
        CaptureStatsHolder.latest = null
    }

    private fun pipelineWithDetector(): FramePipeline {
        val detector = FakePoseDetector(emptyList(), 320, 320, Accelerator.CPU)
        val pipeline =
            FramePipeline(detector, TrackerConfig(), NanoClock { NOW_NS }, { }, { _, _ -> })
        pipeline.setExpectedSize(CaptureSize(WIDTH, HEIGHT))
        return pipeline
    }

    private val headXsImg = floatArrayOf(130f, 124f, 136f, 118f, 142f)
    private val headYsImg = floatArrayOf(40f, 36f, 36f, 40f, 40f)

    /** A pipeline whose detector sees one person 200 px high in every [TRACK_WIDTH] x [TRACK_HEIGHT] frame. */
    private fun pipelineWithOnePerson(): FramePipeline {
        val xy = FloatArray(TrackerConfig.HEAD_KEYPOINT_COUNT * 2) { (if (it % 2 == 0) headXsImg else headYsImg)[it / 2] }
        val person =
            PoseDetection(100f, 20f, 160f, 220f, 0.9f, xy, FloatArray(TrackerConfig.HEAD_KEYPOINT_COUNT) { 0.9f })
        val recorded = DetectionFrame(0L, TimestampSource.IMAGE, TRACK_WIDTH, TRACK_HEIGHT, listOf(person))
        val detector = FakePoseDetector(listOf(recorded), 320, 320, Accelerator.CPU)
        val pipeline = FramePipeline(detector, TrackerConfig(), NanoClock { NOW_NS }, { }, { _, _ -> })
        pipeline.setExpectedSize(CaptureSize(TRACK_WIDTH, TRACK_HEIGHT))
        return pipeline
    }

    @Test
    fun publish_carriesTheTimingSummaryOfThePipeline() {
        val pipeline = pipelineWithDetector()
        pipeline.onFrame(SolidImage(), NOW_NS - MS_NS)
        pipeline.onFrame(SolidImage(), NOW_NS - MS_NS)

        CaptureStatsHolder.publish(OverlayState(), pipeline, null)

        val timing = CaptureStatsHolder.latest!!.timing
        assertThat(timing).isEqualTo(pipeline.timingSummary())
        assertThat(timing.samples).isEqualTo(2)
    }

    @Test
    fun publish_beforeAnyFrame_hasAnEmptyTiming() {
        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null)

        assertThat(CaptureStatsHolder.latest!!.timing).isEqualTo(TimingSummary.EMPTY)
    }

    @Test
    fun publish_carriesTheThermalReading() {
        val sampler = ThermalSampler(FixedThermal(), TrackerConfig().thermalSampleIntervalNs) { _, _ -> }
        sampler.sample(NOW_NS)

        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), sampler)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.thermalStatus).isEqualTo("LIGHT")
        assertThat(stats.thermalHeadroom).isEqualTo(HEADROOM)
    }

    @Test
    fun publish_carriesTheThermalStepDown() {
        val policy = ThermalStepDown(TrackerConfig())
        policy.update(2, Float.NaN, 0L)
        policy.update(2, Float.NaN, 4_000_000_000L)
        val hotMs = 10_000L

        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null, policy, 14_000_000_000L, "blocked")

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.thermalState).isEqualTo("HOT")
        assertThat(stats.thermalSmallModel).isEqualTo("blocked")
        assertThat(stats.thermalStepDowns).isEqualTo(1)
        assertThat(stats.thermalHotMs).isEqualTo(hotMs)
    }

    @Test
    fun publish_withoutAStepDownPolicy_isNormal() {
        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.thermalState).isEqualTo("NORMAL")
        assertThat(stats.thermalSmallModel).isEqualTo("no")
        assertThat(stats.thermalStepDowns).isEqualTo(0)
        assertThat(stats.thermalHotMs).isEqualTo(0L)
    }

    @Test
    fun publish_withoutAThermalSampler_saysNotAvailable() {
        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.thermalStatus).isEqualTo("n/a")
        assertThat(stats.thermalHeadroom).isNaN()
    }

    @Test
    fun publish_beforeAnyFrame_hasZeroTrackCounters() {
        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.tracksShown).isEqualTo(0)
        assertThat(stats.tracksMostAtOnce).isEqualTo(0)
        assertThat(stats.trackBirths).isEqualTo(0)
        assertThat(stats.trackConfirmations).isEqualTo(0)
        assertThat(stats.trackIdlePromotions).isEqualTo(0)
        assertThat(stats.trackRecoveries).isEqualTo(0)
        assertThat(stats.trackRemovals).isEqualTo(0)
    }

    @Test
    fun publish_tracksShownIsTheNumberOfTracksOfTheNewestState() {
        val state = OverlayState(tracks = TrackSnapshot.of(FakeTracks(2), 320, 240))

        CaptureStatsHolder.publish(state, pipelineWithDetector(), null)

        assertThat(CaptureStatsHolder.latest!!.tracksShown).isEqualTo(2)
    }

    @Test
    fun publish_afterThreeFramesOfOnePerson_carriesTheTrackCountersOfThePipeline() {
        val pipeline = pipelineWithOnePerson()
        // The image has no valid timestamp (0), so each frame is stamped with its arrival, 30 ms apart.
        for (k in 0 until 3) pipeline.onFrame(SolidImage(TRACK_WIDTH, TRACK_HEIGHT, 0L), NOW_NS + k * FRAME_GAP_NS)

        CaptureStatsHolder.publish(OverlayState(), pipeline, null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.trackBirths).isEqualTo(1)
        assertThat(stats.trackConfirmations).isEqualTo(1)
        assertThat(stats.tracksMostAtOnce).isEqualTo(1)
        assertThat(stats.trackIdlePromotions).isEqualTo(0)
        assertThat(stats.trackRecoveries).isEqualTo(0)
        assertThat(stats.trackRemovals).isEqualTo(0)
    }

    @Test
    fun publish_beforeAnyFrame_hasZeroLockCounters() {
        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.locks).isEqualTo(0)
        assertThat(stats.lockRelocks).isEqualTo(0)
        assertThat(stats.lockRelockRejects).isEqualTo(0)
        assertThat(stats.lockExpired).isEqualTo(0)
    }

    @Test
    fun publish_beforeAnyFrame_hasZeroAssocMissCounters() {
        CaptureStatsHolder.publish(OverlayState(), pipelineWithDetector(), null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.assocMisses).isEqualTo(0)
        assertThat(stats.assocMissGate).isEqualTo(0)
        assertThat(stats.assocMissCost).isEqualTo(0)
        assertThat(stats.assocMissTaken).isEqualTo(0)
        assertThat(stats.assocMissLowBirth).isEqualTo(0)
        assertThat(stats.assocBirthNearLive).isEqualTo(0)
        assertThat(stats.assocBirthAfterRemoval).isEqualTo(0)
        assertThat(stats.assocBirthSuppressed).isEqualTo(0)
    }

    @Test
    fun publish_afterALockedFrame_carriesTheLockCountOfThePipeline() {
        val pipeline = pipelineWithOnePerson()
        for (k in 0 until 3) pipeline.onFrame(SolidImage(TRACK_WIDTH, TRACK_HEIGHT, 0L), NOW_NS + k * FRAME_GAP_NS)
        pipeline.requestLock(1)
        pipeline.onFrame(SolidImage(TRACK_WIDTH, TRACK_HEIGHT, 0L), NOW_NS + 3 * FRAME_GAP_NS)

        CaptureStatsHolder.publish(OverlayState(), pipeline, null)

        val stats = CaptureStatsHolder.latest!!
        assertThat(stats.locks).isEqualTo(1)
        assertThat(stats.lockRelocks).isEqualTo(0)
        assertThat(stats.lockRelockRejects).isEqualTo(0)
        assertThat(stats.lockExpired).isEqualTo(0)
    }
}
