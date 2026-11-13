package com.memo494.posetracker.capture

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One named step of the capture teardown; the [name] is what the log says when the step fails.
 *
 * @property name short human-readable name, for logs.
 * @property action the work; any [Throwable] it throws is logged and the next step still runs.
 */
internal class TeardownStep(
    val name: String,
    val action: () -> Unit,
)

/**
 * Idempotent teardown of the capture session (Consult C1 order) that never blocks the main thread
 * (AGENTS rule 2): no join, no latch, no wait. It runs in three phases, each handed to the thread that
 * owns the objects involved:
 *
 * 1. **main first** ([mainFirst], main thread): remove the overlay, release the virtual display.
 * 2. **capture** ([onCapture], one runnable on the capture thread): close the image reader and the
 *    detector, which belong to that thread.
 * 3. **main last** ([mainLast], main thread, posted by the capture runnable when it is done):
 *    unregister the callback, stop the projection, quit the capture thread, stop the service.
 *
 * [start] may be called from any thread. When it is called on the capture thread (for example from
 * `MediaProjection.Callback.onStop`) the first phase is posted to the main thread instead of run inline.
 *
 * Every step runs in its own `try`/`catch (Throwable)`: a failing step is logged with its name and the
 * following steps still run. The steps of one phase run in the given order.
 *
 * If the capture executor refuses the work ([postToCapture] returns `false` or throws, which means the
 * capture thread is already gone) the [onCapture] steps are skipped and logged, and the last phase runs
 * at once. Skipping is acceptable only because a dead thread can no longer use the reader or the
 * detector, and the capture steps must never run on the main thread (the detector and the reader are
 * thread-confined).
 *
 * @param mainFirst steps of phase 1.
 * @param onCapture steps of phase 2.
 * @param mainLast steps of phase 3.
 * @param postToMain queues a runnable on the main thread; must not run it inline when called off main.
 * @param postToCapture queues a runnable on the capture thread; `false` = refused (thread dead).
 * @param isMainThread whether the calling thread is the main thread.
 * @param logger receives a message and the failure, if any.
 */
internal class CaptureTeardown(
    private val mainFirst: List<TeardownStep>,
    private val onCapture: List<TeardownStep>,
    private val mainLast: List<TeardownStep>,
    private val postToMain: (Runnable) -> Unit,
    private val postToCapture: (Runnable) -> Boolean,
    private val isMainThread: () -> Boolean,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var finished = false

    /** `true` once [start] was called (the teardown may still be running). */
    val isStarted: Boolean
        get() = started.get()

    /** `true` once the last step of the last phase has run. */
    val isFinished: Boolean
        get() = finished

    /**
     * Starts the teardown; safe from any thread. Returns `true` for the first call, which runs the
     * teardown, and `false` for every later call, which does nothing.
     */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        if (isMainThread()) {
            runMainFirst()
        } else {
            postMain(Runnable { runMainFirst() })
        }
        return true
    }

    private fun runMainFirst() {
        runSteps(mainFirst)
        val posted =
            try {
                postToCapture(Runnable { runOnCapture() })
            } catch (e: Throwable) {
                safeLog("teardown: posting to the capture thread failed", e)
                false
            }
        if (!posted) {
            safeLog("teardown: capture thread gone, skipped steps: ${onCapture.joinToString { it.name }}", null)
            runMainLast()
        }
    }

    private fun runOnCapture() {
        try {
            runSteps(onCapture)
        } finally {
            postMain(Runnable { runMainLast() })
        }
    }

    private fun runMainLast() {
        try {
            runSteps(mainLast)
        } finally {
            finished = true
        }
    }

    private fun postMain(runnable: Runnable) {
        try {
            postToMain(runnable)
        } catch (e: Throwable) {
            safeLog("teardown: posting to the main thread failed", e)
        }
    }

    private fun runSteps(steps: List<TeardownStep>) {
        for (step in steps) {
            try {
                step.action()
            } catch (e: Throwable) {
                safeLog("teardown step '${step.name}' failed", e)
            }
        }
    }

    private fun safeLog(
        message: String,
        error: Throwable?,
    ) {
        try {
            logger(message, error)
        } catch (e: Throwable) {
            // A failing logger must not stop the teardown; there is nowhere left to report it.
        }
    }
}
