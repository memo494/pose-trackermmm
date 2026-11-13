package com.memo494.posetracker.core.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

class CaptureFrameTest {
    @Test
    fun newFrame_isEmpty() {
        val frame = CaptureFrame()

        assertThat(frame.rgba.size).isEqualTo(0)
        assertThat(frame.widthImg).isEqualTo(0)
        assertThat(frame.heightImg).isEqualTo(0)
    }

    @Test
    fun set_exactSize_isAcceptedAndStoresEveryField() {
        val frame = CaptureFrame()
        val pixels = ByteArray(3 * 2 * 4)

        val accepted = frame.set(pixels, 3, 2, 123_456L, TimestampSource.IMAGE)

        assertThat(accepted).isTrue()
        assertThat(frame.widthImg).isEqualTo(3)
        assertThat(frame.heightImg).isEqualTo(2)
        assertThat(frame.timestampNs).isEqualTo(123_456L)
        assertThat(frame.timestampSource).isEqualTo(TimestampSource.IMAGE)
    }

    @Test
    fun set_neverCopiesTheArray() {
        val frame = CaptureFrame()
        val pixels = ByteArray(2 * 2 * 4)

        frame.set(pixels, 2, 2, 1L, TimestampSource.ARRIVAL)

        assertThat(frame.rgba).isSameInstanceAs(pixels)
    }

    @Test
    fun set_again_replacesThePreviousContent() {
        val frame = CaptureFrame()
        val second = ByteArray(1 * 1 * 4)
        frame.set(ByteArray(2 * 2 * 4), 2, 2, 1L, TimestampSource.IMAGE)

        val accepted = frame.set(second, 1, 1, 2L, TimestampSource.ARRIVAL)

        assertThat(accepted).isTrue()
        assertThat(frame.rgba).isSameInstanceAs(second)
        assertThat(frame.widthImg).isEqualTo(1)
        assertThat(frame.heightImg).isEqualTo(1)
        assertThat(frame.timestampNs).isEqualTo(2L)
        assertThat(frame.timestampSource).isEqualTo(TimestampSource.ARRIVAL)
    }

    @Test
    fun set_arrayOneByteTooShortOrTooLong_isRejected() {
        val frame = CaptureFrame()

        assertThat(frame.set(ByteArray(3 * 2 * 4 - 1), 3, 2, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(3 * 2 * 4 + 1), 3, 2, 1L, TimestampSource.IMAGE)).isFalse()
    }

    @Test
    fun set_nonPositiveSize_isRejected() {
        val frame = CaptureFrame()

        assertThat(frame.set(ByteArray(0), 0, 0, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(0), 0, 2, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(0), 2, 0, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(8), -1, -2, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(8), -2, 1, 1L, TimestampSource.IMAGE)).isFalse()
    }

    @Test
    fun set_sizeThatOverflowsInt_isRejectedNotWrappedToZero() {
        val frame = CaptureFrame()

        // 65536 * 65536 * 4 wraps to 0 in Int arithmetic, which an empty array would wrongly match.
        assertThat(frame.set(ByteArray(0), 65_536, 65_536, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(8), Int.MAX_VALUE, Int.MAX_VALUE, 1L, TimestampSource.IMAGE)).isFalse()
        assertThat(frame.set(ByteArray(8), 1 shl 30, 1, 1L, TimestampSource.IMAGE)).isFalse()
    }

    @Test
    fun rejectedSet_leavesThePreviousContentUntouched() {
        val frame = CaptureFrame()
        val pixels = ByteArray(2 * 2 * 4)
        frame.set(pixels, 2, 2, 77L, TimestampSource.IMAGE)

        val accepted = frame.set(ByteArray(5), 2, 2, 99L, TimestampSource.ARRIVAL)

        assertThat(accepted).isFalse()
        assertThat(frame.rgba).isSameInstanceAs(pixels)
        assertThat(frame.widthImg).isEqualTo(2)
        assertThat(frame.heightImg).isEqualTo(2)
        assertThat(frame.timestampNs).isEqualTo(77L)
        assertThat(frame.timestampSource).isEqualTo(TimestampSource.IMAGE)
    }
}
