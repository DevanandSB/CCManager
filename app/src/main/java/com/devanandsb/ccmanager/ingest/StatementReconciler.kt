package com.devanandsb.ccmanager.ingest

import kotlin.math.abs

/**
 * Accounting-identity self-reconciler for Indian credit card statements:
 *   OpeningBalance - TotalCredits + TotalDebits == TotalAmountDue   (within ±₹2.00 = ±200 paise)
 *   0 <= MinimumAmountDue <= TotalAmountDue
 *
 * When a primary Total Amount Due or Minimum Amount Due candidate fails reconciliation (or matches a
 * Credit Limit / Available Credit / Cash Limit / Reward Points trap), selects a Page 1 candidate
 * that satisfies the accounting equation so both known and unseen bank layouts parse accurately.
 */
object StatementReconciler {

    private const val TOLERANCE_PAISE = 200L

    data class Context(
        val labeledTotalCandidates: List<Long> = emptyList(),
        val labeledMinCandidates: List<Long> = emptyList(),
        val page1NumericCandidates: List<Long> = emptyList(),
        val limitPaise: Long? = null,
        val availableCreditPaise: Long? = null,
        val cashLimitPaise: Long? = null,
        val availableCashPaise: Long? = null,
        val summaryTotalPaise: Long? = null,
        val equationTotalPaise: Long? = null,
    )

    fun reconcile(parsed: ParsedStatement, ctx: Context): ParsedStatement {
        val debits = parsed.debits
        val credits = parsed.credits
        val opening = parsed.openingPaise
        val limit = ctx.limitPaise ?: parsed.creditLimitPaise
        val avail = ctx.availableCreditPaise
        val cashLimit = ctx.cashLimitPaise
        val availCash = ctx.availableCashPaise

        val txnIdentity = if (opening != null) opening - credits + debits else null
        val zeroOpeningIdentity = if (opening == null && parsed.lines.isNotEmpty()) debits - credits else null
        val limitDiff = if (limit != null && avail != null && limit > 0L && avail in 1 until limit) limit - avail else null

        fun confirmsValue(amt: Long): Boolean {
            if (txnIdentity != null && abs(amt - txnIdentity) <= TOLERANCE_PAISE) return true
            if (ctx.summaryTotalPaise != null && abs(amt - ctx.summaryTotalPaise) <= TOLERANCE_PAISE) return true
            if (ctx.equationTotalPaise != null && abs(amt - ctx.equationTotalPaise) <= TOLERANCE_PAISE) return true
            return false
        }

        fun isBlacklistedLimit(amt: Long): Boolean {
            if (confirmsValue(amt)) return false
            if (limit != null && limit > 0L && amt == limit && (debits < limit || (txnIdentity != null && txnIdentity < limit - TOLERANCE_PAISE))) {
                return true
            }
            if (cashLimit != null && cashLimit > 0L && amt == cashLimit) return true
            if (availCash != null && availCash > 0L && amt == availCash) return true
            if (avail != null && limit != null && avail in 1 until limit && amt == avail && amt != limitDiff && debits < amt) {
                return true
            }
            return false
        }

        val allTotalCandidates = (
            listOfNotNull(parsed.totalDuePaise) +
                ctx.labeledTotalCandidates +
                listOfNotNull(ctx.summaryTotalPaise, ctx.equationTotalPaise) +
                ctx.page1NumericCandidates
            ).distinct()

        val validLabeled = (listOfNotNull(parsed.totalDuePaise) + ctx.labeledTotalCandidates)
            .distinct()
            .filterNot(::isBlacklistedLimit)

        val resolvedTotal: Long? = when {
            // 1. Primary parsed total satisfies the accounting identity and isn't a blacklisted limit
            parsed.totalDuePaise != null &&
                txnIdentity != null &&
                abs(parsed.totalDuePaise - txnIdentity) <= TOLERANCE_PAISE &&
                !isBlacklistedLimit(parsed.totalDuePaise) -> parsed.totalDuePaise

            // 2. Any labeled candidate satisfies the accounting identity
            txnIdentity != null && validLabeled.any { abs(it - txnIdentity) <= TOLERANCE_PAISE } ->
                validLabeled.filter { abs(it - txnIdentity) <= TOLERANCE_PAISE }
                    .minBy { abs(it - txnIdentity) }

            // 3. Any Page 1 numeric candidate satisfies the accounting identity (heals unseen layouts)
            txnIdentity != null && allTotalCandidates.any { !isBlacklistedLimit(it) && abs(it - txnIdentity) <= TOLERANCE_PAISE } ->
                allTotalCandidates.filter { !isBlacklistedLimit(it) && abs(it - txnIdentity) <= TOLERANCE_PAISE }
                    .minBy { abs(it - txnIdentity) }

            // 4. Summary/Equation total confirms a candidate
            ctx.summaryTotalPaise != null && !isBlacklistedLimit(ctx.summaryTotalPaise) &&
                validLabeled.any { abs(it - ctx.summaryTotalPaise) <= TOLERANCE_PAISE } ->
                validLabeled.first { abs(it - ctx.summaryTotalPaise) <= TOLERANCE_PAISE }

            ctx.equationTotalPaise != null && !isBlacklistedLimit(ctx.equationTotalPaise) &&
                validLabeled.any { abs(it - ctx.equationTotalPaise) <= TOLERANCE_PAISE } ->
                validLabeled.first { abs(it - ctx.equationTotalPaise) <= TOLERANCE_PAISE }

            // 5. Available credit complement check (Credit Limit - Available Credit)
            limitDiff != null && validLabeled.any { abs(it - limitDiff) <= TOLERANCE_PAISE } ->
                validLabeled.first { abs(it - limitDiff) <= TOLERANCE_PAISE }

            // 6. Zero-opening identity fallback (for unseen statements that omit Opening Balance and miss/misread Total)
            zeroOpeningIdentity != null &&
                (parsed.totalDuePaise == null || isBlacklistedLimit(parsed.totalDuePaise)) &&
                allTotalCandidates.any { !isBlacklistedLimit(it) && abs(it - zeroOpeningIdentity) <= TOLERANCE_PAISE } ->
                allTotalCandidates.filter { !isBlacklistedLimit(it) && abs(it - zeroOpeningIdentity) <= TOLERANCE_PAISE }
                    .minBy { abs(it - zeroOpeningIdentity) }

            zeroOpeningIdentity != null &&
                validLabeled.size > 1 &&
                (parsed.totalDuePaise == null || !confirmsValue(parsed.totalDuePaise)) &&
                validLabeled.any { abs(it - zeroOpeningIdentity) <= TOLERANCE_PAISE } ->
                validLabeled.filter { abs(it - zeroOpeningIdentity) <= TOLERANCE_PAISE }
                    .minBy { abs(it - zeroOpeningIdentity) }

            // 7. Fallback to primary if not blacklisted, or first non-blacklisted labeled candidate
            parsed.totalDuePaise != null && !isBlacklistedLimit(parsed.totalDuePaise) -> parsed.totalDuePaise
            validLabeled.isNotEmpty() -> validLabeled.first()
            ctx.summaryTotalPaise != null && !isBlacklistedLimit(ctx.summaryTotalPaise) -> ctx.summaryTotalPaise
            ctx.equationTotalPaise != null && !isBlacklistedLimit(ctx.equationTotalPaise) -> ctx.equationTotalPaise
            else -> parsed.totalDuePaise
        }

        // Enforce 0 <= MinimumAmountDue <= TotalAmountDue (and exclude limit / available credit values)
        val rawMinCandidates = (listOfNotNull(parsed.minDuePaise) + ctx.labeledMinCandidates)
            .distinct()
            .filter { it >= 0L }
            .filterNot { minVal ->
                isBlacklistedLimit(minVal) ||
                    (avail != null && avail != resolvedTotal && minVal == avail)
            }

        val resolvedMin: Long? = when {
            resolvedTotal == null -> rawMinCandidates.firstOrNull()
            resolvedTotal < 0L -> if (0L in rawMinCandidates) 0L else null
            resolvedTotal == 0L -> {
                if (0L in rawMinCandidates) 0L
                else if (rawMinCandidates.size == 1 && opening == null) rawMinCandidates.first()
                else null
            }
            else -> {
                val bounded = rawMinCandidates.filter { it in 0L..resolvedTotal }
                bounded.firstOrNull()
                    ?: ctx.page1NumericCandidates
                        .filter { it in 1L..resolvedTotal && !isBlacklistedLimit(it) && it != opening }
                        .firstOrNull { minCand -> minCand * 100 / resolvedTotal in 1..20 }
            }
        }

        return parsed.copy(
            totalDuePaise = resolvedTotal,
            minDuePaise = resolvedMin,
            creditLimitPaise = parsed.creditLimitPaise ?: limit,
        )
    }
}
