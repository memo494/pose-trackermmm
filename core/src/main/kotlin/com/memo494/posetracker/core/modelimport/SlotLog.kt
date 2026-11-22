package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig

/** The log lines of the slot choice and of a slot model the runtime refuses ([fitsSlot]). Pure. */
object SlotLog {
    private const val BUILT_IN = "built-in"
    private const val STORE_FAILED = "storing the model choice failed"

    /**
     * The one line after the owner chose [sha256] (`null` = built-in) for [orientation]: [meta] is that model as the
     * library held it before the choice (`null` = not found), [stored] whether the store accepted it.
     * `slot: landscape = <name> <sha16> 960x448`, `slot: landscape = built-in`,
     * `slot: portrait refused <name> <sha16> 960x448 (wide)` for a model that does not fit,
     * `slot: portrait unknown model <sha16>` for a [sha256] the library did not hold, otherwise
     * `storing the model choice failed`.
     */
    fun afterSetSlot(
        orientation: ModelOrientation,
        sha256: String?,
        meta: ImportedModelMeta?,
        stored: Boolean,
    ): String {
        val slot = orientation.logName
        return when {
            sha256 == null && stored -> "slot: $slot = $BUILT_IN"
            meta != null && !fitsSlot(meta, orientation) -> "slot: $slot refused ${describe(meta)} (${shapeWord(meta)})"
            sha256 != null && meta == null -> "slot: $slot unknown model ${shortSha(sha256)}"
            meta != null && stored -> "slot: $slot = ${describe(meta)}"
            else -> STORE_FAILED
        }
    }

    /** The line at load when the slot of [orientation] holds [meta], which does not fit it: the built-in model runs. */
    fun notUsed(
        orientation: ModelOrientation,
        meta: ImportedModelMeta,
    ): String = "model: ${orientation.logName} slot holds ${meta.displayName} ${size(meta)} (${shapeWord(meta)}), not used; $BUILT_IN runs"

    private fun describe(meta: ImportedModelMeta): String = "${meta.displayName} ${shortSha(meta.sha256)} ${size(meta)}"

    private fun shortSha(sha256: String): String = sha256.take(TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH)

    private fun size(meta: ImportedModelMeta): String = "${meta.inputWidth}x${meta.inputHeight}"
}
