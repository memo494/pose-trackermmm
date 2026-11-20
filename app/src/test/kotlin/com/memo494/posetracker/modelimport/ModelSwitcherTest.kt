package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.OrientationSlots
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.detector.Accelerator
import com.memo494.posetracker.detector.PoseDetector
import org.junit.Test

private class TestDetector(
    val name: String,
    override val inputWidth: Int,
    override val inputHeight: Int,
) : PoseDetector {
    override val accelerator: Accelerator = Accelerator.CPU
    override val lastInferenceMs: Float = 0f
    var closeCount = 0
    var closeFailure: Throwable? = null

    override fun detect(frame: CaptureFrame): DetectionFrame = throw UnsupportedOperationException("not used")

    override fun close() {
        closeCount++
        closeFailure?.let { throw it }
    }
}

class ModelSwitcherTest {
    private val metaA = ImportedModelMeta("a.tflite", "a".repeat(64), 1_000L, 640, 640, 300)
    private val metaB = ImportedModelMeta("b.tflite", "b".repeat(64), 1_000L, 832, 384, 300)

    private var slots = OrientationSlots()
    private var blocked = emptySet<String>()
    private var unreadable = emptySet<ModelOrientation>()
    private var readFailure: Throwable? = null
    private var now = 0L
    private val guardWrites = mutableListOf<ImportGuardState>()
    private var guardStored = true
    private val loadsImported = mutableListOf<String>()
    private var builtInLoads = 0
    private var importedFailure: Throwable? = null
    private var builtInFailure: Throwable? = null
    private val detectors = mutableListOf<TestDetector>()
    private val logs = mutableListOf<String>()
    private var logFailure: Throwable? = null
    private var nowCalls = 0
    private var nowFailsOnCall = -1

    /** How long each load takes on the fake clock, nanoseconds. */
    private var loadNs = 5_000_000L

    private fun library() =
        LibraryState(
            entries = listOf(LibraryEntry(metaA, true), LibraryEntry(metaB, true)),
            slots = slots,
            slotsUnreadable = unreadable,
            blocked = blocked,
            blockedUnreadable = false,
            guard = ImportGuardState.Idle,
            anyFileStored = true,
        )

    private fun newDetector(
        name: String,
        width: Int,
        height: Int,
    ) = TestDetector(name, width, height).also { detectors.add(it) }

    private val switcher =
        ModelSwitcher(
            readLibrary = {
                readFailure?.let { throw it }
                library()
            },
            setGuard = { state ->
                if (guardStored) guardWrites.add(state)
                guardStored
            },
            loadImported = { meta ->
                now += loadNs
                loadsImported.add(meta.displayName)
                importedFailure?.let { throw it }
                newDetector(meta.displayName, meta.inputWidth, meta.inputHeight)
            },
            loadBuiltIn = {
                now += loadNs
                builtInLoads++
                builtInFailure?.let { throw it }
                newDetector("builtin", 416, 416)
            },
            verifyStored = { false },
            nowNs = {
                nowCalls++
                if (nowCalls == nowFailsOnCall) throw IllegalStateException("clock failed")
                now
            },
            log = { message, _ ->
                logs.add(message)
                logFailure?.let { throw it }
            },
        )

    private fun choose(
        portrait: SlotChoice = SlotChoice.BuiltIn,
        landscape: SlotChoice = SlotChoice.BuiltIn,
    ) {
        slots = OrientationSlots(portrait, landscape)
    }

    private fun library(meta: ImportedModelMeta) = SlotChoice.Library(meta.sha256)

    @Test
    fun start_withBuiltInSlots_loadsTheBuiltInModelOnce_andIsNotASwitch() {
        val selection = switcher.select(ModelOrientation.PORTRAIT)!!

        assertThat(selection.loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(selection.isSwitch).isFalse()
        assertThat(selection.logLine()).isEqualTo("model: built-in for portrait (start 5ms)")
        assertThat(builtInLoads).isEqualTo(1)
        assertThat(switcher.switches).isEqualTo(0)
    }

    @Test
    fun start_withAModelInTheSlot_loadsItWithTheMarkerNamingIt() {
        choose(portrait = library(metaA))

        val selection = switcher.select(ModelOrientation.PORTRAIT)!!

        assertThat(selection.loaded.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(selection.logLine()).isEqualTo("model: a.tflite for portrait (start 5ms)")
        assertThat(guardWrites).containsExactly(ImportGuardState.Pending(ImportStage.LOAD, metaA.sha256), ImportGuardState.Idle).inOrder()
    }

    @Test
    fun rotation_toAnotherModel_closesTheOldDetectorAndLoadsTheNewOne() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        val old = detectors.single()

        val selection = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(old.closeCount).isEqualTo(1)
        assertThat(selection.isSwitch).isTrue()
        assertThat(selection.loaded.detector!!.inputWidth).isEqualTo(832)
        assertThat(selection.logLine()).isEqualTo("model: b.tflite for landscape (switch 5ms)")
        assertThat(switcher.switches).isEqualTo(1)
        assertThat(switcher.switchMsMax).isEqualTo(5L)
    }

    @Test
    fun rotation_toTheSameModel_changesNothing() {
        choose(portrait = library(metaA), landscape = library(metaA))
        switcher.select(ModelOrientation.PORTRAIT)

        assertThat(switcher.select(ModelOrientation.LANDSCAPE)).isNull()

        assertThat(detectors.single().closeCount).isEqualTo(0)
        assertThat(loadsImported).hasSize(1)
        assertThat(switcher.switches).isEqualTo(0)
    }

    @Test
    fun rotation_withBothSlotsBuiltIn_changesNothing() {
        switcher.select(ModelOrientation.PORTRAIT)

        assertThat(switcher.select(ModelOrientation.LANDSCAPE)).isNull()
        assertThat(builtInLoads).isEqualTo(1)
    }

    @Test
    fun switchMsMax_isTheLongestSwitch() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        switcher.select(ModelOrientation.LANDSCAPE)
        loadNs = 20_000_000L
        switcher.select(ModelOrientation.PORTRAIT)
        loadNs = 8_000_000L
        switcher.select(ModelOrientation.LANDSCAPE)

        assertThat(switcher.switches).isEqualTo(3)
        assertThat(switcher.switchMsMax).isEqualTo(20L)
    }

    @Test
    fun aModelThatFailsToLoad_fallsBackToBuiltIn_andIsTriedAgainWhenItsOrientationReturns() {
        choose(portrait = library(metaA))
        importedFailure = IllegalStateException("bad model")

        val first = switcher.select(ModelOrientation.PORTRAIT)!!
        switcher.select(ModelOrientation.LANDSCAPE)
        switcher.select(ModelOrientation.PORTRAIT)

        assertThat(first.loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(first.loaded.detector).isNotNull()
        assertThat(loadsImported).hasSize(2)
        assertThat(guardWrites.last()).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun anOutOfMemoryErrorWhileLoading_isContained() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        importedFailure = OutOfMemoryError("big")

        val selection = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(selection.loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(selection.loaded.detector).isNotNull()
        assertThat(selection.loaded.source).isEqualTo(ModelSource.BUILT_IN)
    }

    @Test
    fun aBlockedModel_isNotLoaded_andTheOtherSlotStillWorks() {
        choose(portrait = library(metaA), landscape = library(metaB))
        blocked = setOf(metaA.sha256)

        val portrait = switcher.select(ModelOrientation.PORTRAIT)!!
        val landscape = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(portrait.loaded.fallback).isEqualTo(ModelFallback.GUARD_TRIPPED_LOAD)
        assertThat(landscape.loaded.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(loadsImported).containsExactly("b.tflite")
    }

    @Test
    fun aMarkerThatCannotBeStored_meansTheModelIsNotLoaded() {
        choose(portrait = library(metaA))
        guardStored = false

        val selection = switcher.select(ModelOrientation.PORTRAIT)!!

        assertThat(selection.loaded.fallback).isEqualTo(ModelFallback.GUARD_NOT_STORED)
        assertThat(loadsImported).isEmpty()
    }

    @Test
    fun aLibraryThatCannotBeRead_runsTheBuiltInModelWithAnUnreadableFallback() {
        readFailure = IllegalStateException("disk")

        val selection = switcher.select(ModelOrientation.PORTRAIT)!!

        assertThat(selection.loaded.fallback).isEqualTo(ModelFallback.STATE_UNREADABLE)
        assertThat(selection.loaded.detector).isNotNull()
    }

    @Test
    fun aChoiceThatCannotBeRead_runsTheBuiltInModelWithAnUnreadableFallback() {
        unreadable = setOf(ModelOrientation.LANDSCAPE)

        val selection = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(selection.loaded.fallback).isEqualTo(ModelFallback.STATE_UNREADABLE)
    }

    @Test
    fun aDetectorThatFailsToClose_doesNotStopTheSwitch() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        detectors.single().closeFailure = IllegalStateException("close broke")

        val selection = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(selection.loaded.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(logs.any { it.contains("closing the previous detector") }).isTrue()
    }

    @Test
    fun withoutAnyDetector_theNextSelectTriesAgain() {
        builtInFailure = IllegalStateException("no assets")
        val first = switcher.select(ModelOrientation.PORTRAIT)!!
        builtInFailure = null

        val second = switcher.select(ModelOrientation.LANDSCAPE)

        assertThat(first.loaded.detector).isNull()
        assertThat(second!!.loaded.detector).isNotNull()
    }

    @Test
    fun status_describesTheRunningModelTheSlotsAndTheCounters() {
        choose(portrait = library(metaA), landscape = library(metaB))
        assertThat(switcher.status()).isNull()
        switcher.select(ModelOrientation.PORTRAIT)
        switcher.select(ModelOrientation.LANDSCAPE)

        val status = switcher.status()!!

        assertThat(status.source).isEqualTo(ModelSource.IMPORTED)
        assertThat(status.meta).isEqualTo(metaB)
        assertThat(status.portrait).isEqualTo("a.tflite " + "a".repeat(12))
        assertThat(status.landscape).isEqualTo("b.tflite " + "b".repeat(12))
        assertThat(status.switches).isEqualTo(1)
        assertThat(status.switchMsMax).isEqualTo(5L)
    }

    @Test
    fun status_withBuiltInSlots_saysBuiltIn() {
        switcher.select(ModelOrientation.PORTRAIT)

        val status = switcher.status()!!

        assertThat(status.portrait).isEqualTo("builtin")
        assertThat(status.landscape).isEqualTo("builtin")
    }

    @Test
    fun aFailureAfterTheOldDetectorWasClosed_neverLeavesAClosedModelRunning() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        val old = detectors.single()
        nowCalls = 0
        nowFailsOnCall = 2

        val selection = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(old.closeCount).isEqualTo(1)
        assertThat(detectors).hasSize(2)
        assertThat(detectors[1].closeCount).isEqualTo(1)
        assertThat(selection.loaded.detector).isNull()
        assertThat(selection.loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(selection.loaded.fallback).isEqualTo(ModelFallback.LOAD_FAILED)
        assertThat(switcher.status()!!.inputWidth).isEqualTo(0)
        assertThat(switcher.switches).isEqualTo(1)
    }

    @Test
    fun afterSuchAFailure_theNextSelectLoadsAgain() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        nowCalls = 0
        nowFailsOnCall = 2
        switcher.select(ModelOrientation.LANDSCAPE)
        nowFailsOnCall = -1

        val again = switcher.select(ModelOrientation.LANDSCAPE)

        assertThat(again!!.loaded.detector!!.inputWidth).isEqualTo(832)
    }

    @Test
    fun aLogThatThrows_afterTheOldDetectorWasClosed_doesNotStopTheSwitch() {
        choose(portrait = library(metaA), landscape = library(metaB))
        switcher.select(ModelOrientation.PORTRAIT)
        detectors.single().closeFailure = IllegalStateException("close failed")
        importedFailure = IllegalStateException("load failed")
        logFailure = IllegalStateException("log failed")

        val selection = switcher.select(ModelOrientation.LANDSCAPE)!!

        assertThat(selection.loaded.source).isEqualTo(ModelSource.BUILT_IN)
        assertThat(selection.loaded.detector).isNotNull()
    }

    @Test
    fun aFailureAtStart_afterTheDetectorWasBuilt_closesThatDetectorAndReturnsNothing() {
        nowCalls = 0
        nowFailsOnCall = 2

        val selection = switcher.select(ModelOrientation.PORTRAIT)

        assertThat(selection).isNull()
        assertThat(detectors).hasSize(1)
        assertThat(detectors.single().closeCount).isEqualTo(1)
        assertThat(switcher.status()).isNull()
    }
}
