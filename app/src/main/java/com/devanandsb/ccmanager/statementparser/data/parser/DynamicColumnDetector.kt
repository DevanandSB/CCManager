package com.devanandsb.ccmanager.statementparser.data.parser

import com.devanandsb.ccmanager.statementparser.domain.model.ColumnInterval
import com.devanandsb.ccmanager.statementparser.domain.model.ColumnLayout
import com.devanandsb.ccmanager.statementparser.domain.model.CvTableGrid
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import kotlin.math.abs

/**
 * Dynamically detects transaction table column X-intervals from header synonym dictionaries,
 * optionally tightened with CV/vector-detected [CvTableGrid] column walls, adapting automatically
 * to bank/card layout shifts (including 2-line wrapped headers and multi-page X shifts) and
 * ignoring auxiliary columns like Reward Points, Merchant Category, Serial No., or Reference No.
 */
class DynamicColumnDetector {

    private enum class HeaderRole {
        DATE,
        DESCRIPTION,
        AMOUNT,
        DEBIT_AMOUNT,
        CREDIT_AMOUNT,
        CR_DR_INDICATOR,
        IGNORED,
    }

    private data class HeaderCell(
        val text: String,
        val x: Int,
        val xEnd: Int,
        val role: HeaderRole,
    )

    fun detect(
        rows: List<List<TextBlock>>,
        tables: List<CvTableGrid> = emptyList(),
    ): ColumnLayout? {
        for (rowIndex in rows.indices) {
            detectAt(rowIndex, rows, tables)?.let { return it }
        }
        return null
    }

    /**
     * Attempts to detect a single-row or 2-row merged table header starting at [rowIndex].
     * Used both initially and when a subsequent page shifts table columns horizontally (e.g. ICICI Page 2).
     */
    fun detectAt(
        rowIndex: Int,
        rows: List<List<TextBlock>>,
        tables: List<CvTableGrid> = emptyList(),
    ): ColumnLayout? {
        val row = rows.getOrNull(rowIndex) ?: return null
        if (row.size >= 3) {
            tryMatchHeaderRow(rowIndex, row, tables)?.let { return it }
        }
        val nextRow = rows.getOrNull(rowIndex + 1) ?: return null
        if (row.firstOrNull()?.page != nextRow.firstOrNull()?.page) return null
        val yDiff = nextRow.first().y - row.first().y
        if (yDiff in 1..22 && !looksLikeDataRow(nextRow)) {
            val mergedTwoRow = mergeTwoVerticalHeaderRows(row, nextRow)
            if (mergedTwoRow.size >= 3) {
                tryMatchHeaderRow(rowIndex + 1, mergedTwoRow, tables)?.let { return it }
            }
        }
        return null
    }

    /**
     * Checks whether [row] is a repeated table header on a subsequent page so rows
     * above/matching it aren't merged as transaction descriptions.
     */
    fun isHeaderRow(row: List<TextBlock>): Boolean = tryMatchHeaderRow(0, row) != null

    private fun looksLikeDataRow(row: List<TextBlock>): Boolean {
        val joined = row.joinToString(" ") { it.text }
        if (TransactionRowParser.parseDateIso(joined) != null) return true
        return Regex("\\d+\\.\\d{2}").containsMatchIn(joined)
    }

    private fun mergeTwoVerticalHeaderRows(
        top: List<TextBlock>,
        bottom: List<TextBlock>,
    ): List<TextBlock> {
        val page = top.first().page
        val y = bottom.first().y
        val consumedBottom = BooleanArray(bottom.size)
        val out = mutableListOf<TextBlock>()

        for (tb in top.sortedBy { it.x }) {
            val matchIdx = bottom.indices.firstOrNull { idx ->
                !consumedBottom[idx] && horizontallyAligned(tb, bottom[idx])
            }
            if (matchIdx != null) {
                consumedBottom[matchIdx] = true
                val bb = bottom[matchIdx]
                out += TextBlock(
                    text = "${tb.text.trim()} ${bb.text.trim()}".replace(Regex("\\s+"), " ").trim(),
                    x = minOf(tb.x, bb.x),
                    y = y,
                    width = maxOf(tb.xEnd, bb.xEnd) - minOf(tb.x, bb.x),
                    page = page,
                )
            } else {
                out += tb.copy(y = y)
            }
        }
        for (idx in bottom.indices) {
            if (!consumedBottom[idx]) out += bottom[idx]
        }
        return out.sortedBy { it.x }
    }

    private fun horizontallyAligned(a: TextBlock, b: TextBlock): Boolean {
        val overlap = minOf(a.xEnd, b.xEnd) - maxOf(a.x, b.x)
        return overlap >= -8 || abs(a.centerX - b.centerX) <= 36
    }

    private fun tryMatchHeaderRow(
        rowIndex: Int,
        row: List<TextBlock>,
        tables: List<CvTableGrid> = emptyList(),
    ): ColumnLayout? {
        val sorted = row.sortedBy { it.x }
        val mergedCells = mergeAdjacentHeaderBlocks(sorted)
        val classified = mergedCells.map { cell ->
            cell.copy(role = classifyHeader(cell.text))
        }

        val dateCell = classified.firstOrNull { it.role == HeaderRole.DATE } ?: return null
        val descCell = classified.firstOrNull { it.role == HeaderRole.DESCRIPTION } ?: return null
        val amountCell = classified.firstOrNull { it.role == HeaderRole.AMOUNT }
        val debitCell = classified.firstOrNull { it.role == HeaderRole.DEBIT_AMOUNT }
        val creditCell = classified.firstOrNull { it.role == HeaderRole.CREDIT_AMOUNT }
        val crDrCell = classified.firstOrNull { it.role == HeaderRole.CR_DR_INDICATOR }

        // Require Date + Description + (Amount OR separate Debit/Credit)
        val primaryAmountCell = amountCell ?: debitCell ?: creditCell ?: return null

        // Sanity check: avoid matching narrative prose or formula equation lines
        val joinedLow = mergedCells.joinToString(" ") { it.text }.lowercase()
        if (joinedLow.contains("previous balance") && joinedLow.contains("=")) return null
        if (mergedCells.sumOf { it.text.length } > 170) return null
        if (dateCell.x >= primaryAmountCell.xEnd) return null

        // Ignore left sidebar headings sitting to the left of Date (e.g. "SPENDS OVERVIEW")
        val tableCells = classified.filter { it.x >= dateCell.x - 16 }.sortedBy { it.x }
        val baseIntervals = buildIntervals(tableCells)
        val headerPage = sorted.first().page
        val headerY = sorted.first().y
        val matchingGrid = tables.firstOrNull { grid ->
            grid.page == headerPage &&
                grid.columnBounds.size >= 3 &&
                headerY in (grid.yStart - 16)..(grid.yEnd + 4)
        }
        val intervals = if (matchingGrid != null) {
            tightenIntervalsWithCvGrid(tableCells, baseIntervals, matchingGrid)
        } else {
            baseIntervals
        }

        val dateInterval = intervals[dateCell] ?: return null
        val descInterval = intervals[descCell] ?: return null
        val amountInterval = intervals[primaryAmountCell] ?: return null
        val creditAmountInterval = if (amountCell == null && creditCell != null && creditCell != primaryAmountCell) {
            intervals[creditCell]
        } else {
            null
        }
        val crDrInterval = crDrCell?.let { intervals[it] }
        val ignored = tableCells
            .filter { it.role == HeaderRole.IGNORED }
            .mapNotNull { intervals[it] }

        return ColumnLayout(
            headerRowIndex = rowIndex,
            headerPage = headerPage,
            headerY = headerY,
            dateColumn = dateInterval,
            descriptionColumn = descInterval,
            amountColumn = amountInterval,
            creditAmountColumn = creditAmountInterval,
            crDrIndicatorColumn = crDrInterval,
            ignoredIntervals = ignored,
        )
    }

    /**
     * Tightens heuristic header midpoint intervals using CV/vector-detected column walls when each
     * CV column maps 1-to-1 to a header cell and encloses it cleanly.
     */
    private fun tightenIntervalsWithCvGrid(
        ordered: List<HeaderCell>,
        base: Map<HeaderCell, ColumnInterval>,
        grid: CvTableGrid,
    ): Map<HeaderCell, ColumnInterval> {
        val matches = LinkedHashMap<HeaderCell, IntRange>()
        val usedCols = HashMap<IntRange, Int>()
        for (cell in ordered) {
            val center = (cell.x + cell.xEnd) / 2
            val cvCol = grid.columnRangeForX(center) ?: continue
            val colWidth = cvCol.last - cvCol.first
            val cellWidth = cell.xEnd - cell.x
            if (colWidth < 14 || colWidth < cellWidth - 10) continue
            if (!grid.hasRuledLines && (cvCol.first > cell.x + 8 || cvCol.last < cell.xEnd - 8)) continue
            matches[cell] = cvCol
            usedCols[cvCol] = (usedCols[cvCol] ?: 0) + 1
        }
        if (matches.size < 3) return base
        val out = LinkedHashMap<HeaderCell, ColumnInterval>(base.size)
        for ((cell, baseInterval) in base) {
            val cvCol = matches[cell]
            if (cvCol != null && usedCols[cvCol] == 1) {
                out[cell] = ColumnInterval(cvCol.first, cvCol.last)
            } else {
                out[cell] = baseInterval
            }
        }
        return out
    }

    private fun mergeAdjacentHeaderBlocks(sorted: List<TextBlock>): List<HeaderCell> {
        val result = mutableListOf<HeaderCell>()
        for (block in sorted) {
            val prev = result.lastOrNull()
            if (prev == null) {
                result += HeaderCell(block.text.trim(), block.x, block.xEnd, HeaderRole.IGNORED)
            } else {
                val gap = block.x - prev.xEnd
                val combined = "${prev.text} ${block.text.trim()}"
                val prevRole = classifyHeader(prev.text)
                val currRole = classifyHeader(block.text.trim())
                val combinedRole = classifyHeader(combined)

                val distinctKnownColumns = prevRole != HeaderRole.IGNORED &&
                    currRole != HeaderRole.IGNORED &&
                    prevRole != currRole

                val shouldMerge = !distinctKnownColumns && (
                    gap <= 8 || (
                        gap <= 24 &&
                            combinedRole != HeaderRole.IGNORED &&
                            (prevRole == HeaderRole.IGNORED || currRole == HeaderRole.IGNORED)
                        )
                    )
                if (shouldMerge) {
                    result[result.lastIndex] = HeaderCell(
                        text = combined,
                        x = prev.x,
                        xEnd = maxOf(prev.xEnd, block.xEnd),
                        role = HeaderRole.IGNORED,
                    )
                } else {
                    result += HeaderCell(block.text.trim(), block.x, block.xEnd, HeaderRole.IGNORED)
                }
            }
        }
        return result
    }

    private fun buildIntervals(ordered: List<HeaderCell>): Map<HeaderCell, ColumnInterval> {
        val map = LinkedHashMap<HeaderCell, ColumnInterval>(ordered.size)
        for ((index, cell) in ordered.withIndex()) {
            val prev = ordered.getOrNull(index - 1)
            val next = ordered.getOrNull(index + 1)

            val interval = when (cell.role) {
                HeaderRole.DATE -> {
                    val left = prev?.let { (it.xEnd + cell.x) / 2 + 1 } ?: (cell.x - 18).coerceAtLeast(0)
                    val right = next?.let { (cell.xEnd + it.x) / 2 - 1 } ?: (cell.xEnd + 24)
                    ColumnInterval(minOf(left, cell.x - 8).coerceAtLeast(0), maxOf(right, cell.xEnd + 14))
                }

                HeaderRole.DESCRIPTION -> {
                    val left = prev?.let { maxOf(it.xEnd + 4, (it.xEnd + cell.x) / 2 + 1) }
                        ?: (cell.x - 14).coerceAtLeast(0)
                    val right = next?.let {
                        val nextSafetyLeft = when (it.role) {
                            HeaderRole.AMOUNT, HeaderRole.DEBIT_AMOUNT, HeaderRole.CREDIT_AMOUNT ->
                                maxOf(cell.xEnd + 20, it.x - 56)
                            HeaderRole.IGNORED -> {
                                // Reserve extra left slack for Merchant Category / Reward headers whose values are wider than the header text
                                val isCategory = it.text.lowercase().let { t -> t.contains("category") || t.contains("mcc") }
                                if (isCategory) maxOf(cell.xEnd + 16, it.x - 32) else maxOf(cell.xEnd + 16, it.x - 10)
                            }
                            else -> (it.x - 8).coerceAtLeast(cell.xEnd + 8)
                        }
                        maxOf(cell.xEnd + 16, nextSafetyLeft)
                    } ?: (cell.xEnd + 160)
                    ColumnInterval(left, right)
                }

                HeaderRole.AMOUNT, HeaderRole.DEBIT_AMOUNT, HeaderRole.CREDIT_AMOUNT -> {
                    val prevRightEnd = prev?.let {
                        when (it.role) {
                            HeaderRole.DESCRIPTION -> map[it]?.xEnd?.plus(1) ?: (it.xEnd + 12)
                            else -> it.xEnd + 8
                        }
                    } ?: 0
                    val left = maxOf(prevRightEnd, cell.x - 65).coerceAtLeast(0)
                    val right = next?.let { (cell.xEnd + it.x) / 2 - 1 } ?: (cell.xEnd + 48)
                    ColumnInterval(minOf(left, cell.x - 8).coerceAtLeast(0), maxOf(right, cell.xEnd + 32))
                }

                HeaderRole.CR_DR_INDICATOR -> {
                    val prevRight = prev?.let { (it.xEnd + cell.x) / 2 + 1 } ?: (cell.x - 12).coerceAtLeast(0)
                    val nextLeft = next?.let { (cell.xEnd + it.x) / 2 - 1 } ?: (cell.xEnd + 18)
                    ColumnInterval(prevRight, maxOf(nextLeft, cell.xEnd + 12))
                }

                HeaderRole.IGNORED -> {
                    val isCategory = cell.text.lowercase().let { t -> t.contains("category") || t.contains("mcc") }
                    val leftSlack = if (isCategory) 30 else 16
                    val left = prev?.let { maxOf(it.xEnd + 4, cell.x - leftSlack) } ?: (cell.x - 12).coerceAtLeast(0)
                    val right = next?.let { minOf(it.x - 4, cell.xEnd + 24) } ?: (cell.xEnd + 24)
                    ColumnInterval(left, maxOf(right, cell.xEnd + 4))
                }
            }
            map[cell] = interval
        }
        return map
    }

    private fun classifyHeader(raw: String): HeaderRole {
        val norm = raw.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

        if (IGNORED_HEADER_KEYWORDS.any { norm.contains(it) }) return HeaderRole.IGNORED
        if (CR_DR_SYNONYMS.any { matchesHeaderTerm(norm, it) }) return HeaderRole.CR_DR_INDICATOR
        if (DATE_SYNONYMS.any { matchesHeaderTerm(norm, it) }) return HeaderRole.DATE
        if (DESC_SYNONYMS.any { matchesHeaderTerm(norm, it) }) return HeaderRole.DESCRIPTION
        if (AMOUNT_SYNONYMS.any { matchesHeaderTerm(norm, it) }) return HeaderRole.AMOUNT
        if (DEBIT_SYNONYMS.any { matchesHeaderTerm(norm, it) }) return HeaderRole.DEBIT_AMOUNT
        if (CREDIT_SYNONYMS.any { matchesHeaderTerm(norm, it) }) return HeaderRole.CREDIT_AMOUNT
        return HeaderRole.IGNORED
    }

    private fun matchesHeaderTerm(normalizedCell: String, term: String): Boolean {
        if (normalizedCell == term) return true
        if (normalizedCell.startsWith("$term ") || normalizedCell.startsWith("$term(")) return true
        val escaped = Regex.escape(term)
        return Regex("(^|\\b)$escaped($|\\b|\\(|\\.)").containsMatchIn(normalizedCell)
    }

    companion object {
        private val DATE_SYNONYMS = listOf(
            "transaction date",
            "txn date",
            "txn. date",
            "post date",
            "tran date",
            "value date",
            "posting date",
            "date & time",
            "date",
        )

        private val DESC_SYNONYMS = listOf(
            "transaction details",
            "transaction description",
            "transaction/ details",
            "merchant / description",
            "merchant name",
            "description",
            "particulars",
            "narration",
            "merchant",
            "details",
        )

        private val AMOUNT_SYNONYMS = listOf(
            "amount (in rs.)",
            "amount (in rs)",
            "amount(in rs.)",
            "amount (in ₹)",
            "amount (in `)",
            "amount (in",
            "amount(in",
            "amount (inr)",
            "amount (rs.)",
            "amount (rs)",
            "billing amount",
            "debit/credit",
            "amount",
            "amt (rs.)",
            "amt",
            "inr",
        )

        private val CR_DR_SYNONYMS = listOf(
            "cr/dr",
            "dr/cr",
            "cr / dr",
            "dr / cr",
            "type",
        )

        private val DEBIT_SYNONYMS = listOf(
            "debit (in rs.)",
            "debit amount",
            "debits",
            "debit",
        )

        private val CREDIT_SYNONYMS = listOf(
            "credit (in rs.)",
            "credit amount",
            "credits",
            "credit",
        )

        private val IGNORED_HEADER_KEYWORDS = listOf(
            "reward",
            "points",
            "monies",
            "ser no",
            "serno",
            "sr no",
            "sr. no",
            "sl no",
            "sl. no",
            "s.no",
            "ref no",
            "reference",
            "category",
            "intl",
            "foreign",
            "balance",
            "mcc",
        )
    }
}
