package com.devanandsb.ccmanager.capture

import com.devanandsb.ccmanager.domain.CardNumbers

/** What the camera saw on a card. Pure text parsing, so it's unit-tested without a camera. */
data class ScannedCard(val number: String?, val expiry: String?, val issuer: String? = null)

object CardText {
    // 4-4-4-4 (Visa/MC/RuPay), 4-6-5 (Amex), 4-6-4 (Diners), or a plain run; OCR may mix spaces or hyphens.
    private val numberRun = Regex("(?<!\\d)(\\d[\\d \\u00A0\\-]{12,24}\\d)(?!\\d)")
    private val expiryRe = Regex("(?<![/\\-\\d])(0[1-9]|1[0-2])\\s?[/\\-]\\s?(\\d{2}|20\\d{2})(?![/\\-\\d])")
    private val ocrExpiryTokenRe = Regex("(?<![/\\-A-Za-z0-9])([0-9OoDQIl|][0-9OoDQIl|SBZ])\\s?[/\\-]\\s?([0-9OoDQIl|SBZ]{2}|2[0OoDQ][0-9OoDQIl|SBZ]{2})(?![/\\-A-Za-z0-9])")
    private val expiryCueRx = Regex("\\b(?:valid\\s*thru|good\\s*thru|valid\\s*to|expir(?:y|es)?|exp\\.?|thru|upto)\\b", RegexOption.IGNORE_CASE)
    private val issueCueRx = Regex("\\b(?:valid\\s*from|member\\s*since|since|issued|from)\\b", RegexOption.IGNORE_CASE)

    /** Common OCR slips on embossed digits. Only applied inside digit-looking runs. */
    private fun normalize(line: String) = line.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it }
    }.joinToString("")

    fun parse(text: String): ScannedCard {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val number = findCardNumber(lines)
        val expiry = latestExpiry(text)
        return ScannedCard(number, expiry, com.devanandsb.ccmanager.domain.Issuers.detect(text))
    }

    private fun findCardNumber(lines: List<String>): String? {
        val searchList = lines + lines.joinToString(" ")
        for (l in searchList) {
            val raw = numberRun.findAll(l).map { it.value.filter(Char::isDigit) }.toList()
            val norm = numberRun.findAll(normalize(l)).map { it.value.filter(Char::isDigit) }.toList()
            val valid = (raw + norm).distinct().filter { it.length in 14..19 && CardNumbers.luhnValid(it) }
            if (valid.isNotEmpty()) {
                return valid.firstOrNull { isExpectedNetworkLength(it) }
                    ?: valid.firstOrNull { CardNumbers.detectNetwork(it) != null }
                    ?: valid.first()
            }
        }
        return null
    }

    private fun isExpectedNetworkLength(digits: String): Boolean = when (CardNumbers.detectNetwork(digits)) {
        "Amex" -> digits.length == 15
        "Diners" -> digits.length == 14 || digits.length == 16
        "Mastercard" -> digits.length == 16
        "Visa", "RuPay" -> digits.length == 16 || digits.length == 19
        else -> false
    }

    /** Cards show "valid from" and "valid thru"; prefers labeled expiry, ignores "valid from"/"member since", returns "MM/YY". */
    private fun latestExpiry(text: String): String? {
        data class ExpHit(val year: Int, val month: Int, val formatted: String, val isExpiryLabeled: Boolean, val isIssueLabeled: Boolean)

        val rawMatches = expiryRe.findAll(text).map { it.range to it.groupValues[1] + "/" + it.groupValues[2].takeLast(2) }.toList()
        val rawRanges = rawMatches.map { it.first }
        val ocrMatches = ocrExpiryTokenRe.findAll(text).mapNotNull { m ->
            if (rawRanges.any { r -> m.range.first <= r.last && m.range.last >= r.first }) return@mapNotNull null
            val normTok = normalize(m.groupValues[1]) + "/" + normalize(m.groupValues[2]).takeLast(2)
            val parsed = expiryRe.matchEntire(normTok) ?: return@mapNotNull null
            m.range to "${parsed.groupValues[1]}/${parsed.groupValues[2].takeLast(2)}"
        }.toList()

        val hits = (rawMatches + ocrMatches).map { (range, mmYy) ->
            val mm = mmYy.substring(0, 2).toInt()
            val yy2 = mmYy.substring(3, 5).toInt()
            val fullYear = if (yy2 >= 80) 1900 + yy2 else 2000 + yy2
            val ctx = text.substring((range.first - 24).coerceAtLeast(0), range.first)
            val expMatch = expiryCueRx.findAll(ctx).lastOrNull()
            val issMatch = issueCueRx.findAll(ctx).lastOrNull()
            val isExp = expMatch != null && (issMatch == null || expMatch.range.last > issMatch.range.last)
            val isIss = issMatch != null && (expMatch == null || issMatch.range.last > expMatch.range.last)
            ExpHit(fullYear, mm, mmYy, isExp, isIss)
        }
        val pool = hits.filter { it.isExpiryLabeled }.ifEmpty { hits.filterNot { it.isIssueLabeled } }
        return pool.maxWithOrNull(compareBy({ it.year }, { it.month }))?.formatted
    }
}
