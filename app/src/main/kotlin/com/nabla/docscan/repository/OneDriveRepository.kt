package com.nabla.docscan.repository

import android.content.Context
import android.util.Log
import com.nabla.docscan.BuildConfig
import com.nabla.docscan.model.OneDriveConfig
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.nabla.docscan.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Repository for Microsoft OneDrive operations via Microsoft Graph API.
 *
 * Authentication: MSAL (Microsoft Authentication Library)
 * Scopes required: Files.ReadWrite, User.Read
 *
 * Setup: Register an Azure AD app at https://portal.azure.com
 * Add the client ID to local.properties as: msal.clientId=YOUR_CLIENT_ID
 */
@Singleton
class OneDriveRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OneDriveRepository"
        private const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        private val SCOPES = arrayOf("Files.ReadWrite", "User.Read")
    }

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private var msalInitialized = false
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Initialize MSAL. Call once before any auth operations.
     * Reads client ID from BuildConfig (sourced from local.properties).
     */
    suspend fun initializeMsal(): Result<Unit> {
        if (msalInitialized) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                R.raw.msal_config, // msal_config.json in res/raw/
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        msalApp = application
                        msalInitialized = true
                        cont.resume(Result.success(Unit))
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(TAG, "MSAL init failed", exception)
                        cont.resume(Result.failure(exception))
                    }
                }
            )
        }
    }
    }

    /**
     * Sign in interactively (shows Microsoft login UI).
     * Must be called from an Activity context.
     */
    suspend fun signIn(activity: android.app.Activity): Result<IAccount> =
        suspendCancellableCoroutine { cont ->
            val app = msalApp ?: run {
                cont.resume(Result.failure(IllegalStateException("MSAL not initialized")))
                return@suspendCancellableCoroutine
            }
            app.signIn(
                activity,
                null,
                SCOPES,
                object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        cont.resume(Result.success(authenticationResult.account))
                    }

                    override fun onError(exception: MsalException) {
                        cont.resume(Result.failure(exception))
                    }

                    override fun onCancel() {
                        cont.resume(Result.failure(Exception("Sign-in cancelled by user")))
                    }
                }
            )
        }

    /**
     * Sign out the current account.
     */
    suspend fun signOut(): Result<Unit> = suspendCancellableCoroutine { cont ->
        msalApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                cont.resume(Result.success(Unit))
            }

            override fun onError(exception: MsalException) {
                cont.resume(Result.failure(exception))
            }
        }) ?: cont.resume(Result.failure(IllegalStateException("MSAL not initialized")))
    }

    /**
     * Get current signed-in account, if any.
     */
    suspend fun getCurrentAccount(): IAccount? = withContext(Dispatchers.IO) {
        try {
            msalApp?.getCurrentAccount()?.currentAccount
        } catch (e: Exception) {
            Log.w(TAG, "Could not get current account", e)
            null
        }
    }

    /**
     * Acquire access token silently (no UI). Falls back to interactive if needed.
     */
    private suspend fun acquireTokenSilent(activity: android.app.Activity): Result<String> {
        val app = msalApp ?: return Result.failure(IllegalStateException("MSAL not initialized"))
        val account = getCurrentAccount() ?: run {
            // No account — sign in interactively, then retry silent
            signIn(activity).getOrElse { e -> return Result.failure(e) }
            return acquireTokenSilent(activity)
        }

        val silentResult = suspendCancellableCoroutine { cont ->
            val params = AcquireTokenSilentParameters.Builder()
                .forAccount(account)
                .fromAuthority("https://login.microsoftonline.com/consumers")
                .withScopes(SCOPES.toList())
                .withCallback(object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        cont.resume(Result.success(authenticationResult.accessToken))
                    }

                    override fun onError(exception: MsalException) {
                        cont.resume(Result.failure(exception))
                    }
                })
                .build()
            app.acquireTokenSilentAsync(params)
        }

        // If silent auth fails due to missing UI interaction, fall back to interactive sign-in
        val silentError = silentResult.exceptionOrNull()
        if (silentError is MsalUiRequiredException) {
            Log.d(TAG, "Silent auth requires UI, falling back to interactive sign-in")
            return signIn(activity).map { it.toString() }.let {
                // After interactive sign-in, retry silent to get a fresh token
                acquireTokenSilent(activity)
            }
        }

        return silentResult
    }

    /**
     * Upload a PDF file to the configured OneDrive folder.
     *
     * Uses Microsoft Graph upload session for large files (>4MB).
     * Falls back to simple PUT for smaller files.
     *
     * @param pdfFile     Local PDF file to upload
     * @param config      OneDrive folder configuration
     * @param activity    Activity for interactive auth if needed
     * @param onProgress  Progress callback (0-100)
     * @return Result with the OneDrive web URL of the uploaded file
     */
    suspend fun uploadPdf(
        pdfFile: File,
        config: OneDriveConfig,
        activity: android.app.Activity,
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Get access token
            val accessToken = acquireTokenSilent(activity)
                .getOrElse { e -> return@withContext Result.failure(e) }

            val fileName = pdfFile.name
            val fileSize = pdfFile.length()

            if (fileSize < 4 * 1024 * 1024) {
                // Simple PUT for files < 4MB
                simplePutUpload(accessToken, pdfFile, config.folderId, fileName, onProgress)
            } else {
                // Upload session for large files
                uploadWithSession(accessToken, pdfFile, config.folderId, fileName, onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }

    private suspend fun simplePutUpload(
        token: String,
        file: File,
        folderId: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val uploadUrl = if (folderId == "root") {
            "$GRAPH_BASE_URL/me/drive/root:/$fileName:/content"
        } else {
            "$GRAPH_BASE_URL/me/drive/items/$folderId:/$fileName:/content"
        }

        val requestBody = file.asRequestBody("application/pdf".toMediaType())
        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/pdf")
            .put(requestBody)
            .build()

        onProgress(50)
        val response = httpClient.newCall(request).execute()
        onProgress(100)

        val body = response.body?.string() ?: "{}"
        Log.d(TAG, "Upload response: ${response.code} body: ${body.take(500)}")

        if (response.isSuccessful) {
            val json = JSONObject(body)
            val webUrl = json.optString("webUrl", "")
            if (webUrl.isNotEmpty()) {
                Result.success(webUrl)
            } else {
                // webUrl missing — construct a meaningful path from name + parentReference
                val name = json.optString("name", fileName)
                val parentPath = json.optJSONObject("parentReference")
                    ?.optString("path", "") ?: ""
                // parentPath is like "/drive/root:/Documents" — strip the prefix
                val cleanParent = parentPath.substringAfter("root:").trimStart('/')
                val displayPath = if (cleanParent.isNotEmpty()) "$cleanParent/$name" else name
                Log.w(TAG, "webUrl missing from response, constructed path: $displayPath")
                Result.success("Uploaded: $name to /$displayPath")
            }
        } else {
            Result.failure(IOException("Upload failed: ${response.code} ${response.message}"))
        }
    }

    private suspend fun uploadWithSession(
        token: String,
        file: File,
        folderId: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        // Create upload session
        val sessionUrl = if (folderId == "root") {
            "$GRAPH_BASE_URL/me/drive/root:/$fileName:/createUploadSession"
        } else {
            "$GRAPH_BASE_URL/me/drive/items/$folderId:/$fileName:/createUploadSession"
        }

        val sessionBody = """{"item": {"@microsoft.graph.conflictBehavior": "rename"}}"""
        val sessionRequest = Request.Builder()
            .url(sessionUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(sessionBody.toRequestBody("application/json".toMediaType()))
            .build()

        val sessionResponse = httpClient.newCall(sessionRequest).execute()
        if (!sessionResponse.isSuccessful) {
            return@withContext Result.failure(
                IOException("Failed to create upload session: ${sessionResponse.code}")
            )
        }

        val sessionJson = JSONObject(sessionResponse.body?.string() ?: "{}")
        val uploadUrl = sessionJson.getString("uploadUrl")

        // Upload in 5MB chunks
        val chunkSize = 5 * 1024 * 1024
        val fileSize = file.length()
        val bytes = file.readBytes()
        var offset = 0
        var lastWebUrl = ""

        while (offset < fileSize) {
            val end = minOf(offset + chunkSize - 1, fileSize.toInt() - 1)
            val chunkBytes = bytes.copyOfRange(offset, end + 1)

            val chunkRequest = Request.Builder()
                .url(uploadUrl)
                .addHeader("Content-Range", "bytes $offset-$end/$fileSize")
                .addHeader("Content-Length", chunkBytes.size.toString())
                .put(chunkBytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val chunkResponse = httpClient.newCall(chunkRequest).execute()
            val progress = ((end + 1).toFloat() / fileSize * 100).toInt()
            onProgress(progress)

            if (chunkResponse.code == 200 || chunkResponse.code == 201) {
                val responseJson = JSONObject(chunkResponse.body?.string() ?: "{}")
                lastWebUrl = responseJson.optString("webUrl", "")
            } else if (chunkResponse.code != 202) {
                return@withContext Result.failure(
                    IOException("Chunk upload failed at offset $offset: ${chunkResponse.code}")
                )
            }

            offset = end + 1
        }

        Result.success(lastWebUrl)
    }

    /**
     * List OneDrive folders (for folder picker in Settings).
     */
    suspend fun listFolders(
        activity: android.app.Activity,
        parentId: String = "root"
    ): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        try {
            val tokenResult = acquireTokenSilent(activity)
            val token = tokenResult.getOrElse { e -> return@withContext Result.failure(e) }

            val url = if (parentId == "root") {
                "$GRAPH_BASE_URL/me/drive/root/children?\$filter=folder ne null&\$select=id,name"
            } else {
                "$GRAPH_BASE_URL/me/drive/items/$parentId/children?\$filter=folder ne null&\$select=id,name"
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("List folders failed: ${response.code}"))
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val items = json.getJSONArray("value")
            val folders = mutableListOf<Pair<String, String>>() // id to name

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                folders.add(Pair(item.getString("id"), item.getString("name")))
            }

            Result.success(folders)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
