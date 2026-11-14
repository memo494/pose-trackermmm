package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ImportedModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs = FakeGuardPrefs()
    private val thread = FakeMainThread()
    private val oldBytes = ByteArray(300) { (it % 7).toByte() }
    private val newBytes = ByteArray(400) { (it % 11).toByte() }
    private val oldMeta = meta("old.tflite", 'a', oldBytes.size)
    private val newMeta = meta("new.tflite", 'b', newBytes.size)

    /** Test hook: a rename that returns `false` (a failed move) for a target with this name. */
    private var failRenameOf: String? = null

    /** Test hook: a rename that throws (the process died) for a target with this name. */
    private var dieOnRenameOf: String? = null

    /** Test hook: what the store is told about "an import is running now" (the importer's flag in production). */
    private var importRunning = false

    private val directory get() = File(tmp.root, "imported")

    private val oldName = "model-" + "a".repeat(16) + ".tflite"
    private val newName = "model-" + "b".repeat(16) + ".tflite"

    private fun meta(
        name: String,
        digit: Char,
        bytes: Int,
    ) = ImportedModelMeta(name, digit.toString().repeat(64), bytes.toLong(), 640, 300)

    private fun newStore(appStartApplied: AtomicBoolean = AtomicBoolean(false)) =
        ImportedModelStore(directory, prefs, appStartApplied, thread::check, { importRunning }) { from, to ->
            if (to.name == dieOnRenameOf) throw IllegalStateException("the process died")
            to.name != failRenameOf && from.renameTo(to)
        }

    private fun stage(
        store: ImportedModelStore,
        content: ByteArray,
    ): File = store.newTempFile().also { it.writeBytes(content) }

    private fun storeOldModel(store: ImportedModelStore) {
        assertThat(store.accept(stage(store, oldBytes), oldMeta)).isTrue()
    }

    // ---- state ----

    @Test
    fun emptyStore_hasNoMetaNoFileAndIdleGuard() {
        val state = newStore().state()

        assertThat(state).isEqualTo(StoredModelState(meta = null, fileExists = false, guard = ImportGuardState.Idle))
    }

    @Test
    fun accept_thenState_returnsTheMetaAndTheFile() {
        val store = newStore()
        val temp = stage(store, newBytes)

        assertThat(store.accept(temp, newMeta)).isTrue()

        assertThat(store.state()).isEqualTo(StoredModelState(newMeta, fileExists = true, guard = ImportGuardState.Idle))
        assertThat(store.modelFileFor(newMeta).readBytes()).isEqualTo(newBytes)
        assertThat(temp.exists()).isFalse()
        assertThat(directory.list()!!.toSet()).containsExactly(newName, "meta.txt")
    }

    @Test
    fun accept_replacesThePreviousModelAndMeta() {
        val store = newStore()
        storeOldModel(store)

        assertThat(store.accept(stage(store, newBytes), newMeta)).isTrue()

        assertThat(store.state().meta).isEqualTo(newMeta)
        assertThat(store.modelFileFor(newMeta).readBytes()).isEqualTo(newBytes)
    }

    @Test
    fun accept_removesEveryOtherModelFile() {
        val store = newStore()
        storeOldModel(store)
        File(directory, "model.tflite").writeText("pre-R1 name")
        File(directory, "model-" + "c".repeat(16) + ".tflite").writeText("stray")
        File(directory, "notes.txt").writeText("not a model file")

        assertThat(store.accept(stage(store, newBytes), newMeta)).isTrue()

        assertThat(directory.list()!!.toSet()).containsExactly(newName, "meta.txt", "notes.txt")
        assertThat(store.state().anyFileStored).isTrue()
    }

    @Test
    fun twoModelsOfEqualSize_getDifferentFileNames() {
        val store = newStore()
        val first = meta("first.tflite", 'a', 300)
        val second = meta("second.tflite", 'b', 300)

        assertThat(store.modelFileFor(first).name).isEqualTo(oldName)
        assertThat(store.modelFileFor(second).name).isEqualTo(newName)
        assertThat(store.modelFileFor(first).name).isNotEqualTo(store.modelFileFor(second).name)
    }

    @Test
    fun acceptingTheSameContentAgain_keepsOneFile() {
        val store = newStore()
        storeOldModel(store)

        assertThat(store.accept(stage(store, oldBytes), oldMeta.copy(displayName = "again.tflite"))).isTrue()

        assertThat(directory.list()!!.toSet()).containsExactly(oldName, "meta.txt")
        assertThat(store.modelFileFor(oldMeta).readBytes()).isEqualTo(oldBytes)
    }

    @Test
    fun accept_storesTheGuardAsIdle_liftingATrip() {
        val store = newStore()
        store.setGuard(ImportGuardState.Tripped(ImportStage.PROBE))

        assertThat(store.accept(stage(store, newBytes), newMeta)).isTrue()

        assertThat(prefs.text).isEqualTo("idle")
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun failedAccept_keepsATrip() {
        val store = newStore()
        storeOldModel(store)
        store.setGuard(ImportGuardState.Tripped(ImportStage.LOAD))
        failRenameOf = newName

        assertThat(store.accept(stage(store, newBytes), newMeta)).isFalse()

        assertThat(store.state().guard).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))
    }

    @Test
    fun guardThatCannotBeStored_failsTheAcceptAndKeepsThePreviousModel() {
        val store = newStore()
        storeOldModel(store)
        val temp = stage(store, newBytes)
        prefs.failWrites = true

        assertThat(store.accept(temp, newMeta)).isFalse()

        prefs.failWrites = false
        assertThat(store.state()).isEqualTo(StoredModelState(oldMeta, fileExists = true, guard = ImportGuardState.Idle))
        assertThat(directory.list()!!.toSet() - temp.name).containsExactly(oldName, "meta.txt")
    }

    @Test
    fun orphanModelFileWithoutMeta_isReportedAsStored_andClearRemovesIt() {
        val store = newStore()
        directory.mkdirs()
        File(directory, newName).writeText("left by a process death")

        val state = store.state()

        assertThat(state.meta).isNull()
        assertThat(state.fileExists).isFalse()
        assertThat(state.anyFileStored).isTrue()
        assertThat(store.clear()).isTrue()
        assertThat(store.state().anyFileStored).isFalse()
        assertThat(directory.list()!!.toList()).isEmpty()
    }

    @Test
    fun clear_removesEveryModelFileNotJustTheOneInTheMeta() {
        val store = newStore()
        storeOldModel(store)
        File(directory, "model.tflite").writeText("pre-R1 name")
        File(directory, newName).writeText("orphan")

        assertThat(store.clear()).isTrue()

        assertThat(directory.list()!!.toList()).isEmpty()
    }

    @Test
    fun metaThatDoesNotDecode_isNoImportedModel() {
        val store = newStore()
        storeOldModel(store)
        File(directory, "meta.txt").writeText("this is not a meta")

        val state = store.state()

        assertThat(state.meta).isNull()
        assertThat(ModelChoice.resolve(state)).isEqualTo(ModelChoice.BuiltIn(null))
    }

    @Test
    fun missingModelFile_keepsTheMetaButReportsNoFile() {
        val store = newStore()
        storeOldModel(store)
        assertThat(store.modelFileFor(oldMeta).delete()).isTrue()

        val state = store.state()

        assertThat(state.meta).isEqualTo(oldMeta)
        assertThat(state.fileExists).isFalse()
        assertThat(ModelChoice.resolve(state)).isEqualTo(ModelChoice.BuiltIn(BuiltInReason.FILE_MISSING))
    }

    @Test
    fun clear_deletesEverythingAndResetsTheGuard() {
        val store = newStore()
        storeOldModel(store)
        val leftover = store.newTempFile().also { it.writeText("half a copy") }
        store.setGuard(ImportGuardState.Tripped(ImportStage.LOAD))

        assertThat(store.clear()).isTrue()

        assertThat(store.state()).isEqualTo(StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle))
        assertThat(leftover.exists()).isFalse()
        assertThat(directory.list()!!.toList()).isEmpty()
        assertThat(prefs.text).isEqualTo("idle")
    }

    @Test
    fun clear_onAnEmptyStore_isFine() {
        assertThat(newStore().clear()).isTrue()
    }

    // ---- temp file ----

    @Test
    fun newTempFile_isInsideTheDirectory_andDropsALeftover() {
        val store = newStore()
        stage(store, newBytes)

        val temp = store.newTempFile()

        assertThat(temp.parentFile).isEqualTo(store.modelFileFor(newMeta).parentFile)
        assertThat(temp.name).endsWith(".tflite")
        assertThat(temp.exists()).isFalse()
    }

    // ---- leftover temporary file of a dead process ----

    private fun leaveTemp(): File {
        directory.mkdirs()
        return File(directory, "import.tflite").also { it.writeBytes(newBytes) }
    }

    @Test
    fun firstAccessOfTheProcess_deletesALeftoverTempFile() {
        val leftover = leaveTemp()

        newStore().state()

        assertThat(leftover.exists()).isFalse()
    }

    @Test
    fun firstAccessDuringAnImport_keepsTheTempFile() {
        val leftover = leaveTemp()
        importRunning = true

        newStore().state()

        assertThat(leftover.exists()).isTrue()
    }

    @Test
    fun laterAccess_doesNotDeleteTheTempFileOfAnImportInProgress() {
        val store = newStore()
        store.state()
        importRunning = true
        val temp = store.newTempFile().also { it.writeBytes(newBytes) }

        store.state()
        store.setGuard(ImportGuardState.Idle)
        newStore(AtomicBoolean(true)).state()

        assertThat(temp.exists()).isTrue()
    }

    @Test
    fun leftoverTempFile_isKeptOnLaterAccessesWhenTheProcessAlreadyStarted() {
        val leftover = leaveTemp()

        newStore(AtomicBoolean(true)).state()

        assertThat(leftover.exists()).isTrue()
    }

    // ---- guard ----

    @Test
    fun guard_roundTrips_andIsWrittenAsText() {
        val store = newStore()
        val states =
            listOf(
                ImportGuardState.Pending(ImportStage.PROBE),
                ImportGuardState.Pending(ImportStage.LOAD),
                ImportGuardState.Tripped(ImportStage.LOAD),
                ImportGuardState.Idle,
            )

        for (guard in states) {
            assertThat(store.setGuard(guard)).isTrue()
            assertThat(store.state().guard).isEqualTo(guard)
        }
        assertThat(prefs.text).isEqualTo("idle")
    }

    @Test
    fun setGuard_reportsAFailedWrite() {
        prefs.failWrites = true

        assertThat(newStore().setGuard(ImportGuardState.Pending(ImportStage.PROBE))).isFalse()
    }

    @Test
    fun pendingLeftByACrashedRun_becomesTrippedAndIsStored() {
        prefs.text = "pending:probe"

        val state = newStore().state()

        assertThat(state.guard).isEqualTo(ImportGuardState.Tripped(ImportStage.PROBE))
        assertThat(prefs.text).isEqualTo("tripped:probe")
    }

    @Test
    fun secondState_doesNotApplyTheAppStartAgain() {
        val store = newStore()
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
        // This process begins an attempt; it is not a crash of an earlier run.
        prefs.text = "pending:load"

        assertThat(store.state().guard).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD))
        assertThat(prefs.text).isEqualTo("pending:load")
    }

    @Test
    fun appStartIsProcessWide_aSecondStoreInstanceDoesNotApplyItAgain() {
        val process = AtomicBoolean(false)
        newStore(process).state()
        prefs.text = "pending:probe"

        assertThat(newStore(process).state().guard).isEqualTo(ImportGuardState.Pending(ImportStage.PROBE))
    }

    @Test
    fun setGuardAsFirstCall_stillAppliesTheAppStartBeforeWriting() {
        prefs.text = "pending:load"
        val store = newStore()

        store.setGuard(ImportGuardState.Pending(ImportStage.PROBE))

        // The stale Pending was tripped (and then replaced by the new marker); it is not applied to the new one.
        assertThat(prefs.writes).containsExactly("tripped:load", "pending:probe").inOrder()
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Pending(ImportStage.PROBE))
    }

    @Test
    fun tripThatCannotBeStored_isAppliedAgainOnTheNextCall() {
        prefs.text = "pending:load"
        prefs.failWrites = true
        val store = newStore()
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))

        prefs.failWrites = false

        assertThat(store.state().guard).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))
        assertThat(prefs.text).isEqualTo("tripped:load")
    }

    // ---- failure and crash safety of accept ----

    @Test
    fun failedModelRename_leavesTheOldModelAndMeta() {
        val store = newStore()
        storeOldModel(store)
        failRenameOf = newName

        val accepted = store.accept(stage(store, newBytes), newMeta)

        assertThat(accepted).isFalse()
        assertThat(store.state()).isEqualTo(StoredModelState(oldMeta, fileExists = true, guard = ImportGuardState.Idle))
        assertThat(store.modelFileFor(oldMeta).readBytes()).isEqualTo(oldBytes)
        assertThat(File(directory, "meta.tmp").exists()).isFalse()
    }

    @Test
    fun failedMetaRename_afterTheModelMoved_isNoUsableModel() {
        val store = newStore()
        storeOldModel(store)
        failRenameOf = "meta.txt"

        val accepted = store.accept(stage(store, newBytes), newMeta)

        assertThat(accepted).isFalse()
        assertThat(store.state().meta).isNull()
    }

    @Test
    fun processDeathAfterTheModelRename_leavesNoMetaThatDescribesAnotherFile() {
        val store = newStore()
        storeOldModel(store)
        dieOnRenameOf = "meta.txt"

        assertThrows(IllegalStateException::class.java) { store.accept(stage(store, newBytes), newMeta) }
        dieOnRenameOf = null

        // A new process: the model is the new file, and no meta (old or new) claims otherwise.
        val state = newStore().state()
        assertThat(state.meta).isNull()
        assertThat(ModelChoice.resolve(state)).isEqualTo(ModelChoice.BuiltIn(null))
    }

    @Test
    fun processDeathBeforeTheModelRename_leavesNoMeta() {
        val store = newStore()
        storeOldModel(store)
        dieOnRenameOf = newName

        assertThrows(IllegalStateException::class.java) { store.accept(stage(store, newBytes), newMeta) }
        dieOnRenameOf = null

        val state = newStore().state()
        assertThat(state.meta).isNull()
        assertThat(store.modelFileFor(oldMeta).readBytes()).isEqualTo(oldBytes)
    }

    @Test
    fun acceptWithAMissingTempFile_failsAndKeepsTheOldModel() {
        val store = newStore()
        storeOldModel(store)

        val accepted = store.accept(File(directory, "no-such-file.tmp"), newMeta)

        assertThat(accepted).isFalse()
        assertThat(store.state().meta).isEqualTo(oldMeta)
        assertThat(store.modelFileFor(oldMeta).readBytes()).isEqualTo(oldBytes)
    }

    // ---- the injected main-thread check ----

    @Test
    fun everyMethod_refusesTheMainThread_andTouchesNothing() {
        val store = newStore()
        val temp = stage(store, newBytes)
        thread.onMainThread = true

        assertThrows(IllegalStateException::class.java) { store.state() }
        assertThrows(IllegalStateException::class.java) { store.modelFileFor(newMeta) }
        assertThrows(IllegalStateException::class.java) { store.newTempFile() }
        assertThrows(IllegalStateException::class.java) { store.accept(temp, newMeta) }
        assertThrows(IllegalStateException::class.java) { store.clear() }
        assertThrows(IllegalStateException::class.java) { store.setGuard(ImportGuardState.Idle) }

        assertThat(temp.exists()).isTrue()
        assertThat(prefs.writes).isEmpty()
        thread.onMainThread = false
        assertThat(store.state().meta).isNull()
    }

    @Test
    fun offTheMainThread_theCheckLetsEveryMethodRun() {
        val store = newStore()

        assertThat(store.accept(stage(store, newBytes), newMeta)).isTrue()
        assertThat(store.setGuard(ImportGuardState.Idle)).isTrue()
        assertThat(store.clear()).isTrue()
    }
}
