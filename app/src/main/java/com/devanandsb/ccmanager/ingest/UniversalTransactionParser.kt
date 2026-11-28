package com.devanandsb.ccmanager.ingest

import com.devanandsb.ccmanager.statementparser.domain.model.CvTableGrid
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Spatial multi-archetype transaction table extractor.
 * Uses exact PDF (X, Y) coordinates from [PageLayout] to:
 *   1. Ignore left/right sidebars & pie-chart overlays (e.g. ICICI's "Apparel/Grocery-9% Others-91%" at X < DateCol).
 *   2. Isolate `Merchant Category` (Yes Bank, Axis), `Reward Points`, `SerNo.`, `Intl.`, and `Balance` columns
 *      from merchant descriptions, storing `Merchant Category` into [ParsedLine.categoryHint].
 *   3. Support separate Debit & Credit columns as well as single Amount columns with `CR`/`DR` or `+/-`.
 *   4. Merge wrapped multi-line descriptions (e.g. ICICI's `"IN"` continuation line).
 *   5. Handle AU Small Finance Bank's multi-Y stacked cell card rows (`Day`, `MMM YY`, `Cr`/`Dr`, `+₹Amt`).
 */
object UniversalTransactionParser {

    private data class ColumnSpec(
        val kind: ColKind,
        val minX: Float,
        val maxX: Float,
        val centerX: Float,
    )

    private enum class ColKind {
        DATE, SER_NO, DESC, CATEGORY, REWARD, INTL, DEBIT, CREDIT, AMOUNT, CR_DR, BALANCE, OTHER
    }

    private data class RawRow(
        val page: Int,
        val date: LocalDate,
        val dateRaw: String,
        val descParts: MutableList<String>,
        val categoryHint: String,
        val reference: String,
        val mcc: String,
        val amountPaise: Long,
        val hasExplicitCredit: Boolean?,
        val plusSign: Boolean,
        val minusSign: Boolean,
        val rowBand: IntRange? = null,
    )

    private val ILLUSTRATION_HEADERS = listOf(
        "illustrative example", "illustration for calculating", "sample calculation",
        "interest charges calculation", "how minimum amount due is calculated",
        "finance charge calculation", "minimum amount due calculation",
        "schedule of charges", "key fact statement", "glossary",
    )

    private val TABLE_FOOTER_MARKERS = listOf(
        "end of statement", "end of the statement", "earnings", "important messages",
        "important information", "cashback details", "# international spends",
        "gst details", "just so you know", "reward points you have earned",
        "statement summary", "payment summary", "summary of reward points",
    )

    private val AU_DAY_RX = Regex("^([0-3]?\\d)$")
    private val AU_MON_YY_RX = Regex("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(\\d{2})$", RegexOption.IGNORE_CASE)
    private val CR_DR_ONLY_RX = Regex("^\\(?\\s*(Cr|Dr)\\.?\\s*\\)?$", RegexOption.IGNORE_CASE)
    private val AMOUNT_CELL_RX = Regex(
        "^([+-])?\\s*(?:(?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP|`|[\uE000-\uF8FF]|C)\\s*)?(\\d+(?:,\\d+)*(?:\\.\\d{1,2})?)(?:\\s*(?:INR|Rs\\.?|₹|USD|EUR|GBP))?\\s*(?:\\(?\\s*(Cr|CR|cr|Dr|DR|dr|C|D)\\.?\\s*\\)?)?\\.?$",
        RegexOption.IGNORE_CASE,
    )
    private val PURE_NUM_RX = Regex("^[+-]?\\d+(?:,\\d+)*(?:\\.\\d{1,2})?$")

    fun extract(
        pages: List<PageLayout>,
        stmtDate: LocalDate?,
        periodStart: LocalDate?,
        creditWords: List<String>,
        minusMeansCredit: Boolean = false,
        stripCategorySuffixRx: Regex? = null,
    ): List<ParsedLine> {
        if (pages.isEmpty()) return emptyList()
        val fallbackYear = (stmtDate ?: periodStart ?: LocalDate.now()).year
        val illusStart = findIllustrationStart(pages)
        fun isBeforeIllustration(pageNo: Int, y: Float): Boolean =
            illusStart == null || pageNo < illusStart.first || (pageNo == illusStart.first && y < illusStart.second - 1f)

        val rawRows = mutableListOf<RawRow>()
        var lastDetectedColumns: List<ColumnSpec>? = null

        // 1. Try columnar table extraction page-by-page (supporting headerless Page 2+ continuation pages)
        for (page in pages.sortedBy { it.page }) {
            val spans = page.spans.filter { isBeforeIllustration(page.page, it.y) }
            if (spans.isEmpty()) continue
            val rows = groupRows(spans)
            val cols = detectColumnsOnPage(rows)
            if (cols != null) {
                lastDetectedColumns = cols.second
                val (pageTxns, hitFooter) = extractColumnarRows(
                    pageNo = page.page,
                    rows = rows,
                    headerIdx = cols.first,
                    columns = cols.second,
                    tables = page.tables,
                    stmtDate = stmtDate,
                    fallbackYear = fallbackYear,
                )
                rawRows += pageTxns
                if (hitFooter) lastDetectedColumns = null
                continue
            }
            // 2. Check for AU-style card section ("Your Transactions" without columnar header)
            val hasYourTxns = rows.any { r -> r.any { it.text.equals("Your Transactions", ignoreCase = true) } }
            if (hasYourTxns) {
                rawRows += extractAuCardRows(page.page, spans, stmtDate, fallbackYear)
                lastDetectedColumns = null
                continue
            }
            // 3. Headerless continuation page carrying forward Page 1/previous page column layout
            val carried = lastDetectedColumns
            if (carried != null) {
                val (pageTxns, hitFooter) = extractColumnarRows(
                    pageNo = page.page,
                    rows = rows,
                    headerIdx = -1,
                    columns = carried,
                    tables = page.tables,
                    stmtDate = stmtDate,
                    fallbackYear = fallbackYear,
                )
                rawRows += pageTxns
                if (hitFooter) lastDetectedColumns = null
            }
        }

        if (rawRows.isEmpty()) return emptyList()

        val anyDrCr = rawRows.any { it.hasExplicitCredit != null }
        val anyPlus = rawRows.any { it.plusSign }
        val anySign = anyPlus || (rawRows.any { it.minusSign } && minusMeansCredit)

        val earliest = (periodStart ?: stmtDate?.minusDays(45))?.minusDays(5)
        val latest = stmtDate?.plusDays(2)

        val candidates = rawRows.mapNotNull { row ->
            var desc = row.descParts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            desc = desc
                .replace(Regex("^\\d{6,16}\\s+(?=[A-Za-z0-9])"), "")
                .replace(Regex("^\\d{4,5}\\s+(?=[A-Za-z])"), "")
                .replace(Regex("\\s*\\b(?:MCC|MC)\\s*[:#\\-]?\\s*\\d{4}\\b", RegexOption.IGNORE_CASE), "")
                .trim()
            var strippedCat = ""
            if (row.categoryHint.isEmpty() && stripCategorySuffixRx != null) {
                stripCategorySuffixRx.find(desc)?.let { cm ->
                    val catText = cm.groupValues[1].trim()
                    if (!catText.equals("MISCELLANEOUS", ignoreCase = true) && !catText.equals("OTHERS", ignoreCase = true)) {
                        strippedCat = catText
                    }
                    desc = desc.substring(0, cm.range.first).trim()
                }
            }
            desc = Text.stripEmiLink(desc)
            if (desc.none(Char::isLetter)) return@mapNotNull null

            val lower = desc.lowercase()
            val isCredit = when {
                row.hasExplicitCredit != null -> row.hasExplicitCredit
                row.plusSign -> true
                row.minusSign && (minusMeansCredit || !anyPlus) -> true
                !anyDrCr && !anySign ->
                    !(lower.contains("cashback") && lower.contains("reversal")) &&
                        !(lower.contains("payment") && lower.contains("reversal")) &&
                        creditWords.any { lower.contains(it) }
                else -> false
            }

            val ref = row.reference.ifEmpty {
                Regex("\\b(?:ref(?:erence)?\\s*(?:no\\.?)?|arn)\\s*[:#\\-]?\\s*([A-Z0-9]{6,})", RegexOption.IGNORE_CASE)
                    .find(desc)?.groupValues?.get(1).orEmpty()
            }
            val hint = cleanCategory(row.categoryHint.ifEmpty { strippedCat })
            row.date to ParsedLine(
                date = row.date,
                description = desc,
                amountPaise = row.amountPaise,
                isCredit = isCredit,
                reference = ref,
                categoryHint = hint,
                mcc = row.mcc.ifEmpty { Text.extractMcc(desc) },
            )
        }

        val inRange = candidates.filter { (d, _) ->
            (earliest == null || !d.isBefore(earliest)) && (latest == null || !d.isAfter(latest))
        }
        return (if (inRange.isNotEmpty() || stmtDate == null) inRange else candidates).map { it.second }
    }

    private fun cleanCategory(raw: String): String {
        val c = raw.trim()
        if (c.isEmpty() || c == "-" || c.equals("MISCELLANEOUS", true) || c.equals("OTHERS", true)) return ""
        return c
    }

    private fun findIllustrationStart(pages: List<PageLayout>): Pair<Int, Float>? {
        for (page in pages.sortedBy { it.page }) {
            for (s in page.spans) {
                val low = s.text.lowercase()
                if (ILLUSTRATION_HEADERS.any { low.contains(it) }) return page.page to s.y
                if (page.page >= 2 && (low.startsWith("important information") || low.startsWith("important safety"))) {
                    return page.page to s.y
                }
            }
        }
        return null
    }

    private fun groupRows(spans: List<TextSpan>, yTol: Float = 4.2f): List<List<TextSpan>> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedWith(compareBy<TextSpan> { it.y }.thenBy { it.x })
        val rows = mutableListOf<MutableList<TextSpan>>()
        for (s in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last[0].y - s.y) <= yTol) last.add(s) else rows.add(mutableListOf(s))
        }
        rows.forEach { it.sortBy { s -> s.x } }
        return rows
    }

    private fun detectColumnsOnPage(rows: List<List<TextSpan>>): Pair<Int, List<ColumnSpec>>? {
        for (idx in rows.indices) {
            detectHeaderOnRow(rows[idx])?.let { specs -> return idx to specs }
            if (idx + 1 < rows.size) {
                val yGap = rows[idx + 1].first().y - rows[idx].first().y
                if (yGap in 1f..16f) {
                    val merged = mergeTwoHeaderRows(rows[idx], rows[idx + 1])
                    detectHeaderOnRow(merged)?.let { specs -> return (idx + 1) to specs }
                }
            }
        }
        return null
    }

    private fun mergeTwoHeaderRows(top: List<TextSpan>, bottom: List<TextSpan>): List<TextSpan> {
        val usedBottom = HashSet<TextSpan>()
        val out = mutableListOf<TextSpan>()
        for (t in top) {
            val match = bottom.firstOrNull { b ->
                b !in usedBottom && (minOf(t.endX, b.endX) - maxOf(t.x, b.x) >= -8f || abs((t.x + t.endX) / 2f - (b.x + b.endX) / 2f) <= 28f)
            }
            if (match != null) {
                usedBottom += match
                out += TextSpan(
                    page = match.page,
                    x = minOf(t.x, match.x),
                    endX = maxOf(t.endX, match.endX),
                    y = match.y,
                    text = "${t.text.trim()} ${match.text.trim()}".replace(Regex("\\s+"), " "),
                )
            } else {
                out += t
            }
        }
        for (b in bottom) {
            if (b !in usedBottom) out += b
        }
        out.sortBy { it.x }
        return out
    }

    private fun detectHeaderOnRow(row: List<TextSpan>): List<ColumnSpec>? {
        val joined = row.joinToString(" ") { it.text }.lowercase()
        if (joined.contains("previous balance") && joined.contains("=")) return null
        val hasDateHeader = row.any { s ->
            val l = s.text.lowercase()
            l == "date" || l.startsWith("txn date") || l.startsWith("txn. date") ||
                l.startsWith("tran date") || l.startsWith("transaction date") ||
                l.startsWith("posting date") || l.startsWith("post date") || l.startsWith("value date")
        }
        val hasDescOrAmt = joined.contains("transaction") || joined.contains("description") ||
            joined.contains("particulars") || joined.contains("narration") ||
            joined.contains("merchant") || joined.contains("amount") ||
            joined.contains("debit") || joined.contains("amt")
        if (!hasDateHeader || !hasDescOrAmt) return null

        // Exclude sidebars sitting on the same Y as header (e.g. "SPENDS OVERVIEW" at X=89 while "Date" is at X=207)
        val dateSpan = row.first { s ->
            val l = s.text.lowercase()
            l == "date" || l.startsWith("txn date") || l.startsWith("txn. date") ||
                l.startsWith("tran date") || l.startsWith("transaction date") ||
                l.startsWith("posting date") || l.startsWith("post date") || l.startsWith("value date")
        }
        val rightLimitHeader = row.filter { it.x >= dateSpan.x - 20f }
        val specs = rightLimitHeader.map { s ->
            ColumnSpec(classifyHeader(s.text), s.x, s.endX, (s.x + s.endX) / 2f)
        }.sortedBy { it.centerX }

        val hasMoneyCol = specs.any { it.kind == ColKind.AMOUNT || it.kind == ColKind.DEBIT || it.kind == ColKind.CREDIT }
        return specs.takeIf { it.size >= 3 && hasMoneyCol }
    }

    private fun classifyHeader(raw: String): ColKind {
        val l = raw.lowercase().trim()
        return when {
            l == "date" || l.contains("txn date") || l.contains("txn. date") || l.contains("tran date") ||
                l.contains("transaction date") || l.contains("posting date") || l.contains("post date") || l.contains("value date") -> ColKind.DATE
            l.startsWith("ser") || l.startsWith("sr.") || l == "sl. no." || l.startsWith("ref") || l == "txn id" -> ColKind.SER_NO
            l.contains("merchant category") || l == "category" || l == "mcc" -> ColKind.CATEGORY
            l.contains("transaction") || l.contains("description") || l.contains("particulars") || l.contains("narration") || l.contains("merchant") -> ColKind.DESC
            l.contains("reward") || l.contains("points") || l.contains("monies") -> ColKind.REWARD
            l.contains("intl") || l.contains("foreign") || l.contains("currency") -> ColKind.INTL
            l == "cr/dr" || l == "dr/cr" || l == "cr / dr" || l == "dr / cr" || l == "d/c" || l == "c/d" -> ColKind.CR_DR
            l.contains("debit") || l.contains("withdrawal") || l == "dr" -> ColKind.DEBIT
            l.contains("credit") || l.contains("deposit") || l == "cr" -> ColKind.CREDIT
            l.contains("balance") -> ColKind.BALANCE
            l.contains("amount") || l.contains("amt") || l == "inr" || l == "usd" || l == "eur" || l == "gbp" -> ColKind.AMOUNT
            else -> ColKind.OTHER
        }
    }

    private fun extractColumnarRows(
        pageNo: Int,
        rows: List<List<TextSpan>>,
        headerIdx: Int,
        columns: List<ColumnSpec>,
        tables: List<CvTableGrid>,
        stmtDate: LocalDate?,
        fallbackYear: Int,
    ): Pair<List<RawRow>, Boolean> {
        val out = mutableListOf<RawRow>()
        val headerY = if (headerIdx >= 0) rows[headerIdx].first().y else -100f
        val dateCol = columns.first { it.kind == ColKind.DATE }
        val tableMinX = (columns.minOf { it.minX }) - 18f

        val activeRuledTable = tables.firstOrNull { t ->
            t.page == pageNo && (headerY < 0f || headerY in (t.yStart - 16f)..(t.yEnd + 16f))
        }
        val cvColumnForSpec: Map<ColumnSpec, ClosedFloatingPointRange<Float>> = if (
            activeRuledTable != null &&
            activeRuledTable.hasRuledLines &&
            activeRuledTable.columnBounds.size >= 3
        ) {
            val matched = HashMap<ColumnSpec, IntRange>()
            val counts = HashMap<IntRange, Int>()
            for (spec in columns) {
                val cvRange = activeRuledTable.columnRangeForX(spec.centerX.roundToInt()) ?: continue
                if (cvRange.last - cvRange.first >= 14) {
                    matched[spec] = cvRange
                    counts[cvRange] = (counts[cvRange] ?: 0) + 1
                }
            }
            matched.filterValues { counts[it] == 1 }
                .mapValues { (_, r) -> r.first.toFloat()..r.last.toFloat() }
        } else {
            emptyMap()
        }

        // Compute midpoint column dividers between consecutive header specs, snapped to ruled CV walls when available
        val bounds = columns.indices.map { i ->
            val spec = columns[i]
            val cvBound = cvColumnForSpec[spec]
            if (cvBound != null) {
                spec to cvBound
            } else {
                val left = if (i == 0) tableMinX else (columns[i - 1].maxX + spec.minX) / 2f
                val right = if (i == columns.lastIndex) Float.MAX_VALUE else (spec.maxX + columns[i + 1].minX) / 2f
                spec to (left..right)
            }
        }
        val descSpec = columns.firstOrNull { it.kind == ColKind.DESC }
        val catSpec = columns.firstOrNull { it.kind == ColKind.CATEGORY }
        val rewardSpec = columns.firstOrNull { it.kind == ColKind.REWARD }

        fun columnForSpan(s: TextSpan): ColKind {
            // If span starts in Date column and extends far into Description (e.g. Yes Bank concatenated span), split carefully
            val cx = (s.x + s.endX) / 2f
            val byStart = bounds.firstOrNull { (_, range) -> s.x in range }?.first
            val byCenter = bounds.firstOrNull { (_, range) -> cx in range }?.first
            if (catSpec != null && s.x >= catSpec.minX - 28f && s.endX <= catSpec.maxX + 40f) {
                return ColKind.CATEGORY
            }
            if (rewardSpec != null && byStart?.kind == ColKind.REWARD && (PURE_NUM_RX.matches(s.text.trim()) || s.text.equals("0"))) {
                return ColKind.REWARD
            }
            return byStart?.kind ?: byCenter?.kind ?: ColKind.OTHER
        }

        // Skip possible 2nd sub-header line ("Points   amount") and "Card No: 4315XXXXXXXX4321"
        var current: RawRow? = null
        var lastRowY = headerY
        var hitFooter = false

        for (rIdx in (headerIdx + 1) until rows.size) {
            val rawRow = rows[rIdx]
            val y = rawRow.first().y
            if (headerIdx >= 0 && y - headerY <= 14f && rawRow.all { it.text.lowercase() in setOf("points", "amount", "(rs.)", "(in `)", "(in ₹)", "pts") || it.text.contains("card no", true) }) {
                continue
            }

            // Filter out left/right sidebars outside the table's X-bounds (e.g. ICICI pie chart at X=50..158 when Date X=207.84)
            val row = rawRow.filter { it.endX >= tableMinX }
            if (row.isEmpty()) continue

            val fullLow = row.joinToString(" ") { it.text }.lowercase()
            if (TABLE_FOOTER_MARKERS.any { m -> fullLow == m || fullLow.startsWith(m) || (m.length > 8 && fullLow.contains(m)) }) {
                if (current != null) {
                    out += current
                    current = null
                }
                hitFooter = true
                break
            }
            if (Regex("^page\\s*:?\\s*\\d+\\s+of\\s+\\d+", RegexOption.IGNORE_CASE).containsMatchIn(fullLow)) {
                continue
            }
            if (fullLow.startsWith("card no:") || Regex("^[0-9xX*•]{8,19}$").matches(fullLow.replace(" ", ""))) {
                continue
            }

            // Look for date cell at the left side of the table
            val dateCell = row.firstOrNull { s ->
                s.x <= dateCol.maxX + 24f && Text.dateTokens(s.text).isNotEmpty()
            }

            if (dateCell != null) {
                val dateTok = Text.dateTokens(dateCell.text).first()
                var date = Text.date(dateTok, fallbackYear)
                if (date != null && stmtDate != null && date.isAfter(stmtDate.plusDays(15)) && Regex("\\d{4}").find(dateTok) == null) {
                    date = date.minusYears(1)
                }
                if (date == null) continue

                if (current != null) {
                    out += current
                    current = null
                }

                // Extract each column's text on this row
                val descParts = mutableListOf<String>()
                var catHint = ""
                var ref = ""
                var mcc = ""
                var debitAmt: Pair<Long, String>? = null
                var creditAmt: Pair<Long, String>? = null
                var singleAmt: Triple<Long, Boolean?, Pair<Boolean, Boolean>>? = null
                var crDrColCredit: Boolean? = null

                // Check if dateCell itself contains glued "21/10/2026 PAYMENT RECEIVED BBPS..."
                val tokEnd = dateCell.text.indexOf(dateTok) + dateTok.length
                val remainderInDateSpan = dateCell.text.substring(tokEnd).trim()
                if (remainderInDateSpan.isNotEmpty() && remainderInDateSpan.any(Char::isLetter)) {
                    descParts += remainderInDateSpan
                }

                for (s in row) {
                    if (s === dateCell) continue
                    val colKind = columnForSpan(s)
                    when (colKind) {
                        ColKind.DATE -> {
                            // If a secondary value/post date appears slightly right of first date, ignore
                            if (Text.dateTokens(s.text).isEmpty()) descParts += s.text
                        }
                        ColKind.SER_NO -> {
                            if (ref.isEmpty() && Regex("^[A-Z0-9]{6,}$", RegexOption.IGNORE_CASE).matches(s.text.trim())) {
                                ref = s.text.trim()
                            }
                        }
                        ColKind.DESC, ColKind.OTHER -> {
                            // Only put in desc if it's not strictly a reward number under rewardCol or amount under amountCol
                            val mAmt = AMOUNT_CELL_RX.matchEntire(s.text.trim())
                            if (mAmt != null && columns.any { it.kind == ColKind.AMOUNT } && s.x >= (columns.first { it.kind == ColKind.AMOUNT }.minX - 60f)) {
                                singleAmt = parseAmountToken(s.text)
                            } else {
                                mcc = mcc.ifEmpty { Text.extractMcc(s.text) }
                                descParts += s.text
                            }
                        }
                        ColKind.CATEGORY -> {
                            catHint = listOf(catHint, s.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                        }
                        ColKind.REWARD, ColKind.INTL, ColKind.BALANCE -> Unit
                        ColKind.CR_DR -> {
                            val marker = CR_DR_ONLY_RX.matchEntire(s.text.trim())?.groupValues?.get(1)?.lowercase()
                            if (marker != null) {
                                crDrColCredit = marker.startsWith("c")
                            }
                        }
                        ColKind.DEBIT -> {
                            parseAmountToken(s.text)?.let { (paise, _, _) ->
                                if (paise > 0L) debitAmt = paise to s.text
                            }
                        }
                        ColKind.CREDIT -> {
                            parseAmountToken(s.text)?.let { (paise, _, _) ->
                                if (paise > 0L) creditAmt = paise to s.text
                            }
                        }
                        ColKind.AMOUNT -> {
                            parseAmountToken(s.text)?.let { singleAmt = it }
                        }
                    }
                }

                // Fallback: if singleAmt/debitAmt/creditAmt missed due to wide X-offset (e.g. short numbers aligned right), check rightmost numeric cell
                if (singleAmt == null && debitAmt == null && creditAmt == null) {
                    val lastSpan = row.lastOrNull()
                    if (lastSpan != null && lastSpan !== dateCell && lastSpan.x > (descSpec?.maxX ?: dateCol.maxX) + 40f) {
                        parseAmountToken(lastSpan.text)?.let { parsedAmt ->
                            singleAmt = parsedAmt
                            descParts.remove(lastSpan.text)
                        }
                    }
                }

                val resolved = when {
                    creditAmt != null -> Triple(creditAmt.first, true as Boolean?, false to false)
                    debitAmt != null -> Triple(debitAmt.first, false as Boolean?, false to false)
                    singleAmt != null -> if (singleAmt.second == null && crDrColCredit != null) {
                        singleAmt.copy(second = crDrColCredit)
                    } else singleAmt
                    else -> null
                } ?: continue

                val currentBand = activeRuledTable?.rowBandForY(y.roundToInt())
                current = RawRow(
                    page = pageNo,
                    date = date,
                    dateRaw = dateTok,
                    descParts = descParts,
                    categoryHint = catHint,
                    reference = ref,
                    mcc = mcc,
                    amountPaise = resolved.first,
                    hasExplicitCredit = resolved.second,
                    plusSign = resolved.third.first,
                    minusSign = resolved.third.second,
                    rowBand = currentBand,
                )
                lastRowY = y
            } else if (current != null && (y - lastRowY <= 18f || (current.rowBand != null && current.rowBand == activeRuledTable?.rowBandForY(y.roundToInt())))) {
                // Continuation line for multi-line wrapped merchant description (e.g. ICICI "IN" at X=305.26)
                val descMinX = (descSpec?.minX ?: (dateCol.maxX + 8f)) - 12f
                val descMaxX = (columns.firstOrNull { it.kind == ColKind.CATEGORY || it.kind == ColKind.REWARD || it.kind == ColKind.AMOUNT || it.kind == ColKind.DEBIT }?.minX
                    ?: Float.MAX_VALUE) - 6f
                val contParts = row.filter { s ->
                    s.x in descMinX..descMaxX &&
                        AMOUNT_CELL_RX.matchEntire(s.text.trim()) == null &&
                        CR_DR_ONLY_RX.matchEntire(s.text.trim()) == null &&
                        s.text.any(Char::isLetter)
                }
                if (contParts.isNotEmpty()) {
                    current.descParts.addAll(contParts.map { it.text })
                    lastRowY = y
                }
            }
        }

        if (current != null) out += current
        return out to hitFooter
    }

    private fun parseAmountToken(raw: String): Triple<Long, Boolean?, Pair<Boolean, Boolean>>? {
        val m = AMOUNT_CELL_RX.matchEntire(raw.trim()) ?: return null
        val sign = m.groupValues[1]
        val numStr = m.groupValues[2]
        val marker = m.groupValues[3].lowercase()
        // Reject integer-only small numbers if there's no currency prefix and no decimals and no Cr/Dr
        val hasDecimal = numStr.contains('.')
        val hasCurrencyOrSign = Regex("[₹`+-]|Rs\\.?|INR", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (!hasDecimal && !hasCurrencyOrSign && marker.isEmpty()) return null
        val paise = Text.paise(numStr) ?: return null
        val explicitCredit = when {
            marker.startsWith("c") -> true
            marker.startsWith("d") -> false
            else -> null
        }
        return Triple(paise, explicitCredit, (sign == "+") to (sign == "-"))
    }

    /**
     * AU Small Finance Bank card transaction extractor:
     * Each card row places `Day` (`27`) at X<65, `MMM YY` (`Oct 26`) ~15pt below at X<70, `Cr`/`Dr` beside `MMM YY` at X<100,
     * description above/within the band at X in 65..400, and amount (`+₹1,000.00` or `₹1,800.00`) at X>400.
     */
    private fun extractAuCardRows(
        pageNo: Int,
        spans: List<TextSpan>,
        stmtDate: LocalDate?,
        fallbackYear: Int,
    ): List<RawRow> {
        val txStartY = spans.firstOrNull { it.text.equals("Your Transactions", ignoreCase = true) }?.y ?: 0f
        val bodySpans = spans.filter { it.y > txStartY + 5f && !it.text.startsWith("Page ", ignoreCase = true) }

        val monSpans = bodySpans.filter { it.x < 75f && AU_MON_YY_RX.matches(it.text.trim()) }.sortedBy { it.y }
        if (monSpans.isEmpty()) return emptyList()

        val daySpans = bodySpans.filter { it.x < 70f && AU_DAY_RX.matches(it.text.trim()) }
        val consumedSpans = HashSet<TextSpan>()
        val out = mutableListOf<RawRow>()

        for (monSpan in monSpans) {
            val monMatch = AU_MON_YY_RX.matchEntire(monSpan.text.trim()) ?: continue
            val daySpan = daySpans.filter { ds ->
                ds !in consumedSpans && (monSpan.y - ds.y) in 6f..24f
            }.minByOrNull { abs(monSpan.y - it.y) } ?: continue

            val bandTop = daySpan.y - 12f
            val bandBottom = monSpan.y + 5f
            val bandSpans = bodySpans.filter { it.y in bandTop..bandBottom }

            val flagSpan = bandSpans.firstOrNull {
                it.x in 60f..110f && abs(it.y - monSpan.y) <= 6f && CR_DR_ONLY_RX.matches(it.text.trim())
            }
            val amtSpan = bandSpans.filter { it.x > 380f }.firstNotNullOfOrNull { s ->
                parseAmountToken(s.text)?.let { s to it }
            } ?: continue

            val descSpans = bandSpans.filter { s ->
                s !== daySpan && s !== monSpan && s !== flagSpan && s !== amtSpan.first &&
                    s.x in 60f..380f && s.text.any(Char::isLetterOrDigit)
            }.sortedWith(compareBy<TextSpan> { it.y }.thenBy { it.x })

            if (descSpans.isEmpty()) continue

            val rawDate = "${daySpan.text.trim()} ${monMatch.groupValues[1]} '${monMatch.groupValues[2]}"
            var date = Text.date(rawDate, fallbackYear) ?: continue
            if (stmtDate != null && date.isAfter(stmtDate.plusDays(15))) date = date.minusYears(1)

            val flagMarker = flagSpan?.text?.trim()?.lowercase().orEmpty()
            val explicitCredit = when {
                flagMarker.startsWith("c") -> true
                flagMarker.startsWith("d") -> false
                else -> amtSpan.second.second
            }

            consumedSpans += daySpan
            consumedSpans += monSpan
            out += RawRow(
                page = pageNo,
                date = date,
                dateRaw = rawDate,
                descParts = descSpans.map { it.text }.toMutableList(),
                categoryHint = "",
                reference = "",
                mcc = "",
                amountPaise = amtSpan.second.first,
                hasExplicitCredit = explicitCredit,
                plusSign = amtSpan.second.third.first,
                minusSign = amtSpan.second.third.second,
            )
        }
        return out
    }
}
