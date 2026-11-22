package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.ModelInputText

/**
 * The built-in (bundled) model of each orientation: the portrait model is 448 wide and 960 high, the landscape one
 * 960 wide and 448 high (sizes in `TrackerConfig`). Both are listed in `model_info.json`; the square 416 model is the
 * safety fallback when one of them cannot be loaded (`TrackerConfig.modelInputSize`). Pure.
 */
object BuiltInModels {
    /** Width of the built-in model of [orientation], in model pixels (Mdl). */
    fun widthFor(orientation: ModelOrientation): Int =
        when (orientation) {
            ModelOrientation.PORTRAIT -> TrackerConfig.BUILT_IN_PORTRAIT_WIDTH
            ModelOrientation.LANDSCAPE -> TrackerConfig.BUILT_IN_LANDSCAPE_WIDTH
        }

    /** Height of the built-in model of [orientation], in model pixels (Mdl). */
    fun heightFor(orientation: ModelOrientation): Int =
        when (orientation) {
            ModelOrientation.PORTRAIT -> TrackerConfig.BUILT_IN_PORTRAIT_HEIGHT
            ModelOrientation.LANDSCAPE -> TrackerConfig.BUILT_IN_LANDSCAPE_HEIGHT
        }

    /** The input size of the built-in model of [orientation] as shown to the owner: `448x960` / `960x448`. */
    fun inputText(orientation: ModelOrientation): String = ModelInputText.text(widthFor(orientation), heightFor(orientation))
}
