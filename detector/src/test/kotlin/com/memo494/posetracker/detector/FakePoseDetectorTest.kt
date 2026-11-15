package com.memo494.posetracker.detector

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.replay.ReplayFixture
import org.junit.Assert.assertThrows
import org.junit.Test

class FakePoseDetectorTest {
    private val fixtureJson =
        """
        {"version":1,"frames":[
          {"timestampNs":100,"timestampSource":"IMAGE","widthImg":640,"heightImg":480,"detections":[
            {"box":[1,2,30,40],"score":0.9,"keypointsXy":[10,20,30,40],"keypointScores":[0.8,0.7]}]},
          {"timestampNs":200,"timestampSource":"IMAGE","widthImg":640,"heightImg":480,"detections":[]},
          {"timestampNs":300,"timestampSource":"IMAGE","widthImg":320,"heightImg":240,"detections":[
            {"box":[5,6,7,8],"score":0.5,"keypointsXy":[],"keypointScores":[]},
            {"box":[9,10,11,12],"score":0.6,"keypointsXy":[],"keypointScores":[]}]}
        ]}
        """.trimIndent()

    private fun captureFrame(
        timestampNs: Long,
        timestampSource: TimestampSource = TimestampSource.ARRIVAL,
        widthImg: Int = INCOMING_WIDTH,
        heightImg: Int = INCOMING_HEIGHT,
    ): CaptureFrame {
        val frame = CaptureFrame()
        val rgba = ByteArray(widthImg * heightImg * TrackerConfig.RGBA_BYTES_PER_PIXEL)
        assertThat(frame.set(rgba, widthImg, heightImg, timestampNs, timestampSource)).isTrue()
        return frame
    }

    private fun fixtureDetector(): FakePoseDetector =
        FakePoseDetector(ReplayFixture.parse(fixtureJson), INPUT_SIZE, INPUT_SIZE, Accelerator.GPU)

    @Test
    fun replaysTheFixtureFramesInOrderAndLoops() {
        val detector = fixtureDetector()

        val counts = List(FIXTURE_FRAMES * 2) { detector.detect(captureFrame(it.toLong())).detections.size }

        assertThat(counts).containsExactly(1, 0, 2, 1, 0, 2).inOrder()
    }

    @Test
    fun keepsTheRecordedDetectionsAndImgSize() {
        val detector = fixtureDetector()

        val first = detector.detect(captureFrame(timestampNs = 7L))
        val detection = first.detections.single()

        assertThat(first.widthImg).isEqualTo(640)
        assertThat(first.heightImg).isEqualTo(480)
        assertThat(detection.score).isEqualTo(0.9f)
        assertThat(detection.boxRightImg).isEqualTo(30f)
        assertThat(detection.keypointsXyImg.toList()).containsExactly(10f, 20f, 30f, 40f).inOrder()
        assertThat(detection.keypointScores.toList()).containsExactly(0.8f, 0.7f).inOrder()
    }

    @Test
    fun takesTimestampAndSourceFromTheIncomingFrame() {
        val detector = fixtureDetector()

        val result = detector.detect(captureFrame(timestampNs = 987_654L, timestampSource = TimestampSource.IMAGE))

        assertThat(result.timestampNs).isEqualTo(987_654L)
        assertThat(result.timestampSource).isEqualTo(TimestampSource.IMAGE)
    }

    @Test
    fun emptyListGivesFramesWithTheIncomingSizeAndNoDetections() {
        val detector = FakePoseDetector(emptyList(), INPUT_SIZE, INPUT_SIZE, Accelerator.CPU)

        val result = detector.detect(captureFrame(timestampNs = 42L, widthImg = 12, heightImg = 9))

        assertThat(result).isEqualTo(DetectionFrame(42L, TimestampSource.ARRIVAL, 12, 9, emptyList()))
    }

    @Test
    fun reportsItsConfigurationAndZeroInferenceTime() {
        val detector = FakePoseDetector(emptyList(), INPUT_SIZE, INPUT_SIZE, Accelerator.CPU)
        detector.detect(captureFrame(timestampNs = 1L))

        assertThat(detector.inputWidth).isEqualTo(INPUT_SIZE)
        assertThat(detector.inputHeight).isEqualTo(INPUT_SIZE)
        assertThat(detector.accelerator).isEqualTo(Accelerator.CPU)
        assertThat(detector.lastInferenceMs).isEqualTo(0f)
    }

    @Test
    fun reportsARectangularInputAsWidthAndHeight() {
        val detector = FakePoseDetector(emptyList(), RECT_WIDTH, RECT_HEIGHT, Accelerator.GPU)

        assertThat(detector.inputWidth).isEqualTo(RECT_WIDTH)
        assertThat(detector.inputHeight).isEqualTo(RECT_HEIGHT)
    }

    @Test
    fun detectAfterCloseThrows() {
        val detector = fixtureDetector()
        detector.close()

        assertThrows(IllegalStateException::class.java) { detector.detect(captureFrame(timestampNs = 1L)) }
    }

    @Test
    fun closeTwiceIsHarmless() {
        val detector = fixtureDetector()

        detector.close()
        detector.close()
    }

    private companion object {
        const val INPUT_SIZE = 416
        const val RECT_WIDTH = 832
        const val RECT_HEIGHT = 384
        const val FIXTURE_FRAMES = 3
        const val INCOMING_WIDTH = 8
        const val INCOMING_HEIGHT = 6
    }
}
