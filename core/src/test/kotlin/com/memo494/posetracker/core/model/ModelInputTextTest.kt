package com.memo494.posetracker.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModelInputTextTest {
    private val sizes = listOf(0, -1, 1, 32, 288, 384, 416, 640, 832, 960, 1024, 4096)

    private fun written(
        width: Int,
        height: Int,
    ): String {
        val out = CharArray(32)
        val length = ModelInputText.write(width, height, out, 0)
        return String(out, 0, length)
    }

    @Test
    fun text_squareInputIsOneNumber() {
        assertThat(ModelInputText.text(640, 640)).isEqualTo("640")
        assertThat(ModelInputText.text(416, 416)).isEqualTo("416")
    }

    @Test
    fun text_rectangularInputIsWidthXHeight() {
        assertThat(ModelInputText.text(832, 384)).isEqualTo("832x384")
        assertThat(ModelInputText.text(384, 832)).isEqualTo("384x832")
        assertThat(ModelInputText.text(640, 288)).isEqualTo("640x288")
    }

    @Test
    fun text_atTheLimits() {
        assertThat(ModelInputText.text(32, 32)).isEqualTo("32")
        assertThat(ModelInputText.text(4096, 4096)).isEqualTo("4096")
        assertThat(ModelInputText.text(4096, 1024)).isEqualTo("4096x1024")
        assertThat(ModelInputText.text(1024, 4096)).isEqualTo("1024x4096")
    }

    @Test
    fun text_unknownSideIsZero() {
        assertThat(ModelInputText.text(0, 0)).isEqualTo("0")
        assertThat(ModelInputText.text(0, 384)).isEqualTo("0")
        assertThat(ModelInputText.text(832, 0)).isEqualTo("0")
        assertThat(ModelInputText.text(-1, -1)).isEqualTo("0")
        assertThat(ModelInputText.text(832, -5)).isEqualTo("0")
    }

    @Test
    fun write_givesTheSameTextAsTextForEveryPair() {
        for (width in sizes) {
            for (height in sizes) {
                assertThat(written(width, height)).isEqualTo(ModelInputText.text(width, height))
            }
        }
    }

    @Test
    fun write_startsAtTheGivenPositionAndReturnsTheNewOne() {
        val out = "ab-------".toCharArray()

        val end = ModelInputText.write(832, 384, out, 2)

        assertThat(end).isEqualTo(9)
        assertThat(String(out)).isEqualTo("ab832x384")
    }

    @Test
    fun write_aFullBufferCutsTheTextInsteadOfThrowing() {
        val out = CharArray(5)

        val end = ModelInputText.write(832, 384, out, 0)

        assertThat(end).isEqualTo(5)
        assertThat(String(out)).isEqualTo("832x3")
        assertThat(ModelInputText.write(832, 384, out, 5)).isEqualTo(5)
    }
}
