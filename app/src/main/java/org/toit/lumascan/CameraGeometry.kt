// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import kotlin.math.abs
import kotlin.math.min

/** Geometry shared by camera discovery, stream choice, and the uncropped viewfinder. */
internal object CameraGeometry {
    data class Dimensions(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
    }
    data class Viewport(val naturalWidth: Float, val naturalHeight: Float, val scale: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun sameAspect(a: Dimensions, b: Dimensions) =
        a.width.toLong() * b.height == b.width.toLong() * a.height

    fun streams(yuv: List<Dimensions>, preview: List<Dimensions>, maxPixels: Long): Pair<Dimensions, Dimensions> {
        fun bounded(size: Dimensions, pixels: Long) = size.width > 0 && size.height > 0 &&
            size.pixels <= pixels && size.width <= 8192 && size.height <= 8192
        val previews = preview.filter { bounded(it, 2_100_000L) }
        val sources = yuv.filter { bounded(it, maxPixels) && previews.any { p -> sameAspect(it, p) } }
        require(sources.isNotEmpty()) { "No matching preview and YUV aspect ratio within capture limits" }
        val fourThree = sources.filter { abs(it.width.toDouble() / it.height - 4.0 / 3.0) < .025 }
        val source = (fourThree.ifEmpty { sources }).maxBy { it.pixels }
        return source to previews.filter { sameAspect(source, it) }.maxBy { it.pixels }
    }

    /** TextureView already rotates the sensor buffer; the matrix adds only display compensation. */
    fun fitCenter(viewWidth: Int, viewHeight: Int, buffer: Dimensions, sensorDegrees: Int, displayDegrees: Int): Viewport {
        require(viewWidth > 0 && viewHeight > 0 && buffer.width > 0 && buffer.height > 0)
        require(sensorDegrees in listOf(0, 90, 180, 270) && displayDegrees in listOf(0, 90, 180, 270))
        val sensorSwapped = sensorDegrees == 90 || sensorDegrees == 270
        val naturalWidth = (if (sensorSwapped) buffer.height else buffer.width).toFloat()
        val naturalHeight = (if (sensorSwapped) buffer.width else buffer.height).toFloat()
        val displaySwapped = displayDegrees == 90 || displayDegrees == 270
        val width = if (displaySwapped) naturalHeight else naturalWidth
        val height = if (displaySwapped) naturalWidth else naturalHeight
        val scale = min(viewWidth / width, viewHeight / height)
        val left = (viewWidth - width * scale) / 2f
        val top = (viewHeight - height * scale) / 2f
        return Viewport(naturalWidth, naturalHeight, scale, left, top, left + width * scale, top + height * scale)
    }

    fun lensType(equivalentFocalMm: Double?) = when {
        equivalentFocalMm == null || !equivalentFocalMm.isFinite() -> "Rear camera"
        equivalentFocalMm < 22 -> "Ultra-wide"
        equivalentFocalMm > 40 -> "Telephoto"
        else -> "Wide"
    }
}
