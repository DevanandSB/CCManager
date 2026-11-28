package com.devanandsb.ccmanager.statementparser.domain.model

/**
 * Axis-aligned bounding box of a visually enclosed summary card, panel, or table header band
 * detected either by the Tiny CV Bitmap detector (morphological lines / contours / shading)
 * or by PDFBox vector graphics path inspection. Coordinates use top-left origin in PDF points.
 */
data class RegionBox(
    val id: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isShaded: Boolean = false,
) {
    val xEnd: Int get() = x + width.coerceAtLeast(1)
    val yEnd: Int get() = y + height.coerceAtLeast(1)
    val area: Long get() = width.toLong().coerceAtLeast(1L) * height.toLong().coerceAtLeast(1L)

    fun containsPoint(px: Int, py: Int, pad: Int = 4): Boolean =
        px in (x - pad)..(xEnd + pad) && py in (y - pad)..(yEnd + pad)

    fun containsBlock(bx: Int, bxEnd: Int, by: Int, pad: Int = 4): Boolean {
        val center = (bx + bxEnd) / 2
        return center in (x - pad)..(xEnd + pad) && by in (y - pad)..(yEnd + pad)
    }
}

/**
 * Structured table grid (either ruled via horizontal/vertical lines or borderless via
 * CV whitespace-gutter projection) used to isolate column intervals and row bands.
 */
data class CvTableGrid(
    val page: Int,
    val xStart: Int,
    val yStart: Int,
    val xEnd: Int,
    val yEnd: Int,
    val columnBounds: List<IntRange>,
    val rowBands: List<IntRange>,
    val hasRuledLines: Boolean,
) {
    fun columnRangeForX(centerX: Int): IntRange? =
        columnBounds.firstOrNull { centerX in (it.first - 4)..(it.last + 4) }

    fun rowBandForY(y: Int): IntRange? =
        rowBands.firstOrNull { y in (it.first - 3)..(it.last + 3) }
}

data class PageGeometry(
    val page: Int,
    val boxes: List<RegionBox> = emptyList(),
    val tables: List<CvTableGrid> = emptyList(),
)
