package com.nabla.docscan.repository

import android.content.Context
import android.content.SharedPreferences
import com.nabla.docscan.model.OneDriveConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for app preferences using SharedPreferences.
 * Stores OneDrive folder configuration and app settings.
 * No secrets stored here — only folder IDs and display names.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "docscanner_prefs"
        private const val KEY_ONEDRIVE_FOLDER_ID = "onedrive_folder_id"
        private const val KEY_ONEDRIVE_FOLDER_PATH = "onedrive_folder_path"
        private const val KEY_AUTO_UPLOAD = "auto_upload"
        private const val KEY_PDF_QUALITY = "pdf_quality"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOneDriveConfig(): OneDriveConfig {
        return OneDriveConfig(
            folderId = prefs.getString(KEY_ONEDRIVE_FOLDER_ID, "root") ?: "root",
            folderPath = prefs.getString(KEY_ONEDRIVE_FOLDER_PATH, "OneDrive Root") ?: "OneDrive Root",
            autoUpload = prefs.getBoolean(KEY_AUTO_UPLOAD, true)
        )
    }

    fun saveOneDriveConfig(config: OneDriveConfig) {
        prefs.edit()
            .putString(KEY_ONEDRIVE_FOLDER_ID, config.folderId)
            .putString(KEY_ONEDRIVE_FOLDER_PATH, config.folderPath)
            .putBoolean(KEY_AUTO_UPLOAD, config.autoUpload)
            .apply()
    }

    fun isOcrEnabled(): Boolean = prefs.getBoolean(KEY_OCR_ENABLED, true)

    fun setOcrEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OCR_ENABLED, enabled).apply()
    }

    fun getPdfQuality(): Int = prefs.getInt(KEY_PDF_QUALITY, 85) // 0-100

    fun setPdfQuality(quality: Int) {
        prefs.edit().putInt(KEY_PDF_QUALITY, quality.coerceIn(10, 100)).apply()
    }
}
