package com.nabla.docscan.viewmodel

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nabla.docscan.model.OneDriveConfig
import com.nabla.docscan.repository.OneDriveRepository
import com.nabla.docscan.repository.PreferencesRepository
import com.microsoft.identity.client.IAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Settings screen.
 * Manages OneDrive auth state and folder configuration.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val oneDriveRepository: OneDriveRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _account = MutableLiveData<IAccount?>()
    val account: LiveData<IAccount?> = _account

    private val _config = MutableLiveData<OneDriveConfig>()
    val config: LiveData<OneDriveConfig> = _config

    private val _folderList = MutableLiveData<List<Pair<String, String>>>()
    val folderList: LiveData<List<Pair<String, String>>> = _folderList

    // Navigation stack: List<Pair<folderId, folderName>>
    private val folderStack = mutableListOf<Pair<String, String>>()

    // Current path string for display (e.g. "OneDrive / Documents / Scans")
    private val _currentFolderPath = MutableLiveData("OneDrive")
    val currentFolderPath: LiveData<String> = _currentFolderPath

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        loadConfig()
    }

    fun loadConfig() {
        _config.value = preferencesRepository.getOneDriveConfig()
    }

    fun initMsal() {
        viewModelScope.launch {
            val result = oneDriveRepository.initializeMsal()
            result.onFailure { e ->
                Log.e(TAG, "MSAL init failed", e)
                _errorMessage.value = "MSAL initialization failed: ${e.message}"
            }
            // Check for existing signed-in account
            val account = oneDriveRepository.getCurrentAccount()
            _account.value = account
        }
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = oneDriveRepository.signIn(activity)
            result.fold(
                onSuccess = { account ->
                    _account.value = account
                    Log.d(TAG, "Signed in: ${account.username}")
                },
                onFailure = { e ->
                    _errorMessage.value = "Sign-in failed: ${e.message}"
                }
            )
            _isLoading.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            oneDriveRepository.signOut()
            _account.value = null
        }
    }

    fun loadFolders(activity: Activity, parentId: String = "root") {
        viewModelScope.launch {
            _isLoading.value = true
            val result = oneDriveRepository.listFolders(activity, parentId)
            result.fold(
                onSuccess = { folders ->
                    // Sort alphabetically by name (first in pair)
                    _folderList.value = folders.sortedBy { it.first.lowercase() }
                },
                onFailure = { e -> _errorMessage.value = "Could not load folders: ${e.message}" }
            )
            _isLoading.value = false
        }
    }

    fun navigateIntoFolder(folderId: String, folderName: String, activity: Activity) {
        folderStack.add(folderId to folderName)
        _currentFolderPath.value = "OneDrive / " + folderStack.joinToString(" / ") { it.second }
        loadFolders(activity, parentId = folderId)
    }

    fun navigateUp(activity: Activity) {
        if (folderStack.isNotEmpty()) folderStack.removeLast()
        _currentFolderPath.value = if (folderStack.isEmpty()) "OneDrive"
            else "OneDrive / " + folderStack.joinToString(" / ") { it.second }
        val parentId = folderStack.lastOrNull()?.first ?: "root"
        loadFolders(activity, parentId = parentId)
    }

    fun isAtRoot() = folderStack.isEmpty()

    fun selectCurrentFolder() {
        val folderId = folderStack.lastOrNull()?.first ?: "root"
        val folderPath = if (folderStack.isEmpty()) "/"
            else "/" + folderStack.joinToString("/") { it.second }
        selectFolder(folderId, folderPath)
        folderStack.clear()
        _currentFolderPath.value = "OneDrive"
    }

    fun resetFolderNav() {
        folderStack.clear()
        _currentFolderPath.value = "OneDrive"
    }

    fun selectFolder(folderId: String, folderPath: String) {
        val current = _config.value ?: OneDriveConfig()
        val updated = current.copy(folderId = folderId, folderPath = folderPath)
        preferencesRepository.saveOneDriveConfig(updated)
        _config.value = updated
    }

    fun setAutoUpload(enabled: Boolean) {
        val current = _config.value ?: OneDriveConfig()
        val updated = current.copy(autoUpload = enabled)
        preferencesRepository.saveOneDriveConfig(updated)
        _config.value = updated
    }

    fun setOcrEnabled(enabled: Boolean) {
        preferencesRepository.setOcrEnabled(enabled)
    }

    fun isOcrEnabled() = preferencesRepository.isOcrEnabled()

    fun clearError() {
        _errorMessage.value = null
    }
}
