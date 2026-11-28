package com.devanandsb.ccmanager

import android.app.Application
import android.content.Context
import com.devanandsb.ccmanager.data.AppDatabase
import com.devanandsb.ccmanager.data.Prefs
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.domain.BinLookup
import com.devanandsb.ccmanager.ingest.SyncWorker
import com.devanandsb.ccmanager.reminders.MissingStatementWorker
import com.devanandsb.ccmanager.reminders.Notifications
import com.devanandsb.ccmanager.reminders.Reminders
import com.devanandsb.ccmanager.reminders.SweepWorker
import com.devanandsb.ccmanager.security.DbKey
import com.devanandsb.ccmanager.statementparser.data.extractor.MlKitOcrFallbackExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CardsApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repo
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var db: AppDatabase
        private set

    private val upgradeMutex = Mutex()

    suspend fun ensureParserUpgraded() {
        if (prefs.parserVersion >= PARSER_VERSION) return
        upgradeMutex.withLock {
            if (prefs.parserVersion >= PARSER_VERSION) return@withLock
            val allCardsList = repo.allCards()
            val issuers = allCardsList.associateBy({ it.id }, { it.issuer })
            runCatching { repo.ledger.refreshAfterParserUpgrade { issuers[it] } }
            runCatching { repo.ledger.reclassifyAll() }
            runCatching { repo.ledger.clearAllLiveAlertTxns() }
            runCatching { repo.ledger.syncCardsWithLatestStatements(allCardsList.map { it.id }) }
            prefs.parserVersion = PARSER_VERSION
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Installs from before onboarding existed: a database already on disk means it isn't a first run.
        // Checked before the database is opened (opening creates it).
        if (!prefs.onboardedKnown) prefs.onboarded = getDatabasePath(AppDatabase.FILE).exists()
        db = AppDatabase.open(this, DbKey.passphrase(this))
        repo = Repo(db.dao(), db.ledger(), this)
        Notifications.createChannel(this)
        SweepWorker.enqueue(this)
        MissingStatementWorker.enqueue(this)
        scope.launch {
            repo.ledger.ensureSeeded()
            // Seed BIN table from bundled CSV on first run (no-op once populated).
            BinLookup.seed(this@CardsApp)
            MlKitOcrFallbackExtractor.prewarmModuleAsync(this@CardsApp)
            ensureParserUpgraded()
            Reminders.rescheduleAll(this@CardsApp)
            if (repo.ledger.connectedMailSourcesOnce().isNotEmpty()) SyncWorker.enable(this@CardsApp)
        }
    }

    companion object {
        /** Bump when parsers improve enough that stored statements & alerts should be refreshed. */
        const val PARSER_VERSION = 21
        fun from(context: Context) = context.applicationContext as CardsApp
    }
}
