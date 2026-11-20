package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import java.io.File

/** What [ImportTarget.store] did with an accepted model. */
enum class StoreResult {
    /** The model is stored (also when the library already held the same model: it is never added twice). */
    STORED,

    /** The model library already holds the most models it may hold; nothing was stored. */
    LIBRARY_FULL,

    /** The model could not be stored; what was stored before is unchanged. */
    FAILED,
}

/**
 * Where [ModelImporter] puts the file it copied and checked, and the crash marker it keeps around the probe. The
 * production target is the model library ([ModelLibraryStore]); [ImportedModelStore] is a target with room for one
 * model. Every method touches the disk, so each one refuses the main thread.
 */
interface ImportTarget {
    /** A fresh, empty temporary file on the same file system as the stored models; a leftover is deleted first. */
    fun newTempFile(): File

    /** The crash marker as stored now (the app-start step already applied). */
    fun guard(): ImportGuardState

    /** Stores the crash marker synchronously; `false` when it could not be written. */
    fun setGuard(state: ImportGuardState): Boolean

    /**
     * Makes [temp] (from [newTempFile]) a stored model described by [meta]. [temp] is left for the caller to delete
     * unless the result is [StoreResult.STORED].
     */
    fun store(
        temp: File,
        meta: ImportedModelMeta,
    ): StoreResult
}
