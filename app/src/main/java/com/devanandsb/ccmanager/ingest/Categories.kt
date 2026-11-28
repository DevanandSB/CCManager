package com.devanandsb.ccmanager.ingest

import com.devanandsb.ccmanager.data.Category
import com.devanandsb.ccmanager.data.CategoryRule

/** Starter categories and merchant rules, seeded cleanly. Users edit from there. */
object DefaultCategories {
    const val USER_PRIORITY = 100
    private const val SEED_PRIORITY = 10

    val seed: List<Triple<String, Long, List<String>>> = listOf(
        Triple(
            "Food & dining", 0xFFEF6C00,
            listOf(
                "swiggy", "zomato", "eatsure", "domino", "mcdonald", "kfc", "starbucks", "restaurant",
                "cafe", "pizza", "burger", "chaayos", "haldiram", "subway", "dunkin", "eatclub",
                "biryani", "tiffin", "dhaba", "bakery", "sweets", "mithai", "bistro", "kitchen",
                "cater", "coffee", "chai", "tea stall", "ice cream", "baskin", "barista", "third wave",
                "wow momo", "behrouz", "faasos", "box8", "magicpin",
            ),
        ),
        Triple(
            "Groceries", 0xFF43A047,
            listOf(
                "bigbasket", "blinkit", "zepto", "instamart", "dmart", "avenue supermart", "jiomart",
                "grofers", "more retail", "nature's basket", "ratnadeep", "spencer", "star bazaar",
                "milkbasket", "country delight", "supermarket", "super market", "supermart", "kirana",
                "provision", "general store", "dairy", "milk", "vegetables", "fruits", "amul",
                "nandini", "mother dairy", "heritage", "licious", "fresh", "organic",
            ),
        ),
        Triple(
            "Shopping", 0xFF8E24AA,
            listOf(
                "amazon", "flipkart", "myntra", "ajio", "nykaa", "meesho", "tatacliq", "croma",
                "reliance digital", "vijay sales", "decathlon", "ikea", "lifestyle", "westside",
                "pantaloons", "h&m", "zara", "uniqlo", "apple", "petshop", "pet shop", "pet store",
                "zudio", "trends", "max fashion", "shoppers stop", "lenskart", "titan", "tanishq",
                "jewel", "electronics", "garments", "textiles", "boutique", "footwear", "bata",
                "nike", "adidas", "puma", "hardware", "stationery",
            ),
        ),
        Triple(
            "Travel", 0xFF1E88E5,
            listOf(
                "makemytrip", "goibibo", "cleartrip", "irctc", "indigo", "interglobe", "air india",
                "vistara", "akasa", "spicejet", "uber", "olacabs", "ola cabs", "rapido", "yatra",
                "agoda", "booking.com", "airbnb", "oyo", "ixigo", "redbus", "abhibus", "fastag",
                "nhai", "toll", "metro", "bmtc", "ksrtc", "msrtc", "namma yatri", "cab", "airways",
                "railway", "treebo", "fabhotel",
            ),
        ),
        Triple(
            "Fuel", 0xFF6D4C41,
            listOf(
                "fuel", "petrol", "diesel", "petroleum", "hpcl", "bpcl", "iocl", "indian oil",
                "indianoil", "bharat petroleum", "hindustan petroleum", "nayara", "shell", "essar",
                "hp pay", "filling station", "service station", "cng", "ev charging",
            ),
        ),
        Triple(
            "Bills & utilities", 0xFF00897B,
            listOf(
                "airtel", "jio", "vodafone", "bescom", "electricity", "tata power", "adani",
                "act fibernet", "bsnl", "broadband", "recharge", "billdesk", "mahanagar gas",
                "water board", "torrent power", "tneb", "msedcl", "mescom", "hescom", "cesc",
                "hathway", "tata play", "dth", "indane", "hp gas", "bharat gas", "igl", "mgl",
                "postpaid", "prepaid", "utility", "municipal", "insurance", "lic",
            ),
        ),
        Triple(
            "Entertainment", 0xFFD81B60,
            listOf(
                "netflix", "hotstar", "spotify", "prime video", "youtube", "bookmyshow", "pvr",
                "inox", "cinepolis", "sonyliv", "zee5", "jiocinema", "steam", "playstation",
                "xbox", "cinema", "multiplex", "gaming", "concert",
            ),
        ),
        Triple(
            "Health", 0xFFE53935,
            listOf(
                "pharmacy", "pharma", "apollo", "1mg", "pharmeasy", "netmeds", "truemeds",
                "hospital", "clinic", "medplus", "practo", "cult.fit", "cultfit", "diagnostic",
                "chemist", "druggist", "medicine", "medical", "dental", "thyrocare", "pathlab",
                "lal path", "doctor", "health", "gym",
            ),
        ),
        Triple(
            "UPI & transfers", 0xFF5C6BC0,
            listOf(
                "cred club", "cred rent", "nobroker", "housing rent", "rent pay", "wallet load",
                "upi/p2p", "p2p transfer", "fund transfer",
            ),
        ),
        Triple(
            "Payments", 0xFF2E7D32,
            listOf(
                "payment received", "thank you", "autopay payment", "autopay bill", "autodebit payment",
                "neft", "imps", "upi payment", "bbps payment", "payment - ", "repayment",
            ),
        ),
        Triple(
            "Fees & interest", 0xFF757575,
            listOf(
                "interest", "finance charge", "late fee", "late payment", "annual fee",
                "membership fee", "joining fee", "gst", "igst", "cgst", "sgst", "surcharge",
                "overlimit", "cash advance fee", "mark-up", "markup", "processing fee",
            ),
        ),
        Triple(
            "Education", 0xFF3949AB,
            listOf("school", "college", "university", "udemy", "coursera", "byju", "unacademy", "tuition", "academy"),
        ),
        Triple("Other", 0xFF90A4AE, emptyList()),
    )

    fun rules(categoryIds: Map<String, Long>): List<CategoryRule> =
        seed.flatMap { (name, _, patterns) ->
            val id = categoryIds[name] ?: return@flatMap emptyList()
            patterns.map { CategoryRule(categoryId = id, pattern = it, priority = SEED_PRIORITY) }
        }

    fun categories(): List<Category> = seed.map { (name, color, _) -> Category(name = name, color = color) }
}

class Categorizer(
    rules: List<CategoryRule>,
    categories: List<Category> = emptyList(),
) {
    private val sorted = rules.sortedWith(compareByDescending<CategoryRule> { it.priority }.thenByDescending { it.pattern.length })
    private val catByLowerName = categories.associateBy({ it.name.lowercase() }) { it.id }

    fun categorise(merchant: String): Long? = categorise(merchant, categoryHint = "", mcc = "", isUpi = false)

    fun categorise(
        merchant: String,
        categoryHint: String = "",
        mcc: String = "",
        isUpi: Boolean = false,
        kind: String = "",
    ): Long? {
        val m = merchant.lowercase()

        // 1. User-created custom rules always win.
        sorted.firstOrNull { it.priority >= DefaultCategories.USER_PRIORITY && m.contains(it.pattern.lowercase()) }
            ?.let { return it.categoryId }

        // 2. Explicit transaction kind for repayments & fees.
        if (kind == TxnKind.PAYMENT) catId("Payments")?.let { return it }
        if (kind == TxnKind.FEE) catId("Fees & interest")?.let { return it }

        // 3. Explicit category from the bank statement (e.g. "• Groceries", "• Food & Dining").
        val cleanHint = categoryHint.trim().ifEmpty { Text.bulletCategory(merchant) }
        if (cleanHint.isNotEmpty()) {
            hintCategoryName(cleanHint)?.let { name -> catId(name)?.let { return it } }
        }

        // 4. ISO-18245 Merchant Category Code (MCC) from statement or SMS.
        val code = mcc.trim().ifEmpty { Text.extractMcc(merchant) }
        if (code.isNotEmpty()) {
            mccCategoryName(code)?.let { name -> catId(name)?.let { return it } }
        }

        // 5. Built-in merchant & UPI store keyword rules.
        sorted.firstOrNull { ruleMatches(m, it) }?.let { return it.categoryId }

        // 6. UPI handles / P2P transfers shouldn't collapse into "Other".
        val looksLikeUpi = isUpi ||
            m.startsWith("upi/") || m.startsWith("upi-") || m.startsWith("upi ") ||
            UPI_HANDLE_RX.containsMatchIn(m)
        if (looksLikeUpi) {
            catId("UPI & transfers")?.let { return it }
        }
        return null
    }

    private fun ruleMatches(merchantLower: String, rule: CategoryRule): Boolean {
        val pat = rule.pattern.lowercase()
        if (rule.priority < DefaultCategories.USER_PRIORITY && pat in WORD_BOUNDARY_SEED_PATTERNS) {
            return WORD_BOUNDARY_CACHE.getOrPut(pat) { Regex("\\b${Regex.escape(pat)}\\b") }.containsMatchIn(merchantLower)
        }
        return merchantLower.contains(pat)
    }

    private fun catId(name: String): Long? = catByLowerName[name.lowercase()]

    companion object {
        private val WORD_BOUNDARY_SEED_PATTERNS = setOf("lic", "cab", "chai", "inox", "dth", "cng", "gst", "gym", "interest", "oyo", "shell", "bata")
        private val WORD_BOUNDARY_CACHE = HashMap<String, Regex>()
        private val UPI_HANDLE_RX = Regex(
            "[a-z0-9._-]+@(?:ok[a-z]+|ybl|ibl|axl|paytm|pt[a-z]+|pingpay|upi|icici|hdfcbank|sbi|kotak|axis|apl|rapl|sib|fbl|federal|indus|aubank|jupiteraxis|sliceaxis|superyes|naviaxis|fam|freecharge|jio|airtel|wa[a-z]+)",
        )

        fun hintCategoryName(hint: String): String? {
            val h = hint.lowercase()
            return when {
                h.contains("food") || h.contains("dining") || h.contains("restaurant") || h.contains("eat") || h.contains("cafe") -> "Food & dining"
                h.contains("grocer") || h.contains("supermarket") || h.contains("kirana") || h.contains("dairy") -> "Groceries"
                h.contains("fuel") || h.contains("petrol") || h.contains("gas station") -> "Fuel"
                h.contains("travel") || h.contains("transport") || h.contains("transit") || h.contains("cab") || h.contains("commute") || h.contains("flight") || h.contains("hotel") || h.contains("train") || h.contains("railway") || h.contains("airline") -> "Travel"
                h.contains("shop") || h.contains("retail") || h.contains("apparel") || h.contains("fashion") || h.contains("electronic") || h.contains("dept") || h.contains("cloth") || h.contains("jewel") || h.contains("e-commerce") || h.contains("ecommerce") || h.contains("hardware") -> "Shopping"
                h.contains("bill") || h.contains("utilit") || h.contains("recharge") || h.contains("telecom") || h.contains("broadband") || h.contains("electric") || h.contains("insurance") -> "Bills & utilities"
                h.contains("entertain") || h.contains("movie") || h.contains("media") || h.contains("gaming") -> "Entertainment"
                h.contains("health") || h.contains("med") || h.contains("pharma") || h.contains("hospital") || h.contains("wellness") -> "Health"
                h.contains("educat") || h.contains("tuition") || h.contains("school") -> "Education"
                h.contains("fee") || h.contains("interest") || h.contains("charge") || h.contains("tax") -> "Fees & interest"
                h.contains("repay") || h.contains("payment") || h.contains("bill pay") -> "Payments"
                h.contains("transfer") || h.contains("p2p") || h.contains("rent") || h.contains("wallet") -> "UPI & transfers"
                else -> null
            }
        }

        fun mccCategoryName(code: String): String? {
            val n = code.toIntOrNull() ?: return null
            return when (n) {
                in 3000..3999 -> "Travel"
                4111, 4112, 4121, 4131, 4411, 4511, 4722, 4784, 4789, 7011, 7512 -> "Travel"
                5411, 5422, 5441, 5451, 5462, 5499 -> "Groceries"
                5541, 5542, 5983 -> "Fuel"
                5811, 5812, 5813, 5814 -> "Food & dining"
                4812, 4814, 4816, 4899, 4900, 5968, 6300 -> "Bills & utilities"
                5912, 8011, 8021, 8031, 8041, 8042, 8043, 8049, 8050, 8062, 8071, 8099 -> "Health"
                7829, 7832, 7841, 7911, 7922, 7929, 7932, 7933, 7941, 7991, 7994, 7996, 7997, 7999 -> "Entertainment"
                8211, 8220, 8241, 8244, 8249, 8299 -> "Education"
                in 5200..5399, in 5611..5699, in 5712..5735, in 5921..5949, in 5970..5977, in 5992..5999 -> "Shopping"
                4829, 6010, 6011, 6012, 6051, 6513, 6540 -> "UPI & transfers"
                else -> null
            }
        }

        /** A reusable rule key from a raw merchant line: "SWIGGY*ORDER 1234 BANGALORE IN" -> "swiggy". */
        fun merchantKey(merchant: String): String {
            val words = merchant.lowercase()
                .replace(Regex("[*#_/]"), " ")
                .replace(Regex("\\b(www\\.|\\.com|\\.in|pvt|ltd|private|limited|india|bangalore|bengaluru|mumbai|delhi|gurgaon|gurugram|noida|pune|hyderabad|chennai|kolkata|in)\\b"), " ")
                .split(Regex("\\s+")).filter { w -> w.length >= 3 && w.any(Char::isLetter) && w.count(Char::isDigit) < 3 }
            return words.take(2).joinToString(" ").ifBlank { merchant.lowercase().take(12).trim() }
        }
    }
}

/** What a statement line is, separate from its category: only spends and fees are spending. */
object TxnKind {
    const val SPEND = "spend"
    const val PAYMENT = "payment"
    const val REFUND = "refund"
    const val CASHBACK = "cashback"
    const val FEE = "fee"

    private val payment = Regex(
        "\\bpayment\\b|\\bpayments\\s+received\\b|payzapp|bppy|thank you|autopay|auto[- ]?debit|repayment|pymt|\\bpmt\\b|recd|neft|imps|rtgs|bbps|\\bach\\b|\\bnach\\b|bill ?desk|received|^upi[- ]|cc pay|cred\\s*(?:club|pay)|cheq",
        RegexOption.IGNORE_CASE,
    )
    private val refund = Regex("refund|reversal|reversed|cancel", RegexOption.IGNORE_CASE)
    private val cashback = Regex(
        "cash ?back|\\bmonies\\b|\\brewards?\\b.*\\b(?:credit|redeem|redempt|cash|bonus|adj|earn|statement)|\\b(?:credit|redeem|redempt|cash|bonus|adj)\\b.*\\brewards?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val debitCashback = Regex("cash ?back|\\bmonies\\b|\\breward\\b.*\\b(?:revers|forfeit|laps|deduct|adj)|\\b(?:revers|forfeit|laps|deduct|adj)\\b.*\\breward\\b", RegexOption.IGNORE_CASE)
    private val fee = Regex(
        "^fee:|\\bfee\\b|\\bcharges?\\b|\\b(?:chg|chrgs)\\b|\\bgst\\b|\\bigst\\b|\\bcgst\\b|\\bsgst\\b|\\binterest\\b|\\bfinance charge\\b|late pay|late ?fee|\\bsurcharge\\b|\\bmark-?up\\b|\\bmarkup\\b|over ?limit|int\\.?\\s*ch|\\bemi\\b.*\\b(interest|int|processing|proc)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun classify(description: String, isCredit: Boolean): String = when {
        isCredit && cashback.containsMatchIn(description) -> CASHBACK
        !isCredit && debitCashback.containsMatchIn(description) -> CASHBACK // reversals are debits of the same kind
        isCredit && refund.containsMatchIn(description) -> REFUND
        isCredit && payment.containsMatchIn(description) -> PAYMENT
        isCredit -> REFUND
        fee.containsMatchIn(description) -> FEE
        else -> SPEND
    }

    fun label(kind: String) = when (kind) {
        PAYMENT -> "Payment"; REFUND -> "Refund"; CASHBACK -> "Cashback"; FEE -> "Fee"; else -> "Spend"
    }

    /** Counts toward "spent": purchases and fees, less refunds. Payments and cashback are separate. */
    fun spendSign(kind: String, isCredit: Boolean): Int = when (kind) {
        SPEND, FEE -> if (isCredit) -1 else 1
        REFUND -> -1
        else -> 0
    }
}
