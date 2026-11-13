package com.memo494.posetracker.capture

import android.media.Image
import java.nio.ByteBuffer

/**
 * [FrameImage] over an `android.media.Image` with `RGBA_8888` content. One instance is reused for every
 * frame: [attach] points it at the next image, [close] hands that image back and drops every reference.
 * Used on the capture thread only.
 */
internal class ImageFrame : FrameImage {
    private var image: Image? = null
    private var buffer: ByteBuffer? = null

    override var widthImg: Int = 0
        private set

    override var heightImg: Int = 0
        private set

    override var timestampNs: Long = 0L
        private set

    override var rowStride: Int = 0
        private set

    override var pixelStride: Int = 0
        private set

    override val pixels: ByteBuffer
        get() = checkNotNull(buffer) { "no image attached" }

    /**
     * Points this frame at [image]. Reads `image.planes` once (the platform clones the array on every
     * call) and keeps plane 0. Nothing is kept when it throws; the caller then closes [image] itself.
     */
    fun attach(image: Image) {
        val plane = image.planes[0]
        buffer = plane.buffer
        rowStride = plane.rowStride
        pixelStride = plane.pixelStride
        widthImg = image.width
        heightImg = image.height
        timestampNs = image.timestamp
        this.image = image
    }

    override fun close() {
        val current = image ?: return
        image = null
        buffer = null
        current.close()
    }
}
