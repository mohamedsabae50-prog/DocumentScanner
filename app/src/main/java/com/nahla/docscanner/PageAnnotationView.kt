package com.nahla.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class PageShapeType {
    RECTANGLE, FILLED_BOX, OVAL, ARROW
}

data class ShapePlacement(
    var centerX: Float = 0.5f,
    var centerY: Float = 0.5f,
    var width: Float = 0.3f,
    var height: Float = 0.12f,
    var rotationDegrees: Float = 0f,
    var color: Int = Color.BLACK,
    var strokeWidth: Float = 3f
) {
    companion object {
        const val BLACK = Color.BLACK
        const val WHITE = Color.WHITE
    }
}

data class ShapeLayer(
    val type: PageShapeType,
    val placement: ShapePlacement
)

/** Live page preview and touch editor for signatures and vector annotations. */
class PageAnnotationView(
    context: Context,
    private val page: Bitmap,
    private val signatures: MutableList<SignatureLayer>,
    private val shapes: MutableList<ShapeLayer>
) : View(context) {

    private val pageRect = RectF()
    private val startRect = RectF()
    private val previewBitmap = createPreviewBitmap()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00C853.toInt()
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        strokeWidth = dp(1.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00A846.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val deleteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(17f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
    }

    private data class Selection(val signature: Boolean, val index: Int)
    private var selected: Selection? = if (signatures.isNotEmpty()) {
        Selection(true, signatures.lastIndex)
    } else if (shapes.isNotEmpty()) {
        Selection(false, shapes.lastIndex)
    } else null

    private var activeHandle = -1
    private var downX = 0f
    private var downY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var startCenterX = 0f
    private var startCenterY = 0f
    private var startWidth = 0f
    private var startHeight = 0f
    private var startRotation = 0f
    private var startAngle = 0f
    private var dragging = false
    private var rotating = false
    private var resizing = false
    private var pinching = false
    private var pinchStartDistance = 1f

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    val signatureLayers: List<SignatureLayer> get() = signatures.toList()
    val shapeLayers: List<ShapeLayer> get() = shapes.map { it.copy(placement = it.placement.copy()) }

    fun addSignature(bitmap: Bitmap) {
        val layer = SignatureLayer(
            bitmap,
            SignaturePlacement(centerX = 0.5f, centerY = 0.5f, width = 0.38f)
        )
        signatures.add(layer)
        selected = Selection(true, signatures.lastIndex)
        invalidate()
    }

    fun addShape(type: PageShapeType, strokeWidth: Float) {
        shapes.add(ShapeLayer(type, ShapePlacement(
            centerX = 0.5f,
            centerY = 0.5f,
            width = 0.38f,
            height = 0.18f,
            color = if (type == PageShapeType.FILLED_BOX) Color.WHITE else Color.BLACK,
            strokeWidth = strokeWidth
        )))
        selected = Selection(false, shapes.lastIndex)
        invalidate()
    }

    fun releasePreview() {
        if (!previewBitmap.isRecycled) previewBitmap.recycle()
    }

    fun drawAnnotations(canvas: Canvas, target: Bitmap) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        signatures.forEach { layer ->
            val placement = layer.placement
            val markWidth = target.width * placement.width
            val markHeight = markWidth * layer.bitmap.height / layer.bitmap.width
            val cx = target.width * placement.centerX
            val cy = target.height * placement.centerY
            val rect = RectF(cx - markWidth / 2f, cy - markHeight / 2f,
                cx + markWidth / 2f, cy + markHeight / 2f)
            canvas.save()
            canvas.rotate(placement.rotationDegrees, cx, cy)
            canvas.drawBitmap(layer.bitmap, null, rect, paint)
            canvas.restore()
        }
        val pageScale = target.width / pageRect.width().coerceAtLeast(1f)
        val density = resources.displayMetrics.density
        shapes.forEach { layer ->
            val placement = layer.placement
            val cx = target.width * placement.centerX
            val cy = target.height * placement.centerY
            val rect = RectF(
                cx - target.width * placement.width / 2f,
                cy - target.height * placement.height / 2f,
                cx + target.width * placement.width / 2f,
                cy + target.height * placement.height / 2f
            )
            canvas.save()
            canvas.rotate(placement.rotationDegrees, cx, cy)
            drawShape(canvas, layer.type, rect, placement, paint.apply {
                strokeWidth = placement.strokeWidth * density * pageScale
            })
            canvas.restore()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        pageRect.set(fitRect())
        canvas.drawBitmap(previewBitmap, null, pageRect, bitmapPaint)

        signatures.forEachIndexed { index, layer ->
            val rect = signatureRect(layer)
            canvas.save()
            canvas.rotate(layer.placement.rotationDegrees, rect.centerX(), rect.centerY())
            canvas.drawBitmap(layer.bitmap, null, rect, bitmapPaint)
            if (selected == Selection(true, index)) drawSelection(canvas, rect)
            canvas.restore()
        }
        shapes.forEachIndexed { index, layer ->
            val rect = shapeRect(layer.placement)
            canvas.save()
            canvas.rotate(layer.placement.rotationDegrees, rect.centerX(), rect.centerY())
            drawShape(canvas, layer.type, rect, layer.placement, shapePaint.apply {
                strokeWidth = dp(layer.placement.strokeWidth)
            })
            if (selected == Selection(false, index)) drawSelection(canvas, rect)
            canvas.restore()
        }
        if (signatures.isEmpty() && shapes.isEmpty()) {
            canvas.drawText("Add a signature or shape", width / 2f, height / 2f, hintPaint)
        }
    }

    private fun drawShape(canvas: Canvas, type: PageShapeType, rect: RectF,
                          placement: ShapePlacement, paint: Paint) {
        paint.color = if (type == PageShapeType.FILLED_BOX) Color.WHITE else Color.BLACK
        when (type) {
            PageShapeType.RECTANGLE -> {
                paint.style = Paint.Style.STROKE
                canvas.drawRect(rect, paint)
            }
            PageShapeType.FILLED_BOX -> {
                paint.style = Paint.Style.FILL
                canvas.drawRect(rect, paint)
            }
            PageShapeType.OVAL -> {
                paint.style = Paint.Style.STROKE
                canvas.drawOval(rect, paint)
            }
            PageShapeType.ARROW -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
                val arrowSize = min(rect.height() * 0.35f, rect.width() * 0.2f)
                canvas.drawLine(rect.right - arrowSize, rect.centerY() - arrowSize,
                    rect.right, rect.centerY(), paint)
                canvas.drawLine(rect.right - arrowSize, rect.centerY() + arrowSize,
                    rect.right, rect.centerY(), paint)
            }
        }
        paint.style = Paint.Style.STROKE
    }

    private fun drawSelection(canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, selectionPaint)
        val radius = dp(5f)
        for (handle in handles(rect)) {
            canvas.drawCircle(handle.first, handle.second, radius, handlePaint)
            canvas.drawCircle(handle.first, handle.second, radius, handleBorder)
        }
        val deleteX = rect.left
        val deleteY = rect.top
        canvas.drawCircle(deleteX, deleteY, dp(11f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
        })
        canvas.drawText("×", deleteX, deleteY - deleteTextPaint.fontMetrics.ascent / 2f,
            deleteTextPaint)
        canvas.drawCircle(rect.centerX(), rect.top - dp(20f), dp(6f), handlePaint)
        canvas.drawCircle(rect.centerX(), rect.top - dp(20f), dp(6f), handleBorder)
    }

    private fun handles(rect: RectF): List<Pair<Float, Float>> = listOf(
        rect.left to rect.top, rect.centerX() to rect.top, rect.right to rect.top,
        rect.right to rect.centerY(), rect.right to rect.bottom, rect.centerX() to rect.bottom,
        rect.left to rect.bottom, rect.left to rect.centerY()
    )

    private fun signatureRect(layer: SignatureLayer): RectF {
        val markWidth = pageRect.width() * layer.placement.width
        val markHeight = markWidth * layer.bitmap.height / layer.bitmap.width
        val cx = pageRect.left + pageRect.width() * layer.placement.centerX
        val cy = pageRect.top + pageRect.height() * layer.placement.centerY
        return RectF(cx - markWidth / 2f, cy - markHeight / 2f,
            cx + markWidth / 2f, cy + markHeight / 2f)
    }

    private fun shapeRect(placement: ShapePlacement): RectF {
        val cx = pageRect.left + pageRect.width() * placement.centerX
        val cy = pageRect.top + pageRect.height() * placement.centerY
        return RectF(cx - pageRect.width() * placement.width / 2f,
            cy - pageRect.height() * placement.height / 2f,
            cx + pageRect.width() * placement.width / 2f,
            cy + pageRect.height() * placement.height / 2f)
    }

    private fun selectedRect(): RectF? = when (val item = selected) {
        null -> null
        else -> if (item.signature) signatures.getOrNull(item.index)?.let(::signatureRect)
            else shapes.getOrNull(item.index)?.let { shapeRect(it.placement) }
    }

    private fun selectedPlacement(): Pair<FloatArray, FloatArray>? {
        val rect = selectedRect() ?: return null
        return Pair(floatArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            floatArrayOf(rect.centerX(), rect.centerY()))
    }

    private fun setSelectedPlacement(centerX: Float, centerY: Float, width: Float, height: Float,
                                     rotation: Float) {
        val item = selected ?: return
        val normalizedX = ((centerX - pageRect.left) / pageRect.width()).coerceIn(0f, 1f)
        val normalizedY = ((centerY - pageRect.top) / pageRect.height()).coerceIn(0f, 1f)
        if (item.signature) {
            val layer = signatures.getOrNull(item.index) ?: return
            layer.placement = SignaturePlacement(normalizedX, normalizedY,
                (width / pageRect.width()).coerceIn(0.04f, 0.9f), rotation)
        } else {
            val layer = shapes.getOrNull(item.index) ?: return
            layer.placement.centerX = normalizedX
            layer.placement.centerY = normalizedY
            layer.placement.width = (width / pageRect.width()).coerceIn(0.03f, 0.95f)
            layer.placement.height = (height / pageRect.height()).coerceIn(0.02f, 0.95f)
            layer.placement.rotationDegrees = rotation
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageRect.set(fitRect())
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = (shapes.indices.reversed().map { Selection(false, it) } +
                    signatures.indices.reversed().map { Selection(true, it) })
                    .firstOrNull { contains(it, downX, downY) }
                if (hit != null) selected = hit
                val rect = selectedRect() ?: return true
                val local = localPoint(downX, downY, rect, selectedRotation())
                downLocalX = local.first
                downLocalY = local.second
                startRect.set(rect)
                if (hypot(local.first - rect.left, local.second - rect.top) <= dp(36f)) {
                    deleteSelected()
                    return true
                }
                startCenterX = rect.centerX()
                startCenterY = rect.centerY()
                startWidth = rect.width()
                startHeight = rect.height()
                startRotation = selectedRotation()
                startAngle = angle(downX, downY, rect.centerX(), rect.centerY())
                rotating = hypot(downX - rect.centerX(), downY - (rect.top - dp(20f))) <= dp(36f)
                activeHandle = if (rotating) -1 else nearestHandle(rect, local.first, local.second)
                resizing = activeHandle >= 0
                dragging = !rotating && !resizing && contains(selected!!, downX, downY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val rect = selectedRect() ?: return true
                if (event.pointerCount >= 2) {
                    pinching = true
                    dragging = false
                    resizing = false
                    rotating = false
                    startCenterX = rect.centerX()
                    startCenterY = rect.centerY()
                    startWidth = rect.width()
                    startHeight = rect.height()
                    startRotation = selectedRotation()
                    pinchStartDistance = pointerDistance(event).coerceAtLeast(1f)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val rect = selectedRect() ?: return true
                if (pinching && event.pointerCount >= 2) {
                    val scale = pointerDistance(event) / pinchStartDistance
                    updateSelected(startCenterX, startCenterY,
                        startWidth * scale, startHeight * scale, startRotation)
                } else if (rotating) {
                    val delta = angle(event.x, event.y, startCenterX, startCenterY) - startAngle
                    updateSelected(startCenterX, startCenterY, startWidth, startHeight,
                        startRotation + delta)
                } else if (resizing) {
                    val local = localPoint(event.x, event.y, startRect, startRotation)
                    val dx = local.first - downLocalX
                    val dy = local.second - downLocalY
                    if (selected?.signature == true) {
                        val amount = when (activeHandle) {
                            1, 5 -> dy
                            3, 7 -> dx
                            else -> (dx + dy) / 2f
                        }
                        val sign = if (activeHandle in 0..2) -1f else 1f
                        val width = (startWidth + sign * amount * 2f).coerceIn(
                            pageRect.width() * 0.04f, pageRect.width() * 0.9f)
                        updateSelected(startCenterX, startCenterY, width,
                            width * startHeight / startWidth, startRotation)
                    } else {
                        val horizontal = activeHandle in setOf(0, 2, 3, 4, 6, 7)
                        val vertical = activeHandle in setOf(0, 1, 2, 4, 5, 6)
                        val sx = if (activeHandle in setOf(0, 6, 7)) -1f else 1f
                        val sy = if (activeHandle in setOf(0, 1, 2)) -1f else 1f
                        val width = (startWidth + if (horizontal) sx * dx * 2f else 0f)
                            .coerceIn(pageRect.width() * 0.03f, pageRect.width() * 0.95f)
                        val height = (startHeight + if (vertical) sy * dy * 2f else 0f)
                            .coerceIn(pageRect.height() * 0.02f, pageRect.height() * 0.95f)
                        updateSelected(startCenterX, startCenterY, width, height, startRotation)
                    }
                } else if (dragging) {
                    updateSelected(startCenterX + event.x - downX, startCenterY + event.y - downY,
                        startWidth, startHeight, startRotation)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) pinching = false
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                resizing = false
                rotating = false
                pinching = false
                activeHandle = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    private fun updateSelected(cx: Float, cy: Float, w: Float, h: Float, rotation: Float) {
        setSelectedPlacement(cx.coerceIn(pageRect.left, pageRect.right),
            cy.coerceIn(pageRect.top, pageRect.bottom), w, h, rotation)
    }

    private fun deleteSelected() {
        val item = selected ?: return
        if (item.signature) {
            signatures.removeAt(item.index).bitmap.let {
                if (!it.isRecycled) it.recycle()
            }
        } else {
            shapes.removeAt(item.index)
        }
        selected = when {
            signatures.isNotEmpty() -> Selection(true, signatures.lastIndex)
            shapes.isNotEmpty() -> Selection(false, shapes.lastIndex)
            else -> null
        }
        invalidate()
    }

    private fun selectedRotation(): Float = selected?.let {
        if (it.signature) signatures.getOrNull(it.index)?.placement?.rotationDegrees ?: 0f
        else shapes.getOrNull(it.index)?.placement?.rotationDegrees ?: 0f
    } ?: 0f

    private fun contains(item: Selection, x: Float, y: Float): Boolean {
        val rect = if (item.signature) signatures.getOrNull(item.index)?.let(::signatureRect)
            else shapes.getOrNull(item.index)?.let { shapeRect(it.placement) }
        rect ?: return false
        val point = localPoint(x, y, rect, if (item.signature) {
            signatures[item.index].placement.rotationDegrees
        } else shapes[item.index].placement.rotationDegrees)
        return RectF(rect.left - dp(20f), rect.top - dp(20f),
            rect.right + dp(20f), rect.bottom + dp(20f)).contains(point.first, point.second)
    }

    private fun localPoint(x: Float, y: Float, rect: RectF, rotation: Float): Pair<Float, Float> {
        val radians = Math.toRadians(-rotation.toDouble())
        val dx = x - rect.centerX()
        val dy = y - rect.centerY()
        return rect.centerX() + (dx * kotlin.math.cos(radians) - dy * kotlin.math.sin(radians)).toFloat() to
            rect.centerY() + (dx * kotlin.math.sin(radians) + dy * kotlin.math.cos(radians)).toFloat()
    }

    private fun nearestHandle(rect: RectF, x: Float, y: Float): Int {
        val points = handles(rect)
        val targetRadius = dp(36f)
        return points.indices.minByOrNull { hypot(points[it].first - x, points[it].second - y) }
            ?.takeIf { hypot(points[it].first - x, points[it].second - y) <= targetRadius } ?: -1
    }

    private fun angle(x: Float, y: Float, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    private fun fitRect(): RectF {
        val scale = min(width.toFloat() / page.width, height.toFloat() / page.height)
        val fittedWidth = page.width * scale
        val fittedHeight = page.height * scale
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        return RectF(left, top, left + fittedWidth, top + fittedHeight)
    }

    private fun createPreviewBitmap(): Bitmap {
        val maxWidth = min(1080, resources.displayMetrics.widthPixels.coerceAtLeast(1))
        val scale = min(1f, maxWidth.toFloat() / page.width)
        val previewWidth = (page.width * scale).toInt().coerceAtLeast(1)
        val previewHeight = (page.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(page, null, RectF(0f, 0f, previewWidth.toFloat(),
                previewHeight.toFloat()), bitmapPaint)
        }
    }

    private fun pointerDistance(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
