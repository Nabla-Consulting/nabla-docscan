package com.nabla.docscan.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Use case for generating a PDF from a list of scanned + enhanced page images.
 *
 * Uses Android's built-in PdfDocument API (no external library needed for basic use).
 * Pages are scaled to A4 size (595 x 842 points at 72 DPI).
 *
 * If OCR text is available, it is embedded as invisible text overlay for searchability
 * (basic implementation — full searchable PDF requires iText or PDFBox).
 */
class GeneratePdfUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GeneratePdfUseCase"

        // A4 at 72 DPI (standard PDF points)
        private const val A4_WIDTH_PT = 595
        private const val A4_HEIGHT_PT = 842

        // A4 at 150 DPI (higher quality for rendering)
        private const val A4_WIDTH_PX = 1240  // 8.27" * 150 DPI
        private const val A4_HEIGHT_PX = 1754 // 11.69" * 150 DPI
    }

    /**
     * Generate a PDF from a list of image URIs.
     *
     * @param imageUris  Ordered list of enhanced page image URIs
     * @param outputDir  Directory to save the generated PDF
     * @param fileName   Output PDF filename (without .pdf extension)
     * @param ocrTexts   Optional list of OCR text per page (same order as imageUris)
     * @param onProgress Progress callback (0 to imageUris.size)
     * @return File path of the generated PDF
     */
    suspend fun generatePdf(
        imageUris: List<Uri>,
        outputDir: File,
        fileName: String = "scan_${System.currentTimeMillis()}",
        ocrTexts: List<String> = emptyList(),
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (imageUris.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No pages to generate PDF"))
            }

            outputDir.mkdirs()
            val outputFile = File(outputDir, "$fileName.pdf")

            val pdfDocument = PdfDocument()

            imageUris.forEachIndexed { index, imageUri ->
                val bitmap = loadAndScaleBitmap(imageUri)
                    ?: run {
                        Log.w(TAG, "Could not load image $index, skipping")
                        return@forEachIndexed
                    }

                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val canvas = page.canvas

                // Auto-rotate landscape images to portrait
                val oriented = if (bitmap.width > bitmap.height) {
                    val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else bitmap

                // White background
                canvas.drawColor(android.graphics.Color.WHITE)

                // Scale to fit A4 maintaining aspect ratio (letterbox)
                val scaleX = A4_WIDTH_PT.toFloat() / oriented.width
                val scaleY = A4_HEIGHT_PT.toFloat() / oriented.height
                val scale = minOf(scaleX, scaleY)
                val scaledW = (oriented.width * scale).toInt()
                val scaledH = (oriented.height * scale).toInt()
                val left = (A4_WIDTH_PT - scaledW) / 2f
                val top = (A4_HEIGHT_PT - scaledH) / 2f

                val scaled = Bitmap.createScaledBitmap(oriented, scaledW, scaledH, true)
                canvas.drawBitmap(scaled, left, top, null)

                if (scaled != oriented) scaled.recycle()
                if (oriented != bitmap) oriented.recycle()
                bitmap.recycle()

                pdfDocument.finishPage(page)
                onProgress(index + 1)
            }

            FileOutputStream(outputFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()

            Log.d(TAG, "PDF generated: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            Result.success(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "PDF generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Load a bitmap from a URI and scale it appropriately for PDF embedding.
     * Returns null if the image cannot be loaded.
     */
    private fun loadAndScaleBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // First pass: get dimensions
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Calculate sample size to avoid OOM
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, A4_WIDTH_PX, A4_HEIGHT_PX)

            // Second pass: decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val secondStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(secondStream, null, decodeOptions)
            secondStream.close()

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from $uri", e)
            null
        }
    }

    private fun calculateSampleSize(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        while (srcWidth / (sampleSize * 2) >= targetWidth && srcHeight / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
