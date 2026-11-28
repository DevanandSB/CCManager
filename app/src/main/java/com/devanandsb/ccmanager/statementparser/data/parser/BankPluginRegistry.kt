package com.devanandsb.ccmanager.statementparser.data.parser

import com.devanandsb.ccmanager.statementparser.domain.model.StatementTransaction
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import com.devanandsb.ccmanager.statementparser.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Strategy interface for edge-case bank card statement layouts where standard tabular headers
 * are absent (e.g., stacked card-style transaction feeds like AU Bank and Slice).
 */
interface BankParserPlugin {
    val bankName: String
    fun canHandle(rawText: String): Boolean
    fun parse(rows: List<List<TextBlock>>): List<StatementTransaction>
}

class BankPluginRegistry(
    private val plugins: List<BankParserPlugin> = listOf(
        AuBankStackedPlugin(),
        SliceStackedPlugin(),
        GenericInlineRowPlugin(),
    ),
) {
    fun parseFallback(
        rawText: String,
        rows: List<List<TextBlock>>,
    ): List<StatementTransaction> {
        for (plugin in plugins) {
            if (plugin.canHandle(rawText)) {
                val txns = plugin.parse(rows)
                if (txns.isNotEmpty()) return txns
            }
        }
        return emptyList()
    }
}

/**
 * Handles AU Small Finance Bank's headerless "Your Transactions" stacked card layout where
 * day (`27` around X=40) and month/year (`Oct 26` around X=33) sit on adjacent Y lines while
 * description (X~70), `Cr`/`Dr` (X~74), and `+₹1,000.00` (X>400) sit in the same vertical card band.
 */
class AuBankStackedPlugin : BankParserPlugin {
    override val bankName: String = "AU Bank"

    private val dayRx = Regex("^([0-3]?\\d)$")
    private val monYrRx = Regex("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(\\d{2})$", RegexOption.IGNORE_CASE)
    private val amtRx = Regex("^([+-])?\\s*(?:₹|Rs\\.?|INR|`|[\uE000-\uF8FF])?\\s*(\\d+(?:,\\d+)*(?:\\.\\d{1,2})?)$", RegexOption.IGNORE_CASE)

    override fun canHandle(rawText: String): Boolean {
        val low = rawText.lowercase()
        return (low.contains("au small finance") || low.contains("aubank") || low.contains("lit credit card")) &&
            low.contains("your transactions")
    }

    override fun parse(rows: List<List<TextBlock>>): List<StatementTransaction> {
        val allBlocks = rows.flatten().sortedWith(compareBy<TextBlock> { it.page }.thenBy { it.y }.thenBy { it.x })
        val results = mutableListOf<StatementTransaction>()

        val byPage = allBlocks.groupBy { it.page }
        for ((pageNo, pageBlocks) in byPage) {
            val pageText = pageBlocks.joinToString(" ") { it.text }.lowercase()
            if (pageText.contains("illustrative example") || pageText.contains("illustration for calculating")) {
                break
            }
            if (pageText.contains("reward points you have earned") ||
                (pageText.contains("just so you know") && !pageText.contains("your transactions"))
            ) {
                continue
            }

            // Detect the date column dynamically (day blocks live around X=35..55, never past X=65)
            val dayBlocks = pageBlocks.filter { it.x < 65 && dayRx.matches(it.text.trim()) }
            val dateColMaxX = dayBlocks.maxOfOrNull { it.xEnd }?.plus(12) ?: 62
            val consumedDays = HashSet<TextBlock>()
            val monBlocks = pageBlocks
                .filter { it.x <= dateColMaxX && monYrRx.matches(it.text.trim()) }
                .sortedBy { it.y }

            for (monBlock in monBlocks) {
                val dayBlock = dayBlocks
                    .filter { it !in consumedDays && (monBlock.y - it.y) in 6..24 }
                    .minByOrNull { abs(monBlock.y - it.y) }
                    ?: continue

                val dateIso = TransactionRowParser.parseDateIso("${dayBlock.text.trim()} ${monBlock.text.trim()}") ?: continue
                consumedDays += dayBlock

                val yMin = dayBlock.y - 10
                val yMax = monBlock.y + 6
                val band = pageBlocks
                    .filter { it.y in yMin..yMax && it !== dayBlock && it !== monBlock }
                    .sortedBy { it.x }

                val amountBlock = band.lastOrNull { it.x > 250 && amtRx.matches(it.text.trim()) } ?: continue
                val crDrBlock = band.firstOrNull {
                    it.x in (dateColMaxX - 10)..120 &&
                        (it.text.trim().equals("Cr", true) || it.text.trim().equals("Dr", true))
                }
                val crDrHint = crDrBlock?.text?.trim().orEmpty()

                val parsedAmt = TransactionRowParser.parseAmount(amountBlock.text, crDrHint) ?: continue
                val desc = band
                    .filter {
                        it !== amountBlock &&
                            it !== crDrBlock &&
                            it.x >= dateColMaxX - 5 &&
                            it.xEnd < amountBlock.x &&
                            abs(it.y - dayBlock.y) <= 10
                    }
                    .joinToString(" ") { it.text.trim() }
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (desc.isNotBlank()) {
                    results += StatementTransaction(
                        date = dateIso,
                        description = TransactionRowParser.maskCardNumbers(desc),
                        amount = parsedAmt.amount.setScale(2, RoundingMode.HALF_UP),
                        type = parsedAmt.type,
                        page = pageNo,
                    )
                }
            }
        }
        return results
    }
}

/**
 * Handles Slice credit card's app-style stacked feed across multi-page statements:
 *   - Sections: `Spends`, `Bank transfers`, `Fees`, `EMIs` (DEBIT) vs `Refunds & repayments`, `Cashback` (CREDIT)
 *   - Card row: `<Merchant> <₹Amount>` on row Y, avatar letter, `<16 Nov '26 • UPI>` on Y + 12..24
 *   - Plus non-zero summary fee items (`Interest`, `Surcharge`, `Late fee`, `Flat fee`, `Foreclosure charge`).
 */
class SliceStackedPlugin : BankParserPlugin {
    override val bankName: String = "Slice"

    private val creditSections = setOf("refunds & repayments", "refunds and repayments", "cashback", "repayments", "refunds")
    private val debitSections = setOf("spends", "bank transfers", "fees", "emis", "ongoing emis")
    private val stopSections = setOf("monies", "limit details", "gst details", "important information", "glossary")
    private val feeLabels = setOf("interest", "surcharge", "late fee", "flat fee", "foreclosure charge", "gst", "igst", "cgst", "sgst")

    private val rowAmtRx = Regex("^(?:[+-]\\s*)?(?:₹|Rs\\.?|INR|`|[\uE000-\uF8FF])\\s*(\\d+(?:,\\d+)*(?:\\.\\d{1,2})?)$", RegexOption.IGNORE_CASE)
    private val cardDateRx = Regex("^(\\d{1,2}\\s+[A-Za-z]{3}\\s+'\\d{2})\\b")

    override fun canHandle(rawText: String): Boolean {
        val low = rawText.lowercase()
        return low.contains("slice") && (low.contains("statement summary") || low.contains("monies") || low.contains("spends"))
    }

    override fun parse(rows: List<List<TextBlock>>): List<StatementTransaction> {
        val results = mutableListOf<StatementTransaction>()
        var currentSectionCredit: Boolean? = null
        var insideSummary = false
        val summaryFees = mutableListOf<Pair<String, BigDecimal>>()
        var fallbackDateIso: String? = null

        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if (row.isEmpty()) {
                i++
                continue
            }
            val joined = row.sortedBy { it.x }.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            val low = joined.lowercase()

            if (low == "statement summary") {
                insideSummary = true
                i++
                continue
            }
            if (insideSummary) {
                if (low.startsWith("min amount due") || low.startsWith("minimum amount due")) {
                    insideSummary = false
                    i++
                    continue
                }
                val label = row.firstOrNull()?.text?.trim()?.lowercase().orEmpty()
                val lastAmt = row.lastOrNull()?.text?.trim()?.let { rowAmtRx.matchEntire(it)?.groupValues?.get(1) }
                if (label in feeLabels && lastAmt != null) {
                    val v = runCatching { BigDecimal(lastAmt.replace(",", "")).setScale(2, RoundingMode.HALF_UP) }.getOrNull()
                    if (v != null && v > BigDecimal.ZERO) {
                        summaryFees += label.replaceFirstChar(Char::uppercase) to v
                    }
                }
                i++
                continue
            }

            if (low in stopSections) {
                currentSectionCredit = null
                if (low == "important information" || low == "glossary") break
                i++
                continue
            }
            if (low in creditSections) {
                currentSectionCredit = true
                i++
                continue
            }
            if (low in debitSections) {
                currentSectionCredit = false
                i++
                continue
            }

            val isCredit = currentSectionCredit
            if (isCredit != null && row.size >= 2) {
                val amtBlock = row.lastOrNull()
                val amtMatch = amtBlock?.text?.trim()?.let { rowAmtRx.matchEntire(it) }
                if (amtBlock != null && amtMatch != null && amtBlock.x > 140) {
                    val desc = row.dropLast(1).joinToString(" ") { it.text.trim() }.trim()
                    val dateInfo = findCardDateBelow(rows, i, row.first().page)
                    if (desc.isNotBlank() && dateInfo != null) {
                        val value = runCatching {
                            BigDecimal(amtMatch.groupValues[1].replace(",", "")).setScale(2, RoundingMode.HALF_UP)
                        }.getOrNull()
                        if (value != null && value > BigDecimal.ZERO) {
                            fallbackDateIso = fallbackDateIso ?: dateInfo.first
                            results += StatementTransaction(
                                date = dateInfo.first,
                                description = TransactionRowParser.maskCardNumbers(desc),
                                amount = value,
                                type = if (isCredit) TransactionType.CREDIT else TransactionType.DEBIT,
                                page = row.first().page,
                            )
                            i = dateInfo.second + 1
                            continue
                        }
                    }
                }
            }

            i++
        }

        val feeDate = fallbackDateIso ?: results.firstOrNull()?.date
        if (feeDate != null) {
            for ((label, amt) in summaryFees) {
                results += StatementTransaction(
                    date = feeDate,
                    description = label,
                    amount = amt,
                    type = TransactionType.DEBIT,
                    page = 1,
                )
            }
        }
        return results
    }

    private fun findCardDateBelow(
        rows: List<List<TextBlock>>,
        startIdx: Int,
        pageNo: Int,
    ): Pair<String, Int>? {
        for (offset in 1..3) {
            val idx = startIdx + offset
            val candidateRow = rows.getOrNull(idx) ?: break
            if (candidateRow.firstOrNull()?.page != pageNo) break
            val joined = candidateRow.joinToString(" ") { it.text.trim() }
            val m = cardDateRx.find(joined) ?: continue
            val iso = TransactionRowParser.parseDateIso(m.groupValues[1]) ?: continue
            return iso to idx
        }
        return null
    }
}

/**
 * Last-resort headerless fallback for single-row statements containing `<Date> <Description> <Amount [CR/DR]>`.
 */
class GenericInlineRowPlugin : BankParserPlugin {
    override val bankName: String = "Generic"

    private val inlineRowRx = Regex(
        "^(\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{1,2}\\s+[A-Za-z]{3,9}\\s+'?\\d{2,4})\\s+(.+?)\\s+([+-]?(?:₹|Rs\\.?|INR|`|[\uE000-\uF8FF]|\\$|€|£)?\\s*\\d+(?:,\\d+)*(?:\\.\\d{1,2})?(?:\\s*(?:CR|DR))?)$",
        RegexOption.IGNORE_CASE,
    )

    override fun canHandle(rawText: String): Boolean = rawText.isNotBlank()

    override fun parse(rows: List<List<TextBlock>>): List<StatementTransaction> {
        val out = mutableListOf<StatementTransaction>()
        for (row in rows) {
            if (row.isEmpty()) continue
            val joined = row.sortedBy { it.x }.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ")
            val match = inlineRowRx.matchEntire(joined) ?: continue
            val dateIso = TransactionRowParser.parseDateIso(match.groupValues[1]) ?: continue
            val desc = match.groupValues[2].trim()
            val parsedAmt = TransactionRowParser.parseAmount(match.groupValues[3]) ?: continue
            if (desc.isNotBlank()) {
                out += StatementTransaction(
                    date = dateIso,
                    description = TransactionRowParser.maskCardNumbers(desc),
                    amount = parsedAmt.amount,
                    type = parsedAmt.type,
                    page = row.first().page,
                )
            }
        }
        return out
    }
}
