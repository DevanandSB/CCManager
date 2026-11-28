package com.devanandsb.ccmanager.statementparser.data.parser

import com.devanandsb.ccmanager.statementparser.domain.model.ColumnInterval
import com.devanandsb.ccmanager.statementparser.domain.model.ColumnLayout
import com.devanandsb.ccmanager.statementparser.domain.model.CvTableGrid
import com.devanandsb.ccmanager.statementparser.domain.model.StatementTransaction
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import com.devanandsb.ccmanager.statementparser.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Reconstructs [StatementTransaction] records from positioned [TextBlock] rows using a dynamically
 * detected [ColumnLayout] and optional CV/vector [CvTableGrid] row/column boundaries, supporting
 * multi-page column shifts, glued date+description cells, multi-line merchant descriptions, Indian
 * lakh/crore comma numbers, `CR`/`DR` flags, `+/-` prefixes, and automatic 12-16 digit PAN masking.
 */
class TransactionRowParser(
    private val columnDetector: DynamicColumnDetector = DynamicColumnDetector(),
) {
    private data class MutableTxn(
        val dateIso: String,
        val descParts: MutableList<String>,
        val amount: BigDecimal,
        val type: TransactionType,
        val page: Int,
        var lastY: Int,
        var rowBand: IntRange? = null,
    )

    fun parseRows(
        rows: List<List<TextBlock>>,
        layout: ColumnLayout,
        inferredYear: Int? = null,
        tables: List<CvTableGrid> = emptyList(),
    ): List<StatementTransaction> {
        val collected = mutableListOf<MutableTxn>()
        var activeLayout = layout
        var skipPage: Int? = null
        var index = layout.headerRowIndex + 1

        while (index < rows.size) {
            val row = rows[index]
            if (row.isEmpty()) {
                index++
                continue
            }
            val currentPage = row.first().page
            val rowY = row.first().y
            if (skipPage == currentPage) {
                index++
                continue
            }
            skipPage = null

            val rowLine = row.joinToString(" ") { it.text }.trim()
            if (isHardDocumentStop(rowLine)) break

            // Re-detect shifted column header on subsequent pages (e.g. ICICI Page 2 shifts X left)
            val newHeaderLayout = columnDetector.detectAt(index, rows, tables)
            if (newHeaderLayout != null) {
                activeLayout = newHeaderLayout
                index = newHeaderLayout.headerRowIndex + 1
                continue
            }

            if (isPageNoise(rowLine) || columnDetector.isHeaderRow(row)) {
                index++
                continue
            }

            val activeTable = tables.firstOrNull { t ->
                t.page == currentPage && rowY in (t.yStart - 12)..(t.yEnd + 12)
            }
            val currentRowBand = activeTable?.rowBandForY(rowY)

            // If a headerless continuation page shifted horizontally inside a CV-detected table, realign columns
            if (activeLayout.headerPage != currentPage &&
                activeTable != null &&
                activeTable.columnBounds.size >= 3
            ) {
                val firstCol = activeTable.columnBounds.first()
                val shiftX = firstCol.first - activeLayout.dateColumn.xStart
                if (abs(shiftX) > 16) {
                    val hasDateInActive = row.any {
                        activeLayout.dateColumn.contains(it) && parseDateIso(it.text, inferredYear) != null
                    }
                    val hasDateInShifted = row.any {
                        it.centerX in (firstCol.first - 8)..(firstCol.last + 8) &&
                            parseDateIso(it.text, inferredYear) != null
                    }
                    if (!hasDateInActive && hasDateInShifted) {
                        activeLayout = activeLayout.copy(
                            headerPage = currentPage,
                            dateColumn = ColumnInterval(
                                (activeLayout.dateColumn.xStart + shiftX).coerceAtLeast(0),
                                (activeLayout.dateColumn.xEnd + shiftX).coerceAtLeast(10),
                            ),
                            descriptionColumn = ColumnInterval(
                                (activeLayout.descriptionColumn.xStart + shiftX).coerceAtLeast(0),
                                (activeLayout.descriptionColumn.xEnd + shiftX).coerceAtLeast(10),
                            ),
                            amountColumn = ColumnInterval(
                                (activeLayout.amountColumn.xStart + shiftX).coerceAtLeast(0),
                                (activeLayout.amountColumn.xEnd + shiftX).coerceAtLeast(10),
                            ),
                            creditAmountColumn = activeLayout.creditAmountColumn?.let {
                                ColumnInterval((it.xStart + shiftX).coerceAtLeast(0), (it.xEnd + shiftX).coerceAtLeast(10))
                            },
                            crDrIndicatorColumn = activeLayout.crDrIndicatorColumn?.let {
                                ColumnInterval((it.xStart + shiftX).coerceAtLeast(0), (it.xEnd + shiftX).coerceAtLeast(10))
                            },
                            ignoredIntervals = activeLayout.ignoredIntervals.map {
                                ColumnInterval((it.xStart + shiftX).coerceAtLeast(0), (it.xEnd + shiftX).coerceAtLeast(10))
                            },
                        )
                    }
                }
            }

            // Exclude left/right sidebars completely outside the active table's horizontal band
            val tableMinX = activeLayout.dateColumn.xStart - 12
            val tableBlocks = row.filter { it.xEnd >= tableMinX }
            val activeBlocks = tableBlocks.filterNot { activeLayout.isIgnored(it) }
            if (activeBlocks.isEmpty()) {
                index++
                continue
            }

            var dateText = collectColumnText(activeBlocks, activeLayout.dateColumn)
            val descText = collectColumnText(activeBlocks, activeLayout.descriptionColumn)
            val amountText = collectColumnText(activeBlocks, activeLayout.amountColumn)
            val creditColumnText = activeLayout.creditAmountColumn?.let { collectColumnText(activeBlocks, it) }.orEmpty()
            val crDrText = activeLayout.crDrIndicatorColumn?.let { collectColumnText(activeBlocks, it) }.orEmpty()

            // Handle single merged span starting in Date column and extending into Description (e.g. Yes Bank)
            var gluedDateSpanRemainder = ""
            if (dateText.isBlank()) {
                val leftBlock = activeBlocks.firstOrNull {
                    it.x <= activeLayout.dateColumn.xEnd + 16 &&
                        extractLeadingDateToken(it.text) != null
                }
                if (leftBlock != null) {
                    val tok = extractLeadingDateToken(leftBlock.text)!!
                    dateText = tok
                    val tokEnd = leftBlock.text.indexOf(tok) + tok.length
                    gluedDateSpanRemainder = leftBlock.text.substring(tokEnd).trim()
                }
            }

            val parsedDate = parseDateIso(dateText, inferredYear)
            val parsedAmountFromPrimary = parseAmount(amountText, crDrText)
            val parsedAmountFromCreditCol = if (parsedAmountFromPrimary == null && creditColumnText.isNotBlank()) {
                parseAmount(creditColumnText, "CR")
            } else {
                null
            }
            val parsedAmount = parsedAmountFromPrimary ?: parsedAmountFromCreditCol

            if (parsedDate == null && isPageSectionStop(rowLine)) {
                skipPage = currentPage
                index++
                continue
            }

            if (parsedDate != null && parsedAmount != null) {
                val combinedDesc = listOf(gluedDateSpanRemainder, descText)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                val cleanDesc = combinedDesc.ifBlank {
                    // Fallback: any non-date, non-amount text between date and amount columns
                    activeBlocks.filter {
                        it.centerX in (activeLayout.dateColumn.xEnd + 1) until activeLayout.amountColumn.xStart
                    }.joinToString(" ") { it.text }
                }.trim()

                if (cleanDesc.isNotBlank() && !isSummaryOrNoiseDescription(cleanDesc)) {
                    collected += MutableTxn(
                        dateIso = parsedDate,
                        descParts = mutableListOf(cleanDesc),
                        amount = parsedAmount.amount,
                        type = parsedAmount.type,
                        page = currentPage,
                        lastY = rowY,
                        rowBand = currentRowBand,
                    )
                }
            } else if (parsedDate == null && parsedAmount == null && descText.isNotBlank()) {
                // Multi-line wrapped merchant description belonging to previous transaction
                val last = collected.lastOrNull()
                val sameRuledBand = last != null &&
                    last.page == currentPage &&
                    last.rowBand != null &&
                    currentRowBand != null &&
                    last.rowBand == currentRowBand
                val yGap = if (last != null && last.page == currentPage) rowY - last.lastY else Int.MAX_VALUE
                if (last != null && (sameRuledBand || yGap in 1..18) && !isLikelyNonContinuationLine(descText)) {
                    last.descParts += descText
                    last.lastY = rowY
                }
            }

            index++
        }

        return collected.map { txn ->
            val mergedDesc = txn.descParts
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
            StatementTransaction(
                date = txn.dateIso,
                description = maskCardNumbers(mergedDesc),
                amount = txn.amount.setScale(2, RoundingMode.HALF_UP),
                type = txn.type,
                page = txn.page,
            )
        }
    }

    private fun collectColumnText(blocks: List<TextBlock>, interval: ColumnInterval): String =
        blocks.filter { interval.contains(it) }
            .sortedBy { it.x }
            .joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isPageNoise(line: String): Boolean {
        val low = line.lowercase()
        return PAGE_NO_RX.matches(low) ||
            low.startsWith("statement for ") ||
            low.startsWith("card no") ||
            low.startsWith("card number") ||
            low.contains("domestic transactions") ||
            low.contains("international transactions") ||
            MASKED_CARD_ONLY_RX.matches(low.replace(" ", ""))
    }

    private fun isHardDocumentStop(line: String): Boolean {
        val low = line.lowercase()
        return HARD_STOP_MARKERS.any { low.contains(it) }
    }

    private fun isPageSectionStop(line: String): Boolean {
        val low = line.lowercase()
        return PAGE_STOP_MARKERS.any { marker ->
            low == marker || low.startsWith("$marker ") || low.startsWith("$marker:") ||
                (marker.length > 10 && low.contains(marker))
        }
    }

    private fun isSummaryOrNoiseDescription(desc: String): Boolean {
        val low = desc.lowercase()
        return low == "total" || low == "sub total" || low == "subtotal" || low.startsWith("opening balance")
    }

    private fun isLikelyNonContinuationLine(text: String): Boolean {
        val low = text.lowercase()
        return low.startsWith("page ") ||
            low.startsWith("total ") ||
            low.startsWith("card no") ||
            low.contains("continued on next page") ||
            low.contains("reward points") ||
            low.startsWith("# international")
    }

    companion object {
        private val PAGE_NO_RX = Regex("^page\\s*:?\\s*\\d+(?:\\s+of\\s+\\d+)?$", RegexOption.IGNORE_CASE)
        private val MASKED_CARD_ONLY_RX = Regex("^[0-9xX*•]{8,19}$")

        private val HARD_STOP_MARKERS = listOf(
            "illustrative example",
            "illustration for calculating",
            "schedule of charges",
            "key fact statement",
            "most important terms and conditions",
            "important safety instructions",
            "glossary",
        )

        private val PAGE_STOP_MARKERS = listOf(
            "end of statement",
            "end of the statement",
            "**** end of statement",
            "important messages",
            "important information",
            "earnings",
            "reward points summary",
            "your reward points summary",
            "cashback details",
            "gst details",
            "just so you know",
            "# international spends",
        )

        private const val MON = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec|January|February|March|April|June|July|August|September|October|November|December"

        private val DATE_TOKEN_RX = Regex(
            "(?<![\\d.,/-])(" +
                "\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}|" +
                "\\d{1,2}[/.-]\\d{1,2}[/.-](?:\\d{4}|\\d{2})|" +
                "\\d{1,2}(?:st|nd|rd|th)?[\\s-]+(?:$MON)(?:(?:\\s*,\\s*|\\s+|-)\\s*'?\\d{2,4})?|" +
                "(?:$MON)\\s+\\d{1,2}(?:st|nd|rd|th)?(?:(?:\\s*,\\s*|\\s+)\\d{2,4})?" +
                ")(?!\\d)",
            RegexOption.IGNORE_CASE,
        )

        private val AMOUNT_RX = Regex(
            "^([+-])?\\s*(\\()?\\s*(?:(?:₹|Rs\\.?|INR|`|[\uE000-\uF8FF]|\\$|€|£)\\s*)?(\\d+(?:,\\d+)*(?:\\.\\d{1,2})?)(?:\\s*(?:INR|Rs\\.?|₹))?\\s*(\\))?\\s*(?:\\(?\\s*(Cr|Dr|C|D)\\.?\\s*\\)?)?\\.?$",
            RegexOption.IGNORE_CASE,
        )

        private val MASK_CARD_PAN_RX = Regex("(?<!\\d)(\\d{4})[\\s-]?(\\d{4})[\\s-]?(\\d{0,4})[\\s-]?(\\d{4})(?!\\d)")

        private val DATE_FORMATTERS_DMY = listOf(
            "uuuu-M-d",
            "uuuu/M/d",
            "d/M/uuuu",
            "d-M-uuuu",
            "d.M.uuuu",
            "d/M/uu",
            "d-M-uu",
            "d.M.uu",
            "d MMM uuuu",
            "d-MMM-uuuu",
            "d MMMM uuuu",
            "d-MMMM-uuuu",
            "d MMM, uuuu",
            "d MMMM, uuuu",
            "d MMM yy",
            "d-MMM-yy",
            "MMMM d, uuuu",
            "MMM d, uuuu",
            "MMMM d uuuu",
            "MMM d uuuu",
        ).map { pat ->
            DateTimeFormatterBuilder().parseCaseInsensitive()
                .appendPattern(pat)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.SMART)
        }

        private val DATE_FORMATTERS_MDY = listOf(
            "M/d/uuuu",
            "M-d-uuuu",
        ).map {
            DateTimeFormatter.ofPattern(it, Locale.ENGLISH).withResolverStyle(ResolverStyle.SMART)
        }

        private val YEARLESS_DM_FMT = DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.SMART)
        private val YEARLESS_DM_FULL_FMT = DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d MMMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.SMART)
        private val YEARLESS_MD_FMT = DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("MMM d uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.SMART)
        private val YEARLESS_MD_FULL_FMT = DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("MMMM d uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.SMART)

        data class ParsedAmount(
            val amount: BigDecimal,
            val type: TransactionType,
        )

        fun extractLeadingDateToken(raw: String): String? {
            val trimmed = raw.trim()
            val match = DATE_TOKEN_RX.find(trimmed) ?: return null
            return if (match.range.first <= 3) match.groupValues[1] else null
        }

        fun parseDateIso(raw: String, inferredYear: Int? = null): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val candidate = DATE_TOKEN_RX.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
            val normalizedCandidate = candidate
                .replace(Regex("(?i)(\\d{1,2})(?:st|nd|rd|th)\\b"), "$1")
                .replace(Regex("(?i)\\bSept\\b"), "Sep")
                .replace(Regex("(?i)\\b([A-Za-z]{3,9})\\s*'(\\d{2})\\b"), "$1 20$2")
                .replace(Regex("(\\d{1,2})\\s*'(\\d{2})\\b"), "$1 20$2")
                .replace(Regex("\\s+"), " ")
                .trim()

            for (fmt in DATE_FORMATTERS_DMY) {
                runCatching { LocalDate.parse(normalizedCandidate, fmt) }.getOrNull()?.let {
                    return it.toString()
                }
            }
            for (fmt in DATE_FORMATTERS_MDY) {
                runCatching { LocalDate.parse(normalizedCandidate, fmt) }.getOrNull()?.let {
                    return it.toString()
                }
            }
            if (inferredYear != null) {
                val cleanedYearless = normalizedCandidate.replace('-', ' ').trimEnd(',')
                for (fmt in listOf(YEARLESS_DM_FMT, YEARLESS_DM_FULL_FMT, YEARLESS_MD_FMT, YEARLESS_MD_FULL_FMT)) {
                    runCatching { LocalDate.parse("$cleanedYearless $inferredYear", fmt) }.getOrNull()?.let {
                        return it.toString()
                    }
                }
            }
            return null
        }

        fun parseAmount(rawAmountCell: String, crDrCell: String = ""): ParsedAmount? {
            val cleanedCell = rawAmountCell
                .replace('\u00A0', ' ')
                .replace(Regex("[ \t]+"), " ")
                .trim()
            if (cleanedCell.isEmpty()) return null

            val match = AMOUNT_RX.matchEntire(cleanedCell) ?: return null
            val leadingSign = match.groupValues[1]
            val openParen = match.groupValues[2]
            val numericStr = match.groupValues[3].replace(",", "")
            val closeParen = match.groupValues[4]
            val inlineFlag = match.groupValues[5]

            val value = runCatching { BigDecimal(numericStr) }.getOrNull() ?: return null
            if (value.compareTo(BigDecimal.ZERO) <= 0) return null

            val flag = (inlineFlag.ifBlank { crDrCell }).trim().uppercase(Locale.ENGLISH)
            val isCredit = when {
                flag.startsWith("C") -> true
                flag.startsWith("D") -> false
                openParen == "(" && closeParen == ")" -> true
                leadingSign == "+" -> true
                leadingSign == "-" -> true
                else -> false
            }

            return ParsedAmount(
                amount = value.setScale(2, RoundingMode.HALF_UP),
                type = if (isCredit) TransactionType.CREDIT else TransactionType.DEBIT,
            )
        }

        /**
         * Redacts 12-to-16 digit card numbers inside transaction descriptions to `XXXX-XXXX-XXXX-1234`.
         */
        fun maskCardNumbers(text: String): String =
            MASK_CARD_PAN_RX.replace(text) { match ->
                val digitsOnly = match.value.filter(Char::isDigit)
                if (digitsOnly.length in 12..16) {
                    "XXXX-XXXX-XXXX-${digitsOnly.takeLast(4)}"
                } else {
                    match.value
                }
            }
    }
}
