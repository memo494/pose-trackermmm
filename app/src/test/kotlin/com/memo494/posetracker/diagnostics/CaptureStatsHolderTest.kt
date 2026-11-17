package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.capture.FrameImage
import com.memo494.posetracker.capture.FramePipeline
import com.memo494.posetracker.capture.NanoClock
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.stats.ThermalReader
import com.memo494.posetracker.core.stats.ThermalSampler
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
private const val THERMAL_STATUS_LIGHT = 1
private const val HEADROOM = 0.75f

class CaptureStatsHolderTest {
    private class SolidImage : FrameImage {
        override val widthImg = WIDTH
        override val heightImg = HEIGHT
        override val timestampNs = NOW_NS - 5 * MS_NS
        override val rowStride = WIDTH * TrackerConfig.RGBA_BYTES_PER_PIXEL
        override val pixelStride = TrackerConfig.RGBA_BYTES_PER_PIXEL
        override val pixels: ByteBuffer = ByteBuffer.allocate(rowStride * HEIGHT)

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
        val state = OverlayState(tracks = TrackSnapshot.of(FakeTracks(2)))

        CaptureStatsHolder.publish(state, pipelineWithDetector(), null)

        assertThat(CaptureStatsHolder.latest!!.tracksShown).isEqualTo(2)
    }
}
