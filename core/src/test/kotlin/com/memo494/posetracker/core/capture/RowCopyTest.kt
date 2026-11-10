package com.memo494.posetracker.core.capture

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class RowCopyTest {
    // 3x2 RGBA image, rowStride 16 (12 pixel bytes + 4 padding bytes per row).
    private val width = 3
    private val height = 2
    private val rowStride = 16
    private val pixelStride = 4

    private fun paddedSource(totalBytes: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(totalBytes)
        for (i in 0 until totalBytes) buffer.put(i, (i + 1).toByte())
        return buffer
    }

    // Pixel bytes of both rows; the 4 padding bytes (13..16) of the first row are skipped.
    private val expected = ((1..12) + (17..28)).map { it.toByte() }.toByteArray()

    @Test
    fun paddedStride_isRemovedFromEveryRow() {
        val destination = ByteArray(width * height * pixelStride)

        val source = paddedSource(rowStride * height)

        val ok = RowCopy.copyRgba(source, width, height, rowStride, pixelStride, destination)

        assertThat(ok).isTrue()
        assertThat(destination).isEqualTo(expected)
    }

    @Test
    fun shortLastRow_isCopiedWithoutTheTrailingPadding() {
        // Android only guarantees rowStride * (height - 1) + width * pixelStride bytes.
        val destination = ByteArray(width * height * pixelStride)

        val source = paddedSource(rowStride + width * pixelStride)

        val ok = RowCopy.copyRgba(source, width, height, rowStride, pixelStride, destination)

        assertThat(ok).isTrue()
        assertThat(destination).isEqualTo(expected)
    }

    @Test
    fun lastRowShorterThanThePixels_failsClosedAndLeavesTheDestinationUntouched() {
        val destination = ByteArray(width * height * pixelStride) { 99 }

        val source = paddedSource(rowStride + width * pixelStride - 1)

        val ok = RowCopy.copyRgba(source, width, height, rowStride, pixelStride, destination)

        assertThat(ok).isFalse()
        assertThat(destination.all { it == 99.toByte() }).isTrue()
    }

    @Test
    fun tightStride_copiesTheWholeBuffer() {
        val tightRow = width * pixelStride
        val destination = ByteArray(width * height * pixelStride)

        val ok = RowCopy.copyRgba(paddedSource(tightRow * height), width, height, tightRow, pixelStride, destination)

        assertThat(ok).isTrue()
        assertThat(destination.toList()).isEqualTo((1..tightRow * height).map { it.toByte() })
    }

    @Test
    fun sourcePosition_isRestoredAndUsedAsTheStart() {
        val source = paddedSource(rowStride * height + 8)
        source.position(8)
        val destination = ByteArray(width * height * pixelStride)

        val ok = RowCopy.copyRgba(source, width, height, rowStride, pixelStride, destination)

        assertThat(ok).isTrue()
        assertThat(source.position()).isEqualTo(8)
        assertThat(destination[0]).isEqualTo(9.toByte())
        assertThat(destination[width * pixelStride]).isEqualTo((8 + rowStride + 1).toByte())
    }

    @Test
    fun strideSmallerThanARow_isRejected() {
        val destination = ByteArray(width * height * pixelStride)

        val ok = RowCopy.copyRgba(paddedSource(64), width, height, width * pixelStride - 1, pixelStride, destination)

        assertThat(ok).isFalse()
    }

    @Test
    fun nonRgbaPixelStride_isRejected() {
        val destination = ByteArray(width * height * pixelStride)

        val ok = RowCopy.copyRgba(paddedSource(64), width, height, rowStride, 2, destination)

        assertThat(ok).isFalse()
    }

    @Test
    fun destinationOfTheWrongSize_isRejected() {
        val destination = ByteArray(width * height * pixelStride + 1)

        val ok = RowCopy.copyRgba(paddedSource(64), width, height, rowStride, pixelStride, destination)

        assertThat(ok).isFalse()
    }

    @Test
    fun ensureBuffer_reusesAnExactSizeBuffer() {
        val first = RowCopy.ensureBuffer(null, 610, 1356)

        val second = RowCopy.ensureBuffer(first, 610, 1356)

        assertThat(first.size).isEqualTo(610 * 1356 * 4)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun ensureBuffer_reallocatesOnlyWhenTheSizeChanges() {
        val portrait = RowCopy.ensureBuffer(null, 610, 1356)

        val landscape = RowCopy.ensureBuffer(portrait, 1356, 610)

        assertThat(landscape.size).isEqualTo(1356 * 610 * 4)
        assertThat(RowCopy.ensureBuffer(landscape, 1356, 610)).isSameInstanceAs(landscape)
    }
}
