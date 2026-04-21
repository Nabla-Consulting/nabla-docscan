package com.nabla.docscan.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Use case for OCR (Optical Character Recognition) using ML Kit Text Recognition.
 *
 * Best-effort: returns empty string if recognition fails.
 * Recognizes Latin script (English and most European languages).
 */
class OcrUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OcrUseCase"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extract text from an image URI.
     *
     * @param imageUri URI of the image to process
     * @return Extracted text, or empty string if OCR failed/unavailable
     */
    suspend fun recognizeText(imageUri: Uri): String =
        suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, imageUri)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val text = visionText.text
                        Log.d(TAG, "OCR extracted ${text.length} chars")
                        cont.resume(text)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "OCR failed (best-effort, continuing)", e)
                        cont.resume("") // Best-effort: return empty on failure
                    }
            } catch (e: Exception) {
                Log.w(TAG, "OCR setup failed", e)
                cont.resume("")
            }
        }

    /**
     * Recognize text from an InputImage directly (preferred — avoids context re-loading).
     */
    suspend fun recognizeText(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed (best-effort)", e)
                    cont.resume("")
                }
        }

    fun close() {
        recognizer.close()
    }
}
