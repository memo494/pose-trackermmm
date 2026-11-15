package com.memo494.posetracker.detector

import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.DetectionFrame

/**
 * A [PoseDetector] that replays recorded detections instead of running a model (tests and the debug
 * sheet's replay mode, ARCHITECTURE §6.1).
 *
 * [detect] returns the recorded frames in order and starts over after the last one. The timestamp and its
 * source come from the incoming [CaptureFrame]; the recorded Img size and detections are kept. With no
 * recorded frames every result has the incoming size and no detections.
 *
 * @param frames the recorded frames, for example from `ReplayFixture.parse`; copied on construction.
 * @property inputWidth the model input width to report, in model pixels (Mdl space).
 * @property inputHeight the model input height to report, in model pixels (Mdl space).
 * @property accelerator the backend to report.
 */
class FakePoseDetector(
    frames: List<DetectionFrame>,
    override val inputWidth: Int,
    override val inputHeight: Int,
    override val accelerator: Accelerator,
) : PoseDetector {
    private val frames: List<DetectionFrame> = frames.toList()
    private var nextIndex = 0
    private var closed = false

    /** Always 0: nothing is inferred. */
    override val lastInferenceMs: Float = 0f

    override fun detect(frame: CaptureFrame): DetectionFrame {
        check(!closed) { "FakePoseDetector is closed" }
        if (frames.isEmpty()) {
            return DetectionFrame(
                frame.timestampNs,
                frame.timestampSource,
                frame.widthImg,
                frame.heightImg,
                emptyList(),
            )
        }
        val recorded = frames[nextIndex]
        nextIndex = (nextIndex + 1) % frames.size
        return DetectionFrame(
            frame.timestampNs,
            frame.timestampSource,
            recorded.widthImg,
            recorded.heightImg,
            recorded.detections,
        )
    }

    override fun close() {
        closed = true
    }
}
