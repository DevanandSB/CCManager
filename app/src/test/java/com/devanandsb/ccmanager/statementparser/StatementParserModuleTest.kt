package com.devanandsb.ccmanager.statementparser

import com.devanandsb.ccmanager.statementparser.data.cv.TinyCvTableDetector
import com.devanandsb.ccmanager.statementparser.data.extractor.MlKitOcrFallbackExtractor
import com.devanandsb.ccmanager.statementparser.data.extractor.PdfBoxCoordinateExtractor
import com.devanandsb.ccmanager.statementparser.data.parser.BankPluginRegistry
import com.devanandsb.ccmanager.statementparser.data.parser.DynamicColumnDetector
import com.devanandsb.ccmanager.statementparser.data.parser.SummaryAndMathVerifier
import com.devanandsb.ccmanager.statementparser.data.parser.TransactionRowParser
import com.devanandsb.ccmanager.statementparser.domain.model.ExtractionEngine
import com.devanandsb.ccmanager.statementparser.domain.model.ParsedStatementResult
import com.devanandsb.ccmanager.statementparser.domain.model.StatementSummary
import com.devanandsb.ccmanager.statementparser.domain.model.StatementTransaction
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import com.devanandsb.ccmanager.statementparser.domain.model.TransactionType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

class StatementParserModuleTest {

    private val detector = DynamicColumnDetector()
    private val rowParser = TransactionRowParser(detector)
    private val registry = BankPluginRegistry()
    private val verifier = SummaryAndMathVerifier()

    private fun sampleFile(name: String): File? {
        val root = listOf(File("."), File("..")).firstOrNull { File(it, "sample_pdfs").isDirectory } ?: return null
        return File(root, "sample_pdfs/$name").takeIf { it.isFile }
    }

    private fun extractPdfBlocks(file: File): Triple<List<TextBlock>, List<List<TextBlock>>, String> {
        val captured = mutableListOf<TextBlock>()
        val rawText = PDDocument.load(file).use { doc ->
            val stripper = object : PDFTextStripper() {
                private val pagePositions = mutableListOf<TextPosition>()

                init {
                    sortByPosition = true
                }

                override fun startPage(page: PDPage) {
                    super.startPage(page)
                    pagePositions.clear()
                }

                override fun processTextPosition(text: TextPosition) {
                    super.processTextPosition(text)
                    val ch = text.unicode ?: return
                    if (ch.isEmpty()) return
                    pagePositions += text
                }

                override fun endPage(page: PDPage) {
                    super.endPage(page)
                    val sortedByY = pagePositions.sortedWith(compareBy<TextPosition> { it.yDirAdj }.thenBy { it.xDirAdj })
                    val yRows = mutableListOf<MutableList<TextPosition>>()
                    for (pos in sortedByY) {
                        val lastRow = yRows.lastOrNull()
                        if (lastRow != null && abs(lastRow[0].yDirAdj - pos.yDirAdj) <= 2.0f) {
                            lastRow += pos
                        } else {
                            yRows += mutableListOf(pos)
                        }
                    }
                    for (yRow in yRows) {
                        val sorted = yRow.sortedWith(compareBy<TextPosition> { it.xDirAdj }.thenBy { it.yDirAdj })
                        val deduped = ArrayList<TextPosition>(sorted.size)
                        for (pos in sorted) {
                            val ch = pos.unicode ?: continue
                            if (ch.isEmpty()) continue
                            val dup = deduped.asReversed().take(4).any { prev ->
                                prev.unicode == ch &&
                                    abs(prev.xDirAdj - pos.xDirAdj) <= 1.8f &&
                                    abs(prev.yDirAdj - pos.yDirAdj) <= 1.8f
                            }
                            if (!dup) deduped += pos
                        }
                        val chunks = mutableListOf<MutableList<TextPosition>>()
                        for (pos in deduped) {
                            val cur = chunks.lastOrNull()
                            if (cur == null) {
                                chunks += mutableListOf(pos)
                            } else {
                                val prev = cur.last()
                                val gap = pos.xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                                val sw = prev.widthOfSpace.takeIf { it > 0f } ?: 3.5f
                                if (gap <= maxOf(sw * 1.35f, 5.5f)) cur += pos else chunks += mutableListOf(pos)
                            }
                        }
                        for (chunk in chunks) {
                            val sb = StringBuilder()
                            var prevEndX = chunk.first().xDirAdj
                            for ((idx, pos) in chunk.withIndex()) {
                                val ch = pos.unicode ?: continue
                                if (idx > 0) {
                                    val gap = pos.xDirAdj - prevEndX
                                    val sw = pos.widthOfSpace.takeIf { it > 0f } ?: 3.5f
                                    if (gap > maxOf(sw * 0.42f, 1.4f) && !sb.endsWith(' ') && !ch.startsWith(' ')) {
                                        sb.append(' ')
                                    }
                                }
                                sb.append(ch)
                                prevEndX = pos.xDirAdj + pos.widthDirAdj
                            }
                            val clean = PdfBoxCoordinateExtractor.cleanFauxBoldText(
                                sb.toString().replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim(),
                            )
                            if (clean.isBlank()) continue
                            val first = chunk.first()
                            val last = chunk.last()
                            val startX = first.xDirAdj
                            val endX = maxOf(last.xDirAdj + last.widthDirAdj, startX + 1f)
                            val avgY = chunk.map { it.yDirAdj }.average().roundToInt()
                            captured += TextBlock(
                                text = clean,
                                x = startX.roundToInt(),
                                y = avgY,
                                width = (endX - startX).roundToInt().coerceAtLeast(1),
                                page = currentPageNo,
                            )
                        }
                    }
                }
            }
            stripper.getText(doc)
        }
        val blocks = PdfBoxCoordinateExtractor.deduplicateOverlappingBlocks(captured)
        val rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(blocks)
        return Triple(blocks, rows, rawText)
    }

    private fun parseFullSamplePdf(name: String): ParsedStatementResult? {
        val file = sampleFile(name) ?: return null
        val (_, rows, rawText) = extractPdfBlocks(file)
        val page1Rows = rows.filter { it.firstOrNull()?.page == 1 }
        val initialSummary = verifier.extractSummary(page1Rows, rawText, rows)
        val inferredYear = initialSummary.statementDate?.take(4)?.toIntOrNull()
            ?: initialSummary.paymentDueDate?.take(4)?.toIntOrNull()
        val layout = detector.detect(rows)
        val tabularTxns = if (layout != null) rowParser.parseRows(rows, layout, inferredYear) else emptyList()
        val txns = tabularTxns.ifEmpty { registry.parseFallback(rawText, rows) }
        val (reconciledSummary, math) = verifier.reconcileAndVerify(initialSummary, txns, page1Rows)
        return ParsedStatementResult(
            summary = reconciledSummary,
            transactions = txns,
            extractionEngine = ExtractionEngine.PDFBOX_VECTOR,
            totalDebits = math.totalDebits,
            totalCredits = math.totalCredits,
            isMathVerified = math.isMathVerified,
            discrepancyAmount = math.discrepancyAmount,
        )
    }

    @Test
    fun dynamicColumnDetectorAdaptsAcrossThreeSimulatedBankLayoutsIncludingRewardPoints() {
        // Layout 1: HDFC style (Date | Transaction Description | Amount (in Rs.))
        val hdfcRows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("Date", x = 36, y = 210, width = 32, page = 1),
                TextBlock("Transaction Description", x = 110, y = 211, width = 140, page = 1),
                TextBlock("Amount (in Rs.)", x = 470, y = 210, width = 95, page = 1),
                TextBlock("14/11/2026", x = 36, y = 235, width = 62, page = 1),
                TextBlock("SWIGGY INSTAMART", x = 110, y = 236, width = 120, page = 1),
                TextBlock("1,249.50", x = 495, y = 235, width = 55, page = 1),
            ),
        )
        val hdfcLayout = detector.detect(hdfcRows)
        assertNotNull(hdfcLayout)
        val hdfcTxns = rowParser.parseRows(hdfcRows, hdfcLayout!!)
        assertEquals(1, hdfcTxns.size)
        assertEquals("2026-11-14", hdfcTxns[0].date)
        assertEquals("SWIGGY INSTAMART", hdfcTxns[0].description)
        assertEquals(BigDecimal("1249.50"), hdfcTxns[0].amount)

        // Layout 2: ICICI style with extra Reward Points column between Particulars and Billing Amount
        val iciciRows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("Txn Date", x = 42, y = 300, width = 50, page = 1),
                TextBlock("Particulars", x = 125, y = 300, width = 70, page = 1),
                TextBlock("Reward Points", x = 370, y = 300, width = 72, page = 1),
                TextBlock("Billing Amount", x = 478, y = 300, width = 82, page = 1),
                TextBlock("15-11-2026", x = 42, y = 326, width = 60, page = 1),
                TextBlock("AMAZON PAY INDIA", x = 125, y = 326, width = 135, page = 1),
                TextBlock("250", x = 396, y = 326, width = 24, page = 1),
                TextBlock("5,400.00 DR", x = 482, y = 326, width = 74, page = 1),
            ),
        )
        val iciciLayout = detector.detect(iciciRows)
        assertNotNull(iciciLayout)
        assertEquals(1, iciciLayout!!.ignoredIntervals.size)
        val iciciTxns = rowParser.parseRows(iciciRows, iciciLayout)
        assertEquals(1, iciciTxns.size)
        assertEquals("2026-11-15", iciciTxns[0].date)
        assertEquals("AMAZON PAY INDIA", iciciTxns[0].description)
        assertFalse(iciciTxns[0].description.contains("250"))
        assertEquals(BigDecimal("5400.00"), iciciTxns[0].amount)
        assertEquals(TransactionType.DEBIT, iciciTxns[0].type)

        // Layout 3: Axis / International shifted layout with separate Cr/Dr indicator column
        val axisRows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("Post Date", x = 28, y = 180, width = 56, page = 1),
                TextBlock("Merchant Name", x = 105, y = 180, width = 95, page = 1),
                TextBlock("INR", x = 430, y = 180, width = 32, page = 1),
                TextBlock("Cr/Dr", x = 515, y = 180, width = 34, page = 1),
                TextBlock("18 Nov 26", x = 28, y = 205, width = 58, page = 1),
                TextBlock("AIR INDIA BOOKING", x = 105, y = 205, width = 120, page = 1),
                TextBlock("18,950.00", x = 412, y = 205, width = 62, page = 1),
                TextBlock("Cr", x = 520, y = 205, width = 20, page = 1),
            ),
        )
        val axisLayout = detector.detect(axisRows)
        assertNotNull(axisLayout)
        assertNotNull(axisLayout!!.crDrIndicatorColumn)
        val axisTxns = rowParser.parseRows(axisRows, axisLayout)
        assertEquals(1, axisTxns.size)
        assertEquals("2026-11-18", axisTxns[0].date)
        assertEquals(BigDecimal("18950.00"), axisTxns[0].amount)
        assertEquals(TransactionType.CREDIT, axisTxns[0].type)
    }

    @Test
    fun transactionRowParserHandlesMultiLineDescriptionsIndianCommasFlagsAndDuplicateGlyphs() {
        val rawBlocks = listOf(
            // Header row
            TextBlock("Date", x = 40, y = 150, width = 32, page = 1),
            TextBlock("Narration", x = 120, y = 150, width = 65, page = 1),
            TextBlock("Amount", x = 480, y = 150, width = 55, page = 1),

            // Faux-bold overlapping duplicate block + CCRREEDDIITT doubled token on Row 1
            TextBlock("02 Nov 2026", x = 40, y = 180, width = 68, page = 1),
            TextBlock("02 Nov 2026", x = 41, y = 180, width = 68, page = 1),
            TextBlock("BBPS CCRREEDDIITT PAYMENT FOR 4321567890123456", x = 120, y = 180, width = 260, page = 1),
            TextBlock("₹ 1,25,400.50 CR", x = 450, y = 180, width = 95, page = 1),

            // Row 2 with wrapped multi-line description
            TextBlock("11/24/2026", x = 40, y = 210, width = 64, page = 1),
            TextBlock("APPLE INDIA ONLINE STORE", x = 120, y = 210, width = 180, page = 1),
            TextBlock("Rs. 45,999.00", x = 468, y = 210, width = 76, page = 1),
            TextBlock("BENGALURU KARNATAKA IN", x = 120, y = 224, width = 165, page = 1),

            // Row 3 with parenthesized credit amount
            TextBlock("25/11/2026", x = 40, y = 250, width = 64, page = 1),
            TextBlock("UBER TRIP REFUND", x = 120, y = 250, width = 130, page = 1),
            TextBlock("(450.00)", x = 482, y = 250, width = 52, page = 1),
        )

        val rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(rawBlocks, yTolerance = 4)
        val layout = detector.detect(rows)!!
        val txns = rowParser.parseRows(rows, layout)

        assertEquals(3, txns.size)

        // Row 1: doubled glyph cleanup + PAN masking + Indian lakh comma amount + CR
        assertEquals("2026-11-02", txns[0].date)
        assertEquals("BBPS CREDIT PAYMENT FOR XXXX-XXXX-XXXX-3456", txns[0].description)
        assertEquals(BigDecimal("125400.50"), txns[0].amount)
        assertEquals(TransactionType.CREDIT, txns[0].type)

        // Row 2: MM/dd/yyyy fallback date + multi-line wrapped merchant description
        assertEquals("2026-11-24", txns[1].date)
        assertEquals("APPLE INDIA ONLINE STORE BENGALURU KARNATAKA IN", txns[1].description)
        assertEquals(BigDecimal("45999.00"), txns[1].amount)
        assertEquals(TransactionType.DEBIT, txns[1].type)

        // Row 3: parenthesized credit
        assertEquals("2026-11-25", txns[2].date)
        assertEquals(BigDecimal("450.00"), txns[2].amount)
        assertEquals(TransactionType.CREDIT, txns[2].type)
    }

    @Test
    fun summaryAndMathVerifierValidatesAccountingIdentityAndExportsJson() {
        val page1Rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("HDFC Bank Regalia Credit Card", x = 40, y = 50, width = 190, page = 1),
                TextBlock("Card No: XXXX XXXX XXXX 9876", x = 40, y = 70, width = 180, page = 1),
                TextBlock("Credit Limit", x = 40, y = 100, width = 75, page = 1),
                TextBlock("Rs. 5,00,000.00", x = 40, y = 116, width = 95, page = 1),
                TextBlock("Statement Date", x = 220, y = 100, width = 90, page = 1),
                TextBlock("20/11/2026", x = 220, y = 116, width = 65, page = 1),
                TextBlock("Payment Due Date", x = 340, y = 100, width = 105, page = 1),
                TextBlock("10/12/2026", x = 340, y = 116, width = 65, page = 1),
                TextBlock("Opening Balance", x = 40, y = 145, width = 95, page = 1),
                TextBlock("Rs. 5,000.00", x = 40, y = 160, width = 75, page = 1),
                TextBlock("Total Amount Due", x = 220, y = 145, width = 105, page = 1),
                TextBlock("Rs. 7,500.50", x = 220, y = 160, width = 80, page = 1),
                TextBlock("Minimum Amount Due", x = 360, y = 145, width = 120, page = 1),
                TextBlock("Rs. 375.00", x = 360, y = 160, width = 70, page = 1),
            ),
        )
        val rawText = page1Rows.flatten().joinToString(" ") { it.text }
        val summary = verifier.extractSummary(page1Rows, rawText)

        assertEquals("HDFC Bank", summary.bankName)
        assertEquals("9876", summary.cardLast4Digits)
        assertEquals("2026-11-20", summary.statementDate)
        assertEquals("2026-12-10", summary.paymentDueDate)
        assertEquals(BigDecimal("5000.00"), summary.openingBalance)
        assertEquals(BigDecimal("7500.50"), summary.totalAmountDue)
        assertEquals(BigDecimal("375.00"), summary.minimumAmountDue)
        assertEquals(BigDecimal("500000.00"), summary.creditLimit)

        // Opening 5000.00 - Credits 2500.00 + Debits 5000.50 = 7500.50
        val balancedTxns = listOf(
            StatementTransaction("2026-11-05", "NETBANKING PAYMENT", BigDecimal("2500.00"), TransactionType.CREDIT, 1),
            StatementTransaction("2026-11-10", "ZOMATO ORDER", BigDecimal("1500.50"), TransactionType.DEBIT, 1),
            StatementTransaction("2026-11-14", "MAKEMYTRIP", BigDecimal("3500.00"), TransactionType.DEBIT, 1),
        )
        val verifiedOutcome = verifier.verifyMath(summary, balancedTxns)
        assertTrue(verifiedOutcome.isMathVerified)
        assertEquals(BigDecimal("5000.50"), verifiedOutcome.totalDebits)
        assertEquals(BigDecimal("2500.00"), verifiedOutcome.totalCredits)
        assertEquals(BigDecimal("0.00"), verifiedOutcome.discrepancyAmount)

        // Check discrepancy detection when a transaction is missing
        val unbalancedOutcome = verifier.verifyMath(
            StatementSummary(totalAmountDue = BigDecimal("9000.00"), openingBalance = BigDecimal("5000.00")),
            balancedTxns,
        )
        assertFalse(unbalancedOutcome.isMathVerified)
        assertEquals(BigDecimal("1499.50"), unbalancedOutcome.discrepancyAmount)

        // Check JSON export
        val json = ParsedStatementResult(
            summary = summary,
            transactions = balancedTxns,
            extractionEngine = ExtractionEngine.PDFBOX_VECTOR,
            totalDebits = verifiedOutcome.totalDebits,
            totalCredits = verifiedOutcome.totalCredits,
            isMathVerified = verifiedOutcome.isMathVerified,
            discrepancyAmount = verifiedOutcome.discrepancyAmount,
        ).toPrettyJson()
        assertTrue(json.contains("\"bankName\": \"HDFC Bank\""))
        assertTrue(json.contains("\"isMathVerified\": true"))
        assertTrue(json.contains("\"7500.50\""))
    }

    @Test
    fun bankPluginRegistryParsesHeaderlessGenericFallback() {
        val rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("12/11/2026", x = 30, y = 100, width = 60, page = 1),
                TextBlock("METRO RAIL TOPUP", x = 105, y = 100, width = 120, page = 1),
                TextBlock("200.00 DR", x = 450, y = 100, width = 65, page = 1),
            ),
        )
        val parsed = registry.parseFallback("Statement without headers", rows)
        assertEquals(1, parsed.size)
        assertEquals("2026-11-12", parsed[0].date)
        assertEquals(BigDecimal("200.00"), parsed[0].amount)
        assertEquals(TransactionType.DEBIT, parsed[0].type)
    }

    @Test
    fun extractsWrappedTwoLineHeadersAndRejectsLimitTrapViaReconciliation() {
        val page1Rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("IndusInd Legend Credit Card XXXX XXXX XXXX 4412", x = 40, y = 50, width = 280, page = 1),
                // Two-line wrapped headers
                TextBlock("Total Credit", x = 40, y = 90, width = 65, page = 1),
                TextBlock("Limit", x = 40, y = 104, width = 32, page = 1),
                TextBlock("Rs. 2,50,000.00", x = 40, y = 122, width = 90, page = 1),

                TextBlock("Statement", x = 170, y = 90, width = 58, page = 1),
                TextBlock("Date", x = 170, y = 104, width = 30, page = 1),
                TextBlock("18/11/2026", x = 170, y = 122, width = 64, page = 1),

                TextBlock("Payment", x = 280, y = 90, width = 52, page = 1),
                TextBlock("Due Date", x = 280, y = 104, width = 54, page = 1),
                TextBlock("08/12/2026", x = 280, y = 122, width = 64, page = 1),

                TextBlock("Total", x = 40, y = 155, width = 32, page = 1),
                TextBlock("Amount Due", x = 40, y = 169, width = 68, page = 1),
                TextBlock("Rs. 4,250.00", x = 40, y = 186, width = 76, page = 1),

                TextBlock("Minimum", x = 180, y = 155, width = 52, page = 1),
                TextBlock("Amount Due", x = 180, y = 169, width = 68, page = 1),
                TextBlock("Rs. 215.00", x = 180, y = 186, width = 64, page = 1),

                TextBlock("Previous Balance", x = 320, y = 169, width = 95, page = 1),
                TextBlock("Rs. 1,000.00", x = 320, y = 186, width = 76, page = 1),
            ),
        )
        val rawText = page1Rows.flatten().joinToString("\n") { it.text }
        val summary = verifier.extractSummary(page1Rows, rawText)
        assertEquals(BigDecimal("250000.00"), summary.creditLimit)
        assertEquals("2026-11-18", summary.statementDate)
        assertEquals("2026-12-08", summary.paymentDueDate)
        assertEquals(BigDecimal("4250.00"), summary.totalAmountDue)
        assertEquals(BigDecimal("215.00"), summary.minimumAmountDue)
        assertEquals(BigDecimal("1000.00"), summary.openingBalance)

        // Simulate a corrupted totalAmountDue equal to Credit Limit; reconcileAndVerify must heal it back to 4,250.00
        val corrupted = summary.copy(totalAmountDue = BigDecimal("250000.00"))
        val txns = listOf(
            StatementTransaction("2026-11-02", "BBPS PAYMENT", BigDecimal("1000.00"), TransactionType.CREDIT, 1),
            StatementTransaction("2026-11-12", "BIGBASKET", BigDecimal("4250.00"), TransactionType.DEBIT, 1),
        )
        val (healed, outcome) = verifier.reconcileAndVerify(corrupted, txns, page1Rows)
        assertEquals(BigDecimal("4250.00"), healed.totalAmountDue)
        assertTrue(outcome.isMathVerified)
        assertEquals(BigDecimal("0.00"), outcome.discrepancyAmount)
    }

    @Test
    fun handlesNegativeAndCrBalanceAndOcrNormalization() {
        val page1Rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
            listOf(
                TextBlock("Citibank Rewards Credit Card XXXX XXXX XXXX 1122", x = 40, y = 50, width = 280, page = 1),
                TextBlock("Statement Date", x = 40, y = 90, width = 85, page = 1),
                TextBlock("22/11/2026", x = 40, y = 106, width = 64, page = 1),
                TextBlock("Payment Due Date", x = 180, y = 90, width = 100, page = 1),
                TextBlock("12/12/2026", x = 180, y = 106, width = 64, page = 1),
                TextBlock("Opening Balance", x = 40, y = 140, width = 90, page = 1),
                TextBlock("Rs. 500.00", x = 40, y = 156, width = 68, page = 1),
                TextBlock("Total Amount Due", x = 180, y = 140, width = 100, page = 1),
                TextBlock("500.00 CR", x = 180, y = 156, width = 68, page = 1),
                TextBlock("Minimum Amount Due", x = 320, y = 140, width = 115, page = 1),
                TextBlock("0.00", x = 320, y = 156, width = 35, page = 1),
            ),
        )
        val rawText = page1Rows.flatten().joinToString("\n") { it.text }
        val summary = verifier.extractSummary(page1Rows, rawText)
        assertEquals(BigDecimal("-500.00"), summary.totalAmountDue)
        assertEquals(BigDecimal("0.00"), summary.minimumAmountDue)

        val txns = listOf(
            StatementTransaction("2026-11-10", "MERCHANT REFUND", BigDecimal("1000.00"), TransactionType.CREDIT, 1),
        )
        val (reconciled, math) = verifier.reconcileAndVerify(summary, txns, page1Rows)
        assertEquals(BigDecimal("-500.00"), reconciled.totalAmountDue)
        assertTrue(math.isMathVerified)

        // Verify OCR normalization and sparse/garbled fallback detection
        assertEquals("₹1,250.50", MlKitOcrFallbackExtractor.normalizeOcrChunk("?1,250.50"))
        assertEquals("₹950.00", MlKitOcrFallbackExtractor.normalizeOcrChunk("₹ 950 . 00"))
        assertTrue(MlKitOcrFallbackExtractor.needsOcrFallback("", 0, page1HasAmountsOrDates = false))
        assertTrue(MlKitOcrFallbackExtractor.needsOcrFallback("Statement Summary", 4, page1HasAmountsOrDates = false))
        assertFalse(MlKitOcrFallbackExtractor.needsOcrFallback("Total Amount Due Rs. 1,250.00 Payment Due Date 10/12/2026 ".repeat(3), 40, page1HasAmountsOrDates = true))
    }

    @Test
    fun parsesAllFiveRealSamplePdfsEndToEndWithZeroMathDiscrepancy() {
        fun paiseToInr(paise: Long): BigDecimal = BigDecimal.valueOf(paise, 2)

        val targets = listOf(
            Triple("ICICI.pdf", "SAMPLE_PDF_ICICI", "ICICI Bank"),
            Triple("AU Bank_unlocked.pdf", "SAMPLE_PDF_AU", "AU Small Finance Bank"),
            Triple("Yes Bank_unlocked.pdf", "SAMPLE_PDF_YES", "Yes Bank"),
            Triple("Axis_unlocked.pdf", "SAMPLE_PDF_AXIS", "Axis Bank"),
            Triple("Slice.pdf", "SAMPLE_PDF_SLICE", "Slice"),
        )
        var ranAny = false
        for ((fileName, envKey, expectedBank) in targets) {
            val spec = com.devanandsb.ccmanager.ingest.DotEnv.get(envKey)?.split('|')
            if (sampleFile(fileName) != null && spec != null && spec.size >= 11) {
                ranAny = true
                val res = parseFullSamplePdf(fileName)!!
                assertEquals(expectedBank, res.summary.bankName)
                assertEquals(spec[0], res.summary.cardLast4Digits)
                assertEquals(spec[1], res.summary.statementDate)
                assertEquals(spec[3], res.summary.paymentDueDate)
                assertEquals(paiseToInr(spec[7].toLong()), res.summary.creditLimit)
                assertEquals(paiseToInr(spec[4].toLong()), res.summary.totalAmountDue)
                assertEquals(paiseToInr(spec[5].toLong()), res.summary.minimumAmountDue)
                assertEquals(paiseToInr(spec[6].toLong()), res.summary.openingBalance)
                assertEquals(spec[8].toInt(), res.transactions.size)
                assertEquals(paiseToInr(spec[9].toLong()), res.totalDebits)
                assertEquals(paiseToInr(spec[10].toLong()), res.totalCredits)
                assertTrue(res.isMathVerified)
                assertEquals(BigDecimal("0.00"), res.discrepancyAmount)
            }
        }
        assumeTrue(ranAny)
    }

    @Test
    fun cvDetectorExtractsSummaryBoxesRuledGridAndIsolatesNeighborBoxes() {
        val w = 595
        val h = 842
        val pixels = ByteArray(w * h) { 255.toByte() }
        fun drawHLine(y: Int, x1: Int, x2: Int, lum: Int = 20) {
            for (dy in 0..1) for (x in x1..x2) pixels[(y + dy) * w + x] = lum.toByte()
        }
        fun drawVLine(x: Int, y1: Int, y2: Int, lum: Int = 20) {
            for (dx in 0..1) for (y in y1..y2) pixels[y * w + (x + dx)] = lum.toByte()
        }
        fun drawBox(x1: Int, y1: Int, x2: Int, y2: Int) {
            drawHLine(y1, x1, x2)
            drawHLine(y2, x1, x2)
            drawVLine(x1, y1, y2)
            drawVLine(x2, y1, y2)
        }

        // Side-by-side summary cards + shaded banner
        drawBox(40, 80, 250, 145)
        drawBox(275, 80, 520, 145)
        for (y in 170..192) for (x in 40..520) pixels[y * w + x] = 212.toByte()

        // Ruled 4-column transaction table
        for (y in listOf(260, 290, 342, 390)) drawHLine(y, 40, 550)
        for (x in listOf(40, 120, 380, 460, 550)) drawVLine(x, 260, 390)

        val geo = TinyCvTableDetector.detectFromGrid(
            TinyCvTableDetector.GrayscaleGrid(w, h, pixels),
            pageNo = 1,
        )
        assertTrue("Expected at least 3 CV boxes, got ${geo.boxes.size}", geo.boxes.size >= 3)
        assertTrue(geo.boxes.any { it.isShaded && it.y in 164..176 })
        val ruledTable = geo.tables.firstOrNull { it.hasRuledLines }
        assertNotNull("Expected ruled CV table grid", ruledTable)
        assertEquals(4, ruledTable!!.columnBounds.size)
        assertTrue("Expected ruled rowBands, got ${ruledTable.rowBands.size}", ruledTable.rowBands.size >= 2)

        // Neighboring box isolation: Box A holds Total Amount Due = 1500.00 below label;
        // Box B sits immediately to the right at same Y with a decoy amount 5258.49.
        val rawBlocks = listOf(
            TextBlock("HDFC Bank Credit Card 4315XXXXXXXX1234", 42, 45, 220, 1),
            TextBlock("Statement Date: 16/11/2026", 42, 60, 130, 1),
            TextBlock("Total Amount Due", 52, 95, 95, 1),
            TextBlock("5258.49", 300, 95, 50, 1), // Same Y, inside Neighbor Box B!
            TextBlock("₹1,500.00", 52, 122, 60, 1), // Inside Box A below label
            TextBlock("Minimum Amount Due", 290, 122, 105, 1),
            TextBlock("₹100.00", 420, 122, 45, 1),
            // Table header inside ruled band 0 (y=260..290)
            TextBlock("Date", 50, 270, 28, 1),
            TextBlock("Description", 140, 270, 68, 1),
            TextBlock("Points", 395, 270, 35, 1),
            TextBlock("Amount", 475, 270, 45, 1),
            // Transaction row inside ruled band 1 (y=290..342) with 24pt wrapped continuation line
            TextBlock("05/11/2026", 48, 300, 56, 1),
            TextBlock("AMAZON PAY INDIA", 130, 300, 110, 1),
            TextBlock("25", 400, 300, 16, 1),
            TextBlock("1,500.00 Dr", 472, 300, 62, 1),
            TextBlock("BANGALORE KA", 130, 324, 90, 1), // 24pt gap > 18pt, rescued by ruled rowBand!
        )
        val tagged = TinyCvTableDetector.assignBlocksToBoxes(rawBlocks, geo.boxes)
        val rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(tagged)
        val summary = verifier.extractSummary(rows.filter { it.first().page == 1 }, tagged.joinToString("\n") { it.text }, rows)
        assertEquals(BigDecimal("1500.00"), summary.totalAmountDue)
        assertEquals(BigDecimal("100.00"), summary.minimumAmountDue)

        val layout = detector.detect(rows, geo.tables)
        assertNotNull(layout)
        val txns = rowParser.parseRows(rows, layout!!, inferredYear = 2026, tables = geo.tables)
        assertEquals(1, txns.size)
        assertEquals("AMAZON PAY INDIA BANGALORE KA", txns[0].description)
        assertEquals(BigDecimal("1500.00"), txns[0].amount)
    }

    @Test
    fun cvDetectorFindsBorderlessGutterTableAndEstimatesSkewAngle() {
        // 1. Borderless 3-column whitespace-gutter table
        val w = 420
        val h = 420
        val gutterPixels = ByteArray(w * h) { 255.toByte() }
        for (rowIdx in 0 until 8) {
            val y0 = 150 + rowIdx * 20
            for (y in y0..(y0 + 6)) {
                for (x in 30..85) gutterPixels[y * w + x] = 30.toByte()
                for (x in 135..260) gutterPixels[y * w + x] = 30.toByte()
                for (x in 320..380) gutterPixels[y * w + x] = 30.toByte()
            }
        }
        val geo = TinyCvTableDetector.detectFromGrid(
            TinyCvTableDetector.GrayscaleGrid(w, h, gutterPixels),
            pageNo = 2,
        )
        val borderless = geo.tables.firstOrNull { !it.hasRuledLines }
        assertNotNull("Expected borderless whitespace-gutter CvTableGrid", borderless)
        assertEquals(3, borderless!!.columnBounds.size)

        // 2. Tilted lines (~2.2 degrees) -> skew estimator detects non-zero tilt
        val sw = 300
        val sh = 300
        val skewPixels = ByteArray(sw * sh) { 255.toByte() }
        val slope = kotlin.math.tan(Math.toRadians(2.2))
        for (lineY in listOf(50, 90, 130, 170, 210, 250)) {
            for (x in 25..275) {
                val y = (lineY - (x - 150) * slope).roundToInt()
                if (y in 2 until (sh - 2)) {
                    skewPixels[y * sw + x] = 10.toByte()
                    skewPixels[(y + 1) * sw + x] = 10.toByte()
                }
            }
        }
        val angle = TinyCvTableDetector.estimateSkewAngleDeg(
            TinyCvTableDetector.GrayscaleGrid(sw, sh, skewPixels),
        )
        assertTrue("Expected skew angle around ~2.2°, got $angle", abs(angle) >= 1.2f)
    }
}
