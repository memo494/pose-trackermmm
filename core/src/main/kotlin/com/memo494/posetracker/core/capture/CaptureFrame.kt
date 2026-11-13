package com.memo494.posetracker.core.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.TimestampSource

/**
 * One captured frame as the detector reads it: a tight RGBA_8888 pixel array (from [RowCopy], no row
 * padding) plus its size and capture time.
 *
 * Ownership: the capture thread owns the instance and the array, refills it with [set] for every
 * frame and reuses both across frames, so nothing is allocated per frame. A detector must read the
 * frame only during `detect` and must not keep the frame or its array after `detect` returns, because
 * the capture thread overwrites them for the next frame. Not thread-safe: all calls must come from
 * one thread at a time (the capture thread, or the thread it hands the frame to for the duration of
 * one `detect` call).
 *
 * A new frame is empty: a zero-length [rgba] and size 0.
 *
 * @property rgba pixels, 4 bytes (R, G, B, A) per pixel, row-major, exactly
 *   `widthImg * heightImg * 4` bytes once a frame was accepted by [set]; the array is not copied.
 * @property widthImg width of the Img space (capture content rect), pixels.
 * @property heightImg height of the Img space, pixels.
 * @property timestampNs capture time, System.nanoTime() timebase, nanoseconds (ARCHITECTURE §4.1).
 * @property timestampSource whether [timestampNs] is the image stamp or the arrival fallback; an empty
 *   frame reports [TimestampSource.ARRIVAL].
 */
class CaptureFrame {
    var rgba: ByteArray = ByteArray(0)
        private set

    var widthImg: Int = 0
        private set

    var heightImg: Int = 0
        private set

    var timestampNs: Long = 0L
        private set

    var timestampSource: TimestampSource = TimestampSource.ARRIVAL
        private set

    /**
     * Stores the next frame; [rgba] is kept by reference, never copied.
     *
     * Fails closed: returns `false`, and changes nothing, unless [widthImg] and [heightImg] are
     * positive and [rgba] is exactly `widthImg * heightImg * 4` bytes. The size is compared without
     * Int overflow, so a huge size can never wrap around to match a small array.
     */
    fun set(
        rgba: ByteArray,
        widthImg: Int,
        heightImg: Int,
        timestampNs: Long,
        timestampSource: TimestampSource,
    ): Boolean {
        if (widthImg <= 0 || heightImg <= 0) return false
        val pixels = widthImg.toLong() * heightImg
        if (pixels > Int.MAX_VALUE / TrackerConfig.RGBA_BYTES_PER_PIXEL) return false
        if (rgba.size != pixels.toInt() * TrackerConfig.RGBA_BYTES_PER_PIXEL) return false
        this.rgba = rgba
        this.widthImg = widthImg
        this.heightImg = heightImg
        this.timestampNs = timestampNs
        this.timestampSource = timestampSource
        return true
    }
}
