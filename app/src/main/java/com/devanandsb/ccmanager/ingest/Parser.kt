package com.devanandsb.ccmanager.ingest

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

data class ParsedLine(
    val date: LocalDate,
    val description: String,
    val amountPaise: Long,
    val isCredit: Boolean,
    val reference: String = "",
    val categoryHint: String = "",
    val mcc: String = "",
)

data class ParsedStatement(
    val statementDate: LocalDate?,
    val periodStart: LocalDate?,
    val dueDate: LocalDate?,
    val totalDuePaise: Long?,
    val minDuePaise: Long?,
    val openingPaise: Long?,
    val last4: String?,
    val lines: List<ParsedLine>,
    val rewards: Rewards? = null,
    val creditLimitPaise: Long? = null,
) {
    val debits get() = lines.filter { !it.isCredit }.sumOf { it.amountPaise }
    val credits get() = lines.filter { it.isCredit }.sumOf { it.amountPaise }
}

/**
 * What the statement says the card earned. Points-based cards fill [pointsBalance]/[pointsEarned]
 * (slice calls them "monies"); cashback cards fill [cashbackPaise] for this cycle.
 */
data class Rewards(
    val pointsBalance: Long? = null,
    val pointsEarned: Long? = null,
    val cashbackPaise: Long? = null,
    val unit: String = "points",
) {
    val isEmpty get() = pointsBalance == null && pointsEarned == null && cashbackPaise == null
}

/** Outcome of the "lines add up to the total" check. */
data class Validation(val ok: Boolean, val note: String)

interface StatementParser {
    val name: String

    /**
     * [received] is when the statement arrived (the mail's date), used by layouts that print dates
     * without a year; null when unknown (a picked file, a re-parse).
     */
    fun parse(text: String, received: LocalDate? = null): ParsedStatement

    fun parse(text: String, received: LocalDate? = null, pages: List<PageLayout> = emptyList()): ParsedStatement =
        parse(text, received)
}

/**
 * Label-driven parser for Indian card statements. Each issuer gets its own instance with extra
 * label spellings; the extraction engine is shared. Works on text from PDFBox with sort-by-position.
 */
open class LabelParser(
    override val name: String,
    totalLabels: List<String> = emptyList(),
    minLabels: List<String> = emptyList(),
    dueLabels: List<String> = emptyList(),
    stmtDateLabels: List<String> = emptyList(),
    openingLabels: List<String> = emptyList(),
    creditWords: List<String> = emptyList(),
    private val minusMeansCredit: Boolean = false,
    private val stripCategorySuffixRx: Regex? = null,
    private val rewardUnitHint: String? = null,
    exactTotalLabels: List<String>? = null,
) : StatementParser {

    protected val total = exactTotalLabels ?: (totalLabels + listOf(
        "total amount due", "total dues", "total amt due", "total amt. due", "total due amount",
        "total payment due", "total amount payable", "total payable", "net payable", "payable amount",
        "total outstanding", "current outstanding", "outstanding balance", "outstanding amount",
        "statement balance", "total statement balance", "statement total", "closing balance",
        "total new balance", "current balance", "new balance", "total due", "amount due",
    ))
    protected val min = minLabels + listOf(
        "minimum amount due", "minimum payment due", "min. amount due", "min amount due", "minimum due", "min. amt. due", "min amt due", "minimum amount payable",
    )
    protected val due = dueLabels + listOf("payment due date", "due date", "please pay by", "pay by", "payment due on", "due by")
    protected val stmtDate = stmtDateLabels + listOf(
        "statement generation date", "date of statement", "bill generation date",
        "statement closing date", "statement generated on", "bill generated on",
        "statement date", "stmt date", "stmt. date", "statement dt", "bill dt",
        "closing date", "bill date", "billing date", "generated on", "invoice date",
    )
    protected val opening = openingLabels + listOf(
        "opening balance", "previous balance", "previous statement dues", "previous outstanding", "last statement balance",
        "previous amount due", "opening bal",
    )
    protected val period = listOf("statement period", "billing period", "billing cycle", "statement for", "for the period", "period from")
    protected val credits = creditWords + listOf(
        "payment received", "payment - thank you", "payment thank you", "thank you", "refund", "reversal", "cashback",
        "cash back", "credit adjustment", "autopay payment", "autopay py", "autopay recd", "autodebit py",
        "autodebit recd", "cc autopay", "autopay bill", "bbps payment", "cc payment", "repayment",
    )

    override fun parse(text: String, received: LocalDate?, pages: List<PageLayout>): ParsedStatement {
        if (pages.isEmpty()) return parse(text, received)
        val textParsed = parse(text, received)
        val spatial = SpatialSummaryExtractor.extract(pages, total, min, due, stmtDate, opening, received)
        val preStmt = spatial.statementDate ?: spatial.periodEnd ?: textParsed.statementDate ?: received
        val periodStart = spatial.periodStart ?: textParsed.periodStart
        val spatialTxns = UniversalTransactionParser.extract(
            pages = pages,
            stmtDate = preStmt,
            periodStart = periodStart,
            creditWords = credits,
            minusMeansCredit = minusMeansCredit,
            stripCategorySuffixRx = stripCategorySuffixRx,
        )
        val finalTxns = spatialTxns.ifEmpty { textParsed.lines }

        val stmtCandidates = listOfNotNull(spatial.statementDate, spatial.periodEnd, textParsed.statementDate).distinct()
        val finalStmt = Text.pickChronologicalStmtDate(stmtCandidates, spatial.periodEnd, finalTxns)
            ?: spatial.statementDate
            ?: spatial.periodEnd
            ?: textParsed.statementDate

        val finalDue = when {
            spatial.dueDate != null && finalStmt != null && !spatial.dueDate.isBefore(finalStmt) && spatial.dueDate <= finalStmt.plusDays(60) ->
                spatial.dueDate
            textParsed.dueDate != null && finalStmt != null && !textParsed.dueDate.isBefore(finalStmt) && textParsed.dueDate <= finalStmt.plusDays(60) ->
                textParsed.dueDate
            else -> spatial.dueDate ?: textParsed.dueDate
        }

        val finalOpening = spatial.openingPaise ?: textParsed.openingPaise
        val finalLimit = spatial.creditLimitPaise ?: textParsed.creditLimitPaise
        val summaryTotal = if (finalOpening != null && spatial.purchasesPaise != null) {
            finalOpening +
                (spatial.purchasesPaise ?: 0L) +
                (spatial.cashAdvancesPaise ?: 0L) +
                (spatial.chargesPaise ?: 0L) -
                (spatial.paymentsPaise ?: 0L)
        } else null

        val lines = text.lines()
            .map { Text.collapseFauxBold(it.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim()) }
            .filter { it.isNotEmpty() }
        val eqTotal = Text.summaryEquation(lines)?.second

        val spatialRewards = Rewards(
            pointsBalance = spatial.pointsBalance ?: textParsed.rewards?.pointsBalance,
            pointsEarned = spatial.pointsEarned ?: textParsed.rewards?.pointsEarned,
            cashbackPaise = textParsed.rewards?.cashbackPaise,
            unit = rewardUnitHint ?: textParsed.rewards?.unit ?: "points",
        ).takeUnless { it.isEmpty }

        val preReconcile = ParsedStatement(
            statementDate = finalStmt,
            periodStart = periodStart?.takeIf { finalStmt == null || !it.isAfter(finalStmt) },
            dueDate = finalDue,
            totalDuePaise = spatial.totalDuePaise ?: textParsed.totalDuePaise,
            minDuePaise = spatial.minDuePaise ?: textParsed.minDuePaise,
            openingPaise = finalOpening,
            last4 = textParsed.last4 ?: Text.last4(text),
            lines = finalTxns,
            rewards = spatialRewards,
            creditLimitPaise = finalLimit,
        )

        return StatementReconciler.reconcile(
            preReconcile,
            StatementReconciler.Context(
                labeledTotalCandidates = (spatial.labeledTotalCandidates + listOfNotNull(textParsed.totalDuePaise)).distinct(),
                labeledMinCandidates = (spatial.labeledMinCandidates + listOfNotNull(textParsed.minDuePaise)).distinct(),
                page1NumericCandidates = spatial.page1NumericCandidates,
                limitPaise = finalLimit,
                availableCreditPaise = spatial.availableCreditPaise,
                cashLimitPaise = spatial.cashLimitPaise,
                availableCashPaise = spatial.availableCashPaise,
                summaryTotalPaise = summaryTotal,
                equationTotalPaise = eqTotal,
            ),
        )
    }

    override fun parse(text: String, received: LocalDate?): ParsedStatement {
        val rawLines = text.lines()
            .map { Text.collapseFauxBold(it.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim()) }
            .filter { it.isNotEmpty() }
        val lines = preprocessTxnLines(rawLines)
        val fields = HeaderFields(lines)

        val periodDates = fields.dates(period, 2, received?.year)
        val rawStart = periodDates.getOrNull(0)
        val nonIllusStmt = fields.nonIllustrationDateHits(stmtDate)
        val stmtCandidatesExplicit = (nonIllusStmt + listOfNotNull(periodDates.getOrNull(1)))
            .ifEmpty { fields.dateHits(stmtDate) }
            .distinct()
        val fallbackYearHint = stmtCandidatesExplicit.firstOrNull()?.year ?: periodDates.getOrNull(1)?.year ?: received?.year ?: LocalDate.now().year
        val yearlessStmtCandidates = fields.nonIllustrationDateHits(stmtDate, fallbackYearHint)
            .ifEmpty { fields.dateHits(stmtDate, fallbackYearHint) }
        val preStmt = Text.pickChronologicalStmtDate(stmtCandidatesExplicit, periodDates.getOrNull(1), emptyList())
            ?: Text.pickChronologicalStmtDate(yearlessStmtCandidates, periodDates.getOrNull(1), emptyList())
        val stmtForTxns = preStmt ?: received
        val txns = transactionLines(lines, stmtForTxns, rawStart, (stmtForTxns ?: LocalDate.now()).year)

        val stmtCandidatesFinal = (
            fields.nonIllustrationDateHits(stmtDate) +
                fields.nonIllustrationDateHits(stmtDate, txns.maxOfOrNull { it.date }?.year ?: fallbackYearHint)
            ).ifEmpty {
            fields.dateHits(stmtDate) + fields.dateHits(stmtDate, txns.maxOfOrNull { it.date }?.year ?: fallbackYearHint)
        }.distinct()
        val rawStmt = Text.pickChronologicalStmtDate(stmtCandidatesFinal, periodDates.getOrNull(1), txns)
            ?: Text.pickChronologicalStmtDate(stmtCandidatesExplicit, periodDates.getOrNull(1), txns)
            ?: preStmt
        val periodStart = rawStart?.takeIf { rawStmt == null || !it.isAfter(rawStmt) }
        val fallbackYear = (rawStmt ?: txns.maxOfOrNull { it.date } ?: received ?: LocalDate.now()).year
        val finalStmtWithTxns = rawStmt ?: periodDates.getOrNull(1)
        val limit = Text.creditLimit(lines)
        val available = findAvailableCredit(lines, fields)
        val cashLimit = fields.amount(listOf("cash limit (including cash)", "total cash limit", "cash limit"))
        val availCash = fields.amount(listOf("available cash (including cash)", "available cash limit", "available cash"))
        val equation by lazy { Text.summaryEquation(lines) }

        fun isAnyLimit(amt: Long): Boolean =
            (limit != null && limit > 0L && amt == limit) ||
                (cashLimit != null && cashLimit > 0L && amt == cashLimit) ||
                (availCash != null && availCash > 0L && amt == availCash) ||
                (limit != null && available != null && available in 1 until limit && amt == available && amt != (limit - available))

        val rawOpening = fields.amountHits(opening)
            .firstOrNull { !it.inIllustration && !it.nearLimit && !isAnyLimit(it.paise) }?.paise
            ?: fields.amount(opening)?.takeUnless(::isAnyLimit)
            ?: equation?.first
        val openingAmt = rawOpening

        val debitsSum = txns.filter { !it.isCredit }.sumOf { it.amountPaise }
        val creditsSum = txns.filter { it.isCredit }.sumOf { it.amountPaise }
        val txnComputed = (openingAmt ?: 0L) + debitsSum - creditsSum
        val summaryPurchases = fields.amount(
            listOf("purchases / other charges", "purchases & other charges", "purchases & other debits", "purchases / charges", "purchases/ charges", "purchases/charges", "purchases & charges", "purchases / debits", "current purchases", "current debits", "spends & charges", "total spends", "spends", "purchases"),
        )?.let(::abs)
        val summaryCashAdv = fields.amount(listOf("cash advances", "cash advance"))?.let(::abs) ?: 0L
        val summaryCharges = fields.amount(listOf("other debits & charges", "finance charges", "fees & charges", "fee / charges", "interest charges"))?.let(::abs) ?: 0L
        val summaryPayments = fields.amount(
            listOf("payments / credits", "payments/ credits", "payments/credits", "payments & credits", "payments & refunds", "payments / refunds", "repayments & refunds", "repayments"),
        )?.let(::abs) ?: 0L
        val summaryTotal = if (openingAmt != null && summaryPurchases != null) {
            openingAmt + summaryPurchases + summaryCashAdv + summaryCharges - summaryPayments
        } else null

        val totalHits = fields.amountHits(total)
        val eqTotal = equation?.second
        val anyDecimalTotal = totalHits.any { it.hasDecimals && !it.inIllustration && !it.nearLimit && !isAnyLimit(it.paise) }
        fun isLimitFalsePositive(amt: Long): Boolean {
            val summaryConfirms = (summaryTotal != null && abs(summaryTotal - amt) <= 100L) ||
                (eqTotal != null && abs(eqTotal - amt) <= 100L) ||
                (txns.isNotEmpty() && openingAmt != null && abs(txnComputed - amt) <= 100L)
            if (summaryConfirms) return false
            if (limit != null && limit > 0L && amt == limit && (debitsSum < limit || txnComputed < limit - 100L)) return true
            if (cashLimit != null && cashLimit > 0L && amt == cashLimit) return true
            if (availCash != null && availCash > 0L && amt == availCash) return true
            if (limit != null && available != null && available in 1 until limit && amt == available && amt != (limit - available) && debitsSum < amt) return true
            return false
        }

        val validHits = totalHits.filterNot { isLimitFalsePositive(it.paise) }
        val candidates = validHits.map { it.paise }.ifEmpty {
            listOfNotNull(summaryTotal, eqTotal)
        }.distinct()
        val pickedTotal = candidates
            .maxByOrNull { cand ->
                var score = 0
                val bestHit = validHits.filter { it.paise == cand }
                    .sortedWith(compareBy<HeaderFields.AmountHit> { it.inIllustration }.thenBy { it.nearLimit }.thenByDescending { it.explicit })
                    .firstOrNull()
                if (summaryTotal != null && abs(cand - summaryTotal) <= 100L) score += 12
                if (eqTotal != null && abs(cand - eqTotal) <= 100L) score += 12
                if (validHits.size > 1) {
                    if (txns.isNotEmpty() && openingAmt != null && abs(cand - txnComputed) <= 100L) score += 10
                    if (txns.isNotEmpty() && openingAmt == null && abs(cand - (debitsSum - creditsSum)) <= 100L) score += 6
                    if (limit != null && available != null && available in 1 until limit && abs((limit - available) - cand) <= 100L) score += 5
                }
                if (bestHit != null) {
                    score += when {
                        !bestHit.inIllustration && !bestHit.nearLimit && bestHit.explicit -> 8
                        !bestHit.inIllustration && !bestHit.nearLimit -> 5
                        !bestHit.inIllustration -> 1
                        else -> -12
                    }
                    if (bestHit.nearLimit) score -= 6
                    if (!bestHit.hasDecimals && anyDecimalTotal) score -= 4
                }
                score
            }
            ?: eqTotal?.takeUnless(::isLimitFalsePositive)
            ?: totalHits.filterNot { it.inIllustration }.firstOrNull()?.paise
            ?: totalHits.firstOrNull()?.paise
            ?: eqTotal

        val minHits = fields.amountHits(min)
            .filter { it.paise >= 0L }
            .filterNot { isLimitFalsePositive(it.paise) || (available != null && available != pickedTotal && it.paise == available) }
            .sortedWith(
                compareBy<HeaderFields.AmountHit> { it.inIllustration }
                    .thenBy { it.nearLimit }
                    .thenByDescending { pickedTotal == null || pickedTotal < 0L || it.paise <= pickedTotal }
                    .thenByDescending { it.explicit },
            )
        val pickedMin = minHits.firstOrNull()?.paise
            ?: fields.amount(min)?.coerceAtLeast(0L)?.takeUnless(::isLimitFalsePositive)

        val dueYear = (finalStmtWithTxns ?: stmtForTxns)?.year ?: fallbackYear
        val explicitDue = fields.nonIllustrationDateHits(due).ifEmpty { fields.dateHits(due) }
        val dueCandidates = if (explicitDue.isNotEmpty()) explicitDue else {
            val baseYearDue = fields.dateHits(due, dueYear)
            if (finalStmtWithTxns != null && finalStmtWithTxns.monthValue >= 11) {
                (baseYearDue + fields.dateHits(due, dueYear + 1)).distinct()
            } else baseYearDue
        }
        val pickedDue = if (finalStmtWithTxns != null) {
            dueCandidates.firstOrNull { !it.isBefore(finalStmtWithTxns) && it <= finalStmtWithTxns.plusDays(60) }
                ?: dueCandidates.firstOrNull()
        } else {
            dueCandidates.firstOrNull()
        }

        val page1Numbers = lines.take(fields.illustrationStartIdx.coerceAtMost(60))
            .filterNot { lineHasBlacklistedLimit(it.lowercase()) }
            .flatMap { Text.signedHeaderAmounts(it) }
            .mapNotNull { Text.paise(it) }
            .distinct()

        val baseParsed = ParsedStatement(
            statementDate = finalStmtWithTxns,
            periodStart = periodStart,
            dueDate = pickedDue,
            // A trailing "Cr" (card in credit) comes back negative: nothing is due, and the total check still adds up.
            totalDuePaise = pickedTotal,
            minDuePaise = pickedMin,
            openingPaise = openingAmt,
            last4 = Text.last4(text),
            lines = txns,
            rewards = rewards(lines.take(fields.illustrationStartIdx), txns),
            creditLimitPaise = limit,
        )

        return StatementReconciler.reconcile(
            baseParsed,
            StatementReconciler.Context(
                labeledTotalCandidates = validHits.filterNot { it.inIllustration }.map { it.paise }.ifEmpty { candidates },
                labeledMinCandidates = minHits.filterNot { it.inIllustration }.map { it.paise }.ifEmpty { minHits.map { it.paise } },
                page1NumericCandidates = page1Numbers,
                limitPaise = limit,
                availableCreditPaise = available,
                cashLimitPaise = cashLimit,
                availableCashPaise = availCash,
                summaryTotalPaise = summaryTotal,
                equationTotalPaise = eqTotal,
            ),
        )
    }

    private fun lineHasBlacklistedLimit(low: String): Boolean =
        low.contains("credit limit") || low.contains("available credit") ||
            low.contains("cash limit") || low.contains("available cash") ||
            low.contains("reward points")

    private fun preprocessTxnLines(rawLines: List<String>): List<String> {
        val out = ArrayList<String>(rawLines.size)
        val pieChartPrefixRx = Regex("^(?:[A-Za-z/]+-\\d+%\\s+)+(?:Others-\\d+%\\s+)?(?=\\d{2}/\\d{2}/\\d{4}\\b)", RegexOption.IGNORE_CASE)
        val auDayLineRx = Regex("^(\\d{2})\\s+(.+)$")
        val auMonYrFlagRx = Regex("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(\\d{2})\\s+(Cr|Dr)$", RegexOption.IGNORE_CASE)
        var i = 0
        while (i < rawLines.size) {
            val l = pieChartPrefixRx.replace(rawLines[i], "").trim()
            val dm = auDayLineRx.matchEntire(l)
            if (dm != null) {
                val n1 = rawLines.getOrNull(i + 1)
                val n2 = rawLines.getOrNull(i + 2)
                if (n1 != null) {
                    val m1 = auMonYrFlagRx.matchEntire(n1)
                    if (m1 != null) {
                        out += "${dm.groupValues[1]} ${m1.groupValues[1]} '${m1.groupValues[2]} ${dm.groupValues[2]} ${m1.groupValues[3]}"
                        i += 2
                        continue
                    }
                    if (n2 != null) {
                        val m2 = auMonYrFlagRx.matchEntire(n2)
                        if (m2 != null && Text.AMT_ONLY.matches(n1)) {
                            out += "${dm.groupValues[1]} ${m2.groupValues[1]} '${m2.groupValues[2]} ${dm.groupValues[2]} $n1 ${m2.groupValues[3]}"
                            i += 3
                            continue
                        }
                    }
                }
            }
            out += l
            i++
        }
        return out
    }

    private fun findAvailableCredit(lines: List<String>, fields: HeaderFields): Long? {
        fields.amountHits(listOf("available credit (including cash)", "available credit limit", "available credit", "available limit"))
            .firstOrNull { !it.inIllustration }?.paise?.let { return it }
        for (l in lines) {
            val low = l.lowercase()
            if (!low.contains("available credit") && !low.contains("available limit")) continue
            val kw = if (low.contains("available credit")) "available credit" else "available limit"
            val after = l.substring(low.indexOf(kw) + kw.length)
            Text.signedHeaderAmounts(after).firstNotNullOfOrNull { Text.paise(it) }?.let { return it }
        }
        return null
    }

    private data class RawMatch(
        val index: Int,
        val date: LocalDate,
        val desc: String,
        val match: MatchResult,
        val categoryHint: String = "",
        val tightMinus: Boolean = false,
        val appendIn: Boolean = false,
    )

    private fun transactionLines(lines: List<String>, stmt: LocalDate?, periodStart: LocalDate?, fallbackYear: Int): List<ParsedLine> {
        val rawParsed = mutableListOf<RawMatch>()
        val used = HashSet<Int>()
        val tightMinusAmtRx = if (minusMeansCredit) {
            Regex("\\s-(?:\\s*(?:₹|Rs\\.?|INR|`|C|[\uE000-\uF8FF])\\s*|)\\d", RegexOption.IGNORE_CASE)
        } else {
            Regex("\\s-(?:\\s*(?:₹|Rs\\.?|INR)\\s*|(?:`|C|[\uE000-\uF8FF])\\s*)?\\d", RegexOption.IGNORE_CASE)
        }

        fun tryAdd(
            m: MatchResult,
            rawDateStr: String,
            descRaw: String,
            i: Int,
            markPrev: Boolean = false,
            markNext: Boolean = false,
            categoryHint: String = "",
        ): Boolean {
            var date = Text.date(rawDateStr, fallbackYear) ?: return false
            if (stmt != null && date.isAfter(stmt.plusDays(15)) && rawDateStr.none { it == '\'' } && Regex("\\d{4}").find(rawDateStr) == null) {
                date = date.minusYears(1)
            }
            var desc = descRaw.trim()
            if (desc.replace(Regex("^[+\\-]?\\s*(?:C|₹|Rs\\.?|INR|[\uE000-\uF8FF])?$"), "").none(Char::isLetter)) {
                val above = lines.getOrNull(i - 1)?.takeIf { it.isNotBlank() && !isTableHeader(it) && !isSummaryLine(it) && Text.TXN.matchEntire(it) == null }
                val below = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("Page ", true) && !isSummaryLine(it) && it.length < 60 && Text.TXN.matchEntire(it) == null }
                desc = listOfNotNull(above, below).joinToString(" ").replace("( ", "(").trim()
                if (above != null) used.add(i - 1)
                if (below != null) used.add(i + 1)
            }
            if (desc.none(Char::isLetter)) return false
            if (isHeaderish(desc)) return false
            val appendIn = !markNext && (i + 1) !in used && lines.getOrNull(i + 1)?.trim() == "IN"
            if (appendIn) used.add(i + 1)
            used.add(i)
            if (markPrev) used.add(i - 1)
            if (markNext) used.add(i + 1)
            val isTightMinus = m.groupValues[4] == "-" && tightMinusAmtRx.containsMatchIn(m.value)
            rawParsed += RawMatch(i, date, desc, m, categoryHint, tightMinus = isTightMinus, appendIn = appendIn)
            return true
        }

        // Pass 1: Single-line (and HDFC wrapped above/below) matches
        for ((i, line) in lines.withIndex()) {
            if (i in used) continue
            val m = Text.TXN.matchEntire(line) ?: continue
            tryAdd(m, m.groupValues[1], m.groupValues[3], i)
        }

        // Pass 2A/2B: Two-to-four line rows (Date-only + wrapped Desc/Amt, Date+partial-desc wrapped onto next lines, or Desc + Amount-only)
        for ((i, line) in lines.withIndex()) {
            if (i in used || i + 1 >= lines.size || (i + 1) in used) continue
            if (isTableHeader(line) || isSummaryLine(line)) continue
            val next = lines[i + 1]
            val prevUnconsumedDescAmt = i > 0 && (i - 1) !in used && Text.DESC_CUR_AMT.matches(lines[i - 1])
            if (Text.DATE_ONLY.matches(line) && !prevUnconsumedDescAmt) {
                val hint = Text.bulletCategory(line)
                val combined = "$line $next"
                Text.TXN.matchEntire(combined)?.let { m ->
                    if (tryAdd(m, m.groupValues[1], m.groupValues[3], i, markNext = true, categoryHint = hint)) continue
                }
                if (i + 2 < lines.size && (i + 2) !in used && !isSummaryLine(next) && !isTableHeader(next) && !isSummaryLine(lines[i + 2]) && !isTableHeader(lines[i + 2]) && !Text.startsWithDate(next) && !Text.startsWithDate(lines[i + 2])) {
                    val combined2 = "$line $next ${lines[i + 2]}"
                    Text.TXN.matchEntire(combined2)?.let { m ->
                        if (tryAdd(m, m.groupValues[1], m.groupValues[3], i, markNext = true, categoryHint = hint)) {
                            used.add(i + 2)
                            continue
                        }
                    }
                    if (i + 3 < lines.size && (i + 3) !in used && !isSummaryLine(lines[i + 3]) && !isTableHeader(lines[i + 3]) && !Text.startsWithDate(lines[i + 3])) {
                        val combined3 = "$combined2 ${lines[i + 3]}"
                        Text.TXN.matchEntire(combined3)?.let { m ->
                            if (tryAdd(m, m.groupValues[1], m.groupValues[3], i, markNext = true, categoryHint = hint)) {
                                used.add(i + 2); used.add(i + 3)
                            }
                        }
                    }
                }
            } else {
                var matchedAmtOnly = false
                if (Text.AMT_ONLY.matches(next)) {
                    val combined = "$line $next"
                    Text.TXN.matchEntire(combined)?.let { m ->
                        matchedAmtOnly = tryAdd(m, m.groupValues[1], m.groupValues[3], i, markNext = true)
                    }
                }
                if (!matchedAmtOnly && !prevUnconsumedDescAmt && !line.contains('•') && Text.amounts(line).isEmpty() && Text.startsWithDate(line) && !isSummaryLine(next) && !isTableHeader(next) && !Text.startsWithDate(next)) {
                    val combined = "$line $next"
                    val m1 = Text.TXN.matchEntire(combined)
                    if (m1 != null) {
                        tryAdd(m1, m1.groupValues[1], m1.groupValues[3], i, markNext = true)
                    } else if (i + 2 < lines.size && (i + 2) !in used && !isSummaryLine(lines[i + 2]) && !isTableHeader(lines[i + 2]) && !Text.startsWithDate(lines[i + 2])) {
                        val combined2 = "$line $next ${lines[i + 2]}"
                        val m2 = Text.TXN.matchEntire(combined2)
                        if (m2 != null) {
                            if (tryAdd(m2, m2.groupValues[1], m2.groupValues[3], i, markNext = true)) used.add(i + 2)
                        } else if (i + 3 < lines.size && (i + 3) !in used && !isSummaryLine(lines[i + 3]) && !isTableHeader(lines[i + 3]) && !Text.startsWithDate(lines[i + 3])) {
                            val combined3 = "$combined2 ${lines[i + 3]}"
                            Text.TXN.matchEntire(combined3)?.let { m3 ->
                                if (tryAdd(m3, m3.groupValues[1], m3.groupValues[3], i, markNext = true)) {
                                    used.add(i + 2); used.add(i + 3)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pass 2C: App-style two-line statements (Desc + ₹Amount on line i, Date [• Category] on i+1 or i+2) when structured rows matched nothing
        if (rawParsed.isEmpty()) {
            for ((i, line) in lines.withIndex()) {
                if (i in used || isTableHeader(line) || isSummaryLine(line)) continue
                val rm = Text.DESC_CUR_AMT.matchEntire(line) ?: continue
                val desc = rm.groupValues[1].trim()
                if (desc.none(Char::isLetter) || isHeaderish(desc) || isSummaryLine(desc)) continue
                val dateHit = (1..2).firstNotNullOfOrNull { off ->
                    val cand = lines.getOrNull(i + off) ?: return@firstNotNullOfOrNull null
                    val dt = Text.dateTokens(cand).firstOrNull() ?: return@firstNotNullOfOrNull null
                    Triple(off, dt, Text.bulletCategory(cand))
                } ?: continue
                val synthetic = "${dateHit.second} $desc ${rm.groupValues[2].trim()}"
                Text.TXN.matchEntire(synthetic)?.let { m ->
                    if (tryAdd(m, m.groupValues[1], m.groupValues[3], i, categoryHint = dateHit.third)) used.add(i + dateHit.first)
                }
            }
        }

        rawParsed.sortBy { it.index }
        val anyDrCr = rawParsed.any { (_, _, _, m, _, _) ->
            listOf(6, 8, 10).any { m.groupValues[it].isNotEmpty() }
        }
        val anyPlusSign = rawParsed.any {
            it.match.groupValues[2] == "+" || it.match.groupValues[4] == "+"
        }
        val anyPlusOrTightSign = rawParsed.any {
            it.match.groupValues[2].isNotEmpty() || it.match.groupValues[4] == "+" || (it.tightMinus && minusMeansCredit)
        }
        val earliest = (periodStart ?: stmt?.minusDays(45))?.minusDays(5)
        val latest = stmt?.plusDays(1)

        val preAmountMarkerRx = Regex("(?:\\s*\\(\\s*(CR|Cr|DR|Dr)\\.?\\s*\\)|\\s+(CR|Cr|DR|Dr)\\.?)$")
        val anyPreMarker = rawParsed.any { preAmountMarkerRx.containsMatchIn(it.desc) }
        val anyInlineMarker = anyDrCr || anyPreMarker
        val candidates = rawParsed.mapNotNull { (idx, date, rawDesc, m, rowHint, isTightMinus, appendIn) ->
            val amtStr = listOf(5, 7, 9).firstNotNullOfOrNull { i2 -> m.groupValues[i2].takeIf { it.isNotEmpty() } } ?: return@mapNotNull null
            val amount = Text.paise(amtStr) ?: return@mapNotNull null
            val trailingMarker = listOf(6, 8, 10).firstNotNullOfOrNull { i2 -> m.groupValues[i2].takeIf { it.isNotEmpty() } }.orEmpty().lowercase()
            val preMarkerMatch = if (trailingMarker.isEmpty()) preAmountMarkerRx.find(rawDesc) else null
            val preMarker = preMarkerMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty().lowercase()
            val marker = trailingMarker.ifEmpty { preMarker }
            val sign = m.groupValues[2].ifEmpty { if (anyPlusOrTightSign || isTightMinus) m.groupValues[4] else "" }
            val nearbyLine = lines.getOrNull(idx + 1)?.takeIf { it.contains("mcc", ignoreCase = true) || Text.bulletCategory(it).isNotEmpty() }
            val mcc = Text.extractMcc(rawDesc).ifEmpty { nearbyLine?.let { Text.extractMcc(it) }.orEmpty() }
            var desc = (if (preMarkerMatch != null) rawDesc.substring(0, preMarkerMatch.range.first) else rawDesc)
                .replace(Regex("^\\d{6,16}\\s+(?=[A-Za-z0-9])"), "")
                .replace(Regex("^\\d{4,5}\\s+(?=[A-Za-z])"), "")
                .replace(Regex("\\s*\\b(?:MCC|MC)\\s*[:#\\-]?\\s*\\d{4}\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:[-+]?\\s?(?:\\d{1,6}(?:\\s*pts?)?|\\d{1,3}(?:,\\d{3})+)|[-–—]{1,2})$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(₹|Rs\\.?|INR|`|[\uE000-\uF8FF])\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            var strippedAxisCat = ""
            if (stripCategorySuffixRx != null) {
                stripCategorySuffixRx.find(desc)?.let { cm ->
                    val catText = cm.groupValues[1].trim()
                    if (!catText.equals("MISCELLANEOUS", ignoreCase = true) && !catText.equals("OTHERS", ignoreCase = true)) {
                        strippedAxisCat = catText
                    }
                    desc = desc.substring(0, cm.range.first).trim()
                }
            }
            val hint = rowHint.ifEmpty { nearbyLine?.let { Text.bulletCategory(it) }.orEmpty() }.ifEmpty { strippedAxisCat }
            desc = Text.stripEmiLink(desc)
            if (appendIn && !desc.endsWith(" IN")) desc = "$desc IN"
            if (desc.none(Char::isLetter)) return@mapNotNull null
            val lower = desc.lowercase()
            val credit = when {
                marker.startsWith("c") -> true
                marker.startsWith("d") -> false
                sign == "+" -> true
                isTightMinus && (minusMeansCredit || !anyPlusSign) -> true
                sign == "-" -> false
                !anyInlineMarker && !anyPlusOrTightSign -> !(lower.contains("cashback") && lower.contains("reversal")) &&
                    !(lower.contains("payment") && lower.contains("reversal")) &&
                    credits.any { lower.contains(it) }
                else -> false
            }
            val ref = Regex("\\b(?:ref|reference|arn)[:#\\s]*([A-Z0-9]{6,})", RegexOption.IGNORE_CASE).find(desc)?.groupValues?.get(1).orEmpty()
            date to ParsedLine(date, desc, amount, credit, ref, categoryHint = hint, mcc = mcc)
        }

        val inRange = candidates.filter { (d, _) ->
            (earliest == null || !d.isBefore(earliest)) && (latest == null || !d.isAfter(latest))
        }
        // If a mis-parsed header date excluded every single transaction, keep the parsed lines instead of returning empty.
        val finalList = if (inRange.isNotEmpty() || stmt == null) inRange else candidates
        return finalList.map { it.second }
    }

    private fun isSummaryLine(l: String): Boolean {
        val low = l.lowercase()
        return low.contains("total amount due") || low.contains("total due") || low.contains("min amount due") ||
            low.contains("minimum amount") || low.contains("minimum due") || low.contains("opening balance") ||
            low.contains("closing balance") || low.contains("previous balance") || low.contains("carry forward") ||
            low.contains("credit limit") || low.contains("available credit") || low.contains("available cash") ||
            low.contains("statement summary") || low.contains("reward points") || low.contains("gst details") ||
            low.startsWith("statement ") || low.startsWith("payment due") || low.startsWith("due on ")
    }

    private fun isTableHeader(l: String): Boolean {
        val low = l.lowercase()
        return (low.contains("date") && (low.contains("description") || low.contains("amount"))) || low.contains("ckyc") ||
            low.endsWith("transactions")
    }

    /**
     * Reward points from a points table ("Reward Points | Opening Balance | Earned | Disbursed | Lapsed" with the
     * numbers on a following line: closing balance first, then opening, then earned), and cashback from a
     * "Cash Back Summary … Total ₹x" block or, failing that, the net of cashback lines.
     */
    private fun rewards(lines: List<String>, txns: List<ParsedLine>): Rewards? {
        var balance: Long? = null
        var earned: Long? = null
        val lower = lines.map { it.lowercase() }
        val unit = rewardUnitHint ?: when {
            lower.any { it.contains("neucoin") } -> "NeuCoins"
            lower.any { it.contains("cashpoints") || it.contains("cash points") } -> "CashPoints"
            lower.any { it.contains("membership reward") } -> "Membership Rewards"
            lower.any { it.contains("jewels") } -> "Jewels"
            lower.any { it.contains("scapia") } -> "Scapia coins"
            else -> "points"
        }
        val ptsHead = lower.indexOfFirst { l ->
            (l.startsWith("reward points") || l.startsWith("neucoins") || l.startsWith("cashpoints") ||
                l.startsWith("membership rewards") || l.contains("reward points summary")) &&
                (l.contains("opening") || l.contains("earned") || l.contains("closing"))
        }
        if (ptsHead >= 0) {
            val headText = (lower[ptsHead] + " " + lower.getOrElse(ptsHead + 1) { "" }).trim()
            val nums = lines.drop(ptsHead + 1).take(4).firstNotNullOfOrNull { l ->
                l.takeIf { Regex("^[\\d,\\s]+$").matches(it) }?.split(' ')?.filter { it.isNotBlank() }?.mapNotNull { it.replace(",", "").toLongOrNull() }
                    ?.takeIf { it.size >= 3 }
            }
            if (nums != null) {
                val hdfcOrder = headText.startsWith("reward points opening balance feature") ||
                    (headText.contains("opening balance") && !headText.contains("closing"))
                if (hdfcOrder) {
                    balance = nums.getOrNull(0)
                    earned = nums.getOrNull(2)
                } else {
                    // Standard order across SBI, Axis, IDFC, Kotak, Yes, RBL, Amex: Opening | Earned | Redeemed/Adjusted | Closing
                    earned = nums.getOrNull(1)
                    balance = nums.lastOrNull()
                }
            }
        }
        if (earned == null) {
            val earnedLine = lower.indexOfFirst {
                it.contains("points earned") || it.contains("neucoins earned") || it.contains("cashpoints earned") ||
                    it.startsWith("earned +") || it.startsWith("earned:") || Regex("^earned\\s+\\d").containsMatchIn(it)
            }
            if (earnedLine >= 0) {
                earned = Regex("\\b(\\d[\\d,]*)\\b").findAll(lines[earnedLine]).lastOrNull()?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            } else if (ptsHead >= 0) {
                earned = lines.drop(ptsHead + 1).take(3)
                    .firstNotNullOfOrNull { l -> Regex("^(\\d[\\d,]*)\\s*pts?$", RegexOption.IGNORE_CASE).matchEntire(l)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() }
            }
            if (earned == null) {
                val earnedHeader = lower.indexOfFirst { it == "earned" }
                if (earnedHeader >= 0) {
                    earned = lines.drop(earnedHeader + 1).take(4)
                        .firstNotNullOfOrNull { l ->
                            l.takeIf { Regex("^[\\d,\\s]+$").matches(it) }
                                ?.split(' ')?.firstOrNull { it.isNotBlank() }?.replace(",", "")?.toLongOrNull()
                        }
                }
            }
        }
        if (balance == null) {
            val balLine = lower.indexOfFirst {
                ((it.contains("closing balance") && !it.contains("credit limit")) ||
                    it.contains("available points") || it.contains("total reward points") || it.contains("neucoins balance")) &&
                    !it.contains("amount") && !it.contains("₹") && !it.contains("rs") && !it.contains("%")
            }
            if (balLine >= 0) {
                balance = Regex("(?<!\\.)\\b(\\d[\\d,]*)\\b(?!\\s*%)").findAll(lines[balLine]).lastOrNull()?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            }
        }
        val cbHead = lower.indexOfFirst { l ->
            val compact = l.replace(" ", "")
            compact.startsWith("cashbacksummary") || l.contains("cashback summary") || l.contains("amazon pay cashback")
        }
        val cashback = if (cbHead >= 0) {
            lines.drop(cbHead + 1).take(12)
                .firstOrNull { l ->
                    val lc = l.lowercase()
                    lc.startsWith("total") || lc.contains("cashback earned") || lc.contains("credited")
                }
                ?.let { Text.signedHeaderAmounts(it).firstOrNull { a -> !a.startsWith("-") } ?: Text.amounts(it).firstOrNull() }
                ?.let { Text.paise(it) }
                ?: lines.drop(cbHead + 1).take(6)
                    .firstNotNullOfOrNull { Text.signedHeaderAmounts(it).lastOrNull() }
                    ?.let { Text.paise(it) }
        } else {
            val explicitCb = lower.indexOfFirst { it.contains("total cashback earned") || it.contains("cashback earned this cycle") || it.contains("cashback earned:") }
            if (explicitCb >= 0) {
                (Text.signedHeaderAmounts(lines[explicitCb]).firstOrNull { !it.startsWith("-") }
                    ?: Text.amounts(lines[explicitCb]).firstOrNull())?.let { Text.paise(it) }
            } else null
        } ?: run {
            val cb = txns.filter { it.description.contains("cashback", true) || it.description.contains("cash back", true) }
            (cb.filter { it.isCredit }.sumOf { it.amountPaise } - cb.filter { !it.isCredit }.sumOf { it.amountPaise }).takeIf { it > 0 }
        }
        return Rewards(pointsBalance = balance, pointsEarned = earned, cashbackPaise = cashback, unit = unit).takeUnless { it.isEmpty }
    }

    private fun isHeaderish(desc: String): Boolean {
        val l = desc.lowercase()
        val extra = listOf(
            "credit limit", "available credit", "available cash", "carried forward", "brought forward",
            "total debits", "total credits", "total spends", "statement summary", "payment summary",
        )
        return (total + min + due + opening + extra).any { l.startsWith(it) }
    }
}

/** Finds labelled values, handling both "Label: value" and column headers with values on the next line. */
private class HeaderFields(rawLines: List<String>) {
    private val lines = foldWrappedHeaderLines(rawLines)
    private val lower = lines.map { it.lowercase() }
    val illustrationStartIdx: Int = lines.indices.drop(8).firstOrNull { i ->
        val l = lower[i]
        l.contains("illustrative example") || l.contains("illustration") || l.contains("sample calculation") ||
            l.contains("how minimum amount due is calculated") || l.contains("key fact statement") ||
            l.contains("most important terms") || l.contains("important information") ||
            l.contains("understanding your statement") || l.contains("terms and conditions") ||
            l.contains("terms & conditions") || l.contains("interest calculation") || l == "glossary"
    } ?: lines.size

    data class AmountHit(
        val paise: Long,
        val explicit: Boolean,
        val nearLimit: Boolean,
        val inIllustration: Boolean = false,
        val hasDecimals: Boolean = true,
    )

    /** Signed: "1,234.00 Cr" is −1,234.00 (a credit balance). */
    fun amount(labels: List<String>): Long? = amountHits(labels).sortedBy { it.inIllustration }.firstOrNull()?.paise

    fun amountHits(labels: List<String>): List<AmountHit> =
        findAll(labels, Kind.AMOUNT) { Text.signedHeaderAmounts(it).firstOrNull() }
            .mapNotNull { hit ->
                Text.paise(hit.value)?.let { p ->
                    AmountHit(p, hit.explicit, hit.nearLimit, hit.inIllustration, hasDecimals = hit.value.contains('.'))
                }
            }

    fun nonIllustrationDateHits(labels: List<String>, fallbackYear: Int? = null): List<LocalDate> =
        findAll(labels, Kind.DATE) { Text.dateTokens(it).firstOrNull() }
            .filterNot { it.inIllustration }
            .mapNotNull { Text.date(it.value, fallbackYear) }

    fun dateHits(labels: List<String>, fallbackYear: Int? = null): List<LocalDate> {
        val raw = findAll(labels, Kind.DATE) { Text.dateTokens(it).firstOrNull() }
        val nonIllus = raw.filterNot { it.inIllustration }
        return (nonIllus.ifEmpty { raw }).mapNotNull { Text.date(it.value, fallbackYear) }
    }

    fun dates(labels: List<String>, n: Int, defaultYear: Int? = null): List<LocalDate> {
        fun parseSlice(s: String): List<LocalDate> {
            val toks = Text.dateTokens(s)
            val explicitYears = toks.mapNotNull { Text.date(it) }
            val y = explicitYears.lastOrNull()?.year ?: defaultYear
            val parsed = if (explicitYears.size >= n || y == null) {
                explicitYears.toMutableList()
            } else {
                toks.mapNotNull { Text.date(it, y) }.toMutableList()
            }
            if (n == 2 && parsed.size >= 2 && toks.size >= 2 && parsed[0].isAfter(parsed[1])) {
                if (Text.date(toks[0]) == null) parsed[0] = parsed[0].minusYears(1)
                else if (Text.date(toks[1]) == null) parsed[1] = parsed[1].plusYears(1)
            }
            return parsed
        }
        for ((i, l) in lower.withIndex()) {
            val label = labels.firstOrNull { l.contains(it) } ?: continue
            val inline = stripNextLabel(lines[i].substring(l.indexOf(label) + label.length))
            val inlineDates = parseSlice(inline)
            if (inlineDates.size >= n) return inlineDates.take(n)

            val next = lines.getOrNull(i + 1) ?: continue
            if (allLabelsOnLine(next.lowercase()).any { kindOf(it) != Kind.AMOUNT }) continue
            val combinedDates = parseSlice("$inline ${stripNextLabel(next)}")
            if (combinedDates.size >= n) return combinedDates.take(n)
        }
        return emptyList()
    }

    private data class Hit(val value: String, val explicit: Boolean, val nearLimit: Boolean, val inIllustration: Boolean = false)

    private fun isLimitText(nl: String): Boolean =
        nl.contains("limit") || nl.contains("sanctioned") || nl.contains("available credit") ||
            nl.contains("available cash")

    private fun findAll(labels: List<String>, targetKind: Kind, pick: (String) -> String?): List<Hit> {
        val hits = mutableListOf<Hit>()
        for ((i, l) in lower.withIndex()) {
            val label = labels.firstOrNull { l.contains(it) } ?: continue
            val start = l.indexOf(label)
            val inIllus = i >= illustrationStartIdx

            // Column layout: several labels on this line, values on the next line (skipping up to 2 short unit/sub-label lines).
            val onLine = allLabelsOnLine(l)
            if (onLine.any { (eLab, eStart) -> start >= eStart && start < eStart + eLab.length && eLab !in labels }) continue
            val sameKind = onLine.filter { kindOf(it) == targetKind }
            val idxInSame = sameKind.indexOfFirst { start >= it.second && start < it.second + it.first.length }
            val hasInlineColonOrToks = l.contains(':') ||
                (if (targetKind == Kind.DATE) Text.dateTokens(l).isNotEmpty() else Text.signedHeaderAmounts(l).isNotEmpty())
            if (onLine.size >= 2 && idxInSame >= 0 && !hasInlineColonOrToks) {
                fun tokCount(s: String) = if (targetKind == Kind.DATE) Text.dateTokens(s).size else Text.signedHeaderAmounts(s).size
                val periodCountBefore = if (targetKind == Kind.DATE) {
                    val targetPos = sameKind[idxInSame].second
                    onLine.count { kindOf(it) == Kind.PERIOD && it.second < targetPos }
                } else 0
                val totalPeriodsOnLine = if (targetKind == Kind.DATE) onLine.count { kindOf(it) == Kind.PERIOD } else 0
                val expectedToks = sameKind.size + (2 * totalPeriodsOnLine)
                val targetTokenIdx = idxInSame + (2 * periodCountBefore)

                val n1 = lines.getOrNull(i + 1)
                val n2 = lines.getOrNull(i + 2)
                val n3 = lines.getOrNull(i + 3)
                fun cleanRow(s: String?) = s != null && allLabelsOnLine(s.lowercase()).isEmpty() && !isLimitText(s.lowercase())
                val n1InterleavedSingle = n1 != null && !isLimitText(n1.lowercase()) &&
                    allLabelsOnLine(n1.lowercase()).size == 1 && tokCount(n1) == 1 && expectedToks >= 2
                val valRow = when {
                    cleanRow(n1) && tokCount(n1!!) == expectedToks -> n1
                    cleanRow(n1) && cleanRow(n2) && tokCount(n1!!) in 1 until expectedToks &&
                        tokCount("$n1 $n2") == expectedToks -> "$n1 $n2"
                    n1InterleavedSingle && cleanRow(n2) && tokCount(n2!!) == expectedToks -> n2
                    n1 != null && cleanRow(n1) && tokCount(n1) == 0 && cleanRow(n2) && tokCount(n2!!) == expectedToks -> n2
                    n1 != null && cleanRow(n1) && tokCount(n1) == 0 && cleanRow(n2) && cleanRow(n3) &&
                        tokCount(n2!!) in 1 until expectedToks && tokCount("$n2 $n3") == expectedToks -> "$n2 $n3"
                    n1 != null && n2 != null && tokCount(n1) == 0 && tokCount(n2) == 0 &&
                        allLabelsOnLine(n1.lowercase()).isEmpty() && allLabelsOnLine(n2.lowercase()).isEmpty() &&
                        cleanRow(n3) && tokCount(n3!!) == expectedToks -> n3
                    else -> null
                }
                if (valRow != null) {
                    val values = if (targetKind == Kind.DATE) Text.dateTokens(valRow) else Text.signedHeaderAmounts(valRow)
                    if (values.size == expectedToks && targetTokenIdx in values.indices) {
                        val targetIsLimit = isLimitLabel(sameKind[idxInSame].first)
                        hits += Hit(
                            value = values[targetTokenIdx],
                            explicit = true,
                            nearLimit = targetIsLimit || (targetKind == Kind.AMOUNT && isLimitText(l)),
                            inIllustration = inIllus,
                        )
                        continue
                    }
                }
            }

            // Stacked single-label vertical column block: Label1\nLabel2\nVal1\nVal2 (common when PDFBox interleaves 2 boxes)
            if (onLine.size == 1 && sameKind.size == 1) {
                fun singleKindLabel(lineIdx: Int): Pair<Kind, String>? {
                    val ls = lower.getOrNull(lineIdx) ?: return null
                    val lbls = allLabelsOnLine(ls)
                    return lbls.singleOrNull()?.let { kindOf(it) to it.first }
                }
                fun singleKindValueCount(lineIdx: Int): List<String>? {
                    val row = lines.getOrNull(lineIdx) ?: return null
                    if (allLabelsOnLine(row.lowercase()).isNotEmpty() || isLimitText(row.lowercase())) return null
                    val toks = if (targetKind == Kind.DATE) Text.dateTokens(row) else Text.signedHeaderAmounts(row)
                    return toks.takeIf { it.size == 1 }
                }
                val startOfBlock = (i downTo (i - 3).coerceAtLeast(0)).takeWhile { idx ->
                    val info = singleKindLabel(idx)
                    info != null && info.first == targetKind && singleKindValueCount(idx) == null
                }.lastOrNull() ?: i
                val blockLabels = mutableListOf<Pair<Int, String>>()
                var cursor = startOfBlock
                while (blockLabels.size < 5) {
                    val info = singleKindLabel(cursor) ?: break
                    if (info.first != targetKind || singleKindValueCount(cursor) != null) break
                    val lineTokens = if (targetKind == Kind.DATE) Text.dateTokens(lines[cursor]) else Text.signedHeaderAmounts(lines[cursor])
                    if (lineTokens.isNotEmpty() || lines[cursor].contains(':')) break
                    blockLabels += cursor to info.second
                    cursor++
                }
                if (blockLabels.size in 2..4) {
                    val blockPos = blockLabels.indexOfFirst { it.first == i }
                    val vals = (0 until blockLabels.size).mapNotNull { off -> singleKindValueCount(cursor + off)?.singleOrNull() }
                    if (blockPos >= 0 && vals.size == blockLabels.size) {
                        val anyLimitInBlock = blockLabels.any { isLimitLabel(it.second) || isLimitText(lower[it.first]) }
                        hits += Hit(
                            value = vals[blockPos],
                            explicit = true,
                            nearLimit = isLimitLabel(blockLabels[blockPos].second) || anyLimitInBlock,
                            inIllustration = inIllus,
                        )
                        continue
                    }
                }
            }

            // Skip explanatory prose sentences ("For example...", "If Total Amount Due is...", "Please pay... to avoid late fees").
            val tailLow = l.substring(start)
            val isMultiPaneTail = start > 20 && tailLow.length <= 64 &&
                !EXAMPLE_PROSE.any { tailLow.contains(it) } &&
                !PROSE_PHRASES.any { tailLow.contains(it) }
            val isExampleProse = EXAMPLE_PROSE.any { l.contains(it) }
            val prose = (l.length > 72 && !isMultiPaneTail) || PROSE_PHRASES.any { l.contains(it) }
            if (isExampleProse || (prose && !l.contains(':') && onLine.size <= 1)) continue

            // Inline: value after the label on the same line.
            val rest = lines[i].substring(start + label.length)
            val inlineTrim = stripNextLabel(rest)
            val inlinePick = pick(inlineTrim)
                ?: if (targetKind == Kind.AMOUNT && rest.trimStart().startsWith(":")) standaloneSmallWholeAmount(inlineTrim) else null
            inlinePick?.let {
                hits += Hit(it, explicit = rest.trimStart().startsWith(":") || inlineTrim.isNotBlank(), nearLimit = isLimitText(l), inIllustration = inIllus)
                continue
            }

            if (onLine.size >= 2 && idxInSame >= 0) {
                val n1 = lines.getOrNull(i + 1)
                if (n1 != null && allLabelsOnLine(n1.lowercase()).isEmpty() && !isLimitText(n1.lowercase())) {
                    val expectedCols = sameKind.size + (if (targetKind == Kind.DATE) 2 * onLine.count { kindOf(it) == Kind.PERIOD } else 0)
                    val n1Values = if (targetKind == Kind.DATE) Text.dateTokens(n1) else Text.signedHeaderAmounts(n1)
                    val colIdx = idxInSame + (if (targetKind == Kind.DATE) 2 * onLine.count { kindOf(it) == Kind.PERIOD && it.second < sameKind[idxInSame].second } else 0)
                    if (n1Values.size == expectedCols && colIdx in n1Values.indices) {
                        val targetIsLimit = isLimitLabel(sameKind[idxInSame].first)
                        hits += Hit(
                            value = n1Values[colIdx],
                            explicit = true,
                            nearLimit = targetIsLimit,
                            inIllustration = inIllus,
                        )
                        continue
                    }
                }
            }

            // Never pull an unlabelled amount from the next row when multiple same-kind columns or limit labels share this line.
            val lineHasLimit = targetKind == Kind.AMOUNT && isLimitText(l)
            fun isSkippableUnitLine(raw: String, low: String): Boolean =
                allLabelsOnLine(low).isEmpty() && pick(raw) == null &&
                    (low == "_" || (low.startsWith("(") && low.endsWith(")")) ||
                        low in setOf("rs", "rs.", "inr", "₹", "`", ":", "(in ₹)", "(in rs.)", "amount in rs.", "amount in inr"))

            fun nextPick(raw: String, low: String): String? {
                val labelsOnNext = allLabelsOnLine(low)
                if (targetKind == Kind.DATE) {
                    if (labelsOnNext.any { kindOf(it) != Kind.AMOUNT }) return null
                    val sliced = stripNextLabel(raw)
                    return pick(sliced) ?: pick(raw)
                }
                if (labelsOnNext.isNotEmpty() || isLimitText(low)) return null
                return pick(raw) ?: standaloneSmallWholeAmount(raw)
            }

            if (!lineHasLimit && sameKind.size <= 1 && (sameKind.isEmpty() || idxInSame <= 0)) {
                val candidateNext = lines.getOrNull(i + 1)?.let { n ->
                    val nl = n.lowercase()
                    nextPick(n, nl)?.let { n to it }
                } ?: lines.getOrNull(i + 1)?.takeIf { n1 ->
                    isSkippableUnitLine(n1, n1.lowercase())
                }?.let {
                    lines.getOrNull(i + 2)?.let { n2 ->
                        nextPick(n2, n2.lowercase())?.let { n2 to it }
                    }
                }
                if (candidateNext != null) {
                    val prevHasLimit = targetKind == Kind.AMOUNT && isLimitText(lower.getOrElse(i - 1) { "" })
                    hits += Hit(candidateNext.second, explicit = false, nearLimit = prevHasLimit, inIllustration = inIllus)
                }
            }
        }
        return hits
    }

    /** Cut off at the next known label so "Total Dues 1,000.00 Minimum Due 50.00" doesn't bleed. */
    private fun stripNextLabel(rest: String): String {
        val l = rest.lowercase()
        val cut = ALL.mapNotNull { lab -> l.indexOf(lab).takeIf { it > 0 } }.minOrNull() ?: return rest
        return rest.substring(0, cut)
    }

    private fun standaloneSmallWholeAmount(raw: String): String? {
        val m = SMALL_WHOLE_AMOUNT_RX.matchEntire(raw.trim().removePrefix(":").trim()) ?: return null
        val n = m.groupValues[1]
        return if (m.groupValues[2].isNotEmpty()) "-$n" else n
    }

    private enum class Kind { DATE, AMOUNT, PERIOD }

    private fun kindOf(p: Pair<String, Int>) = kindOf(p.first)
    private fun kindOf(label: String) = when {
        PERIOD_LABELS.any { label.contains(it) } -> Kind.PERIOD
        DATE_LABELS.any { label.contains(it) } -> Kind.DATE
        else -> Kind.AMOUNT
    }

    private fun isLimitLabel(label: String): Boolean =
        label.contains("limit") || label.contains("available credit") || label.contains("available cash")

    private fun allLabelsOnLine(l: String): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (lab in ALL.sortedByDescending { it.length }) {
            var from = 0
            while (true) {
                val at = l.indexOf(lab, from)
                if (at < 0) break
                val end = at + lab.length
                if (hits.none { (eLab, eStart) -> at < eStart + eLab.length && end > eStart }) hits += lab to at
                from = at + lab.length
            }
        }
        return hits.sortedBy { it.second }
    }

    companion object {
        private val SMALL_WHOLE_AMOUNT_RX = Regex("^[-+]?\\s*(\\d{1,7})(?:\\s*\\(?\\s*(Cr|CR|cr)\\s*\\)?)?$")
        val PERIOD_LABELS = listOf("period", "cycle", "statement for")
        val DATE_LABELS = listOf("date", "dt", "please pay by", "pay by", "due by", "due on", "generated on", "last date of payment")
        private val EXAMPLE_PROSE = listOf("for example", "illustrative", "assuming", "say rs", "is calculated")
        private val PROSE_PHRASES = listOf(
            "is generated", "will be", "please pay", "if you", "subject to", "always pay", "in order to",
            "avoid late", "will attract", "auto-debit", "auto debit", "standing instruction", "please note",
        )
        val ALL = listOf(
            "your total amount due", "current total amount due", "total amount due (tad)", "total amount due", "total bill amount", "total dues", "total amt due", "total amt. due", "total due amount",
            "total payment due", "total amount payable", "total payable", "net payable", "payable amount", "total outstanding", "current outstanding",
            "statement balance", "total statement balance", "statement total", "closing balance", "total new balance", "current balance", "new balance", "total due", "amount due", "please pay",
            "minimum amount due (mad)", "minimum amount due", "minimum payment due", "min. amount due", "min amount due", "min. amt. due", "min amt due", "minimum amount payable", "minimum amount", "minimum due",
            "payment due date", "due date", "payment due on", "please pay by", "pay by", "due by", "due on", "last date of payment", "payment due by",
            "statement generation date", "date of statement", "bill generation date", "statement closing date", "statement generated on", "bill generated on",
            "statement date", "stmt date", "stmt. date", "statement dt", "bill dt", "closing date", "bill date", "billing date", "generated on", "invoice date",
            "statement period", "billing period", "billing cycle", "statement for", "for the period", "period from",
            "opening balance", "previous balance", "prev balance", "prev. balance", "previous statement dues", "previous outstanding", "last statement balance", "previous amount due", "opening bal",
            "purchases / other charges", "purchases & other charges", "purchases & other debits", "purchases / charges", "purchases/ charges", "purchases/charges", "purchases & charges", "purchases / debits", "current purchases", "current debits", "spends & charges", "purchases", "cash advances", "cash advance",
            "payments / credits", "payments/ credits", "payments/credits", "payments & credits", "payments / refunds", "repayments & refunds", "repayments", "other debits & charges", "finance charges", "fees & charges", "fee / charges", "interest charges", "new credits", "new debits", "new charges", "spends",
            "total credit limit", "sanctioned credit limit", "sanctioned limit", "maximum credit limit", "total card limit", "assigned credit limit", "credit limit (including cash)", "available credit (including cash)", "available credit limit", "available cash limit", "available cash (including cash)", "cash limit (including cash)", "total cash limit",
            "limit (including cash)",
            "credit limit", "card limit", "total limit", "total credit", "available credit", "available cash", "available limit", "cash limit", "spend limit", "overlimit",
        )

        private fun foldWrappedHeaderLines(rawLines: List<String>): List<String> {
            if (rawLines.size < 2) return rawLines
            val out = ArrayList<String>(rawLines.size)
            var i = 0
            while (i < rawLines.size) {
                val cur = rawLines[i]
                val next = rawLines.getOrNull(i + 1)
                if (next != null && shouldFoldWrappedHeader(cur, next)) {
                    out += "$cur $next".replace(Regex("\\s+"), " ").trim()
                    i += 2
                } else {
                    out += cur
                    i++
                }
            }
            return out
        }

        private fun shouldFoldWrappedHeader(l1: String, l2: String): Boolean {
            val low1 = l1.trim().lowercase()
            val low2 = l2.trim().lowercase()
            if (low1.length !in 3..28 || low2.length !in 3..28) return false
            if (low1.contains(':') || low2.contains(':') || low1.any(Char::isDigit) || low2.any(Char::isDigit)) return false
            if (Text.signedHeaderAmounts(l1).isNotEmpty() || Text.signedHeaderAmounts(l2).isNotEmpty()) return false
            if (Text.dateTokens(l1).isNotEmpty() || Text.dateTokens(l2).isNotEmpty()) return false
            val combined = "$low1 $low2".replace(Regex("\\s+"), " ")
            val combinedLab = ALL.firstOrNull { combined == it || combined.startsWith("$it ") } ?: return false
            val exactL1 = ALL.any { low1 == it }
            return (!exactL1 || combinedLab.length > low1.length) && ALL.none { low2 == it && combinedLab.length == low2.length }
        }
    }
}

object Text {
    /**
     * HDFC prints a clickable "EMI" link (convert this purchase to EMI) before eligible lines, and it
     * comes out as a leading "EMI" word. Real EMI lines say what they are ("EMI PRINCIPAL", "EMI INTEREST",
     * "EMI 3/6", processing fee…), so those keep it.
     */
    private val emiLink = Regex(
        "^EMI\\s+(?!PRINCIPAL|PRIN\\b|INTEREST|INT\\b|INT\\.|PROC|PROCESSING|CONV|CONVERSION|FORECLOS|PRE-?CLOS|AMOUNT|AMT\\b|INST|INSTALL|\\d+\\s*/\\s*\\d+|LOAN|BOOKED|CANCEL)",
        RegexOption.IGNORE_CASE,
    )

    fun stripEmiLink(desc: String): String = desc.replace(emiLink, "").trim().ifEmpty { desc }

    /** Collapses faux-bold doubled tokens like "SSTTAATTEEMMEENNTT  DDAATTEE" -> "STATEMENT DATE". */
    fun collapseFauxBold(s: String): String {
        if (s.length < 4) return s
        val words = s.split(' ')
        var anyChanged = false
        val out = words.map { tok ->
            val len = tok.length
            val canBeDoubled = len >= 4 && len % 2 == 0 &&
                tok.count(Char::isLetter) >= 4 &&
                tok.filter(Char::isLetter).toSet().size >= 2 &&
                (0 until len step 2).all { i -> tok[i] == tok[i + 1] }
            if (canBeDoubled) {
                anyChanged = true
                buildString(len / 2) {
                    for (i in 0 until len step 2) append(tok[i])
                }
            } else {
                tok
            }
        }
        return if (!anyChanged) s else out.joinToString(" ").replace(Regex("[ \t]+"), " ").trim()
    }

    private val mccRx = Regex("\\b(?:MCC|MC)\\s*[:#\\-]?\\s*(\\d{4})\\b", RegexOption.IGNORE_CASE)
    private val parenMccRx = Regex("(?:^|\\s)\\((\\d{4})\\)(?=\\s|$)")

    fun extractMcc(s: String): String {
        mccRx.find(s)?.groupValues?.get(1)?.takeIf { isValidMcc(it) }?.let { return it }
        parenMccRx.find(s)?.groupValues?.get(1)?.takeIf { isValidMcc(it) }?.let { return it }
        return ""
    }

    private fun isValidMcc(code: String): Boolean {
        val n = code.toIntOrNull() ?: return false
        return n in 3000..9999
    }

    fun bulletCategory(s: String): String {
        val after = s.substringAfter('•', "").trim()
        if (after.isEmpty()) return ""
        return after.split('•').firstOrNull { seg ->
            val t = seg.trim()
            t.any(Char::isLetter) && !t.equals("UPI", ignoreCase = true)
        }?.trim().orEmpty()
    }

    private const val MON = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
    private const val DATE_RX =
        "(?:\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.](?:\\d{4}|\\d{2})|(?<![\\d.,/\\-])(?!24/7\\b)(?:0?[1-9]|[12]\\d|3[01])[/\\-](?:0?[1-9]|1[0-2])(?![\\d.,/\\-])|\\d{1,2}(?:st|nd|rd|th)?[ \\-/.](?:$MON)[a-z]*(?:(?:\\s*,\\s*|[\\-/.]|\\s+)(?:\\d{4}|'\\d{2}|\\d{2}(?!\\s+(?:$MON)\\b))(?!\\d))?|(?:$MON)[a-z]*[ .\\-]\\s*\\d{1,2}(?:st|nd|rd|th)?(?!(?:\\d|[a-z]))(?:(?:,\\s*|[\\s\\-]+)(?:\\d{4}|'?\\d{2})(?!\\d))?)"
    private const val AMOUNT_RX = "(?<![\\d,.])\\d+(?:,\\d+)*\\.\\d{2}(?!\\d)"
    private const val AMT_ANY_RX = "(?<![\\d,.])\\d+(?:,\\d+)*(?:\\.\\d{1,2})?(?!\\d)"

    private val DATE = Regex(DATE_RX, RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex(AMOUNT_RX)
    private val DATE_START = Regex("^(?:\\d{1,3}\\s+)?($DATE_RX)(?=\\s|$)", RegexOption.IGNORE_CASE)

    fun startsWithDate(line: String): Boolean = DATE_START.containsMatchIn(line)

    val DATE_ONLY = Regex(
        "^(?:\\d{1,3}\\s+)?($DATE_RX)(?:\\s*[|,]?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?(?:\\s*IST)?)?$",
        RegexOption.IGNORE_CASE,
    )
    val AMT_ONLY = Regex(
        "^([+-])?\\s*(?:(?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP|`|C)\\s*($AMT_ANY_RX)|($AMOUNT_RX)(?:\\s*(?:INR|Rs\\.?|₹|USD|EUR|GBP))?|($AMT_ANY_RX)(?:\\s*(?:INR|Rs\\.?|₹|USD|EUR|GBP))?\\s*\\(?\\s*(Cr|CR|cr|Dr|DR|dr|C|D)\\.?\\s*\\)?)\\s*(?:\\(?\\s*(Cr|CR|cr|Dr|DR|dr|C|D)\\.?\\s*\\)?)?\\.?$",
        RegexOption.IGNORE_CASE,
    )
    val DESC_CUR_AMT = Regex(
        "^(.+?)\\s+((?:[+-]\\s*)?(?:₹|\\$|€|£|Rs\\.?|INR)\\s*$AMT_ANY_RX(?:\\s*\\(?\\s*(?:Cr|CR|cr|Dr|DR|dr)\\.?\\s*\\)?)?)$",
        RegexOption.IGNORE_CASE,
    )

    /*
     * [serial|card] date[| time] [date] [+|-] description [+|-] [₹] amount [Cr|Dr] [pts|bullet]
     * Groups: 1 date, 2 leading sign, 3 description, 4 trailing sign, 5 amount, 6 Cr/Dr marker.
     */
    val TXN = Regex(
        "^(?:\\d{1,3}\\s+|[•●\\-*]\\s+|(?:\\d{4,6}[Xx*•]{4,8}\\d{4}|\\d{4}[ -]?(?:\\d{2}[Xx*•]{2}|[Xx*•]{4})[ -]?[Xx*•]{4}[ -]?\\d{4}|(?:[Xx*•]{2,4}[ -]?){1,3}\\d{4})\\s+|\\|\\s*)?($DATE_RX)(?:\\s*[|,]?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?(?:\\s*IST)?)?\\s+(?:$DATE_RX\\s+)?([+-])?\\s*(.+?)\\s+([+-])?\\s*(?:(?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP|`|[\uE000-\uF8FF]|(?<![A-Za-z])(?-i:C))\\s*($AMT_ANY_RX)\\s*(?:\\(?\\s*(Cr|CR|cr|C|Dr|DR|dr|D)\\.?\\s*\\)?)?|($AMOUNT_RX)(?:\\s*(?:INR|Rs\\.?|₹|USD|EUR|GBP)\\b)?\\s*(?:\\(?\\s*(Cr|CR|cr|C|Dr|DR|dr|D)\\.?\\s*\\)?)?|($AMT_ANY_RX)(?:\\s*(?:INR|Rs\\.?|₹|USD|EUR|GBP)\\b)?\\s*\\(?\\s*(Cr|CR|cr|Dr|DR|dr)\\.?\\s*\\)?)\\.?(?:\\s+[l•●]|\\s+[+-]?\\s*\\d{1,6}(?:\\s*pts?)?)*$",
        RegexOption.IGNORE_CASE,
    )

    private val MONEY_TOKEN = Regex("^(?:C|₹|`|[\uE000-\uF8FF]|Rs\\.?|INR)?\\s?(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d{2})?)$")
    private val GROUPED_WHOLE_INR = Regex("(?<![\\d,.])(\\d{1,3}(?:,\\d{2})*,\\d{3})(?!\\d)")

    /**
     * Total credit limit. Handles inline ("Sanctioned Credit Limit: 150,000.00" or "Credit Limit (Including cash) `1,40,000.00"),
     * multi-column ("Credit Limit   Available Credit\n1,40,000.00  1,38,600.00"), and HDFC's whole-rupee column block.
     * "Available" limits alone are never taken, and page-2 illustrative examples are skipped.
     */
    fun creditLimit(lines: List<String>): Long? {
        val illustrationIdx = lines.indices.drop(8).firstOrNull { idx ->
            val ll = lines[idx].lowercase()
            ll.contains("illustrative example") || ll.contains("sample calculation") ||
                ll.contains("how minimum amount due is calculated") || ll.contains("key fact statement")
        } ?: lines.size
        val creditLimitKwRx = Regex(
            "(?<!available\\s)(?:total\\s+|sanctioned\\s+|maximum\\s+|assigned\\s+|card\\s+)?(?:credit\\s*limit|cr\\.?\\s*limit)",
            RegexOption.IGNORE_CASE,
        )
        fun wholeRupeeInSlice(s: String): String? {
            Regex("(?:[₹`\uE000-\uF8FF]|Rs\\.?|INR|(?<![A-Za-z])(?-i:C))\\s*(\\d{4,8}|\\d+(?:,\\d+)+)(?!\\d)")
                .find(s)?.groupValues?.get(1)?.let { return it }
            GROUPED_WHOLE_INR.find(s)?.groupValues?.get(1)?.let { return it }
            return Regex("(?<![\\d,.])(\\d{5,8})(?!\\d)").find(s)?.groupValues?.get(1)
        }
        fun isLimitHeaderLine(low: String): Boolean =
            low.contains("credit limit") || low.contains("available credit") ||
                low.contains("cash limit") || low.contains("available cash") ||
                low.contains("spend limit")

        for (i in 0 until illustrationIdx) {
            val l = lines[i]
            val low = l.lowercase()
            val kwMatch = creditLimitKwRx.find(low) ?: continue
            if (low.substring(0, kwMatch.range.first).trimEnd().endsWith("available")) continue
            val after = l.substring(kwMatch.range.last + 1)
                .replace(Regex("^(?:\\s*\\([^)]*\\)|\\s*[:\\-])+", RegexOption.IGNORE_CASE), "")
            val cut = listOf("available credit", "available cash", "cash limit", "total amount due", "minimum amount due")
                .mapNotNull { after.lowercase().indexOf(it).takeIf { idx -> idx >= 0 } }
                .minOrNull()
            val slice = (cut?.let { after.substring(0, it) } ?: after).trim()
            amounts(slice).firstOrNull()?.let { return paise(it) }
            wholeRupeeInSlice(slice)?.let { return paise(it) }

            for (off in 1..2) {
                if (i + off >= illustrationIdx) continue
                val cand = lines.getOrNull(i + off) ?: continue
                val cl = cand.lowercase()
                if (cl.contains("available") || cl.contains("credit limit") || cl.contains("cash limit")) continue
                amounts(cand).firstOrNull()?.let { return paise(it) }
                wholeRupeeInSlice(cand)?.let { return paise(it) }
            }

            // Multi-column horizontal row or stacked vertical limit block (up to 4 header lines followed by values)
            val headerSkip = (1..4).takeWhile { off ->
                val nl = lines.getOrNull(i + off)?.lowercase() ?: return@takeWhile false
                isLimitHeaderLine(nl) || nl in setOf("(in rs.)", "(in ₹)", "(including cash)")
            }.size
            val valueStart = i + 1 + headerSkip
            for (rowIdx in valueStart until minOf(valueStart + 4, illustrationIdx)) {
                val row = lines[rowIdx]
                val toks = mergeCurrencyPrefixes(row.split(' ').filter { it.isNotBlank() })
                val vals = toks.mapNotNull { MONEY_TOKEN.matchEntire(it)?.groupValues?.get(1) }
                if (vals.size >= 2 && vals.size == toks.size) return paise(vals.first())
                if (rowIdx == valueStart && vals.size == 1 && toks.size == 1 && !low.substring(0, kwMatch.range.first).contains("available")) {
                    val v = paise(vals.first())
                    if (v != null && v >= 100_000L) return v
                }
            }
        }
        return null
    }

    private fun mergeCurrencyPrefixes(toks: List<String>): List<String> {
        val out = mutableListOf<String>()
        var idx = 0
        while (idx < toks.size) {
            val t = toks[idx]
            if ((t == "C" || t == "₹" || t == "`" || t.equals("Rs.", true) || t.equals("Rs", true) || t.equals("INR", true)) && idx + 1 < toks.size) {
                out += "$t${toks[idx + 1]}"
                idx += 2
            } else {
                out += t
                idx += 1
            }
        }
        return out
    }

    /**
     * Summary rows written as an equation:
     * - HDFC style: "opening − credits + debits + charges = total"
     * - ICICI style: "total = opening + purchases + cash − payments"
     * Returns (opening, total) in paise. Skips credit-limit equations (including when "limit" sits on header lines above).
     */
    fun summaryEquation(lines: List<String>): Pair<Long, Long>? {
        for ((i, l) in lines.withIndex()) {
            if (l.count { it == '=' } != 1) continue
            val context = listOfNotNull(lines.getOrNull(i - 2), lines.getOrNull(i - 1), l).joinToString(" ").lowercase()
            if (context.contains("limit") || context.contains("available credit") || context.contains("available cash")) continue
            val left = amounts(l.substringBefore('='))
            val right = amounts(l.substringAfter('='))
            if (left.size >= 3 && right.size == 1) {
                return (paise(left.first()) ?: continue) to (paise(right.first()) ?: continue)
            }
            if (left.size == 1 && right.size >= 3) {
                return (paise(right.first()) ?: continue) to (paise(left.first()) ?: continue)
            }
            // Two-line summary equation: header formula on line i ("Total = Opening + Purchases - Payments"), amounts on line i+1 / i+2.
            val lowL = l.lowercase()
            if (left.isEmpty() && right.isEmpty() && (lowL.contains("total") || lowL.contains("previous") || lowL.contains("opening"))) {
                val rhsBeforeEq = lowL.substringAfter('=').contains('+')
                val valRow = lines.drop(i + 1).take(2).firstOrNull { signedHeaderAmounts(it).size >= 4 }
                if (valRow != null) {
                    val nums = signedHeaderAmounts(valRow).mapNotNull { paise(it) }
                    if (nums.size >= 4) {
                        return if (rhsBeforeEq) (nums[1] to nums[0]) else (nums[0] to nums.last())
                    }
                }
            }
        }
        return null
    }

    fun amounts(s: String): List<String> = AMOUNT.findAll(s).map { it.value }.toList()

    private val SIGNED_HEADER_AMOUNT = Regex(
        "(?:(?<=^|[\\s:(])(-)(?=\\s*(?:[₹$\u20AC\u00A3`\uE000-\uF8FF]|Rs\\.?|INR)|\\d))?(?:($AMOUNT_RX)|(?:[₹$\u20AC\u00A3`\uE000-\uF8FF]|Rs\\.?|INR|(?<![A-Za-z])(?-i:C))\\s*($AMT_ANY_RX)|(\\d{1,3}(?:,\\d{2})*,\\d{3})(?![\\d.]))(?:\\s*(?:\\(\\s*(Cr|CR|cr)\\s*\\)|(Cr|CR|cr)\\b))?",
        RegexOption.IGNORE_CASE,
    )

    /** Header variant that also matches negative/CR amounts, USD/EUR/GBP, currency-prefixed or comma-grouped whole-rupee totals. */
    fun signedHeaderAmounts(s: String): List<String> =
        SIGNED_HEADER_AMOUNT.findAll(s).mapNotNull { m ->
            val num = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            if (num.isEmpty()) return@mapNotNull null
            val isNeg = m.groupValues[1].isNotEmpty() || m.groupValues[5].isNotEmpty() || m.groupValues[6].isNotEmpty()
            if (isNeg) "-$num" else num
        }.toList()
    fun dateTokens(s: String): List<String> = DATE.findAll(s).map { it.value }.toList()

    fun paise(s: String): Long? = s.replace(",", "").toBigDecimalOrNull()?.movePointRight(2)?.toLong()

    private val formats: List<DateTimeFormatter> = listOf(
        "uuuu-M-d", "uuuu/M/d",
        "d/M/uuuu", "d-M/uuuu", "d-M-uuuu", "d.M.uuuu", "d/M/uu", "d-M-uu", "d.M.uu",
        "M/d/uuuu", "M-d-uuuu", "M/d/uu", "M-d-uu",
        "d MMM uuuu", "d-MMM-uuuu", "d/MMM/uuuu", "d.MMM.uuuu", "d MMM uu", "d-MMM-uu", "d/MMM/uu", "d.MMM.uu",
        "d MMMM uuuu", "d-MMMM-uuuu", "d/MMMM/uuuu", "d.MMMM.uuuu", "d MMM, uuuu", "d MMMM, uuuu",
        "MMM d, uuuu", "MMMM d, uuuu", "MMM d uuuu", "MMMM d uuuu", "MMM d, uu", "MMMM d, uu",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    }

    private val yearlessMonthFirst = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    private val yearlessDayFirst = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)

    fun date(raw: String): LocalDate? = date(raw, null)

    fun date(raw: String, fallbackYear: Int?): LocalDate? {
        val cleaned = raw.trim()
            .replace(Regex("(?i)(\\d{1,2})(?:st|nd|rd|th)\\b"), "$1")
            .replace(Regex(",(\\d)"), ", $1")
            .replace(Regex("(?i)\\b([a-z]{3,9})\\.\\s+(?=\\d{1,2}\\b)"), "$1 ")
            .replace(Regex("(?i)\\b([a-z]{3,9})-(\\d{1,2})-(\\d{2,4})\\b"), "$1 $2 $3")
            .replace(Regex("(?i)\\b([a-z]{3,9})\\s*'(\\d{2})\\b"), "$1 20$2")
            .replace(Regex("(\\d{1,2})\\s*'(\\d{2})\\b"), "$1 20$2")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\bsept\\b"), "Sep")
            .trimEnd(',')
        for (f in formats) runCatching { return LocalDate.parse(cleaned, f) }
        val y = fallbackYear ?: return null
        val normalized = cleaned.replace('-', ' ')
        runCatching { return LocalDate.parse("$normalized $y", yearlessDayFirst) }
        runCatching { return LocalDate.parse("$normalized $y", yearlessMonthFirst) }
        Regex("^(\\d{1,2})[/\\-](\\d{1,2})$").matchEntire(cleaned)?.let { m ->
            if (cleaned == "24/7") return null
            val d = m.groupValues[1].toIntOrNull()
            val mon = m.groupValues[2].toIntOrNull()
            if (d != null && mon != null && mon in 1..12 && d in 1..31) runCatching { return LocalDate.of(y, mon, d) }
        }
        return null
    }

    /**
     * All masked card endings found in document order (e.g. "XXXX XXXX XXXX 4321", "4375XXXXXXXX4321",
     * "4375 82XX XXXX 5678", Amex "XXXX-XXXXXX-81001", or "Card No: XX4321").
     */
    fun allLast4s(text: String): List<String> {
        val norm = text.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ")
        val patterns = listOf(
            Regex("(?:[Xx*•]{4}[ -]?){2,3}(\\d{4})\\b"),
            Regex("\\b\\d{4}[ -]?(?:\\d{2}[Xx*•]{2}|[Xx*•]{2,4})[ -]?[Xx*•]{4}[ -]?(\\d{4})\\b"),
            Regex("(?:[Xx*•]{4}|\\d{4})[ -]?[Xx*•]{5,6}[ -]?(\\d{5})\\b"),
            Regex("\\b\\d{4,8}[Xx*•]{4,8}(\\d{4})\\b"),
            Regex("(?:card\\s*(?:no\\.?|number|#)?\\s*[:\\-]?\\s*|ending(?:\\s*with|\\s*in)?\\s*)(?:\\d{4}[ -]?)?(?:[Xx*•]{2,}\\s*[- ]?\\s*)+(\\d{4,5})\\b", RegexOption.IGNORE_CASE),
            Regex("\\bcard\\s+(?:ending(?:\\s*with|\\s*in)?|no\\.?\\s*ending)\\s*[:\\-]?\\s*(\\d{4,5})\\b", RegexOption.IGNORE_CASE),
            Regex("\\b\\d{4}[ -]?[Xx*•]{4,8}[ -]?(\\d{4,5})\\b"),
        )
        return patterns.flatMap { rx ->
            rx.findAll(norm).map { m -> m.range.first to m.groupValues[1].takeLast(4) }.toList()
        }.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun last4(text: String): String? = allLast4s(text).firstOrNull()

    /** Picks a statement date chronologically aligned with [txns] so stale sample/example years (e.g. Oct 2023) lose to real statement dates. */
    internal fun pickChronologicalStmtDate(
        candidates: List<LocalDate>,
        periodEnd: LocalDate?,
        txns: List<ParsedLine>,
    ): LocalDate? {
        val all = (candidates + listOfNotNull(periodEnd)).distinct()
        if (all.isEmpty()) return null
        val maxTxn = txns.maxOfOrNull { it.date } ?: return all.first()
        return all.firstOrNull { !it.isBefore(maxTxn.minusDays(10)) && !it.isAfter(maxTxn.plusDays(45)) }
            ?: all.firstOrNull { abs(java.time.temporal.ChronoUnit.DAYS.between(it, maxTxn)) <= 120 }
            ?: all.firstOrNull()
    }
}

/**
 * Dedicated offline parser for ICICI Bank / Amazon Pay ICICI credit card PDFs.
 * Guardrails specifically prevent Page-1 Credit Limit (e.g. 1,40,000.00), Available Credit, or Cash Limit columns
 * from leaking into Total Amount Due, recover dates split across 2-column header blocks, and validate
 * totals using Summary Breakdown, Equation, Available Credit + Total Due ~ Credit Limit, and Transaction sums.
 */
class IciciParser : LabelParser(
    name = "icici",
    totalLabels = listOf("your total amount due", "current total amount due", "total amount due", "total amount payable", "amount payable"),
    openingLabels = listOf("previous balance", "prev balance", "prev. balance", "opening balance"),
) {
    override fun parse(text: String, received: LocalDate?, pages: List<PageLayout>): ParsedStatement {
        if (pages.isEmpty()) return parse(text, received)
        val spatialBase = super.parse(text, received, pages)
        val lines = text.lines()
            .map { Text.collapseFauxBold(it.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim()) }
            .filter { it.isNotEmpty() }
        val fields = HeaderFields(lines)
        val limit = spatialBase.creditLimitPaise ?: findIciciCreditLimit(lines, fields)
        val avail = findIciciAvailableCredit(lines, fields)
        return StatementReconciler.reconcile(
            spatialBase.copy(creditLimitPaise = limit),
            StatementReconciler.Context(
                limitPaise = limit,
                availableCreditPaise = avail,
                equationTotalPaise = Text.summaryEquation(lines)?.second,
            ),
        )
    }

    override fun parse(text: String, received: LocalDate?): ParsedStatement {
        val base = super.parse(text, received)
        val lines = text.lines().map { it.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }
        val fields = HeaderFields(lines)
        val limit = base.creditLimitPaise ?: findIciciCreditLimit(lines, fields)
        val available = findIciciAvailableCredit(lines, fields)
        val cashLimit = fields.amount(listOf("cash limit (including cash)", "cash limit", "total cash limit"))
        val availCash = fields.amount(listOf("available cash (including cash)", "available cash limit", "available cash"))

        // Recover dates carefully, ignoring stale sample dates when transactions show the true cycle year.
        val periodDates = fields.dates(period, 2, received?.year)
        val rawStmtCandidates = (fields.dateHits(stmtDate) + listOfNotNull(periodDates.getOrNull(1), base.statementDate)).distinct()
        val anchorYear = (rawStmtCandidates.firstOrNull() ?: base.lines.maxOfOrNull { it.date } ?: periodDates.getOrNull(1) ?: base.dueDate ?: received ?: LocalDate.now()).year
        val allStmtCandidates = (rawStmtCandidates + fields.dateHits(stmtDate, anchorYear)).distinct()
        val rawStmt = Text.pickChronologicalStmtDate(allStmtCandidates, periodDates.getOrNull(1), base.lines)
            ?: base.statementDate
        val dueYear = (rawStmt ?: base.lines.maxOfOrNull { it.date } ?: received ?: LocalDate.now()).year
        val explicitDue = fields.dateHits(due)
        val dueCandidates = if (explicitDue.isNotEmpty()) {
            (explicitDue + listOfNotNull(base.dueDate)).distinct()
        } else {
            val baseYearDue = fields.dateHits(due, dueYear)
            val wrapped = if (rawStmt != null && rawStmt.monthValue >= 11) fields.dateHits(due, dueYear + 1) else emptyList()
            (baseYearDue + wrapped + listOfNotNull(base.dueDate)).distinct()
        }
        val rawDue = if (rawStmt != null) {
            dueCandidates.firstOrNull { !it.isBefore(rawStmt) && it <= rawStmt.plusDays(60) } ?: base.dueDate
        } else {
            dueCandidates.firstOrNull() ?: base.dueDate
        }

        // Cross-check Summary breakdown: Previous Balance + Purchases/Charges + Cash Advances - Payments/Credits
        val prevBal = fields.amount(listOf("previous balance", "prev balance", "prev. balance", "opening balance")) ?: base.openingPaise
        val purchases = fields.amount(
            listOf("purchases / other charges", "purchases & other charges", "purchases / charges", "purchases/ charges", "purchases/charges", "purchases & charges", "purchases / debits", "purchases"),
        )?.let { kotlin.math.abs(it) }
        val cashAdv = fields.amount(listOf("cash advances"))?.let { kotlin.math.abs(it) } ?: 0L
        val payments = fields.amount(listOf("payments / credits", "payments/ credits", "payments/credits", "payments / refunds"))?.let { kotlin.math.abs(it) } ?: 0L
        val finCharges = fields.amount(listOf("finance charges"))?.let { kotlin.math.abs(it) } ?: 0L
        val summaryTotal = if (prevBal != null && purchases != null) prevBal + purchases + cashAdv + finCharges - payments else null
        val txnComputed = (prevBal ?: 0L) + base.debits - base.credits

        val totalHitObjs = fields.amountHits(total)
        val equationTotal = Text.summaryEquation(lines)?.second
        fun isCreditLimitFalsePositive(amt: Long): Boolean {
            if (limit != null && limit > 0L && amt == limit) {
                val est = (prevBal ?: 0L) + base.debits - base.credits
                val summaryConfirmsMax = summaryTotal != null && abs(summaryTotal - limit) <= 100L
                val eqConfirmsMax = equationTotal != null && abs(equationTotal - limit) <= 100L
                if (!summaryConfirmsMax && !eqConfirmsMax && (base.debits < limit || est < limit - 100L)) return true
            }
            if (limit != null && available != null && limit > 0L && available in 1 until limit) {
                if (amt == limit && (summaryTotal == null || abs(summaryTotal - limit) > 100L)) return true
                if (amt == available && amt != (limit - available) && base.debits < amt &&
                    (summaryTotal == null || abs(summaryTotal - available) > 100L)
                ) return true
            }
            return false
        }
        fun isCashLimitFalsePositive(amt: Long): Boolean =
            ((cashLimit != null && cashLimit > 0L && amt == cashLimit) ||
                (availCash != null && availCash > 0L && amt == availCash)) &&
                (summaryTotal == null || abs(summaryTotal - amt) > 100L) &&
                (equationTotal == null || abs(equationTotal - amt) > 100L) &&
                (base.lines.isEmpty() || abs(txnComputed - amt) > 100L)

        val candidates = (totalHitObjs.map { it.paise } + listOfNotNull(summaryTotal, equationTotal, if (base.lines.isNotEmpty()) txnComputed else null, base.totalDuePaise)).distinct()
        val scored = candidates.map { cand ->
            var score = 0
            val bestHit = totalHitObjs.filter { it.paise == cand }
                .sortedWith(compareBy<HeaderFields.AmountHit> { it.inIllustration }.thenBy { it.nearLimit }.thenByDescending { it.explicit })
                .firstOrNull()
            if (isCreditLimitFalsePositive(cand)) score -= 30
            if (isCashLimitFalsePositive(cand)) score -= 20
            if (summaryTotal != null && abs(cand - summaryTotal) <= 100L) score += 10
            if (equationTotal != null && abs(cand - equationTotal) <= 100L) score += 10
            if (base.lines.isNotEmpty() && abs(cand - txnComputed) <= 100L) score += 7
            if (limit != null && available != null && available in 1 until limit && abs((limit - available) - cand) <= 100L) score += 5
            if (bestHit != null) {
                score += when {
                    !bestHit.inIllustration && !bestHit.nearLimit && bestHit.explicit -> 6
                    !bestHit.inIllustration && !bestHit.nearLimit -> 4
                    !bestHit.inIllustration -> 1
                    else -> -2
                }
            }
            cand to score
        }
        val resolvedTotal = scored
            .filter { !isCreditLimitFalsePositive(it.first) }
            .maxByOrNull { it.second }
            ?.first
            ?: summaryTotal?.takeUnless { isCreditLimitFalsePositive(it) }
            ?: equationTotal?.takeUnless { isCreditLimitFalsePositive(it) }
            ?: if (base.lines.isNotEmpty() && !isCreditLimitFalsePositive(txnComputed)) txnComputed else base.totalDuePaise?.takeUnless { isCreditLimitFalsePositive(it) }

        val minHits = fields.amountHits(min).filter { it.paise >= 0L }
        val resolvedMin = minHits
            .filterNot { isCreditLimitFalsePositive(it.paise) || isCashLimitFalsePositive(it.paise) || (available != null && available != resolvedTotal && it.paise == available) }
            .sortedWith(
                compareBy<HeaderFields.AmountHit> { it.inIllustration }
                    .thenByDescending { resolvedTotal != null && it.paise <= kotlin.math.max(0L, resolvedTotal) }
                    .thenByDescending { it.explicit },
            )
            .firstOrNull()?.paise
            ?: base.minDuePaise?.takeIf { !isCreditLimitFalsePositive(it) && (available == null || it == resolvedTotal || it != available) && (resolvedTotal == null || it <= kotlin.math.max(0L, resolvedTotal)) }

        return base.copy(
            statementDate = rawStmt,
            dueDate = rawDue,
            totalDuePaise = resolvedTotal,
            minDuePaise = resolvedMin,
            openingPaise = prevBal ?: base.openingPaise,
            creditLimitPaise = limit ?: base.creditLimitPaise,
        )
    }

    private fun findIciciCreditLimit(lines: List<String>, fields: HeaderFields): Long? {
        Text.creditLimit(lines)?.let { return it }
        fields.amountHits(listOf("total credit limit", "credit limit (including cash)", "credit limit"))
            .filter { !it.inIllustration && it.paise >= 100_000L }
            .firstOrNull()?.paise?.let { return it }
        val limitStopIdx = lines.indices.drop(8).firstOrNull { idx ->
            val ll = lines[idx].lowercase()
            ll.contains("illustrative example") || ll.contains("sample calculation") ||
                ll.contains("how minimum amount due is calculated") || ll.contains("key fact statement")
        } ?: lines.size
        for (i in 0 until limitStopIdx) {
            val l = lines[i]
            val low = l.lowercase()
            if ((low.contains("credit limit") && !low.contains("available credit limit")) || low.contains("cr_limit")) {
                val inline = Text.signedHeaderAmounts(l).firstNotNullOfOrNull { Text.paise(it) }
                if (inline != null && inline >= 100_000L) return inline
                for (off in 1..3) {
                    if (i + off >= limitStopIdx) continue
                    val next = lines.getOrNull(i + off) ?: continue
                    val v = Text.signedHeaderAmounts(next).firstNotNullOfOrNull { Text.paise(it) }
                    if (v != null && v >= 100_000L) return v
                }
            }
        }
        return null
    }

    private fun findIciciAvailableCredit(lines: List<String>, fields: HeaderFields): Long? {
        fields.amountHits(listOf("available credit (including cash)", "available credit limit", "available credit"))
            .filter { !it.inIllustration }
            .firstOrNull()?.paise?.let { return it }
        for ((i, l) in lines.withIndex()) {
            val low = l.lowercase()
            if (!low.contains("available credit")) continue
            val after = l.substring(low.indexOf("available credit") + "available credit".length)
            Text.signedHeaderAmounts(after).firstNotNullOfOrNull { Text.paise(it) }?.let { return it }
            val hasCreditLimitBefore = low.indexOf("credit limit").let { idx -> idx in 0 until low.indexOf("available credit") }
            for (off in 1..2) {
                val next = lines.getOrNull(i + off) ?: continue
                if (next.lowercase().contains("cash limit") || next.lowercase().contains("available")) continue
                val amts = Text.signedHeaderAmounts(next).mapNotNull { Text.paise(it) }
                if (amts.isNotEmpty()) {
                    return if (hasCreditLimitBefore && amts.size >= 2) amts[1] else amts.first()
                }
            }
        }
        return null
    }
}

object Parsers {
    private val AXIS_CATEGORY_SUFFIX_RX = Regex(
        "\\s+(DEPT\\s+STORES?|SUPERMARKETS?|E-COMMERCE|ECOMMERCE|RESTAURANTS?|UTILITIES?|APPAREL|CLOTHING|CLOTH\\s+STORES?|MISC\\s+STORES?|RAILWAYS?|AIRLINES?|ELECTRONICS?|ENTERTAINMENT|EDUCATION|TELECOM(?:MUNICATIONS)?|FINANCIAL\\s+SERVICES?|JEWELLERY|GROCERIES|INSURANCE|GOVERNMENT\\s+SERVICES?|FUEL\\s*&\\s*PETROL|HOTELS?\\s*&\\s*MOTELS?|TRANSPORTATION|HOME\\s+FURNISHING|PERSONAL\\s+CARE|BUSINESS\\s+SERVICES?|MISCELLANEOUS|OTHERS)$",
        RegexOption.IGNORE_CASE,
    )

    private val generic = LabelParser("generic")
    private val icici = IciciParser()
    private val hdfc = LabelParser("hdfc", totalLabels = listOf("total dues", "total amount due"), openingLabels = listOf("opening balance", "previous statement dues"))
    private val sbi = LabelParser("sbi", totalLabels = listOf("total amount due", "total outstanding"), openingLabels = listOf("previous balance", "opening balance"))
    private val axis = LabelParser("axis", totalLabels = listOf("total payment due", "total amount due"), minLabels = listOf("minimum payment due", "minimum amount due"), openingLabels = listOf("previous balance", "opening balance"), stripCategorySuffixRx = AXIS_CATEGORY_SUFFIX_RX)
    private val kotak = LabelParser("kotak", totalLabels = listOf("total amount due"), openingLabels = listOf("previous amount due", "previous balance"))
    private val idfc = LabelParser("idfc", totalLabels = listOf("total amount due"), openingLabels = listOf("opening balance", "previous balance"))
    private val amex = LabelParser(
        "amex",
        totalLabels = listOf("closing balance", "total new balance", "new balance"),
        dueLabels = listOf("please pay by", "payment due date", "due date"),
        stmtDateLabels = listOf("statement closing date", "closing date", "statement date"),
        openingLabels = listOf("opening balance", "previous balance"),
        minusMeansCredit = true,
        rewardUnitHint = "Membership Rewards",
    )
    private val au = LabelParser(
        "au",
        exactTotalLabels = listOf("total amount due", "total bill amount", "total payment due", "total dues"),
        minLabels = listOf("minimum amount due", "minimum payment due", "min amount due", "min. amount due", "minimum due"),
        openingLabels = listOf("opening balance", "previous balance", "prev. balance", "previous statement dues"),
    )
    private val yes = LabelParser("yes", totalLabels = listOf("total amount due"), openingLabels = listOf("previous balance", "opening balance"))
    private val indusind = LabelParser("indusind", totalLabels = listOf("total amount due"), openingLabels = listOf("previous balance", "opening balance"))
    private val rbl = LabelParser("rbl", totalLabels = listOf("total amount due"), minLabels = listOf("minimum payment due", "minimum amount due"), openingLabels = listOf("opening balance", "previous balance"))
    private val federal = LabelParser("federal", totalLabels = listOf("total amount due"), openingLabels = listOf("opening balance", "previous balance"))
    private val csb = LabelParser("csb", totalLabels = listOf("total amount due", "statement balance"), openingLabels = listOf("opening balance", "carry forward"), rewardUnitHint = "Jewels")
    private val hsbc = LabelParser("hsbc", totalLabels = listOf("new balance", "total payment due", "total amount due"), minLabels = listOf("minimum payment due", "minimum amount due"), openingLabels = listOf("previous balance", "opening balance"), minusMeansCredit = true)
    private val sc = LabelParser("sc", totalLabels = listOf("total payment due", "total amount due", "closing balance"), minLabels = listOf("minimum payment due", "minimum amount due"), openingLabels = listOf("previous balance", "opening balance"), minusMeansCredit = true)
    private val bob = LabelParser("bob", totalLabels = listOf("total amount due", "total payment due"), openingLabels = listOf("opening balance", "previous balance"))
    private val onecard = LabelParser("onecard", totalLabels = listOf("total amount due"), openingLabels = listOf("opening balance"), minusMeansCredit = true)
    private val slice = SliceParser()
    private val unity = UnityParser()

    private val byIssuer: Map<String, StatementParser> = mapOf(
        "HDFC Bank" to hdfc,
        "SBI Card" to sbi,
        "State Bank of India" to sbi,
        "ICICI Bank" to icici,
        "Axis Bank" to axis,
        "Yes Bank" to yes,
        "Kotak" to kotak,
        "IDFC FIRST" to idfc,
        "Amex" to amex,
        "AU Bank" to au,
        "Paytm" to au,
        "Paytm AU Bank" to au,
        "CSB Bank" to csb,
        "CSB Bank (Jupiter Edge+)" to csb,
        "IndusInd" to indusind,
        "RBL Bank" to rbl,
        "Federal Bank" to federal,
        "Federal (Scapia)" to federal,
        "HSBC" to hsbc,
        "Standard Chartered" to sc,
        "BOB Card" to bob,
        "Bank of Baroda" to bob,
        "OneCard" to onecard,
        "OneCard (FPL)" to onecard,
        "Slice" to slice,
        "Unity Bank (Roar)" to unity,
    )

    fun forIssuer(issuer: String): StatementParser = byIssuer[issuer] ?: generic

    /** Resolves exact issuer parser, or auto-detects from raw PDF text across all major Indian issuers. */
    fun resolve(issuer: String, text: String): StatementParser {
        byIssuer[issuer]?.let { return it }
        val low = text.lowercase()
        return when {
            low.contains("icici bank") || low.contains("amazon pay icici") || low.contains("icicibank") ||
                (low.contains("icici") && (low.contains("credit card") || low.contains("imobile"))) -> icici
            low.contains("hdfc bank") || low.contains("hdfcbank") || low.contains("neucoins") -> hdfc
            low.contains("sbi card") || low.contains("sbicard") -> sbi
            low.contains("axis bank") || low.contains("axisbank") -> axis
            low.contains("kotak mahindra") || (low.contains("kotak") && low.contains("card")) -> kotak
            low.contains("idfc first") || low.contains("idfcbank") -> idfc
            low.contains("american express") || (low.contains("amex") && low.contains("membership rewards")) -> amex
            low.contains("yes bank") || low.contains("yesbank") -> yes
            low.contains("indusind") -> indusind
            low.contains("rbl bank") || low.contains("rblbank") -> rbl
            low.contains("au small finance") || low.contains("au bank") || low.contains("aubank") ||
                low.contains("ixigo au") || low.contains("paytm au") || low.contains("au.bank.in") || low.contains("au 0101") -> au
            low.contains("standard chartered") -> sc
            low.contains("hsbc") -> hsbc
            low.contains("bobcard") || low.contains("bob financial") || low.contains("bank of baroda") -> bob
            low.contains("onecard") || low.contains("one card") || low.contains("fpl technologies") -> onecard
            low.contains("jupiter") || low.contains("csb bank") -> csb
            low.contains("federal bank") || low.contains("scapia") -> federal
            low.contains("slice") && (low.contains("monies") || low.contains("slice credit card") || (low.contains("statement summary") && low.contains("spends"))) -> slice
            low.contains("roarbank") || low.contains("unity small finance") -> unity
            else -> generic
        }
    }

    /**
     * Most Indian issuers do not print an opening balance on every statement layout. When opening balance
     * IS present, opening + debits - credits must match total due (₹1 rounding slack); when omitted, the
     * statement is still accepted as valid once total due, due date, and transaction rows are present.
     */
    fun validate(p: ParsedStatement): Validation {
        if (p.totalDuePaise == null) return Validation(false, "Couldn't find the total amount due")
        if (p.dueDate == null) return Validation(false, "Couldn't find the payment due date")
        if (p.lines.isEmpty() && p.totalDuePaise != 0L && p.totalDuePaise != p.openingPaise) {
            return Validation(false, "No transaction lines found")
        }
        val opening = p.openingPaise ?: return Validation(true, "")
        val computed = opening + p.debits - p.credits
        val diff = computed - p.totalDuePaise
        return if (abs(diff) <= 100) Validation(true, "")
        else Validation(false, "Lines don't add up: opening + debits − credits is off by ${com.devanandsb.ccmanager.domain.Money.inr(abs(diff), true)}")
    }

    fun validate(totalDue: Long?, dueKnown: Boolean, opening: Long?, debits: Long, credits: Long): Validation =
        when {
            totalDue == null -> Validation(false, "Total amount due is missing")
            !dueKnown -> Validation(false, "Payment due date is missing")
            opening == null -> Validation(true, "")
            abs(opening + debits - credits - totalDue) <= 100 -> Validation(true, "")
            else -> Validation(false, "Lines don't add up: off by ${com.devanandsb.ccmanager.domain.Money.inr(abs(opening + debits - credits - totalDue), true)}")
        }

    /**
     * Cross-checks a PDF [ParsedStatement] against the statement email's HTML/plain summary table.
     * When the PDF parser hit a table-layout mismatch (for example grabbing a limit/available number
     * instead of the real Total Amount Due), or missed Min Due / Due Date, the email summary heals it.
     */
    fun mergeWithEmailSummary(
        parsed: ParsedStatement,
        email: EmailSummary?,
        rawPdfText: String,
    ): ParsedStatement {
        if (email == null) return parsed
        val pdfValidation = validate(parsed)
        val eTotal = email.totalDuePaise
        val eMin = email.minDuePaise
        val eDue = email.dueDate

        val pdfTotal = parsed.totalDuePaise
        val pdfContainsEmailTotal = eTotal != null && containsRupeeAmount(rawPdfText, eTotal)
        val pdfTotalLooksSuspect = pdfTotal == null ||
            (parsed.creditLimitPaise != null && pdfTotal == parsed.creditLimitPaise && parsed.debits < pdfTotal) ||
            (!pdfValidation.ok && eTotal != null) ||
            (eTotal != null && pdfTotal != eTotal && pdfContainsEmailTotal)

        val resolvedTotal = if (eTotal != null && pdfTotalLooksSuspect) eTotal else pdfTotal
        val pMin = parsed.minDuePaise
        val resolvedMin = when {
            eMin == null -> pMin
            pMin == null -> eMin
            resolvedTotal != null && pMin > resolvedTotal -> eMin
            eTotal != null && resolvedTotal == eTotal && pMin != eMin -> eMin
            else -> pMin
        }
        val pDue = parsed.dueDate
        val resolvedDue = when {
            eDue == null -> pDue
            pDue == null -> eDue
            parsed.statementDate != null && pDue.isBefore(parsed.statementDate) && !eDue.isBefore(parsed.statementDate) -> eDue
            else -> pDue
        }
        return parsed.copy(
            totalDuePaise = resolvedTotal,
            minDuePaise = resolvedMin,
            dueDate = resolvedDue,
            last4 = parsed.last4 ?: email.last4,
        )
    }

    private fun containsRupeeAmount(text: String, paise: Long): Boolean {
        val whole = paise / 100L
        val frac = abs(paise % 100L)
        val plain = if (frac == 0L) whole.toString() else "$whole.${frac.toString().padStart(2, '0')}"
        val cleanText = text.replace(",", "")
        return Regex("(?<!\\d)${Regex.escape(plain)}(?!\\d)").containsMatchIn(cleanText)
    }
}

/** Quick statement summary extracted from the statement email body HTML/text. */
data class EmailSummary(
    val totalDuePaise: Long? = null,
    val minDuePaise: Long? = null,
    val dueDate: LocalDate? = null,
    val last4: String? = null,
)

object EmailSummaryParser {
    private val emailParser = LabelParser(
        name = "email_summary",
        totalLabels = listOf(
            "total amount due (₹)", "total amount due (rs.)", "total amount due (rs)", "total amount due",
            "total payment due", "total dues", "amount due",
        ),
        minLabels = listOf(
            "minimum amount due (₹)", "minimum amount due (rs.)", "minimum amount due (rs)", "minimum amount due",
            "min. amount due", "min amount due", "min due", "minimum due",
        ),
        dueLabels = listOf("payment due date", "due date", "pay by"),
    )

    fun parse(bodyText: String, received: LocalDate? = null): EmailSummary? {
        if (bodyText.isBlank()) return null
        val cleaned = bodyText
            .replace(Regex("\\(\\s*[₹rRsS.]+\\s*\\)"), " ")
            .trim()
        val p = emailParser.parse(cleaned, received)
        val last4 = p.last4 ?: Text.last4(cleaned)
        if (p.totalDuePaise == null && p.minDuePaise == null && p.dueDate == null && last4 == null) return null
        return EmailSummary(
            totalDuePaise = p.totalDuePaise,
            minDuePaise = p.minDuePaise,
            dueDate = p.dueDate,
            last4 = last4,
        )
    }
}
