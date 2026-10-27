package com.nahla.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class SignatureInkMode {
    ORIGINAL, BLUE, BLACK, NATURAL
}

class SignatureCropView(
    context: Context,
    private val bitmap: Bitmap
) : View(context) {

    private val imageRect = RectF()
    private val cropRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
    }
    private var moveCrop = false
    private var resizeCrop = false
    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        imageRect.set(fitRect())
        if (cropRect.isEmpty) cropRect.set(imageRect)
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint)
        canvas.drawRect(imageRect, shadePaint)
        canvas.save()
        canvas.clipRect(cropRect)
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint)
        canvas.restore()
        canvas.drawRect(cropRect, borderPaint)
        val handle = dp(12f)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handle, handlePaint)
        canvas.drawText("Drag the box or its corner to crop", width / 2f, height - dp(12f), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                resizeCrop = RectF(cropRect.right - dp(28f), cropRect.bottom - dp(28f),
                    cropRect.right + dp(28f), cropRect.bottom + dp(28f)).contains(downX, downY)
                moveCrop = !resizeCrop && cropRect.contains(downX, downY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (moveCrop) {
                    val left = (cropRect.left + dx).coerceIn(imageRect.left, imageRect.right - cropRect.width())
                    val top = (cropRect.top + dy).coerceIn(imageRect.top, imageRect.bottom - cropRect.height())
                    cropRect.offsetTo(left, top)
                    downX = event.x
                    downY = event.y
                    invalidate()
                } else if (resizeCrop) {
                    cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + dp(48f), imageRect.right)
                    cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + dp(48f), imageRect.bottom)
                    downX = event.x
                    downY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moveCrop = false
                resizeCrop = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun croppedBitmap(): Bitmap {
        if (imageRect.isEmpty || cropRect.isEmpty) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Could not copy selected signature photo")
        val left = (((cropRect.left - imageRect.left) / imageRect.width()) * bitmap.width)
            .roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (((cropRect.top - imageRect.top) / imageRect.height()) * bitmap.height)
            .roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (((cropRect.right - imageRect.left) / imageRect.width()) * bitmap.width)
            .roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (((cropRect.bottom - imageRect.top) / imageRect.height()) * bitmap.height)
            .roundToInt().coerceIn(top + 1, bitmap.height)
        val region = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val crop = if (region === bitmap) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Could not copy cropped signature photo")
        } else {
            region
        }
        val maxEdge = 1200
        val longEdge = max(crop.width, crop.height)
        if (longEdge <= maxEdge) return crop
        val scale = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            crop,
            (crop.width * scale).roundToInt().coerceAtLeast(1),
            (crop.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    private fun fitRect(): RectF {
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val fittedWidth = bitmap.width * scale
        val fittedHeight = bitmap.height * scale
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        return RectF(left, top, left + fittedWidth, top + fittedHeight)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

object SignatureExtractionEngine {

    private const val BACKGROUND_TILE_SIZE = 96
    private const val HISTOGRAM_BINS = 256

    fun decode(context: Context, uri: Uri): Bitmap {
        val rawFile = File(context.cacheDir, "sig_raw_import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            rawFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Could not open signature photo")
        val orientation = try {
            ExifInterface(rawFile.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        } catch (e: IllegalArgumentException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(rawFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Unsupported signature photo")
        }
        val sample = max(1, (max(bounds.outWidth, bounds.outHeight) + 1199) / 1200)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeFile(rawFile.absolutePath, options)
            ?: throw IllegalArgumentException("Could not decode signature photo")
        val longEdge = max(decoded.width, decoded.height)
        val capped = if (longEdge <= 1200) decoded else {
            val scale = 1200f / longEdge
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return capped
        val rotated = Bitmap.createBitmap(
            capped, 0, 0, capped.width, capped.height,
            Matrix().apply { postRotate(degrees) }, true
        )
        if (rotated !== capped) capped.recycle()
        return rotated
    }

    fun extract(source: Bitmap, mode: SignatureInkMode, adjustment: Int): Bitmap {
        if (mode == SignatureInkMode.NATURAL) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Could not copy natural signature crop")
        }
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = IntArray(count)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = FloatArray(count) { index ->
            val color = pixels[index]
            0.299f * ((color shr 16) and 0xff) +
                0.587f * ((color shr 8) and 0xff) +
                0.114f * (color and 0xff)
        }
        val gridWidth = max(1, (width + BACKGROUND_TILE_SIZE - 1) / BACKGROUND_TILE_SIZE)
        val gridHeight = max(1, (height + BACKGROUND_TILE_SIZE - 1) / BACKGROUND_TILE_SIZE)
        val background = estimateBackground(luma, width, height, gridWidth, gridHeight)
        val darkness = FloatArray(count)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val paperLevel = sampleGrid(background, gridWidth, gridHeight, x, y, width, height)
                    .coerceAtLeast(1f)
                val value = ((paperLevel - luma[index]) / paperLevel).coerceIn(0f, 1f)
                darkness[index] = value
            }
        }
        val darkest = darkness.maxOrNull() ?: 0f
        val threshold = (0.018f + (adjustment.coerceIn(0, 100) - 50) * 0.00045f)
            .coerceIn(0.003f, 0.08f)
            .let { candidate -> if (darkest > 0f && candidate >= darkest) darkest * 0.45f else candidate }
        val alphaValues = FloatArray(count)
        for (index in pixels.indices) {
            if (darkness[index] > threshold) {
                alphaValues[index] = (
                    ((darkness[index] - threshold) / (1f - threshold) * 2.8f)
                        .coerceIn(0f, 1f).pow(0.45f)
                    )
            }
        }
        val output = IntArray(count)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                var alpha = alphaValues[index]
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            alpha = max(alpha, alphaValues[ny * width + nx] * 0.85f)
                        }
                    }
                }
                if (alpha <= 0f) continue
                val color = when (mode) {
                    SignatureInkMode.BLUE -> 0xFF002FA7.toInt()
                    SignatureInkMode.BLACK -> 0xFF050505.toInt()
                    SignatureInkMode.ORIGINAL -> darkenOriginalInk(pixels[index])
                    SignatureInkMode.NATURAL -> pixels[index]
                }
                output[index] = (alpha * 255f).roundToInt().coerceIn(0, 255) shl 24 or
                    (color and 0x00ffffff)
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    /** Finds ink in the central photo area and returns a padded raw-paper region for preview. */
    fun autoCrop(source: Bitmap): Bitmap {
        val extracted = extract(source, SignatureInkMode.ORIGINAL, 50)
        try {
            val pixels = IntArray(extracted.width * extracted.height)
            extracted.getPixels(pixels, 0, extracted.width, 0, 0, extracted.width, extracted.height)
            var left = extracted.width
            var top = extracted.height
            var right = -1
            var bottom = -1
            val centralLeft = extracted.width * 15 / 200
            val centralTop = extracted.height * 15 / 200
            val centralRight = extracted.width * 185 / 200
            val centralBottom = extracted.height * 185 / 200
            for (y in centralTop until centralBottom) {
                for (x in centralLeft until centralRight) {
                    if (pixels[y * extracted.width + x] ushr 24 > 24) {
                        left = min(left, x)
                        top = min(top, y)
                        right = max(right, x)
                        bottom = max(bottom, y)
                    }
                }
            }
            if (right < left || bottom < top) {
                return source.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Could not copy signature photo")
            }
            val padding = 12
            left = (left - padding).coerceAtLeast(0)
            top = (top - padding).coerceAtLeast(0)
            right = (right + padding + 1).coerceAtMost(source.width)
            bottom = (bottom + padding + 1).coerceAtMost(source.height)
            return Bitmap.createBitmap(source, left, top, right - left, bottom - top).let { crop ->
                if (crop === source) source.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Could not copy signature photo")
                else crop
            }
        } finally {
            extracted.recycle()
        }
    }

    fun trimTransparent(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 > 8) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Could not copy extracted signature")
        }
        val padding = 3
        val cropLeft = (left - padding).coerceAtLeast(0)
        val cropTop = (top - padding).coerceAtLeast(0)
        val cropRight = (right + padding + 1).coerceAtMost(width)
        val cropBottom = (bottom + padding + 1).coerceAtMost(height)
        val trimmed = Bitmap.createBitmap(
            source, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop
        )
        return if (trimmed === source) {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Could not copy extracted signature")
        } else {
            trimmed
        }
    }

    private fun darkenOriginalInk(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        fun darken(channel: Int): Int {
            val contrasted = (((channel / 255f - 0.5f) * 1.25f) + 0.5f).coerceIn(0f, 1f)
            return (contrasted * 0.55f * 255f).roundToInt()
        }
        return (darken(r) shl 16) or (darken(g) shl 8) or darken(b)
    }

    private fun estimateBackground(
        luma: FloatArray,
        width: Int,
        height: Int,
        gridWidth: Int,
        gridHeight: Int
    ): FloatArray {
        val grid = FloatArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) {
            val top = gy * BACKGROUND_TILE_SIZE
            val bottom = min(height, top + BACKGROUND_TILE_SIZE)
            for (gx in 0 until gridWidth) {
                val left = gx * BACKGROUND_TILE_SIZE
                val right = min(width, left + BACKGROUND_TILE_SIZE)
                val histogram = IntArray(HISTOGRAM_BINS)
                var sampleCount = 0
                for (y in top until bottom) {
                    for (x in left until right) {
                        histogram[luma[y * width + x].roundToInt().coerceIn(0, 255)]++
                        sampleCount++
                    }
                }
                val percentileTarget = (sampleCount * 0.8f).roundToInt().coerceAtLeast(1)
                var cumulative = 0
                var percentile = 255
                for (value in histogram.indices) {
                    cumulative += histogram[value]
                    if (cumulative >= percentileTarget) {
                        percentile = value
                        break
                    }
                }
                grid[gy * gridWidth + gx] = percentile.toFloat()
            }
        }
        return boxBlur(grid, gridWidth, gridHeight, 2)
    }

    private fun boxBlur(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horizontal = FloatArray(values.size)
        val blurred = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (offset in -radius..radius) {
                    sum += values[y * width + (x + offset).coerceIn(0, width - 1)]
                    count++
                }
                horizontal[y * width + x] = sum / count
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (offset in -radius..radius) {
                    sum += horizontal[(y + offset).coerceIn(0, height - 1) * width + x]
                    count++
                }
                blurred[y * width + x] = sum / count
            }
        }
        return blurred
    }

    private fun sampleGrid(
        grid: FloatArray,
        gridWidth: Int,
        gridHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Float {
        val gx = x.toFloat() * (gridWidth - 1) / max(1, width - 1)
        val gy = y.toFloat() * (gridHeight - 1) / max(1, height - 1)
        val x0 = gx.toInt().coerceIn(0, gridWidth - 1)
        val y0 = gy.toInt().coerceIn(0, gridHeight - 1)
        val x1 = min(x0 + 1, gridWidth - 1)
        val y1 = min(y0 + 1, gridHeight - 1)
        val top = grid[y0 * gridWidth + x0] +
            (grid[y0 * gridWidth + x1] - grid[y0 * gridWidth + x0]) * (gx - x0)
        val bottom = grid[y1 * gridWidth + x0] +
            (grid[y1 * gridWidth + x1] - grid[y1 * gridWidth + x0]) * (gx - x0)
        return top + (bottom - top) * (gy - y0)
    }

}
