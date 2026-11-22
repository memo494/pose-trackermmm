package com.memo494.posetracker.core.modelpack

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class ModelInfoTest {
    private val goodSha = "ab".repeat(32)
    private val goodTensors = """[{"name":"t","shape":[1,3,416,416],"dtype":"float32"}]"""
    private val goodOutput = """[{"name":"o","shape":[1,300,57],"dtype":"float32"}]"""

    /** A one-size document; every argument can be replaced to build a broken variant. */
    private fun document(
        key: String = "416",
        file: String = "m_416.tflite",
        sha256: String = goodSha,
        input: String = goodTensors,
        output: String = goodOutput,
    ): String = """{"sizes":{"$key":{"file":"$file","sha256":"$sha256","input":$input,"output":$output}}}"""

    private fun assertRejected(text: String) {
        assertThrows(IllegalArgumentException::class.java) { ModelInfo.parse(text) }
    }

    private fun inputOf(shape: String): String = """[{"name":"t","shape":$shape}]"""

    private fun outputOf(shape: String): String = """[{"name":"o","shape":$shape}]"""

    @Test
    fun parse_committedModelInfo_listsTheSquareAndTheRectangularModels() {
        val info = ModelInfo.parse(File("../model/${ModelInfo.ASSET_NAME}").readText())

        assertThat(info.entries.map { it.inputWidth to it.inputHeight })
            .containsExactly(320 to 320, 416 to 416, 448 to 960, 960 to 448)
            .inOrder()
        for (size in listOf(320, 416)) {
            val entry = info.entryFor(size)
            assertThat(entry.fileName).isEqualTo("yolo26n-pose_$size.tflite")
            assertThat(entry.inputShape).containsExactly(1, 3, size, size).inOrder()
            assertThat(entry.outputShape).containsExactly(1, 300, 57).inOrder()
            assertThat(entry.sha256).hasLength(64)
        }
    }

    @Test
    fun parse_committedModelInfo_rectangularEntriesAreNchwWithHeightBeforeWidth() {
        val info = ModelInfo.parse(File("../model/${ModelInfo.ASSET_NAME}").readText())

        val portrait = info.entryFor(448, 960)
        assertThat(portrait.fileName).isEqualTo("yolo26n-pose_448x960.tflite")
        assertThat(portrait.sha256).isEqualTo("35b62098e36ae7aaec59cc4f36ef88bb4ab00482a393abd6251c7f5f3781dd45")
        assertThat(portrait.inputShape).containsExactly(1, 3, 960, 448).inOrder()
        assertThat(portrait.outputShape).containsExactly(1, 300, 57).inOrder()
        val landscape = info.entryFor(960, 448)
        assertThat(landscape.fileName).isEqualTo("yolo26n-pose_960x448.tflite")
        assertThat(landscape.sha256).isEqualTo("e8e149af1314494bf633b30d76642ef281d1106bc629ded4368446a65a4c73ad")
        assertThat(landscape.inputShape).containsExactly(1, 3, 448, 960).inOrder()
        assertThat(landscape.outputShape).containsExactly(1, 300, 57).inOrder()
    }

    @Test
    fun parse_rectangularKey_givesWidthAndHeightFromTheKey() {
        val text = document(key = "448x960", file = "m.tflite", input = inputOf("[1,3,960,448]"))

        val entry = ModelInfo.parse(text).entryFor(448, 960)

        assertThat(entry.inputWidth).isEqualTo(448)
        assertThat(entry.inputHeight).isEqualTo(960)
    }

    @Test
    fun parse_rectangularKeyWithTheTransposedShape_isRejected() {
        assertRejected(document(key = "448x960", input = inputOf("[1,3,448,960]")))
        assertRejected(document(key = "448x960", input = inputOf("[1,3,448,448]")))
        assertRejected(document(key = "448x960", input = inputOf("[1,3,960,960]")))
    }

    @Test
    fun parse_keysThatAreNotAPositiveIntegerOrCanonicalWidthXHeight_areRejected() {
        for (key in listOf("416x416", "448X960", "448x", "x960", "0x960", "448x0", "-448x960", "0448x960", "448x960x1", "448 x960", "448xabc")) {
            assertRejected(document(key = key, input = inputOf("[1,3,960,448]")))
        }
    }

    @Test
    fun parse_aSquareAndARectangularModelWithTheSameSides_areDifferentEntries() {
        val text =
            """
            {"sizes":{
              "448x960":{"file":"m_p.tflite","sha256":"$goodSha","input":${inputOf("[1,3,960,448]")},"output":$goodOutput},
              "960x448":{"file":"m_l.tflite","sha256":"$goodSha","input":${inputOf("[1,3,448,960]")},"output":$goodOutput}}}
            """.trimIndent()

        val info = ModelInfo.parse(text)

        assertThat(info.entryFor(448, 960).fileName).isEqualTo("m_p.tflite")
        assertThat(info.entryFor(960, 448).fileName).isEqualTo("m_l.tflite")
    }

    @Test
    fun assetName_isModelInfoJson() {
        assertThat(ModelInfo.ASSET_NAME).isEqualTo("model_info.json")
    }

    @Test
    fun parse_validDocument_givesTheEntryFields() {
        val entry = ModelInfo.parse(document()).entryFor(416)

        assertThat(entry.inputWidth).isEqualTo(416)
        assertThat(entry.inputHeight).isEqualTo(416)
        assertThat(entry.fileName).isEqualTo("m_416.tflite")
        assertThat(entry.sha256).isEqualTo(goodSha)
        assertThat(entry.inputShape).containsExactly(1, 3, 416, 416).inOrder()
        assertThat(entry.outputShape).containsExactly(1, 300, 57).inOrder()
    }

    @Test
    fun parse_sizesInAnyOrder_areListedAscending() {
        val text =
            """
            {"sizes":{
              "416":{"file":"m_416.tflite","sha256":"$goodSha","input":$goodTensors,"output":$goodOutput},
              "320":{"file":"m_320.tflite","sha256":"$goodSha",
                     "input":${inputOf("[1,3,320,320]")},"output":$goodOutput}}}
            """.trimIndent()

        val info = ModelInfo.parse(text)

        assertThat(info.entries.map { it.inputWidth }).containsExactly(320, 416).inOrder()
    }

    @Test
    fun entryFor_unknownSize_failsNamingTheKnownSizes() {
        val info = ModelInfo.parse(document())

        val error = assertThrows(IllegalArgumentException::class.java) { info.entryFor(512) }

        assertThat(error.message).contains("512")
        assertThat(error.message).contains("416")
    }

    @Test
    fun parse_missingOrEmptySizes_isRejected() {
        assertRejected("""{}""")
        assertRejected("""{"sizes":{}}""")
        assertRejected("""{"sizes":[]}""")
        assertRejected("""{"sizes":5}""")
        assertRejected("""[]""")
        assertRejected("""{"sizes":{"416":5}}""")
    }

    @Test
    fun parse_malformedJson_isRejected() {
        assertRejected("")
        assertRejected("{")
        assertRejected(document() + " x")
    }

    @Test
    fun parse_sizeKeyThatIsNotAPositiveInteger_isRejected() {
        for (key in listOf("0", "-416", "abc", "416.0", "0416", "+416", " 416", "")) {
            assertRejected(document(key = key, input = inputOf("[1,3,416,416]")))
        }
        assertRejected(document(key = "0", input = inputOf("[1,3,0,0]")))
    }

    @Test
    fun parse_inputOrOutputWithoutExactlyOneTensor_isRejected() {
        val two = """[{"shape":[1,3,416,416]},{"shape":[1,3,416,416]}]"""
        assertRejected(document(input = "[]"))
        assertRejected(document(input = two))
        assertRejected(document(output = "[]"))
        assertRejected(document(output = """[{"shape":[1,300,57]},{"shape":[1,300,57]}]"""))
    }

    @Test
    fun parse_inputShapeThatIsNotBatch1Channels3AndTheSizeKey_isRejected() {
        val badShapes =
            listOf(
                "[1,3,416,320]",
                "[1,3,320,416]",
                "[1,3,320,320]",
                "[1,1,416,416]",
                "[2,3,416,416]",
                "[1,416,416,3]",
                "[1,3,416]",
                "[1,3,416,416,1]",
                "[]",
                "[1,3,416.5,416]",
            )
        for (shape in badShapes) {
            assertRejected(document(input = inputOf(shape)))
        }
    }

    @Test
    fun parse_outputDimensionThatIsNotPositive_isRejected() {
        for (shape in listOf("[1,0,57]", "[1,300,-1]", "[0]", "[]")) {
            assertRejected(document(output = outputOf(shape)))
        }
    }

    @Test
    fun parse_missingShapeFileOrSha_isRejected() {
        assertRejected(document(input = """[{"name":"t"}]"""))
        assertRejected(document().replace("\"file\":\"m_416.tflite\",", ""))
        assertRejected(document().replace("\"sha256\":\"$goodSha\",", ""))
    }

    @Test
    fun parse_fileNameThatIsNotABareNameWithAnExtension_isRejected() {
        val badNames =
            listOf(
                "",
                "a/m.tflite",
                "/m.tflite",
                "a\\\\m.tflite",
                "..",
                "../m.tflite",
                "m..tflite",
                ".m.tflite",
                "m",
                "m.",
            )
        for (name in badNames) {
            assertRejected(document(file = name))
        }
    }

    @Test
    fun parse_sha256ThatIsNotSixtyFourLowercaseHexCharacters_isRejected() {
        val badShas =
            listOf(
                "",
                goodSha.dropLast(1),
                goodSha + "0",
                goodSha.uppercase(),
                "g" + goodSha.drop(1),
                "A" + goodSha.drop(1),
            )
        for (sha in badShas) {
            assertRejected(document(sha256 = sha))
        }
    }
}
