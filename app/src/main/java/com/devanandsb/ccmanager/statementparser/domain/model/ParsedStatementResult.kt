package com.devanandsb.ccmanager.statementparser.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

@Serializable
enum class ExtractionEngine {
    PDFBOX_VECTOR,
    MLKIT_OCR,
}

@Serializable
data class ParsedStatementResult(
    val summary: StatementSummary,
    val transactions: List<StatementTransaction>,
    val extractionEngine: ExtractionEngine,
    @Serializable(with = BigDecimalSerializer::class)
    val totalDebits: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val totalCredits: BigDecimal,
    val isMathVerified: Boolean,
    @Serializable(with = BigDecimalSerializer::class)
    val discrepancyAmount: BigDecimal,
    val pageCount: Int = 1,
) {
    fun toPrettyJson(): String = StatementParserJson.encodeToString(serializer(), this)

    companion object {
        val StatementParserJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

sealed interface ParseOutcome {
    data class Success(val result: ParsedStatementResult) : ParseOutcome

    data class PasswordRequired(
        val hint: String = "First 4 letters of name + DDMM, or DOB (DDMMYYYY)",
        val incorrectAttempt: Boolean = false,
    ) : ParseOutcome

    data class Failure(val message: String) : ParseOutcome
}
