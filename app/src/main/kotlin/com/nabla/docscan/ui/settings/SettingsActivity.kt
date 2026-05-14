package com.nabla.docscan.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nabla.docscan.BuildConfig
import com.nabla.docscan.R
import com.nabla.docscan.databinding.ActivitySettingsBinding
import com.nabla.docscan.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Settings screen for:
 * - Microsoft account sign-in / sign-out
 * - OneDrive folder selection
 * - Auto-upload toggle
 * - OCR toggle
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_settings)

        viewModel.initMsal()
        setupObservers()
        setupClickListeners()
        loadCurrentSettings()
    }

    private fun setupObservers() {
        viewModel.account.observe(this) { account ->
            if (account != null) {
                binding.tvSignInStatus.text = getString(R.string.label_signed_in, account.username)
                binding.btnSignIn.visibility = View.GONE
                binding.btnSignOut.visibility = View.VISIBLE
                binding.btnSelectFolder.isEnabled = true
            } else {
                binding.tvSignInStatus.text = getString(R.string.label_not_signed_in)
                binding.btnSignIn.visibility = View.VISIBLE
                binding.btnSignOut.visibility = View.GONE
                binding.btnSelectFolder.isEnabled = false
            }
        }

        viewModel.config.observe(this) { config ->
            binding.tvSelectedFolder.text = config.folderPath
            binding.switchAutoUpload.isChecked = config.autoUpload
        }

        viewModel.folderList.observe(this) { folders ->
            // Always show dialog on update (handles both initial load and subfolder navigation)
            showFolderPickerDialog(folders)
        }

        viewModel.errorMessage.observe(this) { message ->
            if (!message.isNullOrBlank()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun setupClickListeners() {
        binding.btnSignIn.setOnClickListener {
            viewModel.signIn(this)
        }

        binding.btnSignOut.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_sign_out_title)
                .setMessage(R.string.dialog_sign_out_message)
                .setPositiveButton(R.string.action_sign_out) { _, _ -> viewModel.signOut() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnSelectFolder.setOnClickListener {
            viewModel.loadFolders(this)
        }

        binding.switchAutoUpload.setOnCheckedChangeListener { _, checked ->
            viewModel.setAutoUpload(checked)
        }

        binding.switchOcr.setOnCheckedChangeListener { _, checked ->
            viewModel.setOcrEnabled(checked)
        }
    }

    private fun loadCurrentSettings() {
        binding.switchOcr.isChecked = viewModel.isOcrEnabled()
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    }

    private fun showFolderPickerDialog(folders: List<Pair<String, String>>) {
        // Build item list: optionally prepend ".." if inside a subfolder
        val items = mutableListOf<String>()
        if (!viewModel.isAtRoot()) items.add("↑ ..")
        items.addAll(folders.map { "📁  ${it.first}" })  // first = name

        AlertDialog.Builder(this)
            .setTitle(viewModel.currentFolderPath.value ?: "OneDrive")
            .setItems(items.toTypedArray()) { dialogRef, which ->
                dialogRef.dismiss()
                val adjustedIndex = which - (if (!viewModel.isAtRoot()) 1 else 0)
                if (!viewModel.isAtRoot() && which == 0) {
                    // Navigate up
                    viewModel.navigateUp(this)
                } else {
                    val selected = folders[adjustedIndex]
                    // Navigate into subfolder — observer shows new dialog when folderList updates
                    // selected.first = name, selected.second = id
                    viewModel.navigateIntoFolder(selected.second, selected.first, this)
                }
            }
            .setNeutralButton("✓ Select this folder") { _, _ ->
                viewModel.selectCurrentFolder()
                Toast.makeText(this, "Folder saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.resetFolderNav()
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
