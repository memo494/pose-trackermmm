package com.memo494.posetracker.core.capture

import com.memo494.posetracker.core.TrackerConfig
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Copies an `ImageReader` RGBA_8888 plane (with `rowStride` padding) into a tight, caller-owned,
 * reused `ByteArray` of exactly `width * height * 4` bytes, so the detector can read an exact-width
 * source (Consult C1-E). No per-call allocation: the caller passes the destination and only calls
 * [ensureBuffer] when the frame size changes.
 */
object RowCopy {
    /**
     * Returns [current] if it already is exactly `widthImg * heightImg * 4` bytes, else a new array of
     * that size (allocation happens only when the capture size changes, e.g. on rotation).
     * Throws `IllegalArgumentException` for a non-positive size; callers validate sizes first.
     */
    fun ensureBuffer(
        current: ByteArray?,
        widthImg: Int,
        heightImg: Int,
    ): ByteArray {
        require(widthImg > 0 && heightImg > 0) { "size ${widthImg}x$heightImg" }
        val size = Math.multiplyExact(Math.multiplyExact(widthImg, heightImg), TrackerConfig.RGBA_BYTES_PER_PIXEL)
        return if (current != null && current.size == size) current else ByteArray(size)
    }

    /**
     * Copies [widthImg] x [heightImg] RGBA pixels from [source] into [destination], dropping the row
     * padding. Row `r` starts at `source.position() + r * rowStride`; only `widthImg * pixelStride`
     * bytes of each row are read, so the last row may be shorter than [rowStride] (Android only
     * guarantees `rowStride * (heightImg - 1) + widthImg * pixelStride` bytes).
     *
     * Fails closed: returns `false`, and writes nothing, when the size is not positive, [pixelStride]
     * is not [TrackerConfig.RGBA_BYTES_PER_PIXEL], [rowStride] is smaller than one row of pixels,
     * [destination] is not exactly `widthImg * heightImg * 4` bytes, or [source] has fewer bytes than
     * the last row needs.
     *
     * Buffer behaviour: reads are relative `get`s, so [source] position moves during the copy and is
     * restored to its entry value before returning (also on exceptions); the limit and contents are
     * unchanged. Not thread-safe on the same buffer.
     */
    fun copyRgba(
        source: ByteBuffer,
        widthImg: Int,
        heightImg: Int,
        rowStride: Int,
        pixelStride: Int,
        destination: ByteArray,
    ): Boolean {
        if (widthImg <= 0 || heightImg <= 0 || pixelStride != TrackerConfig.RGBA_BYTES_PER_PIXEL) return false
        val rowBytes = widthImg.toLong() * pixelStride
        if (rowStride < rowBytes) return false
        if (rowBytes * heightImg != destination.size.toLong()) return false
        val start = source.position()
        val lastRowStart = start.toLong() + rowStride.toLong() * (heightImg - 1)
        if (lastRowStart + rowBytes > source.limit()) return false
        // Position moves through Buffer so the call binds to Buffer.position(int) on every JDK/ART level.
        val cursor: Buffer = source
        try {
            for (row in 0 until heightImg) {
                cursor.position(start + row * rowStride)
                source.get(destination, row * rowBytes.toInt(), rowBytes.toInt())
            }
        } finally {
            cursor.position(start)
        }
        return true
    }
}
