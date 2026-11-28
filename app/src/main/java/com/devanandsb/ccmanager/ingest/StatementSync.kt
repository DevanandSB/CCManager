package com.devanandsb.ccmanager.ingest

import android.content.Context
import com.devanandsb.ccmanager.CardsApp
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.ImportLog
import com.devanandsb.ccmanager.data.Ledger
import com.devanandsb.ccmanager.data.MailCrypt
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.isCredit
import com.devanandsb.ccmanager.domain.Issuers
import com.devanandsb.ccmanager.reminders.Notifications
import com.devanandsb.ccmanager.reminders.Reminders
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

data class SyncState(val running: Boolean = false, val message: String = "")

/**
 * Mail -> PDF -> text -> parsed statement -> encrypted DB. Shared by the daily worker and the
 * manual "Sync now" button; a mutex keeps them from overlapping.
 */
object StatementSync {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    suspend fun run(context: Context): String = runDetailed(context).message

    /** [retryable]: the run failed on the network or mail server, so trying again later may work. */
    class RunResult(val message: String, val retryable: Boolean)

    suspend fun runDetailed(context: Context): RunResult = mutex.withLock {
        _state.value = SyncState(true, "Connecting…")
        try {
            val runOut = try {
                val res = withContext(Dispatchers.IO) { doRun(context) }
                RunResult(res.summary, res.retryable)
            } catch (e: CancellationException) {
                throw e
            } catch (_: javax.mail.AuthenticationFailedException) {
                RunResult("Sign-in failed. Check the email and app password.", false)
            } catch (e: Exception) {
                RunResult(
                    "Sync failed. Check your connection and try again.",
                    e is java.io.IOException || e is javax.mail.MessagingException,
                )
            }
            withContext(NonCancellable) {
                runCatching { CardsApp.from(context).repo.ledger.recordSync(runOut.message) }
            }
            _state.value = SyncState(false, runOut.message)
            runOut
        } finally {
            // Cancelled or failed while recording: never leave the UI stuck on "Connecting…".
            if (_state.value.running) _state.value = SyncState(false, "Sync stopped.")
        }
    }

    /** Connects and reports which folder would be searched. */
    suspend fun test(host: String, port: Int, user: String, password: String): String = withContext(Dispatchers.IO) {
        try {
            "Connected. Searching “${MailClient(host, port, user, password.replace(" ", "")).test()}”."
        } catch (e: javax.mail.AuthenticationFailedException) {
            "Sign-in failed. Gmail needs an app password, not your normal password."
        } catch (_: Exception) {
            "Couldn't connect. Check your email, app password, and internet connection."
        }
    }

    private data class SyncSummary(
        val summary: String,
        val retryable: Boolean = false,
        val authFailures: Int = 0,
    )

    private suspend fun doRun(context: Context): SyncSummary {
        val app = CardsApp.from(context)
        val ledger = app.repo.ledger
        ledger.ensureSeeded()
        val accounts = ledger.connectedMailSourcesOnce()
        if (accounts.isEmpty()) return SyncSummary("Email sync isn't set up.")

        val walletCards = app.repo.activeCards().filter { it.isCredit }
        if (walletCards.isEmpty()) return SyncSummary("Add a credit card to your wallet first.")

        val issuers = walletCards.map { Issuers.byName(it.issuer) }.distinct()
        val learned = ledger.statementSenders()

        Pdf.init(context)
        val profileSrc = ledger.profileSourceOnce()
        val profile = ledger.profile(profileSrc)

        var imported = 0
        var review = 0
        var locked = 0
        var skippedNotInWallet = 0
        var problems = 0
        var foundTotal = 0
        var authFailures = 0
        var retryableFailures = 0
        val batchLatestByCard = HashMap<Long, Pair<Card, Statement>>()

        accounts.forEachIndexed { index, src ->
            val password = MailCrypt.dec(src.passwordEnc)
            if (password == null || src.user.isBlank()) {
                ledger.recordSync(src.id, "Email or app password missing.")
                authFailures++
                return@forEachIndexed
            }
            val extra = src.extraSenders.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val matchingLearned = learned.filter { s -> issuers.any { iss -> iss.senders.any { s.contains(it) } } || s in extra }
            val exact = (issuers.flatMap { it.statementFrom } + matchingLearned + extra.filter { it.contains('@') }).distinct()
            val domains = (issuers.flatMap { it.senders } + extra.filterNot { it.contains('@') }).distinct()
            if (exact.isEmpty() && domains.isEmpty()) {
                ledger.recordSync(src.id, "No known statement senders for wallet cards.")
                return@forEachIndexed
            }

            val since = Date(System.currentTimeMillis() - src.lookbackDays * 86_400_000L)
            val prefix = if (accounts.size > 1) "${src.user} (${index + 1}/${accounts.size}): " else ""
            _state.value = SyncState(true, "${prefix}Searching mail…")

            var acctImported = 0
            var acctReview = 0
            var acctLocked = 0
            var acctSkipped = 0
            var acctProblems = 0

            try {
                val client = MailClient(src.host, src.port, src.user, password)
                val found = client.fetchStatements(since, exact, domains, skip = { key -> ledger.alreadySeen(key) }) { i, total, mail, failure ->
                    _state.value = SyncState(true, "${prefix}Reading statement ${i + 1} of $total…")
                    val res = if (failure != null) {
                        logError(ledger, mail, failure) to null
                    } else {
                        processSafelyWithStatement(context, ledger, walletCards, profile, mail)
                    }
                    res.second?.let { (card, stmt) ->
                        val prev = batchLatestByCard[card.id]?.second
                        if (prev == null || stmt.statementEpochDay >= prev.statementEpochDay) {
                            batchLatestByCard[card.id] = card to stmt
                        }
                    }
                    when (res.first) {
                        "imported" -> acctImported++
                        "needs_review" -> acctReview++
                        "locked" -> acctLocked++
                        "not_in_wallet" -> acctSkipped++
                        "duplicate" -> {}
                        else -> acctProblems++
                    }
                }
                foundTotal += found
                imported += acctImported
                review += acctReview
                locked += acctLocked
                skippedNotInWallet += acctSkipped
                problems += acctProblems
                val acctMsg = formatBatchSummary(found, acctImported, acctReview, acctLocked, acctSkipped, acctProblems)
                ledger.recordSync(src.id, acctMsg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: javax.mail.AuthenticationFailedException) {
                authFailures++
                ledger.recordSync(src.id, "Sign-in failed. Check email and app password.")
            } catch (e: Exception) {
                val netErr = e is java.io.IOException || e is javax.mail.MessagingException
                if (netErr) retryableFailures++ else problems++
                ledger.recordSync(
                    src.id,
                    "Sync failed. Check your connection and try again.",
                )
            }
        }

        // Notify at most ONCE per wallet card after all accounts finish, and only for its newest non-stale statement.
        val today = LocalDate.now()
        for ((card, stmt) in batchLatestByCard.values) {
            val newestStoredDay = ledger.latestStatementEpochDayFor(card.id)
            val dueDate = stmt.dueEpochDay?.let { LocalDate.ofEpochDay(it) }
            val isLatest = newestStoredDay == null || stmt.statementEpochDay >= newestStoredDay
            val isCurrentOrUpcoming = dueDate == null || !dueDate.isBefore(today.minusDays(1))
            if (isLatest && isCurrentOrUpcoming) {
                Reminders.suppressTodayDuplicate(context, card, stmt.dueEpochDay)
                Notifications.showStatement(context, card, stmt)
            }
        }

        if (authFailures == accounts.size) {
            val msg = if (accounts.size == 1) "Sign-in failed. Check the email and app password."
            else "Sign-in failed for all $authFailures connected Gmail accounts."
            return SyncSummary(msg, retryable = false, authFailures = authFailures)
        }
        val summary = buildString {
            append(formatBatchSummary(foundTotal, imported, review, locked, skippedNotInWallet, problems))
            if (authFailures > 0) append(" ($authFailures account sign-in failed.)")
        }
        return SyncSummary(
            summary = summary,
            retryable = retryableFailures > 0 && imported == 0 && foundTotal == 0,
            authFailures = authFailures,
        )
    }

    private fun formatBatchSummary(
        found: Int,
        imported: Int,
        review: Int,
        locked: Int,
        skippedNotInWallet: Int,
        problems: Int,
    ): String {
        if (found == 0) return "No new statements."
        val parts = buildList {
            if (imported > 0) add("$imported imported")
            if (review > 0) add("$review need review")
            if (locked > 0) add("$locked need password")
            if (skippedNotInWallet > 0) add("$skippedNotInWallet skipped (not in wallet)")
            if (problems > 0) add("$problems couldn't be read")
        }
        return if (parts.isEmpty()) "Already up to date." else parts.joinToString(", ") + "."
    }

    /** Result of fixing or importing one PDF: the outcome code and a sentence for the UI. */
    data class Outcome(val code: String, val message: String, val statementId: Long? = null, val key: String = "")

    /** Retries a kept PDF with a typed password and/or a chosen card. */
    suspend fun fix(
        context: Context,
        key: String,
        password: String?,
        cardId: Long?,
        remember: Boolean,
        applyToBank: Boolean = remember,
        strictPreferredCard: Boolean = false,
    ): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = CardsApp.from(context)
            val ledger = app.repo.ledger
            val e = ledger.logEntry(key) ?: return@withContext Outcome("error", "That statement isn't in the list any more.")
            val pdf = ledger.pendingPdf(key) ?: return@withContext Outcome("error", "The PDF isn't stored any more. Please upload it again.")
            Pdf.init(context)
            ledger.ensureSeeded()
            val cards = app.repo.activeCards().filter { it.isCredit }
            val mail = MailPdf(e.key, e.msgId, e.subject, e.sender, e.receivedAt, pdf.fileName, pdf.bytes)
            val pw = password?.trim()?.takeIf { it.isNotEmpty() }
            val mailSrc = ledger.profileSourceOnce()
            val profBlocked = ledger.profileRequiresAuth(mailSrc) && runCatching { ledger.profileOrThrow(mailSrc) }.isFailure
            val prof = if (profBlocked) PdfProfile() else ledger.profile(mailSrc)
            val forced = if (strictPreferredCard) null else cards.firstOrNull { it.id == cardId }
            var r = processOne(context, ledger, cards, prof, mail, listOfNotNull(pw), forced, notifyImmediately = true)
            if (strictPreferredCard && r.outcome.code == "unmatched" && cardId != null) {
                val pref = cards.firstOrNull { it.id == cardId }
                if (pref != null) {
                    r = processOne(context, ledger, cards, prof, mail, listOfNotNull(pw), pref, notifyImmediately = true)
                }
            }
            val matchedCard = r.card ?: cards.firstOrNull { it.id == cardId }
            if (pw != null && (remember || applyToBank) && r.outcome.code != "locked") {
                val saveCard = if (remember) matchedCard else null
                val bankLabel = matchedCard?.issuer?.takeIf { it.isNotBlank() && it != "Other" }
                if (saveCard != null) {
                    ledger.rememberPassword(pw, card = saveCard, requireAuth = com.devanandsb.ccmanager.security.Auth.deviceSecure(context))
                } else if (applyToBank) {
                    val label = bankLabel?.let { "$it statements" }.orEmpty()
                    ledger.rememberPassword(
                        pw,
                        card = null,
                        customLabel = label,
                        requireAuth = com.devanandsb.ccmanager.security.Auth.deviceSecure(context),
                    )
                }
            }
            if (cardId != null && r.outcome.code in setOf("imported", "needs_review") && e.sender.isNotBlank()) {
                ledger.rememberSender(e.sender, cardId)
            }
            r.outcome
        }
    }

    /** Imports a PDF picked from Files or shared from another app, through the same pipeline as email. */
    suspend fun importFile(context: Context, fileName: String, bytes: ByteArray): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = CardsApp.from(context)
            val ledger = app.repo.ledger
            Pdf.init(context)
            ledger.ensureSeeded()
            val cards = app.repo.activeCards().filter { it.isCredit }
            if (cards.isEmpty()) return@withContext Outcome("error", "Add a credit card first.")
            val mail = MailPdf("file:" + Pdf.sha256(bytes), "", fileName, "", System.currentTimeMillis(), fileName, bytes)
            val mailSrc = ledger.profileSourceOnce()
            val profBlocked = ledger.profileRequiresAuth(mailSrc) && runCatching { ledger.profileOrThrow(mailSrc) }.isFailure
            val prof = if (profBlocked) PdfProfile() else ledger.profile(mailSrc)
            val r = processOne(context, ledger, cards, prof, mail, notifyImmediately = true)
            if (r.outcome.code == "locked" && profBlocked) {
                return@withContext Outcome("auth_required", "Verify screen lock to try saved statement passwords.", key = mail.key)
            }
            r.outcome
        }
    }

    private data class ProcessResult(val outcome: Outcome, val card: Card? = null, val statement: Statement? = null)

    /**
     * [processOne] for the sync loop: a PDF that throws (parser bug, out of memory) is logged as an error so
     * it isn't fetched again on every sync (Retry failed brings it back), and the rest of the batch carries on.
     */
    private suspend fun processSafelyWithStatement(
        context: Context,
        ledger: Ledger,
        cards: List<Card>,
        profile: PdfProfile,
        mail: MailPdf,
    ): Pair<String, Pair<Card, Statement>?> =
        try {
            val r = processOne(context, ledger, cards, profile, mail, notifyImmediately = false)
            val pair = if (r.card != null && r.statement != null) r.card to r.statement else null
            r.outcome.code to pair
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logError(ledger, mail, "Couldn't read this PDF.") to null
        } catch (_: OutOfMemoryError) {
            logError(ledger, mail, "Too large to read on this phone") to null
        }

    private suspend fun logError(ledger: Ledger, mail: MailPdf, detail: String): String {
        ledger.log(ImportLog(mail.key, mail.msgId, mail.subject, mail.from, mail.receivedAt, "error", detail))
        return "error"
    }

    private suspend fun processOne(
        context: Context,
        ledger: Ledger,
        cards: List<Card>,
        profile: PdfProfile,
        mail: MailPdf,
        extraPasswords: List<String> = emptyList(),
        forceCard: Card? = null,
        notifyImmediately: Boolean = true,
    ): ProcessResult {
        suspend fun done(outcome: String, detail: String, message: String, statementId: Long? = null, keep: Boolean = false): ProcessResult {
            ledger.log(ImportLog(mail.key, mail.msgId, mail.subject, mail.from, mail.receivedAt, outcome, detail, statementId))
            if (keep) ledger.keepPdf(mail.key, mail.fileName, mail.bytes) else ledger.dropPdf(mail.key)
            return ProcessResult(Outcome(outcome, message, statementId, mail.key))
        }

        val hash = Pdf.sha256(mail.bytes)
        val existingByHash = ledger.statementByHash(hash)
        if (existingByHash != null && !mail.key.startsWith("file:")) {
            return done("duplicate", "Already imported", "Already imported.")
        }

        // A card you picked for this sender before wins over guessing by bank.
        val remembered = if (forceCard == null) ledger.cardForSender(mail.from)?.let { id -> cards.firstOrNull { it.id == id } } else null
        val issuer = (forceCard ?: remembered)?.let { Issuers.byName(it.issuer) } ?: Issuers.bySender(mail.from)
        val issuerCards = forceCard?.let { listOf(it) } ?: cards.filter { issuer != null && Issuers.sameIssuer(it.issuer, issuer.name) }.ifEmpty { cards }
        val candidates = extraPasswords + Passwords.candidates(profile, issuerCards.map { it.last4 }.distinct())

        val (text, pages) = when (
            val r = Pdf.extractWithOcrFallback(
                context = context,
                bytes = mail.bytes,
                candidates = candidates,
                hasExplicitAttempt = extraPasswords.isNotEmpty(),
            )
        ) {
            is Pdf.Result.Text -> r.text to r.pages
            Pdf.Result.Locked -> return done(
                "locked",
                if (extraPasswords.isEmpty()) "Password-protected. Tap to enter the password." else "That password didn't work.",
                if (extraPasswords.isEmpty()) "It's password-protected." else "That password didn't work.",
                keep = true,
            )
            is Pdf.Result.Failed -> return done("error", r.reason, "Couldn't open the PDF.")
        }
        if (text.isBlank()) return done("unparsed", "No text in the PDF (scanned image?)", "There's no text in this PDF.")

        // Mail date anchors statements that print dates without a year; a picked file has no useful date.
        val received = if (mail.key.startsWith("file:")) null
        else java.time.Instant.ofEpochMilli(mail.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val emailSummary = if (mail.bodyText.isNotBlank()) EmailSummaryParser.parse(mail.bodyText, received) else null

        // Extract all candidate masked card endings BEFORE parsing full statement lines so statements
        // for cards not in the user's wallet are never parsed, stored, or notified, and cards never mix.
        val pdfIssuer = Issuers.detect(text)
        val senderIssuer = Issuers.bySender(mail.from)?.name
        val pdfLast4s = Text.allLast4s(text)
        val candidateLast4s = pdfLast4s.ifEmpty { listOfNotNull(emailSummary?.last4) }
        val effIssuer = pdfIssuer ?: senderIssuer
        val safeRemembered = remembered?.takeIf { c ->
            (effIssuer == null || Issuers.sameIssuer(c.issuer, effIssuer)) &&
                (candidateLast4s.isEmpty() || c.last4 in candidateLast4s)
        }

        if (existingByHash != null && mail.key.startsWith("file:")) {
            val matchedForHash = forceCard ?: matchWalletCardStrict(cards, pdfIssuer, senderIssuer, candidateLast4s, text, safeRemembered)
            val targetCard = matchedForHash ?: cards.firstOrNull { it.id == existingByHash.cardId }
            if (targetCard != null) {
                ledger.reparse(
                    id = existingByHash.id,
                    issuer = targetCard.issuer,
                    reassignCardId = targetCard.id,
                    pages = pages,
                    updatedRawText = text,
                )
                ledger.syncCardsWithLatestStatements(listOf(targetCard.id, existingByHash.cardId).distinct())
                return ProcessResult(Outcome("imported", "Refreshed ${targetCard.nickname} (•• ${targetCard.last4}) offline.", existingByHash.id, mail.key), targetCard)
            }
            return done("duplicate", "Already imported", "Already imported.", existingByHash.id)
        }

        val card = forceCard ?: matchWalletCardStrict(cards, pdfIssuer, senderIssuer, candidateLast4s, text, safeRemembered)
        if (card == null) {
            val hintBank = (pdfIssuer ?: senderIssuer)?.let { "$it " } ?: ""
            val hintLast4 = candidateLast4s.firstOrNull()
            // For email statements (or manual files whose issuer/last4 clearly do not exist in the wallet),
            // stop here without parsing transactions or sending due-date notifications.
            val manualAmbiguous = mail.key.startsWith("file:") &&
                candidateLast4s.isEmpty() &&
                (pdfIssuer == null || cards.any { Issuers.sameIssuer(it.issuer, pdfIssuer) })
            return if (manualAmbiguous) {
                done(
                    "unmatched",
                    "Couldn't tell which ${hintBank}card this is for. Tap to pick it.",
                    "Couldn't tell which card this is for.",
                    keep = true,
                )
            } else {
                done(
                    "not_in_wallet",
                    "Card $hintBank${hintLast4?.let { "•• $it " } ?: ""}not in wallet — skipped without parsing.",
                    "Card $hintBank${hintLast4?.let { "•• $it " } ?: ""}is not in your wallet.",
                    keep = false,
                )
            }
        }

        val parser = Parsers.resolve(card.issuer, text)
        val parsed = Parsers.mergeWithEmailSummary(parser.parse(text, received, pages), emailSummary, text)

        val saved = ledger.importStatement(
            card, parsed, java.time.Instant.ofEpochMilli(mail.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay(),
            mail.msgId, hash, parser.name, text,
        )

        ledger.syncCardsWithLatestStatements(listOf(card.id))
        val ok = saved.status == Ledger.STATUS_OK
        if (notifyImmediately) {
            val newestStoredDay = ledger.latestStatementEpochDayFor(card.id)
            val dueDate = saved.dueEpochDay?.let { LocalDate.ofEpochDay(it) }
            val isLatest = newestStoredDay == null || saved.statementEpochDay >= newestStoredDay
            val isCurrentOrUpcoming = dueDate == null || !dueDate.isBefore(LocalDate.now().minusDays(1))
            if (isLatest && isCurrentOrUpcoming) {
                Reminders.suppressTodayDuplicate(context, card, saved.dueEpochDay)
                Notifications.showStatement(context, card, saved)
            }
        }
        val detailMsg = if (ok) "${parsed.lines.size} lines" else saved.note
        val outcome = Outcome(
            if (ok) "imported" else "needs_review",
            if (ok) "Imported to ${card.nickname} (•• ${card.last4})." else "Imported to ${card.nickname} (•• ${card.last4}), check details.",
            saved.id,
            mail.key,
        )
        ledger.log(ImportLog(mail.key, mail.msgId, mail.subject, mail.from, mail.receivedAt, outcome.code, detailMsg, saved.id))
        ledger.dropPdf(mail.key)
        return ProcessResult(outcome, card, saved)
    }

    /**
     * Strictly matches a PDF statement to an active credit card in the wallet without ever falling back
     * to another bank or another card variant of the same bank when last-4 digits disagree.
     */
    internal fun matchWalletCardStrict(
        walletCards: List<Card>,
        pdfIssuer: String?,
        senderIssuer: String?,
        candidateLast4s: List<String>,
        text: String,
        rememberedCard: Card? = null,
    ): Card? {
        if (walletCards.isEmpty()) return null
        val detectedIssuer = pdfIssuer ?: senderIssuer
        var pool = if (detectedIssuer != null) {
            walletCards.filter { Issuers.sameIssuer(it.issuer, detectedIssuer) }
        } else {
            walletCards
        }
        // If pdfIssuer and senderIssuer disagree (e.g. a secondary bank name appeared inside the PDF),
        // also check senderIssuer if the wallet has a card from senderIssuer whose last4 matches the PDF.
        if (pool.isEmpty() && senderIssuer != null && pdfIssuer != null && !Issuers.sameIssuer(senderIssuer, pdfIssuer)) {
            val senderPool = walletCards.filter { Issuers.sameIssuer(it.issuer, senderIssuer) }
            val senderMatchesLast4 = candidateLast4s.isNotEmpty() && senderPool.any { it.last4 in candidateLast4s }
            if (senderMatchesLast4) pool = senderPool
        }
        // If we know the statement's bank and the user has no wallet card from that bank, reject immediately.
        if (detectedIssuer != null && pool.isEmpty()) return null

        if (candidateLast4s.isNotEmpty()) {
            val matched = candidateLast4s.mapNotNull { l4 -> pool.singleOrNull { it.last4 == l4 } }.distinctBy { it.id }
            if (matched.size == 1) return matched[0]
            if (matched.size > 1) {
                val headerLast4s = Text.allLast4s(text.lines().take(35).joinToString("\n"))
                val headerMatched = headerLast4s.mapNotNull { l4 -> matched.singleOrNull { it.last4 == l4 } }.distinctBy { it.id }
                if (headerMatched.size == 1) return headerMatched[0]
                if (rememberedCard != null && matched.any { it.id == rememberedCard.id }) return rememberedCard
            }
            return null
        }

        // Only when the PDF exposes zero masked last-4 patterns do we inspect standalone digits / single-card issuer.
        val headerText = text.lines().take(35).joinToString("\n")
        val headerMentions = pool.filter { c -> Regex("(?<!\\d)${c.last4}(?!\\d)").containsMatchIn(headerText) }
        if (headerMentions.size == 1) return headerMentions[0]
        val allMentions = pool.filter { c -> Regex("(?<!\\d)${c.last4}(?!\\d)").containsMatchIn(text) }
        if (allMentions.size == 1) return allMentions[0]
        if (pdfIssuer != null && pool.size == 1) return pool[0]
        if (rememberedCard != null && pool.any { it.id == rememberedCard.id }) return rememberedCard
        return null
    }
}
