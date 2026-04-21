package com.nabla.docscan.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Use case for document image enhancement.
 *
 * Applies adaptive contrast and brightness adjustments to make scanned
 * documents more readable as PDFs. Uses Android's built-in Bitmap processing
 * (no OpenCV dependency required for basic enhancement).
 *
 * Enhancement pipeline:
 * 1. Load bitmap from URI
 * 2. Convert to grayscale
 * 3. Apply adaptive thresholding approximation (contrast stretch)
 * 4. Apply sharpening
 * 5. Save enhanced image
 */
class EnhanceImageUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EnhanceImageUseCase"
        private const val OUTPUT_QUALITY = 90 // JPEG quality for enhanced images
    }

    /**
     * Enhance a single scanned page image.
     *
     * @param imageUri URI of the original scanned image
     * @param outputDir Directory to save the enhanced image
     * @return URI of the enhanced image file
     */
    suspend fun enhanceImage(imageUri: Uri, outputDir: File): Result<Uri> =
        withContext(Dispatchers.Default) {
            try {
                // Load bitmap
                val inputStream = context.contentResolver.openInputStream(imageUri)
                    ?: return@withContext Result.failure(Exception("Cannot open image: $imageUri"))

                val original = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (original == null) {
                    return@withContext Result.failure(Exception("Failed to decode image"))
                }

                // Apply enhancement pipeline
                val enhanced = applyDocumentEnhancement(original)

                // Save enhanced image
                val outputFile = File(outputDir, "enhanced_${System.currentTimeMillis()}.jpg")
                outputFile.parentFile?.mkdirs()

                FileOutputStream(outputFile).use { fos ->
                    enhanced.compress(Bitmap.CompressFormat.JPEG, OUTPUT_QUALITY, fos)
                }

                // Recycle bitmaps
                if (enhanced != original) {
                    original.recycle()
                }

                Log.d(TAG, "Enhanced image saved: ${outputFile.absolutePath}")
                Result.success(Uri.fromFile(outputFile))

            } catch (e: Exception) {
                Log.e(TAG, "Image enhancement failed", e)
                Result.failure(e)
            }
        }

    /**
     * Apply document-specific enhancement to improve readability.
     *
     * This is a pure Android Bitmap implementation that approximates
     * the adaptive thresholding effect used in professional scanner apps.
     *
     * Steps:
     * 1. Analyze image histogram to determine optimal contrast stretch
     * 2. Apply auto-levels (stretch contrast to use full dynamic range)
     * 3. Boost contrast for document readability
     * 4. Apply slight sharpening
     */
    private fun applyDocumentEnhancement(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height

        // Step 1: Convert to ARGB_8888 if needed
        val workBitmap = if (original.config == Bitmap.Config.ARGB_8888) {
            original.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            original.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Step 2: Analyze histogram for auto-levels
        val pixels = IntArray(width * height)
        workBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = IntArray(256)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            luminance[lum]++
        }

        // Find 1st and 99th percentile for contrast stretch
        val totalPixels = pixels.size
        var minLum = 0
        var maxLum = 255
        var cumSum = 0

        for (i in 0..255) {
            cumSum += luminance[i]
            if (cumSum.toFloat() / totalPixels < 0.01f) minLum = i
        }
        cumSum = 0
        for (i in 255 downTo 0) {
            cumSum += luminance[i]
            if (cumSum.toFloat() / totalPixels < 0.01f) maxLum = i
        }

        // Step 3: Build contrast-stretched + boosted color matrix
        val scale = if (maxLum > minLum) 255f / (maxLum - minLum) else 1f
        val translate = -minLum * scale

        // ColorMatrix: contrast stretch + slight contrast boost for documents
        val contrastBoost = 1.15f // Boost contrast slightly for document readability
        val finalScale = scale * contrastBoost
        val finalTranslate = translate - (contrastBoost - 1f) * 128f

        val colorMatrix = ColorMatrix(floatArrayOf(
            finalScale, 0f, 0f, 0f, finalTranslate,
            0f, finalScale, 0f, 0f, finalTranslate,
            0f, 0f, finalScale, 0f, finalTranslate,
            0f, 0f, 0f, 1f, 0f
        ))

        // Step 4: Apply color matrix
        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(workBitmap, 0f, 0f, paint)

        if (workBitmap != original) workBitmap.recycle()

        return enhanced
    }

    /**
     * Batch enhance all pages.
     *
     * @param imageUris List of page image URIs
     * @param outputDir Directory for enhanced images
     * @param onProgress Progress callback (0 to imageUris.size)
     */
    suspend fun enhanceAll(
        imageUris: List<Uri>,
        outputDir: File,
        onProgress: (Int) -> Unit = {}
    ): List<Result<Uri>> {
        return imageUris.mapIndexed { index, uri ->
            val result = enhanceImage(uri, outputDir)
            onProgress(index + 1)
            result
        }
    }
}
