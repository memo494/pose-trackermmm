package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureTeardownTest {
    /** Recording executors: runnables wait in a queue until the test drains them. */
    private class Rig(
        var onMain: Boolean = true,
        var captureAlive: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val mainQueue = ArrayDeque<Runnable>()
        val captureQueue = ArrayDeque<Runnable>()

        fun step(
            name: String,
            fails: Boolean = false,
        ): TeardownStep =
            TeardownStep(name) {
                events.add(name)
                if (fails) throw IllegalStateException("boom $name")
            }

        fun teardown(
            mainFirst: List<TeardownStep> = listOf(step("overlay"), step("display")),
            onCapture: List<TeardownStep> = listOf(step("reader"), step("detector")),
            mainLast: List<TeardownStep> = listOf(step("callback"), step("projection"), step("service")),
        ): CaptureTeardown =
            CaptureTeardown(
                mainFirst,
                onCapture,
                mainLast,
                { mainQueue.addLast(it) },
                {
                    if (captureAlive) captureQueue.addLast(it)
                    captureAlive
                },
                { onMain },
                { message, _ -> logs.add(message) },
            )

        fun drainMain() {
            while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run()
        }

        fun drainCapture() {
            while (captureQueue.isNotEmpty()) captureQueue.removeFirst().run()
        }
    }

    @Test
    fun startOnMain_runsTheFirstPhaseInlineThenCaptureThenMainLast() {
        val rig = Rig()
        val teardown = rig.teardown()

        assertThat(teardown.start()).isTrue()
        assertThat(rig.events).containsExactly("overlay", "display").inOrder()
        assertThat(teardown.isFinished).isFalse()

        rig.drainCapture()
        assertThat(rig.events).containsExactly("overlay", "display", "reader", "detector").inOrder()
        assertThat(teardown.isFinished).isFalse()

        rig.drainMain()
        assertThat(rig.events)
            .containsExactly("overlay", "display", "reader", "detector", "callback", "projection", "service")
            .inOrder()
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun theCaptureStepsAreGivenAsOneRunnable() {
        val rig = Rig()
        val teardown = rig.teardown()

        teardown.start()

        assertThat(rig.captureQueue).hasSize(1)
    }

    @Test
    fun theLastPhaseIsPostedToMainNotRunOnTheCaptureThread() {
        val rig = Rig()
        val teardown = rig.teardown()
        teardown.start()

        rig.drainCapture()

        assertThat(rig.events).doesNotContain("callback")
        assertThat(rig.mainQueue).hasSize(1)
    }

    @Test
    fun startOffMain_goesThroughPostToMainAndRunsNothingInline() {
        val rig = Rig(onMain = false)
        val teardown = rig.teardown()

        assertThat(teardown.start()).isTrue()

        assertThat(rig.events).isEmpty()
        assertThat(rig.mainQueue).hasSize(1)
        assertThat(teardown.isStarted).isTrue()

        rig.drainMain()
        assertThat(rig.events).containsExactly("overlay", "display").inOrder()
        rig.drainCapture()
        rig.drainMain()
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun secondStart_isANoOp() {
        val rig = Rig()
        val teardown = rig.teardown()
        teardown.start()
        rig.drainCapture()
        rig.drainMain()
        val eventsAfterFirst = rig.events.toList()

        assertThat(teardown.start()).isFalse()
        rig.drainCapture()
        rig.drainMain()

        assertThat(rig.events).isEqualTo(eventsAfterFirst)
    }

    @Test
    fun secondStartWhileTheFirstIsRunning_isANoOp() {
        val rig = Rig(onMain = false)
        val teardown = rig.teardown()

        assertThat(teardown.start()).isTrue()
        assertThat(teardown.start()).isFalse()

        assertThat(rig.mainQueue).hasSize(1)
    }

    @Test
    fun notStartedBeforeStart() {
        val teardown = Rig().teardown()

        assertThat(teardown.isStarted).isFalse()
        assertThat(teardown.isFinished).isFalse()
    }

    @Test
    fun aFailingStep_isLoggedByNameAndTheRestStillRun() {
        val rig = Rig()
        val teardown =
            rig.teardown(
                mainFirst = listOf(rig.step("overlay", fails = true), rig.step("display")),
                onCapture = listOf(rig.step("reader", fails = true), rig.step("detector")),
                mainLast = listOf(rig.step("callback", fails = true), rig.step("service")),
            )

        teardown.start()
        rig.drainCapture()
        rig.drainMain()

        assertThat(rig.events)
            .containsExactly("overlay", "display", "reader", "detector", "callback", "service")
            .inOrder()
        val expectedLogs =
            listOf(
                "teardown step 'overlay' failed",
                "teardown step 'reader' failed",
                "teardown step 'callback' failed",
            )
        assertThat(rig.logs).containsExactlyElementsIn(expectedLogs).inOrder()
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun aStepThatThrowsAnError_doesNotStopTheTeardown() {
        val rig = Rig()
        val teardown =
            rig.teardown(
                onCapture = listOf(TeardownStep("reader") { throw OutOfMemoryError("test") }, rig.step("detector")),
            )

        teardown.start()
        rig.drainCapture()
        rig.drainMain()

        assertThat(rig.events).contains("detector")
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun aCaptureExecutorThatRefuses_skipsTheCaptureStepsAndStillRunsTheLastPhase() {
        val rig = Rig(captureAlive = false)
        val teardown = rig.teardown()

        teardown.start()
        rig.drainCapture()
        rig.drainMain()

        assertThat(rig.events)
            .containsExactly("overlay", "display", "callback", "projection", "service")
            .inOrder()
        assertThat(rig.logs).containsExactly("teardown: capture thread gone, skipped steps: reader, detector")
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun aCaptureExecutorThatThrows_isTreatedAsDead() {
        val rig = Rig()
        val events = rig.events
        val teardown =
            CaptureTeardown(
                listOf(rig.step("overlay")),
                listOf(rig.step("reader")),
                listOf(rig.step("service")),
                { rig.mainQueue.addLast(it) },
                { throw IllegalStateException("handler is dead") },
                { true },
                { message, _ -> rig.logs.add(message) },
            )

        teardown.start()
        rig.drainMain()

        assertThat(events).containsExactly("overlay", "service").inOrder()
        assertThat(rig.logs).contains("teardown: posting to the capture thread failed")
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun aThrowingLogger_doesNotStopTheTeardown() {
        val rig = Rig()
        val teardown =
            CaptureTeardown(
                listOf(rig.step("overlay", fails = true)),
                listOf(rig.step("reader")),
                listOf(rig.step("service")),
                { rig.mainQueue.addLast(it) },
                {
                    rig.captureQueue.addLast(it)
                    true
                },
                { true },
                { _, _ -> throw IllegalStateException("logger broke") },
            )

        teardown.start()
        rig.drainCapture()
        rig.drainMain()

        assertThat(rig.events).containsExactly("overlay", "reader", "service").inOrder()
        assertThat(teardown.isFinished).isTrue()
    }

    @Test
    fun emptyPhases_stillFinish() {
        val rig = Rig()
        val teardown = rig.teardown(emptyList(), emptyList(), emptyList())

        teardown.start()
        rig.drainCapture()
        rig.drainMain()

        assertThat(teardown.isFinished).isTrue()
    }
}
