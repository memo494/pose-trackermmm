package com.memo494.posetracker.detector

import com.google.common.truth.Truth.assertThat
import com.ultralytics.yolo.ImageUtils
import com.ultralytics.yolo.YOLOUtils
import com.ultralytics.yolo.classColor
import com.ultralytics.yolo.labelTextColor
import com.ultralytics.yolo.widenToFloats
import org.junit.Test

/**
 * Pins the pure helpers of the vendored Ultralytics code (third_party/ULTRALYTICS.md) so that a trim or an
 * upstream sync cannot silently change them. The output-layout detection of `PoseEstimator` (1,300,57 vs the
 * classic features-first layout) lives inside its constructor and needs a loaded model, so it is not covered here.
 */
class VendoredHelpersTest {
    @Test
    fun modelPathWithoutExtensionGetsTfliteSuffix() {
        assertThat(YOLOUtils.ensureTFLiteExtension("yolo26n-pose")).isEqualTo("yolo26n-pose.tflite")
    }

    @Test
    fun modelPathWithTfliteExtensionIsUnchangedIgnoringCase() {
        assertThat(YOLOUtils.ensureTFLiteExtension("m.tflite")).isEqualTo("m.tflite")
        assertThat(YOLOUtils.ensureTFLiteExtension("M.TFLITE")).isEqualTo("M.TFLITE")
    }

    @Test
    fun onlySlashPrefixedPathsAreAbsolute() {
        assertThat(YOLOUtils.isAbsolutePath("/data/m.tflite")).isTrue()
        assertThat(YOLOUtils.isAbsolutePath("assets/m.tflite")).isFalse()
    }

    @Test
    fun widenToFloatsTreatsBytesAsUnsigned() {
        val widened = widenToFloats(byteArrayOf(0, 127, -128, -1))
        assertThat(widened.toList()).containsExactly(0f, 127f, 128f, 255f).inOrder()
    }

    @Test
    fun classColorPrefersModelMetadataColors() {
        assertThat(classColor(1, listOf(0x111111, 0x222222))).isEqualTo(0x222222)
    }

    @Test
    fun classColorFallsBackToOpaquePaletteAndWrapsEveryTwentyClasses() {
        val first = classColor(0, emptyList())
        assertThat(first).isEqualTo(0xFF042AFF.toInt())
        assertThat(classColor(20, emptyList())).isEqualTo(first)
    }

    @Test
    fun labelTextIsWhiteOnDarkAndDarkBlueOnLightBackgrounds() {
        assertThat(labelTextColor(0xFF000000.toInt())).isEqualTo(-1)
        assertThat(labelTextColor(0xFFF3F3F3.toInt())).isEqualTo(0xFF111F68.toInt())
    }

    @Test
    fun letterboxOfWideFrameIntoSquareHasEqualVerticalPad() {
        val t = ImageUtils.letterboxTransform(1280, 720, 640, 640)!!
        assertThat(t.gain).isWithin(1e-6f).of(0.5f)
        assertThat(t.resizedWidth).isEqualTo(640)
        assertThat(t.resizedHeight).isEqualTo(360)
        assertThat(t.padX).isEqualTo(0f)
        assertThat(t.padY).isEqualTo(140f)
        assertThat(t.padBottom).isEqualTo(140f)
    }

    @Test
    fun letterboxOddPadPutsTheExtraPixelAtTheTrailingEdge() {
        val t = ImageUtils.letterboxTransform(640, 479, 640, 640)!!
        assertThat(t.gain).isWithin(1e-6f).of(1f)
        assertThat(t.padY).isEqualTo(80f)
        assertThat(t.padBottom).isEqualTo(81f)
    }

    @Test
    fun letterboxCenterCropScalesToTheLargerAxis() {
        val t = ImageUtils.letterboxTransform(1280, 720, 640, 640, centerCrop = true)!!
        assertThat(t.gain).isWithin(1e-6f).of(640f / 720f)
    }

    @Test
    fun letterboxOfEmptySourceOrTargetFailsClosed() {
        assertThat(ImageUtils.letterboxTransform(0, 720, 640, 640)).isNull()
        assertThat(ImageUtils.letterboxTransform(1280, 720, 640, 0)).isNull()
    }
}
