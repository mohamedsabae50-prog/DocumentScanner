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
import kotlin.math.roundToInt

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

    private const val THRESHOLD_HISTOGRAM_BINS = 1024

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

    fun extract(source: Bitmap, inkBoldness: Int): Bitmap {
        val width = source.width
        val height = source.height
        val count = width * height
        val integralWidth = width + 1
        val pixels = IntArray(count)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val originalLuma = FloatArray(count) { index ->
            val color = pixels[index]
            0.299f * ((color shr 16) and 0xff) +
                0.587f * ((color shr 8) and 0xff) +
                0.114f * (color and 0xff)
        }
        val luma = FloatArray(count)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (dy in -1..1) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val wy = if (dy == 0) 2f else 1f
                    for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val wx = if (dx == 0) 2f else 1f
                        sum += originalLuma[sy * width + sx] * wx * wy
                    }
                }
                luma[y * width + x] = sum / 16f
            }
        }

        val integral = DoubleArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            for (x in 0 until width) {
                rowSum += luma[y * width + x]
                integral[(y + 1) * integralWidth + x + 1] =
                    integral[y * integralWidth + x + 1] + rowSum
            }
        }

        val radius = (max(width, height) / 16).coerceAtLeast(1)
        val darkness = FloatArray(count)
        val histogram = IntArray(THRESHOLD_HISTOGRAM_BINS)
        var sampleCount = 0
        val frameX = width * 6 / 100
        val frameY = height * 6 / 100
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val left = (x - radius).coerceAtLeast(0)
                val top = (y - radius).coerceAtLeast(0)
                val right = (x + radius + 1).coerceAtMost(width)
                val bottom = (y + radius + 1).coerceAtMost(height)
                val sum = integral[bottom * integralWidth + right] -
                    integral[top * integralWidth + right] -
                    integral[bottom * integralWidth + left] +
                    integral[top * integralWidth + left]
                val area = (right - left) * (bottom - top)
                val mean = (sum / area).toFloat()
                val relativeDarkness = ((mean - luma[index]) / max(mean, 35f))
                    .coerceIn(0f, 1f)
                darkness[index] = relativeDarkness
                if (x >= frameX && x < width - frameX &&
                    y >= frameY && y < height - frameY
                ) {
                    val bin = (relativeDarkness * (THRESHOLD_HISTOGRAM_BINS - 1))
                        .toInt().coerceIn(0, THRESHOLD_HISTOGRAM_BINS - 1)
                    histogram[bin]++
                    sampleCount++
                }
            }
        }
        val percentileTarget = (sampleCount * 0.88f).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        var baselineBin = 0
        for (bin in histogram.indices) {
            cumulative += histogram[bin]
            if (cumulative >= percentileTarget) {
                baselineBin = bin
                break
            }
        }
        val baselineThreshold = baselineBin.toFloat() / (THRESHOLD_HISTOGRAM_BINS - 1)
        val boldnessFactor = 1.45f - inkBoldness.coerceIn(0, 100) / 100f * 1.1f
        val threshold = (baselineThreshold * boldnessFactor).coerceIn(0.008f, 0.22f)
        val edgeRamp = max(threshold * 2.2f, 0.025f)
        val alpha = FloatArray(count)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (x < frameX || x >= width - frameX ||
                    y < frameY || y >= height - frameY
                ) continue
                val amount = ((darkness[index] - threshold) / edgeRamp).coerceIn(0f, 1f)
                alpha[index] = amount * amount * (3f - 2f * amount)
            }
        }

        val inkMask = BooleanArray(count) { alpha[it] >= 0.08f }
        val dilated = BooleanArray(count)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (inkMask[(y + dy) * width + x + dx]) dilated[index] = true
                    }
                }
            }
        }
        val closed = BooleanArray(count)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var filled = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (!dilated[(y + dy) * width + x + dx]) filled = false
                    }
                }
                closed[y * width + x] = filled
            }
        }

        val visited = BooleanArray(count)
        val queue = IntArray(count)
        val majorInk = BooleanArray(count)
        val minorInk = BooleanArray(count)
        var majorLeft = width
        var majorTop = height
        var majorRight = -1
        var majorBottom = -1
        for (start in closed.indices) {
            if (!closed[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (closed[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }
            val component = if (tail >= 3) majorInk else minorInk
            for (i in 0 until tail) component[queue[i]] = true
            if (tail >= 3) {
                majorLeft = min(majorLeft, left)
                majorTop = min(majorTop, top)
                majorRight = max(majorRight, right)
                majorBottom = max(majorBottom, bottom)
            }
        }

        val output = IntArray(count)
        val keepPadding = max(6, max(width, height) / 50)
        val hasMajorInk = majorRight >= majorLeft && majorBottom >= majorTop
        for (index in output.indices) {
            val x = index % width
            val y = index / width
            val preserveSmall = !hasMajorInk ||
                (x in majorLeft - keepPadding..majorRight + keepPadding &&
                    y in majorTop - keepPadding..majorBottom + keepPadding)
            if (!majorInk[index] && !(minorInk[index] && preserveSmall)) continue
            var opacity = alpha[index]
            if (opacity <= 0f) {
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            opacity = max(opacity, alpha[ny * width + nx] * 0.75f)
                        }
                    }
                }
            }
            if (opacity > 0f) {
                output[index] = (opacity * 255f).roundToInt().coerceIn(1, 255) shl 24
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
    }

    /** Finds ink in the central photo area and returns a padded raw-paper region for preview. */
    fun autoCrop(source: Bitmap): Bitmap {
        val extracted = extract(source, 50)
        try {
            val pixels = IntArray(extracted.width * extracted.height)
            extracted.getPixels(pixels, 0, extracted.width, 0, 0, extracted.width, extracted.height)
            var left = extracted.width
            var top = extracted.height
            var right = -1
            var bottom = -1
            for (y in 0 until extracted.height) {
                for (x in 0 until extracted.width) {
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
            val padding = max(8, max(source.width, source.height) / 100)
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

}
