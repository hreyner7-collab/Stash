package com.stash.core.data.onedrive

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Minimal Microsoft Graph client for the "Sync to OneDrive" feature.
 *
 * **Auth: OAuth2 device-code flow** (consumer endpoint). No WebView, no
 * redirect URIs: [startDeviceLogin] returns a short code the user types
 * at https://microsoft.com/devicelogin in any browser, and
 * [pollForToken] waits for them to finish. Access tokens (~1 h) live in
 * memory; the rotating refresh token persists via [OneDriveAuthStore].
 *
 * **Files live under** `/Stash/Music/` in the user's OneDrive, named
 * `{trackId}.{ext}` — deterministic names make playback lookups a single
 * exact-path Graph call, no manifest required.
 *
 * **Uploads** use Graph upload sessions (chunked, resumable-by-retry) —
 * the simple PUT endpoint caps at 4 MB, smaller than most songs.
 *
 * **Streaming** uses the item's `@microsoft.graph.downloadUrl`: a
 * pre-authenticated, range-request-capable URL valid for ~1 hour —
 * exactly the shape the player's streaming stack already handles
 * (expiring CDN URLs with a refresh seam).
 */
@Singleton
class OneDriveClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authStore: OneDriveAuthStore,
) {
    data class DriveFile(val name: String, val sizeBytes: Long)

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var accessTokenExpiresAtMs: Long = 0

    private val tokenMutex = Mutex()

    // ── In-app browser sign-in (authorization-code flow) ───────────────
    // The connect screen loads [authorizationUrl] in a WebView (same UX
    // as the app's YouTube/antra connections); Microsoft redirects to the
    // registered native-client URL with `?code=...`, the WebView
    // intercepts it, and [exchangeAuthCode] turns the code into tokens.

    /** The Microsoft login page URL for the in-app WebView. */
    fun authorizationUrl(): String =
        "$AUTH_BASE/authorize" +
            "?client_id=$CLIENT_ID" +
            "&response_type=code" +
            "&redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
            "&scope=${java.net.URLEncoder.encode(SCOPES, "UTF-8")}" +
            "&prompt=select_account"

    /** True when [url] is the post-login redirect carrying the auth code. */
    fun isRedirect(url: String): Boolean = url.startsWith(REDIRECT_URI)

    /** Pulls `code` out of the redirect URL, or null on error/denial. */
    fun extractCode(url: String): String? =
        android.net.Uri.parse(url).getQueryParameter("code")

    /** Exchanges the auth code for tokens and persists the connection.
     * Returns the connected account label, or null on failure. */
    suspend fun exchangeAuthCode(code: String): String? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPES)
            .build()
        runCatching {
            val json = execute(Request.Builder().url("$AUTH_BASE/token").post(body).build())
            storeTokens(json)
            val account = fetchAccountLabel()
            authStore.saveConnection(
                refreshToken = json.getString("refresh_token"),
                accountName = account,
            )
            account ?: "Connected"
        }.onFailure { Log.w(TAG, "code exchange failed: ${it.message}") }.getOrNull()
    }

    // ── Token lifecycle ────────────────────────────────────────────────

    private suspend fun validAccessToken(): String? = tokenMutex.withLock {
        accessToken?.takeIf { System.currentTimeMillis() < accessTokenExpiresAtMs - 60_000 }
            ?: refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val refresh = authStore.refreshToken.first() ?: return@withContext null
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refresh)
            .add("scope", SCOPES)
            .build()
        runCatching {
            val json = execute(Request.Builder().url("$AUTH_BASE/token").post(body).build())
            storeTokens(json)
            // Microsoft rotates the refresh token on every use.
            json.optString("refresh_token").takeIf { it.isNotBlank() }
                ?.let { authStore.updateRefreshToken(it) }
            accessToken
        }.onFailure {
            Log.w(TAG, "token refresh failed: ${it.message}")
        }.getOrNull()
    }

    private fun storeTokens(json: JSONObject) {
        accessToken = json.getString("access_token")
        accessTokenExpiresAtMs =
            System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
    }

    private suspend fun fetchAccountLabel(): String? = runCatching {
        val json = graphGet("/me?\$select=userPrincipalName,displayName") ?: return@runCatching null
        json.optString("userPrincipalName").takeIf { it.isNotBlank() }
            ?: json.optString("displayName").takeIf { it.isNotBlank() }
    }.getOrNull()

    // ── Drive operations ───────────────────────────────────────────────

    /** Uploads [file] as `/Stash/Music/{remoteName}` via an upload session.
     * [onProgress] receives 0..1. Returns true on success. */
    suspend fun uploadFile(
        file: File,
        remoteName: String,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val token = validAccessToken() ?: return@withContext false
        val sessionJson = runCatching {
            execute(
                Request.Builder()
                    .url("$GRAPH/me/drive/root:$REMOTE_DIR/$remoteName:/createUploadSession")
                    .header("Authorization", "Bearer $token")
                    .post(
                        """{"item":{"@microsoft.graph.conflictBehavior":"replace"}}"""
                            .toRequestBody("application/json".toMediaType()),
                    )
                    .build(),
            )
        }.getOrNull() ?: return@withContext false
        val uploadUrl = sessionJson.optString("uploadUrl").ifBlank { return@withContext false }

        val total = file.length()
        var sent = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (sent < total) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk: RequestBody = buffer.copyOf(read).toRequestBody(null)
                val rangeEnd = sent + read - 1
                val response = okHttpClient.newCall(
                    Request.Builder()
                        .url(uploadUrl)
                        .header("Content-Range", "bytes $sent-$rangeEnd/$total")
                        .put(chunk)
                        .build(),
                ).execute()
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(TAG, "chunk upload failed (${it.code}) for $remoteName")
                        return@withContext false
                    }
                }
                sent += read
                onProgress(sent.toFloat() / total)
            }
        }
        sent >= total
    }

    /** Pre-authenticated, ~1 h, range-capable streaming URL for a synced
     * track file, or null when not present/connected. */
    suspend fun streamingUrlFor(remoteName: String): String? = withContext(Dispatchers.IO) {
        val json = graphGet(
            "/me/drive/root:$REMOTE_DIR/$remoteName?\$select=size,@microsoft.graph.downloadUrl",
        ) ?: return@withContext null
        json.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotBlank() }
    }

    /**
     * Download a small text file (e.g. the library manifest) from the sync
     * folder and return its contents, or null if absent / on error. Uses the
     * same pre-authed download URL as [streamingUrlFor]; intended for tiny
     * files (manifests, .lrc) — not for streaming audio.
     */
    suspend fun downloadText(remoteName: String): String? = withContext(Dispatchers.IO) {
        val url = streamingUrlFor(remoteName) ?: return@withContext null
        runCatching {
            okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** All files currently in the sync folder (paginated). */
    suspend fun listSyncedFiles(): List<DriveFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DriveFile>()
        var url: String? = "$GRAPH/me/drive/root:$REMOTE_DIR:/children?\$select=name,size&\$top=200"
        while (url != null) {
            val json = graphGetAbsolute(url) ?: break
            val items = json.optJSONArray("value") ?: break
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                out += DriveFile(item.getString("name"), item.optLong("size"))
            }
            url = json.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        out
    }

    private suspend fun graphGet(path: String): JSONObject? = graphGetAbsolute("$GRAPH$path")

    private suspend fun graphGetAbsolute(url: String): JSONObject? {
        val token = validAccessToken() ?: return null
        return runCatching {
            execute(
                Request.Builder().url(url).header("Authorization", "Bearer $token").get().build(),
            )
        }.getOrNull()
    }

    private fun execute(request: Request): JSONObject {
        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "HTTP ${response.code}: ${text.take(200)}" }
            return JSONObject(text)
        }
    }

    private companion object {
        const val TAG = "OneDriveClient"

        /** Public client id registered for the authorization-code flow
         * with the native-client redirect — the same registration the
         * abraunegg/onedrive open-source client has used for years, so a
         * personal build works out of the box. Swap for your own Azure
         * app registration id if you prefer. */
        const val CLIENT_ID = "d50ca740-c83f-4d1b-b616-12c519384f0c"

        /** Microsoft's standard loopback for native/public clients — the
         * WebView intercepts navigation here; nothing is ever served. */
        const val REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"

        const val AUTH_BASE = "https://login.microsoftonline.com/common/oauth2/v2.0"
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        const val SCOPES = "Files.ReadWrite offline_access User.Read"
        const val REMOTE_DIR = "/Stash/Music"

        /** Upload chunk size — must be a multiple of 320 KiB per Graph
         * upload-session rules; 10 MiB keeps round-trips low. */
        const val CHUNK_BYTES = 10 * 1024 * 1024 - (10 * 1024 * 1024 % (320 * 1024))
    }
}
