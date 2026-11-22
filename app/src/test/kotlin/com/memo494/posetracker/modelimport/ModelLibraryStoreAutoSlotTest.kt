package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.core.modelimport.TensorDescription
import com.memo494.posetracker.detector.ProbeResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** M1b-fix-import T2: an accepted new model lands in the slot it fits, in the same step as the add. */
class ModelLibraryStoreAutoSlotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs = FakeGuardPrefs()
    private val thread = FakeMainThread()
    private val logs = mutableListOf<String>()

    /** Test hook: a rename that returns `false` (a failed move) for a target with this name. */
    private var failRenameOf: String? = null

    /** Test hook: reading a file with this name fails (an unreadable record). */
    private var failReadOf: String? = null

    private val directory get() = File(tmp.root, "imported")

    // Tall = H > W (Portrait), wide = W > H (Landscape); the tensor shape is what counts, never the name.
    private val tallBytes = ByteArray(300) { (it % 7).toByte() }
    private val tallTwoBytes = ByteArray(310) { (it % 5).toByte() }
    private val wideBytes = ByteArray(400) { (it % 11).toByte() }
    private val squareBytes = ByteArray(500) { (it % 13).toByte() }
    private val tall = meta("tall.tflite", tallBytes, width = 384, height = 832)
    private val tallTwo = meta("tall2.tflite", tallTwoBytes, width = 448, height = 960)
    private val wide = meta("wide.tflite", wideBytes, width = 832, height = 384)
    private val square = meta("square.tflite", squareBytes, width = 640, height = 640)

    private fun meta(
        name: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): ImportedModelMeta {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ImportedModelMeta(name, sha, bytes.size.toLong(), width, height, 300)
    }

    private fun rename(
        from: File,
        to: File,
    ): Boolean = to.name != failRenameOf && from.renameTo(to)

    private fun readFile(file: File): String {
        if (file.name == failReadOf) throw java.io.IOException("read failed")
        return file.readText(Charsets.UTF_8)
    }

    private fun newLibrary(max: Int = 4) =
        ModelLibraryStore(
            ImportedModelStore(directory, prefs, AtomicBoolean(false), thread::check, { false }, this::rename),
            TrackerConfig(modelLibraryMax = max),
            thread::check,
            this::rename,
            readFile = this::readFile,
        ) { message, _ -> logs.add(message) }

    private fun add(
        library: ModelLibraryStore,
        meta: ImportedModelMeta,
        bytes: ByteArray,
    ): StoreReport = library.storeReporting(library.newTempFile().also { it.writeBytes(bytes) }, meta)

    private fun ModelLibraryStore.slot(orientation: ModelOrientation): SlotChoice = state().slots.choiceFor(orientation)

    @Test
    fun tallModel_withBuiltInPortrait_goesToPortraitAndLandscapeStaysBuiltIn() {
        val library = newLibrary()

        val report = add(library, tall, tallBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isEqualTo(ModelOrientation.PORTRAIT)
        assertThat(report.duplicate).isFalse()
        assertThat(report.libraryCount).isEqualTo(1)
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(tall.sha256))
        assertThat(library.slot(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun ownersCase_bothSlotsHoldATallModel_aWideImportTakesLandscapeAndPortraitStays() {
        val library = newLibrary()
        add(library, tall, tallBytes)
        assertThat(library.setSlot(ModelOrientation.LANDSCAPE, tall.sha256)).isTrue()

        val report = add(library, wide, wideBytes)

        assertThat(report.assignedSlot).isEqualTo(ModelOrientation.LANDSCAPE)
        assertThat(report.libraryCount).isEqualTo(2)
        assertThat(library.slot(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.Library(wide.sha256))
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(tall.sha256))
    }

    @Test
    fun aSlotThatAlreadyFits_isLeftAlone() {
        val library = newLibrary()
        add(library, tall, tallBytes)

        val report = add(library, tallTwo, tallTwoBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isNull()
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(tall.sha256))
        assertThat(library.state().entries.map { it.meta.sha256 }).containsExactly(tall.sha256, tallTwo.sha256)
    }

    @Test
    fun aSquareModel_assignsNothing() {
        val library = newLibrary()

        val report = add(library, square, squareBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isNull()
        assertThat(library.state().slots.choiceFor(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
        assertThat(library.state().slots.choiceFor(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun theSameModelAgain_isADuplicateAndAssignsNothing() {
        val library = newLibrary()
        add(library, wide, wideBytes)
        assertThat(library.setSlot(ModelOrientation.LANDSCAPE, null)).isTrue()

        val report = add(library, wide, wideBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.duplicate).isTrue()
        assertThat(report.assignedSlot).isNull()
        assertThat(report.libraryCount).isEqualTo(1)
        assertThat(library.slot(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun aFullLibrary_assignsNothing() {
        val library = newLibrary(max = 1)
        add(library, tall, tallBytes)

        val report = add(library, wide, wideBytes)

        assertThat(report.result).isEqualTo(StoreResult.LIBRARY_FULL)
        assertThat(report.assignedSlot).isNull()
        assertThat(library.slot(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun aFailedSlotWrite_keepsTheModelStoredAndReportsNoSlot() {
        val library = newLibrary()
        failRenameOf = "slots.txt"

        val report = add(library, tall, tallBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isNull()
        assertThat(report.libraryCount).isEqualTo(1)
        assertThat(library.state().entries.map { it.meta.sha256 }).containsExactly(tall.sha256)
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun anUnreadableSlotRecord_failsClosedStoresTheModelAndAssignsNothing() {
        val library = newLibrary()
        add(library, tall, tallBytes)
        failReadOf = "slots.txt"

        val report = add(library, wide, wideBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isNull()
        failReadOf = null
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(tall.sha256))
        assertThat(library.slot(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
        assertThat(library.state().entries).hasSize(2)
    }

    @Test
    fun aBlockedNewModel_isStoredButNeverAssignedAndStaysBlocked() {
        File(directory, "blocked.txt").also { directory.mkdirs() }.writeText(tall.sha256)
        val library = newLibrary()

        val report = add(library, tall, tallBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isNull()
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
        assertThat(library.slot(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.BuiltIn)
        assertThat(library.state().entries.map { it.meta.sha256 }).containsExactly(tall.sha256)
        assertThat(library.state().blocked).containsExactly(tall.sha256)
        assertThat(logs.filter { it.contains("blocked") }).hasSize(1)
    }

    @Test
    fun anUnreadableBlockedList_failsClosedStoresTheModelAndAssignsNothing() {
        directory.mkdirs()
        File(directory, "blocked.txt").writeText(wide.sha256)
        failReadOf = "blocked.txt"
        val library = newLibrary()

        val report = add(library, tall, tallBytes)

        assertThat(report.result).isEqualTo(StoreResult.STORED)
        assertThat(report.assignedSlot).isNull()
        assertThat(library.state().blockedUnreadable).isTrue()
        failReadOf = null
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
        assertThat(library.state().entries).hasSize(1)
        assertThat(logs.filter { it.contains("blocked list is unreadable") }).hasSize(1)
    }

    @Test
    fun plainStore_assignsToo_sinceItIsTheSameStep() {
        val library = newLibrary()

        val result = library.store(library.newTempFile().also { it.writeBytes(tallBytes) }, tall)

        assertThat(result).isEqualTo(StoreResult.STORED)
        assertThat(library.slot(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(tall.sha256))
    }

    // ---- through the importer ----

    private fun tensor(vararg shape: Int) = TensorDescription(shape.toList(), "float32")

    private fun tfliteBytes(
        size: Int,
        seed: Int,
    ): ByteArray =
        ByteArray(size) { ((it * 31 + seed) % 251).toByte() }.also {
            "TFL3".forEachIndexed { index, c -> it[4 + index] = c.code.toByte() }
        }

    private fun importerFor(
        library: ModelLibraryStore,
        height: Int,
        width: Int,
    ) = ModelImporter(library, TrackerConfig(importMinBytes = 100L, importMaxBytes = 10_000L), thread::check) {
        ProbeResult.Tensors(listOf(tensor(1, 3, height, width)), listOf(tensor(1, 300, 57)))
    }

    private fun importBytes(
        importer: ModelImporter,
        bytes: ByteArray,
        name: String,
    ): ImportOutcome = importer.import({ ByteArrayInputStream(bytes) }, name, bytes.size.toLong())

    @Test
    fun importer_carriesTheAssignedSlotAndTheLibraryCountInTheOutcome() {
        val library = newLibrary()

        val tallOutcome = importBytes(importerFor(library, height = 832, width = 384), tfliteBytes(500, 1), "tall.tflite")
        val wideOutcome = importBytes(importerFor(library, height = 384, width = 832), tfliteBytes(600, 2), "wide.tflite")

        assertThat((tallOutcome as ImportOutcome.Accepted).assignedSlot).isEqualTo(ModelOrientation.PORTRAIT)
        assertThat(tallOutcome.libraryCount).isEqualTo(1)
        assertThat(tallOutcome.duplicate).isFalse()
        assertThat((wideOutcome as ImportOutcome.Accepted).assignedSlot).isEqualTo(ModelOrientation.LANDSCAPE)
        assertThat(wideOutcome.libraryCount).isEqualTo(2)
    }

    @Test
    fun importer_aDuplicateIsAcceptedAsDuplicateWithNoSlot() {
        val library = newLibrary()
        val importer = importerFor(library, height = 832, width = 384)
        importBytes(importer, tfliteBytes(500, 1), "one.tflite")

        val again = importBytes(importer, tfliteBytes(500, 1), "copy.tflite") as ImportOutcome.Accepted

        assertThat(again.duplicate).isTrue()
        assertThat(again.assignedSlot).isNull()
        assertThat(again.libraryCount).isEqualTo(1)
    }

    // ---- the log line ----

    @Test
    fun logLine_hasNameShortShaInputNewOrDuplicateSlotAndCount() {
        val accepted = ImportOutcome.Accepted(tall, ModelOrientation.PORTRAIT, duplicate = false, libraryCount = 2)

        val line = AcceptedImportLog.line(accepted)

        assertThat(
            line,
        ).isEqualTo("model import accepted: tall.tflite sha256=${tall.sha256.take(12)} input=384x832 new slot=portrait library=2")
    }

    @Test
    fun logLine_forADuplicateWithoutASlot_saysDuplicateAndNone() {
        val accepted = ImportOutcome.Accepted(wide, null, duplicate = true, libraryCount = 3)

        val line = AcceptedImportLog.line(accepted)

        assertThat(
            line,
        ).isEqualTo("model import accepted: wide.tflite sha256=${wide.sha256.take(12)} input=832x384 duplicate slot=none library=3")
    }

    @Test
    fun logLine_forALandscapeSlot_saysLandscape() {
        val line = AcceptedImportLog.line(ImportOutcome.Accepted(wide, ModelOrientation.LANDSCAPE, duplicate = false, libraryCount = 1))

        assertThat(line).contains("slot=landscape")
    }
}
