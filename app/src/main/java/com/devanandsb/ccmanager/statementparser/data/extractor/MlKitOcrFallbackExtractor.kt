package com.devanandsb.ccmanager.statementparser.data.extractor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.devanandsb.ccmanager.ingest.PageLayout
import com.devanandsb.ccmanager.ingest.TextSpan
import com.devanandsb.ccmanager.statementparser.data.cv.TinyCvTableDetector
import com.devanandsb.ccmanager.statementparser.domain.model.PageGeometry
import com.devanandsb.ccmanager.statementparser.domain.model.RegionBox
import com.devanandsb.ccmanager.statementparser.domain.model.TextBlock
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * 100% offline hybrid CV + ML Kit OCR fallback used automatically when PDFBox finds zero,
 * sparse, glyph-garbled, or image-only pages:
 * 1. Renders PDF pages via [PdfRenderer] on an opaque white canvas.
 * 2. Deskews tilted scans via [TinyCvTableDetector.deskewBitmapIfNeeded].
 * 3. Extracts CV summary boxes & table grids via [TinyCvTableDetector.detectFromBitmap].
 * 4. Runs Google ML Kit Text Recognition, splits lines at CV table/box boundaries, and
 *    re-OCRs sparse Page 1 summary boxes at 2x upscaled contrast-stretched resolution.
 */
class MlKitOcrFallbackExtractor(
    private val context: Context,
) {
    suspend fun extract(uri: Uri, password: String? = null): PdfExtractionOutput =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@withContext PdfExtractionOutput.Error("Unable to open this PDF file.")

            extractFromBytes(bytes, listOf(password.orEmpty()), hadExplicitAttempt = !password.isNullOrEmpty())
        }

    suspend fun extractFromBytes(
        bytes: ByteArray,
        passwordCandidates: List<String> = listOf(""),
        hadExplicitAttempt: Boolean = false,
    ): PdfExtractionOutput =
        withContext(Dispatchers.IO) {
            var tempPdfFile: File? = null
            var pfd: ParcelFileDescriptor? = null

            try {
                when (val prepared = prepareDecryptedTempFile(bytes, passwordCandidates)) {
                    is PrepResult.Locked -> {
                        return@withContext PdfExtractionOutput.PasswordRequired(incorrectAttempt = hadExplicitAttempt)
                    }
                    is PrepResult.Failed -> {
                        return@withContext PdfExtractionOutput.Error(prepared.reason)
                    }
                    is PrepResult.Ready -> {
                        tempPdfFile = prepared.file
                    }
                }

                pfd = ParcelFileDescriptor.open(tempPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                ensureOcrModuleReady(recognizer)

                val allBlocks = mutableListOf<TextBlock>()
                val pageGeometries = mutableListOf<PageGeometry>()
                val rawTextBuilder = StringBuilder()
                val pageCount: Int

                try {
                    renderer.use { pdfRenderer ->
                        pageCount = pdfRenderer.pageCount
                        val pagesToOcr = minOf(pageCount, MAX_OCR_PAGES)
                        for (pageIndex in 0 until pagesToOcr) {
                            val pageNo = pageIndex + 1
                            pdfRenderer.openPage(pageIndex).use { page ->
                                val maxDim = maxOf(page.width, page.height).coerceAtLeast(1)
                                val scale = (TARGET_LONG_EDGE_PX / maxDim.toFloat()).coerceIn(2.0f, 3.2f)
                                val targetWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
                                val targetHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
                                val rawBitmap = createBitmap(targetWidth, targetHeight)
                                var workingBitmap: Bitmap = rawBitmap
                                try {
                                    // Opaque white background ensures transparent bank PDFs render crisply for OCR.
                                    Canvas(rawBitmap).drawColor(Color.WHITE)
                                    page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                    workingBitmap = TinyCvTableDetector.deskewBitmapIfNeeded(rawBitmap)
                                    val geo = TinyCvTableDetector.detectFromBitmap(workingBitmap, pageNo, scale)
                                    pageGeometries += geo

                                    val image = InputImage.fromBitmap(workingBitmap, 0)
                                    val visionText = recognizer.process(image).await()
                                    val initialBlocks = extractBlocksFromVisionText(
                                        visionText = visionText,
                                        pageNo = pageNo,
                                        scale = scale,
                                        geometry = geo,
                                    )
                                    var taggedBlocks = TinyCvTableDetector.assignBlocksToBoxes(initialBlocks, geo.boxes)

                                    // Targeted 2x contrast-enhanced re-OCR on Page 1 summary boxes that dropped digits/dates.
                                    if (pageNo == 1 && geo.boxes.isNotEmpty()) {
                                        val recovered = reOcrSparseSummaryBoxes(
                                            recognizer = recognizer,
                                            bitmap = workingBitmap,
                                            scale = scale,
                                            boxes = geo.boxes,
                                            existingBlocks = taggedBlocks,
                                        )
                                        if (recovered.isNotEmpty()) {
                                            taggedBlocks = TinyCvTableDetector.assignBlocksToBoxes(
                                                PdfBoxCoordinateExtractor.deduplicateOverlappingBlocks(
                                                    taggedBlocks + recovered,
                                                ),
                                                geo.boxes,
                                            )
                                        }
                                    }

                                    allBlocks += taggedBlocks
                                    val pageRows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(
                                        taggedBlocks,
                                        yTolerance = OCR_Y_TOLERANCE,
                                    )
                                    for (row in pageRows) {
                                        rawTextBuilder.appendLine(row.joinToString(" ") { it.text })
                                    }
                                } finally {
                                    if (workingBitmap !== rawBitmap) workingBitmap.recycle()
                                    rawBitmap.recycle()
                                }
                            }
                        }
                    }
                } finally {
                    recognizer.close()
                }

                val deduped = PdfBoxCoordinateExtractor.deduplicateOverlappingBlocks(allBlocks)
                val rows = PdfBoxCoordinateExtractor.groupBlocksIntoRows(deduped, yTolerance = OCR_Y_TOLERANCE)
                PdfExtractionOutput.Blocks(
                    blocks = deduped,
                    rows = rows,
                    rawText = rawTextBuilder.toString(),
                    pageCount = pageCount,
                    pageGeometries = pageGeometries,
                )
            } catch (_: OutOfMemoryError) {
                PdfExtractionOutput.Error("Scanned PDF resolution exceeded available device memory.")
            } catch (_: Exception) {
                PdfExtractionOutput.Error("Couldn't read this scanned PDF.")
            } finally {
                runCatching { pfd?.close() }
                runCatching { tempPdfFile?.delete() }
            }
        }

    /**
     * Re-OCRs Page 1 CV-detected summary boxes at 2x upscaled contrast-stretched resolution
     * when full-page OCR captured a summary label inside a box without recovering any numeric/date value.
     */
    private suspend fun reOcrSparseSummaryBoxes(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        scale: Float,
        boxes: List<RegionBox>,
        existingBlocks: List<TextBlock>,
    ): List<TextBlock> {
        val byBox = existingBlocks.groupBy { it.boxId }
        val extra = mutableListOf<TextBlock>()
        var reOcrCount = 0

        for (box in boxes) {
            if (reOcrCount >= 8) break
            if (box.y > 460 || box.width < 50 || box.height < 20) continue
            val inside = byBox[box.id].orEmpty()
            if (inside.isEmpty()) continue
            val joined = inside.joinToString(" ") { it.text }.lowercase()
            val hasSummaryKeyword = SUMMARY_BOX_KEYWORDS.any { joined.contains(it) }
            if (!hasSummaryKeyword) continue
            val hasAmountOrDate = inside.any {
                Regex("\\d+(?:,\\d+)*\\.\\d{2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}").containsMatchIn(it.text)
            }
            if (hasAmountOrDate) continue

            val padPx = 6
            val cropX = ((box.x * scale).roundToInt() - padPx).coerceIn(0, bitmap.width - 2)
            val cropY = ((box.y * scale).roundToInt() - padPx).coerceIn(0, bitmap.height - 2)
            val cropW = ((box.width * scale).roundToInt() + padPx * 2).coerceIn(2, bitmap.width - cropX)
            val cropH = ((box.height * scale).roundToInt() + padPx * 2).coerceIn(2, bitmap.height - cropY)
            if (cropW < 40 || cropH < 20) continue

            val rawCrop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            val upscaled = Bitmap.createScaledBitmap(rawCrop, cropW * 2, cropH * 2, true)
            val enhanced = TinyCvTableDetector.enhanceCropContrast(upscaled)
            try {
                reOcrCount++
                val vision = recognizer.process(InputImage.fromBitmap(enhanced, 0)).await()
                val cropScale = scale * 2f
                for (tb in vision.textBlocks) {
                    for (line in tb.lines) {
                        val rect = line.boundingBox ?: continue
                        val text = normalizeOcrChunk(line.text)
                        if (text.isEmpty()) continue
                        extra += TextBlock(
                            text = text,
                            x = ((cropX * 2f + rect.left) / cropScale).roundToInt(),
                            y = ((cropY * 2f + rect.top) / cropScale).roundToInt(),
                            width = (rect.width() / cropScale).roundToInt().coerceAtLeast(1),
                            page = box.page,
                            boxId = box.id,
                        )
                    }
                }
            } finally {
                if (enhanced !== upscaled) enhanced.recycle()
                upscaled.recycle()
                rawCrop.recycle()
            }
        }
        return extra
    }

    private suspend fun ensureOcrModuleReady(recognizer: TextRecognizer) {
        withTimeoutOrNull(12.seconds) {
            runCatching {
                val client = ModuleInstall.getClient(context.applicationContext)
                val availability = client.areModulesAvailable(recognizer).await()
                if (!availability.areModulesAvailable()) {
                    val req = ModuleInstallRequest.newBuilder().addApi(recognizer).build()
                    client.installModules(req).await()
                }
            }
        }
    }

    private fun extractBlocksFromVisionText(
        visionText: Text,
        pageNo: Int,
        scale: Float,
        geometry: PageGeometry,
    ): List<TextBlock> {
        val out = mutableListOf<TextBlock>()
        // Collect vertical boundary X positions from CV table columns and summary boxes on this page
        val splitBoundaries = (
            geometry.tables.flatMap { t -> t.columnBounds.flatMap { listOf(it.first, it.last) } } +
                geometry.boxes.flatMap { b -> listOf(b.x, b.xEnd) }
            ).distinct().sorted()

        fun crossesBoundary(leftPdf: Int, rightPdf: Int): Boolean {
            if (rightPdf <= leftPdf) return false
            return splitBoundaries.any { bx -> bx in (leftPdf + 2)..(rightPdf - 2) }
        }

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val elements = line.elements
                if (elements.isEmpty()) {
                    val rect = line.boundingBox ?: continue
                    val clean = normalizeOcrChunk(line.text)
                    if (clean.isNotEmpty()) {
                        out += TextBlock(
                            text = clean,
                            x = (rect.left / scale).roundToInt(),
                            y = (rect.top / scale).roundToInt(),
                            width = (rect.width() / scale).roundToInt().coerceAtLeast(1),
                            page = pageNo,
                        )
                    }
                    continue
                }

                // Split a vision line into distinct column chunks when element gaps are wide OR cross a CV grid/box line.
                val sortedElements = elements.filter { it.boundingBox != null }
                    .sortedBy { it.boundingBox!!.left }
                val chunks = mutableListOf<MutableList<Text.Element>>()
                for (el in sortedElements) {
                    val rect = el.boundingBox ?: continue
                    val current = chunks.lastOrNull()
                    if (current == null) {
                        chunks += mutableListOf(el)
                    } else {
                        val prevRect = current.last().boundingBox!!
                        val gap = rect.left - prevRect.right
                        val avgCharWidth = (prevRect.width().toFloat() / current.last().text.length.coerceAtLeast(1))
                            .coerceAtLeast(5.5f)
                        val prevCenterPdf = ((prevRect.left + prevRect.right) / (2f * scale)).roundToInt()
                        val curCenterPdf = ((rect.left + rect.right) / (2f * scale)).roundToInt()
                        if (gap <= avgCharWidth * 2.0f && !crossesBoundary(prevCenterPdf, curCenterPdf)) {
                            current += el
                        } else {
                            chunks += mutableListOf(el)
                        }
                    }
                }

                for (chunk in chunks) {
                    val rects = chunk.mapNotNull { it.boundingBox }
                    if (rects.isEmpty()) continue
                    val left = rects.minOf { it.left }
                    val top = rects.minOf { it.top }
                    val right = rects.maxOf { it.right }
                    val rawChunkText = chunk.joinToString(" ") { it.text.trim() }
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    val chunkText = normalizeOcrChunk(rawChunkText)
                    if (chunkText.isNotEmpty()) {
                        out += TextBlock(
                            text = chunkText,
                            x = (left / scale).roundToInt(),
                            y = (top / scale).roundToInt(),
                            width = ((right - left) / scale).roundToInt().coerceAtLeast(1),
                            page = pageNo,
                        )
                    }
                }
            }
        }
        return out
    }

    private sealed interface PrepResult {
        data class Ready(val file: File) : PrepResult
        data object Locked : PrepResult
        data class Failed(val reason: String) : PrepResult
    }

    private fun prepareDecryptedTempFile(bytes: ByteArray, candidates: List<String>): PrepResult {
        PdfBoxInitializer.ensureInitialized(context)
        val pwList = (listOf("") + candidates).distinct()
        for (pw in pwList) {
            try {
                PDDocument.load(bytes, pw).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    val temp = File.createTempFile("cc_ocr_", ".pdf", context.cacheDir)
                    doc.save(temp)
                    return PrepResult.Ready(temp)
                }
            } catch (_: InvalidPasswordException) {
                continue
            } catch (_: Exception) {
                return PrepResult.Failed("Unable to open this scanned PDF.")
            }
        }
        return PrepResult.Locked
    }

    companion object {
        private const val TARGET_LONG_EDGE_PX = 2200f
        private const val OCR_Y_TOLERANCE = 7
        private const val MAX_OCR_PAGES = 16

        private val SUMMARY_BOX_KEYWORDS = listOf(
            "total", "minimum", "min.", "due", "limit", "opening", "previous", "statement", "bill",
        )

        /**
         * Determines whether PDFBox vector text should trigger ML Kit offline OCR fallback:
         * empty, sparse (< 120 alnum chars), broken CMap replacement/PUA glyph garbage, or hybrid
         * PDFs whose Page 1 has neither numbers nor dates.
         */
        fun needsOcrFallback(
            rawText: String,
            blockCount: Int,
            page1HasAmountsOrDates: Boolean = true,
        ): Boolean {
            if (blockCount == 0 || rawText.isBlank()) return true
            val alnum = rawText.count { it.isLetterOrDigit() }
            if (alnum < 120) return true
            val replacementCount = rawText.count { it == '\uFFFD' }
            if (replacementCount >= 5) return true
            if (!page1HasAmountsOrDates) return true
            val nonPrintableGarbage = rawText.count { ch ->
                ch.code >= 0xE000 && ch != '\uF8FF' || (ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t')
            }
            return nonPrintableGarbage > alnum / 4
        }

        /** Pre-warms the Play Services OCR optional module silently in the background. */
        fun prewarmModuleAsync(context: Context) {
            runCatching {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val client = ModuleInstall.getClient(context.applicationContext)
                client.areModulesAvailable(recognizer).addOnSuccessListener { res ->
                    if (!res.areModulesAvailable()) {
                        val req = ModuleInstallRequest.newBuilder().addApi(recognizer).build()
                        client.installModules(req)
                    }
                }.addOnCompleteListener {
                    runCatching { recognizer.close() }
                }
            }
        }

        fun toPageLayouts(output: PdfExtractionOutput.Blocks): List<PageLayout> {
            val byPage = output.blocks.groupBy { it.page }.toSortedMap()
            val geoByPage = output.pageGeometries.associateBy { it.page }
            return byPage.map { (pageNo, blocks) ->
                val geo = geoByPage[pageNo]
                val spans = blocks.map { b ->
                    TextSpan(
                        page = b.page,
                        x = b.x.toFloat(),
                        endX = b.xEnd.toFloat(),
                        y = b.y.toFloat(),
                        text = b.text,
                        boxId = b.boxId,
                    )
                }
                PageLayout(
                    page = pageNo,
                    width = 595f,
                    height = 842f,
                    spans = spans,
                    boxes = geo?.boxes.orEmpty(),
                    tables = geo?.tables.orEmpty(),
                )
            }
        }

        private val OCR_AMOUNT_O_RX = Regex("(?<=\\d)[Oo](?=[\\d,.])|(?<=[₹.,])[Oo](?=\\d)")
        private val OCR_DATE_O_RX = Regex("\\b([Oo0-3]\\d|[0-3][Oo])[/\\-]([Oo01]\\d|[01][Oo])[/\\-](2[Oo]\\d{2}|\\d{2})\\b")

        internal fun normalizeOcrChunk(raw: String): String {
            var s = raw.replace('\u00A0', ' ').replace(Regex("[ \t]+"), " ").trim()
            if (s.isEmpty()) return s
            // Fix '?' misrecognized for '₹' before digits (e.g., "?1,250.50" -> "₹1,250.50")
            s = s.replace(Regex("(^|\\s)\\?(?=\\s*\\d)"), "$1₹")
            // Collapse spaces between currency symbol and digits (e.g., "₹ 950.00" -> "₹950.00")
            s = s.replace(Regex("([₹$€£])\\s+(?=\\d)"), "$1")
            // Collapse spaces around decimal points (e.g., "950 . 00" -> "950.00")
            s = s.replace(Regex("(?<=\\d)\\s*\\.\\s*(?=\\d{2}\\b)"), ".")
            // Fix O -> 0 inside numeric amounts like "1,4O,000.00" or "2O/11/2026"
            s = OCR_AMOUNT_O_RX.replace(s, "0")
            s = OCR_DATE_O_RX.replace(s) { m -> m.value.replace('O', '0').replace('o', '0') }
            s = s.replace(Regex("(?i)\\bRs\\s*\\.\\s*"), "Rs. ")
            return s
        }
    }
}
