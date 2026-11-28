package com.devanandsb.ccmanager.statementparser.data

import android.content.Context
import android.net.Uri
import com.devanandsb.ccmanager.statementparser.data.extractor.MlKitOcrFallbackExtractor
import com.devanandsb.ccmanager.statementparser.data.extractor.PdfBoxCoordinateExtractor
import com.devanandsb.ccmanager.statementparser.data.extractor.PdfExtractionOutput
import com.devanandsb.ccmanager.statementparser.data.parser.BankPluginRegistry
import com.devanandsb.ccmanager.statementparser.data.parser.DynamicColumnDetector
import com.devanandsb.ccmanager.statementparser.data.parser.SummaryAndMathVerifier
import com.devanandsb.ccmanager.statementparser.data.parser.TransactionRowParser
import com.devanandsb.ccmanager.statementparser.domain.model.ExtractionEngine
import com.devanandsb.ccmanager.statementparser.domain.model.ParseOutcome
import com.devanandsb.ccmanager.statementparser.domain.model.ParsedStatementResult
import com.devanandsb.ccmanager.statementparser.domain.repository.StatementParserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

class StatementParserRepositoryImpl(
    context: Context,
    private val pdfBoxExtractor: PdfBoxCoordinateExtractor = PdfBoxCoordinateExtractor(context.applicationContext),
    private val mlKitOcrExtractor: MlKitOcrFallbackExtractor = MlKitOcrFallbackExtractor(context.applicationContext),
    private val columnDetector: DynamicColumnDetector = DynamicColumnDetector(),
    private val rowParser: TransactionRowParser = TransactionRowParser(columnDetector),
    private val bankPluginRegistry: BankPluginRegistry = BankPluginRegistry(),
    private val summaryAndMathVerifier: SummaryAndMathVerifier = SummaryAndMathVerifier(),
) : StatementParserRepository {

    override suspend fun parseStatement(uri: Uri, password: String?): ParseOutcome =
        withContext(Dispatchers.IO) {
            val (engine, extracted) = when (val vectorOut = pdfBoxExtractor.extract(uri, password)) {
                is PdfExtractionOutput.PasswordRequired -> {
                    return@withContext ParseOutcome.PasswordRequired(
                        incorrectAttempt = vectorOut.incorrectAttempt,
                    )
                }

                is PdfExtractionOutput.Error -> {
                    return@withContext ParseOutcome.Failure(vectorOut.message)
                }

                is PdfExtractionOutput.Blocks -> {
                    val p1Blocks = vectorOut.blocks.filter { it.page == 1 }
                    val p1HasDigits = p1Blocks.any { it.text.any(Char::isDigit) }
                    val needsOcr = MlKitOcrFallbackExtractor.needsOcrFallback(
                        rawText = vectorOut.rawText,
                        blockCount = vectorOut.blocks.size,
                        page1HasAmountsOrDates = p1HasDigits,
                    )
                    if (!needsOcr) {
                        ExtractionEngine.PDFBOX_VECTOR to vectorOut
                    } else {
                        // Zero, sparse, garbled CMap, or hybrid rasterized PDF -> run offline ML Kit OCR fallback
                        when (val ocrOut = mlKitOcrExtractor.extract(uri, password)) {
                            is PdfExtractionOutput.PasswordRequired -> {
                                return@withContext ParseOutcome.PasswordRequired(
                                    incorrectAttempt = ocrOut.incorrectAttempt,
                                )
                            }

                            is PdfExtractionOutput.Error -> {
                                if (vectorOut.blocks.isNotEmpty()) {
                                    ExtractionEngine.PDFBOX_VECTOR to vectorOut
                                } else {
                                    return@withContext ParseOutcome.Failure(ocrOut.message)
                                }
                            }

                            is PdfExtractionOutput.Blocks -> {
                                val vectorAlnum = vectorOut.rawText.count { it.isLetterOrDigit() }
                                val ocrAlnum = ocrOut.rawText.count { it.isLetterOrDigit() }
                                when {
                                    ocrOut.blocks.isEmpty() && vectorOut.blocks.isEmpty() -> {
                                        return@withContext ParseOutcome.Failure(
                                            "No readable text was detected in this PDF.",
                                        )
                                    }
                                    ocrAlnum > vectorAlnum || !p1HasDigits -> {
                                        ExtractionEngine.MLKIT_OCR to ocrOut
                                    }
                                    else -> {
                                        ExtractionEngine.PDFBOX_VECTOR to vectorOut
                                    }
                                }
                            }
                        }
                    }
                }
            }

            var bestEngine = engine
            var bestResult = runPipeline(engine, extracted)

            // Hybrid PDF recovery: Page 1 has vector summary text, while later transaction pages are raster images.
            if (engine == ExtractionEngine.PDFBOX_VECTOR) {
                val noTxnsDespiteDue = bestResult.transactions.isEmpty() &&
                    (bestResult.summary.totalAmountDue ?: BigDecimal.ZERO) > BigDecimal.ZERO
                val mismatchWithSparsePages = !bestResult.isMathVerified &&
                    bestResult.discrepancyAmount > BigDecimal("2.00") &&
                    hasSparseLaterPages(extracted)
                if (noTxnsDespiteDue || mismatchWithSparsePages) {
                    val ocrOut = mlKitOcrExtractor.extract(uri, password)
                    if (ocrOut is PdfExtractionOutput.Blocks && ocrOut.blocks.isNotEmpty()) {
                        val ocrResult = runPipeline(ExtractionEngine.MLKIT_OCR, ocrOut)
                        val ocrHasTxns = ocrResult.transactions.isNotEmpty()
                        val vecDiscrepancy = bestResult.discrepancyAmount
                        val ocrDiscrepancy = ocrResult.discrepancyAmount
                        if ((noTxnsDespiteDue && ocrHasTxns) ||
                            (ocrHasTxns && ocrResult.isMathVerified) ||
                            (ocrHasTxns && ocrDiscrepancy < vecDiscrepancy)
                        ) {
                            bestEngine = ExtractionEngine.MLKIT_OCR
                            bestResult = ocrResult
                        }
                    }
                }
            }

            ParseOutcome.Success(bestResult.copy(extractionEngine = bestEngine))
        }

    private fun runPipeline(
        engine: ExtractionEngine,
        extracted: PdfExtractionOutput.Blocks,
    ): ParsedStatementResult {
        val page1Rows = extracted.rows.filter { row -> row.firstOrNull()?.page == 1 }
        val initialSummary = summaryAndMathVerifier.extractSummary(
            page1Rows = page1Rows,
            rawText = extracted.rawText,
            allRows = extracted.rows,
        )

        val inferredYear = initialSummary.statementDate?.take(4)?.toIntOrNull()
            ?: initialSummary.paymentDueDate?.take(4)?.toIntOrNull()

        val allTables = extracted.pageGeometries.flatMap { it.tables }
        val detectedLayout = columnDetector.detect(extracted.rows, allTables)
        val tabularTxns = if (detectedLayout != null) {
            rowParser.parseRows(
                rows = extracted.rows,
                layout = detectedLayout,
                inferredYear = inferredYear,
                tables = allTables,
            )
        } else {
            emptyList()
        }

        val transactions = tabularTxns.ifEmpty {
            bankPluginRegistry.parseFallback(
                rawText = extracted.rawText,
                rows = extracted.rows,
            )
        }

        val (reconciledSummary, math) = summaryAndMathVerifier.reconcileAndVerify(
            summary = initialSummary,
            transactions = transactions,
            page1Rows = page1Rows,
        )

        return ParsedStatementResult(
            summary = reconciledSummary,
            transactions = transactions,
            extractionEngine = engine,
            totalDebits = math.totalDebits,
            totalCredits = math.totalCredits,
            isMathVerified = math.isMathVerified,
            discrepancyAmount = math.discrepancyAmount,
            pageCount = extracted.pageCount,
        )
    }

    private fun hasSparseLaterPages(extracted: PdfExtractionOutput.Blocks): Boolean {
        if (extracted.pageCount <= 1) return false
        val byPage = extracted.blocks.groupBy { it.page }
        return (2..extracted.pageCount).any { pageNo ->
            byPage[pageNo].orEmpty().sumOf { it.text.length } < 45
        }
    }
}
