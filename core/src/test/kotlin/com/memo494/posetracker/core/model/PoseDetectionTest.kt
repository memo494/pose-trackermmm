package com.memo494.posetracker.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PoseDetectionTest {
    @Test
    fun keypointAccessors_returnInterleavedCoordinates() {
        val detection =
            PoseDetection(
                boxLeftImg = 1f,
                boxTopImg = 2f,
                boxRightImg = 30f,
                boxBottomImg = 40f,
                score = 0.9f,
                keypointsXyImg = floatArrayOf(10f, 11f, 20f, 21f),
                keypointScores = floatArrayOf(0.5f, 0.6f),
            )

        assertThat(detection.keypointCount).isEqualTo(2)
        assertThat(detection.keypointXImg(1)).isEqualTo(20f)
        assertThat(detection.keypointYImg(1)).isEqualTo(21f)
    }

    @Test
    fun construction_withMismatchedKeypointArrays_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PoseDetection(
                boxLeftImg = 0f,
                boxTopImg = 0f,
                boxRightImg = 1f,
                boxBottomImg = 1f,
                score = 0.5f,
                keypointsXyImg = floatArrayOf(1f, 2f, 3f),
                keypointScores = floatArrayOf(0.5f, 0.5f),
            )
        }
    }

    /** Box 10..30 x 20..60; keypoints 0-4 (head) at x = 10 + k, y = 20 + k with the given scores, plus one body keypoint. */
    private fun withHeadScores(vararg headScores: Float): PoseDetection {
        val count = headScores.size + 1
        val xy = FloatArray(count * 2)
        for (k in 0 until count) {
            xy[k * 2] = 10f + k
            xy[k * 2 + 1] = 20f + k
        }
        return PoseDetection(10f, 20f, 30f, 60f, 0.9f, xy, headScores + floatArrayOf(1f))
    }

    @Test
    fun headPoint_isTheMeanOfTheConfidentHeadKeypoints() {
        val detection = withHeadScores(0.9f, 0.2f, 0.6f, 0.1f, 0.1f)

        assertThat(detection.headXImg(0.5f)).isEqualTo(11f)
        assertThat(detection.headYImg(0.5f)).isEqualTo(21f)
    }

    @Test
    fun headPoint_ignoresConfidentBodyKeypoints() {
        val detection = withHeadScores(0.9f, 0.1f, 0.1f, 0.1f, 0.1f)

        assertThat(detection.headXImg(0.5f)).isEqualTo(10f)
        assertThat(detection.headYImg(0.5f)).isEqualTo(20f)
    }

    @Test
    fun headPoint_withoutAConfidentHeadKeypoint_fallsBackToTheBoxTopCentre() {
        // A person turned away (no confident face keypoints) still gets a head point, so a ring is drawn.
        val detection = withHeadScores(0.1f, 0.2f, 0.3f, 0.4f, 0.49f)

        assertThat(detection.headXImg(0.5f)).isEqualTo(20f)
        assertThat(detection.headYImg(0.5f)).isEqualTo(20f)
    }
}
