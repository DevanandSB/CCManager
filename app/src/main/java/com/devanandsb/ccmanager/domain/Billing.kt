package com.devanandsb.ccmanager.domain

import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.Payment
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.isCredit
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * The cycle a card is currently in.
 * [dueDate] is the next date the user has to act on: the latest statement's due date, or, once
 * that has been marked paid, the next cycle's.
 */
data class DueInfo(
    val statementDate: LocalDate,
    val dueDate: LocalDate,
    val daysLeft: Long,
    val overdue: Boolean,
    /** The most recent generated statement is settled (we are now showing the next cycle). */
    val lastCyclePaid: Boolean,
)

data class CardWithDue(
    val card: Card,
    val due: DueInfo,
    val postSpendPaise: Long = 0L,
    val postRefundSurplusPaise: Long = 0L,
) {
    /** Total limit used = Pending bill due + New spends after statement date (less any post-statement refund surplus). */
    val totalUsedPaise: Long
        get() = if (!card.isCredit) 0L
        else (card.outstandingPaise.coerceAtLeast(0L) + postSpendPaise.coerceAtLeast(0L) - postRefundSurplusPaise.coerceAtLeast(0L)).coerceAtLeast(0L)

    /** Available credit limit = Credit limit - (Pending amount + New spends after statement date). */
    val availableLimitPaise: Long
        get() = if (!card.isCredit || card.limitPaise <= 0L) 0L else (card.limitPaise - totalUsedPaise).coerceAtLeast(0L)

    val utilization: Float
        get() = if (!card.isCredit || card.limitPaise <= 0L) 0f
        else (totalUsedPaise.toDouble() / card.limitPaise.toDouble()).toFloat().coerceIn(0f, 1f)
}

object Billing {
    fun statementDate(ym: YearMonth, day: Int): LocalDate = ym.atDay(min(day.coerceAtLeast(1), ym.lengthOfMonth()))

    /**
     * Due date source of truth: the latest parsed statement for the current cycle when there is
     * one, else statement day + offset from the card settings.
     */
    fun dueInfo(card: Card, payments: List<Payment>, today: LocalDate = LocalDate.now(), statements: List<Statement> = emptyList()): DueInfo {
        var ym = YearMonth.from(today)
        var stmt = statementDate(ym, card.statementDay)
        if (stmt.isAfter(today)) {
            ym = ym.minusMonths(1)
            stmt = statementDate(ym, card.statementDay)
        }
        var due = stmt.plusDays(card.dueOffsetDays.toLong())
        var cycleYm = ym
        var cycleStmtDay = card.statementDay
        var cycleDueOffset = card.dueOffsetDays

        val parsed = statements.asSequence()
            .filter { it.cardId == card.id }
            .maxWithOrNull(compareBy<Statement> { it.statementEpochDay }.thenBy { it.id })
        // A statement in credit ("Cr", total ≤ 0) has nothing to pay this cycle.
        var nothingDue = false
        var hasActiveParsedStatement = false
        if (parsed != null) {
            val pStmt = LocalDate.ofEpochDay(parsed.statementEpochDay)
            val stmtDiff = kotlin.math.abs(ChronoUnit.DAYS.between(stmt, pStmt))
            val nextMonthStmt = statementDate(ym.plusMonths(1), card.statementDay)
            val nextStmtDiff = kotlin.math.abs(ChronoUnit.DAYS.between(nextMonthStmt, pStmt))
            val matchesCurrent = stmtDiff <= 5L
            val matchesNextEarly = card.billingSet && !matchesCurrent && nextStmtDiff <= 5L
            val windowOk = if (card.billingSet) (matchesCurrent || matchesNextEarly) else !pStmt.isBefore(stmt.minusDays(10))
            if (!pStmt.isAfter(today) && windowOk) {
                if (matchesNextEarly) cycleYm = ym.plusMonths(1)
                val pDue = parsed.dueEpochDay?.let { LocalDate.ofEpochDay(it) }
                val dueGap = pDue?.let { ChronoUnit.DAYS.between(pStmt, it) }
                if (pDue != null && dueGap != null && dueGap in 0..60) {
                    stmt = pStmt
                    due = pDue
                } else {
                    stmt = pStmt
                    due = pStmt.plusDays(card.dueOffsetDays.toLong())
                }
                if (!card.billingSet) {
                    cycleYm = YearMonth.from(pStmt)
                    cycleStmtDay = pStmt.dayOfMonth
                    if (dueGap != null && dueGap in 0..60) cycleDueOffset = dueGap.toInt()
                }
                val effectiveDue = Repo.effectiveStatementDuePaise(parsed, card.limitPaise)
                nothingDue = effectiveDue != null && effectiveDue <= 0L && card.outstandingPaise <= 0L
                hasActiveParsedStatement = (effectiveDue ?: 0L) > 0L || card.outstandingPaise > 0L
            }
        }
        val created = Instant.ofEpochMilli(card.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        // A partial payment (or an unapplied payment row while balance > 0) must NEVER mark a due date settled
        // and jump to next month while outstandingPaise > 0.
        val paidDueDays = if (card.outstandingPaise <= 0L) {
            payments.asSequence().filter { it.cardId == card.id }.map { it.dueEpochDay }.toList()
        } else {
            emptyList()
        }
        val dueEpoch = due.toEpochDay()
        val duePaid = paidDueDays.any { kotlin.math.abs(it - dueEpoch) <= 10L }

        // A freshly added card without an unpaid statement treats a past cycle as settled;
        // if an uploaded unpaid statement still has pending due (outstanding > 0), keep it visible!
        // Conversely, if SMS/manual payments reduced outstanding down to 0, mark the active cycle settled.
        val beforeCreatedSettled = due.isBefore(created) && (!hasActiveParsedStatement || card.outstandingPaise <= 0L)
        val settled = duePaid || beforeCreatedSettled || nothingDue || (hasActiveParsedStatement && card.outstandingPaise <= 0L)
        if (!settled) {
            val days = ChronoUnit.DAYS.between(today, due)
            return DueInfo(stmt, due, days, overdue = days < 0, lastCyclePaid = false)
        }
        var nextYm = cycleYm.plusMonths(1)
        var nextStmt = statementDate(nextYm, cycleStmtDay)
        var nextDue = nextStmt.plusDays(cycleDueOffset.toLong())
        while (nextDue.isBefore(today)) {
            nextYm = nextYm.plusMonths(1)
            nextStmt = statementDate(nextYm, cycleStmtDay)
            nextDue = nextStmt.plusDays(cycleDueOffset.toLong())
        }
        val cyclePaid = duePaid || (card.outstandingPaise <= 0L)
        return DueInfo(nextStmt, nextDue, ChronoUnit.DAYS.between(today, nextDue), overdue = false, lastCyclePaid = cyclePaid)
    }

    fun parseLeads(s: String): List<Int> =
        s.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..60 }.distinct().sortedDescending()
}
