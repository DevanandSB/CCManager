package com.devanandsb.ccmanager.ingest

import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.IMAPResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/** A PDF attachment from a statement-looking email, paired with extracted email body text. */
class MailPdf(
    val key: String,
    val msgId: String,
    val subject: String,
    val from: String,
    val receivedAt: Long,
    val fileName: String,
    val bytes: ByteArray,
    val bodyText: String = "",
)

/**
 * Read-only IMAP over TLS. Opens folders read-only, so nothing is ever marked read or moved.
 *
 * Search is two-level and runs on the server:
 *  1. exact statement sender addresses (built-in list + ones learned from past imports);
 *  2. the banks' domains, but only mails whose subject looks like a statement.
 * On Gmail both use Gmail's own search (X-GM-RAW) with has:attachment filename:pdf, so only statement
 * mails with a PDF come back, not every alert and OTP from the bank. Headers and structure for all hits
 * are fetched in one round trip; only the PDF parts are downloaded.
 */
class MailClient(private val host: String, private val port: Int, private val user: String, private val password: String) {

    private inline fun <T> withStore(block: (Store) -> T): T {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "60000")
            put("mail.imaps.fetchsize", "1048576")
            put("mail.imaps.partialfetch", "false") // fetch each PDF part in one go
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(host, port, user, password)
        try {
            return block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    /** Discovers INBOX, All Mail, Spam/Junk, and Bin/Trash folders that exist on this account. */
    private fun statementFolders(store: Store): List<IMAPFolder> {
        val listed = runCatching { store.defaultFolder.list("*") }.getOrDefault(emptyArray())
            .filterIsInstance<IMAPFolder>()
            .filter { runCatching { (it.type and Folder.HOLDS_MESSAGES) != 0 }.getOrDefault(true) }

        val picked = LinkedHashMap<String, IMAPFolder>()
        fun addIfReadable(f: Folder?) {
            val im = f as? IMAPFolder ?: return
            if (!runCatching { im.exists() }.getOrDefault(false)) return
            picked.putIfAbsent(im.fullName.lowercase(Locale.US), im)
        }

        // 1. Primary Inbox + Archive / All Mail
        addIfReadable(runCatching { store.getFolder("INBOX") }.getOrNull())
        listed.firstOrNull { f -> f.attributes.any { it.equals("\\All", true) } }?.let(::addIfReadable)

        // 2. Spam / Junk folders
        listed.filter { f ->
            f.attributes.any { it.equals("\\Junk", true) } ||
                Regex("\\b(?:spam|junk|bulk)\\b", RegexOption.IGNORE_CASE).containsMatchIn(f.fullName)
        }.forEach(::addIfReadable)
        listOf("[Gmail]/Spam", "[Google Mail]/Spam", "Spam", "Junk").forEach { name ->
            addIfReadable(runCatching { store.getFolder(name) }.getOrNull())
        }

        // 3. Bin / Trash folders
        listed.filter { f ->
            f.attributes.any { it.equals("\\Trash", true) } ||
                Regex("\\b(?:bin|trash|deleted)\\b", RegexOption.IGNORE_CASE).containsMatchIn(f.fullName)
        }.forEach(::addIfReadable)
        listOf("[Gmail]/Bin", "[Gmail]/Trash", "[Google Mail]/Bin", "[Google Mail]/Trash", "Bin", "Trash").forEach { name ->
            addIfReadable(runCatching { store.getFolder(name) }.getOrNull())
        }

        return picked.values.toList()
    }

    private fun isSpamOrBin(folder: IMAPFolder): Boolean =
        folder.attributes.any { it.equals("\\Junk", true) || it.equals("\\Trash", true) } ||
            Regex("\\b(?:spam|junk|bulk|bin|trash|deleted)\\b", RegexOption.IGNORE_CASE).containsMatchIn(folder.fullName)

    /** Returns the folders that will be searched. Throws on bad credentials. */
    fun test(): String = withStore { store ->
        val folders = statementFolders(store)
        if (folders.isEmpty()) "INBOX" else folders.joinToString(", ") { it.name }
    }

    /**
     * Finds statement PDFs since [since] across INBOX, All Mail, Spam, and Bin/Trash.
     * [exact] are full sender addresses (level 1); [domains] are domain words for subject-filtered level 2.
     * [skip] avoids downloading attachments already handled. PDFs are deduplicated across folders by
     * message-id + filename and downloaded one at a time. Returns how many new PDFs were handled.
     */
    suspend fun fetchStatements(
        since: Date, exact: List<String>, domains: List<String>,
        skip: suspend (String) -> Boolean,
        each: suspend (index: Int, total: Int, pdf: MailPdf, failure: String?) -> Unit,
    ): Int = withStore { store ->
        val gmail = (store as? IMAPStore)?.hasCapability("X-GM-EXT-1") == true
        val folders = statementFolders(store).ifEmpty {
            listOfNotNull(store.getFolder("INBOX") as? IMAPFolder)
        }
        val seenKeys = HashSet<String>()
        var totalHandled = 0

        for (folder in folders) {
            val opened = runCatching { folder.open(Folder.READ_ONLY); true }.getOrDefault(false)
            if (!opened) continue
            try {
                var messages: List<Message> = if (gmail) {
                    runCatching { gmailSearch(folder, since, exact, domains) }.getOrDefault(emptyList())
                } else {
                    runCatching { imapSearch(folder, since, exact, domains) }.getOrDefault(emptyList())
                }
                if (gmail && messages.isEmpty() && isSpamOrBin(folder)) {
                    messages = runCatching { imapSearch(folder, since, exact, domains) }.getOrDefault(emptyList())
                }
                if (messages.isEmpty()) continue

                // One round trip for envelope + structure of every hit in this folder.
                folder.fetch(
                    messages.toTypedArray(),
                    FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID) },
                )

                val hits = mutableListOf<Triple<MailPdf, Part, Message>>()
                for (m in messages) {
                    val subject = runCatching { m.subject }.getOrNull().orEmpty()
                    val msgId = (m as? MimeMessage)?.messageID?.takeIf { it.isNotBlank() }
                        ?: "${user.lowercase(Locale.US)}:${folder.fullName}:${folder.getUID(m)}"
                    val from = (m.from?.firstOrNull() as? InternetAddress)?.address ?: m.from?.firstOrNull()?.toString().orEmpty()
                    val received = (m.receivedDate ?: m.sentDate ?: Date()).time
                    for ((name, part) in pdfParts(m)) {
                        if (!looksLikeStatement(subject, name)) continue
                        val key = "$msgId#$name"
                        if (!seenKeys.add(key) || skip(key)) continue
                        hits += Triple(MailPdf(key, msgId, subject, from, received, name, EMPTY), part, m)
                    }
                }

                for ((i, hit) in hits.withIndex()) {
                    val (meta, part, msg) = hit
                    var failure: String? = null
                    var bodyText = ""
                    val bytes = try {
                        val size = runCatching { part.size }.getOrDefault(-1)
                        if (size > MAX_PDF_BYTES) {
                            failure = "Attachment too large (${size / 1_048_576} MB)"; EMPTY
                        } else {
                            bodyText = runCatching { extractBodyText(msg) }.getOrDefault("")
                            part.inputStream.use { it.readBytes() }
                        }
                    } catch (_: OutOfMemoryError) {
                        failure = "Attachment too large to read on this phone"; EMPTY
                    } catch (e: Exception) {
                        if (!folder.isOpen || !store.isConnected) throw e
                        failure = "Couldn't download the PDF"; EMPTY
                    }
                    each(
                        totalHandled + i,
                        totalHandled + hits.size,
                        if (failure == null) MailPdf(meta.key, meta.msgId, meta.subject, meta.from, meta.receivedAt, meta.fileName, bytes, bodyText) else meta,
                        failure,
                    )
                }
                totalHandled += hits.size
            } finally {
                runCatching { folder.close(false) }
            }
        }
        totalHandled
    }

    private fun looksLikeStatement(subject: String, fileName: String) = listOf(subject, fileName).any {
        val s = it.lowercase()
        KEYWORDS.any { k -> s.contains(k) }
    }

    // ---- Gmail: server-side search ----

    private fun gmailSearch(folder: IMAPFolder, since: Date, exact: List<String>, domains: List<String>): List<Message> {
        val after = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(since)
        val base = "has:attachment filename:pdf after:$after"
        val uids = LinkedHashSet<Long>()
        if (exact.isNotEmpty()) {
            uids += rawSearch(folder, "from:(${exact.joinToString(" OR ")}) $base")
        }
        if (domains.isNotEmpty()) {
            val subjects = KEYWORDS.joinToString(" OR ") { "subject:$it" }
            uids += rawSearch(folder, "from:(${domains.joinToString(" OR ")}) ($subjects) $base")
        }
        if (uids.isEmpty()) return emptyList()
        return folder.getMessagesByUID(uids.toLongArray()).filterNotNull()
    }

    /** UID SEARCH X-GM-RAW "<gmail query>" — Gmail's own search syntax, evaluated on the server. */
    private fun rawSearch(folder: IMAPFolder, query: String): List<Long> {
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { p ->
            val args = Argument().apply { writeString(query) }
            val responses = p.command("UID SEARCH X-GM-RAW", args)
            val found = mutableListOf<Long>()
            for (r in responses) {
                if (r is IMAPResponse && r.keyEquals("SEARCH")) {
                    while (true) {
                        val n = r.readLong()
                        if (n < 0) break
                        found += n
                    }
                }
            }
            p.notifyResponseHandlers(responses)
            p.handleResult(responses.last())
            found
        } as List<Long>
    }

    // ---- Other providers: standard IMAP search (still server-side) ----

    private fun imapSearch(folder: IMAPFolder, since: Date, exact: List<String>, domains: List<String>): List<Message> {
        fun anyOf(terms: List<SearchTerm>): SearchTerm = if (terms.size == 1) terms[0] else OrTerm(terms.toTypedArray())
        val date = ReceivedDateTerm(ComparisonTerm.GE, since)
        val hits = LinkedHashMap<Long, Message>()
        if (exact.isNotEmpty()) {
            folder.search(AndTerm(date, anyOf(exact.map { FromStringTerm(it) }))).forEach { hits[folder.getUID(it)] = it }
        }
        if (domains.isNotEmpty()) {
            val subject = anyOf(KEYWORDS.map { SubjectTerm(it) })
            folder.search(AndTerm(arrayOf(date, anyOf(domains.map { FromStringTerm(it) }), subject))).forEach { hits[folder.getUID(it)] = it }
        }
        return hits.values.toList()
    }

    /** PDF parts, from the already-fetched structure. Never downloads unrelated message bodies. */
    private fun pdfParts(p: Part): List<Pair<String, Part>> {
        if (p.isMimeType("multipart/*")) {
            val content = runCatching { p.content }.getOrNull() as? Multipart ?: return emptyList()
            return (0 until content.count).flatMap { pdfParts(content.getBodyPart(it)) }
        }
        val name = runCatching { p.fileName }.getOrNull().orEmpty()
        val isPdf = name.lowercase().endsWith(".pdf") || p.isMimeType("application/pdf")
        return if (isPdf) listOf((name.ifBlank { "statement.pdf" }) to p) else emptyList()
    }

    /** Extracts readable plain-text summary lines from an email's text/plain or text/html part. */
    private fun extractBodyText(p: Part): String {
        val plain = mutableListOf<String>()
        val html = mutableListOf<String>()
        fun walk(part: Part) {
            when {
                part.isMimeType("multipart/*") -> {
                    val mp = runCatching { part.content }.getOrNull() as? Multipart ?: return
                    for (idx in 0 until mp.count) walk(mp.getBodyPart(idx))
                }
                part.isMimeType("text/plain") -> {
                    val s = runCatching { part.content as? String }.getOrNull().orEmpty()
                    if (s.isNotBlank()) plain += s
                }
                part.isMimeType("text/html") -> {
                    val s = runCatching { part.content as? String }.getOrNull().orEmpty()
                    if (s.isNotBlank()) html += htmlToText(s)
                }
            }
        }
        walk(p)
        val raw = when {
            html.isNotEmpty() && plain.isEmpty() -> html.joinToString("\n")
            plain.isNotEmpty() && html.isNotEmpty() -> (plain + html).joinToString("\n")
            else -> plain.joinToString("\n")
        }
        return raw.take(32_768)
    }

    private fun htmlToText(rawHtml: String): String = rawHtml
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<(?:br|p|div|tr|li|h[1-6])[^>]*>"), "\n")
        .replace(Regex("(?i)</(?:p|div|tr|li|h[1-6]|table)>"), "\n")
        .replace(Regex("(?i)<t[dh][^>]*>"), "  ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#8377;", "₹")
        .replace("&#x20b9;", "₹", ignoreCase = true)
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    companion object {
        /** What statement mails say in the subject or attachment name. */
        val KEYWORDS = listOf("statement", "stmt", "e-bill", "ebill", "estatement")

        /** Encoded (base64) size above which an attachment isn't downloaded; statements are a few MB at most. */
        private const val MAX_PDF_BYTES = 40 * 1_048_576
        private val EMPTY = ByteArray(0)
    }
}
