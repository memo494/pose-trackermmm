package com.memo494.posetracker.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.tracking.TrackSnapshot
import org.junit.Test

class OverlayStateTest {
    private fun stateWith(
        trackWidthImg: Int,
        trackHeightImg: Int,
    ) = OverlayState(
        captureWidthImg = WIDTH_IMG,
        captureHeightImg = HEIGHT_IMG,
        tracks = TrackSnapshot.of(FakeTracks(1), trackWidthImg, trackHeightImg),
    )

    @Test
    fun tracksMatchCapture_whenTheSnapshotHasTheCaptureSize() {
        assertThat(stateWith(WIDTH_IMG, HEIGHT_IMG).tracksMatchCapture).isTrue()
    }

    @Test
    fun tracksMatchCapture_isFalseForARotatedSnapshot() {
        assertThat(stateWith(HEIGHT_IMG, WIDTH_IMG).tracksMatchCapture).isFalse()
    }

    @Test
    fun tracksMatchCapture_isFalseWhenOnlyOneSideDiffers() {
        assertThat(stateWith(WIDTH_IMG + 2, HEIGHT_IMG).tracksMatchCapture).isFalse()
        assertThat(stateWith(WIDTH_IMG, HEIGHT_IMG + 2).tracksMatchCapture).isFalse()
    }

    @Test
    fun tracksMatchCapture_isFalseForTheEmptySnapshotOfAKnownCaptureSize() {
        val state = OverlayState(captureWidthImg = WIDTH_IMG, captureHeightImg = HEIGHT_IMG)

        assertThat(state.tracks.count).isEqualTo(0)
        assertThat(state.tracksMatchCapture).isFalse()
    }

    private companion object {
        const val WIDTH_IMG = 320
        const val HEIGHT_IMG = 240
    }
}
