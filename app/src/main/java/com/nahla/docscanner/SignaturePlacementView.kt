package com.nahla.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class SignaturePlacement(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.78f,
    val width: Float = 0.32f,
    val rotationDegrees: Float = 0f
)

data class SignatureLayer(
    val bitmap: Bitmap,
    var placement: SignaturePlacement = SignaturePlacement()
)

class SignaturePlacementView(
    context: Context,
    private val page: Bitmap,
    initialLayers: List<SignatureLayer>
) : View(context) {

    private val pageRect = RectF()
    private val signatureRect = RectF()
    private val layers = initialLayers.toMutableList()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
    }

    private fun containsLayerPoint(layer: SignatureLayer, x: Float, y: Float): Boolean {
        val rect = layerRect(layer)
        val local = toLocalPoint(x, y, rect, layer.placement.rotationDegrees)
        return rect.contains(local.first, local.second)
    }

    private fun toLocalPoint(x: Float, y: Float): Pair<Float, Float> =
        toLocalPoint(
            x, y, signatureRect,
            layers.getOrNull(selectedIndex)?.placement?.rotationDegrees ?: 0f
        )

    private fun toLocalPoint(x: Float, y: Float, rect: RectF, degrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(-degrees.toDouble())
        val dx = x - rect.centerX()
        val dy = y - rect.centerY()
        return Pair(
            rect.centerX() + (dx * kotlin.math.cos(radians) - dy * kotlin.math.sin(radians)).toFloat(),
            rect.centerY() + (dx * kotlin.math.sin(radians) + dy * kotlin.math.cos(radians)).toFloat()
        )
    }

    private fun angleAroundCenter(x: Float, y: Float): Float =
        Math.toDegrees(kotlin.math.atan2(
            (y - signatureRect.centerY()).toDouble(),
            (x - signatureRect.centerX()).toDouble()
        )).toFloat()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 37, 26)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 37, 26)
        textSize = dp(17f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(16f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
    }

    private var selectedIndex = layers.lastIndex
    private var centerX = layers.getOrNull(selectedIndex)?.placement?.centerX ?: 0.5f
    private var centerY = layers.getOrNull(selectedIndex)?.placement?.centerY ?: 0.78f
    private var signatureWidth = layers.getOrNull(selectedIndex)?.placement?.width ?: 0.32f
    private var rotationDegrees = layers.getOrNull(selectedIndex)?.placement?.rotationDegrees ?: 0f
    private var startDistance = 0f
    private var startWidth = signatureWidth
    private var resizeStartX = 0f
    private var resizeStartWidth = signatureWidth
    private var activePointer = -1
    private var dragging = false
    private var resizing = false
    private var rotating = false
    private var rotationStartAngle = 0f
    private var rotationStartDegrees = 0f
    val signatureLayers: List<SignatureLayer>
        get() = layers.toList()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        pageRect.set(fitRect(page.width, page.height))
        canvas.drawBitmap(page, null, pageRect, bitmapPaint)
        layers.forEachIndexed { index, layer ->
            val rect = layerRect(layer)
            canvas.save()
            canvas.rotate(layer.placement.rotationDegrees, rect.centerX(), rect.centerY())
            canvas.drawBitmap(layer.bitmap, null, rect, bitmapPaint)
            if (index == selectedIndex) {
                signatureRect.set(rect)
                canvas.drawRect(rect, borderPaint)
                val size = dp(22f)
                canvas.drawCircle(rect.left, rect.top, size / 2f, handlePaint)
                canvas.drawText("×", rect.left, rect.top - xPaint.fontMetrics.ascent / 2f, xPaint)
                canvas.drawCircle(rect.right, rect.bottom, dp(9f), handlePaint)
                canvas.drawCircle(rect.right, rect.bottom, dp(9f), handleBorderPaint)
                canvas.drawCircle(rect.right, rect.top, dp(9f), handlePaint)
                canvas.drawCircle(rect.right, rect.top, dp(9f), handleBorderPaint)
            }
            canvas.restore()
        }
        if (layers.isEmpty()) {
            canvas.drawText("Choose or draw a signature", width / 2f, height / 2f, hintPaint)
        }
        saveSelectedPlacement()
    }

    private fun layerRect(layer: SignatureLayer): RectF {
        val widthPx = pageRect.width() * layer.placement.width
        val heightPx = widthPx * layer.bitmap.height / layer.bitmap.width
        val centerX = pageRect.left + layer.placement.centerX * pageRect.width()
        val centerY = pageRect.top + layer.placement.centerY * pageRect.height()
        return RectF(centerX - widthPx / 2f, centerY - heightPx / 2f,
            centerX + widthPx / 2f, centerY + heightPx / 2f)
    }

    private fun saveSelectedPlacement() {
        val layer = layers.getOrNull(selectedIndex) ?: return
        layer.placement = SignaturePlacement(centerX, centerY, signatureWidth, rotationDegrees)
    }

    private fun select(index: Int) {
        saveSelectedPlacement()
        selectedIndex = index
        val placement = layers.getOrNull(index)?.placement ?: return
        centerX = placement.centerX
        centerY = placement.centerY
        signatureWidth = placement.width
        rotationDegrees = placement.rotationDegrees
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (width <= 0 || height <= 0) return false
                activePointer = event.getPointerId(0)
                val x = event.x
                val y = event.y
                val hit = layers.indices.reversed().firstOrNull {
                    containsLayerPoint(layers[it], x, y)
                }
                        if (hit != null && hit != selectedIndex) {
                            select(hit)
                            signatureRect.set(layerRect(layers[hit]))
                        }
                if (layers.isEmpty()) return true
                val deleteSize = dp(26f)
                val local = toLocalPoint(x, y)
                if (local.first >= signatureRect.left - deleteSize && local.first <= signatureRect.left + deleteSize &&
                    local.second >= signatureRect.top - deleteSize && local.second <= signatureRect.top + deleteSize
                ) {
                    saveSelectedPlacement()
                    layers.removeAt(selectedIndex)
                    selectedIndex = layers.lastIndex
                    layers.getOrNull(selectedIndex)?.placement?.let {
                        centerX = it.centerX
                        centerY = it.centerY
                        signatureWidth = it.width
                        rotationDegrees = it.rotationDegrees
                    }
                    invalidate()
                    return true
                }
                rotating = hypot(local.first - signatureRect.right, local.second - signatureRect.top) <= dp(24f)
                resizing = !rotating &&
                    hypot(local.first - signatureRect.right, local.second - signatureRect.bottom) <= dp(24f)
                dragging = !rotating && !resizing && signatureRect.contains(local.first, local.second)
                resizeStartX = local.first
                resizeStartWidth = signatureWidth
                rotationStartAngle = angleAroundCenter(x, y)
                rotationStartDegrees = rotationDegrees
                startDistance = 0f
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    startDistance = pointerDistance(event)
                    startWidth = signatureWidth
                    resizing = true
                    dragging = false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (layers.isEmpty()) return true
                saveSelectedPlacement()
                if (event.pointerCount >= 2 && startDistance > 0f) {
                    signatureWidth = (startWidth * pointerDistance(event) / startDistance)
                        .coerceIn(0.08f, 0.8f)
                    keepCenterOnPage()
                    invalidate()
                } else if (dragging) {
                    val index = event.findPointerIndex(activePointer).coerceAtLeast(0)
                    centerX = (event.getX(index) - pageRect.left) / pageRect.width()
                    centerY = (event.getY(index) - pageRect.top) / pageRect.height()
                    keepCenterOnPage()
                    invalidate()
                } else if (rotating) {
                    val index = event.findPointerIndex(activePointer).coerceAtLeast(0)
                    val angleDelta = angleAroundCenter(event.getX(index), event.getY(index)) -
                        rotationStartAngle
                    rotationDegrees = rotationStartDegrees + ((angleDelta + 540f) % 360f - 180f)
                    invalidate()
                } else if (resizing) {
                    val index = event.findPointerIndex(activePointer).coerceAtLeast(0)
                    val localX = toLocalPoint(event.getX(index), event.getY(index)).first
                    signatureWidth = (resizeStartWidth +
                        (localX - resizeStartX) / pageRect.width())
                        .coerceIn(0.08f, 0.8f)
                    keepCenterOnPage()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                startDistance = 0f
                activePointer = event.getPointerId(if (event.actionIndex == 0) 1 else 0)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                resizing = false
                rotating = false
                startDistance = 0f
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

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun keepCenterOnPage() {
        val mark = layers.getOrNull(selectedIndex)?.bitmap ?: return
        val halfWidth = signatureWidth / 2f
        val halfHeight = signatureWidth * mark.height / mark.width / pageRect.height() *
            pageRect.width() / 2f
        centerX = centerX.coerceIn(halfWidth, 1f - halfWidth)
        centerY = centerY.coerceIn(halfHeight.coerceAtMost(0.5f), 1f - halfHeight.coerceAtMost(0.5f))
    }

    private fun fitRect(bitmapWidth: Int, bitmapHeight: Int): RectF {
        val scale = min(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
        val fittedWidth = bitmapWidth * scale
        val fittedHeight = bitmapHeight * scale
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        return RectF(left, top, left + fittedWidth, top + fittedHeight)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

class SignaturePadView(context: Context) : View(context) {

    private data class Stroke(val path: Path)

    private val strokes = mutableListOf<Stroke>()
    private var activePath: Path? = null
    private var lastX = 0f
    private var lastY = 0f
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.displayMetrics.density * 3.2f
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.WHITE)
    }

    fun clear() {
        strokes.clear()
        activePath = null
        invalidate()
    }

    fun trimmedBitmap(): Bitmap? {
        if (width <= 0 || height <= 0 || strokes.isEmpty()) return null
        val sampleScale = 2
        val bitmap = Bitmap.createBitmap(width * sampleScale, height * sampleScale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(sampleScale.toFloat(), sampleScale.toFloat())
        drawStrokes(canvas)

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (pixels[y * bitmap.width + x] ushr 24 != 0) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) {
            bitmap.recycle()
            return null
        }
        val padding = resources.displayMetrics.density.toInt().coerceAtLeast(4)
        val cropLeft = (left - padding).coerceAtLeast(0)
        val cropTop = (top - padding).coerceAtLeast(0)
        val cropRight = (right + padding + 1).coerceAtMost(bitmap.width)
        val cropBottom = (bottom + padding + 1).coerceAtMost(bitmap.height)
        val trimmed = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
        if (trimmed !== bitmap) bitmap.recycle()
        return trimmed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawStrokes(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activePath = Path().apply { moveTo(lastX, lastY) }
                strokes.add(Stroke(activePath!!))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val path = activePath ?: return true
                val x = event.x
                val y = event.y
                path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                activePath?.lineTo(event.x, event.y)
                activePath = null
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activePath = null
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawStrokes(canvas: Canvas) {
        for (stroke in strokes) {
            strokePaint.color = Color.BLACK
            canvas.drawPath(stroke.path, strokePaint)
        }
    }
}
