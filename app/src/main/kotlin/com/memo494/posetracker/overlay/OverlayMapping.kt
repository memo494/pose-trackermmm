package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.geometry.FitCenter

/**
 * The Img -> View mapping of an [OverlayState] (ARCHITECTURE §4.2), free of `android.*` so it is testable
 * on the JVM.
 *
 * The display size comes from the state, i.e. from the capture plan the frame was captured with, never
 * from a size the view reads itself: a view that reads `WindowManager` metrics through the service
 * context can still see the previous rotation when its window is resized, and would then map a
 * landscape frame with the portrait display size and draw every person off-screen.
 */
internal object OverlayMapping {
    /**
     * The mapping for [state] in a view whose top-left is at ([viewLeftOnScreenPx], [viewTopOnScreenPx]) on
     * screen, or `null` (fail closed: draw nothing) when the display or the capture size is unknown.
     */
    fun forState(
        state: OverlayState,
        viewLeftOnScreenPx: Int,
        viewTopOnScreenPx: Int,
    ): FitCenter? {
        val displayWidthPx = state.displayWidthPx
        val contentRectImg =
            FitCenter.contentRectImg(displayWidthPx, state.displayHeightPx, state.captureWidthImg, state.captureHeightImg)
        if (contentRectImg == null) return null
        return FitCenter.forDisplay(contentRectImg, displayWidthPx, viewLeftOnScreenPx, viewTopOnScreenPx)
    }
}
