package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrientationSlotsTest {
    private fun meta(digit: Char) = ImportedModelMeta("m$digit.tflite", digit.toString().repeat(64), 1_000_000L, 640, 640, 300)

    private val a = meta('a')
    private val b = meta('b')
    private val library = ModelLibrary.of(listOf(a, b))
    private val header = "posetracker-model-slots v1"

    @Test
    fun default_isBuiltInForBothOrientations() {
        val slots = OrientationSlots()

        assertThat(slots.choiceFor(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
        assertThat(slots.choiceFor(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun with_setsOnlyThatSlot() {
        val slots = OrientationSlots().with(ModelOrientation.LANDSCAPE, SlotChoice.Library(a.sha256))

        assertThat(slots.choiceFor(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.Library(a.sha256))
        assertThat(slots.choiceFor(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun withoutModel_turnsEverySlotThatPointsThereBackToBuiltIn() {
        val slots = OrientationSlots(SlotChoice.Library(a.sha256), SlotChoice.Library(b.sha256))

        assertThat(slots.withoutModel(a.sha256)).isEqualTo(OrientationSlots(SlotChoice.BuiltIn, SlotChoice.Library(b.sha256)))
        assertThat(OrientationSlots(SlotChoice.Library(a.sha256), SlotChoice.Library(a.sha256)).withoutModel(a.sha256))
            .isEqualTo(OrientationSlots())
    }

    @Test
    fun encode_isTheDocumentedText() {
        val slots = OrientationSlots(SlotChoice.BuiltIn, SlotChoice.Library(a.sha256))

        assertThat(slots.encode()).isEqualTo("$header\nportrait=builtin\nlandscape=${a.sha256}")
    }

    @Test
    fun read_roundTripsEveryCombination() {
        val choices = listOf(SlotChoice.BuiltIn, SlotChoice.Library(a.sha256), SlotChoice.Library(b.sha256))
        for (portrait in choices) {
            for (landscape in choices) {
                val slots = OrientationSlots(portrait, landscape)

                val read = OrientationSlots.read(slots.encode(), library)

                assertThat(read.slots).isEqualTo(slots)
                assertThat(read.unreadable).isEmpty()
            }
        }
    }

    @Test
    fun read_nothingStored_isTheDefaultAndNotAProblem() {
        val read = OrientationSlots.read(null, library)

        assertThat(read.slots).isEqualTo(OrientationSlots())
        assertThat(read.unreadable).isEmpty()
    }

    @Test
    fun read_garbage_makesBothSlotsBuiltInAndUnreadable() {
        val garbage =
            listOf(
                "",
                "hello",
                "$header\nportrait=builtin",
                "$header\nlandscape=builtin\nportrait=builtin",
                "x\nportrait=builtin\nlandscape=builtin",
            )
        for (text in garbage) {
            val read = OrientationSlots.read(text, library)

            assertThat(read.slots).isEqualTo(OrientationSlots())
            assertThat(read.unreadable).containsExactly(ModelOrientation.PORTRAIT, ModelOrientation.LANDSCAPE)
        }
    }

    @Test
    fun read_aBadValueInOneSlot_failsOnlyThatSlot() {
        val text = "$header\nportrait=${a.sha256}\nlandscape=BUILTIN"

        val read = OrientationSlots.read(text, library)

        assertThat(read.slots).isEqualTo(OrientationSlots(SlotChoice.Library(a.sha256), SlotChoice.BuiltIn))
        assertThat(read.unreadable).containsExactly(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun read_aModelThatIsNotInTheLibrary_isBuiltInAndUnreadable() {
        val text = "$header\nportrait=${"c".repeat(64)}\nlandscape=${b.sha256}"

        val read = OrientationSlots.read(text, library)

        assertThat(read.slots).isEqualTo(OrientationSlots(SlotChoice.BuiltIn, SlotChoice.Library(b.sha256)))
        assertThat(read.unreadable).containsExactly(ModelOrientation.PORTRAIT)
    }

    @Test
    fun read_trailingNewlineOrCarriageReturn_isNotAccepted() {
        val text = OrientationSlots(SlotChoice.Library(a.sha256), SlotChoice.BuiltIn).encode()

        assertThat(OrientationSlots.read(text + "\n", library).unreadable).hasSize(2)
        assertThat(OrientationSlots.read(text.replace("\n", "\r\n"), library).unreadable).hasSize(2)
    }
}
