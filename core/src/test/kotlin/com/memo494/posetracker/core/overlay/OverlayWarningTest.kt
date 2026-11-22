package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OverlayWarningTest {
    @Test
    fun failClosedWarnings_hideTheDetections() {
        assertThat(OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED.hidesDetections).isTrue()
        assertThat(OverlayWarning.DETECTOR_UNAVAILABLE.hidesDetections).isTrue()
    }

    @Test
    fun theImportedModelNote_doesNotHideTheDetections() {
        assertThat(OverlayWarning.IMPORTED_MODEL_NOT_USED.hidesDetections).isFalse()
    }

    @Test
    fun theBuiltInModelFailedNote_doesNotHideTheDetections() {
        assertThat(OverlayWarning.BUILT_IN_MODEL_FAILED.hidesDetections).isFalse()
    }
}
