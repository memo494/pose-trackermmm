package com.memo494.posetracker.core.decode

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.PoseOutputLayout
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RawPoseDecoderTest {
    private class Row(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val score: Float,
        val keypoints: FloatArray = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 3),
    )

    private fun flat(
        layout: PoseOutputLayout,
        rows: List<Row>,
    ): FloatArray {
        val channels = TrackerConfig.POSE_RAW_CHANNELS
        val out = FloatArray(channels * rows.size)
        for ((r, row) in rows.withIndex()) {
            val values = floatArrayOf(row.cx, row.cy, row.w, row.h, row.score) + row.keypoints
            for (c in 0 until channels) {
                val index = if (layout == PoseOutputLayout.RAW_CHANNELS_FIRST) c * rows.size + r else r * channels + c
                out[index] = values[c]
            }
        }
        return out
    }

    private fun decoder(
        layout: PoseOutputLayout,
        rows: Int,
        width: Int = 640,
        height: Int = 640,
        config: TrackerConfig = TrackerConfig(),
    ) = RawPoseDecoder(layout, rows, width, height, config)

    private fun person(
        cx: Float,
        cy: Float,
        score: Float,
        size: Float = 100f,
    ) = Row(cx, cy, size, size, score)

    private val raw = listOf(PoseOutputLayout.RAW_CHANNELS_FIRST, PoseOutputLayout.RAW_CHANNELS_LAST)

    @Test
    fun channelsFirstAndChannelsLast_giveIdenticalDetections() {
        val rows =
            listOf(
                person(100f, 100f, 0.9f),
                person(105f, 102f, 0.8f),
                person(400f, 300f, 0.6f, size = 150f),
                person(500f, 500f, 0.1f),
                Row(300f, 50f, 40f, 80f, 0.7f, FloatArray(51) { it * 3f }),
            )
        val first = decoder(PoseOutputLayout.RAW_CHANNELS_FIRST, rows.size)
        val last = decoder(PoseOutputLayout.RAW_CHANNELS_LAST, rows.size)

        val a = first.decode(flat(PoseOutputLayout.RAW_CHANNELS_FIRST, rows), 0.25f)
        val b = last.decode(flat(PoseOutputLayout.RAW_CHANNELS_LAST, rows), 0.25f)

        assertThat(a).isEqualTo(3)
        assertThat(b).isEqualTo(a)
        for (i in 0 until a) {
            assertThat(last.scoreOf(i)).isEqualTo(first.scoreOf(i))
            assertThat(last.boxLeftMdl(i)).isEqualTo(first.boxLeftMdl(i))
            assertThat(last.boxTopMdl(i)).isEqualTo(first.boxTopMdl(i))
            assertThat(last.boxRightMdl(i)).isEqualTo(first.boxRightMdl(i))
            assertThat(last.boxBottomMdl(i)).isEqualTo(first.boxBottomMdl(i))
            for (k in 0 until TrackerConfig.POSE_KEYPOINT_COUNT) {
                assertThat(last.keypointXMdl(i, k)).isEqualTo(first.keypointXMdl(i, k))
                assertThat(last.keypointYMdl(i, k)).isEqualTo(first.keypointYMdl(i, k))
                assertThat(last.keypointScore(i, k)).isEqualTo(first.keypointScore(i, k))
            }
        }
    }

    @Test
    fun overlappingBoxes_keepTheBestOne() {
        for (layout in raw) {
            val rows = listOf(person(100f, 100f, 0.6f), person(104f, 100f, 0.9f), person(98f, 103f, 0.7f))
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows), 0.25f)

            assertThat(count).isEqualTo(1)
            assertThat(d.scoreOf(0)).isEqualTo(0.9f)
            assertThat(d.boxLeftMdl(0)).isEqualTo(54f)
        }
    }

    @Test
    fun separatePeople_areAllKept_bestFirst() {
        for (layout in raw) {
            val rows = listOf(person(100f, 100f, 0.5f), person(300f, 100f, 0.9f), person(500f, 100f, 0.7f))
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows), 0.25f)

            assertThat(count).isEqualTo(3)
            assertThat(listOf(d.scoreOf(0), d.scoreOf(1), d.scoreOf(2))).containsExactly(0.9f, 0.7f, 0.5f).inOrder()
            assertThat(d.boxLeftMdl(0)).isEqualTo(250f)
        }
    }

    @Test
    fun iouAboveTheThresholdIsSuppressed_belowIsKept() {
        // Two 100x100 boxes shifted by 50 px in x: intersection 5000, union 15000, IoU = 1/3.
        val rows = listOf(person(100f, 100f, 0.9f), person(150f, 100f, 0.8f))
        val layout = PoseOutputLayout.RAW_CHANNELS_FIRST
        val loose = decoder(layout, 2, config = TrackerConfig(rawNmsIou = 0.5f))
        val strict = decoder(layout, 2, config = TrackerConfig(rawNmsIou = TrackerConfig.MIN_RAW_NMS_IOU))

        assertThat(loose.decode(flat(layout, rows), 0.25f)).isEqualTo(2)
        assertThat(strict.decode(flat(layout, rows), 0.25f)).isEqualTo(1)
    }

    @Test
    fun scoreThreshold_dropsLowRows_andKeepsEqual() {
        for (layout in raw) {
            val rows = listOf(person(100f, 100f, 0.25f), person(300f, 100f, 0.2499f), person(500f, 100f, 0.9f))
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows), 0.25f)

            assertThat(count).isEqualTo(2)
            assertThat(d.scoreOf(1)).isEqualTo(0.25f)
        }
    }

    @Test
    fun keypoints_mapToTheirOwnIndexAndTriplet() {
        for (layout in raw) {
            val keypoints = FloatArray(51)
            for (k in 0 until 17) {
                keypoints[k * 3] = 10f + k
                keypoints[k * 3 + 1] = 200f + k
                keypoints[k * 3 + 2] = 0.5f + k / 100f
            }
            val rows = listOf(Row(300f, 300f, 100f, 200f, 0.8f, keypoints))
            val d = decoder(layout, 1)

            assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(1)

            for (k in 0 until 17) {
                assertThat(d.keypointXMdl(0, k)).isEqualTo(10f + k)
                assertThat(d.keypointYMdl(0, k)).isEqualTo(200f + k)
                assertThat(d.keypointScore(0, k)).isEqualTo(0.5f + k / 100f)
            }
            assertThat(d.boxLeftMdl(0)).isEqualTo(250f)
            assertThat(d.boxTopMdl(0)).isEqualTo(200f)
            assertThat(d.boxRightMdl(0)).isEqualTo(350f)
            assertThat(d.boxBottomMdl(0)).isEqualTo(400f)
        }
    }

    @Test
    fun normalizedOutput_isScaledByTheInputSize() {
        for (layout in raw) {
            val keypoints = FloatArray(51)
            keypoints[0] = 0.5f
            keypoints[1] = 0.25f
            keypoints[2] = 0.9f
            val rows = listOf(Row(0.5f, 0.5f, 0.2f, 0.4f, 0.8f, keypoints))
            val d = decoder(layout, 1, width = 320, height = 192)

            assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(1)

            assertThat(d.boxLeftMdl(0)).isWithin(1e-3f).of(0.4f * 320f)
            assertThat(d.boxRightMdl(0)).isWithin(1e-3f).of(0.6f * 320f)
            assertThat(d.boxTopMdl(0)).isWithin(1e-3f).of(0.3f * 192f)
            assertThat(d.boxBottomMdl(0)).isWithin(1e-3f).of(0.7f * 192f)
            assertThat(d.keypointXMdl(0, 0)).isWithin(1e-3f).of(160f)
            assertThat(d.keypointYMdl(0, 0)).isWithin(1e-3f).of(48f)
            assertThat(d.keypointScore(0, 0)).isEqualTo(0.9f)
        }
    }

    @Test
    fun emptyOutput_givesNoDetections() {
        for (layout in raw) {
            val rows = listOf(person(100f, 100f, 0.1f), person(300f, 100f, 0f))
            val d = decoder(layout, rows.size)

            assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(0)
            assertThat(d.count).isEqualTo(0)
        }
    }

    @Test
    fun nanDegenerateAndNonFiniteRows_areSkipped() {
        val layout = PoseOutputLayout.RAW_CHANNELS_LAST
        val rows =
            listOf(
                person(100f, 100f, Float.NaN),
                Row(300f, 100f, 0f, 50f, 0.9f),
                Row(300f, 300f, -10f, 50f, 0.9f),
                Row(Float.NaN, 300f, 50f, 50f, 0.9f),
                Row(500f, 300f, Float.POSITIVE_INFINITY, 50f, 0.9f),
                person(500f, 500f, 0.8f),
            )
        val d = decoder(layout, rows.size)

        assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(1)
        assertThat(d.scoreOf(0)).isEqualTo(0.8f)
    }

    @Test
    fun moreThanTheCap_keepsTheBestOnes() {
        val layout = PoseOutputLayout.RAW_CHANNELS_FIRST
        val total = TrackerConfig.RAW_MAX_DETECTIONS + 100
        // 25 columns x 16 rows of 10 px boxes on a 25 px grid: none overlap. Scores fall from 0.99 in steps of 0.001.
        val rows = List(total) { i -> Row(20f + (i % 25) * 25f, 20f + (i / 25) * 25f, 10f, 10f, 0.99f - i * 0.001f) }
        val d = decoder(layout, total)

        val count = d.decode(flat(layout, rows), 0.25f)

        assertThat(count).isEqualTo(TrackerConfig.RAW_MAX_DETECTIONS)
        assertThat(d.scoreOf(0)).isWithin(1e-6f).of(0.99f)
        assertThat(d.scoreOf(count - 1)).isWithin(1e-6f).of(0.99f - (count - 1) * 0.001f)
        for (i in 1 until count) assertThat(d.scoreOf(i)).isAtMost(d.scoreOf(i - 1))
    }

    @Test
    fun decodingTwice_doesNotKeepTheFirstFramesDetections() {
        val layout = PoseOutputLayout.RAW_CHANNELS_FIRST
        val d = decoder(layout, 2)

        assertThat(d.decode(flat(layout, listOf(person(100f, 100f, 0.9f), person(400f, 400f, 0.8f))), 0.25f)).isEqualTo(2)
        val second = d.decode(flat(layout, listOf(person(100f, 100f, 0.1f), person(400f, 400f, 0.7f))), 0.25f)

        assertThat(second).isEqualTo(1)
        assertThat(d.scoreOf(0)).isEqualTo(0.7f)
        assertThat(d.boxLeftMdl(0)).isEqualTo(350f)
    }

    @Test
    fun endToEndLayout_isRefused() {
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.END_TO_END, 10) }
    }

    @Test
    fun nonPositiveRowsOrInputSize_areRefused() {
        val layout = PoseOutputLayout.RAW_CHANNELS_FIRST
        assertThrows(IllegalArgumentException::class.java) { decoder(layout, 0) }
        assertThrows(IllegalArgumentException::class.java) { decoder(layout, 10, width = 0) }
        assertThrows(IllegalArgumentException::class.java) { decoder(layout, 10, height = -1) }
    }

    @Test
    fun outputOfTheWrongSize_isRefused() {
        val d = decoder(PoseOutputLayout.RAW_CHANNELS_FIRST, 10)

        assertThrows(IllegalArgumentException::class.java) { d.decode(FloatArray(10 * 56 - 1), 0.25f) }
    }

    @Test
    fun normalizedFrameWithOnePixelLookingRow_dropsTheOutlier_andKeepsEveryRealPerson() {
        for (layout in raw) {
            val rows =
                List(5) { i -> Row(0.1f + i * 0.18f, 0.5f, 0.1f, 0.3f, 0.8f - i * 0.01f) } +
                    Row(300f, 300f, 100f, 100f, 0.99f)
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows), 0.25f)

            assertThat(count).isEqualTo(5)
            for (i in 0 until count) {
                assertThat(d.scoreOf(i)).isLessThan(0.9f)
                assertThat(d.boxRightMdl(i) - d.boxLeftMdl(i)).isWithin(1e-2f).of(0.1f * 640f)
            }
        }
    }

    @Test
    fun pixelFrameWithOneNormalizedLookingRow_decodesEveryRowAsPixels() {
        for (layout in raw) {
            val rows = List(5) { i -> person(60f + i * 120f, 300f, 0.8f - i * 0.01f) } + Row(0.5f, 0.5f, 0.2f, 0.2f, 0.99f)
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows), 0.25f)

            assertThat(count).isEqualTo(6)
            assertThat(d.scoreOf(0)).isEqualTo(0.99f)
            assertThat(d.boxLeftMdl(0)).isWithin(1e-4f).of(0.4f)
            assertThat(d.boxRightMdl(0)).isWithin(1e-4f).of(0.6f)
            assertThat(d.boxRightMdl(1) - d.boxLeftMdl(1)).isEqualTo(100f)
        }
    }

    @Test
    fun aTieBetweenNormalizedAndPixelRows_isReadAsPixels() {
        val layout = PoseOutputLayout.RAW_CHANNELS_FIRST
        val rows = listOf(Row(0.5f, 0.5f, 0.2f, 0.2f, 0.9f), person(400f, 400f, 0.8f))
        val d = decoder(layout, rows.size)

        assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(2)

        assertThat(d.boxLeftMdl(0)).isWithin(1e-4f).of(0.4f)
        assertThat(d.boxLeftMdl(1)).isEqualTo(350f)
    }

    @Test
    fun rowsBelowTheThreshold_doNotVoteOnTheCoordinateKind() {
        val layout = PoseOutputLayout.RAW_CHANNELS_LAST
        // Three pixel-looking rows are below the threshold; the one passing row is normalized.
        val rows = listOf(person(100f, 100f, 0.1f), person(300f, 300f, 0.1f), person(500f, 500f, 0.1f), Row(0.5f, 0.5f, 0.2f, 0.4f, 0.9f))
        val d = decoder(layout, rows.size, width = 320, height = 192)

        assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(1)
        assertThat(d.boxLeftMdl(0)).isWithin(1e-3f).of(0.4f * 320f)
    }

    @Test
    fun nonFiniteKeypointValues_becomeAKeypointWithConfidenceZeroAndFiniteCoordinates() {
        for (layout in raw) {
            val keypoints = FloatArray(51) { 10f }
            keypoints[3 * 3] = Float.NaN // keypoint 3 x
            keypoints[4 * 3 + 1] = Float.POSITIVE_INFINITY // keypoint 4 y
            keypoints[5 * 3 + 2] = Float.NaN // keypoint 5 conf
            val rows = listOf(Row(300f, 300f, 100f, 200f, 0.8f, keypoints))
            val d = decoder(layout, 1)

            assertThat(d.decode(flat(layout, rows), 0.25f)).isEqualTo(1)

            for (k in listOf(3, 4, 5)) {
                assertThat(d.keypointScore(0, k)).isEqualTo(0f)
                assertThat(d.keypointXMdl(0, k).isFinite()).isTrue()
                assertThat(d.keypointYMdl(0, k).isFinite()).isTrue()
            }
            assertThat(d.keypointScore(0, 2)).isEqualTo(10f)
            assertThat(d.keypointXMdl(0, 2)).isEqualTo(10f)
        }
    }
}
