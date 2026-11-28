package com.devanandsb.ccmanager.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devanandsb.ccmanager.CardsApp
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Daily background check that reminds the user to upload a PDF statement once a card is
 * >= 2 days past its statement date and either Gmail is disconnected or no statement was
 * fetched/uploaded for the current billing cycle.
 */
class MissingStatementWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = CardsApp.from(applicationContext)
        val today = LocalDate.now()
        val cards = app.repo.allCards()
        val statements = app.repo.ledger.statementsOnce()
        val gmailConnected = app.repo.ledger.connectedMailSourcesOnce().isNotEmpty()
        val alreadyNotified = MissingStatementChecker.loadNotifiedKeys(applicationContext)

        val alerts = MissingStatementChecker.findDueAlerts(
            cards = cards,
            statements = statements,
            gmailConnected = gmailConnected,
            alreadyNotifiedKeys = alreadyNotified,
            today = today,
        )

        if (alerts.isNotEmpty()) {
            Notifications.createChannel(applicationContext)
            alerts.forEach { alert ->
                val shown = Notifications.showMissingStatement(
                    context = applicationContext,
                    card = alert.card,
                    statementDate = alert.statementDate,
                    gmailConnected = alert.gmailConnected,
                )
                if (shown) {
                    MissingStatementChecker.markNotified(
                        context = applicationContext,
                        cardId = alert.card.id,
                        statementEpochDay = alert.statementDate.toEpochDay(),
                        today = today,
                    )
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "missing_statement_check"

        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<MissingStatementWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
