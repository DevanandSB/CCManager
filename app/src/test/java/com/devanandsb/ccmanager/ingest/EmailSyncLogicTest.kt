package com.devanandsb.ccmanager.ingest

import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.MailSource
import com.devanandsb.ccmanager.data.Payment
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Issuers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmailSyncLogicTest {

    private fun card(
        id: Long,
        issuer: String,
        nickname: String,
        last4: String,
        statementDay: Int = 15,
        dueOffsetDays: Int = 20,
    ) = Card(
        id = id,
        issuer = issuer,
        network = "Visa",
        nickname = nickname,
        last4 = last4,
        statementDay = statementDay,
        dueOffsetDays = dueOffsetDays,
        outstandingPaise = 0L,
        limitPaise = 200_000_00L,
        color = 0xFF0A84FFL,
    )

    @Test
    fun `rejects statement when bank is not added to wallet`() {
        val wallet = listOf(
            card(1L, "HDFC", "Millennia", "4321"),
            card(2L, "ICICI", "Amazon Pay", "8899"),
        )
        val sbiText = """
            SBI Card Monthly Statement
            Card Number: XXXX XXXX XXXX 9012
            Total Amount Due Rs. 12,400.00
        """.trimIndent()

        val matched = StatementSync.matchWalletCardStrict(
            walletCards = wallet,
            pdfIssuer = Issuers.detect(sbiText),
            senderIssuer = "SBI",
            candidateLast4s = Text.allLast4s(sbiText),
            text = sbiText,
        )
        assertNull(matched)
    }

    @Test
    fun `rejects statement when bank is in wallet but last4 belongs to another unadded card`() {
        val wallet = listOf(
            card(1L, "ICICI", "Amazon Pay", "1111"),
            card(2L, "ICICI", "Coral RuPay", "2222"),
        )
        val otherIciciPdf = """
            ICICI Bank Credit Card Statement
            Card No: 4315 XXXX XXXX 9999
            Total Amount Due 4,500.00
        """.trimIndent()

        val matched = StatementSync.matchWalletCardStrict(
            walletCards = wallet,
            pdfIssuer = "ICICI",
            senderIssuer = "ICICI",
            candidateLast4s = Text.allLast4s(otherIciciPdf),
            text = otherIciciPdf,
            rememberedCard = wallet[0],
        )
        assertNull(matched)
    }

    @Test
    fun `never mixes two cards from same bank even with stale remembered sender mapping`() {
        val amazon = card(10L, "ICICI", "Amazon Pay", "1111")
        val coral = card(20L, "ICICI", "Coral", "2222")
        val wallet = listOf(amazon, coral)

        val coralPdf = """
            ICICI Bank Credit Card Statement
            Credit Card Number: 4315 XXXX XXXX 2222
            Statement Date 12 Oct 2026
        """.trimIndent()

        val matched = StatementSync.matchWalletCardStrict(
            walletCards = wallet,
            pdfIssuer = "ICICI",
            senderIssuer = "ICICI",
            candidateLast4s = Text.allLast4s(coralPdf),
            text = coralPdf,
            rememberedCard = amazon,
        )
        assertNotNull(matched)
        assertEquals(coral.id, matched!!.id)
    }

    @Test
    fun `disambiguates using header last4 when body mentions another card`() {
        val primary = card(1L, "HDFC", "Regalia", "4321")
        val secondary = card(2L, "HDFC", "Swiggy", "8765")
        val wallet = listOf(primary, secondary)

        val bodyPadding = List(40) { "12/09/2026 COFFEE SHOP 250.00" }.joinToString("\n")
        val pdfText = """
            HDFC Bank Credit Card Statement
            Card No. 512345XXXXXX4321
            $bodyPadding
            Reward transfer note for XXXX 8765
        """.trimIndent()

        val matched = StatementSync.matchWalletCardStrict(
            walletCards = wallet,
            pdfIssuer = "HDFC",
            senderIssuer = "HDFC",
            candidateLast4s = Text.allLast4s(pdfText),
            text = pdfText,
        )
        assertNotNull(matched)
        assertEquals(primary.id, matched!!.id)
    }

    @Test
    fun `rejects ambiguous statement when multiple cards of same bank and no last4 found`() {
        val wallet = listOf(
            card(1L, "Axis", "Magnus", "3344"),
            card(2L, "Axis", "Atlas", "5566"),
        )
        val noLast4Pdf = "Axis Bank Credit Card Statement\nTotal Amount Due Rs. 8,000.00"

        val matched = StatementSync.matchWalletCardStrict(
            walletCards = wallet,
            pdfIssuer = "Axis",
            senderIssuer = "Axis",
            candidateLast4s = emptyList(),
            text = noLast4Pdf,
        )
        assertNull(matched)
    }

    @Test
    fun `remembered passwords appear before generated patterns and profile patterns include name dob`() {
        val profile = PdfProfile(
            name = "Alex Kumar",
            dob = LocalDate.of(1994, 7, 19),
            pan = "ABCDE1234F",
            mobile = "9876543210",
            templates = listOf("MyCustomSecret99"),
        )
        val candidates = Passwords.candidates(profile, listOf("4321"))
        assertEquals("MyCustomSecret99", candidates.first())
        assertTrue(candidates.contains("ALEX1907"))
        assertTrue(candidates.contains("alex1907"))
        assertTrue(candidates.contains("ABCDE1234F"))
    }

    @Test
    fun `post statement payment settles remaining to pay without relying on sms`() {
        val c = card(5L, "HDFC", "Millennia", "4321").copy(
            outstandingPaise = 1_500_000L,
            statementDay = 15,
            dueOffsetDays = 20,
        )
        val stmtDate = LocalDate.of(2026, 9, 15)
        val dueDate = LocalDate.of(2026, 10, 5)
        val stmt = Statement(
            id = 99L,
            cardId = c.id,
            statementEpochDay = stmtDate.toEpochDay(),
            periodStartEpochDay = stmtDate.minusMonths(1).toEpochDay(),
            dueEpochDay = dueDate.toEpochDay(),
            totalDuePaise = 1_500_000L,
            minDuePaise = 75_000L,
            openingPaise = 0L,
            debitsPaise = 1_500_000L,
            creditsPaise = 0L,
            status = "ok",
            note = "",
            sourceMsgId = "m1",
            fileHash = "h1",
            parser = "hdfc",
            rawText = "",
        )
        val payments = listOf(
            Payment(
                id = 1L,
                cardId = c.id,
                dueEpochDay = dueDate.toEpochDay(),
                paidOnEpochDay = stmtDate.plusDays(2).toEpochDay(),
                amountPaise = 500_000L,
            ),
        )
        val bd = Repo.computePostStatementBreakdown(
            latest = stmt,
            manualPayments = payments,
            liveTxns = emptyList(),
            stmtLines = emptyList(),
        )
        assertEquals(500_000L, bd.paidPaise)

        val due = Billing.dueInfo(c, payments, today = LocalDate.of(2026, 9, 25), statements = listOf(stmt))
        val cw = CardWithDue(card = c.copy(outstandingPaise = 1_000_000L), due = due)
        assertEquals(1_000_000L, cw.totalUsedPaise)
        assertEquals(19_000_000L, cw.availableLimitPaise)
    }

    @Test
    fun `multi account MailSource supports distinct Gmail accounts`() {
        val acc1 = MailSource(user = "personal@example.com")
        val acc2 = MailSource(user = "work@example.com")
        val acc3 = MailSource(user = "bills@example.com")
        val acc4 = MailSource(user = "archive@example.com")
        val accounts = listOf(acc1, acc2, acc3, acc4)
        assertEquals(4, accounts.map { it.user }.distinct().size)
        assertTrue(accounts.all { it.host == "imap.gmail.com" && it.port == 993 })
    }

    @Test
    fun `paytm au bank parser ignores credit limit and prose and extracts exact due and min due`() {
        val sampleAuPdf = """
            AU Small Finance Bank
            Paytm AU Credit Card Statement
            Card Number: XXXX XXXX XXXX 1234
            Statement Date: 25 Nov 2026
            Statement Period: 26 Oct 2026 - 25 Nov 2026
            Credit Limit: ₹50,000   Available Credit: ₹46,603.38   Cash Limit: 15,236
            Total Amount Due (₹)   Minimum Amount Due (₹)   Payment Due Date
            ₹3,396.62              ₹170.00                  15 Dec 2026
            Opening Balance: ₹0.00
            28/10/2026 AMAZON PAY INDIA 2,000.00 Dr
            12/11/2026 SWIGGY INSTAMART 1,396.62 Dr
            Illustrative Example: For example if Total Amount Due is Rs. 15,236 and Minimum Amount Due is Rs. 762
        """.trimIndent()

        val parsed = Parsers.resolve("AU Bank", sampleAuPdf).parse(sampleAuPdf, LocalDate.of(2026, 11, 26))
        assertEquals(339_662L, parsed.totalDuePaise)
        assertEquals(17_000L, parsed.minDuePaise)
        assertEquals(LocalDate.of(2026, 12, 15), parsed.dueDate)
        assertEquals("1234", parsed.last4)
        assertTrue(Parsers.validate(parsed).ok)
    }

    @Test
    fun `email summary parser heals misparsed pdf total using exact email table`() {
        val emailBody = """
            Paytm AU Credit Card E-Statement
            Dear CARDHOLDER,
            Your Statement Summary for a quick reference
            Total Amount Due (₹)   Minimum Amount Due (₹)   Payment Due Date
            ₹3,396.62              ₹170.00                  15 Dec 2026
        """.trimIndent()

        val summary = EmailSummaryParser.parse(emailBody, LocalDate.of(2026, 11, 26))
        assertNotNull(summary)
        assertEquals(339_662L, summary!!.totalDuePaise)
        assertEquals(17_000L, summary.minDuePaise)
        assertEquals(LocalDate.of(2026, 12, 15), summary.dueDate)

        // Simulate an adversarial broken PDF where 15,236 was picked instead of 3,396.62
        val brokenPdfParsed = ParsedStatement(
            statementDate = LocalDate.of(2026, 11, 25),
            periodStart = LocalDate.of(2026, 10, 26),
            dueDate = LocalDate.of(2026, 12, 15),
            totalDuePaise = 1_523_600L,
            minDuePaise = 17_000L,
            openingPaise = 0L,
            last4 = "1234",
            lines = listOf(ParsedLine(LocalDate.of(2026, 11, 10), "PURCHASE", 339_662L, false)),
            creditLimitPaise = 1_523_600L,
        )
        val merged = Parsers.mergeWithEmailSummary(brokenPdfParsed, summary, "Total Amount Due ₹3,396.62 Minimum Due ₹170.00")
        assertEquals(339_662L, merged.totalDuePaise)
        assertEquals(17_000L, merged.minDuePaise)
        assertEquals(LocalDate.of(2026, 12, 15), merged.dueDate)
    }

    @Test
    fun `amazon pay icici statement mentioning american express is detected as icici and matches wallet card`() {
        val iciciAmazonPdf = """
            ICICI Bank
            Amazon Pay ICICI Bank Credit Card Statement
            Card Number: 4315 XXXX XXXX 5678
            Statement Date: 15 Nov 2026
            Payment Due Date: 03 Dec 2026
            Credit Limit: ₹1,80,000   Available Credit: ₹1,67,550.00
            Total Amount Due: ₹12,450.00
            Minimum Amount Due: ₹625.00
            18/10/2026 AMAZON PAY INDIA 7,450.00 Dr
            02/11/2026 SWIGGY 5,000.00 Dr
            Ways to Pay: NEFT / RTGS / Click to Pay (Visa, Mastercard, American Express network guidelines apply).
            Important Terms & Conditions: American Express and RuPay settlement notices appear below.
        """.trimIndent()

        val detectedIssuer = Issuers.detect(iciciAmazonPdf)
        assertEquals("ICICI Bank", detectedIssuer)

        val parsed = Parsers.resolve(detectedIssuer!!, iciciAmazonPdf).parse(iciciAmazonPdf, LocalDate.of(2026, 11, 16))
        assertEquals("5678", parsed.last4)
        assertEquals(18_000_000L, parsed.creditLimitPaise)
        assertEquals(1_245_000L, parsed.totalDuePaise)

        // Matches wallet cards stored with either canonical "ICICI Bank" or legacy short "ICICI"
        val canonicalWallet = listOf(card(1L, "ICICI Bank", "Amazon Pay", "5678"))
        val matchedCanonical = StatementSync.matchWalletCardStrict(
            walletCards = canonicalWallet,
            pdfIssuer = detectedIssuer,
            senderIssuer = "ICICI Bank",
            candidateLast4s = Text.allLast4s(iciciAmazonPdf),
            text = iciciAmazonPdf,
        )
        assertNotNull(matchedCanonical)
        assertEquals(1L, matchedCanonical!!.id)

        val shortWallet = listOf(card(2L, "ICICI", "Amazon Pay", "5678"))
        val matchedShort = StatementSync.matchWalletCardStrict(
            walletCards = shortWallet,
            pdfIssuer = detectedIssuer,
            senderIssuer = "ICICI Bank",
            candidateLast4s = Text.allLast4s(iciciAmazonPdf),
            text = iciciAmazonPdf,
        )
        assertNotNull(matchedShort)
        assertEquals(2L, matchedShort!!.id)
    }

    @Test
    fun `credit limit extracts whole rupee and stacked column layouts accurately`() {
        val inlineWhole = """
            ICICI Bank Credit Card Statement
            Card Number: 4315 XXXX XXXX 5678
            Assigned Credit Limit ₹250000
        """.trimIndent()
        assertEquals(25_000_000L, Text.creditLimit(inlineWhole.lines()))

        val stackedBlock = """
            HDFC Bank Credit Card Statement
            Credit Limit
            Available Credit
            Cash Limit
            2,40,000.00   1,98,500.00   24,000.00
        """.trimIndent()
        assertEquals(24_000_000L, Text.creditLimit(stackedBlock.lines()))
    }

    @Test
    fun `password candidates prioritize card linked passwords ahead of pattern guesses`() {
        val profile = PdfProfile(
            name = "ALEX MORGAN",
            dob = LocalDate.of(1994, 8, 14),
            cardPasswords = listOf(
                RememberedPdfPassword("ICICI_CUSTOM_PW", cardId = 12L, cardLabel = "Amazon Pay · •• 5678"),
            ),
        )
        val candidates = Passwords.candidates(profile, listOf("5678"))
        assertEquals("ICICI_CUSTOM_PW", candidates.first())
        assertTrue(candidates.contains("ALEX1408"))
    }
}
