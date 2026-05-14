package com.nabla.docscan.ui.scan

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.nabla.docscan.R
import com.nabla.docscan.databinding.ActivityScanBinding
import com.nabla.docscan.model.ProcessingState
import com.nabla.docscan.model.UploadState
import com.nabla.docscan.ui.review.ReviewActivity
import com.nabla.docscan.ui.settings.SettingsActivity
import com.nabla.docscan.viewmodel.ScanViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main scan activity.
 *
 * Uses ML Kit Document Scanner API to scan multiple pages.
 * The scanner handles:
 * - Camera preview
 * - Document edge detection / cropping
 * - Multi-page capture (continuous scan, no extra taps per page)
 * - Image enhancement (built-in to ML Kit)
 *
 * After scanning, processes pages and optionally uploads to OneDrive.
 */
@AndroidEntryPoint
class ScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScanActivity"
    }

    private lateinit var binding: ActivityScanBinding
    private val viewModel: ScanViewModel by viewModels()

    private var documentScanner: GmsDocumentScanner? = null

    // Activity result launcher for ML Kit Document Scanner
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        handleScannerResult(result)
    }

    // Activity result launcher for ReviewActivity
    private val reviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            val action = result.data?.getStringExtra(ReviewActivity.EXTRA_ACTION)
            val uriStrings = result.data?.getStringArrayListExtra(ReviewActivity.EXTRA_IMAGE_URIS)
            if (!uriStrings.isNullOrEmpty()) {
                val uris = uriStrings.map { Uri.parse(it) }
                viewModel.updatePages(uris)
            }
            if (action == ReviewActivity.ACTION_ADD_PAGES) {
                startDocumentScanner()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.setOutputDir(cacheDir)

        setupScanner()
        setupObservers()
        setupClickListeners()

        // Initialize MSAL once at startup
        lifecycleScope.launch {
            viewModel.initializeMsal()
        }

        // Auto-start scanner on launch
        startDocumentScanner()
    }

    private fun setupScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(false)   // Camera-only workflow
            .setPageLimit(50)                  // Up to 50 pages per session
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF  // Get PDF directly from ML Kit
            )
            .build()

        documentScanner = GmsDocumentScanning.getClient(options)
        Log.d(TAG, "ML Kit Document Scanner initialized")
    }

    private fun setupObservers() {
        viewModel.processingState.observe(this) { state ->
            when (state) {
                is ProcessingState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.visibility = View.GONE
                }
                is ProcessingState.Scanning -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.status_scanning)
                    binding.tvStatus.visibility = View.VISIBLE
                }
                is ProcessingState.Processing -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = state.step
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.btnScan.isEnabled = false
                    binding.btnFinished.isEnabled = false
                }
                is ProcessingState.Done -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.status_done)
                    binding.btnScan.isEnabled = true

                    if (viewModel.uploadState.value !is UploadState.InProgress) {
                        // Auto-upload if configured
                        uploadIfEnabled()
                    }
                }
                is ProcessingState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = state.message
                    binding.btnScan.isEnabled = true
                    binding.btnFinished.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewModel.uploadState.observe(this) { state ->
            when (state) {
                is UploadState.Idle -> {
                    binding.tvUploadStatus.visibility = View.GONE
                }
                is UploadState.InProgress -> {
                    binding.tvUploadStatus.text = getString(R.string.status_uploading, state.progressPercent)
                    binding.tvUploadStatus.visibility = View.VISIBLE
                }
                is UploadState.Success -> {
                    binding.tvUploadStatus.text = getString(R.string.status_upload_done)
                    binding.tvUploadStatus.visibility = View.VISIBLE
                    val locationMsg = if (state.oneDriveUrl.isNotEmpty()) {
                        state.oneDriveUrl
                    } else {
                        getString(R.string.toast_upload_success)
                    }
                    Toast.makeText(this, locationMsg, Toast.LENGTH_LONG).show()
                    // Auto-reset after 2 seconds
                    binding.root.postDelayed({
                        viewModel.resetSession()
                        binding.etFileName.setText("")
                        binding.tilFileName.visibility = View.GONE
                    }, 2000)
                }
                is UploadState.Error -> {
                    binding.tvUploadStatus.text = getString(R.string.status_upload_failed, state.message)
                    binding.tvUploadStatus.visibility = View.VISIBLE
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewModel.scanSession.observe(this) { session ->
            val count = session.pages.size
            binding.tvPageCount.text = if (count > 0) {
                getString(R.string.label_page_count, count)
            } else {
                getString(R.string.label_no_pages)
            }
            binding.btnFinished.isEnabled = count > 0
            binding.btnReview.isEnabled = count > 0
            binding.btnStartOver.visibility = if (count > 0) View.VISIBLE else View.GONE
            binding.btnScan.text = if (count > 0) getString(R.string.btn_add_pages) else getString(R.string.btn_scan)
            if (count > 0 && binding.etFileName.text.isNullOrEmpty()) {
                val defaultName = "${java.time.LocalDate.now()}_scan"
                binding.etFileName.setText(defaultName)
                binding.tilFileName.visibility = View.VISIBLE
            }
            if (count > 0) {
                binding.tilFileName.visibility = View.VISIBLE
                if (binding.etFileName.text.isNullOrEmpty()) {
                    binding.etFileName.setText("scan_${java.time.LocalDate.now()}")
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnScan.setOnClickListener {
            startDocumentScanner()
        }

        binding.btnFinished.setOnClickListener {
            val defaultName = "scan_${java.time.LocalDate.now()}"
            val fileName = binding.etFileName.text?.toString()?.trim()?.ifEmpty { defaultName } ?: defaultName
            viewModel.processScans(cacheDir, fileName)
        }

        binding.btnReview.setOnClickListener {
            val pages = viewModel.scanSession.value?.pages ?: emptyList()
            val uriStrings = ArrayList(pages.map { it.imageUri.toString() })
            val intent = Intent(this, ReviewActivity::class.java).apply {
                putStringArrayListExtra(ReviewActivity.EXTRA_IMAGE_URIS, uriStrings)
            }
            reviewLauncher.launch(intent)
        }

        binding.btnStartOver.setOnClickListener {
            viewModel.resetSession()
            binding.etFileName.setText("")
            binding.tilFileName.visibility = View.GONE
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startDocumentScanner() {
        val scanner = documentScanner ?: run {
            Toast.makeText(this, R.string.error_scanner_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start scanner", e)
                Toast.makeText(this, getString(R.string.error_scanner_failed, e.message), Toast.LENGTH_LONG).show()
            }
    }

    private fun handleScannerResult(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)

            if (scanResult == null) {
                Log.w(TAG, "Scanner returned null result")
                return
            }

            val pageUris = scanResult.pages?.map { it.imageUri } ?: emptyList()
            val pdfUri = scanResult.pdf?.uri

            Log.d(TAG, "Scanner returned ${pageUris.size} pages, PDF: $pdfUri")

            if (pageUris.isNotEmpty() || pdfUri != null) {
                viewModel.onScanComplete(pageUris, pdfUri)
            } else {
                Toast.makeText(this, R.string.error_no_pages_scanned, Toast.LENGTH_SHORT).show()
            }
        } else if (result.resultCode == RESULT_CANCELED) {
            Log.d(TAG, "Scan cancelled by user")
        }
    }

    private fun uploadIfEnabled() {
        // PDF already named during processScans — just upload
        viewModel.uploadToOneDrive(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // documentScanner?.close() // GmsDocumentScanner does not implement Closeable
    }
}
