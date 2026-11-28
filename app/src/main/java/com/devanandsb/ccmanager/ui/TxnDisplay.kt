package com.devanandsb.ccmanager.ui

import com.devanandsb.ccmanager.data.Txn
import com.devanandsb.ccmanager.ingest.Text
import com.devanandsb.ccmanager.ingest.TxnKind

/** Plain-English display names and tags for bank statement transaction strings. */
data class FriendlyTxn(
    val title: String,
    val typeLabel: String,
    val channel: String? = null,
)

object TxnDisplay {
    private val refTail = Regex("\\(\\s*Ref#?[^)]*\\)|\\bRef(?:erence)?[:#\\s]*[A-Z0-9-]+", RegexOption.IGNORE_CASE)
    private val mandatePrefix = Regex("^(?:AUTO[\\s-]?PAY|AUTO[\\s-]?DEBIT|E[\\s-]?MANDATE|EMANDATE|MANDATE|STANDING[\\s-]?INSTRUCTION|SI|RECURRING|NACH|ACH)\\s*[*/:\\-]\\s*", RegexOption.IGNORE_CASE)
    private val gatewayPrefix = Regex("^(?:PYU|PAYU|RAZORPAY|RZP|PAYTM|CASHFREE|BILLDESK|CCA|CCAVENUE|INSTAMOJO|BPPY|BBPS|PHONEPE|GPAY|IND|FKRT)\\s*[*\\-]\\s*", RegexOption.IGNORE_CASE)
    private val posPrefix = Regex("^(?:POS|ECOM|INTL|MOTO|PUR|EMI)\\s+(?:\\d{4}[Xx*]{2,8}\\d{4}\\s+)?", RegexOption.IGNORE_CASE)
    private val cardMask = Regex("\\b\\d{4,6}[Xx*•]{4,8}\\d{4}\\b")
    private val domainTld = Regex("\\.(?:com|co\\.in|in|org|net|io)\\b", RegexOption.IGNORE_CASE)
    private val mandateNoise = Regex("\\b(?:auto[\\s-]?pay|auto[\\s-]?debit|e[\\s-]?mandate|emandate|mandate|standing[\\s-]?instruction|recurring)\\b", RegexOption.IGNORE_CASE)
    private val noiseTail = Regex("\\b(?:PVT\\.?\\s*LTD\\.?|PRIVATE\\s+LIMITED|LIMITED|LTD\\.?|INDIA\\s+PVT|INDIA|IN|COM|WWW|BANGALORE|BENGALURU|BANGLORE|MUMBAI|DELHI|NEW\\s+DELHI|GURGAON|GURUGRAM|NOIDA|HYDERABAD|CHENNAI|PUNE|KOLKATA|AHMEDABAD|JAIPUR|KOCHI)\\b", RegexOption.IGNORE_CASE)

    private val autoPayRawOrNote = Regex(
        "^(?:si|e[\\s-]?mandate|emandate|autopay|auto[\\s-]?pay|nach|ach)[*/:\\-\\s]|\\b(?:auto[\\s-]?pay|auto[\\s-]?debit|e[\\s-]?mandate|emandate|standing[\\s-]?instruction|nach|ach|si\\s*[*/\\-])\\b",
        RegexOption.IGNORE_CASE,
    )
    private val autoPaySource = Regex(
        "\\b(?:auto[\\s-]?pay|auto[\\s-]?debit|e[\\s-]?mandate|emandate|standing[\\s-]?instruction|recurring\\s+(?:payment|debit|transaction|mandate))\\b",
        RegexOption.IGNORE_CASE,
    )

    private val brandAliases: List<Pair<Regex, String>> = listOf(
        Regex("\\bamazon\\s*pay\\b", RegexOption.IGNORE_CASE) to "Amazon Pay",
        Regex("\\bprime\\s*video\\b|\\bamazon\\s*prime\\b", RegexOption.IGNORE_CASE) to "Amazon Prime",
        Regex("\\b(amazon|amzn)\\b", RegexOption.IGNORE_CASE) to "Amazon",
        Regex("\\b(flipkart|fkrt|ekart)\\b", RegexOption.IGNORE_CASE) to "Flipkart",
        Regex("\\bswiggy\\s*instamart\\b|\\binstamart\\b", RegexOption.IGNORE_CASE) to "Swiggy Instamart",
        Regex("\\bswiggy\\b", RegexOption.IGNORE_CASE) to "Swiggy",
        Regex("\\bzomato\\b", RegexOption.IGNORE_CASE) to "Zomato",
        Regex("\\bmyntra\\b", RegexOption.IGNORE_CASE) to "Myntra",
        Regex("\\bbigbasket\\b|\\bbig\\s*basket\\b", RegexOption.IGNORE_CASE) to "BigBasket",
        Regex("\\bblinkit\\b|\\bgrofers\\b", RegexOption.IGNORE_CASE) to "Blinkit",
        Regex("\\bzepto\\b", RegexOption.IGNORE_CASE) to "Zepto",
        Regex("\\bdmart\\b|\\bavenue\\s*supermart", RegexOption.IGNORE_CASE) to "DMart",
        Regex("\\bjiomart\\b", RegexOption.IGNORE_CASE) to "JioMart",
        Regex("\\bajio\\b", RegexOption.IGNORE_CASE) to "AJIO",
        Regex("\\bnykaa\\b", RegexOption.IGNORE_CASE) to "Nykaa",
        Regex("\\bmeesho\\b", RegexOption.IGNORE_CASE) to "Meesho",
        Regex("\\btata\\s*cliq\\b|\\btatacliq\\b", RegexOption.IGNORE_CASE) to "Tata CLiQ",
        Regex("\\bcroma\\b", RegexOption.IGNORE_CASE) to "Croma",
        Regex("\\breliance\\s*digital\\b", RegexOption.IGNORE_CASE) to "Reliance Digital",
        Regex("\\bdecathlon\\b", RegexOption.IGNORE_CASE) to "Decathlon",
        Regex("\\bmakemytrip\\b", RegexOption.IGNORE_CASE) to "MakeMyTrip",
        Regex("\\bgoibibo\\b", RegexOption.IGNORE_CASE) to "Goibibo",
        Regex("\\bcleartrip\\b", RegexOption.IGNORE_CASE) to "Cleartrip",
        Regex("\\birctc\\b", RegexOption.IGNORE_CASE) to "IRCTC",
        Regex("\\bindigo\\b|\\binterglobe\\b", RegexOption.IGNORE_CASE) to "IndiGo",
        Regex("\\bair\\s*india\\b", RegexOption.IGNORE_CASE) to "Air India",
        Regex("\\buber\\b", RegexOption.IGNORE_CASE) to "Uber",
        Regex("\\bolacabs\\b|\\bola\\s*cabs\\b", RegexOption.IGNORE_CASE) to "Ola",
        Regex("\\brapido\\b", RegexOption.IGNORE_CASE) to "Rapido",
        Regex("\\bbookmyshow\\b", RegexOption.IGNORE_CASE) to "BookMyShow",
        Regex("\\bnetflix\\b", RegexOption.IGNORE_CASE) to "Netflix",
        Regex("\\bspotify\\b", RegexOption.IGNORE_CASE) to "Spotify",
        Regex("\\bhotstar\\b|\\bjiohotstar\\b", RegexOption.IGNORE_CASE) to "Hotstar",
        Regex("\\byoutube\\b", RegexOption.IGNORE_CASE) to "YouTube",
        Regex("\\bicloud\\b", RegexOption.IGNORE_CASE) to "iCloud",
        Regex("\\bapple\\b", RegexOption.IGNORE_CASE) to "Apple",
        Regex("\\bgoogle\\s*play\\b", RegexOption.IGNORE_CASE) to "Google Play",
        Regex("\\bgoogle\\s*one\\b", RegexOption.IGNORE_CASE) to "Google One",
        Regex("\\bopenai\\b|\\bchatgpt\\b", RegexOption.IGNORE_CASE) to "ChatGPT",
        Regex("\\bclaude\\b|\\banthropic\\b", RegexOption.IGNORE_CASE) to "Claude",
        Regex("\\badobe\\b", RegexOption.IGNORE_CASE) to "Adobe",
        Regex("\\bairtel\\b", RegexOption.IGNORE_CASE) to "Airtel",
        Regex("\\bjio\\b", RegexOption.IGNORE_CASE) to "Jio",
        Regex("\\bapollo\\s*pharm", RegexOption.IGNORE_CASE) to "Apollo Pharmacy",
        Regex("\\b1mg\\b", RegexOption.IGNORE_CASE) to "Tata 1mg",
        Regex("\\bpharmeasy\\b", RegexOption.IGNORE_CASE) to "PharmEasy",
        Regex("\\bstarbucks\\b", RegexOption.IGNORE_CASE) to "Starbucks",
        Regex("\\bmcdonald\\b", RegexOption.IGNORE_CASE) to "McDonald's",
        Regex("\\bdomino", RegexOption.IGNORE_CASE) to "Domino's",
        Regex("\\bkfc\\b", RegexOption.IGNORE_CASE) to "KFC",
        Regex("\\burban\\s*company\\b", RegexOption.IGNORE_CASE) to "Urban Company",
        Regex("\\blenskart\\b", RegexOption.IGNORE_CASE) to "Lenskart",
        Regex("\\bhpcl\\b|\\bhp\\s*pay\\b", RegexOption.IGNORE_CASE) to "HPCL Fuel",
        Regex("\\bbpcl\\b", RegexOption.IGNORE_CASE) to "BPCL Fuel",
        Regex("\\biocl\\b|\\bindian\\s*oil\\b", RegexOption.IGNORE_CASE) to "IndianOil Fuel",
        Regex("\\bshell\\b", RegexOption.IGNORE_CASE) to "Shell Fuel",
        Regex("\\bcred\\b", RegexOption.IGNORE_CASE) to "CRED",
    )

    fun format(t: Txn): FriendlyTxn = format(t.merchant, t.kind, t.isCredit, t.note, t.sourceText)

    fun format(rawMerchant: String, kind: String, isCredit: Boolean): FriendlyTxn =
        format(rawMerchant, kind, isCredit, "", "")

    fun format(
        rawMerchant: String,
        kind: String,
        isCredit: Boolean,
        note: String,
        sourceText: String = "",
    ): FriendlyTxn {
        val raw = Text.stripEmiLink(rawMerchant).trim()
        val low = raw.lowercase()
        val isAutoPay = autoPayRawOrNote.containsMatchIn(raw) ||
            (note.isNotBlank() && autoPayRawOrNote.containsMatchIn(note)) ||
            (sourceText.isNotBlank() && autoPaySource.containsMatchIn(sourceText))
        val typeLabel = when (kind) {
            TxnKind.PAYMENT -> "Payment received"
            TxnKind.REFUND -> "Refund"
            TxnKind.CASHBACK -> if (isCredit) "Cashback" else "Cashback reversed"
            TxnKind.FEE -> "Bank charge"
            else -> if (isCredit) "Credit" else "Purchase"
        }

        if (kind == TxnKind.PAYMENT) {
            val combined = "$raw $note".lowercase()
            val method = when {
                combined.contains("payzapp") -> "PayZapp"
                isAutoPay -> "AutoPay"
                combined.contains("bbps") || combined.contains("bppy") || combined.contains("bharat bill") -> "BBPS"
                combined.contains("billdesk") || combined.contains("bill desk") -> "BillDesk"
                combined.contains("neft") -> "NEFT"
                combined.contains("imps") -> "IMPS"
                combined.contains("rtgs") -> "RTGS"
                combined.contains("upi") -> "UPI"
                Regex("\\bcred\\b").containsMatchIn(combined) || sourceText.startsWith("App: CRED", ignoreCase = true) -> "CRED"
                combined.contains("cheq") -> "CheQ"
                else -> null
            }
            return FriendlyTxn(title = "Payment received", typeLabel = "Bill payment", channel = method)
        }

        if (kind == TxnKind.CASHBACK) {
            return FriendlyTxn(
                title = if (isCredit) "Cashback received" else "Cashback reversed",
                typeLabel = if (isCredit) "Reward" else "Adjustment",
            )
        }

        if (kind == TxnKind.FEE) {
            val cleanFee = when {
                low.contains("igst") || low.contains("cgst") || low.contains("sgst") || low.contains("gst") -> "GST / Tax"
                low.contains("late") -> "Late payment fee"
                low.contains("finance charge") || low.contains("interest") -> "Interest charge"
                low.contains("annual") || low.contains("membership") -> "Annual fee"
                low.contains("joining") -> "Joining fee"
                low.contains("surcharge") -> "Surcharge"
                low.contains("overlimit") -> "Over-limit fee"
                low.contains("processing") -> "Processing fee"
                else -> cleanMerchantName(raw).ifBlank { "Bank charge" }
            }
            return FriendlyTxn(title = cleanFee, typeLabel = "Bank charge")
        }

        // Check UPI patterns: "UPI/...", "UPI-...", "someone@okaxis"
        val isUpi = low.startsWith("upi/") || low.startsWith("upi-") || low.startsWith("upi ") ||
            Regex("[a-z0-9._-]+@[a-z]+", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val cleaned = if (isUpi) cleanUpiName(raw) else cleanMerchantName(raw)
        val baseTitle = cleaned.ifBlank { raw }
        val channel = when {
            isAutoPay -> "AutoPay"
            low.startsWith("bbps") || low.startsWith("bppy") -> "BBPS"
            isUpi -> "UPI"
            Regex("^emi\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "EMI"
            Regex("\\b(?:tap\\s*(?:&|and)\\s*pay|contactless|nfc)\\b", RegexOption.IGNORE_CASE).containsMatchIn("$raw $note") -> "Tap & Pay"
            else -> null
        }

        if (kind == TxnKind.REFUND) {
            val strippedRefund = baseTitle
                .replace(Regex("^(?:refund|reversal|reversed|cr)\\b[\\s\\-:·]*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(?:refund|reversal)\\b", RegexOption.IGNORE_CASE), "")
                .trim()
            val title = if (strippedRefund.isNotBlank()) "Refund · $strippedRefund" else "Refund received"
            return FriendlyTxn(title = title, typeLabel = "Refund received", channel = channel)
        }

        return FriendlyTxn(
            title = baseTitle,
            typeLabel = typeLabel,
            channel = channel,
        )
    }

    private fun cleanUpiName(raw: String): String {
        val parts = raw.split('/', '-', '*')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val skip = setOf(
            "upi", "upiar", "upiab", "p2p", "p2m", "p2a", "vpa", "pay", "payment", "dr", "cr", "in", "tr", "na", "ok",
            "autopay", "autodebit", "auto debit", "mandate", "emandate", "si", "nach", "ach", "recurring",
            "imps", "neft", "rtgs", "txn", "ref", "others", "merchant", "pos", "ecom",
            "utib", "hdfc", "icic", "sbin", "kkbk", "yesb", "aubl", "csbk", "cnrb", "barb", "pytm",
            "okicici", "okhdfcbank", "okaxis", "oksbi", "ybl", "ibl", "axl", "mbk",
        )
        val noVpa = raw.replace(Regex("^VPA\\s+", RegexOption.IGNORE_CASE), "")
        val partsClean = noVpa.split('/', '-', '*').map { it.trim() }.filter { it.isNotEmpty() }
        val named = partsClean.firstOrNull { seg ->
            val sl = seg.lowercase()
            sl !in skip &&
                !seg.contains('@') &&
                seg.any(Char::isLetter) &&
                !Regex("^\\d+$").matches(seg) &&
                !Regex("^[A-Z]{2,4}\\d{4,}$", RegexOption.IGNORE_CASE).matches(seg) &&
                seg.count(Char::isDigit) <= 2
        }
        if (named != null) return normalizeKnownOrTitle(named)
        // Fallback: "@upi" handle -> readable first part ("rahul.sharma@okaxis" -> "Rahul Sharma")
        val handle = parts.firstOrNull { it.contains('@') } ?: raw
        val userPart = handle.substringBefore('@').replace(Regex("[._-]+"), " ").replace(Regex("\\d+$"), "").trim()
        return if (userPart.any(Char::isLetter)) normalizeKnownOrTitle(userPart) else cleanMerchantName(raw)
    }

    private fun cleanMerchantName(raw: String): String {
        var s = raw
            .replace(refTail, " ")
            .replace(cardMask, " ")
            .replace(mandatePrefix, "")
            .replace(gatewayPrefix, "")
            .replace(mandatePrefix, "")
            .replace(posPrefix, "")
            .replace(domainTld, " ")
            .trim()
        brandAliases.firstOrNull { (rx, _) -> rx.containsMatchIn(s) }?.let { return it.second }
        s = s
            .replace(Regex("[*#_/]+"), " ")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(mandateNoise, " ")
            .replace(noiseTail, " ")
            .replace(Regex("\\b\\d{5,}\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '.', ',', ':')
        return normalizeKnownOrTitle(s).ifBlank { raw }
    }

    private fun normalizeKnownOrTitle(s: String): String {
        brandAliases.firstOrNull { (rx, _) -> rx.containsMatchIn(s) }?.let { return it.second }
        return s.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { w ->
                when {
                    w.length <= 3 && w.all(Char::isUpperCase) && w.lowercase() !in setOf("the", "and", "for", "new") -> w
                    else -> w.lowercase().replaceFirstChar { it.titlecase() }
                }
            }
    }
}
