package com.devanandsb.ccmanager.domain

/**
 * Issuer presets: card colour and a typical statement-to-due gap. Users can override both.
 *
 * [statementFrom]: exact addresses statements are commonly sent from. Searched first (fast and precise).
 * They're best-known values, not confirmed by the banks; real senders are also learned from imports.
 * [senders]: domain words/substrings of the bank's mail domains, including the RBI-mandated ".bank.in"
 * domains (2025). Used as the second-level search and to tell which bank an email is from.
 */
data class Issuer(
    val name: String,
    val color: Long,
    val dueOffset: Int,
    val senders: List<String> = emptyList(),
    val statementFrom: List<String> = emptyList(),
)

object Issuers {
    val all = listOf(
        Issuer(
            "HDFC Bank", 0xFF004C8F, 20, listOf("hdfcbank", "hdfc.bank.in"),
            listOf("emailstatements.cards@hdfcbank.net", "emailstatements.cards@hdfcbank.bank.in", "emailstatements.cards@hdfc.bank.in"),
        ),
        Issuer("SBI Card", 0xFF27408B, 20, listOf("sbicard"), listOf("statements@sbicard.com", "estatements@sbicard.com")),
        Issuer("ICICI Bank", 0xFFAE282E, 18, listOf("icicibank", "icici.bank.in"), listOf("credit_cards@icicibank.com", "credit_cards@icici.bank.in", "estatement@icicibank.com")),
        Issuer("Axis Bank", 0xFF97144D, 18, listOf("axisbank", "axis.bank.in"), listOf("cc.statements@axisbank.com", "cc.statements@axis.bank.in", "statements@axisbank.com")),
        Issuer("Kotak", 0xFFD9232D, 18, listOf("kotak"), listOf("cardstatement@kotak.com", "cardstatement@kotak.bank.in")),
        Issuer("Yes Bank", 0xFF00518F, 18, listOf("yesbank", "yes.bank.in")),
        Issuer("IDFC FIRST", 0xFF9C1D26, 20, listOf("idfcfirstbank", "idfcbank", "idfcfirst.bank.in"), listOf("statements@idfcfirstbank.com", "statement@idfcfirstbank.com")),
        Issuer("IndusInd", 0xFF7A1F2B, 20, listOf("indusind", "indusind.bank.in")),
        Issuer(
            "AU Bank", 0xFF6A2C91, 15, listOf("aubank", "au.bank.in"),
            listOf("statements@aubank.in", "creditcard@aubank.in", "estatements@aubank.in", "statements@au.bank.in", "creditcard@au.bank.in"),
        ),
        Issuer("RBL Bank", 0xFF1F3A93, 15, listOf("rblbank", "rbl.bank.in")),
        Issuer("Amex", 0xFF2E77BB, 20, listOf("americanexpress", "aexp")),
        Issuer("HSBC", 0xFFC8102E, 18, listOf("hsbc", "hsbc.co.in", "hsbc.bank.in")),
        Issuer("Standard Chartered", 0xFF0F7D5A, 20, listOf("sc.com", "standardchartered", "sc.bank.in")),
        Issuer("Federal Bank", 0xFF0E4D92, 20, listOf("federalbank", "federal.bank.in")),
        Issuer("Paytm", 0xFF00B9F5, 20, listOf("paytm", "paytmbank"), listOf("customercare@paytm.com", "support@paytmbank.com")),
        Issuer("BOB Card", 0xFFE4572E, 20, listOf("bobcard", "bobfinancial", "bob.bank.in")),
        Issuer("OneCard", 0xFF2B2B2B, 18, listOf("getonecard", "onecard")),
        Issuer("Slice", 0xFF5B2BD8, 15, listOf("sliceit", "slicebank", "nesfb", "slice.bank.in")),
        Issuer("Unity Bank (Roar)", 0xFF13434F, 15, listOf("unitybank.co.in", "theunitybank", "unitybank", "roarbank", "unity.bank.in")),
        // More banks (cards are fully supported; statements from these aren't imported automatically yet).
        Issuer("State Bank of India", 0xFF22409A, 20),
        Issuer("Bank of Baroda", 0xFFF26522, 20),
        Issuer("Punjab National Bank", 0xFFA6192E, 20),
        Issuer("Canara Bank", 0xFF0072BC, 20),
        Issuer("Union Bank of India", 0xFFD71920, 20),
        Issuer("Bank of India", 0xFFF37021, 20),
        Issuer("Indian Bank", 0xFF1D4E89, 20),
        Issuer("Central Bank of India", 0xFFC8102E, 20),
        Issuer("Indian Overseas Bank", 0xFF0057A8, 20),
        Issuer("Bank of Maharashtra", 0xFF1B3F8B, 20),
        Issuer("UCO Bank", 0xFF0A5BA7, 20),
        Issuer("IDBI Bank", 0xFF00836C, 20),
        Issuer("Citi", 0xFF056DAE, 20),
        Issuer("DBS Bank", 0xFFE21D38, 20),
        Issuer("DCB Bank", 0xFF2D3E8C, 20),
        Issuer("Barclays", 0xFF00AEEF, 20),
        Issuer("Deutsche Bank", 0xFF0018A8, 20),
        Issuer("CSB Bank", 0xFF004B87, 20, listOf("csb.co.in", "csb.bank.in", "jupiter.money")),
        Issuer("City Union Bank", 0xFF7B1F3A, 20),
        Issuer("Dhanlaxmi Bank", 0xFF8B1E3F, 20),
        Issuer("J&K Bank", 0xFF006A4E, 20),
        Issuer("Karnataka Bank", 0xFFB5121B, 20),
        Issuer("Karur Vysya Bank", 0xFF1F5130, 20),
        Issuer("South Indian Bank", 0xFF9E1B32, 20),
        Issuer("Saraswat Bank", 0xFF7A2E8E, 20),
        Issuer("Abhyudaya Bank", 0xFF2E5E9E, 20),
        Issuer("Other", 0xFF455A64, 20),
    )

    private val LEGACY_NAMES: Map<String, String> = mapOf(
        "HDFC" to "HDFC Bank",
        "ICICI" to "ICICI Bank",
        "SBI" to "SBI Card",
        "AXIS" to "Axis Bank",
        "AU" to "AU Bank",
        "RBL" to "RBL Bank",
        "IDBI" to "IDBI Bank",
        "FEDERAL" to "Federal Bank",
        "YES" to "Yes Bank",
        "IDFC" to "IDFC FIRST",
        "INDUSIND" to "IndusInd",
        "KOTAK" to "Kotak",
        "AMERICAN EXPRESS" to "Amex",
        "AMEX" to "Amex",
        "PAYTM AU BANK" to "AU Bank",
        "CSB BANK (JUPITER EDGE+)" to "CSB Bank",
        "FEDERAL (SCAPIA)" to "Federal Bank",
        "ONECARD (FPL)" to "OneCard",
    )

    fun canonical(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        all.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.name }
        return LEGACY_NAMES[trimmed.uppercase()] ?: trimmed
    }

    fun sameIssuer(a: String?, b: String?): Boolean {
        val ca = canonical(a) ?: return false
        val cb = canonical(b) ?: return false
        return ca.equals(cb, ignoreCase = true)
    }

    fun byName(name: String): Issuer {
        val trimmed = name.trim()
        all.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
        LEGACY_NAMES[trimmed.uppercase()]?.let { canon ->
            all.firstOrNull { it.name == canon }?.let { return it }
        }
        return all.last()
    }

    private val TXN_START_RX = Regex(
        "^\\s*(?:\\d{1,3}\\s+)?(?:\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|\\d{1,2}\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val FOOTER_PROSE_CUES = listOf(
        "illustrative example", "illustration", "sample calculation", "key fact statement",
        "most important terms", "important information", "terms and conditions", "terms & conditions",
        "ways to pay", "payment options", "payment modes", "pay via", "other bank", "neft", "ifsc",
        "billdesk", "glossary",
    )
    private val HEADER_CUES = listOf(
        "statement", "e-statement", "credit card", "card number", "card no", "imobile",
        "amazon pay icici", "monthly statement", "billing period", "statement period",
    )

    /**
     * Which bank a piece of card text or PDF statement names.
     * For multi-line PDF statements, prioritizes header/statement-title lines and sender domains
     * while ignoring transaction merchant lines and footer payment-method/T&C tables so an ICICI
     * Amazon Pay statement mentioning "American Express" in payment options/T&C is never
     * misidentified as Amex. Within a line, longest non-overlapping alias wins so
     * "State Bank of India" beats "Bank of India" and "South Indian Bank" beats "Indian Bank".
     */
    fun detect(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
            .map { it.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val aliasPairs = ALIASES.flatMap { (bank, names) -> names.map { it to bank } }
            .sortedByDescending { it.first.length }
        val illustrationIdx = lines.indices.drop(8).firstOrNull { idx ->
            val low = lines[idx].lowercase()
            low.contains("illustrative example") || low.contains("terms and conditions") ||
                low.contains("terms & conditions") || low.contains("most important terms") ||
                low.contains("important information") || low.contains("key fact statement")
        } ?: lines.size

        data class MatchSpan(val start: Int, val end: Int, val alias: String, val bank: String)
        val bestLineScoreByBank = HashMap<String, Int>()
        val topHitsCountByBank = HashMap<String, Int>()

        fun scoreLines(includeTxnLines: Boolean) {
            for ((idx, rawLine) in lines.withIndex()) {
                if (!includeTxnLines && TXN_START_RX.containsMatchIn(rawLine)) continue
                val lowLine = rawLine.lowercase()
                val normLine = " " + rawLine.uppercase().replace(Regex("[^A-Z0-9&+]+"), " ") + " "

                val rawHits = mutableListOf<MatchSpan>()
                for ((alias, bank) in aliasPairs) {
                    val needle = " $alias "
                    var from = 0
                    while (true) {
                        val at = normLine.indexOf(needle, from)
                        if (at < 0) break
                        val end = at + needle.length
                        if (rawHits.none { it.start <= at && it.end >= end } &&
                            rawHits.none { at < it.end && end > it.start && it.alias.length >= alias.length }
                        ) {
                            rawHits += MatchSpan(at, end, alias, bank)
                        }
                        from = at + alias.length
                    }
                }

                // Also inspect bank email/web domains printed in statement headers/footers.
                val domainHits = all.filter { iss ->
                    iss.senders.any { d -> lowLine.contains(d) }
                }.map { it.name }

                val distinctBanksOnLine = (rawHits.map { it.bank } + domainHits).distinct()
                if (distinctBanksOnLine.isEmpty()) continue

                val inFooterOrProse = idx >= illustrationIdx || FOOTER_PROSE_CUES.any { lowLine.contains(it) }
                val hasHeaderCue = HEADER_CUES.any { lowLine.contains(it) }
                val multiBankPenalty = if (distinctBanksOnLine.size >= 3) 45 else 0
                val posBonus = when {
                    idx <= 4 -> 65
                    idx <= 14 -> 40
                    idx <= 30 -> 20
                    else -> 0
                }

                for (bank in distinctBanksOnLine) {
                    val maxAliasLen = rawHits.filter { it.bank == bank }.maxOfOrNull { it.alias.length } ?: 0
                    val domainBonus = if (bank in domainHits) 55 else 0
                    val cueBonus = if (hasHeaderCue && !inFooterOrProse) 65 else 0
                    val prosePenalty = if (inFooterOrProse) 55 else 0
                    val score = 20 + maxAliasLen.coerceAtMost(32) + posBonus + cueBonus + domainBonus - prosePenalty - multiBankPenalty
                    val prev = bestLineScoreByBank[bank]
                    if (prev == null || score > prev) bestLineScoreByBank[bank] = score
                    if (idx <= 30 && !inFooterOrProse) {
                        topHitsCountByBank[bank] = (topHitsCountByBank[bank] ?: 0) + 1
                    }
                }
            }
        }

        scoreLines(includeTxnLines = false)
        if (bestLineScoreByBank.isEmpty()) scoreLines(includeTxnLines = true)
        if (bestLineScoreByBank.isEmpty()) return null

        return bestLineScoreByBank.entries.maxByOrNull { (bank, bestScore) ->
            bestScore * 10 + (topHitsCountByBank[bank] ?: 0).coerceAtMost(6) * 12
        }?.key
    }

    private val ALIASES: Map<String, List<String>> = mapOf(
        "HDFC Bank" to listOf("HDFC BANK CREDIT CARD", "HDFC BANK", "HDFCBANK", "HDFC"),
        "SBI Card" to listOf("SBI CARD MONTHLY STATEMENT", "SBI CREDIT CARD", "SBI CARD", "SBICARD"),
        "State Bank of India" to listOf("STATE BANK OF INDIA", "STATE BANK", "SBI"),
        "ICICI Bank" to listOf(
            "AMAZON PAY ICICI BANK CREDIT CARD",
            "AMAZON PAY ICICI BANK",
            "AMAZON PAY ICICI",
            "ICICI BANK CREDIT CARD",
            "ICICI BANK",
            "ICICIBANK",
            "ICICI",
        ),
        "Axis Bank" to listOf("AXIS BANK CREDIT CARD", "AXIS BANK", "AXISBANK", "AXIS"),
        "Kotak" to listOf("KOTAK MAHINDRA BANK", "KOTAK MAHINDRA", "KOTAK BANK", "KOTAK"),
        "Yes Bank" to listOf("YES BANK CREDIT CARD", "YES BANK"),
        "IDFC FIRST" to listOf("IDFC FIRST BANK", "IDFC FIRST", "IDFC"),
        "IndusInd" to listOf("INDUSIND BANK", "INDUSIND"),
        "AU Bank" to listOf("AU SMALL FINANCE BANK", "AU SMALL FINANCE", "AU BANK", "PAYTM AU"),
        "RBL Bank" to listOf("RBL BANK", "RBL"),
        "Amex" to listOf("AMERICAN EXPRESS", "AMEX"),
        "HSBC" to listOf("HSBC"),
        "Standard Chartered" to listOf("STANDARD CHARTERED"),
        "Federal Bank" to listOf("FEDERAL BANK", "SCAPIA", "JUPITER", "FI MONEY", "FI FEDERAL", "EPIFI"),
        "Paytm" to listOf("PAYTM PAYMENTS BANK", "PAYTM FIRST", "PAYTM"),
        "BOB Card" to listOf("BOBCARD", "BOB CARD", "BOB FINANCIAL"),
        "Bank of Baroda" to listOf("BANK OF BARODA"),
        "OneCard" to listOf("ONECARD", "ONE CARD"),
        "Slice" to listOf("SLICE"),
        "Unity Bank (Roar)" to listOf("ROARBANK", "ROAR BANK", "UNITY BANK", "UNITY SMALL FINANCE"),
        "Punjab National Bank" to listOf("PUNJAB NATIONAL BANK", "PNB"),
        "Canara Bank" to listOf("CANARA BANK", "CANARA"),
        "Union Bank of India" to listOf("UNION BANK OF INDIA", "UNION BANK"),
        "Bank of India" to listOf("BANK OF INDIA"),
        "Indian Bank" to listOf("INDIAN BANK"),
        "Central Bank of India" to listOf("CENTRAL BANK OF INDIA"),
        "Indian Overseas Bank" to listOf("INDIAN OVERSEAS BANK", "IOB"),
        "Bank of Maharashtra" to listOf("BANK OF MAHARASHTRA"),
        "UCO Bank" to listOf("UCO BANK"),
        "IDBI Bank" to listOf("IDBI BANK", "IDBI"),
        "Citi" to listOf("CITIBANK", "CITI"),
        "DBS Bank" to listOf("DBS BANK", "DBS"),
        "DCB Bank" to listOf("DCB BANK"),
        "Barclays" to listOf("BARCLAYS"),
        "Deutsche Bank" to listOf("DEUTSCHE BANK"),
        "CSB Bank" to listOf("CSB BANK", "CSB EDGE", "EDGE CSB", "JUPITER EDGE+", "JUPITER EDGE PLUS", "EDGE+", "CSB"),
        "City Union Bank" to listOf("CITY UNION BANK"),
        "Dhanlaxmi Bank" to listOf("DHANLAXMI BANK", "DHANLAXMI"),
        "J&K Bank" to listOf("JAMMU KASHMIR BANK", "J&K BANK", "JK BANK"),
        "Karnataka Bank" to listOf("KARNATAKA BANK"),
        "Karur Vysya Bank" to listOf("KARUR VYSYA BANK", "KARUR VYSYA", "KVB"),
        "South Indian Bank" to listOf("SOUTH INDIAN BANK"),
        "Saraswat Bank" to listOf("SARASWAT BANK", "SARASWAT"),
        "Abhyudaya Bank" to listOf("ABHYUDAYA BANK", "ABHYUDAYA"),
    ).filterKeys { name -> all.any { it.name == name } }

    /** Monogram for the bank badge. */
    fun short(name: String): String = when (canonical(name) ?: name) {
        "HDFC Bank" -> "HDFC"; "SBI Card" -> "SBI"; "ICICI Bank" -> "ICICI"; "Axis Bank" -> "AXIS"
        "Kotak" -> "kotak"; "Yes Bank" -> "YES"; "IDFC FIRST" -> "IDFC"; "IndusInd" -> "IIB"
        "AU Bank" -> "AU"; "RBL Bank" -> "RBL"; "Amex" -> "AMEX"; "HSBC" -> "HSBC"
        "Standard Chartered" -> "SC"; "Federal Bank" -> "FED"; "BOB Card" -> "bob"; "OneCard" -> "ONE"
        "Slice" -> "slice"; "Unity Bank (Roar)" -> "ROAR"
        "State Bank of India" -> "SBI"; "Bank of Baroda" -> "bob"; "Punjab National Bank" -> "PNB"; "Canara Bank" -> "CAN"
        "Union Bank of India" -> "UBI"; "Bank of India" -> "BOI"; "Indian Bank" -> "IB"; "Central Bank of India" -> "CBI"
        "Indian Overseas Bank" -> "IOB"; "Bank of Maharashtra" -> "BOM"; "UCO Bank" -> "UCO"; "IDBI Bank" -> "IDBI"
        "Citi" -> "citi"; "DBS Bank" -> "DBS"; "DCB Bank" -> "DCB"; "Barclays" -> "BARC"; "Deutsche Bank" -> "DB"
        "CSB Bank" -> "CSB"; "City Union Bank" -> "CUB"; "Dhanlaxmi Bank" -> "DLB"; "J&K Bank" -> "J&K"
        "Karnataka Bank" -> "KBL"; "Karur Vysya Bank" -> "KVB"; "South Indian Bank" -> "SIB"; "Saraswat Bank" -> "SRS"
        "Abhyudaya Bank" -> "ABH"
        "Paytm" -> "paytm"
        else -> name.filter(Char::isLetter).take(3).uppercase().ifEmpty { "•" }
    }

    fun bySender(from: String): Issuer? {
        val f = from.lowercase()
        return all.firstOrNull { iss -> iss.statementFrom.any { f.contains(it) } || iss.senders.any { f.contains(it) } }
    }

    val networks = listOf("Visa", "Mastercard", "RuPay", "Amex", "Diners")
}
