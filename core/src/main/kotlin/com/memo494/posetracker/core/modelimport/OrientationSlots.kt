package com.memo494.posetracker.core.modelimport

/** What one orientation slot runs: the built-in model or one library model. */
sealed interface SlotChoice {
    /** The model bundled with the app. */
    data object BuiltIn : SlotChoice

    /** The library model with this SHA-256 (64 lowercase hex characters). */
    data class Library(
        val sha256: String,
    ) : SlotChoice
}

/**
 * What [OrientationSlots.read] found.
 *
 * @property slots the slots to use; every slot that could not be read is [SlotChoice.BuiltIn].
 * @property unreadable the orientations whose stored value could not be read (bad text, or a model that is not in the
 *   library); the caller logs them. Empty when nothing is stored or everything was read.
 */
data class SlotsRead(
    val slots: OrientationSlots,
    val unreadable: Set<ModelOrientation>,
)

/**
 * The model of each orientation (M1b-1): [portrait] and [landscape], each built-in or one library model. The
 * default is built-in for both. Stored as one small text ([encode]); [read] is the strict reader and fails closed per
 * slot: whatever it cannot read is the built-in model for that slot.
 */
data class OrientationSlots(
    val portrait: SlotChoice = SlotChoice.BuiltIn,
    val landscape: SlotChoice = SlotChoice.BuiltIn,
) {
    /** The choice of [orientation]. */
    fun choiceFor(orientation: ModelOrientation): SlotChoice =
        when (orientation) {
            ModelOrientation.PORTRAIT -> portrait
            ModelOrientation.LANDSCAPE -> landscape
        }

    /** These slots with the slot of [orientation] set to [choice]. */
    fun with(
        orientation: ModelOrientation,
        choice: SlotChoice,
    ): OrientationSlots =
        when (orientation) {
            ModelOrientation.PORTRAIT -> copy(portrait = choice)
            ModelOrientation.LANDSCAPE -> copy(landscape = choice)
        }

    /** These slots with every slot that points to [sha256] turned back to built-in (the model was deleted). */
    fun withoutModel(sha256: String): OrientationSlots =
        copy(
            portrait = portrait.unless(sha256),
            landscape = landscape.unless(sha256),
        )

    /** The stored text: [HEADER], `portrait=` and `landscape=`, each `builtin` or a sha256; `\n`-joined, no trailing newline. */
    fun encode(): String =
        listOf(HEADER, KEY_PORTRAIT + valueOf(portrait), KEY_LANDSCAPE + valueOf(landscape)).joinToString(LINE_SEPARATOR.toString())

    private fun SlotChoice.unless(sha256: String): SlotChoice =
        if (this is SlotChoice.Library && this.sha256 == sha256) SlotChoice.BuiltIn else this

    companion object {
        private const val HEADER = "posetracker-model-slots v1"
        private const val KEY_PORTRAIT = "portrait="
        private const val KEY_LANDSCAPE = "landscape="
        private const val VALUE_BUILT_IN = "builtin"
        private const val LINE_SEPARATOR = '\n'
        private const val EXPECTED_LINES = 3

        private fun valueOf(choice: SlotChoice): String =
            when (choice) {
                SlotChoice.BuiltIn -> VALUE_BUILT_IN
                is SlotChoice.Library -> choice.sha256
            }

        /**
         * Reads the text written by [encode] against the models that exist ([library]). `null` (nothing stored) is
         * the default with nothing unreadable. Text with a wrong header, line count or keys makes both slots
         * unreadable (built-in). Otherwise each slot is read alone: `builtin`, or the sha256 of a model that is in
         * [library]; a bad value or a model that is not in [library] makes that slot built-in and unreadable.
         * Never throws.
         */
        fun read(
            text: String?,
            library: ModelLibrary,
        ): SlotsRead {
            if (text == null) return SlotsRead(OrientationSlots(), emptySet())
            val lines = text.split(LINE_SEPARATOR)
            val wellFormed =
                lines.size == EXPECTED_LINES &&
                    lines[0] == HEADER &&
                    lines[1].startsWith(KEY_PORTRAIT) &&
                    lines[2].startsWith(KEY_LANDSCAPE)
            if (!wellFormed) return SlotsRead(OrientationSlots(), ModelOrientation.entries.toSet())
            val portrait = choiceOf(lines[1].removePrefix(KEY_PORTRAIT), library)
            val landscape = choiceOf(lines[2].removePrefix(KEY_LANDSCAPE), library)
            val unreadable =
                buildSet {
                    if (portrait == null) add(ModelOrientation.PORTRAIT)
                    if (landscape == null) add(ModelOrientation.LANDSCAPE)
                }
            return SlotsRead(OrientationSlots(portrait ?: SlotChoice.BuiltIn, landscape ?: SlotChoice.BuiltIn), unreadable)
        }

        /** The choice of a stored value, or `null` when it is neither `builtin` nor the sha256 of a model in [library]. */
        private fun choiceOf(
            value: String,
            library: ModelLibrary,
        ): SlotChoice? =
            when {
                value == VALUE_BUILT_IN -> SlotChoice.BuiltIn
                ModelSha.isValid(value) && library.contains(value) -> SlotChoice.Library(value)
                else -> null
            }
    }
}
