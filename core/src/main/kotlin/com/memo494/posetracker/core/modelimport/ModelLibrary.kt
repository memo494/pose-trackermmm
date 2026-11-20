package com.memo494.posetracker.core.modelimport

/** Result of [ModelLibrary.add]. */
sealed interface LibraryAdd {
    /** The model was added; [library] is the new library. */
    data class Added(
        val library: ModelLibrary,
    ) : LibraryAdd

    /** A model with the same SHA-256 is already in the library; nothing changes (a model is never added twice). */
    data object Duplicate : LibraryAdd

    /** The library already holds the most models it may hold. */
    data object Full : LibraryAdd
}

/**
 * The imported models the app keeps (M1b-1), keyed by [ImportedModelMeta.sha256]. Immutable; the order is the order
 * of [entries]. The size limit is [com.memo494.posetracker.core.TrackerConfig.modelLibraryMax]; it applies to [add]
 * only, so a library that is already larger (the limit was lowered) is still shown whole and can be pruned by
 * [remove].
 *
 * @property entries the models, each sha256 once.
 */
class ModelLibrary private constructor(
    val entries: List<ImportedModelMeta>,
) {
    /** How many models the library holds. */
    val size: Int get() = entries.size

    /** The model with [sha256], or `null`. */
    fun find(sha256: String): ImportedModelMeta? = entries.firstOrNull { it.sha256 == sha256 }

    /** Whether a model with [sha256] is in the library. */
    fun contains(sha256: String): Boolean = find(sha256) != null

    /** [meta] added at the end, [LibraryAdd.Duplicate] when its sha256 is there already, [LibraryAdd.Full] at [max] models. */
    fun add(
        meta: ImportedModelMeta,
        max: Int,
    ): LibraryAdd =
        when {
            contains(meta.sha256) -> LibraryAdd.Duplicate
            size >= max -> LibraryAdd.Full
            else -> LibraryAdd.Added(ModelLibrary(entries + meta))
        }

    /** The library without the model with [sha256] (the same library when it is not there). */
    fun remove(sha256: String): ModelLibrary = ModelLibrary(entries.filterNot { it.sha256 == sha256 })

    override fun equals(other: Any?): Boolean = other is ModelLibrary && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "ModelLibrary($entries)"

    companion object {
        /** A library with no model. */
        val EMPTY = ModelLibrary(emptyList())

        /** A library of [metas]; of two with the same sha256 only the first is kept. No size limit is applied. */
        fun of(metas: List<ImportedModelMeta>): ModelLibrary = ModelLibrary(metas.distinctBy { it.sha256 })
    }
}
