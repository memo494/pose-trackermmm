package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.OrientationSlots
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.core.modelimport.TensorDescription
import com.memo494.posetracker.detector.ProbeResult
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ModelLibraryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs = FakeGuardPrefs()
    private val thread = FakeMainThread()
    private val logs = mutableListOf<String>()

    /** Test hook: a rename that returns `false` (a failed move) for a target with this name. */
    private var failRenameOf: String? = null

    private val directory get() = File(tmp.root, "imported")

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val shaC = "c".repeat(64)
    private val bytesA = ByteArray(300) { (it % 7).toByte() }
    private val bytesB = ByteArray(400) { (it % 11).toByte() }
    private val bytesC = ByteArray(500) { (it % 13).toByte() }
    private val metaA = meta("a.tflite", 'a', bytesA.size)
    private val metaB = meta("b.tflite", 'b', bytesB.size, width = 832, height = 384)
    private val metaC = meta("c.tflite", 'c', bytesC.size)

    private fun meta(
        name: String,
        digit: Char,
        bytes: Int,
        width: Int = 640,
        height: Int = 640,
    ) = ImportedModelMeta(name, digit.toString().repeat(64), bytes.toLong(), width, height, 300)

    private fun rename(
        from: File,
        to: File,
    ): Boolean = to.name != failRenameOf && from.renameTo(to)

    private fun newRoot(appStartApplied: AtomicBoolean = AtomicBoolean(false)) =
        ImportedModelStore(directory, prefs, appStartApplied, thread::check, { false }, this::rename)

    private fun newLibrary(
        max: Int = 4,
        appStartApplied: AtomicBoolean = AtomicBoolean(false),
    ) = ModelLibraryStore(
        newRoot(appStartApplied),
        TrackerConfig(modelLibraryMax = max),
        thread::check,
        this::rename,
    ) { message, _ -> logs.add(message) }

    private fun add(
        library: ModelLibraryStore,
        meta: ImportedModelMeta,
        bytes: ByteArray,
    ): StoreResult = library.store(library.newTempFile().also { it.writeBytes(bytes) }, meta)

    private fun filesIn(directory: File): List<String> = directory.list()?.sorted().orEmpty()

    private fun entryDirectory(sha: String) = File(directory, sha.take(16))

    // ---- empty and adding ----

    @Test
    fun emptyStore_hasNoModelBuiltInSlotsAndIdleGuard() {
        val state = newLibrary().state()

        assertThat(state.entries).isEmpty()
        assertThat(state.slots).isEqualTo(OrientationSlots())
        assertThat(state.slotsUnreadable).isEmpty()
        assertThat(state.blocked).isEmpty()
        assertThat(state.guard).isEqualTo(ImportGuardState.Idle)
        assertThat(state.anyFileStored).isFalse()
    }

    @Test
    fun store_addsTheModelNextToTheOthers() {
        val library = newLibrary()

        assertThat(add(library, metaA, bytesA)).isEqualTo(StoreResult.STORED)
        assertThat(add(library, metaB, bytesB)).isEqualTo(StoreResult.STORED)

        val state = library.state()
        assertThat(state.entries.map { it.meta }).containsExactly(metaA, metaB).inOrder()
        assertThat(state.entries.map { it.fileExists }).containsExactly(true, true)
        assertThat(library.modelFileFor(metaA).readBytes()).isEqualTo(bytesA)
        assertThat(library.modelFileFor(metaB).readBytes()).isEqualTo(bytesB)
        assertThat(state.anyFileStored).isTrue()
    }

    @Test
    fun store_keepsEachModelInItsOwnDirectory() {
        val library = newLibrary()

        add(library, metaA, bytesA)
        add(library, metaB, bytesB)

        assertThat(filesIn(entryDirectory(shaA))).containsExactly("meta.txt", "model-" + "a".repeat(16) + ".tflite")
        assertThat(filesIn(entryDirectory(shaB))).containsExactly("meta.txt", "model-" + "b".repeat(16) + ".tflite")
    }

    @Test
    fun store_theSameModelAgain_isNotAddedTwiceAndLeavesNoTempFile() {
        val library = newLibrary()
        add(library, metaA, bytesA)

        val temp = library.newTempFile().also { it.writeBytes(bytesA) }
        val result = library.store(temp, metaA.copy(displayName = "again.tflite"))

        assertThat(result).isEqualTo(StoreResult.STORED)
        assertThat(library.state().entries.map { it.meta }).containsExactly(metaA)
        assertThat(temp.exists()).isFalse()
    }

    @Test
    fun store_intoAFullLibrary_isRefusedAndChangesNothing() {
        val library = newLibrary(max = 2)
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)

        val temp = library.newTempFile().also { it.writeBytes(bytesC) }
        val result = library.store(temp, metaC)

        assertThat(result).isEqualTo(StoreResult.LIBRARY_FULL)
        assertThat(library.state().entries).hasSize(2)
        assertThat(entryDirectory(shaC).exists()).isFalse()
        assertThat(temp.exists()).isTrue()
    }

    @Test
    fun store_aDuplicateIntoAFullLibrary_isStillAccepted() {
        val library = newLibrary(max = 1)
        add(library, metaA, bytesA)

        assertThat(add(library, metaA, bytesA)).isEqualTo(StoreResult.STORED)
    }

    @Test
    fun store_afterDeletingOne_thereIsRoomAgain() {
        val library = newLibrary(max = 1)
        add(library, metaA, bytesA)
        assertThat(add(library, metaB, bytesB)).isEqualTo(StoreResult.LIBRARY_FULL)

        assertThat(library.delete(shaA)).isTrue()

        assertThat(add(library, metaB, bytesB)).isEqualTo(StoreResult.STORED)
    }

    @Test
    fun store_aFailedMove_isFailedAndLeavesTheLibraryAsItWas() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        failRenameOf = "model-" + "b".repeat(16) + ".tflite"

        val result = add(library, metaB, bytesB)

        assertThat(result).isEqualTo(StoreResult.FAILED)
        assertThat(library.state().entries.map { it.meta }).containsExactly(metaA)
    }

    // ---- slots ----

    @Test
    fun setSlot_choosesALibraryModelPerOrientation() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)

        assertThat(library.setSlot(ModelOrientation.PORTRAIT, shaA)).isTrue()
        assertThat(library.setSlot(ModelOrientation.LANDSCAPE, shaB)).isTrue()

        val slots = library.state().slots
        assertThat(slots.choiceFor(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(shaA))
        assertThat(slots.choiceFor(ModelOrientation.LANDSCAPE)).isEqualTo(SlotChoice.Library(shaB))
    }

    @Test
    fun setSlot_toNull_isTheBuiltInModel() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setSlot(ModelOrientation.PORTRAIT, shaA)

        assertThat(library.setSlot(ModelOrientation.PORTRAIT, null)).isTrue()

        assertThat(library.state().slots.choiceFor(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.BuiltIn)
    }

    @Test
    fun setSlot_aModelThatIsNotInTheLibrary_isRefused() {
        val library = newLibrary()

        assertThat(library.setSlot(ModelOrientation.PORTRAIT, shaA)).isFalse()
        assertThat(library.state().slots).isEqualTo(OrientationSlots())
    }

    @Test
    fun setSlot_aFailedWrite_keepsTheOldChoice() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setSlot(ModelOrientation.PORTRAIT, shaA)
        failRenameOf = "slots.txt"

        assertThat(library.setSlot(ModelOrientation.PORTRAIT, null)).isFalse()

        assertThat(library.state().slots.choiceFor(ModelOrientation.PORTRAIT)).isEqualTo(SlotChoice.Library(shaA))
        assertThat(File(directory, "slots.txt.tmp").exists()).isFalse()
    }

    @Test
    fun slotsRecordThatCannotBeRead_isBuiltInForBothSlotsAndLogged() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        File(directory, "slots.txt").writeText("garbage")

        val state = library.state()

        assertThat(state.slots).isEqualTo(OrientationSlots())
        assertThat(state.slotsUnreadable).containsExactly(ModelOrientation.PORTRAIT, ModelOrientation.LANDSCAPE)
        assertThat(logs.any { it.contains("unreadable") }).isTrue()
    }

    @Test
    fun slotThatPointsToAModelThatIsNotThere_isBuiltInAndUnreadable() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        File(directory, "slots.txt").writeText(OrientationSlots(SlotChoice.Library(shaA), SlotChoice.Library(shaB)).encode())

        val state = library.state()

        assertThat(state.slots).isEqualTo(OrientationSlots(SlotChoice.Library(shaA), SlotChoice.BuiltIn))
        assertThat(state.slotsUnreadable).containsExactly(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun viewFor_aBuiltInSlot_hasNoModel_andAModelSlotHasItsOwn() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setSlot(ModelOrientation.LANDSCAPE, shaA)

        val state = library.state()

        assertThat(state.viewFor(ModelOrientation.PORTRAIT).meta).isNull()
        assertThat(state.viewFor(ModelOrientation.LANDSCAPE).meta).isEqualTo(metaA)
        assertThat(state.viewFor(ModelOrientation.LANDSCAPE).fileExists).isTrue()
        assertThat(ModelChoice.resolve(state.viewFor(ModelOrientation.LANDSCAPE))).isEqualTo(ModelChoice.Imported(metaA))
        assertThat(ModelChoice.resolve(state.viewFor(ModelOrientation.PORTRAIT))).isEqualTo(ModelChoice.BuiltIn(null))
    }

    // ---- delete and clear ----

    @Test
    fun delete_removesTheModelItsFilesAndTheChoicesThatPointedToIt() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)
        library.setSlot(ModelOrientation.PORTRAIT, shaA)
        library.setSlot(ModelOrientation.LANDSCAPE, shaB)

        assertThat(library.delete(shaA)).isTrue()

        val state = library.state()
        assertThat(state.entries.map { it.meta }).containsExactly(metaB)
        assertThat(state.slots).isEqualTo(OrientationSlots(SlotChoice.BuiltIn, SlotChoice.Library(shaB)))
        assertThat(entryDirectory(shaA).exists()).isFalse()
    }

    @Test
    fun delete_aModelThatIsNotStored_isFalse() {
        assertThat(newLibrary().delete(shaA)).isFalse()
    }

    @Test
    fun delete_aFailedSlotWrite_deletesNothing() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setSlot(ModelOrientation.PORTRAIT, shaA)
        failRenameOf = "slots.txt"

        assertThat(library.delete(shaA)).isFalse()

        assertThat(library.state().entries.map { it.meta }).containsExactly(metaA)
    }

    @Test
    fun clearAll_removesEveryModelTheChoicesTheBlockedListAndLiftsTheMarker() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)
        library.setSlot(ModelOrientation.PORTRAIT, shaA)
        prefs.text = "tripped:load:$shaB"
        library.state()

        assertThat(library.clearAll()).isTrue()

        val state = newLibrary().state()
        assertThat(state.entries).isEmpty()
        assertThat(state.slots).isEqualTo(OrientationSlots())
        assertThat(state.blocked).isEmpty()
        assertThat(state.guard).isEqualTo(ImportGuardState.Idle)
        assertThat(filesIn(directory)).isEmpty()
    }

    @Test
    fun clearAll_onAnEmptyStore_isFine() {
        assertThat(newLibrary().clearAll()).isTrue()
    }

    // ---- the crash marker, per model ----

    @Test
    fun aCrashWhileLoadingOneModel_blocksOnlyThatModelAndClearsTheMarker() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)
        library.setSlot(ModelOrientation.PORTRAIT, shaA)
        library.setSlot(ModelOrientation.LANDSCAPE, shaB)
        library.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaA))

        val afterRestart = newLibrary().state()

        assertThat(afterRestart.blocked).containsExactly(shaA)
        assertThat(afterRestart.guard).isEqualTo(ImportGuardState.Idle)
        assertThat(prefs.text).isEqualTo("idle")
        assertThat(ModelChoice.resolve(afterRestart.viewFor(ModelOrientation.PORTRAIT)))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD))
        assertThat(ModelChoice.resolve(afterRestart.viewFor(ModelOrientation.LANDSCAPE))).isEqualTo(ModelChoice.Imported(metaB))
        assertThat(afterRestart.isBlocked(shaA)).isTrue()
        assertThat(afterRestart.isBlocked(shaB)).isFalse()
    }

    @Test
    fun theBlockedList_survivesARestartAndGrows() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)
        library.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaA))
        newLibrary().state()
        newLibrary().setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaB))

        val state = newLibrary().state()

        assertThat(state.blocked).containsExactly(shaA, shaB)
    }

    @Test
    fun aCrashMarkerThatCannotBeMovedToTheBlockedList_stillBlocksThatModelAndRefusesANewMarker() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        add(library, metaB, bytesB)
        library.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaA))
        failRenameOf = "blocked.txt"
        val restarted = newLibrary()

        val state = restarted.state()

        assertThat(state.guard).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD, shaA))
        assertThat(state.isBlocked(shaA)).isTrue()
        assertThat(state.isBlocked(shaB)).isFalse()
        assertThat(restarted.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaB))).isFalse()
        assertThat(restarted.setGuard(ImportGuardState.Idle)).isTrue()
    }

    @Test
    fun blockedList_thatCannotBeRead_blocksEveryModel() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        File(directory, "blocked.txt").mkdirs()

        val state = library.state()

        assertThat(state.blockedUnreadable).isTrue()
        assertThat(state.isBlocked(shaA)).isTrue()
    }

    @Test
    fun importingABlockedModelAgain_unblocksIt() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaA))
        val restarted = newLibrary()
        assertThat(restarted.state().isBlocked(shaA)).isTrue()

        assertThat(add(restarted, metaA, bytesA)).isEqualTo(StoreResult.STORED)

        assertThat(restarted.state().isBlocked(shaA)).isFalse()
        assertThat(restarted.state().blocked).isEmpty()
    }

    @Test
    fun deletingABlockedModel_dropsItFromTheBlockedList() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaA))
        val restarted = newLibrary()
        restarted.state()

        assertThat(restarted.delete(shaA)).isTrue()

        assertThat(restarted.state().blocked).isEmpty()
    }

    @Test
    fun acceptingANewModel_keepsTheRecordOfAnotherModelsCrash() {
        val library = newLibrary()
        add(library, metaA, bytesA)
        library.setGuard(ImportGuardState.Pending(ImportStage.LOAD, shaA))
        val restarted = newLibrary()
        restarted.state()

        add(restarted, metaB, bytesB)

        assertThat(restarted.state().isBlocked(shaA)).isTrue()
        assertThat(restarted.state().isBlocked(shaB)).isFalse()
    }

    @Test
    fun aProbeTrip_staysAndIsLiftedByAnAcceptedImport() {
        val library = newLibrary()
        prefs.text = "tripped:probe"

        assertThat(library.guard()).isEqualTo(ImportGuardState.Tripped(ImportStage.PROBE))

        add(library, metaA, bytesA)

        assertThat(library.guard()).isEqualTo(ImportGuardState.Idle)
    }

    // ---- migration from the single imported model ----

    private fun storeLegacyModel(
        meta: ImportedModelMeta,
        bytes: ByteArray,
    ) {
        val legacy = newRoot()
        assertThat(legacy.accept(legacy.newTempFile().also { it.writeBytes(bytes) }, meta)).isTrue()
    }

    @Test
    fun migration_movesTheSingleModelIntoTheLibraryAndPointsBothSlotsAtIt() {
        storeLegacyModel(metaA, bytesA)

        val state = newLibrary().state()

        assertThat(state.entries.map { it.meta }).containsExactly(metaA)
        assertThat(state.entries.single().fileExists).isTrue()
        assertThat(state.slots).isEqualTo(OrientationSlots(SlotChoice.Library(shaA), SlotChoice.Library(shaA)))
        assertThat(filesIn(directory)).containsExactly("a".repeat(16), "slots.txt")
        assertThat(newLibrary().modelFileFor(metaA).readBytes()).isEqualTo(bytesA)
    }

    @Test
    fun migration_isDoneOnce_andLaterReadsChangeNothing() {
        storeLegacyModel(metaA, bytesA)
        val library = newLibrary()
        library.state()
        library.setSlot(ModelOrientation.PORTRAIT, null)

        val state = newLibrary().state()

        assertThat(state.entries).hasSize(1)
        assertThat(state.slots).isEqualTo(OrientationSlots(SlotChoice.BuiltIn, SlotChoice.Library(shaA)))
    }

    @Test
    fun migration_aLoadCrashRecordedWithoutAModel_blocksTheMigratedModel() {
        storeLegacyModel(metaA, bytesA)
        prefs.text = "pending:load"

        val state = newLibrary().state()

        assertThat(state.blocked).containsExactly(shaA)
        assertThat(state.isBlocked(shaA)).isTrue()
        assertThat(ModelChoice.resolve(state.viewFor(ModelOrientation.PORTRAIT)))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD))
    }

    @Test
    fun migration_afterAProcessDeathBetweenTheMoveAndTheCleanup_isFinishedByTheNextRead() {
        storeLegacyModel(metaA, bytesA)
        // The model moved and the slots were written, but the old meta is still there (the process died).
        val first = newLibrary()
        first.state()
        File(directory, "meta.txt").writeText(metaA.encode())

        val state = newLibrary().state()

        assertThat(state.entries.map { it.meta }).containsExactly(metaA)
        assertThat(File(directory, "meta.txt").exists()).isFalse()
        assertThat(state.slots).isEqualTo(OrientationSlots(SlotChoice.Library(shaA), SlotChoice.Library(shaA)))
    }

    @Test
    fun migration_aFailedMove_isRetriedByTheNextRead() {
        storeLegacyModel(metaA, bytesA)
        failRenameOf = "model-" + "a".repeat(16) + ".tflite"

        val failed = newLibrary().state()

        assertThat(failed.entries).isEmpty()
        assertThat(File(directory, "meta.txt").exists()).isTrue()

        failRenameOf = null
        val retried = newLibrary().state()

        assertThat(retried.entries.map { it.meta }).containsExactly(metaA)
    }

    @Test
    fun migration_aMetaWithoutItsFile_isDropped() {
        storeLegacyModel(metaA, bytesA)
        File(directory, "model-" + "a".repeat(16) + ".tflite").delete()

        val state = newLibrary().state()

        assertThat(state.entries).isEmpty()
        assertThat(File(directory, "meta.txt").exists()).isFalse()
        assertThat(state.slots).isEqualTo(OrientationSlots())
    }

    // ---- the importer into the library ----

    private fun tensor(vararg shape: Int) = TensorDescription(shape.toList(), "float32")

    private fun tfliteBytes(
        size: Int,
        seed: Int,
    ): ByteArray =
        ByteArray(size) { ((it * 31 + seed) % 251).toByte() }.also {
            "TFL3".forEachIndexed { index, c -> it[4 + index] = c.code.toByte() }
        }

    private fun importerFor(library: ModelLibraryStore) =
        ModelImporter(library, TrackerConfig(importMinBytes = 100L, importMaxBytes = 10_000L), thread::check) {
            ProbeResult.Tensors(listOf(tensor(1, 3, 832, 384)), listOf(tensor(1, 300, 57)))
        }

    private fun importBytes(
        importer: ModelImporter,
        bytes: ByteArray,
        name: String,
    ): ImportOutcome = importer.import({ ByteArrayInputStream(bytes) }, name, bytes.size.toLong())

    @Test
    fun importer_addsEachImportToTheLibrary() {
        val library = newLibrary()
        val importer = importerFor(library)

        val first = importBytes(importer, tfliteBytes(500, 1), "one.tflite")
        val second = importBytes(importer, tfliteBytes(600, 2), "two.tflite")

        assertThat(first).isInstanceOf(ImportOutcome.Accepted::class.java)
        assertThat(second).isInstanceOf(ImportOutcome.Accepted::class.java)
        assertThat(library.state().entries.map { it.meta.displayName }).containsExactly("one.tflite", "two.tflite").inOrder()
        assertThat(library.state().entries.map { it.meta.inputWidth }).containsExactly(384, 384)
    }

    @Test
    fun importer_theSameFileTwice_isOneModel() {
        val library = newLibrary()
        val importer = importerFor(library)

        importBytes(importer, tfliteBytes(500, 1), "one.tflite")
        val again = importBytes(importer, tfliteBytes(500, 1), "copy.tflite")

        assertThat(again).isInstanceOf(ImportOutcome.Accepted::class.java)
        assertThat(library.state().entries).hasSize(1)
        assertThat(library.state().entries.single().meta.displayName).isEqualTo("one.tflite")
    }

    @Test
    fun importer_intoAFullLibrary_failsWithLibraryFull() {
        val library = newLibrary(max = 1)
        val importer = importerFor(library)
        importBytes(importer, tfliteBytes(500, 1), "one.tflite")

        val outcome = importBytes(importer, tfliteBytes(600, 2), "two.tflite")

        assertThat(outcome).isEqualTo(ImportOutcome.Failed(FailureKind.LIBRARY_FULL))
        assertThat(library.state().entries).hasSize(1)
    }

    // ---- threads ----

    @Test
    fun everyMethod_refusesTheMainThread() {
        val library = newLibrary()
        thread.onMainThread = true

        assertThrows(IllegalStateException::class.java) { library.state() }
        assertThrows(IllegalStateException::class.java) { library.guard() }
        assertThrows(IllegalStateException::class.java) { library.newTempFile() }
        assertThrows(IllegalStateException::class.java) { library.setGuard(ImportGuardState.Idle) }
        assertThrows(IllegalStateException::class.java) { library.store(File(tmp.root, "x"), metaA) }
        assertThrows(IllegalStateException::class.java) { library.modelFileFor(metaA) }
        assertThrows(IllegalStateException::class.java) { library.storedFileMatches(metaA) }
        assertThrows(IllegalStateException::class.java) { library.setSlot(ModelOrientation.PORTRAIT, null) }
        assertThrows(IllegalStateException::class.java) { library.delete(shaA) }
        assertThrows(IllegalStateException::class.java) { library.clearAll() }
        assertThat(directory.exists()).isFalse()
    }
}
