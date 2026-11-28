package com.devanandsb.ccmanager.statementparser.domain.model

/**
 * Dynamic horizontal bounds [xStart, xEnd] for a single table column on a statement page.
 */
data class ColumnInterval(
    val xStart: Int,
    val xEnd: Int,
) {
    val center: Int get() = (xStart + xEnd) / 2

    fun contains(block: TextBlock, tolerance: Int = 8): Boolean {
        val left = xStart - tolerance
        val right = xEnd + tolerance
        return block.centerX in left..right || (block.x >= left && block.xEnd <= right)
    }
}

/**
 * Column mapping discovered dynamically from a statement's transaction table header row.
 * Extra columns (e.g. Reward Points, Reference No.) are tracked in [ignoredIntervals] so
 * values inside them never bleed into Description or Amount.
 */
data class ColumnLayout(
    val headerRowIndex: Int,
    val headerPage: Int,
    val headerY: Int,
    val dateColumn: ColumnInterval,
    val descriptionColumn: ColumnInterval,
    val amountColumn: ColumnInterval,
    val creditAmountColumn: ColumnInterval? = null,
    val crDrIndicatorColumn: ColumnInterval? = null,
    val ignoredIntervals: List<ColumnInterval> = emptyList(),
) {
    fun isIgnored(block: TextBlock): Boolean =
        ignoredIntervals.any { interval ->
            block.centerX in (interval.xStart - 6)..(interval.xEnd + 6)
        }
}
