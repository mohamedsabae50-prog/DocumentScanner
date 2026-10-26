package com.nahla.docscanner

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Document boundary detection v2 - still zero OpenCV, zero ML, zero native code,
 * but a real pipeline instead of the brightness heuristic that v1 used.
 *
 * The failure mode v1 had was assuming a document is "the bright region", which
 * breaks under a shadow: half the paper falls below the threshold and the quad
 * shrinks onto the lit corner.
 *
 * v2 runs on a 360 px working grid:
 *  1. Rec.601 luminance, 5x5 Gaussian, then a morphological closing (dilate then
 *     erode) so text strokes stop cutting holes in the sheet;
 *  2. Sobel gradient magnitude;
 *  3. a centre-seeded colour mask - the middle quarter of the frame is almost
 *     always paper, so its mean/std defines "what paper looks like here";
 *  4. the largest connected component of that mask, with interior holes filled;
 *  5. the outer contour, read as four ordered profile chains, simplified with
 *     Ramer-Douglas-Peucker, then hulled;
 *  6. the largest-area quadrilateral inscribed in that hull, whose four vertices
 *     are finally snapped onto the strongest gradient within a few pixels.
 *
 * Anything that fails validation (too small, not inside the frame, degenerate)
 * falls back to a safe 8 % inset, so the crop screen always opens usable.
 */
object DocumentEdgeDetector {

    private const val WORKING_EDGE = 360
    private const val MIN_AREA_RATIO = 0.12f
    private const val FALLBACK_INSET = 0.08f
    private const val DETECTED_QUAD_INSET = 0.015f

    /** Closing radius, in pixels of the working grid. */
    private const val CLOSE_RADIUS = 2

    /** Fraction of the contour diagonal kept by Ramer-Douglas-Peucker. */
    private const val RDP_FRACTION = 0.012f

    /** Cap on hull vertices before the maximum-area quad search. */
    private const val MAX_HULL_VERTICES = 28

    /** How far a corner may slide to reach the strongest local gradient. */
    private const val GRADIENT_SNAP = 5

    private const val SIGMA = 2.5f
    private const val MIN_STD = 8f

    /**
     * @return 8 normalised floats `x0,y0,x1,y1,x2,y2,x3,y3` (top-left, top-right,
     *         bottom-right, bottom-left) in 0..1 relative to the full source image.
     */
    fun detect(source: Bitmap): FloatArray {
        val analysis = ImageProcessor.copyScaled(source, WORKING_EDGE)
        try {
            val width = analysis.width
            val height = analysis.height
            val pixels = IntArray(width * height)
            analysis.getPixels(pixels, 0, width, 0, 0, width, height)

            val gray = FloatArray(width * height)
            for (index in pixels.indices) gray[index] = lumaOf(pixels[index])
            gaussian5(gray, width, height)

            val gradient = sobel(gray, width, height)

            var mask = paperMask(pixels, width, height)
            mask = close(mask, width, height)
            val region = largestComponent(mask, width, height)
            val filled = fillHoles(region, width, height)

            val quad = fitQuad(filled, gradient, width, height)
            return quad ?: fallback()
        } finally {
            analysis.recycle()
        }
    }

    // ------------------------------------------------------------------ filtering

    /** Separable 5-tap Gaussian, [1 4 6 4 1] / 16. */
    private fun gaussian5(values: FloatArray, width: Int, height: Int) {
        val kernel = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val temp = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (t in -2..2) sum += kernel[t + 2] * values[row + (x + t).coerceIn(0, width - 1)]
                temp[row + x] = sum / 16f
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (t in -2..2) {
                    sum += kernel[t + 2] * temp[(y + t).coerceIn(0, height - 1) * width + x]
                }
                values[row + x] = sum / 16f
            }
        }
    }

    private fun sobel(gray: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val gx = (gray[row - width + x + 1] + 2f * gray[row + x + 1] + gray[row + width + x + 1]) -
                    (gray[row - width + x - 1] + 2f * gray[row + x - 1] + gray[row + width + x - 1])
                val gy = (gray[row + width + x - 1] + 2f * gray[row + width + x] + gray[row + width + x + 1]) -
                    (gray[row - width + x - 1] + 2f * gray[row - width + x] + gray[row - width + x + 1])
                out[row + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /** Dilate then erode: bridges the text strokes that would otherwise punch holes. */
    private fun close(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        var current = dilate(mask, width, height)
        repeat(CLOSE_RADIUS) {
            current = erode(current, width, height)
            current = dilate(current, width, height)
        }
        current = erode(current, width, height)
        return current
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var any = false
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        if (mask[yy * width + (x + dx).coerceIn(0, width - 1)]) any = true
                    }
                }
                out[row + x] = any
            }
        }
        return out
    }

    private fun erode(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var all = true
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        if (!mask[yy * width + (x + dx).coerceIn(0, width - 1)]) all = false
                    }
                }
                out[row + x] = all
            }
        }
        return out
    }

    // --------------------------------------------------------------- paper mask

    /** "Paper" is whatever colour the middle quarter of the frame is made of. */
    private fun paperMask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val left = width / 4
        val right = (3 * width) / 4
        val top = height / 4
        val bottom = (3 * height) / 4

        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var sumRR = 0f
        var sumGG = 0f
        var sumBB = 0f
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = pixels[y * width + x]
                val r = channel(color, 16)
                val g = channel(color, 8)
                val b = channel(color, 0)
                sumR += r
                sumG += g
                sumB += b
                sumRR += r * r
                sumGG += g * g
                sumBB += b * b
                count++
                x += 2
            }
            y += 2
        }
        if (count == 0) return BooleanArray(pixels.size) { true }

        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumB / count
        val stdR = max(MIN_STD, sqrt(max(0f, sumRR / count - meanR * meanR)))
        val stdG = max(MIN_STD, sqrt(max(0f, sumGG / count - meanG * meanG)))
        val stdB = max(MIN_STD, sqrt(max(0f, sumBB / count - meanB * meanB)))

        val limitR = stdR * SIGMA
        val limitG = stdG * SIGMA
        val limitB = stdB * SIGMA

        val mask = BooleanArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            mask[index] = abs(channel(color, 16) - meanR) <= limitR &&
                abs(channel(color, 8) - meanG) <= limitG &&
                abs(channel(color, 0) - meanB) <= limitB
        }
        return mask
    }

    private fun largestComponent(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var bestSeed = -1
        var bestSize = 0

        var seed = 0
        while (seed < mask.size) {
            if (mask[seed] && !visited[seed]) {
                var head = 0
                var tail = 0
                queue[tail++] = seed
                visited[seed] = true
                var size = 0
                while (head < tail) {
                    val index = queue[head++]
                    size++
                    val x = index % width
                    val y = index / width
                    if (x > 0) push(mask, visited, queue, index - 1, tail).also { tail = it }
                    if (x < width - 1) push(mask, visited, queue, index + 1, tail).also { tail = it }
                    if (y > 0) push(mask, visited, queue, index - width, tail).also { tail = it }
                    if (y < height - 1) push(mask, visited, queue, index + width, tail).also { tail = it }
                }
                if (size > bestSize) {
                    bestSize = size
                    bestSeed = seed
                }
            }
            seed++
        }

        val out = BooleanArray(mask.size)
        if (bestSeed < 0) return out

        var head = 0
        var tail = 0
        queue[tail++] = bestSeed
        out[bestSeed] = true
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) push(mask, out, queue, index - 1, tail).also { tail = it }
            if (x < width - 1) push(mask, out, queue, index + 1, tail).also { tail = it }
            if (y > 0) push(mask, out, queue, index - width, tail).also { tail = it }
            if (y < height - 1) push(mask, out, queue, index + width, tail).also { tail = it }
        }
        return out
    }

    /** Returns the new tail index, or the old one when the neighbour is not paper. */
    private fun push(
        mask: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        index: Int,
        tail: Int
    ): Int = if (mask[index] && !visited[index]) {
        visited[index] = true
        queue[tail] = index
        tail + 1
    } else {
        tail
    }

    /** Background reachable from the frame border stays background; the rest is a hole. */
    private fun fillHoles(region: BooleanArray, width: Int, height: Int): BooleanArray {
        val outside = BooleanArray(region.size)
        val queue = IntArray(region.size)
        var head = 0
        var tail = 0

        fun seed(index: Int) {
            if (!region[index] && !outside[index]) {
                outside[index] = true
                queue[tail++] = index
            }
        }
        var x = 0
        while (x < width) {
            seed(x)
            seed((height - 1) * width + x)
            x++
        }
        var y = 0
        while (y < height) {
            seed(y * width)
            seed(y * width + width - 1)
            y++
        }

        while (head < tail) {
            val index = queue[head++]
            val px = index % width
            val py = index / width
            if (px > 0) seed(index - 1)
            if (px < width - 1) seed(index + 1)
            if (py > 0) seed(index - width)
            if (py < height - 1) seed(index + width)
        }

        val filled = BooleanArray(region.size)
        for (index in filled.indices) filled[index] = region[index] || !outside[index]
        return filled
    }

    // ------------------------------------------------------------- contour + quad

    private fun fitQuad(
        region: BooleanArray,
        gradient: FloatArray,
        width: Int,
        height: Int
    ): FloatArray? {
        val contour = contourFrom(region, width, height)
        if (contour.size < 8) return null

        val diagonal = sqrt((width * width + height * height).toFloat())
        val simplified = rdp(contour, diagonal * RDP_FRACTION)
        if (simplified.size < 8) return null

        val hull = convexHull(simplified)
        if (hull.size < 8) return null

        val quad = largestQuad(hull)
        if (quad == null) return null

        snapToGradient(quad, gradient, width, height)

        val centerX = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
        val centerY = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
        for (corner in 0 until 4) {
            val offset = corner * 2
            quad[offset] = centerX + (quad[offset] - centerX) * (1f - DETECTED_QUAD_INSET)
            quad[offset + 1] =
                centerY + (quad[offset + 1] - centerY) * (1f - DETECTED_QUAD_INSET)
        }

        val maxX = (width - 1).coerceAtLeast(1).toFloat()
        val maxY = (height - 1).coerceAtLeast(1).toFloat()
        val normalized = floatArrayOf(
            quad[0] / maxX, quad[1] / maxY,
            quad[2] / maxX, quad[3] / maxY,
            quad[4] / maxX, quad[5] / maxY,
            quad[6] / maxX, quad[7] / maxY
        )

        return if (isPlausible(normalized, width, height)) normalized else null
    }

    /**
     * Outer boundary as four ordered profile chains: top edge left-to-right, right
     * edge top-to-bottom, bottom edge right-to-left, left edge bottom-to-top.
     */
    private fun contourFrom(region: BooleanArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * 2 * 2 + height * 2 * 2 + 8)
        var count = 0

        for (y in 0 until height) {
            val row = y * width
            var left = -1
            var right = -1
            for (x in 0 until width) {
                if (region[row + x]) {
                    if (left < 0) left = x
                    right = x
                }
            }
            if (left >= 0) {
                out[count * 2] = left.toFloat()
                out[count * 2 + 1] = y.toFloat()
                count++
            }
            if (right >= 0 && right != left) {
                out[count * 2] = right.toFloat()
                out[count * 2 + 1] = y.toFloat()
                count++
            }
        }
        for (x in (width - 1) downTo 0) {
            var top = -1
            var bottom = -1
            for (y in 0 until height) {
                if (region[y * width + x]) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            if (bottom >= 0) {
                out[count * 2] = x.toFloat()
                out[count * 2 + 1] = bottom.toFloat()
                count++
            }
            if (top >= 0 && top != bottom) {
                out[count * 2] = x.toFloat()
                out[count * 2 + 1] = top.toFloat()
                count++
            }
        }
        return if (count * 2 <= out.size) out.copyOf(count * 2) else out
    }

    /** Iterative Ramer-Douglas-Peucker over a closed polyline of x,y pairs. */
    private fun rdp(points: FloatArray, epsilon: Float): FloatArray {
        val n = points.size / 2
        if (n < 3) return points

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        val stackFirst = IntArray(n + 2)
        val stackLast = IntArray(n + 2)
        var depth = 0
        stackFirst[depth] = 0
        stackLast[depth] = n - 1
        depth++

        while (depth > 0) {
            depth--
            val first = stackFirst[depth]
            val last = stackLast[depth]
            if (last <= first + 1) continue

            var farthest = -1
            var farthestDistance = 0f
            for (i in (first + 1) until last) {
                val distance = perpendicularDistance(points, first, last, i)
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = i
                }
            }

            if (farthest > 0 && farthestDistance > epsilon) {
                keep[farthest] = true
                stackFirst[depth] = first
                stackLast[depth] = farthest
                depth++
                stackFirst[depth] = farthest
                stackLast[depth] = last
                depth++
            }
        }

        var count = 0
        for (i in 0 until n) if (keep[i]) count++
        val out = FloatArray(count * 2)
        var cursor = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            out[cursor * 2] = points[i * 2]
            out[cursor * 2 + 1] = points[i * 2 + 1]
            cursor++
        }
        return out
    }

    private fun perpendicularDistance(points: FloatArray, first: Int, last: Int, index: Int): Float {
        val ax = points[first * 2]
        val ay = points[first * 2 + 1]
        val bx = points[last * 2]
        val by = points[last * 2 + 1]
        val px = points[index * 2]
        val py = points[index * 2 + 1]

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1e-6f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))

        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /** Andrew's monotone chain, then thinned down to at most [MAX_HULL_VERTICES]. */
    private fun convexHull(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n < 3) return points

        val order = (0 until n).toMutableList()
        order.sortWith { a, b ->
            val compare = points[a * 2].compareTo(points[b * 2])
            if (compare != 0) compare else points[a * 2 + 1].compareTo(points[b * 2 + 1])
        }

        val hull = ArrayList<Int>(n * 2)
        for (index in order) {
            while (hull.size >= 2 && cross(points, hull[hull.size - 2], hull[hull.size - 1], index) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(index)
        }
        val lowerSize = hull.size + 1
        for (i in order.size - 2 downTo 0) {
            val index = order[i]
            while (hull.size >= lowerSize && cross(points, hull[hull.size - 2], hull[hull.size - 1], index) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(index)
        }
        hull.removeAt(hull.size - 1)

        if (hull.size <= MAX_HULL_VERTICES) {
            val out = FloatArray(hull.size * 2)
            for (i in hull.indices) {
                out[i * 2] = points[hull[i] * 2]
                out[i * 2 + 1] = points[hull[i] * 2 + 1]
            }
            return out
        }
        return thinHull(points, hull)
    }

    /** Drops the vertex whose removal costs the least hull area, repeatedly. */
    private fun thinHull(points: FloatArray, hull: List<Int>): FloatArray {
        val keep = hull.toMutableList()
        while (keep.size > MAX_HULL_VERTICES) {
            var bestIndex = 0
            var bestCost = Float.MAX_VALUE
            for (i in keep.indices) {
                val previous = keep[(i - 1 + keep.size) % keep.size]
                val current = keep[i]
                val next = keep[(i + 1) % keep.size]
                val cost = abs(
                    (points[current * 2] - points[previous * 2]) *
                        (points[next * 2 + 1] - points[previous * 2 + 1]) -
                        (points[current * 2 + 1] - points[previous * 2 + 1]) *
                        (points[next * 2] - points[previous * 2])
                )
                if (cost < bestCost) {
                    bestCost = cost
                    bestIndex = i
                }
            }
            keep.removeAt(bestIndex)
        }
        val out = FloatArray(keep.size * 2)
        for (i in keep.indices) {
            out[i * 2] = points[keep[i] * 2]
            out[i * 2 + 1] = points[keep[i] * 2 + 1]
        }
        return out
    }

    private fun cross(points: FloatArray, a: Int, b: Int, c: Int): Float =
        (points[b * 2] - points[a * 2]) * (points[c * 2 + 1] - points[a * 2 + 1]) -
            (points[b * 2 + 1] - points[a * 2 + 1]) * (points[c * 2] - points[a * 2])

    /**
     * Largest-area quadrilateral inside a convex hull: every pair of hull vertices
     * is tried as a diagonal, and the two vertices furthest on either side complete
     * the quad. O(n^3) over at most 28 vertices.
     */
    private fun largestQuad(hull: FloatArray): FloatArray? {
        val n = hull.size / 2
        if (n < 4) return null

        var best: FloatArray? = null
        var bestArea = 0f

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (j == i) continue
                var farA = -1
                var farB = -1
                var maxSide = 0f
                var minSide = 0f
                for (k in 0 until n) {
                    if (k == i || k == j) continue
                    val side = cross(hull, i, j, k)
                    if (side > maxSide) {
                        maxSide = side
                        farA = k
                    }
                    if (side < minSide) {
                        minSide = side
                        farB = k
                    }
                }
                if (farA < 0 || farB < 0) continue
                val area = (maxSide - minSide) / 2f
                if (area > bestArea) {
                    bestArea = area
                    best = orderQuad(hull, i, j, farA, farB)
                }
            }
        }
        return best
    }

    /** Sorts the four found vertices into TL, TR, BR, BL. */
    private fun orderQuad(hull: FloatArray, i: Int, j: Int, k: Int, m: Int): FloatArray {
        val xs = floatArrayOf(hull[i * 2], hull[j * 2], hull[k * 2], hull[m * 2])
        val ys = floatArrayOf(hull[i * 2 + 1], hull[j * 2 + 1], hull[k * 2 + 1], hull[m * 2 + 1])

        val order = listOf(0, 1, 2, 3)
        order.sortedWith { a, b ->
            val compare = xs[a].compareTo(xs[b])
            if (compare != 0) compare else ys[a].compareTo(ys[b])
        }

        val out = FloatArray(8)
        // Left-most and right-most of the four: split by y to get TL and BL.
        val leftA = order[0]
        val leftB = order[1]
        val rightA = order[3]
        val rightB = order[2]

        val topLeft = if (ys[leftA] <= ys[leftB]) leftA else leftB
        val bottomLeft = if (ys[leftA] <= ys[leftB]) leftB else leftA
        val topRight = if (ys[rightA] <= ys[rightB]) rightA else rightB
        val bottomRight = if (ys[rightA] <= ys[rightB]) rightB else rightA

        out[0] = xs[topLeft]; out[1] = ys[topLeft]
        out[2] = xs[topRight]; out[3] = ys[topRight]
        out[4] = xs[bottomRight]; out[5] = ys[bottomRight]
        out[6] = xs[bottomLeft]; out[7] = ys[bottomLeft]
        return out
    }

    /** Slides each corner onto the strongest gradient in its immediate neighbourhood. */
    private fun snapToGradient(quad: FloatArray, gradient: FloatArray, width: Int, height: Int) {
        for (i in 0 until 4) {
            val cx = quad[i * 2].toInt().coerceIn(0, width - 1)
            val cy = quad[i * 2 + 1].toInt().coerceIn(0, height - 1)
            var bestX = cx
            var bestY = cy
            var bestValue = gradient[cy * width + cx]
            for (dy in -GRADIENT_SNAP..GRADIENT_SNAP) {
                val y = cy + dy
                if (y < 0 || y >= height) continue
                for (dx in -GRADIENT_SNAP..GRADIENT_SNAP) {
                    val x = cx + dx
                    if (x < 0 || x >= width) continue
                    val value = gradient[y * width + x]
                    if (value > bestValue) {
                        bestValue = value
                        bestX = x
                        bestY = y
                    }
                }
            }
            quad[i * 2] = bestX.toFloat()
            quad[i * 2 + 1] = bestY.toFloat()
        }
    }

    private fun isPlausible(quad: FloatArray, width: Int, height: Int): Boolean {
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        for (i in 0 until 4) {
            xs[i] = quad[i * 2] * (width - 1)
            ys[i] = quad[i * 2 + 1] * (height - 1)
            if (!xs[i].isFinite() || !ys[i].isFinite()) return false
            if (xs[i] < 0f || xs[i] > width - 1) return false
            if (ys[i] < 0f || ys[i] > height - 1) return false
        }

        var sign = 0
        var twiceArea = 0f
        for (i in 0 until 4) {
            val next = (i + 1) % 4
            twiceArea += xs[i] * ys[next] - xs[next] * ys[i]
            val opposite = (i + 2) % 4
            val cross = (xs[next] - xs[i]) * (ys[opposite] - ys[i]) -
                (ys[next] - ys[i]) * (xs[opposite] - xs[i])
            val current = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (current == 0) return false
            if (sign == 0) sign = current else if (current != sign) return false
        }

        return abs(twiceArea) / 2f >= width * height * MIN_AREA_RATIO
    }

    // -------------------------------------------------------------------- helpers

    private fun fallback(): FloatArray {
        val inset = FALLBACK_INSET
        val outer = 1f - inset
        return floatArrayOf(
            inset, inset,
            outer, inset,
            outer, outer,
            inset, outer
        )
    }

    private fun channel(color: Int, shift: Int): Float = ((color shr shift) and 0xFF).toFloat()

    private fun lumaOf(color: Int): Float =
        LUMA_RED * channel(color, 16) + LUMA_GREEN * channel(color, 8) + LUMA_BLUE * channel(color, 0)

    private const val LUMA_RED = 0.299f
    private const val LUMA_GREEN = 0.587f
    private const val LUMA_BLUE = 0.114f
}