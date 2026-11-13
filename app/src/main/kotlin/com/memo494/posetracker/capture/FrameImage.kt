package com.memo494.posetracker.capture

import java.nio.ByteBuffer

/**
 * One captured screen image as the [FramePipeline] reads it. A seam for `android.media.Image`: the
 * implementation wraps plane 0 of an `RGBA_8888` image, and [timestampNs] is `Image.timestamp`. An
 * implementation may be one object reused for every frame; the pipeline reads it only between the
 * frame's arrival and its own [close] call and never keeps a reference afterwards.
 */
internal interface FrameImage {
    /** Width of the image content, Img pixels. */
    val widthImg: Int

    /** Height of the image content, Img pixels. */
    val heightImg: Int

    /** Capture time stamped by the platform, nanoseconds; the pipeline checks it with `FrameTimestamp`. */
    val timestampNs: Long

    /** RGBA pixels of plane 0; the pipeline reads it relative to its position and restores the position. */
    val pixels: ByteBuffer

    /** Distance between the starts of two rows in [pixels], in bytes (at least one row of pixels). */
    val rowStride: Int

    /** Distance between two pixels in [pixels], in bytes; 4 for RGBA_8888. */
    val pixelStride: Int

    /** Hands the image back to its reader; the pipeline calls it exactly once per frame. */
    fun close()
}
