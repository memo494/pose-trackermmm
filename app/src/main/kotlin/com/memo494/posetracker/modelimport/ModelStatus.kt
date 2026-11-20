package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import java.util.concurrent.atomic.AtomicReference

/** Which model a capture session runs. */
enum class ModelSource {
    /** The model bundled with the app. */
    BUILT_IN,

    /** The model the owner imported. */
    IMPORTED,
}

/** Why a session runs the built-in model although an imported one may be expected (M0c D4). */
enum class ModelFallback {
    /** A description of an imported model exists but its file is gone. */
    FILE_MISSING,

    /** The crash marker says LiteRT died while probing an imported file; the imported model is blocked. */
    GUARD_TRIPPED_PROBE,

    /** The crash marker says LiteRT died while loading the imported model; it is blocked. */
    GUARD_TRIPPED_LOAD,

    /** The crash marker could not be stored before the load, so the imported model was not even tried. */
    GUARD_NOT_STORED,

    /** Loading the imported model failed with a catchable error; see [ModelStatus.message]. */
    LOAD_FAILED,

    /** What is stored about the imported model could not be read, so nothing imported was used. */
    STATE_UNREADABLE,
}

/**
 * The model of one capture session, as the control screen and the diagnostics read it. Immutable.
 *
 * @property source the model that runs; [ModelSource.BUILT_IN] also when [fallback] is set.
 * @property meta the imported model that is in use ([ModelSource.IMPORTED]) or that was tried and not used
 *   ([ModelFallback.LOAD_FAILED], [ModelFallback.GUARD_NOT_STORED]); `null` when no imported model was tried.
 * @property fallback why the built-in model runs although an imported one may be expected; `null` = nothing to
 *   report (an imported model in use, or nothing imported).
 * @property message the one-line failure text for [ModelFallback.LOAD_FAILED] and
 *   [ModelFallback.STATE_UNREADABLE] (class name and message, at most `ModelProbe.MAX_MESSAGE_CHARS`
 *   characters); `null` otherwise.
 * @property inputWidth width of the running model's input, in model pixels (Mdl); 0 = no model is running.
 * @property inputHeight height of the running model's input, in model pixels (Mdl); 0 = no model is running.
 * @property portrait what the portrait slot is set to (M1b-1): [SLOT_BUILT_IN] or the name and short sha256 of the
 *   library model, as `name 0123456789ab`.
 * @property landscape what the landscape slot is set to, like [portrait].
 * @property switches how many times the model was changed by a change of orientation since Start (the load at Start
 *   is not counted).
 * @property switchMsMax the longest of those changes, from closing the old detector to the new one being ready, in
 *   milliseconds.
 */
data class ModelStatus(
    val source: ModelSource,
    val meta: ImportedModelMeta?,
    val fallback: ModelFallback?,
    val message: String?,
    val inputWidth: Int,
    val inputHeight: Int,
    val portrait: String = SLOT_BUILT_IN,
    val landscape: String = SLOT_BUILT_IN,
    val switches: Int = 0,
    val switchMsMax: Long = 0L,
) {
    companion object {
        /** The text of a slot that runs the built-in model. */
        const val SLOT_BUILT_IN = "builtin"

        /** The message of [chooseFailed]. */
        const val CHOOSE_FAILED_MESSAGE = "choosing the model failed"

        /**
         * What the control screen and the diagnostics show when the model choice at Start failed before anything was
         * loaded (no detector runs; the built-in model is what the next Start tries again).
         */
        fun chooseFailed(): ModelStatus =
            ModelStatus(ModelSource.BUILT_IN, null, ModelFallback.STATE_UNREADABLE, CHOOSE_FAILED_MESSAGE, 0, 0)
    }
}

/**
 * The [ModelStatus] of the newest capture session, written by the session when it starts and read by the control
 * screen and the diagnostics. Process-wide and thread-safe; the last value stays after the session ends.
 */
object ModelStatusHolder {
    private val current = AtomicReference<ModelStatus?>(null)

    /** The newest status; `null` = no session has started in this process. */
    fun latest(): ModelStatus? = current.get()

    /** Any thread: stores [status] as the newest one. */
    fun publish(status: ModelStatus) {
        current.set(status)
    }

    /** Forgets the status (tests). */
    internal fun clear() {
        current.set(null)
    }
}
