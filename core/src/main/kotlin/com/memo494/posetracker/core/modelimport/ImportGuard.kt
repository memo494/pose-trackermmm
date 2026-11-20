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
     * during that attempt. [sha256] is the model being loaded (M1b-1: the model library switches off only the model
     * that crashed); `null` when the attempt was not about one library model (the probe of a new file) or the marker
     * comes from a version that did not record it.
     */
    data class Pending(
        val stage: ImportStage,
        val sha256: String? = null,
    ) : ImportGuardState

    /**
     * The last attempt at [stage] never finished (found as [Pending] at app start): the imported model is not used.
     * [sha256] is the model that crashed, as in [Pending]; `null` = unknown, which switches off every imported model.
     */
    data class Tripped(
        val stage: ImportStage,
        val sha256: String? = null,
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
 *   Tripped, because a new file gets a new chance. From Idle: store `begin(PROBE)` before LiteRT opens the copied
 *   file, and `succeed()` when the probe returns normally (even with a refusal by the rules, the file did not
 *   crash; also after a probe that threw a catchable error). From Tripped: store nothing before or after the
 *   probe, so the stored trip stays and a process death in the probe still reads as the old trip (a load trip
 *   must not turn into a probe trip, which keeps a stored model). Only an accepted import (or [clear]) returns
 *   to Idle.
 * - **Start of a capture session and every change of orientation (M1b-1)**, only for an imported model: store
 *   `begin(LOAD, sha256)` before `PoseDetectors.fromImported`; store `succeed()` once the detector is built, and
 *   also when the load failed with a catchable error (the session falls back to the built-in model). A trip at the
 *   load names the model that crashed; the app keeps those models in a blocklist (`ModelBlocklist`) and clears the
 *   marker, so only they are switched off. A trip without a model (`sha256 == null`, from an older version)
 *   switches off every imported model until an import is accepted or the models are removed.
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
    private const val SHA_SEPARATOR = ":"

    /**
     * An attempt at [stage] is about to start: the marker to store BEFORE the risky call. [sha256] names the library
     * model that is about to be loaded (stage [ImportStage.LOAD]); `null` for the probe of a new file.
     */
    fun begin(
        stage: ImportStage,
        sha256: String? = null,
    ): ImportGuardState = ImportGuardState.Pending(stage, sha256)

    /** The attempt ended without killing the process (success or a catchable failure): the marker to store AFTER it. */
    fun succeed(): ImportGuardState = ImportGuardState.Idle

    /**
     * Applies the app start to the stored [state]: [ImportGuardState.Pending] means the last attempt never
     * finished and counts as a crash, so it becomes [ImportGuardState.Tripped] at the same stage; every other
     * state is returned unchanged.
     */
    fun onAppStart(state: ImportGuardState): ImportGuardState =
        if (state is ImportGuardState.Pending) ImportGuardState.Tripped(state.stage, state.sha256) else state

    /** The owner imports again or switches to the built-in model: back to [ImportGuardState.Idle], lifting a trip. */
    fun clear(): ImportGuardState = ImportGuardState.Idle

    /**
     * The preference text of [state]: `idle`, `pending:probe`, `pending:load`, `tripped:probe` or `tripped:load`,
     * each with `:` and the 64-character sha256 appended when the state has one (`pending:load:<sha256>`).
     */
    fun encode(state: ImportGuardState): String =
        when (state) {
            ImportGuardState.Idle -> TEXT_IDLE
            is ImportGuardState.Pending -> TEXT_PENDING + stageText(state.stage) + shaSuffix(state.sha256)
            is ImportGuardState.Tripped -> TEXT_TRIPPED + stageText(state.stage) + shaSuffix(state.sha256)
        }

    /**
     * Reads the text written by [encode]; `null` or any other text (unknown, wrong case, extra characters) is Idle.
     * A marker whose sha256 part is not a valid sha256 is still a marker, with an unknown (`null`) model: fail
     * closed, so it switches off every imported model instead of none.
     */
    fun decode(text: String?): ImportGuardState {
        if (text == null) return ImportGuardState.Idle
        val pending = markerOf(text, TEXT_PENDING)
        if (pending != null) return ImportGuardState.Pending(pending.first, pending.second)
        val tripped = markerOf(text, TEXT_TRIPPED)
        if (tripped != null) return ImportGuardState.Tripped(tripped.first, tripped.second)
        return ImportGuardState.Idle
    }

    private fun markerOf(
        text: String,
        prefix: String,
    ): Pair<ImportStage, String?>? {
        if (!text.startsWith(prefix)) return null
        val rest = text.substring(prefix.length)
        val separator = rest.indexOf(SHA_SEPARATOR)
        val stage = stageOf(if (separator < 0) rest else rest.substring(0, separator)) ?: return null
        if (separator < 0) return Pair(stage, null)
        return Pair(stage, rest.substring(separator + 1).takeIf(ModelSha::isValid))
    }

    private fun shaSuffix(sha256: String?): String = if (sha256 == null) "" else SHA_SEPARATOR + sha256

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
