package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.detector.Accelerator
import com.memo494.posetracker.detector.FakePoseDetector
import com.memo494.posetracker.detector.ModelProbe
import com.memo494.posetracker.detector.PoseDetector
import org.junit.Test
import java.io.IOException

class SessionModelLoaderTest {
    private val meta = ImportedModelMeta("mine.tflite", "a".repeat(64), 1_000_000L, 640, 300)
    private val importedDetector = FakePoseDetector(emptyList(), 640, Accelerator.CPU)
    private val builtInDetector = FakePoseDetector(emptyList(), 416, Accelerator.CPU)

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
    private var builtInFailure: Throwable? = null
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
            loadBuiltIn = {
                builtInLoads++
                builtInFailure?.let { throw it }
                builtInDetector
            },
            log = { message, error ->
                logs.add(message)
                loggedErrors.add(error)
            },
        )

    private fun storedWith(guard: ImportGuardState) = StoredModelState(meta, fileExists = true, guard = guard)

    // ---- imported model ----

    @Test
    fun importedModel_loadsWithTheMarkerPendingExactlyWhileItLoads() {
        val loaded = loader().load()

        assertThat(guardDuringImportedLoad).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD))
        assertThat(metaGiven).isEqualTo(meta)
        assertThat(storedGuard).isEqualTo(ImportGuardState.Idle)
        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD), ImportGuardState.Idle).inOrder()
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

        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD), ImportGuardState.Idle).inOrder()
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
        assertThat(loggedErrors.filterNotNull()).containsExactly(importedFailure, builtInFailure).inOrder()
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
        assertThat(logs).hasSize(1)
        assertThat(loggedErrors.single()).isSameInstanceAs(builtInFailure)
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

    // ---- the status the rest of the app reads ----

    @Test
    fun status_describesTheModelInUse() {
        val status = loader().load().status()

        assertThat(status)
            .isEqualTo(ModelStatus(ModelSource.IMPORTED, meta, fallback = null, message = null, inputSize = 640))
    }

    @Test
    fun status_ofAFallbackKeepsTheReasonAndTheBuiltInSize() {
        importedFailure = IllegalStateException("bad")

        val status = loader().load().status()

        assertThat(status.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(status.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(status.message).isEqualTo("IllegalStateException: bad")
        assertThat(status.inputSize).isEqualTo(416)
    }

    @Test
    fun status_withoutADetectorHasInputSizeZero() {
        state = StoredModelState(null, fileExists = false, guard = ImportGuardState.Idle)
        builtInFailure = IllegalStateException("x")

        assertThat(loader().load().status().inputSize).isEqualTo(0)
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
    }
}
