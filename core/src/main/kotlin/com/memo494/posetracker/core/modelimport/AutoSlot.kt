package com.memo494.posetracker.core.modelimport

/**
 * Which slot, if any, a freshly imported model should be put into (M1b fix-import).
 *
 * The model's orientation is read from its input tensor only, never from its file name: [ImportedModelMeta.inputWidth]
 * is `W` and [ImportedModelMeta.inputHeight] is `H` of the tensor `[1, 3, H, W]`; `W > H` is
 * [ModelOrientation.LANDSCAPE], `H > W` is [ModelOrientation.PORTRAIT], a square tensor has no orientation.
 *
 * Returns the orientation whose slot gets [newModel], or `null` (assign nothing) when any of these holds:
 * - [newModel] is square;
 * - [newModel] is a duplicate: [library] is the library as it was **before** the add, and it already holds the sha256;
 * - the slot of the model's orientation already holds a model that is not of the opposite shape. Only a library model
 *   whose tensor is strictly the other way round (a tall model in Landscape, the migration state where both slots
 *   share one model) counts as not fitting; a square model in the slot is the owner's choice and is kept;
 * - that slot points to a sha256 that is not in [library]: fail closed, the owner picks by hand. In production this
 *   branch cannot be reached: `OrientationSlots.read` turns such a slot into built-in and marks it unreadable, and
 *   the store's `autoAssign` then declines before calling this function. The branch is defence only.
 *
 * Otherwise the slot is built-in or holds a model of the opposite shape, and its orientation is returned. The other
 * slot is never touched. Pure; never throws.
 */
fun autoSlotFor(
    newModel: ImportedModelMeta,
    slots: OrientationSlots,
    library: ModelLibrary,
): ModelOrientation? {
    val target = orientationOfShape(newModel) ?: return null
    if (library.contains(newModel.sha256)) return null
    val current = slots.choiceFor(target)
    val replaceable =
        when (current) {
            SlotChoice.BuiltIn -> true
            is SlotChoice.Library -> library.find(current.sha256)?.let { orientationOfShape(it) == target.other() } ?: false
        }
    return if (replaceable) target else null
}

/** [ModelOrientation.LANDSCAPE] for `W > H`, [ModelOrientation.PORTRAIT] for `H > W`, `null` for a square tensor. */
private fun orientationOfShape(meta: ImportedModelMeta): ModelOrientation? =
    when {
        meta.inputWidth > meta.inputHeight -> ModelOrientation.LANDSCAPE
        meta.inputHeight > meta.inputWidth -> ModelOrientation.PORTRAIT
        else -> null
    }

private fun ModelOrientation.other(): ModelOrientation =
    when (this) {
        ModelOrientation.PORTRAIT -> ModelOrientation.LANDSCAPE
        ModelOrientation.LANDSCAPE -> ModelOrientation.PORTRAIT
    }
