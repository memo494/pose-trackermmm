package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.detector.Accelerator
import com.memo494.posetracker.detector.BundledDetector
import com.memo494.posetracker.detector.FakePoseDetector
import com.memo494.posetracker.detector.ModelProbe
import com.memo494.posetracker.detector.PoseDetector
import org.junit.Test
import java.io.IOException

class SessionModelLoaderTest {
    private val meta = ImportedModelMeta("mine.tflite", "a".repeat(64), 1_000_000L, 640, 640, 300)
    private val importedDetector = FakePoseDetector(emptyList(), 640, 640, Accelerator.CPU)
    private val builtInDetector = FakePoseDetector(emptyList(), 448, 960, Accelerator.CPU)
    private val squareDetector = FakePoseDetector(emptyList(), 416, 416, Accelerator.CPU)

    private var state = StoredModelState(meta, fileExists = true, guard = ImportGuardState.Idle)
    private var readFailure: Throwable? = null

    /** The marker as stored now; every successful write replaces it. */
    private var storedGuard: ImportGuardState = ImportGuardState.Idle
    private val guardWrites = mutableListOf<ImportGuardState>()

    /** Every write from this index on (0-based, counting all attempts) reports failure and stores nothing. */
    private var failGuardWritesFrom = NEVER
    private var failedWrites = 0
    private var guardThrows: Throwable? = null

    private var importedLoads = 0
    private var builtInLoads = 0
    private var guardDuringImportedLoad: ImportGuardState? = null
    private var metaGiven: ImportedModelMeta? = null
    private var importedFailure: Throwable? = null

    /** Every bundled model fails (the preferred one and the 416 fallback). */
    private var builtInFailure: Throwable? = null

    /** Only the bundled model of the orientation fails; the 416 fallback is fine. */
    private var preferredFailure: Throwable? = null
    private var fallbackLoads = 0

    /** `null` = the hash of the bundled model is unknown, so it loads without a marker; the guard tests set it. */
    private var builtInSha: String? = null
    private val blockedShas = mutableSetOf<String>()
    private var guardDuringBuiltInLoad: ImportGuardState? = null
    private var guardWhileStaging: ImportGuardState? = null

    /** Staging the bundled file (copy and hash) fails: it happens before the marker. */
    private var stagingFailure: Throwable? = null
    private var blockCheckFailure: Throwable? = null
    private var shaReads = 0
    private var storedFileMatches = false
    private var verifyFailure: Throwable? = null
    private val logs = mutableListOf<String>()
    private val loggedErrors = mutableListOf<Throwable?>()

    private fun loader() =
        SessionModelLoader(
            readState = {
                readFailure?.let { throw it }
                state
            },
            setGuard = { guard ->
                guardThrows?.let { throw it }
                if (guardWrites.size + failedWrites >= failGuardWritesFrom) {
                    failedWrites++
                    false
                } else {
                    storedGuard = guard
                    guardWrites.add(guard)
                    true
                }
            },
            loadImported = { given ->
                importedLoads++
                guardDuringImportedLoad = storedGuard
                metaGiven = given
                importedFailure?.let { throw it }
                importedDetector
            },
            builtInSha256 = {
                shaReads++
                builtInSha
            },
            isBuiltInBlocked = {
                blockCheckFailure?.let { throw it }
                it in blockedShas
            },
            loadBuiltIn = { guarded ->
                builtInLoads++
                guardWhileStaging = storedGuard
                stagingFailure?.let { throw it }
                val detector =
                    guarded {
                        guardDuringBuiltInLoad = storedGuard
                        preferredFailure?.let { throw it }
                        builtInFailure?.let { throw it }
                        builtInDetector
                    }
                BundledDetector(detector, "yolo26n-pose_448x960.tflite", BUILT_IN_SHA)
            },
            loadFallbackBuiltIn = {
                fallbackLoads++
                builtInFailure?.let { throw it }
                BundledDetector(squareDetector, "yolo26n-pose_416.tflite", "f".repeat(64))
            },
            log = { message, error ->
                logs.add(message)
                loggedErrors.add(error)
            },
            verifyStored = {
                verifyFailure?.let { throw it }
                storedFileMatches
            },
        )

    private fun storedWith(guard: ImportGuardState) = StoredModelState(meta, fileExists = true, guard = guard)

    // ---- imported model ----

    @Test
    fun importedModel_loadsWithTheMarkerPendingExactlyWhileItLoads() {
        val loaded = loader().load()

        assertThat(guardDuringImportedLoad).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD, meta.sha256))
        assertThat(metaGiven).isEqualTo(meta)
        assertThat(storedGuard).isEqualTo(ImportGuardState.Idle)
        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD, meta.sha256), ImportGuardState.Idle).inOrder()
        assertThat(loaded.detector).isSameInstanceAs(importedDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(loaded.meta).isEqualTo(meta)
        assertThat(loaded.fallback).isNull()
        assertThat(loaded.message).isNull()
        assertThat(builtInLoads).isEqualTo(0)
    }

    @Test
    fun importedModelThatThrowsAnException_fallsBackToTheBuiltInModel() {
        importedFailure = IllegalStateException("input is not NCHW")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(loaded.message).isEqualTo("IllegalStateException: input is not NCHW")
        assertThat(loaded.meta).isEqualTo(meta)
        assertThat(storedGuard).isEqualTo(ImportGuardState.Idle)
        assertThat(builtInLoads).isEqualTo(1)
        assertThat(loggedErrors.filterNotNull().single()).isSameInstanceAs(importedFailure)
    }

    @Test
    fun importedModelThatThrowsAnError_fallsBackToTheBuiltInModel() {
        importedFailure = OutOfMemoryError("model too big")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(loaded.message).isEqualTo("OutOfMemoryError: model too big")
        assertThat(storedGuard).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun aCaughtLoadFailure_isNotACrash_theMarkerGoesBackToIdle() {
        importedFailure = IllegalStateException("no")

        loader().load()

        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD, meta.sha256), ImportGuardState.Idle).inOrder()
    }

    @Test
    fun theFailureMessage_isShortAndOnOneLine() {
        importedFailure = IllegalStateException("line one\nline two " + "x".repeat(ModelProbe.MAX_MESSAGE_CHARS * 2))

        val message = loader().load().message!!

        assertThat(message).doesNotContain("\n")
        assertThat(message.length).isAtMost(ModelProbe.MAX_MESSAGE_CHARS)
        assertThat(message).startsWith("IllegalStateException: line one line two")
    }

    @Test
    fun importedModelThatFailsWhileTheBuiltInModelFailsToo_hasNoDetector() {
        importedFailure = IllegalStateException("imported")
        builtInFailure = IllegalArgumentException("assets")

        val loaded = loader().load()

        assertThat(loaded.detector).isNull()
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(loaded.message).isEqualTo("IllegalStateException: imported")
        assertThat(loggedErrors.filterNotNull()).containsExactly(importedFailure, builtInFailure, builtInFailure).inOrder()
    }

    @Test
    fun markerThatCannotBeStoredBeforeTheLoad_neverLoadsTheImportedModel() {
        failGuardWritesFrom = 0

        val loaded = loader().load()

        assertThat(importedLoads).isEqualTo(0)
        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_NOT_STORED)
        assertThat(loaded.meta).isEqualTo(meta)
    }

    @Test
    fun markerWriteThatThrows_neverLoadsTheImportedModel() {
        guardThrows = IllegalStateException("main thread")

        val loaded = loader().load()

        assertThat(importedLoads).isEqualTo(0)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_NOT_STORED)
        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
    }

    @Test
    fun markerThatCannotBeClearedAfterASuccessfulLoad_stillUsesTheLoadedModel() {
        failGuardWritesFrom = 1

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(importedDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(loaded.fallback).isNull()
        assertThat(logs).isNotEmpty()
    }

    @Test
    fun markerThatCannotBeClearedAfterAFailedLoad_stillFallsBack() {
        failGuardWritesFrom = 1
        importedFailure = IllegalStateException("no")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
    }

    // ---- built-in model ----

    @Test
    fun nothingImported_usesTheBuiltInModelWithoutAFallback() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(loaded.fallback).isNull()
        assertThat(loaded.message).isNull()
        assertThat(loaded.meta).isNull()
        assertThat(importedLoads).isEqualTo(0)
        assertThat(guardWrites).isEmpty()
    }

    @Test
    fun missingFile_usesTheBuiltInModelAndSaysSo() {
        state = StoredModelState(meta, fileExists = false, guard = ImportGuardState.Idle)

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.FILE_MISSING)
        assertThat(loaded.meta).isNull()
        assertThat(importedLoads).isEqualTo(0)
        assertThat(guardWrites).isEmpty()
    }

    @Test
    fun trippedAfterAProbeCrash_usesTheBuiltInModelAndSaysSo() {
        state = storedWith(ImportGuardState.Tripped(ImportStage.PROBE))

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_PROBE)
        assertThat(importedLoads).isEqualTo(0)
        assertThat(guardWrites).isEmpty()
    }

    @Test
    fun trippedAfterAProbeCrash_keepsAStoredModelThatStillMatches() {
        state = storedWith(ImportGuardState.Tripped(ImportStage.PROBE))
        storedFileMatches = true

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(importedDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(loaded.fallback).isNull()
        assertThat(importedLoads).isEqualTo(1)
        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD, meta.sha256), ImportGuardState.Idle).inOrder()
    }

    @Test
    fun trippedAfterAProbeCrash_withAStoredModelThatNoLongerMatches_usesTheBuiltInModel() {
        state = storedWith(ImportGuardState.Tripped(ImportStage.PROBE))
        storedFileMatches = false

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_PROBE)
        assertThat(importedLoads).isEqualTo(0)
        assertThat(guardWrites).isEmpty()
    }

    @Test
    fun trippedAfterAProbeCrash_whenTheCheckThrows_usesTheBuiltInModel() {
        state = storedWith(ImportGuardState.Tripped(ImportStage.PROBE))
        verifyFailure = IOException("cannot read the file")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_PROBE)
        assertThat(importedLoads).isEqualTo(0)
    }

    @Test
    fun trippedAfterALoadCrash_evenWithAMatchingFile_usesTheBuiltInModel() {
        state = storedWith(ImportGuardState.Tripped(ImportStage.LOAD))
        storedFileMatches = true

        val loaded = loader().load()

        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_LOAD)
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(importedLoads).isEqualTo(0)
    }

    @Test
    fun trippedAfterALoadCrash_usesTheBuiltInModelAndSaysSo() {
        state = storedWith(ImportGuardState.Tripped(ImportStage.LOAD))

        val loaded = loader().load()

        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_LOAD)
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(importedLoads).isEqualTo(0)
    }

    @Test
    fun trippedWithNothingStored_isStillReported() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Tripped(ImportStage.PROBE))

        val loaded = loader().load()

        assertThat(loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_PROBE)
        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
    }

    @Test
    fun builtInModelThatFails_hasNoDetectorAndIsLogged() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInFailure = IOException("asset missing")

        val loaded = loader().load()

        assertThat(loaded.detector).isNull()
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(loaded.fallback).isNull()
        assertThat(logs).hasSize(2)
        assertThat(loggedErrors).containsExactly(builtInFailure, builtInFailure).inOrder()
    }

    @Test
    fun builtInModelThatFailsWithAnError_hasNoDetector() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInFailure = OutOfMemoryError("no memory")

        assertThat(loader().load().detector).isNull()
    }

    @Test
    fun stateThatCannotBeRead_usesTheBuiltInModelAndSaysSo() {
        readFailure = SecurityException("denied")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.STATE_UNREADABLE)
        assertThat(loaded.message).isEqualTo("SecurityException: denied")
        assertThat(importedLoads).isEqualTo(0)
        assertThat(guardWrites).isEmpty()
    }

    // ---- the bundled models ----

    @Test
    fun builtIn_runsTheBundledModelOfTheOrientationAndNamesItsFile() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(loaded.builtInFile).isEqualTo("yolo26n-pose_448x960.tflite")
        assertThat(loaded.builtInSha256).isEqualTo(BUILT_IN_SHA)
        assertThat(loaded.status().builtInFile).isEqualTo("yolo26n-pose_448x960.tflite")
        assertThat(loaded.status().builtInSha256).isEqualTo(BUILT_IN_SHA)
        assertThat(fallbackLoads).isEqualTo(0)
    }

    @Test
    fun brokenBundledModel_fallsBackToThe416Model() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        preferredFailure = IllegalStateException("input size differs")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(squareDetector)
        assertThat(loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.BUILT_IN_FAILED)
        assertThat(loaded.builtInFailed).isTrue()
        assertThat(loaded.status().builtInFailed).isTrue()
        assertThat(loaded.builtInFile).isEqualTo("yolo26n-pose_416.tflite")
        assertThat(loaded.message).isEqualTo("built-in model failed: IllegalStateException: input size differs")
        assertThat(loaded.status().inputWidth).isEqualTo(416)
        assertThat(loaded.importedModelNotUsed).isFalse()
        assertThat(loggedErrors.filterNotNull().single()).isSameInstanceAs(preferredFailure)
    }

    @Test
    fun bundledModelThatThrowsAnError_fallsBackToThe416Model() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        preferredFailure = OutOfMemoryError("no memory")

        assertThat(loader().load().detector).isSameInstanceAs(squareDetector)
    }

    @Test
    fun brokenBundledModelAfterAFailedImport_keepsTheImportedFailureAsTheMessage() {
        importedFailure = IllegalStateException("imported")
        preferredFailure = IllegalStateException("bundled")

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(squareDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(loaded.builtInFailed).isTrue()
        assertThat(loaded.importedModelNotUsed).isTrue()
        assertThat(loaded.message).isEqualTo("IllegalStateException: imported")
    }

    @Test
    fun brokenBundledModelAndBroken416Model_haveNoDetector() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        preferredFailure = IllegalStateException("bundled")
        builtInFailure = IllegalStateException("also broken")

        val loaded = loader().load()

        assertThat(loaded.detector).isNull()
        assertThat(loaded.builtInFile).isNull()
        assertThat(loaded.fallback).isNull()
    }

    @Test
    fun bundledModel_loadsWithTheMarkerNamingItExactlyWhileItLoads() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA

        loader().load()

        assertThat(guardDuringBuiltInLoad).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD, BUILT_IN_SHA))
        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD, BUILT_IN_SHA), ImportGuardState.Idle).inOrder()
        assertThat(storedGuard).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun bundledModelThatFailsCatchably_putsTheMarkerBackToIdle_andIsNotABlock() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        preferredFailure = IllegalStateException("bad")

        loader().load()

        assertThat(storedGuard).isEqualTo(ImportGuardState.Idle)
        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD, BUILT_IN_SHA), ImportGuardState.Idle).inOrder()
    }

    @Test
    fun bundledModel_theMarkerIsNotPendingWhileTheFileIsStaged_onlyWhileTheModelIsCreated() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA

        loader().load()

        assertThat(guardWhileStaging).isEqualTo(ImportGuardState.Idle)
        assertThat(guardDuringBuiltInLoad).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD, BUILT_IN_SHA))
    }

    @Test
    fun bundledModelThatFailsToStage_hasNoMarkerAtAll_andTheSquareFallbackRuns() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        stagingFailure = IOException("disk full")

        val loaded = loader().load()

        assertThat(guardWrites).isEmpty()
        assertThat(loaded.detector).isSameInstanceAs(squareDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.BUILT_IN_FAILED)
    }

    @Test
    fun bundledModel_theHashIsReadOncePerLoad() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA

        loader().load()

        assertThat(shaReads).isEqualTo(1)
    }

    @Test
    fun importedLoadFailed_thenTheBundledLoad_markersGoPendingImportedIdlePendingBundledIdle() {
        builtInSha = BUILT_IN_SHA
        importedFailure = IllegalStateException("bad")

        val loaded = loader().load()

        assertThat(loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(guardWrites)
            .containsExactly(
                ImportGuardState.Pending(ImportStage.LOAD, meta.sha256),
                ImportGuardState.Idle,
                ImportGuardState.Pending(ImportStage.LOAD, BUILT_IN_SHA),
                ImportGuardState.Idle,
            ).inOrder()
    }

    @Test
    fun bundledModelThatCrashedBefore_saysTheNoteAndIsMarkedBlocked() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        blockedShas.add(BUILT_IN_SHA)

        val loaded = loader().load()

        assertThat(loaded.message).isEqualTo("built-in model skipped: it crashed earlier")
        assertThat(loaded.builtInBlocked).isTrue()
        assertThat(loaded.status().builtInBlocked).isTrue()
    }

    @Test
    fun crashRecordThatCannotBeChecked_isNotSaidToBeACrash_butStillSkipsTheBundledModel() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        blockCheckFailure = IllegalStateException("blocked list unreadable")

        val loaded = loader().load()

        assertThat(builtInLoads).isEqualTo(0)
        assertThat(loaded.detector).isSameInstanceAs(squareDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.BUILT_IN_FAILED)
        assertThat(loaded.message).isEqualTo("built-in model skipped: crash record unreadable")
        assertThat(loaded.builtInBlocked).isFalse()
    }

    @Test
    fun bundledModelThatCrashedBefore_isSkipped_andTheSquareFallbackRuns() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        blockedShas.add(BUILT_IN_SHA)

        val loaded = loader().load()

        assertThat(builtInLoads).isEqualTo(0)
        assertThat(fallbackLoads).isEqualTo(1)
        assertThat(loaded.detector).isSameInstanceAs(squareDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.BUILT_IN_FAILED)
        assertThat(loaded.builtInFailed).isTrue()
        assertThat(loaded.message).contains("crashed")
        assertThat(guardWrites).isEmpty()
        assertThat(logs.any { it.contains("crashed") }).isTrue()
    }

    @Test
    fun bundledModelWhoseMarkerCannotBeStored_isNotLoaded_theSquareFallbackRuns() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        failGuardWritesFrom = 0

        val loaded = loader().load()

        // The file may be staged, but the model is never created without its marker.
        assertThat(guardDuringBuiltInLoad).isNull()
        assertThat(loaded.detector).isSameInstanceAs(squareDetector)
        assertThat(loaded.fallback).isEqualTo(ModelFallback.BUILT_IN_FAILED)
    }

    @Test
    fun bundledModelWithoutAKnownHash_loadsWithoutAMarker() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)

        val loaded = loader().load()

        assertThat(loaded.detector).isSameInstanceAs(builtInDetector)
        assertThat(guardWrites).isEmpty()
    }

    @Test
    fun squareFallback_isNeverGuarded() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInSha = BUILT_IN_SHA
        blockedShas.add(BUILT_IN_SHA)

        loader().load()

        assertThat(guardWrites).isEmpty()
    }

    @Test
    fun theSquareFallback_isNotAnImportedModelNote() {
        val detector: PoseDetector = squareDetector
        val loaded = LoadedModel(detector, ModelSource.BUILT_IN, null, ModelFallback.BUILT_IN_FAILED, builtInFailed = true)

        assertThat(loaded.importedModelNotUsed).isFalse()
        assertThat(loaded.builtInFailed).isTrue()
    }

    // ---- the status the rest of the app reads ----

    @Test
    fun status_describesTheModelInUse() {
        val status = loader().load().status()

        assertThat(status)
            .isEqualTo(ModelStatus(ModelSource.IMPORTED, meta, fallback = null, message = null, inputWidth = 640, inputHeight = 640))
    }

    @Test
    fun status_ofARectangularModelKeepsWidthAndHeightApart() {
        val rectangular = FakePoseDetector(emptyList(), 832, 384, Accelerator.GPU)

        val status = LoadedModel(rectangular, ModelSource.IMPORTED, meta, null).status()

        assertThat(status.inputWidth).isEqualTo(832)
        assertThat(status.inputHeight).isEqualTo(384)
    }

    @Test
    fun status_ofAFallbackKeepsTheReasonAndTheBuiltInSize() {
        importedFailure = IllegalStateException("bad")

        val status = loader().load().status()

        assertThat(status.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(status.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(status.message).isEqualTo("IllegalStateException: bad")
        assertThat(status.inputWidth).isEqualTo(448)
        assertThat(status.inputHeight).isEqualTo(960)
    }

    @Test
    fun status_withoutADetectorHasInputWidthAndHeightZero() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInFailure = IllegalStateException("x")

        val status = loader().load().status()

        assertThat(status.inputWidth).isEqualTo(0)
        assertThat(status.inputHeight).isEqualTo(0)
    }

    @Test
    fun logLine_namesTheSourceTheInputTextAndTheFallback() {
        val rectangular = FakePoseDetector(emptyList(), 832, 384, Accelerator.GPU)

        assertThat(LoadedModel(rectangular, ModelSource.IMPORTED, meta, null).logLine())
            .isEqualTo("model: IMPORTED, input 832x384, fallback none")
        assertThat(LoadedModel(builtInDetector, ModelSource.BUILT_IN, meta, ModelFallback.LOAD_FAILED).logLine())
            .isEqualTo("model: BUILT_IN, input 448x960, fallback LOAD_FAILED")
        assertThat(LoadedModel(null, ModelSource.BUILT_IN, null, null).logLine())
            .isEqualTo("model: BUILT_IN, input 0, fallback none")
    }

    @Test
    fun hudFlags_followTheFallback() {
        val detector: PoseDetector = builtInDetector
        assertThat(LoadedModel(detector, ModelSource.BUILT_IN, null, null).importedModelNotUsed).isFalse()
        assertThat(LoadedModel(detector, ModelSource.BUILT_IN, null, ModelFallback.FILE_MISSING).importedModelNotUsed).isTrue()
        assertThat(LoadedModel(detector, ModelSource.IMPORTED, meta, null).importedModelNotUsed).isFalse()
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
        val BUILT_IN_SHA = "e".repeat(64)
    }
}
