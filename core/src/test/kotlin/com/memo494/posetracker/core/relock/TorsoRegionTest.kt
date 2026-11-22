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
        rightShoulderScore: Float = 0.9f,
        hipScore: Float = 0.9f,
        shoulderYImg: Float = 60f,
        rightShoulderYImg: Float = shoulderYImg,
        hipYImg: Float = 140f,
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
        put(TrackerConfig.KEYPOINT_LEFT_SHOULDER, shoulderXs[0], shoulderYImg, leftShoulderScore)
        put(TrackerConfig.KEYPOINT_RIGHT_SHOULDER, shoulderXs[1], rightShoulderYImg, rightShoulderScore)
        put(TrackerConfig.KEYPOINT_LEFT_HIP, hipXs[0], hipYImg, hipScore)
        put(TrackerConfig.KEYPOINT_RIGHT_HIP, hipXs[1], hipYImg, hipScore)
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
    fun confidentKeypointsWithNoWidth_failClosedInsteadOfUsingTheBox() {
        val detection = person(shoulderXs = floatArrayOf(120f, 120f), hipXs = floatArrayOf(120f, 120f))

        assertThat(torso(detection)).isFalse()

        assertThat(out.isEmpty).isTrue()
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
        assertThat(out.clipped).isTrue()
    }

    @Test
    fun aTorsoInsideTheImage_isNotMarkedClipped() {
        assertThat(torso(person())).isTrue()

        assertThat(out.clipped).isFalse()
    }

    @Test
    fun aBoxTorsoThatTheImageEdgeCuts_isMarkedClipped() {
        val detection = person(keypointCount = TrackerConfig.HEAD_KEYPOINT_COUNT, boxLeftImg = -30f, boxRightImg = 30f)

        assertThat(torso(detection)).isTrue()

        assertThat(out.clipped).isTrue()
        assertThat(out.leftImg).isEqualTo(0)
    }

    @Test
    fun aTorsoTouchingTheEdgeExactly_isNotMarkedClipped() {
        val flush = TrackerConfig(relockTorsoShrink = 0f)
        val detection = person(shoulderXs = floatArrayOf(0f, 40f), hipXs = floatArrayOf(0f, 40f))

        assertThat(torso(detection, flush)).isTrue()

        assertThat(out.clipped).isFalse()
        assertThat(out.leftImg).isEqualTo(0)
    }

    @Test
    fun aFailure_isNotMarkedClipped() {
        assertThat(torso(person(shoulderXs = floatArrayOf(-50f, -20f), hipXs = floatArrayOf(-50f, -20f)))).isFalse()

        assertThat(out.clipped).isFalse()
    }

    @Test
    fun aBoxWiderThanAWholeBody_givesNoTorsoWithoutKeypoints() {
        val strict = TrackerConfig(relockFullBodyMinAspect = 2f)
        val noKeypoints = TrackerConfig.HEAD_KEYPOINT_COUNT

        // A box 50 wide: 100 high is exactly the aspect, 99 high is too wide.
        val exact = person(noKeypoints, boxLeftImg = 100f, boxRightImg = 150f, boxTopImg = 20f, boxBottomImg = 120f)
        val tooWide = person(noKeypoints, boxLeftImg = 100f, boxRightImg = 150f, boxTopImg = 20f, boxBottomImg = 119f)
        assertThat(torso(exact, strict)).isTrue()
        assertThat(torso(tooWide, strict)).isFalse()
        assertThat(out.isEmpty).isTrue()
    }

    @Test
    fun theAspectLimit_doesNotApplyWhenTheKeypointsGiveTheTorso() {
        val wide = person(boxLeftImg = 90f, boxRightImg = 200f, boxTopImg = 20f, boxBottomImg = 90f)

        assertThat(torso(wide)).isTrue()

        assertThat(rect()).containsExactly(110, 80, 130, 120).inOrder()
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

    /** Upper-body config: a quarter cut, and a torso half a shoulder width tall (exact in binary). */
    private val upperConfig =
        TrackerConfig(
            relockTorsoShrink = 0.25f,
            relockBoxBottomRatio = 0.5f,
            relockBoxWidthRatio = 0.5f,
            relockUpperTorsoRatio = 0.5f,
        )

    /** A person whose hips are not seen (a desk, the frame edge): a box that is not a whole body (1.2 tall per wide). */
    private fun upperBodyOnly(
        leftShoulderScore: Float = 0.9f,
        rightShoulderScore: Float = 0.9f,
        shoulderYImg: Float = 60f,
    ): PoseDetection =
        person(
            leftShoulderScore = leftShoulderScore,
            rightShoulderScore = rightShoulderScore,
            hipScore = 0.1f,
            boxBottomImg = 92f,
            shoulderYImg = shoulderYImg,
        )

    @Test
    fun theTorsoSource_isKeypointsBoxOrUpperBody_andNoneAfterAFailure() {
        assertThat(torso(person())).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_KEYPOINTS)

        assertThat(torso(person(hipScore = 0.1f))).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_BOX)

        assertThat(torso(upperBodyOnly(), upperConfig)).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_UPPER_BODY)

        assertThat(torso(upperBodyOnly(leftShoulderScore = 0.1f), upperConfig)).isFalse()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_NONE)
    }

    @Test
    fun withoutHips_theTorsoIsFromTheShouldersDownByTheUpperRatioTimesTheirWidth() {
        assertThat(torso(upperBodyOnly(), upperConfig)).isTrue()

        // Shoulders x 100..140 (width 40) at y 60; half of 40 down is y 80; a quarter of 40 and of 20 is cut.
        assertThat(rect()).containsExactly(110, 65, 130, 75).inOrder()
    }

    @Test
    fun theUpperBodyTorso_neverReachesAboveTheShoulders() {
        assertThat(torso(upperBodyOnly(), TrackerConfig())).isTrue()

        assertThat(out.topImg).isAtLeast(60)
        assertThat(out.leftImg).isAtLeast(100)
        assertThat(out.rightImg).isAtMost(140)
    }

    @Test
    fun withoutHipsAndOneShoulderNotConfident_thereIsNoTorso() {
        assertThat(torso(upperBodyOnly(leftShoulderScore = 0.4f), upperConfig)).isFalse()
        assertThat(out.isEmpty).isTrue()
        assertThat(torso(upperBodyOnly(rightShoulderScore = 0.4f), upperConfig)).isFalse()
    }

    @Test
    fun withoutHipsButAWholeBodyBox_theBoxPartIsUsedNotTheUpperBody() {
        val detection = person(hipScore = 0.1f)

        assertThat(torso(detection, upperConfig)).isTrue()

        // Box 100..160 x 20..220 (tall): rows 25 % to 50 % (70..120), the middle half of the width, a quarter cut.
        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
    }

    @Test
    fun shouldersWithNoWidth_giveNoUpperBodyTorso() {
        val detection = person(hipScore = 0.1f, boxBottomImg = 92f, shoulderXs = floatArrayOf(120f, 120f))

        assertThat(torso(detection, upperConfig)).isFalse()
    }

    @Test
    fun anUpperBodyTorsoThatTheImageEdgeCuts_isMarkedClipped() {
        assertThat(torso(upperBodyOnly(shoulderYImg = 70f), upperConfig, heightImg = 80)).isTrue()

        assertThat(out.clipped).isTrue()
    }

    @Test
    fun anUpperBodyTorsoInsideTheImage_isNotMarkedClipped() {
        assertThat(torso(upperBodyOnly(), upperConfig)).isTrue()

        assertThat(out.clipped).isFalse()
    }

    @Test
    fun tiltedShoulders_theUpperBodyTorsoStartsAtTheLowerShoulder() {
        val detection = person(hipScore = 0.1f, boxBottomImg = 120f, shoulderYImg = 100f, rightShoulderYImg = 70f)

        assertThat(torso(detection, upperConfig)).isTrue()

        // Shoulders (100, 100) and (140, 70): the torso starts at y 100, 20 down (half of the 40 between them).
        assertThat(out.topImg).isAtLeast(100)
        assertThat(rect()).containsExactly(110, 105, 130, 115).inOrder()
    }

    @Test
    fun withFourConfidentKeypointsThatSpanNoRectangle_theBoxAndTheShouldersAreNotTried() {
        // The hips are above the shoulders; the box is not a whole body, so the shoulders alone would give a torso.
        val detection = person(hipYImg = 50f, boxBottomImg = 92f)

        assertThat(torso(detection, upperConfig)).isFalse()

        assertThat(out.isEmpty).isTrue()
    }

    @Test
    fun withFourConfidentKeypointsThatSpanNoRectangle_aWholeBodyBoxIsNotUsedEither() {
        assertThat(torso(person(hipYImg = 50f), upperConfig)).isFalse()
    }

    @Test
    fun upperBody_aShoulderCoordinateThatIsNotFinite_givesNoTorso() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val badX = person(hipScore = 0.1f, boxBottomImg = 92f, shoulderXs = floatArrayOf(100f, bad))
            val badY = person(hipScore = 0.1f, boxBottomImg = 92f, shoulderYImg = bad)

            assertThat(torso(badX, upperConfig)).isFalse()
            assertThat(torso(badY, upperConfig)).isFalse()
        }
    }

    @Test
    fun upperBody_aBoxThatIsNotFinite_givesNoTorso() {
        assertThat(torso(person(hipScore = 0.1f, boxBottomImg = Float.NaN), upperConfig)).isFalse()
        val infiniteRight = person(hipScore = 0.1f, boxRightImg = Float.POSITIVE_INFINITY, boxBottomImg = 92f)
        val nanTop = person(hipScore = 0.1f, boxTopImg = Float.NaN, boxBottomImg = 92f)
        assertThat(torso(infiniteRight, upperConfig)).isFalse()
        assertThat(torso(nanTop, upperConfig)).isFalse()
    }

    // ---- box-only models (M-legacy T3): the keypoints are never read ----

    private fun boxOnlyTorso(
        detection: PoseDetection,
        config: TrackerConfig = exactConfig,
    ): Boolean = TorsoRegion(config, boxOnly = true).of(detection, IMAGE_WIDTH, IMAGE_HEIGHT, out)

    @Test
    fun boxOnly_aKeypointFreeWholeBodyBox_givesTheBoxPart() {
        val detection = person(leftShoulderScore = 0f, rightShoulderScore = 0f, hipScore = 0f)

        assertThat(boxOnlyTorso(detection)).isTrue()

        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_BOX)
    }

    @Test
    fun boxOnly_ignoresConfidentKeypoints_andUsesTheBoxPartEvenWhenFourAreConfident() {
        assertThat(torso(person())).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_KEYPOINTS)

        assertThat(boxOnlyTorso(person())).isTrue()

        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_BOX)
        assertThat(rect()).containsExactly(122, 82, 138, 108).inOrder()
    }

    @Test
    fun boxOnly_aBoxThatIsNotAWholeBody_givesNoTorso_evenWithConfidentShoulders() {
        val wide = person(hipScore = 0.1f, boxLeftImg = 90f, boxRightImg = 200f, boxTopImg = 20f, boxBottomImg = 92f)
        assertThat(torso(wide, upperConfig)).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_UPPER_BODY)

        assertThat(boxOnlyTorso(wide, upperConfig)).isFalse()

        assertThat(out.isEmpty).isTrue()
    }

    @Test
    fun boxOnly_theFlagCanBeSwitchedOnAndOff() {
        val region = TorsoRegion(exactConfig)
        assertThat(region.boxOnly).isFalse()

        region.boxOnly = true
        assertThat(region.of(person(), IMAGE_WIDTH, IMAGE_HEIGHT, out)).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_BOX)

        region.boxOnly = false
        assertThat(region.of(person(), IMAGE_WIDTH, IMAGE_HEIGHT, out)).isTrue()
        assertThat(out.source).isEqualTo(TorsoRect.SOURCE_KEYPOINTS)
    }

    private companion object {
        const val IMAGE_WIDTH = 320
        const val IMAGE_HEIGHT = 240
    }
}
