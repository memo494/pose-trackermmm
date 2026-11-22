package com.memo494.posetracker.core.modelimport

/**
 * How an imported model lays out its one input tensor. Decided from the input shape alone; width and height always
 * mean the real image width `W` and height `H`, whichever axes the tensor stores them on.
 */
enum class InputLayout {
    /** `[1, 3, H, W]`: channels first (litert-torch and current Ultralytics exports); height is axis 2, width axis 3. */
    NCHW,

    /** `[1, H, W, 3]`: channels last (older Ultralytics onnx2tf TFLite exports); height is axis 1, width axis 2. */
    NHWC,
}
