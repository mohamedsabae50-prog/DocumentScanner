package com.nahla.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Zero-dependency 4-corner crop overlay.
 *
 * Sits on top of an ImageView that renders the photo with `fitCenter`; the
 * activity hands over the on-screen image rect so points can be mapped back to
 * bitmap coordinates. Corners are dragged individually, the body of the quad can
 * be dragged as a whole, and everything stays clamped inside the image.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val handleRadius = dp(8f)
    private val handleRing = dp(2.4f)
    private val edgeWidth = dp(1.6f)
    private val gridWidth = dp(1f)
    private val touchSlop = dp(44f)
    private val minSpan = dp(56f)
    private val loupeRadius = dp(74f)
    private val loupeInset = dp(14f)

    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
    }
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xCC4FC3F7.toInt()
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = edgeWidth
        strokeJoin = Paint.Join.ROUND
        color = ACCENT
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = gridWidth
        color = GRID
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DIM
    }

    /** TL, TR, BR, BL - kept in that winding so the perspective matrix stays valid. */
    private val corners = Array(4) { PointF() }
    private val quadPath = Path()
    private val dimPath = Path()
    private val quadPathForLoupe = Path()
    private val scratch = FloatArray(2)

    private var imageRect = RectF()
    private var activeCorner = NO_CORNER
    private var draggingBody = false
    private var lastX = 0f
    private var lastY = 0f

    /** Invoked when the quad settles (drag end, reset, restore). */
    var onCropChanged: (() -> Unit)? = null

    /**
     * The photo behind the overlay, used only to paint the drag loupe. The view
     * never takes ownership of it - the activity that supplied it recycles it.
     */
    var sourceBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Set false to hide the magnifier on low-end devices. */
    var loupeEnabled: Boolean = true

    init {
        isClickable = true
        contentDescription = context.getString(R.string.crop_overlay_description)
    }

    /**
     * @param resetQuad true when the photo changed (new image, rotation) and the
     *        quad must return to the full frame.
     */
    fun setImageRect(rect: RectF, resetQuad: Boolean) {
        val changed = rect != imageRect
        if (resetQuad || imageRect.isEmpty) {
            imageRect = RectF(rect)
            resetToFull()
            return
        }
        if (changed) {
            val previousPoints = normalizedPoints()
            imageRect = RectF(rect)
            setNormalizedPoints(previousPoints)
        }
    }

    fun resetToFull() {
        if (imageRect.isEmpty) return
        corners[0].set(imageRect.left, imageRect.top)
        corners[1].set(imageRect.right, imageRect.top)
        corners[2].set(imageRect.right, imageRect.bottom)
        corners[3].set(imageRect.left, imageRect.bottom)
        invalidate()
    }

    /** 8 floats: x0,y0,x1,y1,x2,y2,x3,y3 normalised to 0..1 over the image rect. */
    fun normalizedPoints(): FloatArray {
        val out = FloatArray(8)
        if (imageRect.isEmpty || imageRect.width() <= 0f || imageRect.height() <= 0f) return out
        for (i in 0 until 4) {
            out[i * 2] = ((corners[i].x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
            out[i * 2 + 1] = ((corners[i].y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        }
        return out
    }

    fun setNormalizedPoints(points: FloatArray) {
        if (imageRect.isEmpty || points.size != 8) return
        for (i in 0 until 4) {
            corners[i].set(
                imageRect.left + points[i * 2].coerceIn(0f, 1f) * imageRect.width(),
                imageRect.top + points[i * 2 + 1].coerceIn(0f, 1f) * imageRect.height()
            )
        }
        clampIntoImage()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageRect.isEmpty) return

        buildQuadPath()

        // Everything outside the quad, but only over the photo itself.
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(imageRect, Path.Direction.CW)
        dimPath.addPath(quadPath)
        canvas.drawPath(dimPath, dimPaint)

        drawGuides(canvas)

        canvas.drawPath(quadPath, edgePaint)
        for (corner in corners) {
            canvas.drawCircle(corner.x, corner.y, handleRadius, handleFill)
            canvas.drawCircle(corner.x, corner.y, handleRadius, edgePaint)
            canvas.drawCircle(corner.x, corner.y, handleRadius - handleRing / 2f, edgePaint)
        }

        if (activeCorner != NO_CORNER && loupeEnabled) drawLoupe(canvas)
    }

    /**
     * Magnified inset in the top-left corner showing what sits under the corner
     * being dragged, so the handle can be placed on the pixel, not near it.
     */
    private fun drawLoupe(canvas: Canvas) {
        val bitmap = sourceBitmap ?: return
        if (bitmap.isRecycled || imageRect.isEmpty || imageRect.width() <= 0f) return

        val anchor = corners[activeCorner]
        val normalizedX = (anchor.x - imageRect.left) / imageRect.width()
        val normalizedY = (anchor.y - imageRect.top) / imageRect.height()

        val zoom = LOUPE_ZOOM
        val span = loupeRadius / zoom
        val half = min(span, min(bitmap.width, bitmap.height) / 2f)

        val centerX = (normalizedX * bitmap.width).coerceIn(half, bitmap.width - half)
        val centerY = (normalizedY * bitmap.height).coerceIn(half, bitmap.height - half)

        val source = Rect(
            (centerX - half).toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0)),
            (centerY - half).toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0)),
            (centerX + half).toInt().coerceIn(1, bitmap.width),
            (centerY + half).toInt().coerceIn(1, bitmap.height)
        )
        val targetX = loupeInset + loupeRadius
        val targetY = loupeInset + loupeRadius
        val destination = RectF(
            targetX - loupeRadius, targetY - loupeRadius,
            targetX + loupeRadius, targetY + loupeRadius
        )

        val saved = canvas.save()
        quadPathForLoupe.reset()
        quadPathForLoupe.addCircle(targetX, targetY, loupeRadius, Path.Direction.CW)
        canvas.clipPath(quadPathForLoupe)
        canvas.drawBitmap(bitmap, source, destination, loupePaint)
        canvas.restoreToCount(saved)

        canvas.drawCircle(targetX, targetY, loupeRadius, loupeRing)
        canvas.drawCircle(targetX, targetY, loupeRadius - dp(1f), loupeRing)
        canvas.drawLine(targetX - loupeRadius / 3f, targetY, targetX + loupeRadius / 3f, targetY, crosshair)
        canvas.drawLine(targetX, targetY - loupeRadius / 3f, targetX, targetY + loupeRadius / 3f, crosshair)
        canvas.drawCircle(targetX, targetY, dp(2.5f), crosshair)
    }

    private fun drawGuides(canvas: Canvas) {
        for (i in 1..2) {
            val t = i / 3f
            bilinear(t, 0f)
            val ax = scratch[0]
            val ay = scratch[1]
            bilinear(t, 1f)
            canvas.drawLine(ax, ay, scratch[0], scratch[1], gridPaint)

            bilinear(0f, t)
            val bx = scratch[0]
            val by = scratch[1]
            bilinear(1f, t)
            canvas.drawLine(bx, by, scratch[0], scratch[1], gridPaint)
        }
    }

    /** Bilinear sample of the quad: writes x,y into [scratch]. */
    private fun bilinear(u: Float, v: Float) {
        val topX = lerp(corners[0].x, corners[1].x, u)
        val topY = lerp(corners[0].y, corners[1].y, u)
        val bottomX = lerp(corners[3].x, corners[2].x, u)
        val bottomY = lerp(corners[3].y, corners[2].y, u)
        scratch[0] = lerp(topX, bottomX, v)
        scratch[1] = lerp(topY, bottomY, v)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activeCorner = nearestCorner(event.x, event.y)
                if (activeCorner == NO_CORNER) {
                    draggingBody = isInsideQuad(event.x, event.y)
                    if (!draggingBody) return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                if (activeCorner != NO_CORNER) {
                    moveCorner(activeCorner, x, y)
                } else if (draggingBody) {
                    translate(x - lastX, y - lastY)
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCorner = NO_CORNER
                draggingBody = false
                clampIntoImage()
                invalidate()
                onCropChanged?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveCorner(index: Int, x: Float, y: Float) {
        val opposite = corners[(index + 2) % 4]
        val nx = x.coerceIn(imageRect.left, imageRect.right)
        val ny = y.coerceIn(imageRect.top, imageRect.bottom)
        // Never let the quad collapse: keep a minimum span against the opposite corner.
        if (abs(nx - opposite.x) < minSpan || abs(ny - opposite.y) < minSpan) return
        corners[index].set(nx, ny)
    }

    private fun translate(dx: Float, dy: Float) {
        for (corner in corners) {
            corner.x += dx
            corner.y += dy
        }
        clampIntoImage()
    }

    private fun clampIntoImage() {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (corner in corners) {
            minX = min(minX, corner.x)
            maxX = max(maxX, corner.x)
            minY = min(minY, corner.y)
            maxY = max(maxY, corner.y)
        }
        val shiftX = when {
            minX < imageRect.left -> imageRect.left - minX
            maxX > imageRect.right -> imageRect.right - maxX
            else -> 0f
        }
        val shiftY = when {
            minY < imageRect.top -> imageRect.top - minY
            maxY > imageRect.bottom -> imageRect.bottom - maxY
            else -> 0f
        }
        if (shiftX != 0f || shiftY != 0f) {
            for (corner in corners) {
                corner.x = (corner.x + shiftX).coerceIn(imageRect.left, imageRect.right)
                corner.y = (corner.y + shiftY).coerceIn(imageRect.top, imageRect.bottom)
            }
        }
    }

    /** Convex point-in-quad test; the quad is convex by construction. */
    private fun isInsideQuad(x: Float, y: Float): Boolean {
        var positive = false
        var negative = false
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (cross > 0f) positive = true else if (cross < 0f) negative = true
        }
        return !(positive && negative)
    }

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = NO_CORNER
        var bestDistance = touchSlop
        for (i in 0 until 4) {
            val d = hypot(corners[i].x - x, corners[i].y - y)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun buildQuadPath() {
        quadPath.reset()
        quadPath.moveTo(corners[0].x, corners[0].y)
        quadPath.lineTo(corners[1].x, corners[1].y)
        quadPath.lineTo(corners[2].x, corners[2].y)
        quadPath.lineTo(corners[3].x, corners[3].y)
        quadPath.close()
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val NO_CORNER = -1

        /** How much the loupe magnifies the photo under the dragged corner. */
        const val LOUPE_ZOOM = 2.5f

        val ACCENT = Color.parseColor("#4FC3F7")
        val GRID = Color.parseColor("#66FFFFFF")
        val DIM = Color.parseColor("#99000000")
    }
}