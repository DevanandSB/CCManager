package com.devanandsb.ccmanager.statementparser.data.extractor

import android.content.Context
import android.net.Uri
import com.devanandsb.ccmanager.statementparser.data.cv.PdfVectorGeometryExtractor
import com.devanandsb.ccmanager.statementparser.data.cv.TinyCvTableDetector
import com.devanandsb.ccmanager.statementparser.domain.model.PageGeometry
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thread-safe, one-time initializer for PdfBox-Android's font and encoding resources.
 */
object PdfBoxInitializer {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }
}

sealed interface PdfExtractionOutput {
    data class Blocks(
        val blocks: List<TextBlock>,
        val rows: List<List<TextBlock>>,
        val rawText: String,
        val pageCount: Int,
        val pageGeometries: List<PageGeometry> = emptyList(),
    ) : PdfExtractionOutput

    data class PasswordRequired(val incorrectAttempt: Boolean) : PdfExtractionOutput
    data class Error(val message: String) : PdfExtractionOutput
}

class PdfBoxCoordinateExtractor(
    private val context: Context,
) {
    suspend fun extract(uri: Uri, password: String? = null): PdfExtractionOutput =
        withContext(Dispatchers.IO) {
            PdfBoxInitializer.ensureInitialized(context)

            val stream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                return@withContext PdfExtractionOutput.Error(
                    e.message ?: "Unable to open selected PDF file.",
                )
            } ?: return@withContext PdfExtractionOutput.Error("Unable to open selected PDF file.")

            try {
                stream.use { input ->
                    PDDocument.load(input, password.orEmpty()).use { document ->
                        val capturedBlocks = mutableListOf<TextBlock>()
                        val pageGeometries = mutableListOf<PageGeometry>()
                        val stripper = object : PDFTextStripper() {
                            private val pagePositions = mutableListOf<TextPosition>()

                            init {
                                sortByPosition = true
                            }

                            override fun startPage(page: PDPage) {
                                super.startPage(page)
                                pagePositions.clear()
                            }

                            override fun processTextPosition(text: TextPosition) {
                                super.processTextPosition(text)
                                val ch = text.unicode ?: return
                                if (ch.isEmpty()) return
                                pagePositions += text
                            }

                            override fun endPage(page: PDPage) {
                                super.endPage(page)
                                val geo = PdfVectorGeometryExtractor.extractPageGeometry(page, currentPageNo)
                                pageGeometries += geo
                                val rawPageBlocks = buildPageBlocks(currentPageNo, pagePositions)
                                capturedBlocks += TinyCvTableDetector.assignBlocksToBoxes(rawPageBlocks, geo.boxes)
                            }
                        }

                        val rawText = stripper.getText(document)
                        val dedupedBlocks = deduplicateOverlappingBlocks(capturedBlocks)
                        val rows = groupBlocksIntoRows(dedupedBlocks)
                        PdfExtractionOutput.Blocks(
                            blocks = dedupedBlocks,
                            rows = rows,
                            rawText = rawText,
                            pageCount = document.numberOfPages,
                            pageGeometries = pageGeometries,
                        )
                    }
                }
            } catch (_: InvalidPasswordException) {
                PdfExtractionOutput.PasswordRequired(incorrectAttempt = !password.isNullOrEmpty())
            } catch (e: OutOfMemoryError) {
                PdfExtractionOutput.Error("PDF file is too large to process on device.")
            } catch (_: Exception) {
                PdfExtractionOutput.Error("Couldn't read this PDF file.")
            }
        }

    private fun buildPageBlocks(pageNo: Int, positions: List<TextPosition>): List<TextBlock> {
        if (positions.isEmpty()) return emptyList()
        val sortedByY = positions.sortedWith(compareBy<TextPosition> { it.yDirAdj }.thenBy { it.xDirAdj })
        val yRows = mutableListOf<MutableList<TextPosition>>()
        for (pos in sortedByY) {
            val lastRow = yRows.lastOrNull()
            if (lastRow != null && abs(lastRow[0].yDirAdj - pos.yDirAdj) <= 2.0f) {
                lastRow += pos
            } else {
                yRows += mutableListOf(pos)
            }
        }
        val out = mutableListOf<TextBlock>()
        for (row in yRows) {
            val dedupedPositions = deduplicateGlyphPositions(row)
            val chunks = splitIntoVisualChunks(dedupedPositions)
            for (chunk in chunks) {
                val chunkText = cleanFauxBoldText(buildChunkString(chunk))
                if (chunkText.isBlank()) continue
                val first = chunk.first()
                val last = chunk.last()
                val startX = first.xDirAdj
                val endX = maxOf(last.xDirAdj + last.widthDirAdj, startX + 1f)
                val avgY = chunk.map { it.yDirAdj }.average().roundToInt()
                out += TextBlock(
                    text = chunkText,
                    x = startX.roundToInt(),
                    y = avgY,
                    width = (endX - startX).roundToInt().coerceAtLeast(1),
                    page = pageNo,
                )
            }
        }
        return out
    }

    private fun deduplicateGlyphPositions(positions: List<TextPosition>): List<TextPosition> {
        if (positions.size <= 1) return positions
        val sorted = positions.sortedWith(compareBy<TextPosition> { it.xDirAdj }.thenBy { it.yDirAdj })
        val result = ArrayList<TextPosition>(sorted.size)
        for (pos in sorted) {
            val ch = pos.unicode ?: continue
            if (ch.isEmpty()) continue
            val isDuplicate = result.asReversed().take(4).any { prev ->
                prev.unicode == ch &&
                    abs(prev.xDirAdj - pos.xDirAdj) <= 1.8f &&
                    abs(prev.yDirAdj - pos.yDirAdj) <= 1.8f
            }
            if (!isDuplicate) result += pos
        }
        return result
    }

    private fun splitIntoVisualChunks(positions: List<TextPosition>): List<List<TextPosition>> {
        if (positions.isEmpty()) return emptyList()
        val chunks = mutableListOf<MutableList<TextPosition>>()
        for (pos in positions) {
            val currentChunk = chunks.lastOrNull()
            if (currentChunk == null) {
                chunks += mutableListOf(pos)
            } else {
                val prev = currentChunk.last()
                val prevEndX = prev.xDirAdj + prev.widthDirAdj
                val gap = pos.xDirAdj - prevEndX
                val spaceWidth = prev.widthOfSpace.takeIf { it > 0f } ?: 3.5f
                val columnGapThreshold = maxOf(spaceWidth * 1.35f, 5.5f)
                if (gap <= columnGapThreshold) {
                    currentChunk += pos
                } else {
                    chunks += mutableListOf(pos)
                }
            }
        }
        return chunks
    }

    private fun buildChunkString(chunk: List<TextPosition>): String {
        val sb = StringBuilder()
        var prevEndX = chunk.first().xDirAdj
        for ((index, pos) in chunk.withIndex()) {
            val ch = pos.unicode ?: continue
            if (index > 0) {
                val gap = pos.xDirAdj - prevEndX
                val spaceWidth = pos.widthOfSpace.takeIf { it > 0f } ?: 3.5f
                if (gap > maxOf(spaceWidth * 0.42f, 1.4f) &&
                    !sb.endsWith(' ') &&
                    !ch.startsWith(' ')
                ) {
                    sb.append(' ')
                }
            }
            sb.append(ch)
            prevEndX = pos.xDirAdj + pos.widthDirAdj
        }
        return sb.toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \t]+"), " ")
            .trim()
    }

    companion object {
        private val DOUBLED_WORD_RX = Regex("\\b[A-Za-z0-9₹.,/\\-]{4,}\\b")

        /**
         * Groups blocks on each page into visual rows where `abs(blockA.y - blockB.y) <= 4` points
         * and sorts blocks within every row left-to-right by `x`.
         */
        fun groupBlocksIntoRows(
            blocks: List<TextBlock>,
            yTolerance: Int = 3,
        ): List<List<TextBlock>> {
            if (blocks.isEmpty()) return emptyList()
            val cleanBlocks = deduplicateOverlappingBlocks(blocks)
            val allRows = mutableListOf<List<TextBlock>>()

            val byPage = cleanBlocks.groupBy { it.page }.toSortedMap()
            for ((_, pageBlocks) in byPage) {
                val sorted = pageBlocks.sortedWith(compareBy<TextBlock> { it.y }.thenBy { it.x })
                val rows = mutableListOf<MutableList<TextBlock>>()
                for (block in sorted) {
                    val lastRow = rows.lastOrNull()
                    if (lastRow != null && abs(lastRow[0].y - block.y) <= yTolerance) {
                        lastRow += block
                    } else {
                        rows += mutableListOf(block)
                    }
                }
                for (row in rows) {
                    row.sortBy { it.x }
                    allRows += row.toList()
                }
            }
            return allRows
        }

        /**
         * Removes overlapping faux-bold duplicate blocks at near-identical `(page, x, y)` coordinates
         * and cleans doubled-character runs like `"CCRREEDDIITT"` -> `"CREDIT"`.
         */
        fun deduplicateOverlappingBlocks(blocks: List<TextBlock>): List<TextBlock> {
            if (blocks.isEmpty()) return emptyList()
            val sorted = blocks.sortedWith(
                compareBy<TextBlock> { it.page }.thenBy { it.y }.thenBy { it.x },
            )
            val out = ArrayList<TextBlock>(sorted.size)
            for (raw in sorted) {
                val cleanedText = cleanFauxBoldText(raw.text)
                if (cleanedText.isBlank()) continue
                val candidate = if (cleanedText == raw.text) raw else raw.copy(text = cleanedText)
                val duplicate = out.asReversed().take(8).any { prev ->
                    prev.page == candidate.page &&
                        abs(prev.y - candidate.y) <= 2 &&
                        abs(prev.x - candidate.x) <= 2 &&
                        prev.text.equals(candidate.text, ignoreCase = false)
                }
                if (!duplicate) out += candidate
            }
            return out
        }

        /**
         * Collapses faux-bold double-rendered words such as `"CCRREEDDIITT"` -> `"CREDIT"`
         * or `"11,,224499..5500"` -> `"1,249.50"` without altering normal English double letters
         * like `"BILL"`, `"FEE"`, or `"COFFEE"`.
         */
        fun cleanFauxBoldText(input: String): String {
            val normalizedRupee = input
                .replace(Regex("[\uE000-\uF8FF](?=\\s*\\d)"), "₹")
                .replace(Regex("(^|\\s)`(?=\\s*\\d)"), "$1₹")
            if (normalizedRupee.length < 4) return normalizedRupee.trim()
            return DOUBLED_WORD_RX.replace(normalizedRupee) { match ->
                val token = match.value
                if (isEveryCharDoubled(token)) {
                    buildString(token.length / 2) {
                        var i = 0
                        while (i < token.length) {
                            append(token[i])
                            i += 2
                        }
                    }
                } else {
                    token
                }
            }.replace(Regex("[ \t]+"), " ").trim()
        }

        private fun isEveryCharDoubled(token: String): Boolean {
            if (token.length < 4 || token.length % 2 != 0) return false
            var i = 0
            while (i < token.length) {
                if (token[i] != token[i + 1]) return false
                i += 2
            }
            // Guard against legitimate symmetric numeric strings like "2002" (already fails pair check)
            // or "1100": require at least 3 distinct character transitions or alphabetic letters.
            val hasLetters = token.any { it.isLetter() }
            if (hasLetters) return true
            val collapsed = buildString {
                var j = 0
                while (j < token.length) {
                    append(token[j])
                    j += 2
                }
            }
            return collapsed.contains('.') || collapsed.contains(',') || collapsed.length >= 4
        }
    }
}
