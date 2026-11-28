package com.devanandsb.ccmanager.statementparser.data.cv

import android.graphics.Path
import android.graphics.PointF
import com.devanandsb.ccmanager.statementparser.domain.model.PageGeometry
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import kotlin.math.abs

/**
 * Extracts exact drawn rectangles, summary-card borders, shaded header bands, and
 * horizontal/vertical table ruling lines directly from PDF vector graphics streams
 * via [PDFGraphicsStreamEngine], ignoring invisible clipping paths and white fills.
 */
object PdfVectorGeometryExtractor {

    fun extractPageGeometry(page: PDPage, pageNo: Int): PageGeometry {
        val box = page.cropBox ?: page.mediaBox
        val leftX = box?.lowerLeftX ?: 0f
        val topY = box?.upperRightY ?: 842f
        val bottomY = box?.lowerLeftY ?: 0f
        val rightX = box?.upperRightX ?: 595f
        val pageW = box?.width ?: 595f
        val pageH = box?.height ?: 842f
        val rotation = ((page.rotation % 360) + 360) % 360

        fun toTopLeft(px: Float, py: Float): Pair<Float, Float> = when (rotation) {
            90 -> (py - bottomY) to (px - leftX)
            180 -> (rightX - px) to (py - bottomY)
            270 -> (topY - py) to (rightX - px)
            else -> (px - leftX) to (topY - py)
        }

        val hLines = mutableListOf<TinyCvTableDetector.HLine>()
        val vLines = mutableListOf<TinyCvTableDetector.VLine>()
        val rects = mutableListOf<TinyCvTableDetector.RawRect>()

        val engine = object : PDFGraphicsStreamEngine(page) {
            private val pendingSegs = mutableListOf<FloatArray>() // [x1, y1, x2, y2]
            private val pendingRects = mutableListOf<FloatArray>() // [x1, y1, x2, y2]
            private var curX = 0f
            private var curY = 0f
            private var startX = 0f
            private var startY = 0f

            override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
                val (ax, ay) = toTopLeft(p0.x, p0.y)
                val (bx, by) = toTopLeft(p1.x, p1.y)
                val (cx, cy) = toTopLeft(p2.x, p2.y)
                val (dx, dy) = toTopLeft(p3.x, p3.y)
                val minX = minOf(ax, bx, cx, dx)
                val maxX = maxOf(ax, bx, cx, dx)
                val minY = minOf(ay, by, cy, dy)
                val maxY = maxOf(ay, by, cy, dy)
                pendingRects += floatArrayOf(minX, minY, maxX, maxY)
                curX = ax
                curY = ay
            }

            override fun moveTo(x: Float, y: Float) {
                val (tx, ty) = toTopLeft(x, y)
                curX = tx
                curY = ty
                startX = tx
                startY = ty
            }

            override fun lineTo(x: Float, y: Float) {
                val (tx, ty) = toTopLeft(x, y)
                pendingSegs += floatArrayOf(curX, curY, tx, ty)
                curX = tx
                curY = ty
            }

            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val (tx, ty) = toTopLeft(x3, y3)
                curX = tx
                curY = ty
            }

            override fun getCurrentPoint(): PointF = PointF(curX, curY)

            override fun closePath() {
                if (abs(curX - startX) > 0.5f || abs(curY - startY) > 0.5f) {
                    pendingSegs += floatArrayOf(curX, curY, startX, startY)
                }
                curX = startX
                curY = startY
            }

            override fun strokePath() {
                commitPrimitives(isFill = false, isStroke = true)
            }

            override fun fillPath(windingRule: Path.FillType) {
                commitPrimitives(isFill = true, isStroke = false)
            }

            override fun fillAndStrokePath(windingRule: Path.FillType) {
                commitPrimitives(isFill = true, isStroke = true)
            }

            override fun endPath() {
                pendingSegs.clear()
                pendingRects.clear()
            }

            override fun clip(windingRule: Path.FillType) {
                // Clipping uses the pending path; keep it only if stroked/filled later.
            }

            override fun drawImage(pdImage: PDImage) = Unit
            override fun shadingFill(shadingName: COSName) = Unit

            private fun isWhiteNonStrokingFill(): Boolean {
                val color = runCatching { graphicsState.nonStrokingColor }.getOrNull() ?: return false
                val comps = runCatching { color.components }.getOrNull() ?: return false
                return when (comps.size) {
                    1 -> comps[0] > 0.985f // Grayscale white
                    3 -> comps[0] > 0.985f && comps[1] > 0.985f && comps[2] > 0.985f // RGB white
                    4 -> comps[0] < 0.015f && comps[1] < 0.015f && comps[2] < 0.015f && comps[3] < 0.015f // CMYK white
                    else -> false
                }
            }

            private fun commitPrimitives(isFill: Boolean, isStroke: Boolean) {
                val ignoreWhiteFill = isFill && !isStroke && isWhiteNonStrokingFill()
                if (!ignoreWhiteFill) {
                    for (r in pendingRects) {
                        val w = r[2] - r[0]
                        val h = r[3] - r[1]
                        when {
                            h <= 3.5f && w >= 18f -> {
                                hLines += TinyCvTableDetector.HLine((r[1] + r[3]) / 2f, r[0], r[2])
                            }
                            w <= 3.5f && h >= 14f -> {
                                vLines += TinyCvTableDetector.VLine((r[0] + r[2]) / 2f, r[1], r[3])
                            }
                            w >= 28f && h >= 14f -> {
                                rects += TinyCvTableDetector.RawRect(
                                    x1 = r[0],
                                    y1 = r[1],
                                    x2 = r[2],
                                    y2 = r[3],
                                    isShaded = isFill && !isStroke,
                                )
                            }
                        }
                    }

                    // Commit explicit horizontal/vertical line segments and rounded-box paths
                    val segMinX = pendingSegs.minOfOrNull { minOf(it[0], it[2]) } ?: 0f
                    val segMaxX = pendingSegs.maxOfOrNull { maxOf(it[0], it[2]) } ?: 0f
                    val segMinY = pendingSegs.minOfOrNull { minOf(it[1], it[3]) } ?: 0f
                    val segMaxY = pendingSegs.maxOfOrNull { maxOf(it[1], it[3]) } ?: 0f
                    for (s in pendingSegs) {
                        val dx = abs(s[2] - s[0])
                        val dy = abs(s[3] - s[1])
                        if (dy <= 1.5f && dx >= 18f) {
                            hLines += TinyCvTableDetector.HLine((s[1] + s[3]) / 2f, minOf(s[0], s[2]), maxOf(s[0], s[2]))
                        } else if (dx <= 1.5f && dy >= 14f) {
                            vLines += TinyCvTableDetector.VLine((s[0] + s[2]) / 2f, minOf(s[1], s[3]), maxOf(s[1], s[3]))
                        }
                    }
                    // If a closed multi-segment path forms a single rounded rectangle/card, capture it directly
                    if (pendingSegs.size in 4..12) {
                        val bw = segMaxX - segMinX
                        val bh = segMaxY - segMinY
                        if (bw in 32f..(pageW * 0.94f) && bh in 14f..(pageH * 0.85f)) {
                            rects += TinyCvTableDetector.RawRect(
                                x1 = segMinX,
                                y1 = segMinY,
                                x2 = segMaxX,
                                y2 = segMaxY,
                                isShaded = isFill && !isStroke,
                            )
                        }
                    }
                }
                pendingSegs.clear()
                pendingRects.clear()
            }
        }

        runCatching { engine.processPage(page) }
        val effW = if (rotation == 90 || rotation == 270) pageH else pageW
        val effH = if (rotation == 90 || rotation == 270) pageW else pageH
        return TinyCvTableDetector.buildGeometryFromPrimitives(
            pageNo = pageNo,
            pageWidthPts = effW,
            pageHeightPts = effH,
            rawHLines = hLines,
            rawVLines = vLines,
            rawRects = rects,
        )
    }
}
