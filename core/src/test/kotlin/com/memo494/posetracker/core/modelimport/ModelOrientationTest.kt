package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModelOrientationTest {
    @Test
    fun widerThanHigh_isLandscape() {
        assertThat(ModelOrientation.of(2712, 1220)).isEqualTo(ModelOrientation.LANDSCAPE)
        assertThat(ModelOrientation.of(2, 1)).isEqualTo(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun higherThanWide_isPortrait() {
        assertThat(ModelOrientation.of(610, 1356)).isEqualTo(ModelOrientation.PORTRAIT)
    }

    @Test
    fun square_isPortrait() {
        assertThat(ModelOrientation.of(500, 500)).isEqualTo(ModelOrientation.PORTRAIT)
    }

    @Test
    fun unknownSize_isPortrait() {
        assertThat(ModelOrientation.of(0, 0)).isEqualTo(ModelOrientation.PORTRAIT)
    }

    @Test
    fun logNames_areTheWordsOfTheLogLine() {
        assertThat(ModelOrientation.PORTRAIT.logName).isEqualTo("portrait")
        assertThat(ModelOrientation.LANDSCAPE.logName).isEqualTo("landscape")
    }
}
