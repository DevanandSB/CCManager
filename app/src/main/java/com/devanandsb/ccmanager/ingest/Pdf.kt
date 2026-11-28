package com.devanandsb.ccmanager.ingest

import android.content.Context
import com.devanandsb.ccmanager.statementparser.data.cv.PdfVectorGeometryExtractor
import com.devanandsb.ccmanager.statementparser.data.cv.TinyCvTableDetector
import com.devanandsb.ccmanager.statementparser.data.extractor.MlKitOcrFallbackExtractor
import com.devanandsb.ccmanager.statementparser.data.extractor.PdfExtractionOutput
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.security.MessageDigest

object Pdf {
    @Volatile private var ready = false

    fun init(context: Context) {
        if (!ready) synchronized(this) {
            if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true }
        }
    }

    sealed interface Result {
        data class Text(val text: String, val pages: List<PageLayout> = emptyList()) : Result
        data object Locked : Result
        data class Failed(val reason: String) : Result
    }

    /** Tries no password, then each candidate, in memory. The PDF is never written to disk. */
    fun extract(bytes: ByteArray, candidates: List<String>): Result {
        for (pw in (listOf("") + candidates).distinct()) {
            try {
                PDDocument.load(bytes, pw).use { doc ->
                    val pages = mutableListOf<PageLayout>()
                    val stripper = object : PDFTextStripper() {
                        private val pageGlyphs = mutableListOf<GlyphBox>()

                        init {
                            sortByPosition = true
                        }

                        override fun startPage(page: PDPage) {
                            super.startPage(page)
                            pageGlyphs.clear()
                        }

                        override fun processTextPosition(text: TextPosition) {
                            super.processTextPosition(text)
                            val ch = text.unicode ?: return
                            if (ch.isEmpty()) return
                            val x = text.xDirAdj
                            val w = text.widthDirAdj
                            val y = text.yDirAdj
                            val sw = text.widthOfSpace.takeIf { it > 0f } ?: 3.5f
                            pageGlyphs.add(GlyphBox(x, x + w, y, sw, ch))
                        }

                        override fun endPage(page: PDPage) {
                            super.endPage(page)
                            val box = page.mediaBox
                            val geo = PdfVectorGeometryExtractor.extractPageGeometry(page, currentPageNo)
                            val rawSpans = PageLayoutBuilder.buildSpans(currentPageNo, pageGlyphs)
                            val taggedSpans = TinyCvTableDetector.assignSpansToBoxes(rawSpans, geo.boxes)
                            pages += PageLayout(
                                page = currentPageNo,
                                width = box?.width ?: 595f,
                                height = box?.height ?: 842f,
                                spans = taggedSpans,
                                boxes = geo.boxes,
                                tables = geo.tables,
                            )
                        }
                    }
                    val text = stripper.getText(doc)
                    return Result.Text(text, pages)
                }
            } catch (_: InvalidPasswordException) {
                continue
            } catch (_: Exception) {
                return Result.Failed("Couldn't read the PDF file")
            } catch (_: OutOfMemoryError) {
                return Result.Failed("PDF too large")
            }
        }
        return Result.Locked
    }

    /**
     * Extracts vector text + vector/CV coordinates via PDFBox and automatically falls back to
     * offline CV + ML Kit OCR when a PDF is scanned, rasterized, sparse, garbled-CMap, or hybrid
     * (e.g. Page 1 vector text + Page 2+ scanned transaction images).
     */
    suspend fun extractWithOcrFallback(
        context: Context,
        bytes: ByteArray,
        candidates: List<String>,
        hasExplicitAttempt: Boolean = false,
    ): Result {
        val vectorResult = extract(bytes, candidates)
        if (vectorResult !is Result.Text) return vectorResult

        val p1 = vectorResult.pages.firstOrNull { it.page == 1 } ?: vectorResult.pages.firstOrNull()
        val p1HasData = p1 != null && p1.spans.any { s ->
            s.text.any(Char::isDigit) &&
                (Text.signedHeaderAmounts(s.text).isNotEmpty() || Text.dateTokens(s.text).isNotEmpty())
        }
        val spanCount = vectorResult.pages.sumOf { it.spans.size }
        val hasHybridImagePages = detectHybridImagePagesWithoutText(bytes, candidates, vectorResult.pages)
        if (!hasHybridImagePages && !MlKitOcrFallbackExtractor.needsOcrFallback(vectorResult.text, spanCount, p1HasData)) {
            return vectorResult
        }

        val ocrOut = MlKitOcrFallbackExtractor(context.applicationContext)
            .extractFromBytes(bytes, candidates, hadExplicitAttempt = hasExplicitAttempt)
        return when (ocrOut) {
            is PdfExtractionOutput.Blocks -> {
                val vecAlnum = vectorResult.text.count { it.isLetterOrDigit() }
                val ocrAlnum = ocrOut.rawText.count { it.isLetterOrDigit() }
                if (ocrOut.blocks.isNotEmpty() && (ocrAlnum > vecAlnum || !p1HasData || hasHybridImagePages)) {
                    Result.Text(ocrOut.rawText, MlKitOcrFallbackExtractor.toPageLayouts(ocrOut))
                } else {
                    vectorResult
                }
            }
            is PdfExtractionOutput.PasswordRequired -> if (vectorResult.text.isBlank()) Result.Locked else vectorResult
            is PdfExtractionOutput.Error -> vectorResult
        }
    }

    private fun detectHybridImagePagesWithoutText(
        bytes: ByteArray,
        candidates: List<String>,
        pages: List<PageLayout>,
    ): Boolean {
        val sparsePages = pages.filter { p -> p.spans.sumOf { it.text.length } < 45 }.map { it.page }.toSet()
        if (sparsePages.isEmpty()) return false
        for (pw in (listOf("") + candidates).distinct()) {
            val hit = runCatching {
                PDDocument.load(bytes, pw).use { doc ->
                    sparsePages.any { pageNo ->
                        val page = doc.getPage((pageNo - 1).coerceIn(0, doc.numberOfPages - 1))
                        val res = page.resources ?: return@any false
                        res.xObjectNames.any { name ->
                            runCatching { res.getXObject(name) is PDImageXObject }.getOrDefault(false)
                        }
                    }
                }
            }.getOrNull()
            if (hit != null) return hit
        }
        return false
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
