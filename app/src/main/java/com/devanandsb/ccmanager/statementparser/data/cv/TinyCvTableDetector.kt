package com.devanandsb.ccmanager.statementparser.data.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.createBitmap
import com.devanandsb.ccmanager.ingest.TextSpan
import com.devanandsb.ccmanager.statementparser.domain.model.CvTableGrid
import com.devanandsb.ccmanager.statementparser.domain.model.PageGeometry
import com.devanandsb.ccmanager.statementparser.domain.model.RegionBox
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compact, zero-bloat offline Computer Vision (CV) Table & Summary-Box Detector.
 *
 * Designed to pair directly with Google ML Kit Text Recognition (for scanned/image/hybrid PDFs)
 * and PDFBox vector geometry (for vector PDFs) with zero native `.so` binary bloat:
 * 1. Projection-profile skew estimation & deskewing (-4° to +4°).
 * 2. Adaptive contrast stretching for faint/tinted card boxes before ML Kit OCR.
 * 3. Separable O(W*H) morphological horizontal & vertical line extraction.
 * 4. Summary-card bounding box contour clustering (handles rounded corners & shaded cards).
 * 5. Ruled table grid intersection + borderless whitespace-gutter column detection.
 */
object TinyCvTableDetector {

    data class GrayscaleGrid(
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
    ) {
        fun lum(x: Int, y: Int): Int = pixels[(y * width) + x].toInt() and 0xFF

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GrayscaleGrid) return false
            return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + pixels.contentHashCode()
            return result
        }
    }

    data class HLine(val y: Float, val x1: Float, val x2: Float) {
        val length: Float get() = x2 - x1
    }

    data class VLine(val x: Float, val y1: Float, val y2: Float) {
        val length: Float get() = y2 - y1
    }

    data class RawRect(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val isShaded: Boolean = false,
    ) {
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
    }

    /** Downsamples an Android [Bitmap] into a compact [GrayscaleGrid] and returns `(grid, scaleToBitmap)`. */
    fun bitmapToGrid(bitmap: Bitmap, targetMaxDim: Int = 1050): Pair<GrayscaleGrid, Float> {
        val srcW = bitmap.width.coerceAtLeast(1)
        val srcH = bitmap.height.coerceAtLeast(1)
        val maxSrc = maxOf(srcW, srcH)
        val scale = if (maxSrc > targetMaxDim) targetMaxDim.toFloat() / maxSrc.toFloat() else 1.0f
        val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)

        val scaled = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        } else {
            bitmap
        }
        val argb = IntArray(dstW * dstH)
        scaled.getPixels(argb, 0, dstW, 0, 0, dstW, dstH)
        if (scaled !== bitmap) scaled.recycle()

        val out = ByteArray(dstW * dstH)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            out[i] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
        }
        return GrayscaleGrid(dstW, dstH, out) to scale
    }

    /** Estimates page skew in degrees within [-4.0°, +4.0°] using edge projection variance. */
    fun estimateSkewAngleDeg(grid: GrayscaleGrid): Float {
        val w = grid.width
        val h = grid.height
        if (w < 80 || h < 80) return 0f

        // Collect subsampled horizontal/vertical edge points to keep projection under ~4ms.
        val xs = ArrayList<Int>(4096)
        val ys = ArrayList<Int>(4096)
        val step = if (w * h > 400_000) 3 else 2
        for (y in 2 until (h - 2) step step) {
            for (x in 2 until (w - 2) step step) {
                val cur = grid.lum(x, y)
                val dy = abs(cur - grid.lum(x, y - 1))
                val dx = abs(cur - grid.lum(x - 1, y))
                if (dy > 26 || dx > 26) {
                    xs += x
                    ys += y
                }
            }
        }
        if (xs.size < 120) return 0f

        fun projectionVariance(angleDeg: Float): Double {
            val rad = angleDeg * PI / 180.0
            val s = sin(rad)
            val c = cos(rad)
            val cx = w / 2.0
            val cy = h / 2.0
            val hist = IntArray(h + 24)
            for (i in xs.indices) {
                val ry = ((xs[i] - cx) * s + (ys[i] - cy) * c + cy).roundToInt() + 12
                if (ry in hist.indices) hist[ry]++
            }
            var sumSq = 0.0
            for (v in hist) sumSq += v.toDouble() * v.toDouble()
            return sumSq
        }

        val zeroVar = projectionVariance(0f)
        var bestAngle = 0f
        var bestVar = zeroVar
        var deg = -4.0f
        while (deg <= 4.0f) {
            if (abs(deg) >= 0.35f) {
                val v = projectionVariance(deg)
                if (v > bestVar) {
                    bestVar = v
                    bestAngle = deg
                }
            }
            deg += 0.5f
        }
        if (bestAngle != 0f) {
            var fine = bestAngle - 0.4f
            while (fine <= bestAngle + 0.4f) {
                val v = projectionVariance(fine)
                if (v > bestVar) {
                    bestVar = v
                    bestAngle = fine
                }
                fine += 0.2f
            }
        }
        return if (abs(bestAngle) >= 0.4f && bestVar > zeroVar * 1.07) bestAngle else 0f
    }

    /** Deskews [bitmap] onto a white canvas if dominant horizontal tilt >= 0.4°. */
    fun deskewBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val (grid, _) = bitmapToGrid(bitmap, targetMaxDim = 650)
        val angle = estimateSkewAngleDeg(grid)
        if (abs(angle) < 0.4f) return bitmap

        val out = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix().apply {
            postRotate(-angle, bitmap.width / 2f, bitmap.height / 2f)
        }
        canvas.drawBitmap(bitmap, matrix, null)
        return out
    }

    /**
     * Enhances dark-text-on-tinted-card contrast before ML Kit OCR runs. Used primarily on
     * cropped summary boxes where small grey/blue numbers can drop in full-page OCR.
     */
    fun enhanceCropContrast(crop: Bitmap): Bitmap {
        val w = crop.width.coerceAtLeast(1)
        val h = crop.height.coerceAtLeast(1)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        for (c in pixels) {
            val lum = ((77 * ((c ushr 16) and 0xFF) + 150 * ((c ushr 8) and 0xFF) + 29 * (c and 0xFF)) ushr 8)
            hist[lum]++
        }
        val total = pixels.size
        val lowTarget = (total * 0.02f).roundToInt()
        val highTarget = (total * 0.96f).roundToInt()
        var acc = 0
        var pLow = 0
        var pHigh = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc <= lowTarget) pLow = i
            if (acc <= highTarget) pHigh = i
        }
        val span = (pHigh - pLow).coerceAtLeast(24)

        val outPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = ((77 * ((c ushr 16) and 0xFF) + 150 * ((c ushr 8) and 0xFF) + 29 * (c and 0xFF)) ushr 8)
            val stretched = (((lum - pLow) * 255) / span).coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (stretched shl 16) or (stretched shl 8) or stretched
        }
        val out = createBitmap(w, h)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Runs full Tiny CV Table & Summary-Box detection on a rendered page [Bitmap]. */
    fun detectFromBitmap(
        bitmap: Bitmap,
        pageNo: Int,
        renderScaleToPdf: Float,
    ): PageGeometry {
        val (grid, gridScale) = bitmapToGrid(bitmap, targetMaxDim = 1050)
        val pdfPerGridPx = 1.0f / (renderScaleToPdf * gridScale)
        return detectFromGrid(grid, pageNo, pdfPerGridPx)
    }

    /** Pure-Kotlin CV detection entrypoint (directly testable on JVM without Android Bitmap). */
    fun detectFromGrid(
        grid: GrayscaleGrid,
        pageNo: Int,
        coordScaleToPdf: Float = 1.0f,
    ): PageGeometry {
        val w = grid.width
        val h = grid.height
        if (w < 40 || h < 40) return PageGeometry(pageNo)

        val threshold = computeOtsuThreshold(grid).coerceIn(115, 228)
        val fg = BooleanArray(w * h)
        for (i in fg.indices) {
            val lum = grid.pixels[i].toInt() and 0xFF
            fg[i] = lum < threshold
        }

        val minHLen = maxOf(18, w / 16)
        val minVLen = maxOf(14, h / 32)
        val hLines = extractHorizontalLines(fg, w, h, minHLen, coordScaleToPdf)
        val vLines = extractVerticalLines(fg, w, h, minVLen, coordScaleToPdf)
        val shadedRects = extractShadedBoxes(grid, threshold, coordScaleToPdf)

        val pageWidthPts = w * coordScaleToPdf
        val pageHeightPts = h * coordScaleToPdf
        val baseGeo = buildGeometryFromPrimitives(
            pageNo = pageNo,
            pageWidthPts = pageWidthPts,
            pageHeightPts = pageHeightPts,
            rawHLines = hLines,
            rawVLines = vLines,
            rawRects = shadedRects,
        )

        // If no ruled multi-column table grid was found via lines, detect borderless columns via whitespace gutters.
        val tables = if (baseGeo.tables.any { it.columnBounds.size >= 3 }) {
            baseGeo.tables
        } else {
            val gutterTable = detectBorderlessGutterTable(
                fg = fg,
                w = w,
                h = h,
                pageNo = pageNo,
                coordScaleToPdf = coordScaleToPdf,
                startSearchY = if (pageNo == 1) (h * 0.25f).roundToInt() else (h * 0.04f).roundToInt(),
            )
            if (gutterTable != null) baseGeo.tables + gutterTable else baseGeo.tables
        }
        return baseGeo.copy(tables = tables)
    }

    /**
     * Shared structural clustering used by BOTH Tiny CV Bitmap detection and PDFBox Vector Geometry.
     * Clusters raw horizontal/vertical segments & filled/stroked rectangles into deduplicated
     * summary [RegionBox]es and multi-column [CvTableGrid]s.
     */
    fun buildGeometryFromPrimitives(
        pageNo: Int,
        pageWidthPts: Float,
        pageHeightPts: Float,
        rawHLines: List<HLine>,
        rawVLines: List<VLine>,
        rawRects: List<RawRect>,
    ): PageGeometry {
        val mergedH = mergeCollinearHLines(rawHLines)
        val mergedV = mergeCollinearVLines(rawVLines)

        // Collect candidate boxes from explicit rectangles + closed 4-sided line loops (rounded cards allowed up to 8pt corner gap).
        val rectCandidates = mutableListOf<RawRect>()
        for (r in rawRects) {
            if (r.width in 32f..(pageWidthPts * 0.94f) && r.height in 14f..(pageHeightPts * 0.85f)) {
                rectCandidates += r
            }
        }
        rectCandidates += findLineLoopBoxes(mergedH, mergedV, pageWidthPts, pageHeightPts)

        val dedupedRects = deduplicateRects(rectCandidates)
            .sortedByDescending { it.width * it.height } // Outer frames first, inner leaf boxes last

        val boxes = dedupedRects.mapIndexed { idx, r ->
            RegionBox(
                id = pageNo * 1000 + idx + 1,
                page = pageNo,
                x = r.x1.roundToInt(),
                y = r.y1.roundToInt(),
                width = r.width.roundToInt().coerceAtLeast(1),
                height = r.height.roundToInt().coerceAtLeast(1),
                isShaded = r.isShaded,
            )
        }

        val tables = detectRuledTableGrids(pageNo, pageWidthPts, mergedH, mergedV)
        return PageGeometry(page = pageNo, boxes = boxes, tables = tables)
    }

    /** Tags every [TextBlock] with the smallest-area enclosing [RegionBox.id] on its page. */
    fun assignBlocksToBoxes(
        blocks: List<TextBlock>,
        boxes: List<RegionBox>,
    ): List<TextBlock> {
        if (blocks.isEmpty() || boxes.isEmpty()) return blocks
        val byPage = boxes.groupBy { it.page }
        return blocks.map { b ->
            val pageBoxes = byPage[b.page].orEmpty()
            val bestBox = pageBoxes
                .filter { it.containsBlock(b.x, b.xEnd, b.y) }
                .minByOrNull { it.area }
            if (bestBox != null) b.copy(boxId = bestBox.id) else b
        }
    }

    /** Tags every [TextSpan] with the smallest-area enclosing [RegionBox.id] on its page. */
    fun assignSpansToBoxes(
        spans: List<TextSpan>,
        boxes: List<RegionBox>,
    ): List<TextSpan> {
        if (spans.isEmpty() || boxes.isEmpty()) return spans
        val byPage = boxes.groupBy { it.page }
        return spans.map { s ->
            val pageBoxes = byPage[s.page].orEmpty()
            val bestBox = pageBoxes
                .filter { it.containsBlock(s.x.roundToInt(), s.endX.roundToInt(), s.y.roundToInt()) }
                .minByOrNull { it.area }
            if (bestBox != null) s.copy(boxId = bestBox.id) else s
        }
    }

    private fun computeOtsuThreshold(grid: GrayscaleGrid): Int {
        val hist = IntArray(256)
        val step = if (grid.pixels.size > 400_000) 2 else 1
        var total = 0
        var i = 0
        while (i < grid.pixels.size) {
            hist[grid.pixels[i].toInt() and 0xFF]++
            total++
            i += step
        }
        if (total == 0) return 180

        var sum = 0.0
        for (t in 0..255) sum += t * hist[t]
        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var bestT = 180

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val diff = mB - mF
            val between = wB.toDouble() * wF.toDouble() * diff * diff
            if (between > maxVar) {
                maxVar = between
                bestT = t
            }
        }
        return bestT
    }

    private fun extractHorizontalLines(
        fg: BooleanArray,
        w: Int,
        h: Int,
        minLen: Int,
        scale: Float,
    ): List<HLine> {
        val out = mutableListOf<HLine>()
        for (y in 0 until h) {
            val rowOff = y * w
            var x = 0
            while (x < w) {
                if (!fg[rowOff + x]) {
                    x++
                    continue
                }
                val start = x
                var end = x
                var gap = 0
                x++
                while (x < w) {
                    if (fg[rowOff + x]) {
                        end = x
                        gap = 0
                    } else {
                        gap++
                        if (gap > 2) break
                    }
                    x++
                }
                val len = end - start + 1
                if (len >= minLen) {
                    out += HLine(y * scale, start * scale, (end + 1) * scale)
                }
            }
        }
        return out
    }

    private fun extractVerticalLines(
        fg: BooleanArray,
        w: Int,
        h: Int,
        minLen: Int,
        scale: Float,
    ): List<VLine> {
        val out = mutableListOf<VLine>()
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!fg[y * w + x]) {
                    y++
                    continue
                }
                val start = y
                var end = y
                var gap = 0
                y++
                while (y < h) {
                    if (fg[y * w + x]) {
                        end = y
                        gap = 0
                    } else {
                        gap++
                        if (gap > 2) break
                    }
                    y++
                }
                val len = end - start + 1
                if (len >= minLen) {
                    out += VLine(x * scale, start * scale, (end + 1) * scale)
                }
            }
        }
        return out
    }

    /**
     * Detects light-tinted shaded summary cards and table header bars (pixels darker than white
     * background `<= 246` and lighter than foreground ink `>= threshold`) clustered into solid boxes.
     */
    private fun extractShadedBoxes(
        grid: GrayscaleGrid,
        threshold: Int,
        scale: Float,
    ): List<RawRect> {
        val w = grid.width
        val h = grid.height
        val minBandWidth = maxOf(40, w / 6)
        val bandSegments = mutableListOf<Triple<Int, Int, Int>>() // y, x1, x2

        for (y in 0 until h) {
            val rowOff = y * w
            var x = 0
            while (x < w) {
                val lum = grid.pixels[rowOff + x].toInt() and 0xFF
                if (lum in threshold..246) {
                    val start = x
                    var end = x
                    var gap = 0
                    x++
                    while (x < w) {
                        val l2 = grid.pixels[rowOff + x].toInt() and 0xFF
                        // Allow dark ink text inside a shaded card while scanning across the tinted card width
                        if (l2 <= 246) {
                            if (l2 >= threshold) end = x
                            gap = 0
                        } else {
                            gap++
                            if (gap > 4) break
                        }
                        x++
                    }
                    if (end - start + 1 >= minBandWidth) {
                        bandSegments += Triple(y, start, end + 1)
                    }
                } else {
                    x++
                }
            }
        }

        if (bandSegments.isEmpty()) return emptyList()
        val rects = mutableListOf<RawRect>()
        // Group consecutive rows with matching horizontal spans into filled rectangles
        val active = mutableListOf<IntArray>() // [x1, x2, y1, y2]
        for ((y, x1, x2) in bandSegments) {
            val match = active.firstOrNull { cur ->
                abs(cur[0] - x1) <= 6 && abs(cur[1] - x2) <= 6 && (y - cur[3]) <= 2
            }
            if (match != null) {
                match[0] = minOf(match[0], x1)
                match[1] = maxOf(match[1], x2)
                match[3] = y
            } else {
                active += intArrayOf(x1, x2, y, y)
            }
        }
        for (a in active) {
            val heightPx = a[3] - a[2] + 1
            if (heightPx >= 12) {
                rects += RawRect(
                    x1 = a[0] * scale,
                    y1 = a[2] * scale,
                    x2 = a[1] * scale,
                    y2 = (a[3] + 1) * scale,
                    isShaded = true,
                )
            }
        }
        return rects
    }

    private fun mergeCollinearHLines(lines: List<HLine>): List<HLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<HLine> { it.y }.thenBy { it.x1 })
        val out = mutableListOf<HLine>()
        for (l in sorted) {
            val matchIdx = out.indexOfLast { abs(it.y - l.y) <= 2.2f && l.x1 <= it.x2 + 4f && l.x2 >= it.x1 - 4f }
            if (matchIdx >= 0) {
                val cur = out[matchIdx]
                out[matchIdx] = HLine((cur.y + l.y) / 2f, minOf(cur.x1, l.x1), maxOf(cur.x2, l.x2))
            } else {
                out += l
            }
        }
        return out
    }

    private fun mergeCollinearVLines(lines: List<VLine>): List<VLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<VLine> { it.x }.thenBy { it.y1 })
        val out = mutableListOf<VLine>()
        for (l in sorted) {
            val matchIdx = out.indexOfLast { abs(it.x - l.x) <= 2.2f && l.y1 <= it.y2 + 4f && l.y2 >= it.y1 - 4f }
            if (matchIdx >= 0) {
                val cur = out[matchIdx]
                out[matchIdx] = VLine((cur.x + l.x) / 2f, minOf(cur.y1, l.y1), maxOf(cur.y2, l.y2))
            } else {
                out += l
            }
        }
        return out
    }

    /** Finds closed boxes formed by pairs of horizontal & vertical lines (tolerant of rounded corners). */
    private fun findLineLoopBoxes(
        hLines: List<HLine>,
        vLines: List<VLine>,
        pageW: Float,
        pageH: Float,
    ): List<RawRect> {
        if (hLines.size < 2 || vLines.size < 2) return emptyList()
        val tol = 8f
        val found = mutableListOf<RawRect>()

        // Limit combinatorics cleanly by scanning sorted H-lines in pairs with valid height
        val sortedH = hLines.sortedBy { it.y }
        for (i in sortedH.indices) {
            val top = sortedH[i]
            if (top.length < 32f) continue
            for (j in (i + 1) until sortedH.size) {
                val bot = sortedH[j]
                val boxH = bot.y - top.y
                if (boxH < 14f) continue
                if (boxH > pageH * 0.85f) break
                if (abs(top.x1 - bot.x1) > tol || abs(top.x2 - bot.x2) > tol) continue
                val boxW = maxOf(top.x2, bot.x2) - minOf(top.x1, bot.x1)
                if (boxW < 32f || boxW > pageW * 0.94f) continue

                val leftX = (top.x1 + bot.x1) / 2f
                val rightX = (top.x2 + bot.x2) / 2f
                val hasLeft = vLines.any { v ->
                    abs(v.x - leftX) <= tol && v.y1 <= top.y + tol && v.y2 >= bot.y - tol
                }
                val hasRight = vLines.any { v ->
                    abs(v.x - rightX) <= tol && v.y1 <= top.y + tol && v.y2 >= bot.y - tol
                }
                if (hasLeft && hasRight) {
                    found += RawRect(minOf(top.x1, bot.x1), top.y, maxOf(top.x2, bot.x2), bot.y)
                }
            }
        }
        return found
    }

    private fun deduplicateRects(rects: List<RawRect>): List<RawRect> {
        val out = mutableListOf<RawRect>()
        for (r in rects) {
            val dup = out.any {
                abs(it.x1 - r.x1) <= 5f &&
                    abs(it.y1 - r.y1) <= 5f &&
                    abs(it.x2 - r.x2) <= 5f &&
                    abs(it.y2 - r.y2) <= 5f
            }
            if (!dup) out += r
        }
        return out
    }

    /** Clusters intersecting H & V lines into ruled multi-column [CvTableGrid]s. */
    private fun detectRuledTableGrids(
        pageNo: Int,
        pageWidthPts: Float,
        hLines: List<HLine>,
        vLines: List<VLine>,
    ): List<CvTableGrid> {
        val tableHLines = hLines.filter { it.length >= pageWidthPts * 0.42f }.sortedBy { it.y }
        if (tableHLines.size < 2) return emptyList()

        // Cluster consecutive wide horizontal lines separated by <= 90pt into table regions
        val clusters = mutableListOf<MutableList<HLine>>()
        for (hl in tableHLines) {
            val last = clusters.lastOrNull()
            if (last != null && (hl.y - last.last().y) <= 95f &&
                minOf(hl.x2, last.last().x2) - maxOf(hl.x1, last.last().x1) >= pageWidthPts * 0.35f
            ) {
                last += hl
            } else {
                clusters += mutableListOf(hl)
            }
        }

        val grids = mutableListOf<CvTableGrid>()
        for (cluster in clusters) {
            if (cluster.size < 2) continue
            val yStart = cluster.first().y.roundToInt()
            val yEnd = cluster.last().y.roundToInt()
            val xStart = cluster.minOf { it.x1 }.roundToInt()
            val xEnd = cluster.maxOf { it.x2 }.roundToInt()
            val tableHeight = (yEnd - yStart).coerceAtLeast(1)

            val rowBands = cluster.zipWithNext { a, b ->
                a.y.roundToInt()..b.y.roundToInt()
            }.filter { (it.last - it.first) >= 8 }

            // Find vertical lines inside this table's Y span
            val interiorVs = vLines.filter { v ->
                v.x >= xStart - 4 && v.x <= xEnd + 4 &&
                    minOf(v.y2, yEnd.toFloat()) - maxOf(v.y1, yStart.toFloat()) >= minOf(22f, tableHeight * 0.35f)
            }.map { it.x.roundToInt() }
                .distinct()
                .sorted()

            val colXs = mutableListOf<Int>()
            for (vx in interiorVs) {
                if (colXs.isEmpty() || vx - colXs.last() >= 14) colXs += vx
            }
            if (colXs.size >= 4) {
                val colBounds = colXs.zipWithNext { a, b -> a..b }.filter { (it.last - it.first) >= 14 }
                if (colBounds.size >= 3) {
                    grids += CvTableGrid(
                        page = pageNo,
                        xStart = xStart,
                        yStart = yStart,
                        xEnd = xEnd,
                        yEnd = yEnd,
                        columnBounds = colBounds,
                        rowBands = rowBands,
                        hasRuledLines = true,
                    )
                }
            } else if (rowBands.size >= 2) {
                grids += CvTableGrid(
                    page = pageNo,
                    xStart = xStart,
                    yStart = yStart,
                    xEnd = xEnd,
                    yEnd = yEnd,
                    columnBounds = emptyList(),
                    rowBands = rowBands,
                    hasRuledLines = true,
                )
            }
        }
        return grids
    }

    /**
     * Borderless CV table detector: locates wide whitespace column gutters (zero foreground ink
     * across >= 6pt horizontally) inside dense multi-row transaction regions.
     */
    private fun detectBorderlessGutterTable(
        fg: BooleanArray,
        w: Int,
        h: Int,
        pageNo: Int,
        coordScaleToPdf: Float,
        startSearchY: Int,
    ): CvTableGrid? {
        val y0 = startSearchY.coerceIn(0, h - 1)
        val y1 = (h * 0.92f).roundToInt().coerceIn(y0 + 1, h)
        if (y1 - y0 < 40) return null

        val rowInk = IntArray(h)
        for (y in y0 until y1) {
            val rowOff = y * w
            var c = 0
            for (x in 0 until w) if (fg[rowOff + x]) c++
            rowInk[y] = c
        }

        // Find contiguous Y span containing actual ink rows
        val activeYs = (y0 until y1).filter { rowInk[it] >= 8 }
        if (activeYs.size < 18) return null
        val tableTopPx = activeYs.first()
        val tableBotPx = activeYs.last()
        val spanH = (tableBotPx - tableTopPx + 1).coerceAtLeast(1)

        val colInk = IntArray(w)
        for (y in tableTopPx..tableBotPx) {
            val rowOff = y * w
            for (x in 0 until w) if (fg[rowOff + x]) colInk[x]++
        }

        // A column has content where column ink >= 1% of span height; clean gutters stay near 0
        val noiseCeiling = maxOf(1, (spanH * 0.01f).roundToInt())
        val contentColRuns = mutableListOf<IntRange>()
        var x = 0
        while (x < w) {
            if (colInk[x] <= noiseCeiling) {
                x++
                continue
            }
            val s = x
            var e = x
            var gap = 0
            val minGutterPx = maxOf(6, (10f / coordScaleToPdf).roundToInt())
            x++
            while (x < w) {
                if (colInk[x] > noiseCeiling) {
                    e = x
                    gap = 0
                } else {
                    gap++
                    if (gap >= minGutterPx) break
                }
                x++
            }
            if (e - s + 1 >= 6) contentColRuns += s..e
        }

        if (contentColRuns.size < 3) return null
        val colBoundsPdf = contentColRuns.map { run ->
            (run.first * coordScaleToPdf).roundToInt()..(run.last * coordScaleToPdf).roundToInt()
        }
        return CvTableGrid(
            page = pageNo,
            xStart = colBoundsPdf.first().first,
            yStart = (tableTopPx * coordScaleToPdf).roundToInt(),
            xEnd = colBoundsPdf.last().last,
            yEnd = (tableBotPx * coordScaleToPdf).roundToInt(),
            columnBounds = colBoundsPdf,
            rowBands = emptyList(),
            hasRuledLines = false,
        )
    }
}
