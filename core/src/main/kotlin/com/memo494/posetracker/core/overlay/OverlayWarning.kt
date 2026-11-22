package com.memo494.posetracker.core.overlay

/**
 * Why the HUD shows a warning line. `null` in the overlay state means "no warning". Most warnings are fail
 * closed (ARCHITECTURE §4.2) and hide the detections; [hidesDetections] is `false` for a note that does not.
 *
 * Priority on the HUD, highest first: [DETECTOR_UNAVAILABLE], [CAPTURE_NOT_DISPLAY_SIZED], the "content not
 * visible" note, [BUILT_IN_MODEL_FAILED], [IMPORTED_MODEL_NOT_USED] (shown only when nothing else applies).
 *
 * @property hidesDetections whether the detections must not be drawn while this warning is active.
 */
enum class OverlayWarning(
    val hidesDetections: Boolean,
) {
    /** The capture is not display-sized (rotation in flight, resize, or a partial capture); never mapped. */
    CAPTURE_NOT_DISPLAY_SIZED(true),

    /** The model could not be loaded, so nothing is detected; frames are still drained and closed. */
    DETECTOR_UNAVAILABLE(true),

    /**
     * The bundled model of the orientation could not run, so the square fallback model does. Detection works, so the
     * dots stay visible; this is a note only (it shows the fallback's input size).
     */
    BUILT_IN_MODEL_FAILED(false),

    /**
     * An imported model exists but this session runs the built-in one (it could not be loaded, its file is
     * gone, or the crash guard blocks it). Detection works, so the dots stay visible; this is a note only.
     */
    IMPORTED_MODEL_NOT_USED(false),
}
