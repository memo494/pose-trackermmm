package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ImportedModelMetaTest {
    private val sha = "0123456789abcdef".repeat(4)

    private val meta =
        ImportedModelMeta(
            displayName = "yolo26n-pose_640.tflite",
            sha256 = sha,
            bytes = 7_654_321L,
            inputWidth = 640,
            inputHeight = 640,
            outputRows = 300,
        )

    private val rect = meta.copy(inputWidth = 832, inputHeight = 384)

    private val encoded =
        listOf(
            "posetracker-imported-model v2",
            "name=yolo26n-pose_640.tflite",
            "sha256=$sha",
            "bytes=7654321",
            "inputWidth=640",
            "inputHeight=640",
            "outputRows=300",
        )

    private val encodedV1 =
        listOf(
            "posetracker-imported-model v1",
            "name=yolo26n-pose_640.tflite",
            "sha256=$sha",
            "bytes=7654321",
            "inputSize=640",
            "outputRows=300",
        )

    private fun text(vararg replace: Pair<Int, String>): String {
        val lines = encoded.toMutableList()
        for ((index, line) in replace) lines[index] = line
        return lines.joinToString("\n")
    }

    private fun textV1(vararg replace: Pair<Int, String>): String {
        val lines = encodedV1.toMutableList()
        for ((index, line) in replace) lines[index] = line
        return lines.joinToString("\n")
    }

    // ---- codec ----

    @Test
    fun encode_usesTheDocumentedLines() {
        assertThat(meta.encode()).isEqualTo(encoded.joinToString("\n"))
    }

    @Test
    fun decode_ofEncode_roundTrips() {
        assertThat(ImportedModelMeta.decode(meta.encode())).isEqualTo(meta)
    }

    @Test
    fun decode_roundTripsOtherValidMetas() {
        val others =
            listOf(
                meta.copy(displayName = "a", bytes = 1L, inputWidth = 32, inputHeight = 32, outputRows = 1),
                rect,
                meta.copy(inputWidth = 384, inputHeight = 832),
                meta.copy(inputWidth = 640, inputHeight = 288),
                meta.copy(displayName = ImportedModelMeta.sanitiseName("My Model v2 (copy).tflite")),
                meta.copy(
                    bytes = TrackerConfig.MAX_IMPORT_BYTES_LIMIT,
                    inputWidth = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT,
                    inputHeight = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT,
                ),
                meta.copy(outputRows = TrackerConfig.IMPORT_MAX_OUTPUT_ROWS),
            )

        for (other in others) {
            assertThat(ImportedModelMeta.decode(other.encode())).isEqualTo(other)
        }
    }

    @Test
    fun decode_ofTheReferenceText_isTheReferenceMeta() {
        assertThat(ImportedModelMeta.decode(text())).isEqualTo(meta)
    }

    @Test
    fun decode_emptyAndGarbage_isNull() {
        for (garbage in listOf("", "\n", "null", "{}", "hello", "\u0000", encoded.first())) {
            assertThat(ImportedModelMeta.decode(garbage)).isNull()
        }
    }

    @Test
    fun decode_wrongOrMissingVersionLine_isNull() {
        assertThat(ImportedModelMeta.decode(text(0 to "posetracker-imported-model v3"))).isNull()
        assertThat(ImportedModelMeta.decode(text(0 to "posetracker-imported-model v1"))).isNull()
        assertThat(ImportedModelMeta.decode(text(0 to "posetracker-imported-model v2 "))).isNull()
        assertThat(ImportedModelMeta.decode(text(0 to "POSETRACKER-IMPORTED-MODEL V2"))).isNull()
        assertThat(ImportedModelMeta.decode(encoded.drop(1).joinToString("\n"))).isNull()
    }

    @Test
    fun decode_missingField_isNull() {
        for (index in 1..6) {
            val lines = encoded.filterIndexed { i, _ -> i != index }

            assertThat(ImportedModelMeta.decode(lines.joinToString("\n"))).isNull()
        }
    }

    @Test
    fun decode_extraLineOrTrailingNewline_isNull() {
        assertThat(ImportedModelMeta.decode(meta.encode() + "\n")).isNull()
        assertThat(ImportedModelMeta.decode(meta.encode() + "\nextra=1")).isNull()
        assertThat(ImportedModelMeta.decode(meta.encode() + "\n\n")).isNull()
    }

    @Test
    fun decode_fieldsInAnotherOrderOrRenamed_isNull() {
        val swapped = text(1 to encoded[2], 2 to encoded[1])
        assertThat(ImportedModelMeta.decode(swapped)).isNull()
        assertThat(ImportedModelMeta.decode(text(4 to "inputwidth=640"))).isNull()
        assertThat(ImportedModelMeta.decode(text(4 to "inputWidth =640"))).isNull()
        assertThat(ImportedModelMeta.decode(text(4 to "inputWidth= 640"))).isNull()
        assertThat(ImportedModelMeta.decode(text(4 to "inputWidth:640"))).isNull()
        assertThat(ImportedModelMeta.decode(text(5 to "inputheight=640"))).isNull()
        assertThat(ImportedModelMeta.decode(text(5 to "inputHeight= 640"))).isNull()
        assertThat(ImportedModelMeta.decode(text(4 to encoded[5], 5 to encoded[4]))).isNull()
    }

    @Test
    fun decode_windowsLineEndings_isNull() {
        assertThat(ImportedModelMeta.decode(meta.encode().replace("\n", "\r\n"))).isNull()
    }

    @Test
    fun decode_badSha256_isNull() {
        val bad =
            listOf(
                "",
                sha.dropLast(1),
                sha + "0",
                sha.uppercase(),
                "g".repeat(64),
                " ".repeat(64),
                sha.replaceRange(10, 11, "-"),
            )

        for (value in bad) {
            assertThat(ImportedModelMeta.decode(text(2 to "sha256=$value"))).isNull()
        }
    }

    @Test
    fun decode_badNumbers_isNull() {
        val bad =
            listOf(
                "bytes=" to
                    listOf(
                        "",
                        "0",
                        "-1",
                        "+5",
                        "007",
                        "1.5",
                        "1e6",
                        "abc",
                        "0x10",
                        " 5",
                        "5 ",
                        "99999999999999999999",
                        "${TrackerConfig.MAX_IMPORT_BYTES_LIMIT + 1L}",
                    ),
                "inputWidth=" to
                    listOf("", "0", "-640", "+640", "0640", "640.0", "x", "31", "641", "4128", "2147483648"),
                "inputHeight=" to
                    listOf("", "0", "-640", "+640", "0640", "640.0", "x", "31", "641", "4128", "2147483648"),
                "outputRows=" to
                    listOf("", "0", "-1", "+300", "0300", "3.0", "x", "${TrackerConfig.IMPORT_MAX_OUTPUT_ROWS + 1}"),
            )

        for ((prefix, values) in bad) {
            val index = encoded.indexOfFirst { it.startsWith(prefix) }
            for (value in values) {
                assertThat(ImportedModelMeta.decode(text(index to prefix + value))).isNull()
            }
        }
    }

    @Test
    fun decode_unsafeNames_isNull() {
        val bad =
            listOf(
                "",
                "../evil.tflite",
                "dir/model.tflite",
                "dir\\model.tflite",
                ".hidden.tflite",
                " lead.tflite",
                "trail.tflite ",
                "tab\there.tflite",
                "ctrl\u0001.tflite",
                "ünï.tflite",
                "a=b.tflite",
                "x".repeat(TrackerConfig.IMPORT_NAME_MAX_CHARS + 1),
            )

        for (name in bad) {
            assertThat(ImportedModelMeta.decode(text(1 to "name=$name"))).isNull()
        }
    }

    @Test
    fun decode_nameWithEmbeddedNewline_isNull() {
        val injected =
            "posetracker-imported-model v2\nname=a\nsha256=$sha\nbytes=1\ninputWidth=640\ninputHeight=640\noutputRows=300\nname=b"

        assertThat(ImportedModelMeta.decode(injected)).isNull()
    }

    @Test
    fun decode_fieldsAtTheSanityBounds_areAccepted() {
        val atMax =
            text(
                3 to "bytes=${TrackerConfig.MAX_IMPORT_BYTES_LIMIT}",
                4 to "inputWidth=${TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT}",
                5 to "inputHeight=${TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT}",
                6 to "outputRows=${TrackerConfig.IMPORT_MAX_OUTPUT_ROWS}",
            )

        assertThat(ImportedModelMeta.decode(atMax)).isNotNull()
    }

    @Test
    fun encode_ofARectangularMeta_writesWidthThenHeight() {
        val lines = rect.encode().split("\n")

        assertThat(lines[0]).isEqualTo("posetracker-imported-model v2")
        assertThat(lines[4]).isEqualTo("inputWidth=832")
        assertThat(lines[5]).isEqualTo("inputHeight=384")
    }

    @Test
    fun decode_ofARectangularText_keepsWidthAndHeightApart() {
        val decoded = ImportedModelMeta.decode(text(4 to "inputWidth=832", 5 to "inputHeight=384"))

        assertThat(decoded).isEqualTo(rect)
        assertThat(decoded!!.inputWidth).isEqualTo(832)
        assertThat(decoded.inputHeight).isEqualTo(384)
    }

    @Test
    fun rectangularMeta_keepsWidthAndHeightApart() {
        assertThat(rect.inputWidth).isEqualTo(832)
        assertThat(rect.inputHeight).isEqualTo(384)
        assertThat(meta.inputWidth).isEqualTo(meta.inputHeight)
    }

    @Test
    fun decode_v1Text_givesWidthEqualToHeight() {
        val decoded = ImportedModelMeta.decode(encodedV1.joinToString("\n"))

        assertThat(decoded).isEqualTo(meta)
        assertThat(decoded!!.inputWidth).isEqualTo(640)
        assertThat(decoded.inputHeight).isEqualTo(640)
    }

    @Test
    fun decode_v1Text_isAsStrictAsBefore() {
        val texts =
            listOf(
                encodedV1.dropLast(1).joinToString("\n"),
                encodedV1.joinToString("\n") + "\n",
                encodedV1.joinToString("\n") + "\nextra=1",
                encodedV1.joinToString("\r\n"),
                textV1(4 to "inputSize=641"),
                textV1(4 to "inputSize=31"),
                textV1(4 to "inputSize=4128"),
                textV1(4 to "inputSize=0640"),
                textV1(4 to "inputWidth=640"),
                textV1(5 to "outputRows=301"),
                textV1(2 to "sha256=${sha.uppercase()}"),
                textV1(1 to "name=../x"),
            )

        for (text in texts) {
            assertThat(ImportedModelMeta.decode(text)).isNull()
        }
    }

    @Test
    fun decode_v2HeaderWithV1Body_isNull() {
        assertThat(ImportedModelMeta.decode(textV1(0 to encoded[0]))).isNull()
    }

    @Test
    fun decode_v1HeaderWithV2Body_isNull() {
        assertThat(ImportedModelMeta.decode(text(0 to encodedV1[0]))).isNull()
    }

    @Test
    fun decode_v2TextWithAnyOldInputSizeLine_isNull() {
        val old = listOf(encoded[0], encoded[1], encoded[2], encoded[3], "inputSize=640", encoded[6])

        assertThat(ImportedModelMeta.decode(old.joinToString("\n"))).isNull()
    }

    @Test
    fun encode_neverWritesV1() {
        assertThat(meta.encode().substringBefore("\n")).isEqualTo("posetracker-imported-model v2")
    }

    @Test
    fun encode_ofARectangularMeta_writesWidthAndHeightLines() {
        val lines = rect.encode().split("\n")

        assertThat(lines[4]).isEqualTo("inputWidth=832")
        assertThat(lines[5]).isEqualTo("inputHeight=384")
    }

    // ---- sanitiseName ----

    @Test
    fun sanitiseName_keepsAConservativeName() {
        assertThat(ImportedModelMeta.sanitiseName("yolo26n-pose_640.tflite")).isEqualTo("yolo26n-pose_640.tflite")
        assertThat(ImportedModelMeta.sanitiseName("My Model v2.tflite")).isEqualTo("My Model v2.tflite")
    }

    @Test
    fun sanitiseName_dropsDirectories() {
        assertThat(ImportedModelMeta.sanitiseName("/storage/emulated/0/Download/m.tflite")).isEqualTo("m.tflite")
        assertThat(ImportedModelMeta.sanitiseName("C:\\Users\\me\\m.tflite")).isEqualTo("m.tflite")
        assertThat(ImportedModelMeta.sanitiseName("../../etc/passwd")).isEqualTo("passwd")
        assertThat(ImportedModelMeta.sanitiseName("a/b\\c/m.tflite")).isEqualTo("m.tflite")
    }

    @Test
    fun sanitiseName_dropsControlAndUnusualCharacters() {
        assertThat(ImportedModelMeta.sanitiseName("a\u0000b\u0001c\nd\te.tflite")).isEqualTo("abcde.tflite")
        assertThat(ImportedModelMeta.sanitiseName("m=1;<x>|\"q\".tflite")).isEqualTo("m1xq.tflite")
        assertThat(ImportedModelMeta.sanitiseName("modèl.tflite")).isEqualTo("modl.tflite")
    }

    @Test
    fun sanitiseName_trimsLeadingDotsAndOuterSpaces() {
        assertThat(ImportedModelMeta.sanitiseName("..hidden.tflite")).isEqualTo("hidden.tflite")
        assertThat(ImportedModelMeta.sanitiseName("  spaced.tflite  ")).isEqualTo("spaced.tflite")
    }

    @Test
    fun sanitiseName_capsTheLength() {
        val result = ImportedModelMeta.sanitiseName("n".repeat(500) + ".tflite")

        assertThat(result).hasLength(TrackerConfig.IMPORT_NAME_MAX_CHARS)
    }

    @Test
    fun sanitiseName_unusableInput_givesTheFallback() {
        for (raw in listOf(null, "", "   ", "/", "...", "\u0000\u0001", "العربية", "/dir/", "?*|")) {
            assertThat(ImportedModelMeta.sanitiseName(raw)).isEqualTo(TrackerConfig.IMPORT_FALLBACK_NAME)
        }
    }

    @Test
    fun sanitiseName_neverReturnsEmpty_andIsIdempotent() {
        val inputs = listOf(null, "", "a", "a/", "/a", "\u0000a", "x".repeat(200), "..a..", " a b ", "a".repeat(63) + " b")

        for (raw in inputs) {
            val once = ImportedModelMeta.sanitiseName(raw)

            assertThat(once).isNotEmpty()
            assertThat(ImportedModelMeta.sanitiseName(once)).isEqualTo(once)
        }
    }

    @Test
    fun sanitiseName_result_isAlwaysDecodable() {
        val raws = listOf("a b.tflite", "../x", "ünï", "\u0000", "k".repeat(300), null)

        for (raw in raws) {
            val named = meta.copy(displayName = ImportedModelMeta.sanitiseName(raw))

            assertThat(ImportedModelMeta.decode(named.encode())).isEqualTo(named)
        }
    }
}
