package com.memo494.posetracker.core.modelimport

/**
 * The one rule of which slot a model may run in (slot shape guard). It is shared by the auto-assignment ([autoSlotFor]),
 * the control screen's picker, the library store and the runtime, so all four agree.
 *
 * The shape is read from the input tensor only, never from the file name: [ImportedModelMeta.inputWidth] is `W` and
 * [ImportedModelMeta.inputHeight] is `H` of `[1, 3, H, W]`. A wide model (`W > H`) runs in Landscape only, a tall one
 * (`H > W`) in Portrait only, a square one in both. A wide model in Portrait letterboxes a tall frame into a wide
 * tensor and shrinks people to a fraction of their size: detection dies without any error.
 */
fun fitsSlot(
    meta: ImportedModelMeta,
    orientation: ModelOrientation,
): Boolean = orientationOfShape(meta).let { it == null || it == orientation }

/** [ModelOrientation.LANDSCAPE] for `W > H`, [ModelOrientation.PORTRAIT] for `H > W`, `null` for a square tensor. */
fun orientationOfShape(meta: ImportedModelMeta): ModelOrientation? =
    when {
        meta.inputWidth > meta.inputHeight -> ModelOrientation.LANDSCAPE
        meta.inputHeight > meta.inputWidth -> ModelOrientation.PORTRAIT
        else -> null
    }

/** The word of the shape for log lines: `wide`, `tall` or `square`. */
fun shapeWord(meta: ImportedModelMeta): String =
    when (orientationOfShape(meta)) {
        ModelOrientation.LANDSCAPE -> SHAPE_WIDE
        ModelOrientation.PORTRAIT -> SHAPE_TALL
        null -> SHAPE_SQUARE
    }

private const val SHAPE_WIDE = "wide"
private const val SHAPE_TALL = "tall"
private const val SHAPE_SQUARE = "square"
