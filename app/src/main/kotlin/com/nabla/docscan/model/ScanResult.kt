package com.nabla.docscan.model

import android.net.Uri

/**
 * Represents a single scanned page result from ML Kit Document Scanner.
 *
 * @param imageUri URI of the scanned page image (JPEG/PNG)
 * @param pdfUri   Optional URI if ML Kit returned a PDF for this page
 * @param pageIndex 0-based index of this page in the scanning session
 * @param ocrText  Optional OCR text extracted from this page
 */
data class ScanPage(
    val imageUri: Uri,
    val pdfUri: Uri? = null,
    val pageIndex: Int = 0,
    val ocrText: String? = null
)

/**
 * Represents the full result of a multi-page scan session.
 */
data class ScanSession(
    val pages: List<ScanPage> = emptyList(),
    val pdfUri: Uri? = null // Combined PDF if available from ML Kit
)

/**
 * Sealed class representing upload states.
 */
sealed class UploadState {
    object Idle : UploadState()
    data class InProgress(val progressPercent: Int) : UploadState()
    data class Success(val oneDriveUrl: String) : UploadState()
    data class Error(val message: String, val cause: Throwable? = null) : UploadState()
}

/**
 * Sealed class representing processing states.
 */
sealed class ProcessingState {
    object Idle : ProcessingState()
    object Scanning : ProcessingState()
    data class Processing(val step: String) : ProcessingState()
    data class Done(val pdfPath: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}
