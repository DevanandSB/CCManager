package com.devanandsb.ccmanager.statementparser.domain.model

import kotlinx.serialization.Serializable

/**
 * A positioned text chunk extracted from a PDF page via PDFBox vector coordinates
 * or ML Kit offline OCR bounding boxes. Coordinates use top-left origin.
 * [boxId] optionally links the block to its smallest enclosing CV/vector [RegionBox].
 */
@Serializable
data class TextBlock(
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val page: Int,
    val boxId: Int? = null,
) {
    val xEnd: Int get() = x + width.coerceAtLeast(1)
    val centerX: Int get() = x + width.coerceAtLeast(1) / 2
}
