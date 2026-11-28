package com.devanandsb.ccmanager.ingest

import com.devanandsb.ccmanager.statementparser.data.cv.TinyCvTableDetector
import com.devanandsb.ccmanager.statementparser.domain.model.CvTableGrid
import com.devanandsb.ccmanager.statementparser.domain.model.RegionBox
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class StatementParserTest {

    private fun extractPdfWithLayout(file: File): Pair<String, List<PageLayout>> {
        val pages = mutableListOf<PageLayout>()
        val text = PDDocument.load(file).use { doc ->
            val stripper = object : PDFTextStripper() {
                private val glyphs = mutableListOf<GlyphBox>()

                init {
                    sortByPosition = true
                }

                override fun startPage(page: PDPage) {
                    super.startPage(page)
                    glyphs.clear()
                }

                override fun processTextPosition(text: TextPosition) {
                    super.processTextPosition(text)
                    val ch = text.unicode ?: return
                    if (ch.isEmpty()) return
                    val x = text.xDirAdj
                    val w = text.widthDirAdj
                    val y = text.yDirAdj
                    val sw = text.widthOfSpace.takeIf { it > 0f } ?: 3.5f
                    glyphs += GlyphBox(x, x + w, y, sw, ch)
                }

                override fun endPage(page: PDPage) {
                    super.endPage(page)
                    val box = page.mediaBox
                    pages += PageLayout(
                        page = currentPageNo,
                        width = box?.width ?: 595f,
                        height = box?.height ?: 842f,
                        spans = PageLayoutBuilder.buildSpans(currentPageNo, glyphs),
                    )
                }
            }
            stripper.getText(doc)
        }
        return text to pages
    }

    private fun sampleFile(name: String): File? {
        val root = listOf(File("."), File("..")).firstOrNull { File(it, "sample_pdfs").isDirectory } ?: return null
        return File(root, "sample_pdfs/$name").takeIf { it.isFile }
    }

    private fun sampleSpec(envKey: String): List<String>? {
        val raw = DotEnv.get(envKey) ?: return null
        return raw.split('|')
    }

    private fun verifySamplePdf(
        fileName: String,
        envKey: String,
        expectedParser: String,
        extraCheck: (ParsedStatement, ParsedStatement) -> Unit = { _, _ -> },
    ) {
        val file = sampleFile(fileName)
        assumeTrue("sample_pdfs/$fileName present", file != null)
        val spec = sampleSpec(envKey)
        assumeTrue("$envKey set in .env", spec != null && spec.size >= 11)
        val parts = spec!!
        val (text, pages) = extractPdfWithLayout(file!!)
        val parser = Parsers.resolve("Other", text)
        assertEquals(expectedParser, parser.name)

        val spatialParsed = parser.parse(text, received = null, pages = pages)
        val textParsed = parser.parse(text, received = null)

        for (p in listOf(spatialParsed, textParsed)) {
            assertEquals(parts[0], p.last4)
            assertEquals(LocalDate.parse(parts[1]), p.statementDate)
            assertEquals(LocalDate.parse(parts[2]), p.periodStart)
            assertEquals(LocalDate.parse(parts[3]), p.dueDate)
            assertEquals(parts[4].toLong(), p.totalDuePaise)
            assertEquals(parts[5].toLong(), p.minDuePaise)
            assertEquals(parts[6].toLong(), p.openingPaise)
            assertEquals(parts[7].toLong(), p.creditLimitPaise)
            assertEquals(parts[8].toInt(), p.lines.size)
            assertEquals(parts[9].toLong(), p.debits)
            assertEquals(parts[10].toLong(), p.credits)
            parts.getOrNull(11)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { assertEquals(it, p.rewards?.pointsEarned) }
            parts.getOrNull(12)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { assertEquals(it, p.rewards?.pointsBalance) }
            parts.getOrNull(13)?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { assertEquals(it, p.rewards?.cashbackPaise) }
            parts.getOrNull(14)?.takeIf { it.isNotBlank() }?.let { assertEquals(it, p.rewards?.unit) }
            assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
        }
        extraCheck(spatialParsed, textParsed)
    }

    @Test
    fun parsesIciciStatementSpatiallyAndPlainText() {
        val spec = sampleSpec("SAMPLE_PDF_ICICI")
        verifySamplePdf("ICICI.pdf", "SAMPLE_PDF_ICICI", "icici") { spatial, textParsed ->
            val extra1 = spec?.getOrNull(15).orEmpty().split('@')
            val extra2 = spec?.getOrNull(16).orEmpty().split('@')
            for (p in listOf(spatial, textParsed)) {
                if (extra1.size == 2) {
                    assertTrue(p.lines.any { it.description == extra1[0] && it.amountPaise == extra1[1].toLong() })
                }
                if (extra2.size == 3) {
                    assertTrue(
                        p.lines.any {
                            it.description == extra2[0] && !it.isCredit &&
                                it.amountPaise == extra2[1].toLong() && it.date == LocalDate.parse(extra2[2])
                        },
                    )
                }
            }
        }
    }

    @Test
    fun parsesAuBankStatementSpatiallyAndPlainText() {
        val spec = sampleSpec("SAMPLE_PDF_AU")
        verifySamplePdf("AU Bank_unlocked.pdf", "SAMPLE_PDF_AU", "au") { spatial, textParsed ->
            val firstLine = spec?.getOrNull(15).orEmpty().split('@')
            if (firstLine.size == 3) {
                for (p in listOf(spatial, textParsed)) {
                    assertEquals(LocalDate.parse(firstLine[0]), p.lines[0].date)
                    assertEquals(firstLine[1], p.lines[0].description)
                    assertTrue(p.lines[0].isCredit)
                    assertEquals(firstLine[2].toLong(), p.lines[0].amountPaise)
                }
            }
        }
    }

    @Test
    fun parsesYesBankStatementSpatiallyAndPlainText() {
        val spec = sampleSpec("SAMPLE_PDF_YES")
        verifySamplePdf("Yes Bank_unlocked.pdf", "SAMPLE_PDF_YES", "yes") { spatial, _ ->
            val cat1 = spec?.getOrNull(15).orEmpty()
            val cat2 = spec?.getOrNull(16).orEmpty()
            if (cat1.isNotBlank()) {
                assertEquals(cat1, spatial.lines[1].categoryHint)
                assertFalse(spatial.lines[1].description.contains(cat1))
            }
            if (cat2.isNotBlank()) {
                assertEquals(cat2, spatial.lines[2].categoryHint)
                assertFalse(spatial.lines[2].description.contains(cat2))
            }
        }
    }

    @Test
    fun parsesAxisBankStatementSpatiallyAndPlainText() {
        val spec = sampleSpec("SAMPLE_PDF_AXIS")
        verifySamplePdf("Axis_unlocked.pdf", "SAMPLE_PDF_AXIS", "axis") { spatial, textParsed ->
            val desc = spec?.getOrNull(15).orEmpty()
            val cat = spec?.getOrNull(16).orEmpty()
            if (desc.isNotBlank() && cat.isNotBlank()) {
                for (p in listOf(spatial, textParsed)) {
                    assertEquals(desc, p.lines[1].description)
                    assertEquals(cat, p.lines[1].categoryHint)
                }
            }
        }
    }

    @Test
    fun parsesSliceStatementSpatiallyAndPlainText() {
        verifySamplePdf("Slice.pdf", "SAMPLE_PDF_SLICE", "slice")
    }

    @Test
    fun reconcilesUnseenBankLayoutViaAccountingIdentity() {
        // Simulated unseen bank spatial layout where a misleading Credit Limit & neighboring value appear near labels,
        // while the true Total Amount Due (₹2,984.00) sits elsewhere on Page 1 and satisfies Opening - Credits + Debits.
        val syntheticPages = listOf(
            PageLayout(
                page = 1,
                width = 595f,
                height = 842f,
                spans = listOf(
                    TextSpan(1, 40f, 220f, 80f, "Kotak Royale Credit Card 4111XXXXXXXX7788"),
                    TextSpan(1, 40f, 140f, 120f, "Statement Date: 18/11/2026"),
                    TextSpan(1, 240f, 340f, 120f, "Payment Due Date: 08/12/2026"),
                    TextSpan(1, 40f, 140f, 160f, "Previous Balance"),
                    TextSpan(1, 40f, 100f, 176f, "Rs. 500.00"),
                    TextSpan(1, 200f, 300f, 160f, "Credit Limit"),
                    TextSpan(1, 200f, 280f, 176f, "Rs. 1,50,000.00"),
                    TextSpan(1, 40f, 140f, 220f, "Payable Summary"),
                    TextSpan(1, 40f, 120f, 236f, "Rs. 2,984.00"),
                    TextSpan(1, 200f, 320f, 220f, "Minimum Amount Due"),
                    TextSpan(1, 200f, 260f, 236f, "Rs. 150.00"),
                    TextSpan(1, 40f, 70f, 300f, "Date"),
                    TextSpan(1, 120f, 240f, 300f, "Description"),
                    TextSpan(1, 460f, 520f, 300f, "Amount"),
                    TextSpan(1, 40f, 95f, 320f, "02/11/2026"),
                    TextSpan(1, 120f, 260f, 320f, "BBPS PAYMENT THANK YOU"),
                    TextSpan(1, 470f, 520f, 320f, "500.00 Cr"),
                    TextSpan(1, 40f, 95f, 340f, "10/11/2026"),
                    TextSpan(1, 120f, 240f, 340f, "SWIGGY BANGALORE"),
                    TextSpan(1, 470f, 520f, 340f, "2,984.00 Dr"),
                ),
            ),
        )
        val rawText = syntheticPages.flatMap { it.spans }.joinToString("\n") { it.text }
        val parsed = Parsers.resolve("Other", rawText).parse(rawText, pages = syntheticPages)
        assertEquals(298_400L, parsed.totalDuePaise)
        assertEquals(15_000L, parsed.minDuePaise)
        assertEquals(15_000_000L, parsed.creditLimitPaise)
        assertEquals(2, parsed.lines.size)
        assertTrue(Parsers.validate(parsed).note, Parsers.validate(parsed).ok)
    }

    @Test
    fun extractsWrappedTwoLineHeadersAndPage2HeaderlessContinuationTable() {
        val pages = listOf(
            PageLayout(
                page = 1,
                width = 595f,
                height = 842f,
                spans = listOf(
                    TextSpan(1, 40f, 240f, 60f, "Federal Bank Celesta Card XXXX XXXX XXXX 5544"),
                    // Two-line wrapped summary headers
                    TextSpan(1, 40f, 105f, 100f, "Total Credit"),
                    TextSpan(1, 40f, 75f, 114f, "Limit"),
                    TextSpan(1, 40f, 120f, 132f, "Rs. 3,00,000.00"),

                    TextSpan(1, 180f, 240f, 100f, "Statement"),
                    TextSpan(1, 180f, 210f, 114f, "Date"),
                    TextSpan(1, 180f, 245f, 132f, "20/11/2026"),

                    TextSpan(1, 310f, 365f, 100f, "Payment"),
                    TextSpan(1, 310f, 365f, 114f, "Due Date"),
                    TextSpan(1, 310f, 375f, 132f, "10/12/2026"),

                    TextSpan(1, 40f, 75f, 165f, "Total"),
                    TextSpan(1, 40f, 110f, 179f, "Amount Due"),
                    TextSpan(1, 40f, 110f, 196f, "Rs. 1,250.00"),

                    TextSpan(1, 180f, 232f, 165f, "Minimum"),
                    TextSpan(1, 180f, 250f, 179f, "Amount Due"),
                    TextSpan(1, 180f, 240f, 196f, "Rs. 100.00"),

                    // Table header on Page 1
                    TextSpan(1, 40f, 75f, 250f, "Date"),
                    TextSpan(1, 140f, 240f, 250f, "Description"),
                    TextSpan(1, 450f, 510f, 250f, "Amount"),
                    TextSpan(1, 40f, 100f, 275f, "05/11/2026"),
                    TextSpan(1, 140f, 260f, 275f, "ZOMATO BANGALORE"),
                    TextSpan(1, 450f, 510f, 275f, "450.00 Dr"),
                ),
            ),
            // Page 2 has NO repeated header — continuation rows must still be extracted
            PageLayout(
                page = 2,
                width = 595f,
                height = 842f,
                spans = listOf(
                    TextSpan(2, 40f, 100f, 70f, "12/11/2026"),
                    TextSpan(2, 140f, 260f, 70f, "AMAZON RETAIL INDIA"),
                    TextSpan(2, 450f, 510f, 70f, "800.00 Dr"),
                ),
            ),
        )
        val rawText = pages.flatMap { it.spans }.joinToString("\n") { it.text }
        val parsed = Parsers.resolve("Federal Bank", rawText).parse(rawText, pages = pages)
        assertEquals("5544", parsed.last4)
        assertEquals(30_000_000L, parsed.creditLimitPaise)
        assertEquals(LocalDate.of(2026, 11, 20), parsed.statementDate)
        assertEquals(LocalDate.of(2026, 12, 10), parsed.dueDate)
        assertEquals(125_000L, parsed.totalDuePaise)
        assertEquals(10_000L, parsed.minDuePaise)
        assertEquals(2, parsed.lines.size)
        assertEquals(125_000L, parsed.debits)
        assertTrue(Parsers.validate(parsed).note, Parsers.validate(parsed).ok)
    }

    @Test
    fun parsesInternationalUsDateAndUsdStatementAndReconcilesZeroOpening() {
        val pages = listOf(
            PageLayout(
                page = 1,
                width = 612f,
                height = 792f,
                spans = listOf(
                    TextSpan(1, 40f, 280f, 60f, "Chase Sapphire Reserve Card ending 3309"),
                    TextSpan(1, 40f, 180f, 100f, "Statement Date: 11/24/2026"),
                    TextSpan(1, 260f, 400f, 100f, "Payment Due Date: 12/18/2026"),
                    TextSpan(1, 40f, 180f, 130f, "Credit Limit: $10,000.00"),
                    // Misleading label/total trap or omitted label where Page 1 has true due $245.50
                    TextSpan(1, 260f, 420f, 130f, "Payable Summary"),
                    TextSpan(1, 260f, 330f, 146f, "$245.50"),
                    TextSpan(1, 40f, 180f, 165f, "Minimum Payment Due: $35.00"),
                    TextSpan(1, 40f, 90f, 220f, "Post Date"),
                    TextSpan(1, 140f, 250f, 220f, "Merchant Name"),
                    TextSpan(1, 460f, 520f, 220f, "Amount"),
                    TextSpan(1, 40f, 100f, 245f, "11/14/2026"),
                    TextSpan(1, 140f, 260f, 245f, "WHOLE FOODS MARKET"),
                    TextSpan(1, 460f, 520f, 245f, "$145.50"),
                    TextSpan(1, 40f, 100f, 265f, "11/19/2026"),
                    TextSpan(1, 140f, 260f, 265f, "UBER TECHNOLOGIES"),
                    TextSpan(1, 460f, 520f, 265f, "$100.00"),
                ),
            ),
        )
        val rawText = pages.flatMap { it.spans }.joinToString("\n") { it.text }
        val parsed = Parsers.resolve("Other", rawText).parse(rawText, pages = pages)
        assertEquals("3309", parsed.last4)
        assertEquals(LocalDate.of(2026, 11, 24), parsed.statementDate)
        assertEquals(LocalDate.of(2026, 12, 18), parsed.dueDate)
        assertEquals(1_000_000L, parsed.creditLimitPaise)
        assertEquals(24_550L, parsed.totalDuePaise)
        assertEquals(3_500L, parsed.minDuePaise)
        assertEquals(2, parsed.lines.size)
        assertEquals(24_550L, parsed.debits)
        assertTrue(Parsers.validate(parsed).note, Parsers.validate(parsed).ok)
    }

    @Test
    fun recoversFromOcrNoiseAndReconcilesZeroOpeningDirectly() {
        val noisyAmount = com.devanandsb.ccmanager.statementparser.data.extractor.MlKitOcrFallbackExtractor
            .normalizeOcrChunk("? 3,450 . 25")
        assertEquals("₹3,450.25", noisyAmount)

        val base = ParsedStatement(
            statementDate = LocalDate.of(2026, 11, 20),
            periodStart = LocalDate.of(2026, 10, 21),
            dueDate = LocalDate.of(2026, 12, 10),
            totalDuePaise = 10_000_000L, // Limit trap!
            minDuePaise = 15_000L,
            openingPaise = null,
            last4 = "7788",
            lines = listOf(
                ParsedLine(LocalDate.of(2026, 11, 5), "AIRTEL POSTPAID", 100_000L, isCredit = false),
                ParsedLine(LocalDate.of(2026, 11, 8), "AMAZON INDIA", 200_000L, isCredit = false),
            ),
            creditLimitPaise = 10_000_000L,
        )
        val reconciled = StatementReconciler.reconcile(
            base,
            StatementReconciler.Context(
                page1NumericCandidates = listOf(10_000_000L, 300_000L, 15_000L),
                limitPaise = 10_000_000L,
            ),
        )
        assertEquals(300_000L, reconciled.totalDuePaise)
        assertEquals(15_000L, reconciled.minDuePaise)
        assertTrue(Parsers.validate(reconciled).note, Parsers.validate(reconciled).ok)
    }

    @Test
    fun isolatesBoxesAndMergesRuledRowBandsInIngestPipeline() {
        val boxes = listOf(
            RegionBox(id = 1001, page = 1, x = 35, y = 95, width = 180, height = 55),
            RegionBox(id = 1002, page = 1, x = 230, y = 95, width = 180, height = 55),
        )
        val table = CvTableGrid(
            page = 1,
            xStart = 40,
            yStart = 210,
            xEnd = 520,
            yEnd = 310,
            columnBounds = listOf(40..120, 121..420, 421..520),
            rowBands = listOf(210..240, 241..305),
            hasRuledLines = true,
        )
        val rawSpans = listOf(
            TextSpan(1, 40f, 240f, 55f, "IndusInd Legend Credit Card 4111XXXXXXXX8899"),
            TextSpan(1, 40f, 160f, 75f, "Statement Date: 20/11/2026"),
            TextSpan(1, 240f, 380f, 75f, "Payment Due Date: 10/12/2026"),
            TextSpan(1, 45f, 165f, 108f, "Total Amount Due"),
            // Adjacent Box 2 decoy value on exact same Y row! Must be blocked by boxId isolation.
            TextSpan(1, 245f, 330f, 108f, "₹9,999.00"),
            // True value inside Box 1 below label
            TextSpan(1, 45f, 120f, 132f, "₹1,999.00"),
            TextSpan(1, 45f, 90f, 222f, "Date"),
            TextSpan(1, 140f, 230f, 222f, "Description"),
            TextSpan(1, 440f, 500f, 222f, "Amount"),
            // Row inside ruled rowBand 241..305 with a 24pt wrapped continuation line
            TextSpan(1, 45f, 105f, 252f, "12/11/2026"),
            TextSpan(1, 135f, 280f, 252f, "BLUE TOKAI COFFEE"),
            TextSpan(1, 440f, 510f, 252f, "1,999.00 Dr"),
            TextSpan(1, 135f, 240f, 276f, "MUMBAI MH"),
        )
        val taggedSpans = TinyCvTableDetector.assignSpansToBoxes(rawSpans, boxes)
        val pages = listOf(
            PageLayout(
                page = 1,
                width = 595f,
                height = 842f,
                spans = taggedSpans,
                boxes = boxes,
                tables = listOf(table),
            ),
        )
        val rawText = taggedSpans.joinToString("\n") { it.text }
        val parsed = Parsers.resolve("Other", rawText).parse(rawText, pages = pages)
        assertEquals(199_900L, parsed.totalDuePaise)
        assertEquals(1, parsed.lines.size)
        assertEquals("BLUE TOKAI COFFEE MUMBAI MH", parsed.lines[0].description)
        assertTrue(Parsers.validate(parsed).note, Parsers.validate(parsed).ok)
    }
}
