package com.devanandsb.ccmanager.ingest

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One saved PDF unlock password, optionally linked to the card it belongs to. */
data class RememberedPdfPassword(
    val password: String,
    val cardId: Long? = null,
    val cardLabel: String = "",
)

/** What Indian issuers build statement passwords from. Stored encrypted; never leaves the phone. */
data class PdfProfile(
    val name: String = "",
    val dob: LocalDate? = null,
    val pan: String = "",
    val mobile: String = "",
    val templates: List<String> = emptyList(),
    val cardPasswords: List<RememberedPdfPassword> = emptyList(),
) {
    fun toJson(): String {
        val cpArr = JSONArray()
        cardPasswords.forEach { cp ->
            cpArr.put(
                JSONObject()
                    .put("password", cp.password)
                    .put("cardId", cp.cardId ?: JSONObject.NULL)
                    .put("cardLabel", cp.cardLabel),
            )
        }
        return JSONObject()
            .put("name", name).put("dob", dob?.toString() ?: "").put("pan", pan).put("mobile", mobile)
            .put("templates", JSONArray(templates))
            .put("cardPasswords", cpArr)
            .toString()
    }

    companion object {
        fun fromJson(s: String?): PdfProfile {
            if (s.isNullOrBlank()) return PdfProfile()
            val o = JSONObject(s)
            val t = o.optJSONArray("templates")
            val cpArr = o.optJSONArray("cardPasswords")
            val cps = if (cpArr == null) emptyList() else List(cpArr.length()) { idx ->
                val obj = cpArr.getJSONObject(idx)
                val cid = if (obj.isNull("cardId")) null else obj.optLong("cardId").takeIf { it > 0L }
                RememberedPdfPassword(
                    password = obj.optString("password"),
                    cardId = cid,
                    cardLabel = obj.optString("cardLabel"),
                )
            }.filter { it.password.isNotBlank() }
            return PdfProfile(
                name = o.optString("name"),
                dob = o.optString("dob").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                pan = o.optString("pan"),
                mobile = o.optString("mobile"),
                templates = if (t == null) emptyList() else List(t.length()) { t.getString(it) },
                cardPasswords = cps,
            )
        }
    }
}

object Passwords {
    /**
     * Patterns Indian issuers commonly use. Every one is tried, so it does not matter which bank
     * uses which; a wrong guess costs a few milliseconds.
     */
    private val BUILT_IN = listOf(
        "{NAME4}{DDMM}", "{name4}{DDMM}", "{Name4}{DDMM}",
        "{DDMMYYYY}{LAST4}", "{DDMMYY}{LAST4}", "{LAST4}{DDMMYYYY}", "{LAST4}{DDMMYY}", "{LAST4}{DDMM}",
        "{NAME4}{DDMMYY}", "{name4}{DDMMYY}", "{NAME4}{DDMMYYYY}", "{name4}{DDMMYYYY}",
        "{NAME4}{LAST4}", "{name4}{LAST4}",
        "{DDMMYYYY}", "{DDMMYY}", "{DDMM}", "{DDMM}{LAST4}",
        "{PAN}", "{pan}", "{name4}{MOBILE4}", "{NAME4}{MOBILE4}", "{MOBILE}",
    )

    fun candidates(profile: PdfProfile, last4s: Collection<String>): List<String> {
        val words = profile.name.trim().split(Regex("\\s+")).map { it.filter(Char::isLetter) }.filter { it.isNotEmpty() }
        val firstWord = words.firstOrNull().orEmpty()
        val combinedLetters = words.joinToString("")
        val primary4 = if (firstWord.length >= 4) firstWord.take(4) else combinedLetters.take(4)
        val alt4 = words.firstOrNull { it.length >= 2 }?.take(4)
        val nameVariants = listOfNotNull(primary4.takeIf { it.isNotEmpty() }, alt4?.takeIf { it != primary4 }).distinct()
        val dob = profile.dob
        val staticTokens = mapOf(
            "{DDMM}" to (dob?.format(DateTimeFormatter.ofPattern("ddMM")) ?: ""),
            "{DDMMYY}" to (dob?.format(DateTimeFormatter.ofPattern("ddMMyy")) ?: ""),
            "{DDMMYYYY}" to (dob?.format(DateTimeFormatter.ofPattern("ddMMyyyy")) ?: ""),
            "{PAN}" to profile.pan.trim().uppercase(),
            "{pan}" to profile.pan.trim().lowercase(),
            "{MOBILE4}" to profile.mobile.filter(Char::isDigit).takeLast(4),
            "{MOBILE}" to profile.mobile.filter(Char::isDigit).takeLast(10),
        )
        val out = LinkedHashSet<String>()
        val lasts = last4s.ifEmpty { listOf("") }
        val names = nameVariants.ifEmpty { listOf("") }
        val literalCardPws = profile.cardPasswords.map { it.password }.filter { it.isNotBlank() }
        for (pw in literalCardPws) out += pw
        for (t in profile.templates + BUILT_IN) {
            for (n4 in names) {
                val base = staticTokens + mapOf(
                    "{NAME4}" to n4.uppercase(),
                    "{name4}" to n4.lowercase(),
                    "{Name4}" to n4.lowercase().replaceFirstChar { it.uppercase() },
                )
                for (l4 in lasts) {
                    val values = base + ("{LAST4}" to l4)
                    // Skip a template if any token it uses has no value.
                    if (values.any { (k, v) -> t.contains(k) && v.isEmpty() }) continue
                    var s = t
                    values.forEach { (k, v) -> s = s.replace(k, v) }
                    if (s.isNotBlank() && !s.contains('{')) out += s
                }
            }
        }
        return out.toList()
    }
}
