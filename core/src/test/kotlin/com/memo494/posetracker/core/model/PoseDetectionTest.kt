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
}
