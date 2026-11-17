package com.memo494.posetracker.core.relock

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import org.junit.jupiter.api.Test

/** Unit tests of [TorsoRegion] (docs/plans/M4.md, M4c T1). */
class TorsoRegionTest {
    private val out = TorsoRect()

    /** Exact-in-binary ratios, so the expected whole numbers below carry no rounding doubt. */
    private val exactConfig = TrackerConfig(relockTorsoShrink = 0.25f, relockBoxBottomRatio = 0.5f, relockBoxWidthRatio = 0.5f)

    private fun person(
        keypointCount: Int = TrackerConfig.POSE_KEYPOINT_COUNT,
        leftShoulderScore: Float = 0.9f,
        boxLeftImg: Float = 100f,
        boxTopImg: Float = 20f,
        boxRightImg: Float = 160f,
        boxBottomImg: Float = 220f,
        shoulderXs: FloatArray = floatArrayOf(100f, 140f),
        hipXs: FloatArray = floatArrayOf(105f, 135f),
    ): PoseDetection {
        val xy = FloatArray(keypointCount * 2)
        val scores = FloatArray(keypointCount)
        fun put(
            k: Int,
            xImg: Float,
            yImg: Float,
            score: Float,
        ) {
            if (k >= keypointCount) return
            xy[k * 2] = xImg
            xy[k * 2 + 1] = yImg
            scores[k] = score
        }
        put(TrackerConfig.KEYPOINT_LEFT_SHOULDER, shoulderXs[0], 60f, leftShoulderScore)
        put(TrackerConfig.KEYPOINT_RIGHT_SHOULDER, shoulderXs[1], 60f, 0.9f)
        put(TrackerConfig.KEYPOINT_LEFT_HIP, hipXs[0], 140f, 0.9f)
        put(TrackerConfig.KEYPOINT_RIGHT_HIP, hipXs[1], 140f, 0.9f)
        return PoseDetection(boxLeftImg, boxTopImg, boxRightImg, boxBottomImg, 0.9f, xy, scores)
    }

    private fun torso(
        detection: PoseDetection,
        config: TrackerConfig = exactConfig,
        widthImg: Int = IMAGE_WIDTH,
        heightImg: Int = IMAGE_HEIGHT,
    ): Boolean = TorsoRegion(config).of(detection, widthImg, heightImg, out)

    private fun rect(): List<Int> = listOf(out.leftImg, out.topImg, out.rightImg, out.bottomImg)

    @Test
    fun withFourConfidentKeypoints_theTorsoIsTheirRectangleCutOnEverySide() {
        assertThat(torso(person())).isTrue()

        // Shoulders and hips span x 100..140, y 60..140; a quarter of 40 and of 80 is cut from each side.
        assertThat(rect()).containsExactly(110, 80, 130, 120).inOrder()
    }

    @Test
    fun withTheDefaultShrink_theTorsoLiesInsideTheKeypointRectangleAndBelowTheShoulders() {
        assertThat(torso(person(), TrackerConfig())).isTrue()

        assertThat(out.leftImg).isAtLeast(100)
        assertThat(out.rightImg).isAtMost(140)
        assertThat(out.topImg).isAtLeast(60)
        assertThat(out.bottomImg).isAtMost(140)
        assertThat(out.isEmpty).isFalse()
    }

    @Test
    fun aShoulderBelowTheKeypointScore_fallsBackToTheBox() {
        assertThat(torso(person(leftShoulderScore = 0.4f))).isTrue()

        // Box 100..160 x 20..220: rows 25 % to 50 % (70..120), the middle half of the width (115..145), a quarter cut.
        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
    }

    @Test
    fun aDetectionWithFewKeypoints_usesTheBox() {
        assertThat(torso(person(keypointCount = TrackerConfig.HEAD_KEYPOINT_COUNT))).isTrue()

        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
    }

    @Test
    fun aBoxTorso_neverReachesTheHeadRows() {
        val config = TrackerConfig()
        val detection = person(keypointCount = TrackerConfig.HEAD_KEYPOINT_COUNT)

        assertThat(torso(detection, config)).isTrue()

        val boxHeightImg = detection.boxBottomImg - detection.boxTopImg
        assertThat(out.topImg.toFloat()).isAtLeast(detection.boxTopImg + boxHeightImg * config.relockBoxTopRatio - 1f)
    }

    @Test
    fun keypointsWithNoWidth_fallBackToTheBox() {
        val detection = person(shoulderXs = floatArrayOf(120f, 120f), hipXs = floatArrayOf(120f, 120f))

        assertThat(torso(detection)).isTrue()

        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
    }

    @Test
    fun aKeypointThatIsNotFinite_fallsBackToTheBox() {
        val detection = person(shoulderXs = floatArrayOf(Float.NaN, 140f))

        assertThat(torso(detection)).isTrue()

        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
    }

    @Test
    fun theTorso_isClippedToTheImage() {
        val clipped = TrackerConfig(relockTorsoShrink = 0f)
        val detection = person(shoulderXs = floatArrayOf(-20f, 40f), hipXs = floatArrayOf(-20f, 40f))

        assertThat(torso(detection, clipped, widthImg = 30, heightImg = 100)).isTrue()

        assertThat(rect()).containsExactly(0, 60, 30, 100).inOrder()
    }

    @Test
    fun aTorsoEntirelyOutsideTheImage_isNone() {
        val clipped = TrackerConfig(relockTorsoShrink = 0f)
        val detection = person(shoulderXs = floatArrayOf(400f, 440f), hipXs = floatArrayOf(400f, 440f))

        assertThat(torso(detection, clipped)).isFalse()

        assertThat(out.isEmpty).isTrue()
    }

    @Test
    fun aFailure_clearsWhatWasInTheRectangleBefore() {
        assertThat(torso(person())).isTrue()

        assertThat(torso(person(leftShoulderScore = 0.1f, boxRightImg = 100f))).isFalse()

        assertThat(out.isEmpty).isTrue()
    }

    @Test
    fun aBoxThatIsNotUsable_givesNoTorso() {
        val noKeypoints = TrackerConfig.HEAD_KEYPOINT_COUNT

        assertThat(torso(person(noKeypoints, boxRightImg = 100f))).isFalse()
        assertThat(torso(person(noKeypoints, boxBottomImg = 20f))).isFalse()
        assertThat(torso(person(noKeypoints, boxRightImg = Float.NaN))).isFalse()
        assertThat(torso(person(noKeypoints, boxTopImg = Float.NEGATIVE_INFINITY))).isFalse()
    }

    @Test
    fun anImageWithNoSize_givesNoTorso() {
        assertThat(torso(person(), widthImg = 0)).isFalse()
        assertThat(torso(person(), heightImg = 0)).isFalse()
    }

    private companion object {
        const val IMAGE_WIDTH = 320
        const val IMAGE_HEIGHT = 240
    }
}
