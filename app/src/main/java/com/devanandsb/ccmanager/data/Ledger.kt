package com.devanandsb.ccmanager.data

import android.util.Base64
import com.devanandsb.ccmanager.ingest.Categorizer
import com.devanandsb.ccmanager.ingest.DefaultCategories
import com.devanandsb.ccmanager.ingest.PageLayout
import com.devanandsb.ccmanager.ingest.ParsedStatement
import com.devanandsb.ccmanager.ingest.Parsers
import com.devanandsb.ccmanager.ingest.TxnKind
import com.devanandsb.ccmanager.ingest.PdfProfile
import com.devanandsb.ccmanager.security.Keys
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** Non-auth key for the mail password and PDF profile: background sync must read them while locked. */
object MailCrypt {
    private fun key() = Keys.getOrCreate("duely_mail_v1", requireAuth = false)
    fun enc(s: String): ByteArray = Keys.encrypt(key(), s.toByteArray())
    fun dec(b: ByteArray?): String? = b?.let { String(Keys.decrypt(key(), it)) }
}

/**
 * Hardware-backed Android Keystore encryption for the PDF password profile (Name, DOB, PAN,
 * Mobile, custom patterns). Uses `requireAuth = true` whenever the device has a lock screen so
 * these secrets cannot be decrypted without biometric/PIN authorization within
 * [Keys.AUTH_WINDOW_SECONDS].
 */
object ProfileCrypt {
    private const val AUTH_ALIAS = "cc_pdf_profile_auth_v1"
    private const val OPEN_ALIAS = "cc_pdf_profile_open_v1"
    private val AUTH_TAG = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private val OPEN_TAG = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte())

    private fun authKey() = Keys.getOrCreate(AUTH_ALIAS, requireAuth = true)
    private fun openKey() = Keys.getOrCreate(OPEN_ALIAS, requireAuth = false)

    private fun hasTag(b: ByteArray, tag: ByteArray): Boolean =
        b.size > tag.size && b[0] == tag[0] && b[1] == tag[1] && b[2] == tag[2] && b[3] == tag[3]

    fun isAuthProtected(b: ByteArray?): Boolean = b != null && hasTag(b, AUTH_TAG)

    fun isLegacy(b: ByteArray?): Boolean = b != null && b.isNotEmpty() && !hasTag(b, AUTH_TAG) && !hasTag(b, OPEN_TAG)

    fun enc(s: String, requireAuth: Boolean): ByteArray {
        val plain = s.toByteArray()
        if (!requireAuth) {
            return OPEN_TAG + Keys.encrypt(openKey(), plain)
        }
        return try {
            AUTH_TAG + Keys.encrypt(authKey(), plain)
        } catch (e: Throwable) {
            if (Keys.isInvalidated(e)) {
                Keys.delete(AUTH_ALIAS)
                AUTH_TAG + Keys.encrypt(authKey(), plain)
            } else {
                throw e
            }
        }
    }

    fun dec(b: ByteArray?): String? {
        if (b == null || b.isEmpty()) return null
        return when {
            hasTag(b, AUTH_TAG) -> {
                val payload = b.copyOfRange(AUTH_TAG.size, b.size)
                val plain = try {
                    Keys.decrypt(authKey(), payload)
                } catch (e: Throwable) {
                    if (Keys.isInvalidated(e)) Keys.delete(AUTH_ALIAS)
                    throw e
                }
                String(plain)
            }
            hasTag(b, OPEN_TAG) -> String(Keys.decrypt(openKey(), b.copyOfRange(OPEN_TAG.size, b.size)))
            else -> MailCrypt.dec(b)
        }
    }
}

/** Statements, transactions, categories and the mail source. */
class Ledger(private val dao: LedgerDao, private val onCardChanged: suspend (Long) -> Unit) {

    val statements: Flow<List<Statement>> = dao.observeStatements()
    val txns: Flow<List<Txn>> = dao.observeTxns()
    val categories: Flow<List<Category>> = dao.observeCategories()
    val mailSources: Flow<List<MailSource>> = dao.observeMailSources()
    val importLog: Flow<List<ImportLog>> = dao.observeImportLog()

    fun statement(id: Long) = dao.observeStatement(id)
    fun linesFor(statementId: Long) = dao.observeTxnsFor(statementId)

    suspend fun ensureSeeded() {
        val existingCats = dao.categories().associateBy { it.name.lowercase() }.toMutableMap()
        val ids = mutableMapOf<String, Long>()
        for (cat in DefaultCategories.categories()) {
            val found = existingCats[cat.name.lowercase()]
            val id = found?.id ?: dao.insertCategory(cat)
            ids[cat.name] = id
        }
        val existingPatterns = dao.rules().map { it.pattern.lowercase() }.toSet()
        DefaultCategories.rules(ids).forEach { r ->
            if (r.pattern.lowercase() !in existingPatterns) dao.insertRule(r)
        }
    }

    suspend fun categorizer() = Categorizer(dao.rules(), dao.categories())

    // ---- Mail sources ----

    suspend fun connectedMailSourcesOnce(): List<MailSource> =
        dao.mailSources().filter { it.user.isNotBlank() && it.passwordEnc != null }

    suspend fun statementsOnce(): List<Statement> = dao.statements()

    suspend fun profileSourceOnce(): MailSource? = dao.profileCarrier() ?: dao.mailSource()

    suspend fun saveMailAccount(
        id: Int = 0,
        user: String,
        password: String?,
        host: String,
        port: Int,
        extraSenders: String,
        lookbackDays: Int,
        profile: PdfProfile? = null,
        requireProfileAuth: Boolean? = null,
    ) {
        val cleanUser = user.trim()
        val all = dao.mailSources()
        val old = (if (id > 0) all.firstOrNull { it.id == id } else null)
            ?: all.firstOrNull { it.user.equals(cleanUser, ignoreCase = true) }
            ?: all.firstOrNull { it.user.isBlank() && it.passwordEnc == null }
        val useAuth = requireProfileAuth ?: ProfileCrypt.isAuthProtected(old?.profileEnc)
        dao.upsertMailSource(
            MailSource(
                id = old?.id ?: 0,
                host = host.trim().ifBlank { "imap.gmail.com" },
                port = port,
                user = cleanUser,
                passwordEnc = password?.takeIf { it.isNotBlank() }?.let { MailCrypt.enc(it.replace(" ", "")) } ?: old?.passwordEnc,
                extraSenders = extraSenders.trim(),
                profileEnc = profile?.let { ProfileCrypt.enc(it.toJson(), requireAuth = useAuth) } ?: old?.profileEnc,
                lookbackDays = lookbackDays,
                lastSyncAt = old?.lastSyncAt ?: 0L,
                lastResult = old?.lastResult.orEmpty(),
            ),
        )
    }

    suspend fun recordSync(accountId: Int, result: String) {
        val m = dao.mailSources().firstOrNull { it.id == accountId } ?: return
        dao.upsertMailSource(
            MailSource(m.id, m.host, m.port, m.user, m.passwordEnc, m.extraSenders, m.profileEnc, m.lookbackDays, System.currentTimeMillis(), result),
        )
    }

    suspend fun recordSync(result: String) {
        val m = dao.mailSource() ?: return
        recordSync(m.id, result)
    }

    /** Removes or disconnects one email account while preserving the PDF-password profile. */
    suspend fun disconnectMailAccount(accountId: Int) {
        val all = dao.mailSources()
        val target = all.firstOrNull { it.id == accountId } ?: return
        val others = all.filter { it.id != accountId }
        if (others.isNotEmpty()) {
            if (target.profileEnc != null && others.none { it.profileEnc != null }) {
                val dst = others.first()
                dao.upsertMailSource(
                    MailSource(dst.id, dst.host, dst.port, dst.user, dst.passwordEnc, dst.extraSenders, target.profileEnc, dst.lookbackDays, dst.lastSyncAt, dst.lastResult),
                )
            }
            dao.deleteMailSourceById(accountId)
        } else {
            dao.upsertMailSource(MailSource(target.id, target.host, target.port, "", null, target.extraSenders, target.profileEnc, target.lookbackDays, 0, ""))
        }
    }

    fun hasSavedProfile(m: MailSource?): Boolean = m?.profileEnc?.isNotEmpty() == true

    fun profileRequiresAuth(m: MailSource?): Boolean = ProfileCrypt.isAuthProtected(m?.profileEnc)

    fun profileOrThrow(m: MailSource?): PdfProfile = PdfProfile.fromJson(ProfileCrypt.dec(m?.profileEnc))

    fun profile(m: MailSource?): PdfProfile = PdfProfile.fromJson(runCatching { ProfileCrypt.dec(m?.profileEnc) }.getOrNull())

    // ---- Import log ----

    suspend fun alreadySeen(key: String) = dao.logEntry(key) != null
    suspend fun log(entry: ImportLog) = dao.upsertLog(entry)
    suspend fun retryFailed() = dao.clearRetryableLog()
    suspend fun statementByHash(hash: String) = dao.statementByHash(hash)
    suspend fun latestStatementEpochDayFor(cardId: Long): Long? = dao.latestStatementEpochDay(cardId)
    suspend fun latestStatementForCard(cardId: Long): Statement? = dao.latestStatementForCard(cardId)
    suspend fun logEntry(key: String) = dao.logEntry(key)
    suspend fun statementSenders(): List<String> = dao.statementSenders().map { it.trim().lowercase() }.distinct()
    suspend fun dismiss(key: String) { dao.dismissLog(key); dao.dropPdf(key) }

    // ---- Sender -> card, learned from fixes ----

    suspend fun rememberSender(from: String, cardId: Long) {
        senderAddress(from)?.let { dao.upsertSenderCard(SenderCard(it, cardId)) }
    }
    suspend fun cardForSender(from: String): Long? = senderAddress(from)?.let { dao.cardForSender(it) }

    /** "HDFC Bank <statements@hdfcbank.net>" -> "statements@hdfcbank.net". */
    private fun senderAddress(from: String): String? =
        (Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from).trim().lowercase().takeIf { it.contains('@') }

    // ---- PDFs waiting for a password or a card ----

    val pendingKeys: Flow<List<String>> = dao.observePendingKeys()
    suspend fun keepPdf(key: String, fileName: String, bytes: ByteArray) = dao.keepPdf(PendingPdf(key, fileName, bytes))
    suspend fun pendingPdf(key: String) = dao.pendingPdf(key)
    suspend fun dropPdf(key: String) = dao.dropPdf(key)

    /** Saves the PDF-password profile on its own (works without an email account). */
    suspend fun saveProfile(profile: PdfProfile, requireAuth: Boolean? = null) {
        val m = profileSourceOnce() ?: MailSource()
        val useAuth = requireAuth ?: ProfileCrypt.isAuthProtected(m.profileEnc)
        dao.upsertMailSource(
            MailSource(m.id, m.host, m.port, m.user, m.passwordEnc, m.extraSenders, ProfileCrypt.enc(profile.toJson(), requireAuth = useAuth), m.lookbackDays, m.lastSyncAt, m.lastResult),
        )
    }

    /** Remembers a password that unlocked a statement, verifying unlock state so locked profiles are never overwritten. */
    suspend fun rememberPassword(pw: String, card: Card? = null, requireAuth: Boolean? = null, customLabel: String = "") {
        if (pw.isBlank()) return
        val m = profileSourceOnce()
        val p = profileOrThrow(m)
        val label = card?.let { "${it.nickname} · •• ${it.last4}" } ?: customLabel
        val updatedCardPws = p.cardPasswords.toMutableList()
        val existingIdx = updatedCardPws.indexOfFirst {
            it.password == pw && (card == null || it.cardId == null || it.cardId == card.id)
        }
        var changed = false
        if (existingIdx >= 0) {
            val prev = updatedCardPws[existingIdx]
            if (card != null && (prev.cardId != card.id || prev.cardLabel != label)) {
                updatedCardPws[existingIdx] = prev.copy(cardId = card.id, cardLabel = label)
                changed = true
            }
        } else {
            updatedCardPws += com.devanandsb.ccmanager.ingest.RememberedPdfPassword(
                password = pw,
                cardId = card?.id,
                cardLabel = label,
            )
            changed = true
        }
        val cleanedTemplates = if (card != null) p.templates.filterNot { it == pw } else p.templates
        if (changed || cleanedTemplates.size != p.templates.size) {
            val useAuth = (requireAuth ?: false) || ProfileCrypt.isAuthProtected(m?.profileEnc)
            saveProfile(p.copy(templates = cleanedTemplates, cardPasswords = updatedCardPws), requireAuth = useAuth)
        }
    }

    // ---- Statements ----

    /** Stores a parsed statement and its lines. Returns the new statement id. */
    suspend fun importStatement(
        card: Card, parsed: ParsedStatement, fallbackDate: Long, msgId: String, fileHash: String, parser: String, rawText: String,
    ): Statement {
        val stmtDay = (parsed.statementDate ?: java.time.LocalDate.ofEpochDay(fallbackDate)).toEpochDay()
        val (fixedOpening, limitNote) = limitChange(card.id, stmtDay, parsed)
        val v = Parsers.validate(parsed.copy(openingPaise = fixedOpening))
        val s = Statement(
            cardId = card.id,
            statementEpochDay = stmtDay,
            periodStartEpochDay = parsed.periodStart?.toEpochDay(),
            dueEpochDay = parsed.dueDate?.toEpochDay(),
            totalDuePaise = parsed.totalDuePaise,
            minDuePaise = parsed.minDuePaise,
            openingPaise = fixedOpening,
            debitsPaise = parsed.debits,
            creditsPaise = parsed.credits,
            status = if (v.ok) STATUS_OK else STATUS_REVIEW,
            note = if (v.ok) limitNote.orEmpty() else v.note,
            sourceMsgId = msgId,
            fileHash = fileHash,
            parser = parser,
            rawText = rawText,
            rewardPointsBalance = parsed.rewards?.pointsBalance,
            rewardPointsEarned = parsed.rewards?.pointsEarned,
            cashbackPaise = parsed.rewards?.cashbackPaise,
            rewardUnit = parsed.rewards?.unit,
            creditLimitPaise = parsed.creditLimitPaise,
        )
        // One transaction: a process killed mid-import must not leave a statement without lines (its file
        // hash would then block re-importing it).
        val id = dao.insertStatementWithLines(s, lines(null, card.id, parsed))
        afterStatementChange(card.id)
        return s.copy(id = id)
    }

    /**
     * If the only mismatch is a credit-limit change since the card's previous statement (the opening was
     * worked out against the new limit), correct the opening by that change and say so instead of flagging it.
     * Returns the opening to use and an explanatory note, if any.
     */
    private suspend fun limitChange(cardId: Long, stmtDay: Long, p: ParsedStatement): Pair<Long?, String?> {
        val opening = p.openingPaise ?: return null to null
        val total = p.totalDuePaise ?: return opening to null
        val limit = p.creditLimitPaise ?: return opening to null
        if (Parsers.validate(p).ok) return opening to null
        val prev = dao.statements()
            .filter { it.cardId == cardId && it.statementEpochDay < stmtDay && it.creditLimitPaise != null && it.creditLimitPaise > 0L }
            .maxByOrNull { it.statementEpochDay }?.creditLimitPaise ?: return opening to null
        val change = limit - prev
        if (change == 0L) return opening to null
        val diff = opening + p.debits - p.credits - total
        if (kotlin.math.abs(kotlin.math.abs(diff) - kotlin.math.abs(change)) > 100) return opening to null
        val note = "Credit limit changed from ${com.devanandsb.ccmanager.domain.Money.inr(prev)} to ${com.devanandsb.ccmanager.domain.Money.inr(limit)} this cycle"
        return (opening - diff) to note
    }

    private suspend fun writeLines(statementId: Long, cardId: Long, parsed: ParsedStatement) {
        dao.replaceStatementLines(statementId, lines(statementId, cardId, parsed))
    }

    /** Rows for [parsed]'s lines. [statementId] may be null when the statement isn't inserted yet. */
    private suspend fun lines(statementId: Long?, cardId: Long, parsed: ParsedStatement): List<Txn> {
        val cat = categorizer()
        val seen = HashMap<String, Int>()
        return parsed.lines.map { l ->
            val kind = TxnKind.classify(l.description, l.isCredit)
            val base = "$cardId|${l.date.toEpochDay()}|${l.amountPaise}|${l.isCredit}|${normal(l.description)}|${l.reference}"
            val n = seen.merge(base, 1, Int::plus)!!
            Txn(
                cardId = cardId, statementId = statementId, epochDay = l.date.toEpochDay(), merchant = l.description,
                amountPaise = l.amountPaise, isCredit = l.isCredit,
                categoryId = cat.categorise(l.description, categoryHint = l.categoryHint, mcc = l.mcc, kind = kind),
                reference = l.reference, dedupeKey = sha("$base|$n"), kind = kind,
            )
        }
    }

    /**
     * After a parser upgrade: statements that needed review are re-parsed in full; ones already OK
     * (possibly hand-edited) only get their reward fields filled in, so no edits are lost.
     */
    suspend fun refreshAfterParserUpgrade(issuerOf: (Long) -> String?) {
        val staleDateThreshold = java.time.LocalDate.of(2024, 6, 1).toEpochDay()
        for (s in dao.statements()) {
            val issuer = issuerOf(s.cardId) ?: continue
            val lines = dao.txnsFor(s.id)
            val maxTxnDay = lines.maxOfOrNull { it.epochDay }
            val hasStaleDate = s.statementEpochDay < staleDateThreshold || (maxTxnDay != null && s.statementEpochDay < maxTxnDay - 120L)
            val suspectLimit = s.creditLimitPaise != null && s.creditLimitPaise > 0 &&
                s.totalDuePaise == s.creditLimitPaise && s.debitsPaise < s.creditLimitPaise
            val isIcici = issuer.equals("ICICI Bank", ignoreCase = true) ||
                s.rawText.contains("icici", ignoreCase = true) ||
                s.rawText.contains("amazon pay", ignoreCase = true)
            if (s.status == STATUS_REVIEW || lines.isEmpty() || suspectLimit || hasStaleDate ||
                (isIcici && s.note != "Accepted after review")
            ) {
                reparse(s.id, issuer); continue
            }
            val p = runCatching { Parsers.resolve(issuer, s.rawText).parse(s.rawText) }.getOrNull() ?: continue
            val r = p.rewards
            dao.updateStatement(
                s.copy(
                    statementEpochDay = p.statementDate?.toEpochDay() ?: s.statementEpochDay,
                    dueEpochDay = p.dueDate?.toEpochDay() ?: s.dueEpochDay,
                    rewardPointsBalance = r?.pointsBalance, rewardPointsEarned = r?.pointsEarned, cashbackPaise = r?.cashbackPaise,
                    rewardUnit = r?.unit, creditLimitPaise = p.creditLimitPaise ?: s.creditLimitPaise,
                ),
            )
            afterStatementChange(s.cardId)
        }
    }

    /** Re-runs the parser offline on the stored text (or fresh [pages]/[updatedRawText]) and replaces the lines. */
    suspend fun reparse(
        id: Long,
        issuer: String,
        reassignCardId: Long? = null,
        pages: List<PageLayout> = emptyList(),
        updatedRawText: String? = null,
    ) {
        val s = dao.statement(id) ?: return
        val targetCardId = reassignCardId ?: s.cardId
        val activeRawText = updatedRawText?.takeIf { it.isNotBlank() } ?: s.rawText
        val parser = Parsers.resolve(issuer, activeRawText)
        val parsed = parser.parse(activeRawText, null, pages)
        val (fixedOpening, limitNote) = limitChange(targetCardId, parsed.statementDate?.toEpochDay() ?: s.statementEpochDay, parsed)
        val p = parsed.copy(openingPaise = fixedOpening)
        val v = Parsers.validate(p)
        dao.updateStatement(
            s.copy(
                cardId = targetCardId,
                periodStartEpochDay = p.periodStart?.toEpochDay() ?: s.periodStartEpochDay,
                statementEpochDay = p.statementDate?.toEpochDay() ?: s.statementEpochDay,
                dueEpochDay = p.dueDate?.toEpochDay(), totalDuePaise = p.totalDuePaise, minDuePaise = p.minDuePaise,
                openingPaise = p.openingPaise, debitsPaise = p.debits, creditsPaise = p.credits,
                status = if (v.ok) STATUS_OK else STATUS_REVIEW, note = if (v.ok) limitNote.orEmpty() else v.note,
                parser = parser.name,
                rawText = activeRawText,
                rewardPointsBalance = p.rewards?.pointsBalance, rewardPointsEarned = p.rewards?.pointsEarned,
                cashbackPaise = p.rewards?.cashbackPaise, rewardUnit = p.rewards?.unit, creditLimitPaise = p.creditLimitPaise,
            ),
        )
        writeLines(id, targetCardId, p)
        afterStatementChange(targetCardId)
        if (reassignCardId != null && reassignCardId != s.cardId) {
            afterStatementChange(s.cardId)
        }
    }

    /** Header edits from the review screen. Re-validates against the current lines. */
    suspend fun editStatementHeader(id: Long, due: Long?, total: Long?, min: Long?, opening: Long?) {
        val s = dao.statement(id) ?: return
        dao.updateStatement(s.copy(dueEpochDay = due, totalDuePaise = total, minDuePaise = min, openingPaise = opening))
        revalidate(id)
    }

    suspend fun markReviewed(id: Long) {
        val s = dao.statement(id) ?: return
        dao.updateStatement(s.copy(status = STATUS_OK, note = "Accepted after review"))
        afterStatementChange(s.cardId)
    }

    suspend fun deleteStatement(id: Long) {
        val s = dao.statement(id) ?: return
        dao.deleteStatement(id)
        afterStatementChange(s.cardId)
    }

    private suspend fun revalidate(statementId: Long) {
        val s = dao.statement(statementId) ?: return
        val lines = dao.txnsFor(statementId)
        val debits = lines.filter { !it.isCredit }.sumOf { it.amountPaise }
        val credits = lines.filter { it.isCredit }.sumOf { it.amountPaise }
        val v = Parsers.validate(s.totalDuePaise, s.dueEpochDay != null, s.openingPaise, debits, credits)
        val accepted = s.status == STATUS_OK && s.note == "Accepted after review"
        val status = if (v.ok || accepted) STATUS_OK else STATUS_REVIEW
        val note = when {
            v.ok -> ""
            accepted -> "Accepted after review"
            else -> v.note
        }
        dao.updateStatement(s.copy(debitsPaise = debits, creditsPaise = credits, status = status, note = note))
        afterStatementChange(s.cardId)
    }

    // ---- Transactions ----

    suspend fun saveLine(t: Txn) {
        if (t.id == 0L) {
            val key = sha("manual|${t.cardId}|${t.epochDay}|${t.amountPaise}|${System.nanoTime()}")
            dao.insertTxn(t.copy(dedupeKey = key, categoryId = t.categoryId ?: categorizer().categorise(t.merchant), kind = TxnKind.classify(t.merchant, t.isCredit)))
        } else {
            val existing = dao.txn(t.id)
            val reclassify = (existing != null && existing.isCredit != t.isCredit) || t.kind == TxnKind.SPEND || t.kind == TxnKind.REFUND
            dao.updateTxn(if (reclassify) t.copy(kind = TxnKind.classify(t.merchant, t.isCredit)) else t)
        }
        val stmtId = t.statementId
        if (stmtId != null) revalidate(stmtId) else afterStatementChange(t.cardId)
    }

    suspend fun deleteLine(t: Txn) {
        dao.deleteTxn(t.id)
        val stmtId = t.statementId
        if (stmtId != null) revalidate(stmtId) else afterStatementChange(t.cardId)
    }

    /** Sets one transaction's category; with [pattern], also saves a rule and applies it to all matching. */
    suspend fun setCategory(t: Txn, categoryId: Long?, pattern: String?) {
        dao.updateTxn(t.copy(categoryId = categoryId))
        val p = pattern?.trim()?.lowercase()
        if (!p.isNullOrBlank() && categoryId != null) {
            dao.deleteRulesFor(p)
            dao.insertRule(CategoryRule(categoryId = categoryId, pattern = p, priority = DefaultCategories.USER_PRIORITY))
            dao.recategorise(p, categoryId)
        }
    }

    /** Fills [Txn.kind] & missing categories for lines imported before kinds/MCC rules improved. */
    suspend fun reclassifyAll() {
        ensureSeeded()
        val cat = categorizer()
        for (t in dao.txns()) {
            val merchant = com.devanandsb.ccmanager.ingest.Text.stripEmiLink(t.merchant)
            val k = TxnKind.classify(merchant, t.isCredit)
            val improvedCat = cat.categorise(merchant, kind = k)
            val newCat = if (t.categoryId == null || merchant != t.merchant) (improvedCat ?: t.categoryId) else t.categoryId
            if (k != t.kind || merchant != t.merchant || newCat != t.categoryId) {
                dao.updateTxn(t.copy(kind = k, merchant = merchant, categoryId = newCat))
            }
        }
    }

    suspend fun clearAllLiveAlertTxns() = dao.deleteAllLiveAlertTxns()

    suspend fun syncCardsWithLatestStatements(cardIds: List<Long>) {
        for (id in cardIds) afterStatementChange(id)
    }

    suspend fun setNote(t: Txn, note: String) = dao.updateTxn(t.copy(note = note.trim()))

    /** Sets a line's kind by hand (e.g. a refund that was read as a payment). */
    suspend fun setKind(t: Txn, kind: String) {
        dao.updateTxn(t.copy(kind = kind))
        if (t.statementId == null) afterStatementChange(t.cardId)
    }

    private suspend fun afterStatementChange(cardId: Long) = onCardChanged(cardId)

    private fun normal(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun sha(s: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()), Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_REVIEW = "needs_review"
    }
}
