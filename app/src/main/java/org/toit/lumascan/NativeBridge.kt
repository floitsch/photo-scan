// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Native reconstruction state. Call all methods on the same serialized worker. */
object NativeBridge {
    init { System.loadLibrary("lumascan") }

    /** Dedicated cache directory for accepted full-resolution frames; configuration resets capture. */
    external fun configureStorage(directory: String)
    external fun reset()
    /** Report: status, count, inliers, occupied center phase bins / 16, sharpness, RMS, 9 homography entries. */
    external fun addYuv(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int,
        yStride: Int, uStride: Int, vStride: Int,
        uPixel: Int, vPixel: Int,
    ): DoubleArray
    external fun addBitmap(bitmap: Bitmap): DoubleArray
    /** Schema 1 JSON for the latest accepted/rejected attempt; unmeasured numbers are null. */
    external fun lastAttemptDiagnostics(): String
    /** Latest attempted source in sensor orientation, at most 960 pixels per side. */
    external fun diagnosticThumbnail(): Bitmap
    external fun frameBitmap(): Bitmap
    /** Streams the latest accepted sensor RGB directly to a lossless PNG. */
    external fun exportLastSource(pngPath: String)
    /** Bounded preview: at most 640 pixels on the longest side. */
    external fun render(scale: Int, maxSide: Int): Bitmap
    /** Streams final RGB PNG and editable raster; returns width, height, support, clipped, rejected. */
    external fun renderToFiles(pngPath: String, rasterPath: String, scale: Int, maxSide: Int): DoubleArray
    /** Source-normalized corners in desired output TL/TR/BR/BL order; ratio zero derives the shape. */
    external fun exportRaster(rasterPath: String, pngPath: String, corners: FloatArray, ratio: Float, gains: FloatArray): IntArray
    /** Sequential opaque RGB strips; top zero starts a raster, totalHeight stays fixed. */
    external fun writeRasterRows(path: String, bitmap: Bitmap, top: Int, totalHeight: Int)
    /** Last render: fraction supported by 2+ frames, clipped pixels, temporally inconsistent observations. */
    external fun metrics(): DoubleArray
    external fun corners(bitmap: Bitmap): FloatArray
    /** Schema 1 JSON for the latest corner proposal/fallback, in that input bitmap's coordinates. */
    external fun lastCornerDiagnostics(): String
}
