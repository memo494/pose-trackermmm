package com.memo494.posetracker.detector

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import com.ultralytics.yolo.Box
import com.ultralytics.yolo.Keypoints
import com.ultralytics.yolo.Size
import com.ultralytics.yolo.YOLOResult
import org.junit.Assert.assertThrows
import org.junit.Test

class YoloResultMapperTest {
    // Android constructors are stubs on the JVM (isReturnDefaultValues), so the fields are set by hand.
    private fun rect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private fun box(
        conf: Float,
        ltrb: RectF,
    ): Box = Box(index = 0, cls = "person", conf = conf, xywh = ltrb, xywhn = RectF())

    private fun keypoints(
        xy: List<Pair<Float, Float>>,
        conf: List<Float>,
    ): Keypoints = Keypoints(xyn = xy, xy = xy, conf = conf)

    private fun result(
        boxes: List<Box>,
        keypointsList: List<Keypoints>,
    ): YOLOResult =
        YOLOResult(
            origShape = Size(ORIG_WIDTH, ORIG_HEIGHT),
            boxes = boxes,
            keypointsList = keypointsList,
            speed = 0.0,
            names = listOf("person"),
        )

    @Test
    fun noPeopleGiveNoDetections() {
        val detections = YoloResultMapper.toDetections(result(emptyList(), emptyList()))

        assertThat(detections).isEmpty()
    }

    @Test
    fun boxLeftTopRightBottomAndScoreAreMapped() {
        val boxes = listOf(box(0.9f, rect(10f, 20f, 110f, 220f)))
        val keypointsList = listOf(keypoints(listOf(1f to 2f), listOf(0.5f)))

        val detection = YoloResultMapper.toDetections(result(boxes, keypointsList)).single()

        assertThat(detection.boxLeftImg).isEqualTo(10f)
        assertThat(detection.boxTopImg).isEqualTo(20f)
        assertThat(detection.boxRightImg).isEqualTo(110f)
        assertThat(detection.boxBottomImg).isEqualTo(220f)
        assertThat(detection.score).isEqualTo(0.9f)
    }

    @Test
    fun keypointsAreInterleavedXyWithTheirScores() {
        val boxes = listOf(box(0.8f, rect(0f, 0f, 10f, 10f)))
        val keypointsList = listOf(keypoints(listOf(1f to 2f, 3f to 4f, 5f to 6f), listOf(0.1f, 0.2f, 0.3f)))

        val detection = YoloResultMapper.toDetections(result(boxes, keypointsList)).single()

        assertThat(detection.keypointsXyImg.toList()).containsExactly(1f, 2f, 3f, 4f, 5f, 6f).inOrder()
        assertThat(detection.keypointScores.toList()).containsExactly(0.1f, 0.2f, 0.3f).inOrder()
        assertThat(detection.keypointCount).isEqualTo(3)
    }

    @Test
    fun boxIsPairedWithTheKeypointsAtTheSameIndex() {
        val boxes = listOf(box(0.9f, rect(0f, 0f, 1f, 1f)), box(0.7f, rect(5f, 5f, 6f, 6f)))
        val keypointsList =
            listOf(
                keypoints(listOf(1f to 1f), listOf(0.4f)),
                keypoints(listOf(9f to 9f), listOf(0.6f)),
            )

        val detections = YoloResultMapper.toDetections(result(boxes, keypointsList))

        assertThat(detections.map { it.score }).containsExactly(0.9f, 0.7f).inOrder()
        assertThat(detections[0].keypointXImg(0)).isEqualTo(1f)
        assertThat(detections[1].keypointXImg(0)).isEqualTo(9f)
        assertThat(detections[1].keypointScores[0]).isEqualTo(0.6f)
    }

    @Test
    fun differentBoxAndKeypointListSizesAreRejected() {
        val boxes = listOf(box(0.9f, rect(0f, 0f, 1f, 1f)), box(0.7f, rect(5f, 5f, 6f, 6f)))
        val keypointsList = listOf(keypoints(listOf(1f to 1f), listOf(0.4f)))

        assertThrows(IllegalStateException::class.java) {
            YoloResultMapper.toDetections(result(boxes, keypointsList))
        }
    }

    @Test
    fun keypointListWithDifferentXyAndConfSizesIsRejected() {
        val boxes = listOf(box(0.9f, rect(0f, 0f, 1f, 1f)))
        val keypointsList = listOf(keypoints(listOf(1f to 1f, 2f to 2f), listOf(0.4f)))

        assertThrows(IllegalStateException::class.java) {
            YoloResultMapper.toDetections(result(boxes, keypointsList))
        }
    }

    private companion object {
        const val ORIG_WIDTH = 640
        const val ORIG_HEIGHT = 480
    }
}
