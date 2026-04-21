package com.nabla.docscan.model

/**
 * OneDrive upload configuration stored in SharedPreferences.
 * No secrets are stored here — only folder IDs and display names.
 *
 * @param folderId  OneDrive item ID or "root" for the root folder
 * @param folderPath Display path shown in the UI (e.g. "Documents/Scans")
 */
data class OneDriveConfig(
    val folderId: String = "root",
    val folderPath: String = "OneDrive Root",
    val autoUpload: Boolean = true
)
