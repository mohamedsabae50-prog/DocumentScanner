package com.nahla.docscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bitmap pipeline for the scanner.
 *
 * Decoding is sub-sampled plus one bilinear rescale so the longest edge lands on
 * at most [MAX_EDGE], which is 2560 px - roughly 300 DPI across an A4 page, so
 * small body text stays legible. Perspective crops and retained pages use
 * ARGB_8888 and preserve the camera's natural colors and lighting.
 *
 * Ownership contract: every function either returns a new bitmap or documents
 * that it recycles its input. Crops always allocate a fresh bitmap rather than
 * `Bitmap.createBitmap(src, rect)`, which aliases the source pixels.
 */
object ImageProcessor {

    /** Hard ceiling for any working bitmap: 2560 x 2560 x 4 B. */
    const val MAX_EDGE = 2560

    /** Ceiling used when a page is drawn into the PDF. */
    const val PAGE_EDGE = MAX_EDGE

    /** List thumbnail. */
    const val THUMB_EDGE = 256

    private const val MIN_OUTPUT_EDGE = 320
    private const val MIN_DEGENERATE_EDGE = 24

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes [file] so its longest edge is at most [maxEdge], and applies EXIF
     * rotation so callers always get an upright bitmap.
     *
     * Two steps, because `inSampleSize` can only halve: decode at the smallest
     * power-of-two step that still carries >= [maxEdge] pixels of detail, then a
     * single bilinear rescale trims the remainder.
     */
    fun decode(file: File, maxEdge: Int = MAX_EDGE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported or corrupt image: ${file.name}")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Failed to decode image: ${file.name}")

        return capLongEdge(rotateByExif(decoded, file), maxEdge)
    }

    /**
     * Largest power-of-two `inSampleSize` that still leaves at least [maxEdge]
     * pixels on the long edge - the cheapest decode that loses no real detail.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longEdge = max(width, height)
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    private fun rotateByExif(src: Bitmap, file: File): Bitmap {
        val degrees = try {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: IOException) {
            0f
        } catch (_: IllegalArgumentException) {
            0f
        }
        if (degrees == 0f) return src
        return rotate(src, degrees.toInt())
    }

    /** Rotates in place-of-copy. Consumes [src]: it is recycled unless returned. */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    /**
     * Rescales [src] so its longest edge is exactly [maxEdge], preserving aspect
     * ratio. Consumes [src].
     */
    private fun capLongEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src

        val scale = maxEdge.toFloat() / longEdge
        val width = (src.width * scale).roundToInt().coerceAtLeast(1)
        val height = (src.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, width, height, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /** Always returns a NEW bitmap and never recycles [src]. */
    fun copyScaled(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return copy(src)
        return Bitmap.createScaledBitmap(src, scaleWidth(src.width, longEdge, maxEdge),
            scaleHeight(src.height, longEdge, maxEdge), true)
    }

    private fun copy(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, resamplePaint())
        return out
    }

    private fun scaleWidth(width: Int, longEdge: Int, maxEdge: Int): Int =
        (width * (maxEdge.toFloat() / longEdge)).roundToInt().coerceAtLeast(1)

    private fun scaleHeight(height: Int, longEdge: Int, maxEdge: Int): Int =
        (height * (maxEdge.toFloat() / longEdge)).roundToInt().coerceAtLeast(1)

    // ---------------------------------------------------------------- perspective

    /**
     * Rectifies a 4-point quad (TL, TR, BR, BL) into an upright page bitmap.
     *
     * [quad] holds 8 normalised floats: x0,y0,x1,y1,x2,y2,x3,y3 with values in
     * 0..1 relative to the source bitmap. Uses the framework's own
     * `Matrix.setPolyToPoly`, a native perspective transform - no OpenCV, no JNI.
     *
     * Falls back to an axis-aligned bounding-box crop whenever the quad is
     * degenerate, so a bad drag can never produce a garbage or black page.
     */
    fun crop(src: Bitmap, quad: FloatArray): Bitmap {
        require(quad.size == 8) { "quad must hold 8 floats" }

        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val points = FloatArray(8) { i -> quad[i] * if (i % 2 == 0) w else h }

        val outWidth = outputEdge(distance(points, 0, 2), distance(points, 6, 4), src.width)
        val outHeight = outputEdge(distance(points, 0, 6), distance(points, 2, 4), src.height)

        if (outWidth > MIN_DEGENERATE_EDGE && outHeight > MIN_DEGENERATE_EDGE &&
            polygonArea(points) >= src.width * src.height * 0.02f
        ) {
            val inset = 1f
            val dst = floatArrayOf(
                inset, inset,
                outWidth - 1f - inset, inset,
                outWidth - 1f - inset, outHeight - 1f - inset,
                inset, outHeight - 1f - inset
            )
            val matrix = Matrix()
            if (matrix.setPolyToPoly(points, 0, dst, 0, 4) &&
                mapsInto(matrix, points, outWidth, outHeight)
            ) {
                val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(src, matrix, warpPaint())
                return out
            }
        }
        return cropAxisAligned(src, points)
    }

    /** Rotates a normalised quad the same way [rotate] rotates its bitmap. */
    fun rotateNormalizedQuad(quad: FloatArray, degrees: Int): FloatArray {
        val out = FloatArray(8)
        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0) {
            System.arraycopy(quad, 0, out, 0, 8)
            return out
        }
        for (i in 0 until 4) {
            val x = quad[i * 2]
            val y = quad[i * 2 + 1]
            when (turn) {
                90 -> { out[i * 2] = 1f - y; out[i * 2 + 1] = x }
                180 -> { out[i * 2] = 1f - x; out[i * 2 + 1] = 1f - y }
                270 -> { out[i * 2] = y; out[i * 2 + 1] = 1f - x }
            }
        }
        return out
    }

    private fun outputEdge(edgeA: Float, edgeB: Float, sourceDim: Int): Int {
        val mean = ((edgeA + edgeB) / 2f).roundToInt()
        return mean.coerceIn(
            min(MIN_OUTPUT_EDGE, sourceDim),
            max(1, min(MAX_EDGE, sourceDim))
        )
    }

    /** Verifies the perspective matrix really lands the quad on the output canvas. */
    private fun mapsInto(matrix: Matrix, src: FloatArray, outWidth: Int, outHeight: Int): Boolean {
        val mapped = FloatArray(8)
        matrix.mapPoints(mapped, src)
        val toleranceX = outWidth * 0.05f + 2f
        val toleranceY = outHeight * 0.05f + 2f
        for (i in 0 until 4) {
            val x = mapped[i * 2]
            val y = mapped[i * 2 + 1]
            if (!x.isFinite() || !y.isFinite()) return false
            if (abs(x) > toleranceX || abs(x - (outWidth - 1)) > toleranceX) return false
            if (abs(y) > toleranceY || abs(y - (outHeight - 1)) > toleranceY) return false
        }
        return true
    }

    private fun cropAxisAligned(src: Bitmap, points: FloatArray): Bitmap {
        val left = min(min(points[0], points[2]), min(points[4], points[6])).toInt()
            .coerceIn(0, (src.width - 1).coerceAtLeast(0))
        val top = min(min(points[1], points[3]), min(points[5], points[7])).toInt()
            .coerceIn(0, (src.height - 1).coerceAtLeast(0))
        val right = max(max(points[0], points[2]), max(points[4], points[6])).toInt()
            .coerceIn(left + 1, src.width)
        val bottom = max(max(points[1], points[3]), max(points[5], points[7])).toInt()
            .coerceIn(top + 1, src.height)

        val out = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, -left.toFloat(), -top.toFloat(), warpPaint())
        return out
    }

    private fun distance(p: FloatArray, a: Int, b: Int): Float {
        val dx = p[a] - p[b]
        val dy = p[a + 1] - p[b + 1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun polygonArea(p: FloatArray): Float {
        var area = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return abs(area) / 2f
    }

    // -------------------------------------------------------------------- output

    fun writePng(bitmap: Bitmap, target: File): File {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("PNG encoding failed for ${target.name}")
            }
        }
        return target
    }

    private fun warpPaint(): Paint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
    )

    private fun resamplePaint(): Paint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
    )

}