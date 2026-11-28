package com.devanandsb.ccmanager.statementparser.data.parser

import com.devanandsb.ccmanager.statementparser.domain.model.StatementSummary
import com.devanandsb.ccmanager.statementparser.domain.model.StatementTransaction
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import com.devanandsb.ccmanager.statementparser.domain.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs

data class MathVerificationOutcome(
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val isMathVerified: Boolean,
    val discrepancyAmount: BigDecimal,
)

/**
 * Extracts statement summary fields across Page 1 and non-illustration Limit Summary pages using
 * multi-line header fusion + 2D spatial-cone isolation (eliminating neighboring box bleed such as
 * ICICI Credit Summary, Yes Bank Cash Limit, and AU Illustration pages), and reconciles summary
 * totals against itemized transactions via the accounting identity:
 *   OpeningBalance - TotalCredits + TotalDebits == TotalAmountDue
 */
class SummaryAndMathVerifier {

    fun extractSummary(
        page1Rows: List<List<TextBlock>>,
        rawText: String,
        allRows: List<List<TextBlock>> = page1Rows,
    ): StatementSummary {
        val nonIllusPage1Rows = trimAtIllustration(page1Rows)
        val nonIllusAllRows = trimAtIllustration(allRows)

        val fusedPage1Rows = fuseWrappedHeaderRows(nonIllusPage1Rows)
        val page1Text = nonIllusPage1Rows.flatten().joinToString("\n") { it.text }
        val searchBlob = if (page1Text.isNotBlank()) page1Text else rawText

        val bankName = detectBankName(rawText)
        val last4 = extractLast4(searchBlob)
        val formula = extractFormulaSummary(nonIllusPage1Rows)

        // Period & dates
        val statementPeriod = findPeriodString(fusedPage1Rows, searchBlob)
        val periodEndDate = extractPeriodEndDate(statementPeriod, rawText)
        val rawStmtDate = findDateNearLabel(fusedPage1Rows, STATEMENT_DATE_LABELS)
            ?: periodEndDate
        val inferredYear = rawStmtDate?.take(4)?.toIntOrNull()
            ?: extractYearHintFromText(rawText)

        val rawDueDate = findDateNearLabel(fusedPage1Rows, PAYMENT_DUE_DATE_LABELS, inferredYear)
        val resolvedDueDate = resolveChronologicalDueDate(rawDueDate, rawStmtDate)
        val resolvedStmtDate = rawStmtDate ?: periodEndDate

        // Limits across all non-illustration pages (handles Slice Page 10 "Limit details")
        val nonIllusByPage = nonIllusAllRows.flatten().groupBy { it.page }.values
            .map { fuseWrappedHeaderRows(groupRowsByY(it)) }

        val creditLimit = nonIllusByPage.firstNotNullOfOrNull { pRows ->
            findAmountCandidatesNearLabel(pRows, CREDIT_LIMIT_LABELS, allowLimitLabels = true)
                .firstOrNull { it >= MIN_SENSIBLE_LIMIT }
        } ?: findRegexAmount(searchBlob, CREDIT_LIMIT_LABELS, allowLimitLabels = true)?.takeIf { it >= MIN_SENSIBLE_LIMIT }

        val availableCredit = nonIllusByPage.firstNotNullOfOrNull { pRows ->
            findAmountCandidatesNearLabel(pRows, AVAILABLE_CREDIT_LABELS, allowLimitLabels = true).firstOrNull()
        }

        val totalDueCandidates = (
            findAmountCandidatesNearLabel(fusedPage1Rows, TOTAL_DUE_LABELS) +
                listOfNotNull(formula?.totalDue)
            ).distinct()
        val totalAmountDue = totalDueCandidates.firstOrNull()
            ?: findRegexAmount(searchBlob, TOTAL_DUE_LABELS)

        val minDueCandidates = findAmountCandidatesNearLabel(fusedPage1Rows, MIN_DUE_LABELS)
            .filter { it >= BigDecimal.ZERO }
            .distinct()
        val minimumAmountDue = minDueCandidates.firstOrNull()
            ?: findRegexAmount(searchBlob, MIN_DUE_LABELS)?.takeIf { it >= BigDecimal.ZERO }

        val openingBalance = formula?.openingBalance
            ?: findAmountCandidatesNearLabel(fusedPage1Rows, OPENING_BALANCE_LABELS).firstOrNull()
            ?: findRegexAmount(searchBlob, OPENING_BALANCE_LABELS)
            ?: if (bankName == "Slice" && searchBlob.contains("statement summary", ignoreCase = true)) {
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            } else null

        val currentPurchases = formula?.purchases
            ?: findAmountCandidatesNearLabel(fusedPage1Rows, PURCHASES_LABELS).firstOrNull()?.abs()
            ?: findRegexAmount(searchBlob, PURCHASES_LABELS)?.abs()

        return StatementSummary(
            totalAmountDue = totalAmountDue,
            minimumAmountDue = minimumAmountDue,
            statementDate = resolvedStmtDate,
            paymentDueDate = resolvedDueDate,
            cardLast4Digits = last4,
            bankName = bankName,
            openingBalance = openingBalance,
            currentPurchases = currentPurchases,
            creditLimit = creditLimit,
            availableCredit = availableCredit,
            statementPeriod = statementPeriod,
        )
    }

    /**
     * Reconciles extracted [summary] against parsed [transactions] and [page1Rows] using the
     * accounting identity (`Opening - Credits + Debits == TotalDue`), healing mis-picked totals
     * or limits on unfamiliar bank layouts, then returns both the reconciled summary and math verification.
     */
    fun reconcileAndVerify(
        summary: StatementSummary,
        transactions: List<StatementTransaction>,
        page1Rows: List<List<TextBlock>> = emptyList(),
    ): Pair<StatementSummary, MathVerificationOutcome> {
        val totalDebits = transactions
            .filter { it.type == TransactionType.DEBIT }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            .setScale(2, RoundingMode.HALF_UP)

        val totalCredits = transactions
            .filter { it.type == TransactionType.CREDIT }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            .setScale(2, RoundingMode.HALF_UP)

        var resolvedSummary = summary
        if (transactions.isNotEmpty() && page1Rows.isNotEmpty()) {
            val fusedPage1 = fuseWrappedHeaderRows(trimAtIllustration(page1Rows))
            val nonBlacklistedCandidates = extractPage1NumericCandidates(fusedPage1)
            val labeledTotals = findAmountCandidatesNearLabel(fusedPage1, TOTAL_DUE_LABELS)
            val labeledMins = findAmountCandidatesNearLabel(fusedPage1, MIN_DUE_LABELS)
            val limit = summary.creditLimit
            val avail = summary.availableCredit

            fun isLimitTrap(amt: BigDecimal): Boolean {
                if (limit != null && limit > BigDecimal.ZERO && amt.compareTo(limit) == 0 && totalDebits < limit) {
                    return true
                }
                if (avail != null && limit != null && avail > BigDecimal.ZERO && avail < limit &&
                    amt.compareTo(avail) == 0 && totalDebits < amt
                ) {
                    return true
                }
                return false
            }

            val expectedWithOpening = summary.openingBalance?.let {
                (it - totalCredits + totalDebits).setScale(2, RoundingMode.HALF_UP)
            }
            val expectedZeroOpening = if (summary.openingBalance == null) {
                (totalDebits - totalCredits).setScale(2, RoundingMode.HALF_UP)
            } else null

            val reconciledTotal = when {
                expectedWithOpening != null &&
                    summary.totalAmountDue != null &&
                    (summary.totalAmountDue - expectedWithOpening).abs() <= TOLERANCE &&
                    !isLimitTrap(summary.totalAmountDue) -> summary.totalAmountDue

                expectedWithOpening != null -> {
                    val match = (labeledTotals + nonBlacklistedCandidates)
                        .distinct()
                        .filterNot(::isLimitTrap)
                        .firstOrNull { (it - expectedWithOpening).abs() <= TOLERANCE }
                    match ?: summary.totalAmountDue?.takeUnless(::isLimitTrap)
                }

                summary.totalAmountDue == null || isLimitTrap(summary.totalAmountDue) -> {
                    val match = expectedZeroOpening?.let { zeroExp ->
                        (labeledTotals + nonBlacklistedCandidates)
                            .distinct()
                            .filterNot(::isLimitTrap)
                            .firstOrNull { (it - zeroExp).abs() <= TOLERANCE }
                    }
                    match ?: labeledTotals.firstOrNull { !isLimitTrap(it) } ?: summary.totalAmountDue
                }

                else -> summary.totalAmountDue
            }

            val resolvedMin = when {
                reconciledTotal == null -> summary.minimumAmountDue
                reconciledTotal <= BigDecimal.ZERO -> {
                    if (labeledMins.any { it.compareTo(BigDecimal.ZERO) == 0 }) BigDecimal.ZERO.setScale(2)
                    else if (summary.minimumAmountDue != null && summary.minimumAmountDue <= BigDecimal.ZERO) summary.minimumAmountDue
                    else BigDecimal.ZERO.setScale(2)
                }
                summary.minimumAmountDue != null &&
                    summary.minimumAmountDue >= BigDecimal.ZERO &&
                    summary.minimumAmountDue <= reconciledTotal &&
                    !isLimitTrap(summary.minimumAmountDue) -> summary.minimumAmountDue
                else -> labeledMins.firstOrNull { it >= BigDecimal.ZERO && it <= reconciledTotal && !isLimitTrap(it) }
                    ?: nonBlacklistedCandidates.firstOrNull {
                        it > BigDecimal.ZERO && it < reconciledTotal && !isLimitTrap(it) &&
                            reconciledTotal.compareTo(it) != 0 &&
                            summary.openingBalance?.compareTo(it) != 0
                    }
            }

            resolvedSummary = summary.copy(
                totalAmountDue = reconciledTotal,
                minimumAmountDue = resolvedMin,
            )
        }

        val outcome = verifyMath(resolvedSummary, transactions)
        return resolvedSummary to outcome
    }

    fun verifyMath(
        summary: StatementSummary,
        transactions: List<StatementTransaction>,
    ): MathVerificationOutcome {
        val totalDebits = transactions
            .filter { it.type == TransactionType.DEBIT }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            .setScale(2, RoundingMode.HALF_UP)

        val totalCredits = transactions
            .filter { it.type == TransactionType.CREDIT }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            .setScale(2, RoundingMode.HALF_UP)

        if (transactions.isEmpty()) {
            return MathVerificationOutcome(
                totalDebits = totalDebits,
                totalCredits = totalCredits,
                isMathVerified = false,
                discrepancyAmount = summary.totalAmountDue?.abs() ?: BigDecimal.ZERO.setScale(2),
            )
        }

        val targetDue = summary.totalAmountDue
        val expectedFromEquation = when {
            summary.openingBalance != null && targetDue != null ->
                (summary.openingBalance - totalCredits + totalDebits).setScale(2, RoundingMode.HALF_UP)
            targetDue != null ->
                (totalDebits - totalCredits).setScale(2, RoundingMode.HALF_UP)
            summary.currentPurchases != null ->
                totalDebits
            else -> null
        }

        val referenceTarget = targetDue ?: summary.currentPurchases
        if (referenceTarget == null || expectedFromEquation == null) {
            return MathVerificationOutcome(
                totalDebits = totalDebits,
                totalCredits = totalCredits,
                isMathVerified = true,
                discrepancyAmount = BigDecimal.ZERO.setScale(2),
            )
        }

        val discrepancy = (referenceTarget - expectedFromEquation).abs().setScale(2, RoundingMode.HALF_UP)
        val verified = discrepancy <= TOLERANCE ||
            (summary.currentPurchases != null && (summary.currentPurchases - totalDebits).abs() <= TOLERANCE)

        return MathVerificationOutcome(
            totalDebits = totalDebits,
            totalCredits = totalCredits,
            isMathVerified = verified,
            discrepancyAmount = discrepancy,
        )
    }

    private data class FormulaSummary(
        val openingBalance: BigDecimal?,
        val purchases: BigDecimal?,
        val totalDue: BigDecimal?,
    )

    private fun extractFormulaSummary(rows: List<List<TextBlock>>): FormulaSummary? {
        for ((idx, row) in rows.withIndex()) {
            val joined = row.joinToString(" ") { it.text }.lowercase()
            if (!joined.contains("=") || joined.contains("limit")) continue
            val isAxisFormula = joined.contains("previous balance") && joined.contains("payments") &&
                joined.contains("credits") && joined.contains("purchase")
            val isIciciFormula = joined.contains("total amount") && joined.contains("previous balance")
            if (!isAxisFormula && !isIciciFormula) continue

            val valRow = (1..2).firstNotNullOfOrNull { off ->
                val r = rows.getOrNull(idx + off) ?: return@firstNotNullOfOrNull null
                val amts = r.sortedBy { it.x }.flatMap { parseAllAmountsInText(it.text) }
                amts.takeIf { it.size >= 4 }
            } ?: continue

            if (isAxisFormula && valRow.size >= 7) {
                // Axis equation: Previous Balance - Payments - Credits + Purchase + Cash Advance + Other Debit&Charges = Total Payment Due
                // Note: "748.00 Dr" is positive opening due, whereas parseAllAmountsInText preserves Dr as positive.
                return FormulaSummary(
                    openingBalance = valRow[0].abs(),
                    purchases = valRow[3].abs(),
                    totalDue = valRow[6],
                )
            }
            if (isIciciFormula && valRow.size >= 5) {
                return FormulaSummary(
                    openingBalance = valRow[1],
                    purchases = valRow[2].abs(),
                    totalDue = valRow[0],
                )
            }
        }
        return null
    }

    private fun trimAtIllustration(rows: List<List<TextBlock>>): List<List<TextBlock>> {
        val stopIdx = rows.indexOfFirst { row ->
            val joined = row.joinToString(" ") { it.text }.lowercase()
            ILLUSTRATION_MARKERS.any { joined.contains(it) } ||
                (row.firstOrNull()?.page ?: 1) >= 2 && (joined.startsWith("important information") || joined.startsWith("important safety"))
        }
        return if (stopIdx >= 0) rows.take(stopIdx) else rows
    }

    /**
     * Synthesizes merged multi-line headers for wrapped labels such as:
     *   Row r:   "Total"       | "Minimum"    | "Payment"  | "Total Credit"
     *   Row r+1: "Amount Due"  | "Amount Due" | "Due Date" | "Limit"
     */
    private fun fuseWrappedHeaderRows(rows: List<List<TextBlock>>): List<List<TextBlock>> {
        if (rows.size < 2) return rows
        val fused = rows.map { it.toMutableList() }.toMutableList()

        for (i in 0 until rows.lastIndex) {
            val topRow = rows[i]
            val bottomRow = rows[i + 1]
            if (topRow.firstOrNull()?.page != bottomRow.firstOrNull()?.page) continue
            val yGap = bottomRow.first().y - topRow.first().y
            if (yGap !in 1..20) continue

            for (topBlock in topRow) {
                val topLow = topBlock.text.trim().lowercase()
                if (topLow.length > 35 || topLow.contains(":") || parseAllAmountsInText(topBlock.text).isNotEmpty()) continue
                if (TransactionRowParser.parseDateIso(topBlock.text) != null) continue

                val alignedBottom = bottomRow.firstOrNull { b ->
                    val bLow = b.text.trim().lowercase()
                    val overlap = minOf(topBlock.xEnd, b.xEnd) - maxOf(topBlock.x, b.x)
                    val centerDiff = abs(topBlock.centerX - b.centerX)
                    (overlap >= -6 || centerDiff <= 34) &&
                        bLow.length <= 35 &&
                        parseAllAmountsInText(b.text).isEmpty() &&
                        TransactionRowParser.parseDateIso(b.text) == null
                } ?: continue

                val combinedText = "${topBlock.text.trim()} ${alignedBottom.text.trim()}"
                    .replace(Regex("\\s+"), " ").trim()
                val combinedLow = combinedText.lowercase()
                val isKnownCombined = ALL_KNOWN_SUMMARY_LABELS.any { combinedLow.contains(it) }
                if (isKnownCombined && ALL_KNOWN_SUMMARY_LABELS.none { topLow == it || alignedBottom.text.trim().lowercase() == it }) {
                    val minX = minOf(topBlock.x, alignedBottom.x)
                    val maxX = maxOf(topBlock.xEnd, alignedBottom.xEnd)
                    fused[i + 1].removeAll { it === alignedBottom }
                    fused[i + 1].add(
                        TextBlock(
                            text = combinedText,
                            x = minX,
                            y = alignedBottom.y,
                            width = (maxX - minX).coerceAtLeast(1),
                            page = alignedBottom.page,
                        ),
                    )
                    fused[i + 1].sortBy { it.x }
                }
            }
        }
        return fused
    }

    private fun groupRowsByY(blocks: List<TextBlock>, yTolerance: Int = 3): List<List<TextBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(compareBy<TextBlock> { it.y }.thenBy { it.x })
        val rows = mutableListOf<MutableList<TextBlock>>()
        for (b in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last[0].y - b.y) <= yTolerance) {
                last += b
            } else {
                rows += mutableListOf(b)
            }
        }
        rows.forEach { it.sortBy { b -> b.x } }
        return rows
    }

    private fun findAmountCandidatesNearLabel(
        rows: List<List<TextBlock>>,
        labels: List<String>,
        allowLimitLabels: Boolean = false,
    ): List<BigDecimal> {
        val sortedLabels = labels.sortedByDescending { it.length }
        val results = mutableListOf<BigDecimal>()

        for ((rowIdx, row) in rows.withIndex()) {
            val rowLabelsCount = row.count { b ->
                val l = b.text.lowercase()
                ALL_KNOWN_SUMMARY_LABELS.any { l.contains(it) }
            }

            for ((colIdx, block) in row.withIndex()) {
                val low = block.text.lowercase()
                if (low.contains("=") || low.length > 82 || PROSE_MARKERS.any { low.contains(it) }) continue
                if (!allowLimitLabels && BLACKLISTED_SUMMARY_LABELS.any { low.contains(it) }) continue

                val matchedLabel = sortedLabels.firstOrNull { lab ->
                    val idx = low.indexOf(lab)
                    if (idx < 0) return@firstOrNull false
                    if (!allowLimitLabels && lab == "credit limit" && low.contains("available credit")) return@firstOrNull false
                    val longerOther = ALL_KNOWN_SUMMARY_LABELS.firstOrNull { known ->
                        known.length > lab.length &&
                            low.contains(known) &&
                            low.indexOf(known) <= idx &&
                            low.indexOf(known) + known.length >= idx + lab.length &&
                            known !in sortedLabels
                    }
                    longerOther == null
                } ?: continue

                // 1. Inline in same block after label (e.g. "Min amount due ₹247.60", "Total Credit Limit: ₹1,05,000.00")
                val afterLabel = stripFollowingLabel(block.text.substring(low.indexOf(matchedLabel) + matchedLabel.length))
                parseAllAmountsInText(afterLabel, allowWholeRupee = true).firstOrNull()?.let {
                    results += it
                    continue
                }

                // 2. Same-box isolation: when label sits inside a CV/vector box, search strictly inside that box first.
                val boxHit = if (block.boxId != null) {
                    if (rowLabelsCount >= 2) {
                        findBelowInVerticalCone(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = true)
                            ?: findRightSiblingAmount(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = true)
                    } else {
                        findRightSiblingAmount(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = true)
                            ?: findBelowInVerticalCone(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = true)
                    }
                } else null
                if (boxHit != null) {
                    results += boxHit
                    continue
                }

                // Multi-header row (e.g. Yes Bank "Minimum Amount Due: | Available Cash Limit: | Rs. 800.00 Cr",
                // or AU "Total bill amount | Minimum amount due") -> check strict vertical cone BEFORE right sibling!
                if (rowLabelsCount >= 2) {
                    findBelowInVerticalCone(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = false)?.let {
                        results += it
                        continue
                    }
                    findRightSiblingAmount(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = false)?.let {
                        results += it
                        continue
                    }
                } else {
                    findRightSiblingAmount(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = false)?.let {
                        results += it
                        continue
                    }
                    findBelowInVerticalCone(rows, rowIdx, row, colIdx, block, allowLimitLabels, requireSameBox = false)?.let {
                        results += it
                        continue
                    }
                }
            }
        }
        return results.distinct()
    }

    private fun findRightSiblingAmount(
        rows: List<List<TextBlock>>,
        rowIdx: Int,
        row: List<TextBlock>,
        colIdx: Int,
        block: TextBlock,
        allowLimitLabels: Boolean,
        requireSameBox: Boolean = false,
    ): BigDecimal? {
        val nextRightLabel = row.drop(colIdx + 1).firstOrNull { sib ->
            val sl = sib.text.lowercase()
            ALL_KNOWN_SUMMARY_LABELS.any { sl.contains(it) } ||
                sl.contains("limit") ||
                sl.contains("summary")
        }
        val rightLimitX = nextRightLabel?.x ?: Int.MAX_VALUE
        val directHit = row.drop(colIdx + 1)
            .filter { it.x < rightLimitX - 1 && it.x - block.xEnd <= 480 }
            .sortedBy { it.x }
            .firstNotNullOfOrNull { sibling ->
                if (block.boxId != null) {
                    if (requireSameBox && sibling.boxId != block.boxId) return@firstNotNullOfOrNull null
                    if (!requireSameBox && sibling.boxId != null && sibling.boxId != block.boxId) return@firstNotNullOfOrNull null
                }
                val sibLow = sibling.text.lowercase()
                if (!allowLimitLabels && BLACKLISTED_SUMMARY_LABELS.any { sibLow.contains(it) }) return@firstNotNullOfOrNull null
                if (hasDifferentHeaderAbove(rows, rowIdx, sibling, block)) return@firstNotNullOfOrNull null
                parseAllAmountsInText(sibling.text, allowWholeRupee = true).firstOrNull()
            }
        if (directHit != null) return directHit

        // Adjacent-row horizontal sibling within 3pt Y tolerance (handles slight baseline skew
        // when another left-hand box pulled the value into an adjacent Y row).
        for (adjIdx in listOf(rowIdx - 1, rowIdx + 1)) {
            val adjRow = rows.getOrNull(adjIdx) ?: continue
            if (adjRow.firstOrNull()?.page != block.page) continue
            val adjNextLabel = adjRow.firstOrNull { sib ->
                sib.x > block.xEnd && (
                    ALL_KNOWN_SUMMARY_LABELS.any { sib.text.lowercase().contains(it) } ||
                        sib.text.lowercase().contains("limit") ||
                        sib.text.lowercase().contains("summary")
                    )
            }
            val adjRightLimit = minOf(rightLimitX, adjNextLabel?.x ?: Int.MAX_VALUE)
            val adjHit = adjRow
                .filter { it.x > block.xEnd && it.x < adjRightLimit - 1 && it.x - block.xEnd <= 480 && abs(it.y - block.y) <= 3 }
                .sortedBy { it.x }
                .firstNotNullOfOrNull { sibling ->
                    if (block.boxId != null) {
                        if (requireSameBox && sibling.boxId != block.boxId) return@firstNotNullOfOrNull null
                        if (!requireSameBox && sibling.boxId != null && sibling.boxId != block.boxId) return@firstNotNullOfOrNull null
                    }
                    val sibLow = sibling.text.lowercase()
                    if (!allowLimitLabels && BLACKLISTED_SUMMARY_LABELS.any { sibLow.contains(it) }) return@firstNotNullOfOrNull null
                    if (hasDifferentHeaderAbove(rows, adjIdx, sibling, block)) return@firstNotNullOfOrNull null
                    parseAllAmountsInText(sibling.text, allowWholeRupee = true).firstOrNull()
                }
            if (adjHit != null) return adjHit
        }
        return null
    }

    private fun findBelowInVerticalCone(
        rows: List<List<TextBlock>>,
        rowIdx: Int,
        row: List<TextBlock>,
        colIdx: Int,
        block: TextBlock,
        allowLimitLabels: Boolean,
        requireSameBox: Boolean = false,
    ): BigDecimal? {
        val leftNeighbor = row.getOrNull(colIdx - 1)
        val rightNeighbor = row.getOrNull(colIdx + 1)
        val coneMinX = if (leftNeighbor != null) (leftNeighbor.xEnd + block.x) / 2 else maxOf(0, block.x - 42)
        val coneMaxX = if (rightNeighbor != null) (block.xEnd + rightNeighbor.x) / 2 else block.xEnd + 55

        for (nextIdx in (rowIdx + 1)..minOf(rowIdx + 3, rows.lastIndex)) {
            val nextRow = rows[nextIdx]
            if (nextRow.isEmpty()) continue
            val yGap = nextRow.first().y - block.y
            if (yGap > 56) break

            val inCone = nextRow.filter { cs ->
                if (block.boxId != null) {
                    if (requireSameBox && cs.boxId != block.boxId) return@filter false
                    if (!requireSameBox && cs.boxId != null && cs.boxId != block.boxId) return@filter false
                }
                val overlapsSpan = cs.xEnd >= block.x - 16 && cs.x <= block.xEnd + 16
                cs.centerX in coneMinX..coneMaxX ||
                    (overlapsSpan && cs.x >= coneMinX - 6 && cs.xEnd <= coneMaxX + 6)
            }.sortedBy { abs(it.centerX - block.centerX) }

            if (inCone.isEmpty()) continue
            var stopCone = false
            for (below in inCone) {
                val bl = below.text.trim().lowercase()
                if (isNoiseBlock(bl)) continue
                if (ALL_KNOWN_SUMMARY_LABELS.any { bl.contains(it) } ||
                    (!allowLimitLabels && BLACKLISTED_SUMMARY_LABELS.any { bl.contains(it) })
                ) {
                    stopCone = true
                    break
                }
                parseAllAmountsInText(below.text, allowWholeRupee = true).firstOrNull()?.let { return it }
            }
            if (stopCone) break
        }
        return null
    }

    private fun hasDifferentHeaderAbove(
        rows: List<List<TextBlock>>,
        rowIdx: Int,
        cell: TextBlock,
        labelBlock: TextBlock,
    ): Boolean {
        for (i in (rowIdx - 1) downTo maxOf(0, rowIdx - 2)) {
            for (above in rows[i]) {
                if (abs(above.x - labelBlock.x) < 12) continue
                if (cell.centerX in (above.x - 12)..(above.xEnd + 12)) {
                    val al = above.text.lowercase()
                    if (ALL_KNOWN_SUMMARY_LABELS.any { al.contains(it) }) return true
                }
            }
        }
        return false
    }

    private fun findDateNearLabel(
        rows: List<List<TextBlock>>,
        labels: List<String>,
        inferredYear: Int? = null,
    ): String? {
        val sortedLabels = labels.sortedByDescending { it.length }
        for ((rowIdx, row) in rows.withIndex()) {
            val rowLabelsCount = row.count { b ->
                val l = b.text.lowercase()
                ALL_KNOWN_SUMMARY_LABELS.any { l.contains(it) }
            }
            for ((colIdx, block) in row.withIndex()) {
                val low = block.text.lowercase()
                if (low.contains("=") || low.length > 82 || PROSE_MARKERS.any { low.contains(it) }) continue
                val matchedLabel = sortedLabels.firstOrNull { low.contains(it) } ?: continue

                val afterLabel = stripFollowingLabel(block.text.substring(low.indexOf(matchedLabel) + matchedLabel.length))
                TransactionRowParser.parseDateIso(afterLabel, inferredYear)?.let { return it }

                fun searchDateForPass(requireSameBox: Boolean): String? {
                    if (rowLabelsCount < 2) {
                        val nextRightLabel = row.drop(colIdx + 1).firstOrNull { sib ->
                            ALL_KNOWN_SUMMARY_LABELS.any { sib.text.lowercase().contains(it) }
                        }
                        val rightLimitX = nextRightLabel?.x ?: Int.MAX_VALUE
                        row.drop(colIdx + 1)
                            .filter { sib ->
                                if (block.boxId != null) {
                                    if (requireSameBox && sib.boxId != block.boxId) return@filter false
                                    if (!requireSameBox && sib.boxId != null && sib.boxId != block.boxId) return@filter false
                                }
                                sib.x < rightLimitX - 1 && sib.x - block.xEnd <= 320
                            }
                            .sortedBy { it.x }
                            .firstNotNullOfOrNull { TransactionRowParser.parseDateIso(it.text, inferredYear) }
                            ?.let { return it }
                    }

                    val leftNeighbor = row.getOrNull(colIdx - 1)
                    val rightNeighbor = row.getOrNull(colIdx + 1)
                    val coneMinX = if (leftNeighbor != null) (leftNeighbor.xEnd + block.x) / 2 else maxOf(0, block.x - 42)
                    val coneMaxX = if (rightNeighbor != null) (block.xEnd + rightNeighbor.x) / 2 else block.xEnd + 55

                    for (nextIdx in (rowIdx + 1)..minOf(rowIdx + 3, rows.lastIndex)) {
                        val nextRow = rows[nextIdx]
                        if (nextRow.isEmpty() || nextRow.first().y - block.y > 54) break
                        val candidate = nextRow
                            .filter { cs ->
                                if (block.boxId != null) {
                                    if (requireSameBox && cs.boxId != block.boxId) return@filter false
                                    if (!requireSameBox && cs.boxId != null && cs.boxId != block.boxId) return@filter false
                                }
                                cs.centerX in coneMinX..coneMaxX
                            }
                            .sortedBy { abs(it.centerX - block.centerX) }
                            .firstNotNullOfOrNull { TransactionRowParser.parseDateIso(it.text, inferredYear) }
                        if (candidate != null) return candidate
                    }

                    if (rowLabelsCount >= 2) {
                        row.drop(colIdx + 1)
                            .filter { sib ->
                                if (block.boxId != null) {
                                    if (requireSameBox && sib.boxId != block.boxId) return@filter false
                                    if (!requireSameBox && sib.boxId != null && sib.boxId != block.boxId) return@filter false
                                }
                                sib.x - block.xEnd <= 260
                            }
                            .sortedBy { it.x }
                            .firstNotNullOfOrNull { TransactionRowParser.parseDateIso(it.text, inferredYear) }
                            ?.let { return it }
                    }
                    return null
                }

                if (block.boxId != null) {
                    searchDateForPass(requireSameBox = true)?.let { return it }
                }
                searchDateForPass(requireSameBox = false)?.let { return it }
            }
        }
        return null
    }

    private fun findPeriodString(rows: List<List<TextBlock>>, searchBlob: String): String? {
        for (row in rows) {
            val joined = row.joinToString(" ") { it.text }.trim()
            val match = PERIOD_RANGE_RX.find(joined)
            if (match != null) return match.value.trim()
        }
        return PERIOD_RANGE_RX.find(searchBlob)?.value?.trim()
    }

    private fun extractPeriodEndDate(period: String?, rawText: String): String? {
        if (period.isNullOrBlank()) return null
        val parts = period.split(Regex("\\s+(?:to|-)\\s+", RegexOption.IGNORE_CASE))
        val endPart = parts.lastOrNull()?.trim() ?: return null
        val explicit = TransactionRowParser.parseDateIso(endPart)
        if (explicit != null) return explicit
        val yr = extractYearHintFromText(rawText) ?: LocalDate.now().year
        return TransactionRowParser.parseDateIso(endPart, yr)
    }

    private fun extractYearHintFromText(rawText: String): Int? {
        Regex("\\b\\d{1,2}\\s+[A-Za-z]{3}\\s+'(\\d{2})\\b").find(rawText)?.let {
            return 2000 + it.groupValues[1].toInt()
        }
        Regex("\\b(202\\d|203\\d)\\b").find(rawText)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun resolveChronologicalDueDate(dueIso: String?, stmtIso: String?): String? {
        if (dueIso == null || stmtIso == null) return dueIso
        val due = runCatching { LocalDate.parse(dueIso) }.getOrNull() ?: return dueIso
        val stmt = runCatching { LocalDate.parse(stmtIso) }.getOrNull() ?: return dueIso
        if (stmt.monthValue >= 11 && due.isBefore(stmt) && due.year == stmt.year) {
            return due.plusYears(1).toString()
        }
        return dueIso
    }

    private fun extractPage1NumericCandidates(rows: List<List<TextBlock>>): List<BigDecimal> {
        val out = mutableListOf<BigDecimal>()
        for ((rIdx, row) in rows.withIndex()) {
            val rowText = row.joinToString(" ") { it.text }.lowercase()
            if ((rowText.contains("date") && (rowText.contains("transaction") || rowText.contains("description") || rowText.contains("particulars"))) ||
                rowText == "your transactions" || rowText == "statement details"
            ) {
                break
            }
            for ((cIdx, block) in row.withIndex()) {
                val bl = block.text.lowercase()
                if (BLACKLISTED_SUMMARY_LABELS.any { bl.contains(it) } || PROSE_MARKERS.any { bl.contains(it) }) continue
                val leftText = row.getOrNull(cIdx - 1)?.text?.lowercase().orEmpty()
                if (BLACKLISTED_SUMMARY_LABELS.any { leftText.contains(it) }) continue
                val headerAbove = (1..2).firstNotNullOfOrNull { off ->
                    rows.getOrNull(rIdx - off)?.firstOrNull { block.centerX in (it.x - 16)..(it.xEnd + 16) }?.text?.lowercase()
                }
                if (headerAbove != null && BLACKLISTED_SUMMARY_LABELS.any { headerAbove.contains(it) }) continue
                out += parseAllAmountsInText(block.text, allowWholeRupee = false)
            }
        }
        return out.distinct()
    }

    private fun findRegexAmount(
        text: String,
        labels: List<String>,
        allowLimitLabels: Boolean = false,
    ): BigDecimal? {
        for (label in labels) {
            val rx = Regex(
                "${Regex.escape(label)}[^\\d\\n]{0,25}(?:₹|Rs\\.?|INR|`|[\uE000-\uF8FF]|\\$|€|£)?\\s*(\\d+(?:,\\d+)*(?:\\.\\d{1,2})?)",
                RegexOption.IGNORE_CASE,
            )
            for (m in rx.findAll(text)) {
                val snippetStart = maxOf(0, m.range.first - 24)
                val prefix = text.substring(snippetStart, m.range.first).lowercase()
                if (!allowLimitLabels && BLACKLISTED_SUMMARY_LABELS.any { prefix.contains(it) }) continue
                parseCleanBigDecimal(m.groupValues[1])?.let { return it }
            }
        }
        return null
    }

    private fun parseAllAmountsInText(text: String, allowWholeRupee: Boolean = true): List<BigDecimal> {
        val out = mutableListOf<BigDecimal>()
        for (m in SIGNED_AMOUNT_RX.findAll(text)) {
            val negSign = m.groupValues[1] == "-"
            val decNum = m.groupValues[2]
            val curNum = m.groupValues[3]
            val groupedNum = m.groupValues[4]
            val isCr = m.groupValues[5].isNotEmpty() || m.groupValues[6].isNotEmpty()
            val numStr = decNum.ifEmpty { curNum }.ifEmpty { groupedNum }
            if (numStr.isEmpty()) continue
            val bd = parseCleanBigDecimal(numStr) ?: continue
            out += if (negSign || isCr) bd.negate() else bd
        }
        if (out.isEmpty() && allowWholeRupee) {
            WHOLE_AMOUNT_RX.matchEntire(text.trim().removePrefix(":").trim())?.let { m ->
                parseCleanBigDecimal(m.groupValues[1])?.let { bd ->
                    out += if (m.groupValues[2].isNotEmpty()) bd.negate() else bd
                }
            }
        }
        return out
    }

    private fun parseCleanBigDecimal(raw: String): BigDecimal? =
        runCatching {
            BigDecimal(raw.replace(",", "").trim()).setScale(2, RoundingMode.HALF_UP)
        }.getOrNull()

    private fun stripFollowingLabel(rest: String): String {
        val low = rest.lowercase()
        val cut = ALL_KNOWN_SUMMARY_LABELS
            .mapNotNull { lab -> low.indexOf(lab).takeIf { it > 0 } }
            .minOrNull() ?: return rest
        return rest.substring(0, cut)
    }

    private fun isNoiseBlock(low: String): Boolean =
        low in NOISE_TOKENS ||
            (low.startsWith("(") && low.endsWith(")")) ||
            low == "& other charges :" ||
            low == "reversals)"

    private fun extractLast4(text: String): String? {
        val spacedMask = text.replace(" ", "")
        Regex("[Xx*•]{8,15}(\\d{4})\\b").find(spacedMask)?.let { return it.groupValues[1] }
        val masked = Regex("(?:XXXX|xxxx|\\*{4}|•{4}|xx)[\\s-]?(\\d{4})\\b").find(text)
        if (masked != null) return masked.groupValues[1]
        val ending = Regex("(?:ending\\s+(?:in|with)|card\\s+no\\.?\\s*[:#-]?)[^\\d\\n]{0,24}(\\d{4})\\b", RegexOption.IGNORE_CASE).find(text)
        return ending?.groupValues?.get(1)
    }

    private fun detectBankName(rawText: String): String? {
        val low = rawText.lowercase()
        return BANK_SIGNATURES.firstOrNull { (_, keywords) ->
            keywords.any { low.contains(it) }
        }?.first
    }

    companion object {
        private val TOLERANCE = BigDecimal("2.00")
        private val MIN_SENSIBLE_LIMIT = BigDecimal("1000.00")

        private val SIGNED_AMOUNT_RX = Regex(
            "(?:(-)\\s*)?(?:(\\d+(?:,\\d+)*\\.\\d{2})|(?:[₹`\uE000-\uF8FF$€£]|Rs\\.?|INR)\\s*(\\d+(?:,\\d+)*(?:\\.\\d{1,2})?)|(\\d{1,3}(?:,\\d{2})*,\\d{3})(?![\\d.]))(?:\\s*(?:\\(\\s*(Cr)\\s*\\)|(Cr)\\b))?",
            RegexOption.IGNORE_CASE,
        )

        private val WHOLE_AMOUNT_RX = Regex(
            "^[-+]?\\s*(\\d{1,7})(?:\\s*\\(?\\s*(Cr)\\s*\\)?)?$",
            RegexOption.IGNORE_CASE,
        )

        private val PERIOD_RANGE_RX = Regex(
            "(?:\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+[A-Za-z]{3,9}(?:\\s+\\d{2,4})?|[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})\\s+(?:to|-)\\s+(?:\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}\\s+[A-Za-z]{3,9}(?:\\s+\\d{2,4})?|[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})",
            RegexOption.IGNORE_CASE,
        )

        private val TOTAL_DUE_LABELS = listOf(
            "your total amount due", "current total amount due", "total amount due (tad)",
            "total amount due", "total payment due", "total bill amount", "total dues",
            "total amt due", "total amt. due", "total amount payable", "total payable",
            "net payable", "payable amount", "new balance", "statement balance",
            "closing balance", "total due",
        )

        private val MIN_DUE_LABELS = listOf(
            "minimum amount due (mad)", "minimum amount due", "minimum payment due",
            "min. amount due", "min amount due", "min. amt. due", "min amt due",
            "minimum amount payable", "minimum payment", "min payment due", "minimum due",
        )

        private val STATEMENT_DATE_LABELS = listOf(
            "statement generation date", "date of statement", "bill generation date",
            "statement closing date", "statement generated on", "bill generated on",
            "statement date", "stmt date", "stmt. date", "bill date", "billing date", "closing date",
        )

        private val PAYMENT_DUE_DATE_LABELS = listOf(
            "payment due date", "last date of payment", "last date for payment",
            "payment due on", "please pay by", "due date", "pay by", "due by", "due on",
        )

        private val CREDIT_LIMIT_LABELS = listOf(
            "total credit limit", "sanctioned credit limit", "sanctioned limit",
            "maximum credit limit", "credit limit (including cash)", "credit limit", "card limit", "cr. limit",
        )

        private val AVAILABLE_CREDIT_LABELS = listOf(
            "available credit (including cash)", "available credit limit", "available credit", "available limit",
        )

        private val OPENING_BALANCE_LABELS = listOf(
            "opening balance", "previous balance", "prev balance", "prev. balance",
            "previous statement dues", "previous outstanding", "last statement balance",
            "previous amount due", "opening bal", "carry forward",
        )

        private val PURCHASES_LABELS = listOf(
            "purchases / other charges", "purchases & other charges", "purchases / charges",
            "purchases / debits", "current purchases / cash advance", "current purchases",
            "total spends", "spends", "purchases",
        )

        private val BLACKLISTED_SUMMARY_LABELS = listOf(
            "credit limit",
            "available credit",
            "cash limit",
            "available cash",
            "available limit",
            "reward points",
            "points balance",
            "points earned",
            "total limit",
            "card limit",
            "sanctioned limit",
        )

        private val ILLUSTRATION_MARKERS = listOf(
            "illustrative example",
            "illustration for calculating",
            "sample calculation",
            "interest charges calculation",
            "minimum amount due calculation",
            "finance charge calculation",
            "schedule of charges",
            "key fact statement",
            "most important terms",
            "glossary",
        )

        private val PROSE_MARKERS = listOf(
            "will be charged",
            "making only",
            "to avoid late",
            "interest will be",
            "for example",
            "assuming",
            "stretching over",
        )

        private val NOISE_TOKENS = setOf(
            "=", "+", "-", "_", ":", "(in ₹)", "(in rs.)", "(in `)", "(including cash)",
            "amount in rs.", "amount in inr", "reversals)", "& other charges :",
        )

        private val ALL_KNOWN_SUMMARY_LABELS = (
            TOTAL_DUE_LABELS + MIN_DUE_LABELS + STATEMENT_DATE_LABELS + PAYMENT_DUE_DATE_LABELS +
                CREDIT_LIMIT_LABELS + AVAILABLE_CREDIT_LABELS + OPENING_BALANCE_LABELS + PURCHASES_LABELS +
                listOf(
                    "cash limit", "available cash", "statement period", "billing period",
                    "payments / credits", "payment & credits received", "payments & refunds",
                    "refunds & repayments", "cash advances", "cash advance", "finance charges",
                    "fee & charges", "fees & charges", "points earned", "credit summary",
                    "statement summary", "bill summary", "payment summary", "limit details",
                )
            ).distinct().sortedByDescending { it.length }

        private val BANK_SIGNATURES = listOf(
            "HDFC Bank" to listOf("hdfc bank", "hdfcbank", "regalia", "infinia", "millennia", "tata neu"),
            "ICICI Bank" to listOf("icici bank", "icicibank", "sapphiro", "coral", "rubyx", "amazon pay icici"),
            "SBI Card" to listOf("sbi card", "sbicard", "state bank of india"),
            "Axis Bank" to listOf("axis bank", "axisbank", "magnus", "flipkart axis", "ace credit card"),
            "AU Small Finance Bank" to listOf("au small finance", "aubank", "lit credit card", "altura"),
            "Yes Bank" to listOf("yes bank", "yesbank"),
            "Kotak Mahindra Bank" to listOf("kotak mahindra", "kotak bank", "811"),
            "IDFC First Bank" to listOf("idfc first", "idfcfirst"),
            "IndusInd Bank" to listOf("indusind"),
            "RBL Bank" to listOf("rbl bank", "bajaj finserv rbl"),
            "American Express" to listOf("american express", "amex"),
            "HSBC" to listOf("hsbc"),
            "Standard Chartered" to listOf("standard chartered"),
            "Slice" to listOf("slice", "quadrangle", "slice pay"),
            "OneCard" to listOf("onecard", "one card", "fpl technologies"),
            "Citibank" to listOf("citibank", "citi credit"),
            "Chase" to listOf("jpmorgan chase", "chase sapphire", "chase freedom"),
        )
    }
}
