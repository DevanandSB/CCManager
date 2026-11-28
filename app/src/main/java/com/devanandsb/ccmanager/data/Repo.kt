package com.devanandsb.ccmanager.data

import android.content.Context
import androidx.room.withTransaction
import com.devanandsb.ccmanager.CardsApp
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.ingest.TxnKind
import com.devanandsb.ccmanager.reminders.Reminders
import com.devanandsb.ccmanager.security.EncryptedDraft
import com.devanandsb.ccmanager.ui.addAppliedUnstmtPayKeys
import com.devanandsb.ccmanager.ui.clearAppliedUnstmtPayKeys
import com.devanandsb.ccmanager.ui.getAppliedUnstmtPayKeys
import com.devanandsb.ccmanager.ui.setPostSpendPaise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class Repo(private val dao: CardDao, private val ledgerDao: LedgerDao, private val context: Context) {

    val ledger = Ledger(ledgerDao) { cardId -> syncOutstandingFromStatement(cardId) }

    /** Every card, archived included (for history: activity, statements, detail). */
    val everyCard: Flow<List<CardWithDue>> =
        combine(dao.observeCards(), dao.observePayments(), ledgerDao.observeStatements(), ledgerDao.observeTxns()) { cards, payments, statements, txns ->
            val today = LocalDate.now()
            val liveAllowed = false
            cards.map { c ->
                val due = Billing.dueInfo(c, payments, today, statements)
                val pb = computePostBalanceForCard(
                    card = c,
                    statements = statements,
                    txns = txns,
                    savedPref = com.devanandsb.ccmanager.ui.getPostSpendPaise(context, c.id),
                    livePermitted = liveAllowed,
                )
                CardWithDue(c, due, pb.postSpendPaise, pb.postRefundSurplusPaise)
            }
                // Cards without a due date (its dueDate is only a placeholder) go after those with one.
                .sortedWith(compareByDescending<CardWithDue> { it.card.pinned }.thenBy { !it.card.billingSet }.thenBy { it.due.dueDate })
        }

    /** Active cards with their current cycle, pinned first, then soonest due. */
    val cards: Flow<List<CardWithDue>> = everyCard.map { list -> list.filter { it.card.archivedAt == null } }

    /** Archived cards, most recently archived first. */
    val archived: Flow<List<CardWithDue>> =
        everyCard.map { list -> list.filter { it.card.archivedAt != null }.sortedByDescending { it.card.archivedAt } }

    val payments: Flow<List<Payment>> = dao.observePayments()

    fun card(id: Long): Flow<CardWithDue?> = everyCard.map { list -> list.firstOrNull { it.card.id == id } }

    suspend fun cardOnce(id: Long): Card? = dao.card(id)

    suspend fun allCards(): List<Card> = dao.cards()

    /** Cards that statements can be matched to (not archived). */
    suspend fun activeCards(includeAll: Boolean = false): List<Card> =
        dao.cards().filter { it.archivedAt == null && (includeAll || it.isCredit) }

    /**
     * Archive (deactivate) or restore a card. Archiving unpins it and cancels its reminders;
     * restoring schedules them again. Statements, transactions, payments and vault data are kept.
     */
    suspend fun setArchived(id: Long, archived: Boolean) {
        val c = dao.card(id) ?: return
        dao.update(c.copy(archivedAt = if (archived) System.currentTimeMillis() else null, pinned = !archived && c.pinned))
        if (archived) Reminders.cancel(context, id) else reschedule(id)
    }

    suspend fun cardWithDue(id: Long): CardWithDue? {
        val c = dao.card(id) ?: return null
        val statements = ledgerDao.statements()
        val due = Billing.dueInfo(c, dao.paymentsFor(id), LocalDate.now(), statements)
        val pb = computePostBalanceForCard(
            card = c,
            statements = statements,
            txns = ledgerDao.allTxnsForCard(id).filter { it.statementId == null },
            savedPref = com.devanandsb.ccmanager.ui.getPostSpendPaise(context, id),
            livePermitted = false,
        )
        return CardWithDue(c, due, pb.postSpendPaise, pb.postRefundSurplusPaise)
    }

    suspend fun snapshot(): List<CardWithDue> {
        val payments = dao.payments()
        val statements = ledgerDao.statements()
        val txns = ledgerDao.txns()
        val today = LocalDate.now()
        val liveAllowed = false
        return dao.cards().filter { it.archivedAt == null }.map { c ->
            val due = Billing.dueInfo(c, payments, today, statements)
            val pb = computePostBalanceForCard(
                card = c,
                statements = statements,
                txns = txns,
                savedPref = com.devanandsb.ccmanager.ui.getPostSpendPaise(context, c.id),
                livePermitted = liveAllowed,
            )
            CardWithDue(c, due, pb.postSpendPaise, pb.postRefundSurplusPaise)
        }
    }

    suspend fun secret(id: Long): CardSecret? = dao.secret(id)

    /** Inserts or updates [card]; non-null fields of [secrets] replace the stored ones, or clears all if [clearSecrets] is true. */
    suspend fun save(card: Card, secrets: EncryptedDraft?, clearSecrets: Boolean = false): Long {
        // The edit form doesn't know about archiving; keep whatever state the stored row has.
        val id = if (card.id == 0L) dao.insert(card) else card.id.also { dao.update(card.copy(archivedAt = dao.card(it)?.archivedAt)) }
        if (clearSecrets) {
            dao.deleteSecret(id)
        } else if (secrets != null) {
            val old = dao.secret(id)
            dao.upsertSecret(
                CardSecret(
                    cardId = id,
                    numberEnc = secrets.number ?: old?.numberEnc,
                    expiryEnc = secrets.expiry ?: old?.expiryEnc,
                    cvvEnc = secrets.cvv ?: old?.cvvEnc,
                    holderEnc = secrets.holder ?: old?.holderEnc,
                ),
            )
        }
        reschedule(id)
        return id
    }

    suspend fun delete(id: Long) {
        Reminders.cancel(context, id)
        dao.delete(id)
    }

    /**
     * Pins (up to [com.devanandsb.ccmanager.ui.MAX_PINS]) or unpins a card. When the limit is reached, [replacing] names the
     * pinned card that makes room; without it the pin is refused and false is returned.
     */
    suspend fun setPinned(card: Card, pinned: Boolean, replacing: Long? = null): Boolean =
        // Check first and write both rows together, so a refused pin changes nothing.
        CardsApp.from(context).db.withTransaction {
            if (pinned) {
                val others = dao.cards().count { it.pinned && it.archivedAt == null && it.id != card.id && it.id != replacing }
                if (others >= com.devanandsb.ccmanager.ui.MAX_PINS) return@withTransaction false
                replacing?.takeIf { it != card.id }?.let { id -> dao.card(id)?.let { dao.update(it.copy(pinned = false)) } }
            }
            dao.card(card.id)?.let { dao.update(it.copy(pinned = pinned)) }
            true
        }

    suspend fun setOutstanding(id: Long, paise: Long) {
        val clean = paise.coerceAtLeast(0L)
        val c = dao.card(id) ?: return
        dao.update(c.copy(outstandingPaise = clean))
        val hasStmt = ledgerDao.statements().any { it.cardId == id }
        if (!hasStmt && c.isCredit) {
            val existingPayKeys = ledgerDao.allTxnsForCard(id)
                .filter { it.statementId == null && it.kind == TxnKind.PAYMENT && it.isCredit }
                .map { it.dedupeKey }
                .toSet()
            if (existingPayKeys.isNotEmpty()) {
                addAppliedUnstmtPayKeys(context, id, existingPayKeys)
            }
        }
        reschedule(id)
    }

    /** Records a payment against the cycle due on [dueEpochDay] and silences its reminders. */
    suspend fun markPaid(cardId: Long, dueEpochDay: Long, amountPaise: Long) {
        val c = dao.card(cardId) ?: return
        val cleanAmount = amountPaise.coerceAtLeast(0L)
        dao.insertPayment(Payment(cardId = cardId, dueEpochDay = dueEpochDay, paidOnEpochDay = LocalDate.now().toEpochDay(), amountPaise = cleanAmount))
        val latest = latestStatementFor(cardId, ledgerDao.statements())
        val latestBillDue = latest?.let { effectiveStatementDuePaise(it, c.limitPaise) }
        if (latest != null && latestBillDue != null && statementCoversDueDay(latest, dueEpochDay, c)) {
            syncOutstandingFromStatement(cardId)
        } else {
            dao.update(c.copy(outstandingPaise = (c.outstandingPaise - cleanAmount).coerceAtLeast(0L)))
            reschedule(cardId)
        }
    }

    suspend fun deletePayment(p: Payment) {
        dao.deletePayment(p.id)
        val c = dao.card(p.cardId) ?: return
        val statements = ledgerDao.statements()
        val latest = latestStatementFor(p.cardId, statements)
        val latestBillDue = latest?.let { effectiveStatementDuePaise(it, c.limitPaise) }
        if (latest != null && latestBillDue != null && statementCoversDueDay(latest, p.dueEpochDay, c)) {
            syncOutstandingFromStatement(p.cardId)
        } else {
            if (p.amountPaise > 0L) {
                val currentDue = Billing.dueInfo(c, dao.paymentsFor(p.cardId), LocalDate.now(), statements)
                if (kotlin.math.abs(currentDue.dueDate.toEpochDay() - p.dueEpochDay) <= 10L) {
                    dao.update(c.copy(outstandingPaise = (c.outstandingPaise + p.amountPaise).coerceAtLeast(0L)))
                }
            }
            reschedule(p.cardId)
        }
    }

    private fun latestStatementFor(cardId: Long, statements: List<Statement>): Statement? {
        val mine = statements.filter { it.cardId == cardId }
        return mine.filter {
            it.totalDuePaise != null || it.debitsPaise > 0L || it.creditsPaise > 0L || it.dueEpochDay != null
        }.maxWithOrNull(compareBy<Statement> { it.statementEpochDay }.thenBy { it.id })
            ?: mine.maxWithOrNull(compareBy<Statement> { it.statementEpochDay }.thenBy { it.id })
    }

    private fun statementCoversDueDay(st: Statement, dueEpochDay: Long, card: Card): Boolean {
        val stDue = st.dueEpochDay ?: (st.statementEpochDay + card.dueOffsetDays.toLong())
        return kotlin.math.abs(stDue - dueEpochDay) <= 10L
    }

    /**
     * After a statement import or alert sync: fill missing billing dates, sync limit,
     * compute outstanding = newest statement total due minus (manual + post-statement SMS payments),
     * and update post-statement unbilled spends from live alerts after statement date.
     */
    private suspend fun syncOutstandingFromStatement(cardId: Long) {
        var c = dao.card(cardId) ?: return
        val mine = ledgerDao.statements().filter { it.cardId == cardId }
        val trusted = latestStatementFor(cardId, mine)

        trusted?.let { s ->
            val offset = s.dueEpochDay?.let { (it - s.statementEpochDay).toInt() }?.takeIf { it in 0..60 }
            val stmtLocal = LocalDate.ofEpochDay(s.statementEpochDay)
            val isMonthEndClamped = stmtLocal.dayOfMonth == stmtLocal.lengthOfMonth()
            val resolvedStatementDay = if (c.billingSet && isMonthEndClamped && c.statementDay > stmtLocal.dayOfMonth) {
                c.statementDay
            } else {
                stmtLocal.dayOfMonth
            }
            val latestLimit = s.creditLimitPaise?.takeIf { it > 0 }
                ?: mine.filter { it.creditLimitPaise != null && it.creditLimitPaise > 0 }
                    .maxWithOrNull(compareBy<Statement> { it.statementEpochDay }.thenBy { it.id })
                    ?.creditLimitPaise
            c = c.copy(
                limitPaise = latestLimit ?: c.limitPaise,
                statementDay = resolvedStatementDay,
                dueOffsetDays = offset ?: c.dueOffsetDays,
                billingSet = true, // a real statement is the trusted source for limit and cycle dates
            )
        }
        val latest = trusted
            ?: mine.filter { it.totalDuePaise != null }
                .maxWithOrNull(compareBy<Statement> { it.statementEpochDay }.thenBy { it.id })
        val liveTxns = ledgerDao.allTxnsForCard(cardId).filter { it.statementId == null }
        if (latest != null) {
            val manualPayments = dao.paymentsFor(cardId)
            val stmtLines = ledgerDao.txnsFor(latest.id)
            val breakdown = computePostStatementBreakdown(latest, manualPayments, liveTxns, stmtLines)
            val paid = breakdown.paidPaise
            val postSpendPaise = breakdown.postSpendPaise

            val billDue = effectiveStatementDuePaise(latest, c.limitPaise)
            if (billDue != null) {
                c = c.copy(outstandingPaise = (billDue - paid).coerceAtLeast(0))
            }

            if (liveTxns.isNotEmpty()) {
                setPostSpendPaise(context, cardId, postSpendPaise)
            }
            clearAppliedUnstmtPayKeys(context, cardId)
        } else if (c.isCredit && c.outstandingPaise > 0L) {
            val manualPayments = dao.paymentsFor(cardId).toMutableList()
            val createdDay = Instant.ofEpochMilli(c.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val appliedKeys = getAppliedUnstmtPayKeys(context, cardId)
            val smsPays = liveTxns.filter { t ->
                t.statementId == null &&
                    t.kind == TxnKind.PAYMENT &&
                    t.isCredit &&
                    t.epochDay >= createdDay &&
                    t.dedupeKey !in appliedKeys
            }.toMutableList()
            // Avoid double-subtracting payments that were already recorded manually around the same date & amount.
            val consumedSmsKeys = mutableSetOf<String>()
            for (mp in manualPayments) {
                if (mp.amountPaise <= 0L) continue
                val idx = smsPays.indexOfFirst { sp ->
                    kotlin.math.abs(sp.amountPaise - mp.amountPaise) <= 100L &&
                        kotlin.math.abs(sp.epochDay - mp.paidOnEpochDay) <= 7L
                }
                if (idx >= 0) {
                    consumedSmsKeys += smsPays.removeAt(idx).dedupeKey
                }
            }
            var newlyReduced = 0L
            for (sp in smsPays) {
                newlyReduced += sp.amountPaise
                consumedSmsKeys += sp.dedupeKey
            }
            if (newlyReduced > 0L) {
                c = c.copy(outstandingPaise = (c.outstandingPaise - newlyReduced).coerceAtLeast(0L))
            }
            if (consumedSmsKeys.isNotEmpty()) {
                addAppliedUnstmtPayKeys(context, cardId, consumedSmsKeys)
            }
        }
        dao.update(c)
        reschedule(cardId)
    }

    private suspend fun reschedule(id: Long) {
        val cw = cardWithDue(id) ?: return
        if (cw.card.archivedAt != null || !cw.card.hasDues) Reminders.cancel(context, id) else Reminders.schedule(context, cw)
    }

    companion object {
        /**
         * Canonical Total Due extraction shared by [syncOutstandingFromStatement] and StatementScreen
         * so remaining-due and used-limit numbers agree everywhere.
         */
        fun effectiveStatementDuePaise(statement: Statement, cardLimitPaise: Long = 0L): Long? {
            val lim = statement.creditLimitPaise?.takeIf { it > 0L } ?: cardLimitPaise
            val estFromLines = (statement.openingPaise ?: 0L) + statement.debitsPaise - statement.creditsPaise
            val suspectLimitAsDue = lim > 0L && statement.totalDuePaise == lim &&
                statement.debitsPaise < lim && estFromLines < lim - 100L
            return when {
                statement.totalDuePaise != null && !suspectLimitAsDue -> statement.totalDuePaise
                statement.openingPaise != null || statement.debitsPaise > 0L || statement.creditsPaise > 0L ->
                    estFromLines.coerceAtLeast(0L)
                else -> null
            }
        }

        /**
         * Single source of truth for unbilled post-statement spend vs refund surplus across Home,
         * Card Face, Detail, and Available Credit calculation.
         */
        fun computePostSpendForCard(
            card: Card,
            statements: List<Statement>,
            txns: List<Txn>,
            savedPref: Long,
            livePermitted: Boolean,
        ): Long = computePostBalanceForCard(card, statements, txns, savedPref, livePermitted).postSpendPaise

        fun computePostBalanceForCard(
            card: Card,
            statements: List<Statement>,
            txns: List<Txn>,
            savedPref: Long,
            livePermitted: Boolean,
        ): PostBalance {
            if (!card.isCredit) return PostBalance(0L, 0L)
            val latestStmt = statements.filter { it.cardId == card.id }
                .maxWithOrNull(compareBy<Statement> { it.statementEpochDay }.thenBy { it.id })
            val cardLive = txns.filter { it.cardId == card.id && it.statementId == null }
            if (latestStmt != null) {
                val postLive = cardLive.filter { it.epochDay > latestStmt.statementEpochDay }
                if (cardLive.isNotEmpty() || livePermitted) {
                    val rawNet = postLive.sumOf { TxnKind.spendSign(it.kind, it.isCredit) * it.amountPaise }
                    return PostBalance(rawNet.coerceAtLeast(0L), (-rawNet).coerceAtLeast(0L))
                }
                return PostBalance(savedPref.coerceAtLeast(0L), 0L)
            }
            val cutoffEpochDay = if (card.billingSet) {
                val today = LocalDate.now()
                var ym = java.time.YearMonth.from(today)
                var cycleStmt = Billing.statementDate(ym, card.statementDay)
                if (cycleStmt.isAfter(today)) {
                    ym = ym.minusMonths(1)
                    cycleStmt = Billing.statementDate(ym, card.statementDay)
                }
                cycleStmt.toEpochDay()
            } else {
                Instant.ofEpochMilli(card.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1).toEpochDay()
            }
            val postCycle = cardLive.filter { it.epochDay > cutoffEpochDay }
            if (postCycle.isNotEmpty()) {
                val rawNet = postCycle.sumOf { TxnKind.spendSign(it.kind, it.isCredit) * it.amountPaise }
                return PostBalance(rawNet.coerceAtLeast(0L), (-rawNet).coerceAtLeast(0L))
            }
            if (livePermitted && cardLive.isNotEmpty()) return PostBalance(0L, 0L)
            return PostBalance(savedPref.coerceAtLeast(0L), 0L)
        }

        /**
         * Computes (totalPaidPaiseAgainstLatestStatement, postStatementUnbilledSpendPaise) from manual payment
         * entries and live SMS/Notification transactions after [Statement.statementEpochDay].
         * Manual [Payment] rows are matched 1-to-1 against post-statement SMS payments on amount within +-5 days
         * so a user tapping "Mark as paid" alongside a bank payment SMS never double-subtracts.
         */
        data class PostBalance(
            val postSpendPaise: Long,
            val postRefundSurplusPaise: Long,
        )

        data class PostStatementBreakdown(
            val paidPaise: Long,
            val postSpendPaise: Long,
            val postRefundSurplusPaise: Long,
        )

        @JvmOverloads
        fun computePostStatementBreakdown(
            latest: Statement,
            manualPayments: List<Payment>,
            liveTxns: List<Txn>,
            stmtLines: List<Txn> = emptyList(),
        ): PostStatementBreakdown {
            val cycleManual = manualPayments.filter { p ->
                p.cardId == latest.cardId && when {
                    latest.dueEpochDay != null && p.dueEpochDay > 0L ->
                        p.dueEpochDay == latest.dueEpochDay || kotlin.math.abs(p.dueEpochDay - latest.dueEpochDay) <= 10L
                    else -> p.paidOnEpochDay >= latest.statementEpochDay &&
                        (p.dueEpochDay <= 0L || p.dueEpochDay >= latest.statementEpochDay)
                }
            }
            val cardLive = liveTxns.filter { it.cardId == latest.cardId && it.statementId == null }
            val postLive = cardLive.filter { it.epochDay > latest.statementEpochDay }
            val postSmsPayments = cardLive.filter { t ->
                t.kind == TxnKind.PAYMENT &&
                    t.isCredit &&
                    when {
                        t.epochDay > latest.statementEpochDay -> true
                        t.epochDay == latest.statementEpochDay -> {
                            if (stmtLines.isNotEmpty()) {
                                stmtLines.none { s ->
                                    s.isCredit &&
                                        kotlin.math.abs(s.epochDay - t.epochDay) <= 2L &&
                                        kotlin.math.abs(s.amountPaise - t.amountPaise) <= 100L
                                }
                            } else {
                                latest.creditsPaise < t.amountPaise
                            }
                        }
                        else -> false
                    }
            }.toMutableList()

            var totalPaid = 0L
            for (mp in cycleManual) {
                totalPaid += mp.amountPaise
                if (mp.amountPaise > 0L) {
                    val matchedIdx = postSmsPayments.indexOfFirst { smsPay ->
                        kotlin.math.abs(smsPay.amountPaise - mp.amountPaise) <= 100L &&
                            kotlin.math.abs(smsPay.epochDay - mp.paidOnEpochDay) <= 7L
                    }
                    if (matchedIdx >= 0) postSmsPayments.removeAt(matchedIdx)
                }
            }
            totalPaid += postSmsPayments.sumOf { it.amountPaise }

            val rawNet = postLive.sumOf { TxnKind.spendSign(it.kind, it.isCredit) * it.amountPaise }
            return PostStatementBreakdown(
                paidPaise = totalPaid,
                postSpendPaise = rawNet.coerceAtLeast(0L),
                postRefundSurplusPaise = (-rawNet).coerceAtLeast(0L),
            )
        }
    }
}
