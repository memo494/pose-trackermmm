package com.memo494.posetracker.core.modelimport

/** The step of the imported model's life that is being attempted, and so that may crash the process. */
enum class ImportStage {
    /** LiteRT opens the freshly copied file for the first time, only to read its tensor shapes. */
    PROBE,

    /** LiteRT loads the stored imported file to run inference in a capture session. */
    LOAD,
}

/** State of the crash marker kept by [ImportGuard]; stored as one preference value. */
sealed interface ImportGuardState {
    /** No attempt is open and none ended in a suspected crash: the imported model may be used. */
    object Idle : ImportGuardState {
        override fun toString(): String = "Idle"
    }

    /**
     * An attempt at [stage] started and has not ended (yet). Seen at app start it means the process died
     * during that attempt.
     */
    data class Pending(
        val stage: ImportStage,
    ) : ImportGuardState

    /** The last attempt at [stage] never finished (found as [Pending] at app start): the imported model is not used. */
    data class Tripped(
        val stage: ImportStage,
    ) : ImportGuardState
}

/**
 * The pure state machine behind "never worse than the built-in model" (M0c D4). A native crash inside LiteRT
 * cannot be caught, so the app writes a marker BEFORE the risky call, deletes it AFTER, and reads it at the
 * next start: a marker still there means the process died in between.
 *
 * Who calls what, and when (the app stores [encode]'s text in ONE preference value, written synchronously
 * with `commit()` so the marker survives a crash right after it):
 * - **App start** (`Application`/first activity, once, before any model file is opened): read the value,
 *   `state = onAppStart(decode(text))`, store `encode(state)` if it changed. [ImportGuardState.Pending]
 *   becomes [ImportGuardState.Tripped]; while the state is Tripped the imported model must not be used
 *   (the app uses the built-in model and tells the owner which [ImportStage] failed).
 * - **Import** (worker thread): may start in any state except Pending (another attempt is open), also while
 *   Tripped, because a new file gets a new chance. Store `begin(PROBE)` before LiteRT opens the copied file.
 *   When the probe returns normally, even with a refusal by the rules, the file did not crash; also after a
 *   probe that threw a catchable error no crash happened. The marker then goes back to what it was before:
 *   `succeed()` from Idle, and Tripped again when it was Tripped. Only an accepted import (or [clear]) returns
 *   to Idle.
 * - **Start of a capture session**, only for an imported model and only when the state is Idle: store
 *   `begin(LOAD)` before `PoseDetectors.fromImported`; store `succeed()` once the detector is built, and
 *   also when the load failed with a catchable error (the session falls back to the built-in model).
 * - **Owner imports again or taps "Use built-in model"**: store `clear()`; this lifts a Tripped state (a
 *   new file gets a new chance; the built-in model needs no guard).
 *
 * All functions are total and never throw; unknown stored text is [ImportGuardState.Idle].
 */
object ImportGuard {
    private const val TEXT_IDLE = "idle"
    private const val TEXT_PENDING = "pending:"
    private const val TEXT_TRIPPED = "tripped:"
    private const val TEXT_PROBE = "probe"
    private const val TEXT_LOAD = "load"

    /** An attempt at [stage] is about to start: the marker to store BEFORE the risky call. */
    fun begin(stage: ImportStage): ImportGuardState = ImportGuardState.Pending(stage)

    /** The attempt ended without killing the process (success or a catchable failure): the marker to store AFTER it. */
    fun succeed(): ImportGuardState = ImportGuardState.Idle

    /**
     * Applies the app start to the stored [state]: [ImportGuardState.Pending] means the last attempt never
     * finished and counts as a crash, so it becomes [ImportGuardState.Tripped] at the same stage; every other
     * state is returned unchanged.
     */
    fun onAppStart(state: ImportGuardState): ImportGuardState =
        if (state is ImportGuardState.Pending) ImportGuardState.Tripped(state.stage) else state

    /** The owner imports again or switches to the built-in model: back to [ImportGuardState.Idle], lifting a trip. */
    fun clear(): ImportGuardState = ImportGuardState.Idle

    /** The preference text of [state]: `idle`, `pending:probe`, `pending:load`, `tripped:probe` or `tripped:load`. */
    fun encode(state: ImportGuardState): String =
        when (state) {
            ImportGuardState.Idle -> TEXT_IDLE
            is ImportGuardState.Pending -> TEXT_PENDING + stageText(state.stage)
            is ImportGuardState.Tripped -> TEXT_TRIPPED + stageText(state.stage)
        }

    /** Reads the text written by [encode]; `null` or any other text (unknown, wrong case, extra characters) is Idle. */
    fun decode(text: String?): ImportGuardState {
        if (text == null) return ImportGuardState.Idle
        val pending = text.removePrefix(TEXT_PENDING).takeIf { text.startsWith(TEXT_PENDING) }?.let(::stageOf)
        if (pending != null) return ImportGuardState.Pending(pending)
        val tripped = text.removePrefix(TEXT_TRIPPED).takeIf { text.startsWith(TEXT_TRIPPED) }?.let(::stageOf)
        if (tripped != null) return ImportGuardState.Tripped(tripped)
        return ImportGuardState.Idle
    }

    private fun stageText(stage: ImportStage): String =
        when (stage) {
            ImportStage.PROBE -> TEXT_PROBE
            ImportStage.LOAD -> TEXT_LOAD
        }

    private fun stageOf(text: String): ImportStage? =
        when (text) {
            TEXT_PROBE -> ImportStage.PROBE
            TEXT_LOAD -> ImportStage.LOAD
            else -> null
        }
}
