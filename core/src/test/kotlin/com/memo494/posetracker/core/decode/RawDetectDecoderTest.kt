package com.memo494.posetracker.core.decode

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.PoseOutputLayout
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class RawDetectDecoderTest {
    /** One raw row: centre box then one score per class. */
    private class Row(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val scores: FloatArray,
    )

    private val rawLayouts = listOf(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, PoseOutputLayout.DETECT_RAW_CHANNELS_LAST)

    private fun flat(
        layout: PoseOutputLayout,
        rows: List<Row>,
        classes: Int,
    ): FloatArray {
        val channels = TrackerConfig.POSE_BOX_VALUES + classes
        val out = FloatArray(channels * rows.size)
        for ((r, row) in rows.withIndex()) {
            val values = floatArrayOf(row.cx, row.cy, row.w, row.h) + row.scores
            for (c in 0 until channels) {
                val index = if (layout == PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST) c * rows.size + r else r * channels + c
                out[index] = values[c]
            }
        }
        return out
    }

    private fun decoder(
        layout: PoseOutputLayout,
        rows: Int,
        classes: Int = 2,
        personClass: Int = 0,
        width: Int = 640,
        height: Int = 640,
        config: TrackerConfig = TrackerConfig(),
    ) = RawDetectDecoder(layout, rows, classes, personClass, width, height, config)

    private fun person(
        cx: Float,
        cy: Float,
        score: Float,
        size: Float = 100f,
        other: Float = 0f,
    ) = Row(cx, cy, size, size, floatArrayOf(score, other))

    /** End-to-end rows `x1, y1, x2, y2, score, class`, flat (the layout has one row per candidate). */
    private fun e2e(vararg rows: FloatArray): FloatArray = rows.fold(FloatArray(0)) { acc, row -> acc + row }

    private fun e2eRow(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        score: Float,
        cls: Float = 0f,
    ) = floatArrayOf(x1, y1, x2, y2, score, cls)

    @Test
    fun bothAxisOrders_giveIdenticalDetections() {
        val rows =
            listOf(person(100f, 100f, 0.9f), person(105f, 102f, 0.8f), person(400f, 300f, 0.6f, size = 150f), person(500f, 500f, 0.1f))
        val first = decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, rows.size)
        val last = decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_LAST, rows.size)

        val a = first.decode(flat(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, rows, 2), 0.25f)
        val b = last.decode(flat(PoseOutputLayout.DETECT_RAW_CHANNELS_LAST, rows, 2), 0.25f)

        assertThat(a).isEqualTo(2)
        assertThat(b).isEqualTo(a)
        for (i in 0 until a) {
            assertThat(last.scoreOf(i)).isEqualTo(first.scoreOf(i))
            assertThat(last.boxLeftMdl(i)).isEqualTo(first.boxLeftMdl(i))
            assertThat(last.boxTopMdl(i)).isEqualTo(first.boxTopMdl(i))
            assertThat(last.boxRightMdl(i)).isEqualTo(first.boxRightMdl(i))
            assertThat(last.boxBottomMdl(i)).isEqualTo(first.boxBottomMdl(i))
        }
    }

    @Test
    fun pixelBoxes_matchTheSyntheticTruth() {
        for (layout in rawLayouts) {
            val d = decoder(layout, 1)

            val count = d.decode(flat(layout, listOf(Row(200f, 150f, 60f, 120f, floatArrayOf(0.8f, 0f))), 2), 0.25f)

            assertThat(count).isEqualTo(1)
            assertThat(d.scoreOf(0)).isEqualTo(0.8f)
            assertThat(d.boxLeftMdl(0)).isWithin(1f).of(170f)
            assertThat(d.boxTopMdl(0)).isWithin(1f).of(90f)
            assertThat(d.boxRightMdl(0)).isWithin(1f).of(230f)
            assertThat(d.boxBottomMdl(0)).isWithin(1f).of(210f)
        }
    }

    @Test
    fun normalizedBoxes_areScaledToTheInputWidthAndHeight() {
        for (layout in rawLayouts) {
            val d = decoder(layout, 2, width = 320, height = 192)
            val rows = listOf(Row(0.5f, 0.5f, 0.25f, 0.5f, floatArrayOf(0.9f, 0f)), Row(0.2f, 0.2f, 0.1f, 0.1f, floatArrayOf(0.7f, 0f)))

            val count = d.decode(flat(layout, rows, 2), 0.25f)

            assertThat(count).isEqualTo(2)
            assertThat(d.boxLeftMdl(0)).isWithin(1f).of(0.375f * 320f)
            assertThat(d.boxRightMdl(0)).isWithin(1f).of(0.625f * 320f)
            assertThat(d.boxTopMdl(0)).isWithin(1f).of(0.25f * 192f)
            assertThat(d.boxBottomMdl(0)).isWithin(1f).of(0.75f * 192f)
        }
    }

    @Test
    fun aPixelRowInANormalizedFrame_isDroppedAsAnOutlier() {
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
        val rows = listOf(person(0.3f, 0.3f, 0.9f, size = 0.2f), person(0.7f, 0.7f, 0.8f, size = 0.2f), person(300f, 300f, 0.95f))
        val d = decoder(layout, rows.size)

        val count = d.decode(flat(layout, rows, 2), 0.25f)

        assertThat(count).isEqualTo(2)
        assertThat(d.scoreOf(0)).isEqualTo(0.9f)
    }

    @Test
    fun aRowOfANonPersonClass_isIgnored() {
        for (layout in rawLayouts) {
            val rows = listOf(person(100f, 100f, 0f, other = 0.95f), person(400f, 400f, 0.6f, other = 0.1f))
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows, 2), 0.25f)

            assertThat(count).isEqualTo(1)
            assertThat(d.scoreOf(0)).isEqualTo(0.6f)
            assertThat(d.boxLeftMdl(0)).isEqualTo(350f)
        }
    }

    @Test
    fun aPersonClassAboveZero_readsItsOwnScoreColumn() {
        for (layout in rawLayouts) {
            val rows =
                listOf(
                    Row(100f, 100f, 80f, 80f, floatArrayOf(0.9f, 0f, 0f)),
                    Row(300f, 300f, 80f, 80f, floatArrayOf(0f, 0.7f, 0.99f)),
                    Row(500f, 100f, 80f, 80f, floatArrayOf(0f, 0f, 0.8f)),
                )
            val d = decoder(layout, rows.size, classes = 3, personClass = 1)

            val count = d.decode(flat(layout, rows, 3), 0.25f)

            assertThat(count).isEqualTo(1)
            assertThat(d.scoreOf(0)).isEqualTo(0.7f)
            assertThat(d.boxLeftMdl(0)).isEqualTo(260f)
        }
    }

    @Test
    fun eightyClasses_personIsColumnZero() {
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_LAST
        val scores =
            FloatArray(80).also {
                it[0] = 0.6f
                it[17] = 0.99f
            }
        val d = decoder(layout, 1, classes = 80)

        val count = d.decode(flat(layout, listOf(Row(320f, 320f, 100f, 200f, scores)), 80), 0.25f)

        assertThat(count).isEqualTo(1)
        assertThat(d.scoreOf(0)).isEqualTo(0.6f)
    }

    @Test
    fun scoreThreshold_dropsLowRows_keepsEqual_andNeverPassesNaN() {
        for (layout in rawLayouts) {
            val rows =
                listOf(
                    person(100f, 100f, 0.25f),
                    person(300f, 100f, 0.2499f),
                    person(500f, 100f, 0.9f),
                    person(100f, 400f, Float.NaN),
                )
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows, 2), 0.25f)

            assertThat(count).isEqualTo(2)
            assertThat(d.scoreOf(0)).isEqualTo(0.9f)
            assertThat(d.scoreOf(1)).isEqualTo(0.25f)
        }
    }

    @Test
    fun overlappingDuplicates_areSuppressed_bestKept() {
        for (layout in rawLayouts) {
            val rows = listOf(person(100f, 100f, 0.6f), person(104f, 100f, 0.9f), person(98f, 103f, 0.7f), person(400f, 400f, 0.5f))
            val d = decoder(layout, rows.size)

            val count = d.decode(flat(layout, rows, 2), 0.25f)

            assertThat(count).isEqualTo(2)
            assertThat(d.scoreOf(0)).isEqualTo(0.9f)
            assertThat(d.boxLeftMdl(0)).isEqualTo(54f)
            assertThat(d.scoreOf(1)).isEqualTo(0.5f)
        }
    }

    @Test
    fun theNmsIouIsTheConfiguredOne() {
        // Two 100x100 boxes shifted by 50 px in x: IoU = 1/3.
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
        val rows = listOf(person(100f, 100f, 0.9f), person(150f, 100f, 0.8f))
        val loose = decoder(layout, 2, config = TrackerConfig(rawNmsIou = 0.5f))
        val strict = decoder(layout, 2, config = TrackerConfig(rawNmsIou = TrackerConfig.MIN_RAW_NMS_IOU))

        assertThat(loose.decode(flat(layout, rows, 2), 0.25f)).isEqualTo(2)
        assertThat(strict.decode(flat(layout, rows, 2), 0.25f)).isEqualTo(1)
    }

    @Test
    fun invalidBoxes_areDropped() {
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
        val rows =
            listOf(
                Row(100f, 100f, 0f, 50f, floatArrayOf(0.9f, 0f)),
                Row(200f, 100f, -5f, 50f, floatArrayOf(0.9f, 0f)),
                Row(Float.NaN, 100f, 50f, 50f, floatArrayOf(0.9f, 0f)),
                Row(300f, 100f, Float.POSITIVE_INFINITY, 50f, floatArrayOf(0.9f, 0f)),
                person(500f, 500f, 0.5f),
            )
        val d = decoder(layout, rows.size)

        assertThat(d.decode(flat(layout, rows, 2), 0.25f)).isEqualTo(1)
    }

    @Test
    fun atMostTheRawCapIsReturned() {
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_LAST
        val rows = List(400) { i -> person(15f + (i % 20) * 30f, 15f + (i / 20) * 30f, 0.5f + i * 0.001f, size = 10f) }
        val d = decoder(layout, rows.size)

        val count = d.decode(flat(layout, rows, 2), 0.25f)

        assertThat(count).isEqualTo(TrackerConfig.RAW_MAX_DETECTIONS)
        assertThat(d.scoreOf(0)).isEqualTo(0.5f + 399 * 0.001f)
    }

    @Test
    fun theOwnersShape_2100RowsAndTwoClasses_decodesOnePerson() {
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
        val rows = MutableList(2100) { Row(0.5f, 0.5f, 0.1f, 0.1f, floatArrayOf(0.01f, 0.02f)) }
        rows[1234] = Row(0.4f, 0.5f, 0.2f, 0.6f, floatArrayOf(0.88f, 0.1f))
        val d = decoder(layout, 2100, width = 320, height = 320)

        val count = d.decode(flat(layout, rows, 2), 0.15f)

        assertThat(count).isEqualTo(1)
        assertThat(d.boxLeftMdl(0)).isWithin(1f).of(0.3f * 320f)
        assertThat(d.boxBottomMdl(0)).isWithin(1f).of(0.8f * 320f)
    }

    @Test
    fun resultsAreReplacedByTheNextFrame() {
        val layout = PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
        val d = decoder(layout, 2)

        assertThat(d.decode(flat(layout, listOf(person(100f, 100f, 0.9f), person(400f, 400f, 0.8f)), 2), 0.25f)).isEqualTo(2)
        assertThat(d.decode(flat(layout, listOf(person(100f, 100f, 0.1f), person(400f, 400f, 0.1f)), 2), 0.25f)).isEqualTo(0)
        assertThat(d.count).isEqualTo(0)
    }

    @Test
    fun endToEnd_keepsOnlyThePersonClass() {
        val d = decoder(PoseOutputLayout.DETECT_END_TO_END, 3, classes = 0)
        val flat =
            e2e(
                e2eRow(10f, 20f, 110f, 220f, 0.9f, cls = 0f),
                e2eRow(300f, 300f, 400f, 450f, 0.95f, cls = 1f),
                e2eRow(500f, 100f, 560f, 200f, 0.7f, cls = 0f),
            )

        val count = d.decode(flat, 0.25f)

        assertThat(count).isEqualTo(2)
        assertThat(d.scoreOf(0)).isEqualTo(0.9f)
        assertThat(d.boxLeftMdl(0)).isEqualTo(10f)
        assertThat(d.boxTopMdl(0)).isEqualTo(20f)
        assertThat(d.boxRightMdl(0)).isEqualTo(110f)
        assertThat(d.boxBottomMdl(0)).isEqualTo(220f)
        assertThat(d.scoreOf(1)).isEqualTo(0.7f)
    }

    @Test
    fun endToEnd_personClassAboveZero_picksThatClassValue() {
        val d = decoder(PoseOutputLayout.DETECT_END_TO_END, 3, classes = 0, personClass = 2)
        val flat =
            e2e(
                e2eRow(10f, 20f, 110f, 220f, 0.9f, cls = 0f),
                e2eRow(300f, 300f, 400f, 450f, 0.8f, cls = 2f),
                e2eRow(500f, 100f, 560f, 200f, 0.7f, cls = 1f),
            )

        val count = d.decode(flat, 0.25f)

        assertThat(count).isEqualTo(1)
        assertThat(d.boxLeftMdl(0)).isEqualTo(300f)
    }

    @Test
    fun endToEnd_normalizedCoordinates_areScaledAndThresholdApplies() {
        val d = decoder(PoseOutputLayout.DETECT_END_TO_END, 3, classes = 0, width = 320, height = 192)
        val flat =
            e2e(
                e2eRow(0.25f, 0.25f, 0.5f, 0.75f, 0.9f),
                e2eRow(0.6f, 0.1f, 0.9f, 0.4f, 0.1f),
                e2eRow(0f, 0f, 0f, 0f, 0.9f),
            )

        val count = d.decode(flat, 0.25f)

        assertThat(count).isEqualTo(1)
        assertThat(d.boxLeftMdl(0)).isWithin(1f).of(80f)
        assertThat(d.boxTopMdl(0)).isWithin(1f).of(48f)
        assertThat(d.boxRightMdl(0)).isWithin(1f).of(160f)
        assertThat(d.boxBottomMdl(0)).isWithin(1f).of(144f)
    }

    @Test
    fun endToEnd_duplicatesAreSuppressedToo_andBadBoxesAreDropped() {
        val d = decoder(PoseOutputLayout.DETECT_END_TO_END, 4, classes = 0)
        val flat =
            e2e(
                e2eRow(10f, 10f, 110f, 110f, 0.6f),
                e2eRow(12f, 10f, 112f, 110f, 0.9f),
                e2eRow(200f, 200f, 100f, 300f, 0.9f),
                e2eRow(Float.NaN, 0f, 10f, 10f, 0.9f),
            )

        val count = d.decode(flat, 0.25f)

        assertThat(count).isEqualTo(1)
        assertThat(d.scoreOf(0)).isEqualTo(0.9f)
        assertThat(d.boxLeftMdl(0)).isEqualTo(12f)
    }

    @Test
    fun endToEnd_aNaNClassIsNotAPerson() {
        val d = decoder(PoseOutputLayout.DETECT_END_TO_END, 1, classes = 0)

        assertThat(d.decode(e2eRow(10f, 10f, 110f, 110f, 0.9f, cls = Float.NaN), 0.25f)).isEqualTo(0)
    }

    @Test
    fun constructor_refusesAPersonClassOutsideTheClasses() {
        for (layout in rawLayouts) {
            assertThrows(IllegalArgumentException::class.java) { decoder(layout, 10, classes = 2, personClass = 2) }
            assertThrows(IllegalArgumentException::class.java) { decoder(layout, 10, classes = 1, personClass = 1) }
        }
    }

    @Test
    fun constructor_refusesBadArguments() {
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.RAW_CHANNELS_FIRST, 10) }
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.END_TO_END, 10) }
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, 0) }
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, 10, classes = 0) }
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, 10, classes = 81) }
        assertThrows(IllegalArgumentException::class.java) { decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, 10, width = 0) }
    }

    @Test
    fun decode_refusesAFlatArrayOfTheWrongSize() {
        val d = decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, 10)
        assertThrows(IllegalArgumentException::class.java) { d.decode(FloatArray(10 * 5), 0.25f) }
        val e = decoder(PoseOutputLayout.DETECT_END_TO_END, 10, classes = 0)
        assertThrows(IllegalArgumentException::class.java) { e.decode(FloatArray(10 * 5), 0.25f) }
    }

    @Test
    fun accessors_refuseAnIndexOutsideTheResult() {
        val d = decoder(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, 1)
        d.decode(flat(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, listOf(person(100f, 100f, 0.9f)), 2), 0.25f)

        assertThrows(IllegalArgumentException::class.java) { d.scoreOf(1) }
        assertThrows(IllegalArgumentException::class.java) { d.boxLeftMdl(-1) }
    }

    @Test
    fun decode_allocatesNothingPerFrame() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val measurable = threads != null && threads.isThreadAllocatedMemorySupported
        if (!measurable) return
        val layouts = listOf(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, PoseOutputLayout.DETECT_RAW_CHANNELS_LAST)
        val rows = List(2100) { i -> person((i % 40) * 16f + 8f, (i / 40) % 40 * 16f + 8f, if (i % 7 == 0) 0.8f else 0.1f, size = 40f) }
        for (layout in layouts) {
            val input = flat(layout, rows, 2)
            val d = decoder(layout, rows.size)
            repeat(WARM_UP_FRAMES) { d.decode(input, 0.25f) }
            val id = Thread.currentThread().id
            val baseline = threads!!.getThreadAllocatedBytes(id).let { threads.getThreadAllocatedBytes(id) - it }

            val before = threads.getThreadAllocatedBytes(id)
            repeat(MEASURED_FRAMES) { d.decode(input, 0.25f) }
            val allocated = threads.getThreadAllocatedBytes(id) - before - baseline

            assertThat(allocated).isAtMost(0L)
        }
    }

    private companion object {
        const val WARM_UP_FRAMES = 300
        const val MEASURED_FRAMES = 50
    }
}
