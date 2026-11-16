package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DetectionDedupeTest {
    private val dedupe = DetectionDedupe(TrackerConfig.MAX_DETECTIONS_PER_FRAME, TrackerConfig())
    private val kept = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)

    @Test
    fun samePersonTwice_keepsTheHigherScore() {
        val count = dedupe.dedupe(listOf(box(0f, 0.9f), box(2f, 0.6f)), kept)

        assertThat(keptList(count)).containsExactly(0)
    }

    @Test
    fun samePersonTwice_keepsTheHigherScoreWhenItComesSecond() {
        val count = dedupe.dedupe(listOf(box(0f, 0.6f), box(2f, 0.9f)), kept)

        assertThat(keptList(count)).containsExactly(1)
    }

    @Test
    fun equalScores_keepTheLowerIndex() {
        val count = dedupe.dedupe(listOf(box(0f, 0.7f), box(1f, 0.7f), box(2f, 0.7f)), kept)

        assertThat(keptList(count)).containsExactly(0)
    }

    @Test
    fun twoPeopleSideBySideWithIouAboutPointThree_areBothKept() {
        // Two 100x100 boxes shifted by 54 px: IoU = 46 / 154 = 0.2987.
        val count = dedupe.dedupe(listOf(box(0f, 0.9f), box(54f, 0.8f)), kept)

        assertThat(keptList(count)).containsExactly(0, 1).inOrder()
    }

    @Test
    fun aChain_keepsTheBestAndTheEndThatDoesNotOverlapIt() {
        // Shifts of 17 px give IoU 0.709 between neighbours and 0.49 between the ends: greedy suppression, as in NMS.
        val count = dedupe.dedupe(listOf(box(0f, 0.9f), box(17f, 0.8f), box(34f, 0.7f)), kept)

        assertThat(keptList(count)).containsExactly(0, 2).inOrder()
    }

    @Test
    fun keptIndices_stayInInputOrder() {
        val count = dedupe.dedupe(listOf(box(0f, 0.2f), box(300f, 0.9f), box(600f, 0.5f)), kept)

        assertThat(keptList(count)).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun iouExactlyAtTheThreshold_dropsTheLowerScore() {
        val half = DetectionDedupe(TrackerConfig.MAX_DETECTIONS_PER_FRAME, TrackerConfig(dedupeIou = 0.5f))
        val whole = PoseDetection(0f, 0f, 10f, 10f, 0.9f, FloatArray(KEYPOINT_FLOATS), FloatArray(KEYPOINTS))
        val upperHalf = PoseDetection(0f, 0f, 10f, 5f, 0.8f, FloatArray(KEYPOINT_FLOATS), FloatArray(KEYPOINTS))

        val count = half.dedupe(listOf(whole, upperHalf), kept)

        assertThat(keptList(count)).containsExactly(0)
    }

    @Test
    fun boxesWithoutArea_areNeverDuplicates() {
        val flat = PoseDetection(5f, 5f, 5f, 5f, 0.9f, FloatArray(KEYPOINT_FLOATS), FloatArray(KEYPOINTS))
        val same = PoseDetection(5f, 5f, 5f, 5f, 0.8f, FloatArray(KEYPOINT_FLOATS), FloatArray(KEYPOINTS))

        val count = dedupe.dedupe(listOf(flat, same), kept)

        assertThat(keptList(count)).containsExactly(0, 1).inOrder()
    }

    @Test
    fun emptyInput_keepsNothing() {
        assertThat(dedupe.dedupe(emptyList(), kept)).isEqualTo(0)
    }

    @Test
    fun fullFrameOfTheSameBox_keepsTheBestOne() {
        val detections = List(TrackerConfig.MAX_DETECTIONS_PER_FRAME) { box(it * 0.1f, 0.3f + it * 0.01f) }

        val count = dedupe.dedupe(detections, kept)

        assertThat(keptList(count)).containsExactly(TrackerConfig.MAX_DETECTIONS_PER_FRAME - 1)
    }

    @Test
    fun theSameInstance_givesTheSameAnswerOnTheNextCall() {
        val detections = listOf(box(0f, 0.9f), box(2f, 0.6f), box(300f, 0.5f))

        val first = keptList(dedupe.dedupe(detections, kept))
        val second = keptList(dedupe.dedupe(detections, kept))

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun moreDetectionsThanTheCapacity_isRejected() {
        val tooMany = List(TrackerConfig.MAX_DETECTIONS_PER_FRAME + 1) { box(it * 300f, 0.5f) }

        assertThrows(IllegalArgumentException::class.java) { dedupe.dedupe(tooMany, IntArray(tooMany.size)) }
    }

    @Test
    fun anOutputArrayThatIsTooSmall_isRejected() {
        val two = listOf(box(0f, 0.9f), box(300f, 0.8f))

        assertThrows(IllegalArgumentException::class.java) { dedupe.dedupe(two, IntArray(1)) }
    }

    private fun keptList(count: Int): List<Int> = kept.take(count)

    /** A 100x100 person box whose left edge is [leftImg], with no useful keypoints. */
    private fun box(
        leftImg: Float,
        score: Float,
    ): PoseDetection = PoseDetection(leftImg, 0f, leftImg + BOX_SIDE, BOX_SIDE, score, FloatArray(KEYPOINT_FLOATS), FloatArray(KEYPOINTS))

    private companion object {
        const val BOX_SIDE = 100f
        const val KEYPOINTS = 17
        const val KEYPOINT_FLOATS = 34
    }
}
