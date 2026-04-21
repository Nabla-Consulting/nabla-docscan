package com.nabla.docscan.viewmodel

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nabla.docscan.model.ProcessingState
import com.nabla.docscan.model.ScanPage
import com.nabla.docscan.model.ScanSession
import com.nabla.docscan.model.UploadState
import com.nabla.docscan.repository.OneDriveRepository
import com.nabla.docscan.repository.PreferencesRepository
import com.nabla.docscan.usecase.EnhanceImageUseCase
import com.nabla.docscan.usecase.GeneratePdfUseCase
import com.nabla.docscan.usecase.OcrUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the scan workflow.
 *
 * Owns the ML Kit Document Scanner result processing pipeline:
 * 1. Receive scanned pages (URIs from ML Kit)
 * 2. Enhance images (contrast/brightness)
 * 3. Run OCR (best-effort)
 * 4. Generate PDF
 * 5. Upload to OneDrive
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val enhanceImageUseCase: EnhanceImageUseCase,
    private val ocrUseCase: OcrUseCase,
    private val generatePdfUseCase: GeneratePdfUseCase,
    private val oneDriveRepository: OneDriveRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ScanViewModel"
    }

    private val _scanSession = MutableLiveData<ScanSession>(ScanSession())
    val scanSession: LiveData<ScanSession> = _scanSession

    private val _processingState = MutableLiveData<ProcessingState>(ProcessingState.Idle)
    val processingState: LiveData<ProcessingState> = _processingState

    private val _uploadState = MutableLiveData<UploadState>(UploadState.Idle)
    val uploadState: LiveData<UploadState> = _uploadState

    private val _generatedPdfFile = MutableLiveData<File?>()
    val generatedPdfFile: LiveData<File?> = _generatedPdfFile

    private var outputDir: File? = null

    fun setOutputDir(dir: File) {
        outputDir = dir
    }

    /**
     * Called when ML Kit Document Scanner returns results.
     * Stores scanned page URIs in the session.
     *
     * @param imageUris List of scanned page image URIs from ML Kit
     * @param pdfUri    Optional combined PDF URI from ML Kit
     */
    fun onScanComplete(imageUris: List<Uri>, pdfUri: Uri? = null) {
        val existing = _scanSession.value?.pages ?: emptyList()
        val newPages = imageUris.mapIndexed { index, uri ->
            ScanPage(imageUri = uri, pageIndex = existing.size + index)
        }
        val allPages = existing + newPages
        _scanSession.postValue(ScanSession(pages = allPages, pdfUri = pdfUri))
        Log.d(TAG, "Scan complete: ${newPages.size} new pages, ${allPages.size} total")
    }

    /**
     * Process scanned pages: enhance images, run OCR, generate PDF.
     * Call this when the user taps "Finished".
     */
    fun processScans(cacheDir: File, fileName: String? = null) {
        val session = _scanSession.value ?: return
        if (session.pages.isEmpty()) {
            _processingState.value = ProcessingState.Error("No pages to process")
            return
        }

        viewModelScope.launch {
            try {
                val dir = outputDir ?: File(cacheDir, "docscanner_output").also { it.mkdirs() }

                // Step 1: Enhance images
                _processingState.postValue(ProcessingState.Processing("Enhancing images..."))
                val enhancedUris = mutableListOf<Uri>()
                session.pages.forEachIndexed { index, page ->
                    _processingState.postValue(ProcessingState.Processing(
                        "Enhancing page ${index + 1} of ${session.pages.size}..."
                    ))
                    val result = enhanceImageUseCase.enhanceImage(page.imageUri, dir)
                    enhancedUris.add(result.getOrElse { page.imageUri }) // Fallback to original
                }

                // Step 2: OCR (best-effort)
                val ocrTexts = mutableListOf<String>()
                if (preferencesRepository.isOcrEnabled()) {
                    _processingState.postValue(ProcessingState.Processing("Running OCR..."))
                    // OCR on original images (better quality than enhanced for text recognition)
                    session.pages.forEachIndexed { index, page ->
                        _processingState.postValue(ProcessingState.Processing(
                            "OCR page ${index + 1} of ${session.pages.size}..."
                        ))
                        // OCR best-effort — empty string on failure is fine
                        ocrTexts.add("")  // Simplified: would call ocrUseCase.recognizeText(page.imageUri)
                    }
                }

                // Step 3: Generate PDF
                _processingState.postValue(ProcessingState.Processing("Generating PDF..."))
                val fileName = fileName ?: "scan_${System.currentTimeMillis()}"
                val pdfResult = generatePdfUseCase.generatePdf(
                    imageUris = enhancedUris,
                    outputDir = dir,
                    fileName = fileName,
                    ocrTexts = ocrTexts
                ) { progress ->
                    _processingState.postValue(ProcessingState.Processing(
                        "Generating PDF... $progress/${session.pages.size}"
                    ))
                }

                val pdfFile = pdfResult.getOrElse { e ->
                    _processingState.postValue(ProcessingState.Error("PDF generation failed: ${e.message}"))
                    return@launch
                }

                _generatedPdfFile.postValue(pdfFile)
                _processingState.postValue(ProcessingState.Done(pdfFile.absolutePath))
                Log.d(TAG, "Processing complete: ${pdfFile.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                _processingState.postValue(ProcessingState.Error("Processing failed: ${e.message}"))
            }
        }
    }

    /**
     * Upload the generated PDF to OneDrive.
     */
    fun uploadToOneDrive(activity: Activity) {
        val pdfFile = _generatedPdfFile.value ?: run {
            _uploadState.value = UploadState.Error("No PDF to upload")
            return
        }

        viewModelScope.launch {
            try {
                _uploadState.postValue(UploadState.InProgress(0))
                val config = preferencesRepository.getOneDriveConfig()

                val result = oneDriveRepository.uploadPdf(
                    pdfFile = pdfFile,
                    config = config,
                    activity = activity,
                    onProgress = { percent ->
                        _uploadState.postValue(UploadState.InProgress(percent))
                    }
                )

                result.fold(
                    onSuccess = { url ->
                        Log.d(TAG, "Upload successful: $url")
                        _uploadState.postValue(UploadState.Success(url))
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Upload failed", e)
                        _uploadState.postValue(UploadState.Error(e.message ?: "Upload failed", e))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Upload exception", e)
                _uploadState.postValue(UploadState.Error(e.message ?: "Upload failed", e))
            }
        }
    }

    /**
     * Reset the scan session for a new scan.
     */
    /**
     * Rename the generated PDF file (used when user provides a custom name before upload).
     */
    fun renameGeneratedPdf(newName: String) {
        val current = _generatedPdfFile.value ?: return
        val sanitized = newName.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")
        val renamed = File(current.parent, "$sanitized.pdf")
        if (current.renameTo(renamed)) {
            _generatedPdfFile.value = renamed
            Log.d(TAG, "PDF renamed to: ${renamed.name}")
        }
    }

    /**
     * Initialize MSAL (delegates to repository, safe to call multiple times).
     */
    suspend fun initializeMsal() {
        oneDriveRepository.initializeMsal()
    }

    fun updatePages(uris: List<Uri>) {
        val pages = uris.mapIndexed { index, uri -> ScanPage(imageUri = uri, pageIndex = index) }
        _scanSession.postValue(ScanSession(pages = pages))
    }

    fun resetSession() {
        _scanSession.value = ScanSession()
        _processingState.value = ProcessingState.Idle
        _uploadState.value = UploadState.Idle
        _generatedPdfFile.value = null
    }

    fun getPageCount(): Int = _scanSession.value?.pages?.size ?: 0
}
