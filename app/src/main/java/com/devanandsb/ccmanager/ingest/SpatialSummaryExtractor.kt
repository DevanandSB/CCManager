package com.devanandsb.ccmanager.ingest

import com.devanandsb.ccmanager.statementparser.domain.model.CvTableGrid
import com.devanandsb.ccmanager.statementparser.domain.model.RegionBox
import java.time.LocalDate
import kotlin.math.abs

/** A single positioned text cell/chunk extracted from a PDF page (coordinates in PDF points, top-left origin). */
data class TextSpan(
    val page: Int,
    val x: Float,
    val endX: Float,
    val y: Float,
    val text: String,
    val boxId: Int? = null,
)

/** All positioned text spans and CV/vector layout regions for a single PDF page. */
data class PageLayout(
    val page: Int,
    val width: Float,
    val height: Float,
    val spans: List<TextSpan>,
    val boxes: List<RegionBox> = emptyList(),
    val tables: List<CvTableGrid> = emptyList(),
)

/** Raw positioned glyph used by both Android PdfBox and JVM PdfBox to build clean [TextSpan] cells. */
data class GlyphBox(
    val x: Float,
    val endX: Float,
    val y: Float,
    val spaceWidth: Float,
    val text: String,
)

object PageLayoutBuilder {
    fun buildSpans(pageNo: Int, glyphs: List<GlyphBox>): List<TextSpan> {
        if (glyphs.isEmpty()) return emptyList()
        val sorted = glyphs.sortedWith(compareBy<GlyphBox> { it.y }.thenBy { it.x })
        val rows = mutableListOf<MutableList<GlyphBox>>()
        for (g in sorted) {
            val lastRow = rows.lastOrNull()
            if (lastRow != null && abs(lastRow[0].y - g.y) <= 2.0f) {
                lastRow.add(g)
            } else {
                rows.add(mutableListOf(g))
            }
        }
        val spans = mutableListOf<TextSpan>()
        for (row in rows) {
            row.sortBy { it.x }
            // Collapse faux-bold duplicate glyphs drawn at nearly identical (x, y)
            val deduped = mutableListOf<GlyphBox>()
            for (g in row) {
                val prev = deduped.lastOrNull()
                if (prev != null && g.text == prev.text && abs(g.x - prev.x) <= 1.5f && abs(g.y - prev.y) <= 1.5f) {
                    continue
                }
                deduped.add(g)
            }
            if (deduped.isEmpty()) continue
            val rowY = deduped.map { it.y }.average().toFloat()
            val segments = mutableListOf<MutableList<GlyphBox>>()
            for (g in deduped) {
                val prevSeg = segments.lastOrNull()
                if (prevSeg == null) {
                    segments.add(mutableListOf(g))
                } else {
                    val prev = prevSeg.last()
                    val gap = g.x - prev.endX
                    val gapLimit = maxOf(prev.spaceWidth * 1.25f, 4.8f)
                    if (gap <= gapLimit) {
                        prevSeg.add(g)
                    } else {
                        segments.add(mutableListOf(g))
                    }
                }
            }
            for (seg in segments) {
                val startX = seg.minOf { it.x }
                val endX = seg.maxOf { it.endX }
                val tb = StringBuilder()
                var prevEnd = seg.first().x
                for ((idx, g) in seg.withIndex()) {
                    if (idx > 0) {
                        val gap = g.x - prevEnd
                        val spaceThreshold = maxOf(g.spaceWidth * 0.42f, 1.4f)
                        if (gap > spaceThreshold && !tb.endsWith(' ') && !g.text.startsWith(' ')) {
                            tb.append(' ')
                        }
                    }
                    tb.append(g.text)
                    prevEnd = g.endX
                }
                val clean = Text.collapseFauxBold(
                    tb.toString().replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim(),
                )
                if (clean.isNotEmpty()) {
                    spans += TextSpan(pageNo, startX, endX, rowY, clean)
                }
            }
        }
        return spans
    }
}

data class SpatialSummary(
    val statementDate: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val totalDuePaise: Long? = null,
    val labeledTotalCandidates: List<Long> = emptyList(),
    val minDuePaise: Long? = null,
    val labeledMinCandidates: List<Long> = emptyList(),
    val openingPaise: Long? = null,
    val purchasesPaise: Long? = null,
    val cashAdvancesPaise: Long? = null,
    val chargesPaise: Long? = null,
    val paymentsPaise: Long? = null,
    val creditLimitPaise: Long? = null,
    val availableCreditPaise: Long? = null,
    val cashLimitPaise: Long? = null,
    val availableCashPaise: Long? = null,
    val page1NumericCandidates: List<Long> = emptyList(),
    val pointsEarned: Long? = null,
    val pointsBalance: Long? = null,
)

/**
 * 2D spatial-cone and bounded same-row summary extractor.
 * Isolates summary labels (Total Amount Due, Minimum Amount Due, Payment Due Date, Statement Date,
 * Opening Balance, Credit Limit) strictly to their vertical column X-cone or bounded same-row slot,
 * preventing neighboring summary boxes (e.g. ICICI Credit Summary, Yes Bank Cash Limit, AU illustrations)
 * from bleeding into statement due figures.
 */
object SpatialSummaryExtractor {

    private val BLACKLIST_LABELS = listOf(
        "credit limit", "available credit", "cash limit", "available cash",
        "reward points", "points earned", "bonus points", "points redeemed", "points expired",
        "closing reward points", "total reward points", "overlimit", "spend limit",
        "sanctioned credit limit", "sanctioned limit", "maximum credit limit", "card limit",
    )

    private val ILLUSTRATION_MARKERS = listOf(
        "illustrative example", "illustration for calculating", "sample calculation",
        "how minimum amount due is calculated", "minimum amount due calculation",
        "finance charge calculation", "schedule of charges", "key fact statement",
        "most important terms", "understanding your statement", "terms and conditions",
        "terms & conditions", "glossary",
    )

    private val NOISE_TOKENS = setOf(
        "=", "+", "-", "_", ":", "(in ₹)", "(in rs.)", "(in `)", "(including cash)",
        "amount in rs.", "amount in inr", "reversals)", "& other charges :",
        "rs", "rs.", "inr", "₹", "`", "$", "€", "£",
    )

    private val PROSE_MARKERS = listOf(
        "will be charged", "making only minimum", "making only the minimum", "to avoid late",
        "interest will be", "for example", "illustrative", "assuming", "stretching over",
    )

    fun extract(
        pages: List<PageLayout>,
        totalLabels: List<String>,
        minLabels: List<String>,
        dueLabels: List<String>,
        stmtDateLabels: List<String>,
        openingLabels: List<String>,
        received: LocalDate? = null,
    ): SpatialSummary {
        if (pages.isEmpty()) return SpatialSummary()

        val illusPoint = findIllustrationStart(pages)
        fun isBeforeIllustration(s: TextSpan): Boolean =
            illusPoint == null || s.page < illusPoint.first || (s.page == illusPoint.first && s.y < illusPoint.second - 1f)

        val p1 = pages.firstOrNull { it.page == 1 } ?: pages.first()
        val p1Spans = p1.spans.filter(::isBeforeIllustration)
        val p1Rows = fuseWrappedHeaderRows(groupRows(p1Spans))

        // All non-blacklisted numeric amounts on Page 1 (for StatementReconciler fallback on unseen layouts)
        val p1NumericCandidates = extractPage1NumericCandidates(p1Spans, p1Rows)

        // Extract Period first so yearless period dates can anchor dates on Page 1
        val periodLabels = listOf("statement period", "billing period", "billing cycle", "statement for", "for the period", "period from")
        val periodHit = extractDatePair(p1Spans, p1Rows, periodLabels, received?.year)
        val periodStart = periodHit?.first
        val periodEnd = periodHit?.second

        val explicitStmtDates = extractFieldDates(p1Spans, p1Rows, stmtDateLabels, null)
        val explicitYearHint = explicitStmtDates.firstOrNull()?.year ?: periodEnd?.year ?: received?.year
        val fallbackStmtDates = if (explicitStmtDates.isEmpty()) extractFieldDates(p1Spans, p1Rows, stmtDateLabels, explicitYearHint) else emptyList()
        val allStmtDates = (explicitStmtDates + fallbackStmtDates + listOfNotNull(periodEnd)).distinct()
        val resolvedStmtDate = explicitStmtDates.firstOrNull()
            ?: periodEnd
            ?: allStmtDates.firstOrNull()

        val dueYear = (resolvedStmtDate ?: periodEnd ?: received ?: LocalDate.now()).year
        val explicitDueDates = extractFieldDates(p1Spans, p1Rows, dueLabels, null)
        val dueDates = if (explicitDueDates.isNotEmpty()) explicitDueDates else {
            val base = extractFieldDates(p1Spans, p1Rows, dueLabels, dueYear)
            if (resolvedStmtDate != null && resolvedStmtDate.monthValue >= 11) {
                base.map { if (it.isBefore(resolvedStmtDate)) it.plusYears(1) else it }
            } else base
        }
        val resolvedDueDate = if (resolvedStmtDate != null) {
            dueDates.firstOrNull { !it.isBefore(resolvedStmtDate) && it <= resolvedStmtDate.plusDays(60) } ?: dueDates.firstOrNull()
        } else dueDates.firstOrNull()

        // Limits across Page 1 and non-illustration Limit Summary pages (e.g. Slice Page 10)
        val nonIllusSpans = pages.flatMap { it.spans }.filter(::isBeforeIllustration)
        val nonIllusByPage = nonIllusSpans.groupBy { it.page }.values.map { fuseWrappedHeaderRows(groupRows(it)) }
        val limitLabels = listOf(
            "total credit limit", "sanctioned credit limit", "sanctioned limit",
            "maximum credit limit", "total card limit", "assigned credit limit",
            "credit limit (including cash)", "credit limit (inr)", "credit limit (rs.)",
            "credit limit", "cr. limit", "card limit",
        )
        val availCreditLabels = listOf("available credit (including cash)", "available credit limit", "available credit", "available limit")
        val cashLimitLabels = listOf("cash limit (including cash)", "total cash limit", "cash limit")
        val availCashLabels = listOf("available cash (including cash)", "available cash limit", "available cash")

        val creditLimit = nonIllusByPage.firstNotNullOfOrNull { rows ->
            extractFieldAmounts(rows.flatten(), rows, limitLabels, allowLimitLabels = true).firstOrNull { it >= 100_000L }
        }
        val availCredit = extractFieldAmounts(p1Spans, p1Rows, availCreditLabels, allowLimitLabels = true).firstOrNull()
        val cashLimit = extractFieldAmounts(p1Spans, p1Rows, cashLimitLabels, allowLimitLabels = true).firstOrNull()
        val availCash = extractFieldAmounts(p1Spans, p1Rows, availCashLabels, allowLimitLabels = true).firstOrNull()

        // Equation / summary breakdown row on Page 1 (e.g. Axis "Previous Balance - Payments - Credits + Purchase ... =Total Payment Due")
        val eq = extractFormulaRow(p1Rows)

        val openingCands = extractFieldAmounts(p1Spans, p1Rows, openingLabels)
        val purchasesCands = extractFieldAmounts(
            p1Spans, p1Rows,
            listOf(
                "purchases / other charges", "purchases & other charges", "purchases / charges",
                "purchases/ charges", "purchases/charges", "purchases & charges", "purchases / debits",
                "current purchases / cash advance", "current purchases", "total spends", "spends", "purchases",
            ),
        )
        val cashAdvCands = extractFieldAmounts(p1Spans, p1Rows, listOf("cash advances", "cash advance"))
        val chargesCands = extractFieldAmounts(
            p1Spans, p1Rows,
            listOf("other debits & charges", "other debit&charges", "finance charges", "fee & charges", "fees & charges", "interest", "surcharge"),
        )
        val paymentsCands = extractFieldAmounts(
            p1Spans, p1Rows,
            listOf(
                "payments / credits", "payments/ credits", "payments/credits", "payments & credits",
                "payment & credits received", "payments & refunds", "refunds & repayments", "payments", "repayments",
            ),
        )

        val labeledTotals = extractFieldAmounts(p1Spans, p1Rows, totalLabels)
            .ifEmpty { listOfNotNull(eq?.totalPaise) }
            .distinct()
        val labeledMins = extractFieldAmounts(p1Spans, p1Rows, minLabels)
            .filter { it >= 0L }
            .distinct()

        // Rewards on non-illustration pages (e.g. AU Page 3, ICICI Page 1 EARNINGS, Slice Page 9 monies)
        val (ptsEarned, ptsBalance) = extractRewardsSpatial(nonIllusByPage)

        return SpatialSummary(
            statementDate = resolvedStmtDate,
            periodStart = periodStart,
            periodEnd = periodEnd,
            dueDate = resolvedDueDate,
            totalDuePaise = labeledTotals.firstOrNull(),
            labeledTotalCandidates = labeledTotals,
            minDuePaise = labeledMins.firstOrNull(),
            labeledMinCandidates = labeledMins,
            openingPaise = openingCands.firstOrNull() ?: eq?.openingPaise,
            purchasesPaise = purchasesCands.firstOrNull()?.let(::abs) ?: eq?.purchasesPaise,
            cashAdvancesPaise = cashAdvCands.firstOrNull()?.let(::abs) ?: eq?.cashAdvPaise,
            chargesPaise = chargesCands.firstOrNull()?.let(::abs) ?: eq?.chargesPaise,
            paymentsPaise = paymentsCands.firstOrNull()?.let(::abs) ?: eq?.paymentsPaise,
            creditLimitPaise = creditLimit,
            availableCreditPaise = availCredit,
            cashLimitPaise = cashLimit,
            availableCashPaise = availCash,
            page1NumericCandidates = p1NumericCandidates,
            pointsEarned = ptsEarned,
            pointsBalance = ptsBalance,
        )
    }

    private data class FormulaSummary(
        val openingPaise: Long?,
        val totalPaise: Long?,
        val purchasesPaise: Long?,
        val cashAdvPaise: Long?,
        val chargesPaise: Long?,
        val paymentsPaise: Long?,
    )

    private fun extractFormulaRow(rows: List<List<TextSpan>>): FormulaSummary? {
        for ((idx, row) in rows.withIndex()) {
            val joined = row.joinToString(" ") { it.text }.lowercase()
            if (!joined.contains("=")) continue
            if (joined.contains("limit")) continue
            val isAxisFormula = joined.contains("previous balance") && joined.contains("payments") && joined.contains("credits") && joined.contains("purchase")
            val isIciciFormula = joined.contains("total amount") && joined.contains("previous balance")
            if (!isAxisFormula && !isIciciFormula) continue

            // Values sit on the next 1..2 rows
            val valRow = (1..2).firstNotNullOfOrNull { off ->
                val r = rows.getOrNull(idx + off) ?: return@firstNotNullOfOrNull null
                val amts = r.flatMap { Text.signedHeaderAmounts(it.text) }.mapNotNull { Text.paise(it) }
                amts.takeIf { it.size >= 4 }
            } ?: continue

            if (isAxisFormula && valRow.size >= 7) {
                // Previous Balance - Payments - Credits + Purchase + Cash Advance + Other Debit&Charges =Total Payment Due
                return FormulaSummary(
                    openingPaise = valRow[0],
                    paymentsPaise = abs(valRow[1]) + abs(valRow[2]),
                    purchasesPaise = abs(valRow[3]),
                    cashAdvPaise = abs(valRow[4]),
                    chargesPaise = abs(valRow[5]),
                    totalPaise = valRow[6],
                )
            }
            if (isIciciFormula && valRow.size >= 5) {
                return FormulaSummary(
                    openingPaise = valRow[1],
                    purchasesPaise = abs(valRow[2]),
                    cashAdvPaise = abs(valRow[3]),
                    chargesPaise = 0L,
                    paymentsPaise = abs(valRow[4]),
                    totalPaise = valRow[0],
                )
            }
        }
        return null
    }

    private fun findIllustrationStart(pages: List<PageLayout>): Pair<Int, Float>? {
        for (page in pages.sortedBy { it.page }) {
            for (s in page.spans) {
                val low = s.text.lowercase()
                if (ILLUSTRATION_MARKERS.any { low.contains(it) }) {
                    return page.page to s.y
                }
                if (page.page >= 2 && (low.startsWith("important information") || low.startsWith("important safety"))) {
                    return page.page to s.y
                }
            }
        }
        return null
    }

    private fun groupRows(spans: List<TextSpan>, yTol: Float = 4.2f): List<List<TextSpan>> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedWith(compareBy<TextSpan> { it.y }.thenBy { it.x })
        val rows = mutableListOf<MutableList<TextSpan>>()
        for (s in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last[0].y - s.y) <= yTol) {
                last.add(s)
            } else {
                rows.add(mutableListOf(s))
            }
        }
        rows.forEach { r -> r.sortBy { it.x } }
        return rows
    }

    /**
     * Fuses vertically aligned wrapped summary labels such as:
     *   Row i:   "Total"       | "Minimum"    | "Payment"  | "Total Credit"
     *   Row i+1: "Amount Due"  | "Amount Due" | "Due Date" | "Limit"
     */
    private fun fuseWrappedHeaderRows(rows: List<List<TextSpan>>): List<List<TextSpan>> {
        if (rows.size < 2) return rows
        val fused = rows.map { it.toMutableList() }.toMutableList()

        for (i in 0 until rows.lastIndex) {
            val topRow = rows[i]
            val bottomRow = rows[i + 1]
            val topFirst = topRow.firstOrNull() ?: continue
            val botFirst = bottomRow.firstOrNull() ?: continue
            if (topFirst.page != botFirst.page) continue
            val yGap = botFirst.y - topFirst.y
            if (yGap !in 1f..20f) continue

            for (topSpan in topRow) {
                val topLow = topSpan.text.trim().lowercase()
                if (topLow.length > 36 || topLow.contains(":") ||
                    Text.signedHeaderAmounts(topSpan.text).isNotEmpty() ||
                    Text.dateTokens(topSpan.text).isNotEmpty() ||
                    smallWholeAmount(topSpan.text) != null
                ) continue

                val topCenter = (topSpan.x + topSpan.endX) / 2f
                val alignedBottom = bottomRow.firstOrNull { b ->
                    val bLow = b.text.trim().lowercase()
                    val overlap = minOf(topSpan.endX, b.endX) - maxOf(topSpan.x, b.x)
                    val bCenter = (b.x + b.endX) / 2f
                    val centerDiff = abs(topCenter - bCenter)
                    (overlap >= -8f || centerDiff <= 34f) &&
                        bLow.length <= 36 &&
                        !bLow.contains(":") &&
                        Text.signedHeaderAmounts(b.text).isEmpty() &&
                        Text.dateTokens(b.text).isEmpty() &&
                        smallWholeAmount(b.text) == null
                } ?: continue

                val combinedText = "${topSpan.text.trim()} ${alignedBottom.text.trim()}"
                    .replace(Regex("\\s+"), " ").trim()
                val combinedLow = combinedText.lowercase()
                val isKnownCombined = ALL_KNOWN_LABELS.any { combinedLow.contains(it) }
                val bLow = alignedBottom.text.trim().lowercase()
                if (isKnownCombined && ALL_KNOWN_LABELS.none { topLow == it || bLow == it }) {
                    val minX = minOf(topSpan.x, alignedBottom.x)
                    val maxX = maxOf(topSpan.endX, alignedBottom.endX)
                    fused[i + 1].removeAll { it === alignedBottom }
                    fused[i + 1].add(
                        TextSpan(
                            page = alignedBottom.page,
                            x = minX,
                            endX = maxX,
                            y = alignedBottom.y,
                            text = combinedText,
                        ),
                    )
                    fused[i + 1].sortBy { it.x }
                }
            }
        }
        return fused
    }

    private fun isBlacklistedText(low: String): Boolean =
        BLACKLIST_LABELS.any { low.contains(it) }

    private fun isProse(low: String): Boolean =
        low.length > 78 || PROSE_MARKERS.any { low.contains(it) }

    private fun isNoiseSpan(low: String): Boolean =
        low in NOISE_TOKENS ||
            low.startsWith("(") && low.endsWith(")") ||
            low == "& other charges :" ||
            low == "reversals)"

    private fun extractFieldAmounts(
        spans: List<TextSpan>,
        rows: List<List<TextSpan>>,
        labels: List<String>,
        allowLimitLabels: Boolean = false,
    ): List<Long> {
        val hits = findSpatialMatches(spans, rows, labels, allowLimitLabels) { text ->
            val amts = Text.signedHeaderAmounts(text)
            if (amts.isNotEmpty()) amts.firstNotNullOfOrNull { Text.paise(it) }
            else smallWholeAmount(text)?.let { Text.paise(it) }
        }
        return hits.distinct()
    }

    private fun extractFieldDates(
        spans: List<TextSpan>,
        rows: List<List<TextSpan>>,
        labels: List<String>,
        fallbackYear: Int?,
    ): List<LocalDate> {
        return findSpatialMatches(spans, rows, labels, allowLimitLabels = false) { text ->
            val tok = Text.dateTokens(text).firstOrNull() ?: return@findSpatialMatches null
            Text.date(tok, fallbackYear)
        }.distinct()
    }

    private fun extractDatePair(
        spans: List<TextSpan>,
        rows: List<List<TextSpan>>,
        labels: List<String>,
        defaultYear: Int?,
    ): Pair<LocalDate, LocalDate>? {
        val hits = findSpatialMatches(spans, rows, labels, allowLimitLabels = false) { text ->
            val toks = Text.dateTokens(text)
            if (toks.size < 2) return@findSpatialMatches null
            val explicit = toks.mapNotNull { Text.date(it) }
            val y = explicit.lastOrNull()?.year ?: defaultYear ?: return@findSpatialMatches null
            val d1 = Text.date(toks[0], y) ?: return@findSpatialMatches null
            val d2 = Text.date(toks[1], y) ?: return@findSpatialMatches null
            val fixedD1 = if (d1.isAfter(d2) && Text.date(toks[0]) == null) d1.minusYears(1) else d1
            val fixedD2 = if (fixedD1.isAfter(d2) && Text.date(toks[1]) == null) d2.plusYears(1) else d2
            fixedD1 to fixedD2
        }
        return hits.firstOrNull()
    }

    private fun <T> findSpatialMatches(
        spans: List<TextSpan>,
        rows: List<List<TextSpan>>,
        labels: List<String>,
        allowLimitLabels: Boolean,
        parseValue: (String) -> T?,
    ): List<T> {
        val out = mutableListOf<T>()
        val sortedLabels = labels.sortedByDescending { it.length }

        for ((rowIdx, row) in rows.withIndex()) {
            val rowLabelsCount = row.count { b ->
                val l = b.text.lowercase()
                ALL_KNOWN_LABELS.any { l.contains(it) }
            }
            for ((colIdx, span) in row.withIndex()) {
                val low = span.text.lowercase()
                if (isProse(low)) continue
                if (!allowLimitLabels && isBlacklistedText(low)) continue

                val matchedLabel = sortedLabels.firstOrNull { lab ->
                    val idx = low.indexOf(lab)
                    if (idx < 0) return@firstOrNull false
                    // Avoid matching "Credit Limit" when looking for "Total Credit", or matching "Minimum Amount Due" inside "Total Amount Due"
                    if (!allowLimitLabels && lab == "credit limit" && low.contains("available credit limit")) return@firstOrNull false
                    // Ensure no longer blacklisted/different known label covers this exact range
                    val longerKnown = ALL_KNOWN_LABELS.firstOrNull { known ->
                        known.length > lab.length && low.contains(known) &&
                            low.indexOf(known) <= idx && low.indexOf(known) + known.length >= idx + lab.length &&
                            known !in sortedLabels
                    }
                    longerKnown == null
                } ?: continue

                // 1) Inline inside the same span after the label
                val labelPos = low.indexOf(matchedLabel)
                val inlineRest = stripFollowingLabel(span.text.substring(labelPos + matchedLabel.length))
                parseValue(inlineRest)?.let {
                    out += it
                    continue
                }

                // 2) Same-Box Isolation: when label resides inside a CV/vector box, search strictly inside that box first.
                val boxHit = if (span.boxId != null) {
                    if (rowLabelsCount >= 2) {
                        findVerticalConeValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = true, parseValue)
                            ?: findRightSiblingValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = true, parseValue)
                    } else {
                        findRightSiblingValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = true, parseValue)
                            ?: findVerticalConeValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = true, parseValue)
                    }
                } else null
                if (boxHit != null) {
                    out += boxHit
                    continue
                }

                // 3) Spatial fallback (preventing right-sibling bleed into a different non-null boxId).
                if (rowLabelsCount >= 2) {
                    findVerticalConeValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = false, parseValue)?.let {
                        out += it
                        continue
                    }
                    findRightSiblingValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = false, parseValue)?.let {
                        out += it
                        continue
                    }
                } else {
                    findRightSiblingValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = false, parseValue)?.let {
                        out += it
                        continue
                    }
                    findVerticalConeValue(rows, rowIdx, row, colIdx, span, allowLimitLabels, requireSameBox = false, parseValue)?.let {
                        out += it
                        continue
                    }
                }
            }
        }
        return out
    }

    private fun <T> findRightSiblingValue(
        rows: List<List<TextSpan>>,
        rowIdx: Int,
        row: List<TextSpan>,
        colIdx: Int,
        span: TextSpan,
        allowLimitLabels: Boolean,
        requireSameBox: Boolean = false,
        parseValue: (String) -> T?,
    ): T? {
        val nextRightLabel = row.drop(colIdx + 1).firstOrNull { rightSpan ->
            val rl = rightSpan.text.lowercase()
            ALL_KNOWN_LABELS.any { rl.contains(it) } || rl.contains("limit") || rl.contains("summary")
        }
        val rightBoundX = nextRightLabel?.x ?: (span.endX + 480f)
        val rightCandidates = row.drop(colIdx + 1).filter { it.x < rightBoundX - 1f && (it.x - span.endX) <= 480f }
        for (rc in rightCandidates) {
            if (span.boxId != null) {
                if (requireSameBox && rc.boxId != span.boxId) continue
                if (!requireSameBox && rc.boxId != null && rc.boxId != span.boxId) continue
            }
            val rcl = rc.text.lowercase()
            if (!allowLimitLabels && isBlacklistedText(rcl)) break
            if (isNoiseSpan(rcl)) continue
            if (hasDifferentHeaderAbove(rows, rowIdx, rc, span)) continue
            val parsed = parseValue(rc.text)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun <T> findVerticalConeValue(
        rows: List<List<TextSpan>>,
        rowIdx: Int,
        row: List<TextSpan>,
        colIdx: Int,
        span: TextSpan,
        allowLimitLabels: Boolean,
        requireSameBox: Boolean = false,
        parseValue: (String) -> T?,
    ): T? {
        val leftNeighbor = row.getOrNull(colIdx - 1)
        val rightNeighbor = row.getOrNull(colIdx + 1)
        val coneMinX = if (leftNeighbor != null) (leftNeighbor.endX + span.x) / 2f else maxOf(0f, span.x - 40f)
        val coneMaxX = if (rightNeighbor != null) (span.endX + rightNeighbor.x) / 2f else span.endX + 56f

        val maxDy = 56f
        for (rIdx in (rowIdx + 1) until rows.size) {
            val candidateRow = rows[rIdx]
            val rowY = candidateRow.first().y
            if (rowY - span.y > maxDy) break

            val inCone = candidateRow.filter { cs ->
                if (requireSameBox && span.boxId != null && cs.boxId != span.boxId) return@filter false
                val cx = (cs.x + cs.endX) / 2f
                val overlapsSpan = cs.endX >= span.x - 16f && cs.x <= span.endX + 16f
                (cx in coneMinX..coneMaxX) || (overlapsSpan && cs.x >= coneMinX - 4f && cs.endX <= coneMaxX + 4f)
            }
            if (inCone.isEmpty()) continue

            for (cs in inCone) {
                val cl = cs.text.lowercase()
                if (isNoiseSpan(cl)) continue
                if (ALL_KNOWN_LABELS.any { cl.contains(it) } || (!allowLimitLabels && isBlacklistedText(cl))) {
                    return null
                }
                val v = parseValue(cs.text)
                if (v != null) return v
            }
        }
        return null
    }

    private fun hasDifferentHeaderAbove(rows: List<List<TextSpan>>, rowIdx: Int, cell: TextSpan, labelSpan: TextSpan): Boolean {
        val cellCenter = (cell.x + cell.endX) / 2f
        for (i in (rowIdx - 1) downTo maxOf(0, rowIdx - 2)) {
            for (above in rows[i]) {
                if (abs(above.x - labelSpan.x) < 10f) continue
                if (cellCenter in (above.x - 10f)..(above.endX + 10f)) {
                    val al = above.text.lowercase()
                    if (ALL_KNOWN_LABELS.any { al.contains(it) }) return true
                }
            }
        }
        return false
    }

    private fun stripFollowingLabel(rest: String): String {
        val low = rest.lowercase()
        val cut = ALL_KNOWN_LABELS.mapNotNull { lab -> low.indexOf(lab).takeIf { it > 0 } }.minOrNull() ?: return rest
        return rest.substring(0, cut)
    }

    private fun smallWholeAmount(raw: String): String? {
        val m = Regex("^[-+]?\\s*(\\d{1,7})(?:\\s*\\(?\\s*(Cr|CR|cr)\\s*\\)?)?$")
            .matchEntire(raw.trim().removePrefix(":").trim()) ?: return null
        val n = m.groupValues[1]
        return if (m.groupValues[2].isNotEmpty()) "-$n" else n
    }

    private fun extractPage1NumericCandidates(p1Spans: List<TextSpan>, p1Rows: List<List<TextSpan>>): List<Long> {
        val out = mutableListOf<Long>()
        for ((rIdx, row) in p1Rows.withIndex()) {
            val rowText = row.joinToString(" ") { it.text }.lowercase()
            // Stop collecting Page 1 summary candidates once transaction table starts
            if (rowText.contains("date") && (rowText.contains("transaction") || rowText.contains("description") || rowText.contains("particulars"))) {
                break
            }
            if (rowText == "your transactions" || rowText == "statement details" || rowText == "spends") break

            for ((cIdx, span) in row.withIndex()) {
                val sl = span.text.lowercase()
                if (isBlacklistedText(sl) || isProse(sl)) continue
                val leftText = row.getOrNull(cIdx - 1)?.text?.lowercase().orEmpty()
                if (isBlacklistedText(leftText)) continue
                // Check if header directly above this span is blacklisted (e.g. Credit Limit / Cash Limit)
                val headerAbove = (1..2).mapNotNull { off -> rowsAboveHeader(p1Rows, rIdx - off, span) }.firstOrNull()
                if (headerAbove != null && isBlacklistedText(headerAbove.lowercase())) continue

                for (tok in Text.signedHeaderAmounts(span.text)) {
                    Text.paise(tok)?.let { out += it }
                }
            }
        }
        return out.distinct()
    }

    private fun rowsAboveHeader(rows: List<List<TextSpan>>, idx: Int, cell: TextSpan): String? {
        val r = rows.getOrNull(idx) ?: return null
        val cx = (cell.x + cell.endX) / 2f
        return r.firstOrNull { cx in (it.x - 16f)..(it.endX + 16f) }?.text
    }

    private fun extractRewardsSpatial(pageRowsList: List<List<List<TextSpan>>>): Pair<Long?, Long?> {
        var earned: Long? = null
        var balance: Long? = null
        for (rows in pageRowsList) {
            for ((idx, row) in rows.withIndex()) {
                val joined = row.joinToString(" ") { it.text }.lowercase()
                // AU Page 3: "Reward Points you have earned this month" / "Earned + 132" / "Total reward points 83"
                if (joined == "earned" && earned == null) {
                    val num = row.lastOrNull()?.text?.replace("+", "")?.replace(",", "")?.trim()?.toLongOrNull()
                    if (num != null) earned = num
                }
                if (joined.startsWith("earned +") && earned == null) {
                    Regex("\\+?\\s*(\\d[\\d,]*)").find(joined)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.let { earned = it }
                }
                if ((joined.startsWith("total reward points") || joined.startsWith("closing reward points")) && balance == null) {
                    Regex("(\\d[\\d,]*)\\s*$").find(joined)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.let { balance = it }
                }
                // ICICI Page 1 EARNINGS box: "Earned" at Y=681.55 -> "166" below at Y=702.95 inside X cone
                if (row.any { it.text.equals("Earned", ignoreCase = true) } && earned == null) {
                    val eSpan = row.first { it.text.equals("Earned", ignoreCase = true) }
                    for (rOff in 1..3) {
                        val nr = rows.getOrNull(idx + rOff) ?: break
                        val hit = nr.firstOrNull { abs(it.x - eSpan.x) <= 25f && Regex("^\\d[\\d,]*$").matches(it.text.trim()) }
                        if (hit != null) {
                            earned = hit.text.replace(",", "").toLongOrNull()
                            break
                        }
                    }
                }
                // Slice "monies" -> "Earned 5,013"
                if (joined.startsWith("monies") && earned == null) {
                    val nr = rows.getOrNull(idx + 1)
                    if (nr != null && nr.any { it.text.equals("Earned", ignoreCase = true) }) {
                        nr.lastOrNull()?.text?.replace(",", "")?.trim()?.toLongOrNull()?.let { earned = it }
                    }
                }
            }
        }
        return earned to balance
    }

    private val ALL_KNOWN_LABELS = listOf(
        "your total amount due", "current total amount due", "total amount due (tad)", "total amount due", "total bill amount",
        "total dues", "total amt due", "total amt. due", "total due amount", "total payment due",
        "total amount payable", "total payable", "net payable", "payable amount",
        "total outstanding", "current outstanding", "statement balance", "total statement balance",
        "statement total", "closing balance", "total new balance", "current balance", "new balance",
        "total due", "amount due",
        "minimum amount due (mad)", "minimum amount due", "minimum payment due", "min. amount due", "min amount due",
        "min. amt. due", "min amt due", "minimum amount payable", "minimum due",
        "payment due date", "due date", "payment due on", "please pay by", "pay by", "due by", "due on",
        "last date of payment", "payment due by",
        "statement generation date", "date of statement", "bill generation date", "statement closing date",
        "statement generated on", "bill generated on", "statement date", "stmt date", "stmt. date",
        "statement dt", "bill dt", "closing date", "bill date", "billing date", "generated on", "invoice date",
        "statement period", "billing period", "billing cycle", "statement for", "for the period", "period from",
        "opening balance", "previous balance", "prev balance", "prev. balance", "previous statement dues",
        "previous outstanding", "last statement balance", "previous amount due", "opening bal", "carry forward",
        "purchases / other charges", "purchases & other charges", "purchases / charges", "purchases/ charges",
        "purchases/charges", "purchases & charges", "purchases / debits", "current purchases / cash advance",
        "current purchases", "total spends", "spends", "purchases", "cash advances", "cash advance",
        "payments / credits", "payments/ credits", "payments/credits", "payments & credits",
        "payment & credits received", "payments & refunds", "refunds & repayments", "repayments", "payments",
        "other debits & charges", "other debit&charges", "finance charges", "fee & charges", "fees & charges",
        "total credit limit", "sanctioned credit limit", "sanctioned limit", "maximum credit limit",
        "total card limit", "assigned credit limit", "card limit", "total limit",
        "credit limit (including cash)", "available credit (including cash)", "available credit limit",
        "available cash limit", "available cash (including cash)", "cash limit (including cash)", "total cash limit",
        "credit limit", "available credit", "cash limit", "available cash", "available limit",
        "credit summary", "statement summary", "bill summary", "payment summary", "limit details",
    )
}
