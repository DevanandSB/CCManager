package com.devanandsb.ccmanager.reminders

import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.CardKind
import com.devanandsb.ccmanager.data.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MissingStatementCheckerTest {

    private fun sampleCard(
        id: Long = 1L,
        statementDay: Int = 10,
        billingSet: Boolean = true,
        kind: String = CardKind.CREDIT,
        archivedAt: Long? = null,
    ) = Card(
        id = id,
        nickname = "Test Card",
        issuer = "HDFC Bank",
        network = "Visa",
        last4 = "1234",
        statementDay = statementDay,
        dueOffsetDays = 18,
        color = 0xFF004C8F,
        kind = kind,
        billingSet = billingSet,
        archivedAt = archivedAt,
    )

    private fun sampleStatement(
        id: Long = 10L,
        cardId: Long = 1L,
        stmtDate: LocalDate,
        sourceMsgId: String = "msg-1",
    ) = Statement(
        id = id,
        cardId = cardId,
        statementEpochDay = stmtDate.toEpochDay(),
        periodStartEpochDay = null,
        dueEpochDay = stmtDate.plusDays(18).toEpochDay(),
        totalDuePaise = 100_000L,
        minDuePaise = 5_000L,
        openingPaise = null,
        debitsPaise = 100_000L,
        creditsPaise = 0L,
        status = "ok",
        sourceMsgId = sourceMsgId,
        fileHash = "hash-$id",
        parser = "Test",
        rawText = "",
    )

    @Test
    fun doesNotNotifyBeforeTwoDaysAfterStatementDate() {
        val card = sampleCard(statementDay = 10)
        // Statement generated Nov 10 -> +1 day (Nov 11) is < 2 days
        val alerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = emptyList(),
            gmailConnected = false,
            alreadyNotifiedKeys = emptySet(),
            today = LocalDate.of(2026, 11, 11),
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun notifiesAtTwoDaysWhenGmailNotConnectedAndNoStatement() {
        val card = sampleCard(statementDay = 10)
        val today = LocalDate.of(2026, 11, 12)
        val alerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = emptyList(),
            gmailConnected = false,
            alreadyNotifiedKeys = emptySet(),
            today = today,
        )
        assertEquals(1, alerts.size)
        assertEquals(LocalDate.of(2026, 11, 10), alerts[0].statementDate)
        assertFalse(alerts[0].gmailConnected)
    }

    @Test
    fun notifiesAtTwoDaysWhenGmailConnectedButCycleStatementMissing() {
        val card = sampleCard(statementDay = 10)
        // Only October statement exists; November 10 statement is still missing on Nov 12
        val prevStmt = sampleStatement(cardId = card.id, stmtDate = LocalDate.of(2026, 10, 10))
        val alerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = listOf(prevStmt),
            gmailConnected = true,
            alreadyNotifiedKeys = emptySet(),
            today = LocalDate.of(2026, 11, 12),
        )
        assertEquals(1, alerts.size)
        assertEquals(LocalDate.of(2026, 11, 10), alerts[0].statementDate)
        assertTrue(alerts[0].gmailConnected)
    }

    @Test
    fun doesNotNotifyWhenCurrentCycleStatementAlreadyPresent() {
        val card = sampleCard(statementDay = 10)
        // Statement shifted by 1 day (Nov 11) is still within +-5 days for the Nov 10 cycle
        val currStmt = sampleStatement(cardId = card.id, stmtDate = LocalDate.of(2026, 11, 11), sourceMsgId = "")
        val alertsNoGmail = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = listOf(currStmt),
            gmailConnected = false,
            alreadyNotifiedKeys = emptySet(),
            today = LocalDate.of(2026, 11, 14),
        )
        val alertsWithGmail = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = listOf(currStmt),
            gmailConnected = true,
            alreadyNotifiedKeys = emptySet(),
            today = LocalDate.of(2026, 11, 14),
        )
        assertTrue(alertsNoGmail.isEmpty())
        assertTrue(alertsWithGmail.isEmpty())
    }

    @Test
    fun deduplicatesPerBillingCycleAndAllowsNextCycle() {
        val card = sampleCard(statementDay = 10)
        val novStmtDate = LocalDate.of(2026, 11, 10)
        val dedupeSet = setOf(MissingStatementChecker.dedupeKey(card.id, novStmtDate.toEpochDay()))

        val sameCycleAlerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = emptyList(),
            gmailConnected = false,
            alreadyNotifiedKeys = dedupeSet,
            today = LocalDate.of(2026, 11, 18),
        )
        assertTrue(sameCycleAlerts.isEmpty())

        val nextCycleAlerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = emptyList(),
            gmailConnected = false,
            alreadyNotifiedKeys = dedupeSet,
            today = LocalDate.of(2026, 12, 12),
        )
        assertEquals(1, nextCycleAlerts.size)
        assertEquals(LocalDate.of(2026, 12, 10), nextCycleAlerts[0].statementDate)
    }

    @Test
    fun ignoresArchivedDebitAndUnconfiguredBillingCards() {
        val archived = sampleCard(id = 1L, archivedAt = 1_700_000_000_000L)
        val debit = sampleCard(id = 2L, kind = CardKind.DEBIT)
        val unsetBilling = sampleCard(id = 3L, billingSet = false)

        val alerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(archived, debit, unsetBilling),
            statements = emptyList(),
            gmailConnected = false,
            alreadyNotifiedKeys = emptySet(),
            today = LocalDate.of(2026, 11, 15),
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun handlesBeforeStatementDayByCheckingPreviousMonthCycle() {
        val card = sampleCard(statementDay = 25)
        // Today is Dec 2 (before Dec 25), so latest statement date is Nov 25 (7 days ago >= 2 days)
        val alerts = MissingStatementChecker.findDueAlerts(
            cards = listOf(card),
            statements = emptyList(),
            gmailConnected = false,
            alreadyNotifiedKeys = emptySet(),
            today = LocalDate.of(2026, 12, 2),
        )
        assertEquals(1, alerts.size)
        assertEquals(LocalDate.of(2026, 11, 25), alerts[0].statementDate)
    }
}
