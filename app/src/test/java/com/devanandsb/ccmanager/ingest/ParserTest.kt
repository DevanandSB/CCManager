package com.devanandsb.ccmanager.ingest

import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.Payment
import com.devanandsb.ccmanager.data.Repo
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.data.Txn
import com.devanandsb.ccmanager.domain.Billing
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.ui.TxnDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic statements only. No real statement text belongs in this repo. */
class ParserTest {

    // HDFC 2025+ layout: ₹ extracted as "C", "+" after the description marks credits, a trailing "l"
    // category bullet, and the summary as an equation row whose labels sit two lines above.
    private val hdfcLayout = """
        Sample HDFC Bank Credit Card Statement
        TEST USER Credit Card No. 512345XXXXXX4321
        Statement Date 15 Sep, 2026
        Billing Period 16 Aug, 2026 - 15 Sep, 2026
        PAYMENTS/CREDITS PURCHASES/DEBIT
        PREVIOUS STATEMENT DUES FINANCE CHARGES TOTAL AMOUNT DUE
        RECEIVED (Current Billing Cycle)
        _
        C5,000.00 C5,012.00 + C2,734.40 + C0.00 = C2,722.40
        TOTAL CREDIT LIMIT
        AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT
        (Including Cash) MINIMUM DUE DUE DATE
        C140.00 05 Oct, 2026
        C1,00,000 C97,277 C40,000
        Domestic Transactions
        DATE & TIME TRANSACTION DESCRIPTION AMOUNT PI
        17/08/2026| 18:35 BPPY CC PAYMENT DP0000000001 (Ref# ST000000000000001) +  C 5,000.00 l
        17/08/2026| 18:35 SAMPLE GROCER BANGALORE  C 410.00 l
        19/08/2026| 00:00 10% Sample CashBack +  C 12.00 l
        22/08/2026| 00:00 10% Sample CashBack_Reversal  C 37.40 l
        22/08/2026| 07:35 PYU*SampleShopBengaluru  C 1,996.00 l
        Page 1 of 2
        DATE & TIME TRANSACTION DESCRIPTION AMOUNT PI
        13/09/2026| 12:04 Sample FoodBENGALURU  C 291.00 l
        Page 2 of 2
        SR NO. TRANSACTION AMOUNT
        1 10% Sample CashBack C 12.00
    """.trimIndent()

    // slice app-style layout (synthetic): spaced card mask, no year in the period, two-line rows.
    private val sliceLayout = """
        Sample's
        Credit card statement
        X X X X  X X X X  X X X X  1 2 3 4
        15 OCT - 14 NOV
        ₹590.50 Repay
        Due on 1 Dec
        Statement summary
        Spends ₹701.50
        Refunds & repayments ₹100.00
        Cashback ₹11.00
        Interest ₹0.00
        Surcharge ₹0.00
        Total amount due ₹590.50
        Min amount due ₹29.53
        Spends
        Sample store ₹600
        S
        14 Nov '26 • UPI
        Corner cafe ₹101.50
        16 Oct '26 • UPI
        Refunds & repayments
        Sample store ₹100
        S
        23 Oct '26
        Cashback
        Corner cafe ₹11
        C
        11 Nov '26
        monies
        Earned 701
        15 Oct - 14 Nov
        GST details
        IGST ₹0
    """.trimIndent()

    @Test fun sliceLayout() {
        val p = Parsers.forIssuer("Slice").parse(sliceLayout)
        assertEquals(LocalDate.of(2026, 11, 14), p.statementDate)
        assertEquals(LocalDate.of(2026, 10, 15), p.periodStart)
        assertEquals(LocalDate.of(2026, 12, 1), p.dueDate)
        assertEquals(59_050L, p.totalDuePaise)
        assertEquals(2_953L, p.minDuePaise)
        assertEquals("1234", p.last4)
        assertEquals(4, p.lines.size)
        assertEquals(listOf(false, false, true, true), p.lines.map { it.isCredit })
        assertEquals(701L, p.rewards?.pointsEarned)
        assertEquals(1_100L, p.rewards?.cashbackPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Period crossing the year end, with every row still in December.
    @Test fun sliceYearWrap() {
        val text = sliceLayout
            .replace("15 OCT - 14 NOV", "15 DEC - 14 JAN").replace("Due on 1 Dec", "Due on 3 Feb")
            .replace("14 Nov '26", "20 Dec '26").replace("16 Oct '26", "16 Dec '26")
            .replace("23 Oct '26", "23 Dec '26").replace("11 Nov '26", "28 Dec '26")
        for (received in listOf(null, LocalDate.of(2027, 1, 15))) {
            val p = Parsers.forIssuer("Slice").parse(text, received)
            assertEquals(LocalDate.of(2027, 1, 14), p.statementDate)
            assertEquals(LocalDate.of(2026, 12, 15), p.periodStart)
            assertEquals(LocalDate.of(2027, 2, 3), p.dueDate)
        }
    }

    // Overpaid card: "Cr" after the header amounts means a credit balance, not money owed.
    @Test fun creditBalanceIsNotDue() {
        val text = """
            Statement Period : 06 Oct 2026 to 05 Nov 2026
            Card Number XXXX XXXX XXXX 1234
            Total Amount Due: Rs. 700.00 Cr
            Minimum Amount Due: Rs. 0.00
            Payment Due Date: 25 Nov 2026
            Previous Balance 1,000.00 Cr
            10 Oct 2026 UBER INDIA 500.00 D
            18 Oct 2026 REFUND MYNTRA 200.00 C
        """.trimIndent()
        val p = Parsers.forIssuer("SBI Card").parse(text)
        assertEquals(-70_000L, p.totalDuePaise)
        assertEquals(-100_000L, p.openingPaise)
        assertEquals(0L, p.minDuePaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun hdfcWrappedPaymentAndRewards() {
        val text = """
            Statement Date 15 Nov, 2026
            Billing Period 16 Oct, 2026 - 15 Nov, 2026
            Credit Card No. 00361010XXXX9999
            PREVIOUS STATEMENT DUES FINANCE CHARGES TOTAL AMOUNT DUE
            _
            C100.00 C100.00 + C563.02 + C0.00 = C563.02
            (Including Cash) MINIMUM DUE DUE DATE
            C200.00 05 Dec, 2026
            Reward Points Opening Balance Feature + Bonus Reward Disbursed Adjusted/Lapsed
            Points Earned
            1,008 1,000 8 0 0
            DATE & TIME TRANSACTION DESCRIPTION REWARDS AMOUNT PI
            15/10/2026| 12:47 SAMPLE TICKETS GURGAON + 8  C 563.02 l
            CC PAYMENT 000000000000 PayZapp (Ref#
            19/10/2026| 14:30 +  C 100.00 l
            00000000000000000000001)
            Page 1 of 2
        """.trimIndent()
        val p = Parsers.forIssuer("HDFC Bank").parse(text)
        assertEquals("9999", p.last4)
        assertEquals("SAMPLE TICKETS GURGAON", p.lines[0].description)
        assertTrue(p.lines[1].isCredit)
        assertTrue(p.lines[1].description.startsWith("CC PAYMENT"))
        assertEquals(1_008L, p.rewards?.pointsBalance)
        assertEquals(8L, p.rewards?.pointsEarned)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Unity SFB / Roarbank ledger layout (synthetic), including the illustration table that repeats labels.
    @Test fun unityLayout() {
        val text = """
            Roarbank
            Statement Period: 01.10.2026 - 31.10.2026
            Bill Date: 01.11.2026
            Card number: **** **** **** 1234
            Saving Account:
            Balance at the beginning of period: 0.00
            Credit Card:
            Sanctioned Credit Limit: 100,000.00
            Total Amount Due (TAD): 50.00
            Minimum Amount Due (MAD) including past dues if available: 50.00
            Payment Due Date: 27.11.2026
            Balance at the beginning of period: 99,900.00
            Date Description Credit Debit
            Amount Amount Fee Balance
            01-10-2026
            11:14:05 someone@upi 100.00 - - 100,000.00
            23-10-2026
            09:51:09 Sample Movies - 150.00 - 99,850.00
            23-10-2026
            09:52:01 Cashback 100.00 - - 99,950.00
            Minimum Amount due (MAD): INR 700
            Payment Due Date 28th July
        """.trimIndent()
        val p = Parsers.forIssuer("Unity Bank (Roar)").parse(text)
        assertEquals(LocalDate.of(2026, 11, 1), p.statementDate)
        assertEquals(LocalDate.of(2026, 11, 27), p.dueDate)
        assertEquals(5_000L, p.totalDuePaise)
        assertEquals(5_000L, p.minDuePaise)
        assertEquals(10_000L, p.openingPaise)
        assertEquals("1234", p.last4)
        assertEquals(3, p.lines.size)
        assertEquals(10_000L, p.rewards?.cashbackPaise)
        assertEquals(10_000_000L, p.creditLimitPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Limit raised from 1,00,000 to 1,50,000 mid-cycle: once as an explicit row, once only as a jump in Balance.
    @Test fun unityLimitChange() {
        fun stmt(limitRow: Boolean) = """
            Statement Period: 01.10.2026 - 31.10.2026
            Bill Date: 01.11.2026
            Card number: **** **** **** 1234
            Credit Card:
            Sanctioned Credit Limit: 150,000.00
            Total Amount Due (TAD): 1,200.00
            Minimum Amount Due (MAD) including past dues if available: 60.00
            Payment Due Date: 27.11.2026
            Balance at the beginning of period: 99,000.00
            05-10-2026
            10:00:00 Sample Store - 200.00 - 98,800.00
            ${if (limitRow) "12-10-2026\n10:00:00 Credit limit enhancement 50,000.00 - - 148,800.00" else ""}
            20-10-2026
            10:00:00 Sample Cafe - ${if (limitRow) "0.00" else "0.00"} - ${if (limitRow) "148,800.00" else "148,800.00"}
        """.trimIndent()
        for (row in listOf(true, false)) {
            val p = Parsers.forIssuer("Unity Bank (Roar)").parse(stmt(row))
            assertEquals("opening with row=$row", 100_000L, p.openingPaise) // 1,00,000 − 99,000 at the start
            assertTrue("no limit row as a transaction (row=$row)", p.lines.none { it.description.contains("limit", true) })
            assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
        }
    }

    @Test fun hdfcLayout() {
        val p = Parsers.forIssuer("HDFC Bank").parse(hdfcLayout)
        assertEquals(LocalDate.of(2026, 9, 15), p.statementDate)
        assertEquals(LocalDate.of(2026, 10, 5), p.dueDate)
        assertEquals(272_240L, p.totalDuePaise)
        assertEquals(14_000L, p.minDuePaise)
        assertEquals(500_000L, p.openingPaise)
        assertEquals("4321", p.last4)
        assertEquals(6, p.lines.size)
        assertEquals(listOf(true, false, true, false, false, false), p.lines.map { it.isCredit })
        assertEquals("SAMPLE GROCER BANGALORE", p.lines[1].description)
        assertEquals(41_000L, p.lines[1].amountPaise)
        assertEquals(10_000_000L, p.creditLimitPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    // Column layout: labels on one line, values on the next.
    private val columnLayout = """
        Credit Card Statement
        Card No: 4321 XXXX XXXX 9876
        Statement Date Payment Due Date Total Dues Minimum Amount Due
        05/11/2026 25/11/2026 12,450.50 625.00
        Opening Balance 3,000.00
        Domestic Transactions
        Date Transaction Description Amount (in Rs.)
        08/10/2026 SWIGGY BANGALORE 450.00
        12/10/2026 AMAZON PAY INDIA PVT LTD 8,999.00
        15/10/2026 PAYMENT RECEIVED - THANK YOU 3,000.00 Cr
        20/10/2026 BIGBASKET 2,999.50
        02/11/2026 IGST ON FEES 2.00
    """.trimIndent()

    // Inline layout with month-name dates and a period line.
    private val inlineLayout = """
        Statement Period : 06 Oct 2026 to 05 Nov 2026
        Card Number XXXX XXXX XXXX 1234
        Total Amount Due: Rs. 5,500.00
        Minimum Amount Due: Rs. 275.00
        Payment Due Date: 25 Nov 2026
        Previous Balance 1,000.00
        10 Oct 2026 UBER INDIA 500.00 D
        11 Oct 2026 NETFLIX COM 649.00 D
        18 Oct 2026 REFUND MYNTRA 149.00 C
        21 Oct 2026 IRCTC TICKET 3,500.00 D
    """.trimIndent()

    @Test fun columnLayoutAddsUp() {
        val p = Parsers.forIssuer("HDFC Bank").parse(columnLayout)
        assertEquals(LocalDate.of(2026, 11, 5), p.statementDate)
        assertEquals(LocalDate.of(2026, 11, 25), p.dueDate)
        assertEquals(1_245_050L, p.totalDuePaise)
        assertEquals(62_500L, p.minDuePaise)
        assertEquals(300_000L, p.openingPaise)
        assertEquals("9876", p.last4)
        assertEquals(5, p.lines.size)
        assertTrue(p.lines.single { it.description.startsWith("PAYMENT") }.isCredit)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun inlineLayoutAddsUp() {
        val p = Parsers.forIssuer("SBI Card").parse(inlineLayout)
        assertEquals(LocalDate.of(2026, 10, 6), p.periodStart)
        assertEquals(LocalDate.of(2026, 11, 5), p.statementDate)
        assertEquals(LocalDate.of(2026, 11, 25), p.dueDate)
        assertEquals(550_000L, p.totalDuePaise)
        assertEquals(27_500L, p.minDuePaise)
        assertEquals("1234", p.last4)
        assertEquals(4, p.lines.size)
        assertTrue(p.lines.single { it.description.contains("MYNTRA") }.isCredit)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun missingLineIsFlaggedNotImported() {
        val p = Parsers.forIssuer("SBI Card").parse(inlineLayout.lines().filterNot { it.contains("IRCTC") }.joinToString("\n"))
        val v = Parsers.validate(p)
        assertFalse(v.ok)
        assertTrue(v.note.contains("off by"))
    }

    @Test fun txnKinds() {
        assertEquals(TxnKind.PAYMENT, TxnKind.classify("BPPY CC PAYMENT DP0000000001", true))
        assertEquals(TxnKind.PAYMENT, TxnKind.classify("PAYMENT RECEIVED - THANK YOU", true))
        assertEquals(TxnKind.REFUND, TxnKind.classify("REFUND MYNTRA", true))
        assertEquals(TxnKind.REFUND, TxnKind.classify("Sample store", true))
        assertEquals(TxnKind.CASHBACK, TxnKind.classify("10% Sample CashBack", true))
        assertEquals(TxnKind.CASHBACK, TxnKind.classify("10% Sample CashBack_Reversal", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("IGST ON FEES", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("Fee: someone@upi", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("FINANCE CHARGES", false))
        assertEquals(TxnKind.SPEND, TxnKind.classify("AIRTEL RECHARGE", false))
        assertEquals(TxnKind.SPEND, TxnKind.classify("SWIGGY BANGALORE", false))
        // HDFC's "convert to EMI" link label is not an EMI line.
        assertEquals("Flipkart Internet PVTBanglore", Text.stripEmiLink("EMI Flipkart Internet PVTBanglore"))
        assertEquals(TxnKind.SPEND, TxnKind.classify("EMI Flipkart Internet PVTBanglore", false))
        assertEquals("EMI INTEREST 2/6", Text.stripEmiLink("EMI INTEREST 2/6"))
        assertEquals("EMI PRINCIPAL 2/6", Text.stripEmiLink("EMI PRINCIPAL 2/6"))
        assertEquals("EMI 2/6 SAMPLE STORE", Text.stripEmiLink("EMI 2/6 SAMPLE STORE"))
        assertEquals(TxnKind.FEE, TxnKind.classify("EMI INTEREST 2/6", false))
        assertEquals(TxnKind.FEE, TxnKind.classify("EMI PROCESSING FEE", false))
        // Refunds reduce spending; payments and cashback don't count.
        assertEquals(-1, TxnKind.spendSign(TxnKind.REFUND, true))
        assertEquals(0, TxnKind.spendSign(TxnKind.PAYMENT, true))
        assertEquals(0, TxnKind.spendSign(TxnKind.CASHBACK, true))
    }

    @Test fun passwordCandidates() {
        val c = Passwords.candidates(PdfProfile(name = "Dash Kumar", dob = LocalDate.of(1990, 5, 14)), listOf("1234"))
        assertTrue("DASH1405" in c)
        assertTrue("dash1405" in c)
        assertTrue("140519901234" in c)
        assertFalse(c.any { it.contains("{") })
    }

    @Test fun creditLimitNotConfusedWithTotalDue() {
        val layoutWithUnmatchedColumn = """
            Credit Card Statement
            Card No: 4321 XXXX XXXX 5555
            Statement Date 15/11/2026
            Payment Due Date 05/12/2026
            Credit Limit Available Credit Total Amount Due Minimum Amount Due
            1,50,000.00 1,48,600.00 1,400.00 100.00
            Opening Balance 0.00
            10/11/2026 AMAZON PAY INDIA PVT LTD 1,400.00
        """.trimIndent()
        val p1 = Parsers.forIssuer("Axis Bank").parse(layoutWithUnmatchedColumn)
        assertEquals(140_000L, p1.totalDuePaise)

        val layoutWithLimitEquation = """
            Credit Card Statement
            Card No: 4321 XXXX XXXX 6666
            Statement Date: 15 Nov 2026
            Payment Due Date: 05 Dec 2026
            Credit Limit 1,50,000.00 - Unbilled 0.00 - Dues 0.00 = Available Credit Limit 1,50,000.00
            Total Amount Due: Rs. 1,400.00
            Opening Balance 0.00
            10 Nov 2026 FLIPKART INTERNET 1,400.00 D
        """.trimIndent()
        val p2 = Parsers.forIssuer("ICICI Bank").parse(layoutWithLimitEquation)
        assertEquals(140_000L, p2.totalDuePaise)
    }

    @Test fun billingDueShowsPendingEvenWhenPriorDuePassedBeforeAdd() {
        val today = LocalDate.of(2026, 11, 25)
        val createdMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val cardWithPending = Card(
            id = 1L,
            nickname = "Test Card",
            issuer = "HDFC Bank",
            network = "Visa",
            last4 = "1234",
            limitPaise = 10_000_000L,
            outstandingPaise = 140_000L,
            statementDay = 1,
            dueOffsetDays = 20,
            color = 0xFF1E3A8AL,
            createdAt = createdMillis,
            billingSet = true,
        )
        val dueWithPending = Billing.dueInfo(cardWithPending, emptyList(), today = today)
        assertFalse("Must NOT mark All Paid when outstanding > 0", dueWithPending.lastCyclePaid)

        val settledCard = cardWithPending.copy(outstandingPaise = 0L)
        val dueWhenZero = Billing.dueInfo(settledCard, emptyList(), today = today)
        assertTrue(dueWhenZero.lastCyclePaid)
    }

    @Test fun txnDisplayFriendlyNames() {
        val base = Txn(cardId = 1, statementId = 1, epochDay = 20000, merchant = "", amountPaise = 100_00, isCredit = false, categoryId = null, dedupeKey = "k")
        assertEquals("Amazon Pay", TxnDisplay.format(base.copy(merchant = "PYU*AMAZON PAY INDIA PVT LTD BANGALORE IN")).title)
        assertEquals("Flipkart", TxnDisplay.format(base.copy(merchant = "EMI Flipkart Internet PVTBanglore")).title)
        assertEquals("Flipkart", TxnDisplay.format(base.copy(merchant = "FKRT*FLIPKART PAYMENTS BANGALORE")).title)
        assertEquals("Rahul Sharma", TxnDisplay.format(base.copy(merchant = "UPI/P2P/43219876/RAHUL SHARMA/PAY")).title)
        assertEquals("Priya Verma", TxnDisplay.format(base.copy(merchant = "UPI/DR/987654/PRIYA VERMA/SBIN/OK")).title)
        assertEquals("Payment received", TxnDisplay.format(base.copy(merchant = "BPPY CC PAYMENT DP0000000001 (Ref# ST0001)", isCredit = true, kind = TxnKind.PAYMENT)).title)
    }

    @Test fun missingOpeningStillValidAndMultiFormatTxnsParse() {
        val statementWithoutOpening = """
            Credit Card Statement
            Card No: XXXX XXXX XXXX 7788
            Statement Date: 15 Nov 2026
            Payment Due Date: 05 Dec 2026
            Total Amount Due: Rs. 2,899.00
            Minimum Amount Due: Rs. 150.00
            1 12 Nov AMAZON PAY INDIA 1,400.00 Dr 28
            13 Nov '26 FKRT*FLIPKART PAYMENTS ₹999
            2026-11-14
            UPI/P2P/123456/PRIYA VERMA ₹500
            15 Nov 2026 BPPY CC PAYMENT THANK YOU 1,000.00 (Cr)
        """.trimIndent()
        val p = Parsers.forIssuer("AU Bank").parse(statementWithoutOpening)
        assertEquals(4, p.lines.size)
        assertEquals("AMAZON PAY INDIA", p.lines[0].description)
        assertEquals(140_000L, p.lines[0].amountPaise)
        assertFalse(p.lines[0].isCredit)
        assertEquals(99_900L, p.lines[1].amountPaise)
        assertEquals(50_000L, p.lines[2].amountPaise)
        assertEquals("Payment received", TxnDisplay.format(Txn(cardId = 1, statementId = 1, epochDay = p.lines[3].date.toEpochDay(), merchant = p.lines[3].description, amountPaise = p.lines[3].amountPaise, isCredit = p.lines[3].isCredit, categoryId = null, dedupeKey = "x", kind = TxnKind.classify(p.lines[3].description, p.lines[3].isCredit))).title)
        assertTrue(p.lines[3].isCredit)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)

        val appStyleStatement = """
            CSB Edge+ Credit Card Statement
            XXXX XXXX XXXX 9012
            Statement Date: 15 Nov 2026
            Payment Due Date: 05 Dec 2026
            Total Amount Due: ₹1,850.00
            Amazon Pay ₹1,350
            12 Nov '26 • Shopping
            Flipkart ₹500
            13 Nov '26 • Shopping
        """.trimIndent()
        val p2 = Parsers.forIssuer("CSB Bank (Jupiter Edge+)").parse(appStyleStatement)
        assertEquals(2, p2.lines.size)
        assertEquals(135_000L, p2.lines[0].amountPaise)
        assertEquals("Shopping", p2.lines[0].categoryHint)
        assertEquals(50_000L, p2.lines[1].amountPaise)
        assertEquals("Shopping", p2.lines[1].categoryHint)
        assertTrue(Parsers.validate(p2).note, Parsers.validate(p2).ok)

        val mccStatement = """
            Credit Card Statement
            Card No: XXXX XXXX XXXX 3344
            Statement Date: 15 Nov 2026
            Payment Due Date: 05 Dec 2026
            Total Amount Due: Rs. 450.00
            12 Nov 2026 UPI/P2M/1234/SHRI RAM TIFFIN MCC 5812 450.00 Dr
        """.trimIndent()
        val p3 = Parsers.forIssuer("Axis Bank").parse(mccStatement)
        assertEquals(1, p3.lines.size)
        assertEquals("5812", p3.lines[0].mcc)
        assertEquals("UPI/P2M/1234/SHRI RAM TIFFIN", p3.lines[0].description)
    }

    @Test fun amazonPayIciciLimitNeverLeaksIntoTotalDueAndDatesMatch() {
        // Interleaved 2-column ICICI PDF layout where Total Amount Due sits next to Credit Limit (Including cash),
        // dates have comma without space, backtick ` replaces ₹, and lines have SerNo + trailing Reward Points.
        val amazonPayIciciText = """
            Amazon Pay ICICI Bank Credit Card Statement
            Card Number : 4375 XXXX XXXX 5678
            STATEMENT DATE : November 18,2026
            PAYMENT DUE DATE : December 6, 2026
            Total Amount due Credit Limit (Including cash)
            `1,40,000.00
            Available Credit (Including cash)
            `1,38,600.00
            STATEMENT SUMMARY
            Total Amount due = Previous Balance + Purchases / Charges + Cash Advances - Payments / Credits
            `1,400.00 = `0.00 + `1,400.00 + `0.00 - `0.00
            Minimum Amount due : `100.00
            DATE SerNo. TRANSACTION DETAILS Reward Points Amount (in `)
            12/11/2026 987654321 AMAZON PAY INDIA PVT LTD 14 1,400.00
        """.trimIndent()

        val p = Parsers.forIssuer("ICICI Bank").parse(amazonPayIciciText)
        assertEquals("5678", p.last4)
        assertEquals(LocalDate.of(2026, 11, 18), p.statementDate)
        assertEquals(LocalDate.of(2026, 12, 6), p.dueDate)
        assertEquals(14_000_000L, p.creditLimitPaise)
        assertEquals(140_000L, p.totalDuePaise)
        assertEquals(10_000L, p.minDuePaise)
        assertEquals(0L, p.openingPaise)
        assertEquals(1, p.lines.size)
        assertEquals("AMAZON PAY INDIA PVT LTD", p.lines[0].description)
        assertEquals(140_000L, p.lines[0].amountPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun configuredBillingDatesPreservedWhenStatementWindowDiffers() {
        val today = LocalDate.of(2026, 11, 25)
        val createdMillis = today.minusMonths(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val card = Card(
            id = 2L,
            nickname = "Amazon Pay",
            issuer = "ICICI Bank",
            network = "Visa",
            last4 = "5678",
            limitPaise = 14_000_000L,
            outstandingPaise = 140_000L,
            statementDay = 18,
            dueOffsetDays = 18,
            color = 0xFFAE282EL,
            createdAt = createdMillis,
            billingSet = true,
        )
        // A statement whose parsed statementEpochDay fell outside the +-5d cycle window (e.g. imported mid-cycle)
        // must NOT overwrite the user's configured 18th statement date / +18d due date.
        val offWindowStmt = Statement(
            cardId = 2L,
            statementEpochDay = LocalDate.of(2026, 11, 25).toEpochDay(),
            periodStartEpochDay = null,
            dueEpochDay = LocalDate.of(2026, 12, 15).toEpochDay(),
            totalDuePaise = 140_000L,
            minDuePaise = 10_000L,
            openingPaise = null,
            debitsPaise = 140_000L,
            creditsPaise = 0L,
            status = com.devanandsb.ccmanager.data.Ledger.STATUS_OK,
            note = "",
            sourceMsgId = "",
            fileHash = "h",
            parser = "icici",
            rawText = "",
        )
        val due = Billing.dueInfo(card, emptyList(), today = today, statements = listOf(offWindowStmt))
        assertEquals(LocalDate.of(2026, 11, 18), due.statementDate)
        assertEquals(LocalDate.of(2026, 12, 6), due.dueDate)
    }

    @Test fun iciciParserHandlesSplitDatesScrambledCreditLimitAndGenericFallback() {
        val scrambledIcici = """
            Amazon Pay ICICI Bank Credit Card
            Card Number: 4375 XXXX XXXX 5678
            STATEMENT DATE PAYMENT DUE DATE
            18/Nov/2026
            06/12/2026
            Total Amount due Minimum Amount due
            `1,40,000.00 `1,38,600.00
            STATEMENT SUMMARY
            Previous Balance Purchases / Charges Cash Advances Payments / Credits
            `0 `1,400 `0 `0
            Credit Limit (Including cash) Available Credit (Including cash) Cash Limit Available Cash
            (Including cash) (Including cash)
            `1,40,000.00 `1,38,600.00 `14,000.00 `14,000.00
            Minimum Amount due: ₹100
            12/11/2026 998877665 AMAZON PAY INDIA 14 1,400.00
        """.trimIndent()

        // Even when the user saved their card under "Other", auto-detection routes to IciciParser.
        val p = Parsers.resolve("Other", scrambledIcici).parse(scrambledIcici)
        assertEquals("icici", Parsers.resolve("Other", scrambledIcici).name)
        assertEquals(LocalDate.of(2026, 11, 18), p.statementDate)
        assertEquals(LocalDate.of(2026, 12, 6), p.dueDate)
        assertEquals(14_000_000L, p.creditLimitPaise)
        assertEquals(140_000L, p.totalDuePaise)
        assertEquals(10_000L, p.minDuePaise)
        assertEquals(1, p.lines.size)
        assertEquals(140_000L, p.lines[0].amountPaise)
        assertTrue(Parsers.validate(p).note, Parsers.validate(p).ok)
    }

    @Test fun iciciStackedHeaderMixedPeriodWrappedRowsPaymentsCrAndZeroDue() {
        val iciciStacked = """
            Amazon Pay ICICI Bank Credit Card
            Card Number: 4375 XXXXXXXX 5678
            STATEMENT PERIOD STATEMENT DATE PAYMENT DUE DATE
            19/10/2026 - 18/11/2026 18/11/2026 06/12/2026
            Your Total Amount Due
            Minimum Amount Due
            `4,580.00
            `230.00
            Credit Limit (Including cash) Available Credit (Including cash)
            `1,40,000 `1,35,420
            STATEMENT SUMMARY
            Previous Balance Purchases/ Charges Cash Advances Payments/ Credits
            `5,000.00 `4,580.00 `0.00 `5,000.00 CR
            Total Amount due = Previous Balance + Purchases / Charges + Cash Advances - Payments / Credits
            `4,580.00 `5,000.00 `4,580.00 `0.00 `5,000.00
            DATE SerNo. TRANSACTION DETAILS Reward Points Amount (in `)
            25/10/2026 10548293001 BBPS PAYMENT RECEIVED - 5,000.00 CR
            12/11/2026 10548293012 AMAZON PAY INDIA PRIVATE
            LIMITED BANGALORE 45 4,580.00
            Illustrative Example
            Total Amount Due: Rs. 10,000.00
        """.trimIndent()

        val p1 = Parsers.forIssuer("ICICI Bank").parse(iciciStacked)
        assertEquals("5678", p1.last4)
        assertEquals(LocalDate.of(2026, 10, 19), p1.periodStart)
        assertEquals(LocalDate.of(2026, 11, 18), p1.statementDate)
        assertEquals(LocalDate.of(2026, 12, 6), p1.dueDate)
        assertEquals(14_000_000L, p1.creditLimitPaise)
        assertEquals(458_000L, p1.totalDuePaise)
        assertEquals(23_000L, p1.minDuePaise)
        assertEquals(500_000L, p1.openingPaise)
        assertEquals(2, p1.lines.size)
        assertTrue(p1.lines[0].isCredit)
        assertFalse(p1.lines[1].isCredit)
        assertEquals(458_000L, p1.lines[1].amountPaise)
        assertTrue(Parsers.validate(p1).note, Parsers.validate(p1).ok)

        // Zero-due ICICI statement with Credit Limit = Available Credit = 1,40,000 and 0 transactions must NOT pick Credit Limit!
        val iciciZeroDue = """
            ICICI Bank Credit Card Statement
            Card Number: 4375 XXXX XXXX 5678
            STATEMENT DATE : 18/11/2026
            PAYMENT DUE DATE : 06/12/2026
            Credit Limit (Including cash) Available Credit (Including cash)
            `1,40,000.00 `1,40,000.00
            Previous Balance Purchases / Charges Cash Advances Payments / Credits
            `0.00 `0.00 `0.00 `0.00
            Your Total Amount Due Credit Limit
            `1,40,000.00
            Minimum Amount Due: `0.00
        """.trimIndent()
        val pZero = Parsers.forIssuer("ICICI Bank").parse(iciciZeroDue)
        assertEquals(14_000_000L, pZero.creditLimitPaise)
        assertEquals(0L, pZero.totalDuePaise)
        assertTrue(Parsers.validate(pZero).note, Parsers.validate(pZero).ok)
    }

    @Test fun parsesPreAmountCrTrailingCurrencyWrappedRowsAndStatementBreakdown() {
        val mixedRowsPdf = """
            ICICI Bank Credit Card Statement
            Card Number: 4375 XXXXXXXX 9001
            STATEMENT DATE : 18/11/2026
            PAYMENT DUE DATE : 06/12/2026
            Your Total Amount Due : `1,024.00
            Minimum Amount Due : `100.00
            Credit Limit : `1,40,000.00
            Opening Balance Purchases Charges Payments Credits Total Amount Due
            `5,000.00 `1,649.00 `5,625.00 `1,024.00
            DATE DETAILS AMOUNT
            25/10/2026 AUTODEBIT PYMT RECD Cr 5,000.00
            10/11/2026 SWIGGY INSTAMART 450.00 INR
            11/11/2026
            AMAZON PAY INDIA
            PRIVATE LIMITED
            699.00
            12/11/2026 FLIPKART REFUND 500.00 INR Cr
            13/11/2026 AMAZON PAY CASHBACK 125.00 Cr
            15/11/2026 LATE PAYMENT FEE 500.00
        """.trimIndent()

        val parsed = Parsers.forIssuer("ICICI Bank").parse(mixedRowsPdf)
        assertEquals(6, parsed.lines.size)

        val autoDebitPay = parsed.lines[0]
        assertTrue(autoDebitPay.isCredit)
        assertEquals("AUTODEBIT PYMT RECD", autoDebitPay.description)
        assertEquals(500_000L, autoDebitPay.amountPaise)
        assertEquals(TxnKind.PAYMENT, TxnKind.classify(autoDebitPay.description, autoDebitPay.isCredit))

        val swiggy = parsed.lines[1]
        assertFalse(swiggy.isCredit)
        assertEquals(45_000L, swiggy.amountPaise)

        val fourLineAmazon = parsed.lines[2]
        assertFalse(fourLineAmazon.isCredit)
        assertEquals(69_900L, fourLineAmazon.amountPaise)

        val refund = parsed.lines[3]
        assertTrue(refund.isCredit)
        assertEquals(50_000L, refund.amountPaise)
        assertEquals(TxnKind.REFUND, TxnKind.classify(refund.description, refund.isCredit))

        val cashback = parsed.lines[4]
        assertTrue(cashback.isCredit)
        assertEquals(12_500L, cashback.amountPaise)
        assertEquals(TxnKind.CASHBACK, TxnKind.classify(cashback.description, cashback.isCredit))

        val fee = parsed.lines[5]
        assertFalse(fee.isCredit)
        assertEquals(50_000L, fee.amountPaise)
        assertEquals(TxnKind.FEE, TxnKind.classify(fee.description, fee.isCredit))

        val stmt = com.devanandsb.ccmanager.data.Statement(
            id = 1L, cardId = 2L,
            statementEpochDay = LocalDate.of(2026, 11, 18).toEpochDay(),
            periodStartEpochDay = LocalDate.of(2026, 10, 19).toEpochDay(),
            dueEpochDay = LocalDate.of(2026, 12, 6).toEpochDay(),
            totalDuePaise = 102_400L,
            minDuePaise = 10_000L,
            creditLimitPaise = 14_000_000L,
            openingPaise = 500_000L,
            debitsPaise = 164_900L,
            creditsPaise = 562_500L,
            status = "ok", note = "", sourceMsgId = "", fileHash = "hmixed", parser = "icici", rawText = "",
        )
        val txnEntities = parsed.lines.mapIndexed { idx, l ->
            Txn(
                id = idx + 1L,
                cardId = 2L,
                statementId = 1L,
                epochDay = l.date.toEpochDay(),
                merchant = l.description,
                amountPaise = l.amountPaise,
                isCredit = l.isCredit,
                categoryId = null,
                dedupeKey = "k$idx",
                kind = TxnKind.classify(l.description, l.isCredit),
            )
        }
        val postPay = Txn(
            id = 99L, cardId = 2L, statementId = null,
            epochDay = LocalDate.of(2026, 11, 20).toEpochDay(),
            merchant = "Payment received", amountPaise = 102_400L, isCredit = true,
            categoryId = null, dedupeKey = "postp", kind = TxnKind.PAYMENT,
        )
        val breakdown = com.devanandsb.ccmanager.ui.computeStatementBreakdown(
            st = stmt,
            lines = txnEntities,
            cardPayments = emptyList(),
            cardLiveTxns = listOf(postPay),
            isLatestForCard = true,
            outstandingPaise = 0L,
        )
        assertEquals(114_900L, breakdown.spentPaise)           // 450 + 699 (excludes 500 fee)
        assertEquals(50_000L, breakdown.feesPaise)             // 500 late fee
        assertEquals(500_000L, breakdown.paidInStatementPaise) // 5,000 AUTODEBIT PYMT RECD
        assertEquals(102_400L, breakdown.paidAfterBillPaise)   // 1,024 post-statement payment
        assertEquals(50_000L, breakdown.refundPaise)           // 500 Flipkart refund
        assertEquals(12_500L, breakdown.cashbackPaise)         // 125 Amazon Pay cashback
    }

    @Test fun parsesVariantStatementDateFormatsSmallDueAndInterleavedHeaderRows() {
        val variantPdf = """
            HDFC Bank Credit Card Statement
            Card No: 5123 XXXX XXXX 4321
            Stmt. Date
            18.Nov.2026 Credit Limit 1,00,000.00
            Statement Period: 19 Oct - 18 Nov 2026
            Payment Due Date: Nov 18 '26
            Total Amount Due: 0
            Minimum Amount Due
            230
            10/11/2026 AMAZON INDIA 230.00
        """.trimIndent()

        // First test dot-month date, interleaved DATE+AMOUNT next line, yearless period start, and zero/small whole header amounts
        val parsed = Parsers.forIssuer("HDFC Bank").parse(variantPdf)
        assertEquals(LocalDate.of(2026, 11, 18), parsed.statementDate)
        assertEquals(LocalDate.of(2026, 10, 19), parsed.periodStart)
        assertEquals(LocalDate.of(2026, 11, 18), parsed.dueDate)
        assertEquals(0L, parsed.totalDuePaise)
        assertEquals(23_000L, parsed.minDuePaise)

        // Billing.dueInfo must keep a newly synced unpaid cycle visible even when dueEpochDay is null
        val today = LocalDate.of(2026, 11, 25)
        val syncedCard = Card(
            id = 5L,
            nickname = "Amazon Pay",
            issuer = "ICICI Bank",
            network = "Visa",
            last4 = "9001",
            limitPaise = 14_000_000L,
            outstandingPaise = 145_200L,
            statementDay = 18,
            dueOffsetDays = 18,
            color = 0xFFF58220L,
            createdAt = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            billingSet = true,
        )
        val noDueDateStmt = com.devanandsb.ccmanager.data.Statement(
            id = 2L, cardId = 5L,
            statementEpochDay = LocalDate.of(2026, 11, 18).toEpochDay(),
            periodStartEpochDay = null,
            dueEpochDay = null,
            totalDuePaise = 145_200L,
            minDuePaise = 7_300L,
            openingPaise = null, debitsPaise = 145_200L, creditsPaise = 0L,
            status = com.devanandsb.ccmanager.data.Ledger.STATUS_OK,
            note = "", sourceMsgId = "", fileHash = "hnd", parser = "icici", rawText = "",
        )
        val info = Billing.dueInfo(syncedCard, emptyList(), today = today, statements = listOf(noDueDateStmt))
        assertEquals(LocalDate.of(2026, 11, 18), info.statementDate)
        assertEquals(LocalDate.of(2026, 12, 6), info.dueDate)
        assertFalse(info.lastCyclePaid)
    }

    @Test fun parsesAllMajorIndianBankStatementDesignsEndToEnd() {
        // 1) Axis Bank: PAYMENT SUMMARY + Total Payment Due + MERCHANT CATEGORY column + Dr/Cr + Cashback
        val axisPdf = """
            Axis Bank Credit Card Statement
            Card Number : 4375 XXXX XXXX 1122
            Statement Period : 12/10/2026 - 11/11/2026
            Statement Date : 11/11/2026
            Payment Due Date : 01/12/2026
            Total Payment Due : 1,244.00 Dr
            Minimum Payment Due : 100.00 Dr
            Credit Limit : 1,20,000.00
            Previous Balance : 0.00
            DATE TRANSACTION DETAILS MERCHANT CATEGORY AMOUNT (Rs.)
            15/10/2026 FLIPKART PAYMENTS BANGALORE DEPT STORES 1,499.00 Dr
            18/10/2026 CASHBACK CREDIT MISCELLANEOUS 255.00 Cr
        """.trimIndent()
        val axisParsed = Parsers.resolve("Other", axisPdf).parse(axisPdf)
        assertEquals("axis", Parsers.resolve("Other", axisPdf).name)
        assertEquals("1122", axisParsed.last4)
        assertEquals(LocalDate.of(2026, 11, 11), axisParsed.statementDate)
        assertEquals(LocalDate.of(2026, 12, 1), axisParsed.dueDate)
        assertEquals(124_400L, axisParsed.totalDuePaise)
        assertEquals(12_000_000L, axisParsed.creditLimitPaise)
        assertEquals(2, axisParsed.lines.size)
        assertEquals("FLIPKART PAYMENTS BANGALORE", axisParsed.lines[0].description)
        assertEquals("DEPT STORES", axisParsed.lines[0].categoryHint)
        assertFalse(axisParsed.lines[0].isCredit)
        assertTrue(axisParsed.lines[1].isCredit)
        assertEquals(25_500L, axisParsed.rewards?.cashbackPaise)
        assertTrue(Parsers.validate(axisParsed).note, Parsers.validate(axisParsed).ok)

        // 2) American Express (Amex): 15-digit XXXX-XXXXXX-81001 mask, Statement Closing Date, Please Pay By, tight minus credit, Membership Rewards
        val amexPdf = """
            American Express Membership Rewards Credit Card Statement
            Card Number XXXX-XXXXXX-81001
            Statement Closing Date: 15 Nov 2026
            Please Pay By: 05 Dec 2026
            Opening Balance 5,000.00
            Closing Balance 2,200.00
            Minimum Payment Due 150.00
            Membership Rewards Opening Earned Redeemed Closing
            4,000 120 0 4,120
            18 Oct 2026 PAYMENT RECEIVED - THANK YOU -5,000.00
            25 Oct 2026 STARBUCKS INDIA 2,200.00
        """.trimIndent()
        val amexParsed = Parsers.forIssuer("Amex").parse(amexPdf)
        assertEquals("1001", amexParsed.last4)
        assertEquals(LocalDate.of(2026, 11, 15), amexParsed.statementDate)
        assertEquals(LocalDate.of(2026, 12, 5), amexParsed.dueDate)
        assertEquals(220_000L, amexParsed.totalDuePaise)
        assertEquals(15_000L, amexParsed.minDuePaise)
        assertEquals(2, amexParsed.lines.size)
        assertTrue(amexParsed.lines[0].isCredit)
        assertFalse(amexParsed.lines[1].isCredit)
        assertEquals(120L, amexParsed.rewards?.pointsEarned)
        assertEquals(4_120L, amexParsed.rewards?.pointsBalance)
        assertTrue(Parsers.validate(amexParsed).note, Parsers.validate(amexParsed).ok)

        // 3) HDFC Tata Neu with NeuCoins summary + BOB Card + OneCard + Standard Chartered lookup match
        val hdfcNeuPdf = """
            HDFC Bank Credit Card Statement
            Card No: 5123 XXXX XXXX 4321
            Statement Date: 15 Nov 2026
            Payment Due Date: 05 Dec 2026
            Total Dues: ₹1,000.00
            Minimum Amount Due: ₹100.00
            Opening Balance: ₹0.00
            NeuCoins Opening Earned Redeemed Closing
            500 50 0 550
            10/11/2026 BIGBASKET C 1,000.00
        """.trimIndent()
        val hdfcNeuParsed = Parsers.forIssuer("HDFC Bank").parse(hdfcNeuPdf)
        assertEquals(100_000L, hdfcNeuParsed.totalDuePaise)
        assertEquals(50L, hdfcNeuParsed.rewards?.pointsEarned)
        assertEquals(550L, hdfcNeuParsed.rewards?.pointsBalance)
        assertEquals("NeuCoins", hdfcNeuParsed.rewards?.unit)

        // 4) SBI Cashback Card + Cashback Summary + Account Summary
        val sbiCbPdf = """
            SBI Card Monthly Statement
            Card Number: XXXX XXXX XXXX 5544
            Statement Date: 05 Nov 2026
            Payment Due Date: 25 Nov 2026
            Total Outstanding: Rs. 1,900.00
            Minimum Amount Due: Rs. 100.00
            Credit Limit: Rs. 1,00,000.00
            Cashback Summary
            Total Cashback Earned Rs. 100.00
            10 Oct 2026 AMAZON SHOPPING 2,000.00 D
            15 Oct 2026 CASHBACK CREDIT 100.00 C
        """.trimIndent()
        val sbiParsed = Parsers.resolve("Other", sbiCbPdf).parse(sbiCbPdf)
        assertEquals("sbi", Parsers.resolve("Other", sbiCbPdf).name)
        assertEquals(190_000L, sbiParsed.totalDuePaise)
        assertEquals(10_000L, sbiParsed.rewards?.cashbackPaise)
        assertEquals(2, sbiParsed.lines.size)
        assertFalse(sbiParsed.lines[0].isCredit)
        assertTrue(sbiParsed.lines[1].isCredit)

        // Issuer registry aligns with Issuers.all keys cleanly
        assertEquals("onecard", Parsers.forIssuer("OneCard").name)
        assertEquals("csb", Parsers.forIssuer("CSB Bank").name)
        assertEquals("bob", Parsers.forIssuer("BOB Card").name)
        assertEquals("sc", Parsers.forIssuer("Standard Chartered").name)
        assertEquals("au", Parsers.forIssuer("Paytm").name)
    }

    @Test
    fun `icici statement ignores October 2023 prose and isolates two ICICI card variants`() {
        val coralPdf = """
            ICICI Bank Credit Card Statement
            Coral RuPay Credit Card No. 4375 82XX XXXX 9001
            Statement Date: November 18, 2026
            Payment Due Date: December 06, 2026
            Statement Period: October 19, 2026 to November 18, 2026
            Total Amount Due ₹4,250.00
            Minimum Amount Due ₹220.00
            Credit Limit ₹1,50,000.00
            Available Credit ₹1,45,750.00
            Date SerNo. Transaction Details Reward Points Intl.# Amount (in `)
            25/10/2026 1122334455 SWIGGY INSTAMART BENGALURU IN 120 4,250.00
            Page 2 of 3
            Important Information & Illustrative Example
            Sample Statement Date October 2023 RBI guidelines sample calculation.
            If Statement Date is October 20, 2023 and Due Date is November 07, 2023...
        """.trimIndent()

        val amazonPayPdf = """
            Amazon Pay ICICI Bank Credit Card Statement
            Card No: 4375XXXXXXXX5678
            Statement Date 12 Nov 2026
            Payment Due Date 30 Nov 2026
            Total Amount Due ₹1,500.00
            Credit Limit ₹2,00,000.00
            10/11/2026 9988776655 AMAZON PAY INDIA 1,500.00
        """.trimIndent()

        assertEquals(listOf("9001"), Text.allLast4s(coralPdf))
        assertEquals(listOf("5678"), Text.allLast4s(amazonPayPdf))

        val coralParsed = IciciParser().parse(coralPdf)
        assertEquals(LocalDate.of(2026, 11, 18), coralParsed.statementDate)
        assertEquals(LocalDate.of(2026, 12, 6), coralParsed.dueDate)
        assertEquals(425_000L, coralParsed.totalDuePaise)
        assertEquals(15_000_000L, coralParsed.creditLimitPaise)

        val amazonParsed = IciciParser().parse(amazonPayPdf)
        assertEquals(LocalDate.of(2026, 11, 12), amazonParsed.statementDate)
        assertEquals(LocalDate.of(2026, 11, 30), amazonParsed.dueDate)
        assertEquals(150_000L, amazonParsed.totalDuePaise)
        assertEquals(20_000_000L, amazonParsed.creditLimitPaise)
    }

    @Test
    fun `available limit and used balance equal pending plus net spends after statement`() {
        val stmtDate = LocalDate.of(2026, 11, 18)
        val dueDate = LocalDate.of(2026, 12, 6)
        val card = Card(
            id = 7L,
            nickname = "ICICI Coral",
            issuer = "ICICI Bank",
            network = "Visa",
            last4 = "9001",
            limitPaise = 1_500_000L, // ₹15,000
            outstandingPaise = 400_000L, // ₹4,000 pending from bill
            statementDay = 18,
            dueOffsetDays = 18,
            color = 0xFF1F2937L,
            billingSet = true,
        )
        val stmt = Statement(
            id = 99L,
            cardId = 7L,
            statementEpochDay = stmtDate.toEpochDay(),
            periodStartEpochDay = stmtDate.minusMonths(1).toEpochDay(),
            dueEpochDay = dueDate.toEpochDay(),
            totalDuePaise = 500_000L,
            minDuePaise = 25_000L,
            openingPaise = 0L,
            debitsPaise = 500_000L,
            creditsPaise = 0L,
            status = "ok",
            sourceMsgId = "upload:1",
            fileHash = "hash-1",
            parser = "icici",
            rawText = "",
            creditLimitPaise = 1_500_000L,
        )
        val txns = listOf(
            // Pre-statement spend: ignored
            Txn(cardId = 7L, statementId = null, epochDay = stmtDate.toEpochDay(), merchant = "OLD", amountPaise = 100_000L, isCredit = false, categoryId = null, dedupeKey = "k1", kind = TxnKind.SPEND),
            // Post-statement spend: +₹2,500
            Txn(cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(1).toEpochDay(), merchant = "ZEPTO", amountPaise = 250_000L, isCredit = false, categoryId = null, dedupeKey = "k2", kind = TxnKind.SPEND),
            // Post-statement refund: -₹500
            Txn(cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(2).toEpochDay(), merchant = "ZEPTO REFUND", amountPaise = 50_000L, isCredit = true, categoryId = null, dedupeKey = "k3", kind = TxnKind.REFUND),
            // Post-statement bill payment: does NOT reduce new spends
            Txn(cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(3).toEpochDay(), merchant = "BILL PAY", amountPaise = 100_000L, isCredit = true, categoryId = null, dedupeKey = "k4", kind = TxnKind.PAYMENT),
        )
        val due = Billing.dueInfo(card, emptyList(), today = stmtDate.plusDays(4), statements = listOf(stmt))
        val postSpend = Repo.computePostSpendForCard(
            card = card,
            statements = listOf(stmt),
            txns = txns,
            savedPref = 0L,
            livePermitted = true,
        )
        assertEquals(200_000L, postSpend)
        val cw = CardWithDue(card = card, due = due, postSpendPaise = postSpend)
        assertEquals(600_000L, cw.totalUsedPaise)
        assertEquals(900_000L, cw.availableLimitPaise)
        assertEquals(0.4f, cw.utilization, 0.0001f)
    }

    @Test
    fun `statement remaining to pay updates even when statement needs review or has post-statement spends`() {
        val stmtDate = LocalDate.of(2026, 11, 18)
        val dueDate = LocalDate.of(2026, 12, 6)
        val reviewStmt = Statement(
            id = 55L,
            cardId = 7L,
            statementEpochDay = stmtDate.toEpochDay(),
            periodStartEpochDay = stmtDate.minusMonths(1).toEpochDay(),
            dueEpochDay = dueDate.toEpochDay(),
            totalDuePaise = 1_000_000L, // ₹10,000 due
            minDuePaise = 50_000L,
            openingPaise = null,
            debitsPaise = 950_000L,
            creditsPaise = 0L,
            status = com.devanandsb.ccmanager.data.Ledger.STATUS_REVIEW,
            note = "Differs",
            sourceMsgId = "upload:2",
            fileHash = "hash-review",
            parser = "icici",
            rawText = "",
        )
        val smsPay = Txn(
            id = 301L, cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(2).toEpochDay(),
            merchant = "Payment received - Thank you", amountPaise = 400_000L, isCredit = true,
            categoryId = null, dedupeKey = "sp_pay", kind = TxnKind.PAYMENT,
        )
        val smsSpend = Txn(
            id = 302L, cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(3).toEpochDay(),
            merchant = "SWIGGY", amountPaise = 150_000L, isCredit = false,
            categoryId = null, dedupeKey = "sp_spend", kind = TxnKind.SPEND,
        )
        val breakdown = com.devanandsb.ccmanager.ui.computeStatementBreakdown(
            st = reviewStmt,
            lines = emptyList(),
            cardPayments = emptyList(),
            cardLiveTxns = listOf(smsPay, smsSpend),
            isLatestForCard = true,
            outstandingPaise = 300_000L, // manually lowered even further from 6,000 to 3,000 despite post-spend!
        )
        assertEquals(700_000L, breakdown.paidAfterBillPaise)
        assertEquals(150_000L, breakdown.postStatementSpendPaise)
    }

    @Test
    fun `partial payment never marks due cycle settled or jumps to next month`() {
        val today = LocalDate.of(2026, 11, 25)
        val stmtDate = LocalDate.of(2026, 11, 18)
        val dueDate = LocalDate.of(2026, 12, 6)

        val stmt = Statement(
            id = 61L,
            cardId = 11L,
            statementEpochDay = stmtDate.toEpochDay(),
            periodStartEpochDay = stmtDate.minusMonths(1).toEpochDay(),
            dueEpochDay = dueDate.toEpochDay(),
            totalDuePaise = 1_500_000L,
            minDuePaise = 75_000L,
            openingPaise = null,
            debitsPaise = 1_500_000L,
            creditsPaise = 0L,
            status = "ok",
            note = "",
            sourceMsgId = "m61",
            fileHash = "h61",
            parser = "icici",
            rawText = "",
        )
        val partialPay = Payment(
            id = 1L,
            cardId = 11L,
            dueEpochDay = dueDate.toEpochDay(),
            paidOnEpochDay = today.toEpochDay(),
            amountPaise = 150_000L, // ₹1,500 partial out of ₹15,000
        )

        val partiallyPaidCard = Card(
            id = 11L,
            nickname = "ICICI Sapphiro",
            issuer = "ICICI",
            network = "Visa",
            last4 = "1234",
            limitPaise = 2_000_000L,
            outstandingPaise = 1_350_000L, // ₹13,500 still remaining!
            statementDay = 18,
            dueOffsetDays = 18,
            color = 0L,
            billingSet = true,
            createdAt = stmtDate.minusDays(10).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        val partialDue = Billing.dueInfo(partiallyPaidCard, listOf(partialPay), today = today, statements = listOf(stmt))
        assertEquals(dueDate, partialDue.dueDate)
        assertFalse("Partial payment must NOT mark cycle paid", partialDue.lastCyclePaid)

        // Manual card without statement after partial payment must also keep dueDate & lastCyclePaid = false
        val manualPartialDue = Billing.dueInfo(partiallyPaidCard, listOf(partialPay), today = today, statements = emptyList())
        assertEquals(dueDate, manualPartialDue.dueDate)
        assertFalse("Manual partial payment must NOT mark cycle paid", manualPartialDue.lastCyclePaid)

        // Once outstanding hits 0, cycle settles cleanly!
        val settledCard = partiallyPaidCard.copy(outstandingPaise = 0L)
        val settledDue = Billing.dueInfo(settledCard, listOf(partialPay), today = today, statements = listOf(stmt))
        assertTrue(settledDue.lastCyclePaid)
        assertTrue(settledDue.dueDate.isAfter(dueDate))
    }

    @Test
    fun `post statement refund surplus lowers total used and frees available credit`() {
        val stmtDate = LocalDate.of(2026, 11, 18)
        val dueDate = LocalDate.of(2026, 12, 6)
        val card = Card(
            id = 7L,
            nickname = "HDFC Millennia",
            issuer = "HDFC Bank",
            network = "Visa",
            last4 = "4321",
            limitPaise = 1_500_000L,
            outstandingPaise = 400_000L, // ₹4,000 pending due
            statementDay = 18,
            dueOffsetDays = 18,
            color = 0L,
            billingSet = true,
        )
        val stmt = Statement(
            id = 8L,
            cardId = 7L,
            statementEpochDay = stmtDate.toEpochDay(),
            periodStartEpochDay = stmtDate.minusMonths(1).toEpochDay(),
            dueEpochDay = dueDate.toEpochDay(),
            totalDuePaise = 400_000L,
            minDuePaise = 20_000L,
            openingPaise = null,
            debitsPaise = 400_000L,
            creditsPaise = 0L,
            status = "ok",
            note = "",
            sourceMsgId = "m8",
            fileHash = "h8",
            parser = "hdfc",
            rawText = "",
        )
        val txns = listOf(
            Txn(cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(1).toEpochDay(), merchant = "AMAZON", amountPaise = 50_000L, isCredit = false, categoryId = null, dedupeKey = "s1", kind = TxnKind.SPEND),
            Txn(cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(2).toEpochDay(), merchant = "AMAZON REFUND", amountPaise = 250_000L, isCredit = true, categoryId = null, dedupeKey = "r1", kind = TxnKind.REFUND),
        )
        val due = Billing.dueInfo(card, emptyList(), today = stmtDate.plusDays(3), statements = listOf(stmt))
        val pb = Repo.computePostBalanceForCard(
            card = card,
            statements = listOf(stmt),
            txns = txns,
            savedPref = 0L,
            livePermitted = true,
        )
        assertEquals(0L, pb.postSpendPaise)
        assertEquals(200_000L, pb.postRefundSurplusPaise)
        val cw = CardWithDue(card = card, due = due, postSpendPaise = pb.postSpendPaise, postRefundSurplusPaise = pb.postRefundSurplusPaise)
        assertEquals(200_000L, cw.totalUsedPaise)
        assertEquals(1_300_000L, cw.availableLimitPaise)
    }

    @Test
    fun `same day post cutoff sms payment counts via stmtLines even when prior credits exceed it`() {
        val stmtDate = LocalDate.of(2026, 11, 18)
        val dueDate = LocalDate.of(2026, 12, 6)
        val stmt = Statement(
            id = 91L,
            cardId = 7L,
            statementEpochDay = stmtDate.toEpochDay(),
            periodStartEpochDay = stmtDate.minusMonths(1).toEpochDay(),
            dueEpochDay = dueDate.toEpochDay(),
            totalDuePaise = 1_000_000L,
            minDuePaise = 50_000L,
            openingPaise = 500_000L,
            debitsPaise = 1_000_000L,
            creditsPaise = 500_000L, // previous month bill payment inside statement
            status = "ok",
            note = "",
            sourceMsgId = "m91",
            fileHash = "h91",
            parser = "hdfc",
            rawText = "",
        )
        val priorStmtLine = Txn(
            id = 1L, cardId = 7L, statementId = 91L, epochDay = stmtDate.minusDays(20).toEpochDay(),
            merchant = "Payment received", amountPaise = 500_000L, isCredit = true, categoryId = null, dedupeKey = "st1", kind = TxnKind.PAYMENT,
        )
        val manualPay = Payment(
            id = 2L, cardId = 7L, dueEpochDay = dueDate.toEpochDay(),
            paidOnEpochDay = stmtDate.plusDays(1).toEpochDay(), amountPaise = 300_000L,
        )
        // Matching SMS off by ₹0.50 rounding MUST dedupe with manual payment; additional Nov 18 payment MUST count!
        val roundingSmsPay = Txn(
            id = 10L, cardId = 7L, statementId = null, epochDay = stmtDate.plusDays(1).toEpochDay(),
            merchant = "Payment received", amountPaise = 300_050L, isCredit = true, categoryId = null, dedupeKey = "sm1", kind = TxnKind.PAYMENT,
        )
        val sameDayEveningSmsPay = Txn(
            id = 11L, cardId = 7L, statementId = null, epochDay = stmtDate.toEpochDay(),
            merchant = "Payment received", amountPaise = 200_000L, isCredit = true, categoryId = null, dedupeKey = "sm2", kind = TxnKind.PAYMENT,
        )
        val bd = Repo.computePostStatementBreakdown(
            latest = stmt,
            manualPayments = listOf(manualPay),
            liveTxns = listOf(roundingSmsPay, sameDayEveningSmsPay),
            stmtLines = listOf(priorStmtLine),
        )
        assertEquals(500_000L, bd.paidPaise) // ₹3,000 manual (deduped ₹3,000.50 SMS) + ₹2,000 same-day SMS
    }

    @Test
    fun `profile crypt protection tags and password candidate generation`() {
        val sample = PdfProfile(
            name = "ALEX KUMAR",
            dob = LocalDate.of(1990, 1, 15),
            pan = "ABCDE1234F",
            mobile = "9876543210",
            templates = listOf("{NAME4}{DDMM}", "secret123"),
        )
        val candidates = Passwords.candidates(sample, listOf("4321"))
        assertTrue("ALEX1501" in candidates)
        assertTrue("ABCDE1234F" in candidates)
        assertTrue("150119904321" in candidates)
        assertTrue("secret123" in candidates)

        val authTagged = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(), 1, 2, 3)
        val openTagged = byteArrayOf('P'.code.toByte(), 'F'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte(), 1, 2, 3)
        val legacyBlob = byteArrayOf(12, 34, 56, 78, 90)
        assertTrue(com.devanandsb.ccmanager.data.ProfileCrypt.isAuthProtected(authTagged))
        assertFalse(com.devanandsb.ccmanager.data.ProfileCrypt.isLegacy(authTagged))
        assertFalse(com.devanandsb.ccmanager.data.ProfileCrypt.isAuthProtected(openTagged))
        assertFalse(com.devanandsb.ccmanager.data.ProfileCrypt.isLegacy(openTagged))
        assertTrue(com.devanandsb.ccmanager.data.ProfileCrypt.isLegacy(legacyBlob))
    }
}
