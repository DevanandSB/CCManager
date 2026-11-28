package com.devanandsb.ccmanager.reminders

import android.content.Context
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.domain.Billing
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class MissingStatementAlert(
    val card: Card,
    val statementDate: LocalDate,
    val gmailConnected: Boolean,
)

object MissingStatementChecker {
    private const val PREF_NAME = "missing_stmt_reminders"
    private const val KEY_PREFIX = "sent_"
    private const val PRUNE_DAYS = 60L

    /** Most recent statement date for [card] that is on or before [today]. */
    fun latestStatementDate(card: Card, today: LocalDate): LocalDate {
        val ym = YearMonth.from(today)
        val curr = Billing.statementDate(ym, card.statementDay)
        return if (curr.isAfter(today)) {
            Billing.statementDate(ym.minusMonths(1), card.statementDay)
        } else {
            curr
        }
    }

    /**
     * Returns cards that are >= 2 days past their latest statement date without a fetched/uploaded
     * statement for the current billing cycle and have not already been notified for that cycle.
     */
    fun findDueAlerts(
        cards: List<Card>,
        statements: List<Statement>,
        gmailConnected: Boolean,
        alreadyNotifiedKeys: Set<String>,
        today: LocalDate = LocalDate.now(),
    ): List<MissingStatementAlert> {
        return cards.mapNotNull { card ->
            if (!card.isCredit || card.archivedAt != null || !card.billingSet) return@mapNotNull null
            val stmtDate = latestStatementDate(card, today)
            if (today.isBefore(stmtDate.plusDays(2))) return@mapNotNull null

            val hasStatementForCycle = statements.any { s ->
                s.cardId == card.id &&
                    abs(ChronoUnit.DAYS.between(stmtDate, LocalDate.ofEpochDay(s.statementEpochDay))) <= 5L
            }
            if (hasStatementForCycle) return@mapNotNull null

            val key = dedupeKey(card.id, stmtDate.toEpochDay())
            if (key in alreadyNotifiedKeys) return@mapNotNull null

            MissingStatementAlert(
                card = card,
                statementDate = stmtDate,
                gmailConnected = gmailConnected,
            )
        }
    }

    fun dedupeKey(cardId: Long, statementEpochDay: Long): String = "${cardId}_$statementEpochDay"

    fun loadNotifiedKeys(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()
    }

    fun markNotified(
        context: Context,
        cardId: Long,
        statementEpochDay: Long,
        today: LocalDate = LocalDate.now(),
    ) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cutoff = today.toEpochDay() - PRUNE_DAYS
        val editor = prefs.edit()
        for ((k, v) in prefs.all) {
            if (k.startsWith(KEY_PREFIX) && (v as? Long ?: 0L) < cutoff) {
                editor.remove(k)
            }
        }
        editor.putLong("$KEY_PREFIX${dedupeKey(cardId, statementEpochDay)}", statementEpochDay)
        editor.apply()
    }
}
