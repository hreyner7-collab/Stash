package com.stash.feature.settings.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stash.data.download.lossless.antra.AntraFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Hosts a [WebView] pointed at `antra.hoshi.cfd` so the user can log in and
 * pass Cloudflare's JS challenge in-app. antra gates its API behind a
 * `session` cookie (from login) plus a `cf_clearance` cookie (from the
 * Cloudflare challenge); both must be present before requests authenticate.
 *
 * A polling coroutine reads [CookieManager] until BOTH cookies appear, then
 * confirms the login is real by running an in-page `fetch('/api/auth/me')`
 * (which executes with the page's valid `cf_clearance` + matching TLS, so it
 * succeeds where a cold request would 403). When `/api/auth/me` returns a
 * username, it forwards `(session, cf_clearance, username)` to [onConnected]
 * and pops. Mirrors [SquidWtfCaptchaScreen].
 *
 * The WebView's User-Agent is pinned to [AntraFingerprint.USER_AGENT] — the
 * SAME UA the OkHttp interceptor replays — so the `cf_clearance` minted here
 * stays valid when Stash later replays the cookies (Approach A).
 */
@Composable
fun AntraConnectScreen(
    onConnected: (session: String, cfClearance: String, username: String) -> Unit,
    onClose: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // JS→Kotlin bridge so async fetch() results are actually delivered
    // (evaluateJavascript can't await a Promise).
    val jsBridge = remember { AntraJsBridge() }
    var statusText by remember {
        mutableStateOf(
            "To connect:\n" +
                "1. Log in to your antra account\n" +
                "2. Wait for the Cloudflare check to pass\n" +
                "Connection saves automatically once you're logged in.",
        )
    }
    var captured by remember { mutableStateOf(false) }

    // ── Cookie polling + login confirmation ─────────────────────────────
    LaunchedEffect(Unit) {
        var lastCookieSig = ""
        var diagged = false
        while (isActive && !captured) {
            delay(POLL_INTERVAL_MS)
            val cookies = CookieManager.getInstance().getCookie(ANTRA_URL).orEmpty()
            // DIAGNOSTIC: log the cookie NAMES + value lengths (not values)
            // whenever the cookie set changes, so we can see what antra
            // actually sets vs. the names we key off (`session`/`cf_clearance`).
            val sig = cookies.split(";").mapNotNull {
                val n = it.substringBefore("=").trim()
                if (n.isEmpty()) null else "$n(${it.substringAfter("=", "").trim().length})"
            }.sorted().joinToString(",")
            if (sig != lastCookieSig) {
                lastCookieSig = sig
                Log.i(TAG, "cookies for $ANTRA_URL => [$sig]")
            }
            val session = SESSION_REGEX.find(cookies)?.groupValues?.get(1)
            // cf_clearance is optional — only present while Cloudflare is
            // actively challenging. We capture it if it's there (for the
            // OkHttp replay) but never block on it.
            val cfClearance = CF_CLEARANCE_REGEX.find(cookies)?.groupValues?.get(1).orEmpty()
            if (session.isNullOrBlank()) {
                Log.d(TAG, "harvest gate: antra_session absent")
                continue
            }

            // Session cookie present — confirm the user is actually logged in
            // (the cookie can exist pre-login) by hitting /api/auth/me in the
            // page context.
            val wv = webViewRef ?: continue
            // Confirm the session is really logged in by hitting
            // /api/auth/me in the page context (carries the HttpOnly
            // antra_session cookie). Uses the JS bridge so the async fetch
            // result is actually awaited.
            val authMe = fetchAuthMe(wv, jsBridge)
            if (!diagged) {
                diagged = true
                Log.i(TAG, "AUTH PROBE storage => ${probeAuth(wv)}")
            }
            val username = parseUsername(authMe)
            Log.i(TAG, "antra_session present; /api/auth/me => ${authMe.take(200)} username=${username ?: "<none>"} (cf_clearance=${cfClearance.isNotEmpty()})")
            if (!username.isNullOrBlank()) {
                captured = true
                statusText = "Connected as $username — saving and closing."
                onConnected(session, cfClearance, username)
                delay(400)
                onClose()
            }
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connect antra",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (captured) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> buildWebView(context, jsBridge).also { webViewRef = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * JS→Kotlin bridge: an async fetch in the page calls [onResult] with the
 * response JSON when its Promise resolves. Needed because
 * `WebView.evaluateJavascript` returns immediately with the *Promise object*
 * (serializing to `{}`), never the awaited value. The bridge method is
 * invoked on a binder thread, so completing the (thread-safe)
 * [CompletableDeferred] hands the result back to the polling coroutine.
 */
private class AntraJsBridge {
    @Volatile var pending: CompletableDeferred<String>? = null

    @JavascriptInterface
    fun onResult(json: String) {
        pending?.complete(json)
    }
}

/**
 * `fetch('/api/auth/me', {credentials:'include'})` inside the page (carries
 * the HttpOnly `antra_session` cookie), awaited via [bridge]. Returns the
 * raw `{status, body}` JSON, or an `{err}`/timeout marker.
 */
private suspend fun fetchAuthMe(webView: WebView, bridge: AntraJsBridge): String {
    val deferred = CompletableDeferred<String>()
    bridge.pending = deferred
    val js =
        "(async()=>{let o;try{const r=await fetch('/api/auth/me',{credentials:'include'});" +
            "o={status:r.status,body:(await r.text()).slice(0,400)}}catch(e){o={err:String(e)}}" +
            "AntraBridge.onResult(JSON.stringify(o))})()"
    webView.evaluateJavascript(js, null)
    return withTimeoutOrNull(AUTH_TIMEOUT_MS) { deferred.await() } ?: """{"err":"timeout"}"""
}

/**
 * Extracts the username from the [fetchAuthMe] result: parses the outer
 * `{status, body}`, requires `status == 200`, then parses `body` for a
 * `username` (top-level or nested under `user`). Null if not logged in.
 */
private fun parseUsername(authMeJson: String): String? = runCatching {
    val outer = JSONObject(authMeJson)
    if (outer.optInt("status") != 200) return null
    val body = outer.optString("body").takeIf { it.isNotBlank() } ?: return null
    val obj = JSONObject(body)
    obj.optString("username").takeIf { it.isNotBlank() }
        ?: obj.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * DIAGNOSTIC: one-shot dump of where antra keeps its credential. Returns a
 * JSON blob with `/api/auth/me` status+body, JS-visible cookies, and
 * localStorage/sessionStorage keys (value prefixes only). Reveals whether
 * the API is cookie- or bearer-token-authenticated.
 */
private suspend fun probeAuth(webView: WebView): String =
    suspendCancellableCoroutine { cont ->
        // SYNCHRONOUS reads only — localStorage/sessionStorage/document.cookie
        // return immediately, so evaluateJavascript hands back the real value
        // (unlike an async fetch, whose Promise serializes to "{}"). This
        // reveals whether the login credential is a cookie or a stored token.
        val js =
            "JSON.stringify({" +
                "cookie:document.cookie," +
                "lsKeys:Object.keys(localStorage)," +
                "lsVals:Object.fromEntries(Object.keys(localStorage).map(k=>[k,(localStorage.getItem(k)||'').slice(0,70)]))," +
                "ssKeys:Object.keys(sessionStorage)" +
                "})"
        webView.evaluateJavascript(js) { result ->
            if (cont.isActive) cont.resume(decodeJsString(result).take(1200))
        }
    }

/** Decodes the JSON-string literal evaluateJavascript hands back. */
private fun decodeJsString(raw: String?): String {
    if (raw == null || raw == "null") return ""
    // raw is a quoted JSON string like "\"{\\\"username\\\":...}\"".
    return runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault("")
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
private fun buildWebView(context: android.content.Context, bridge: AntraJsBridge): WebView {
    CookieManager.getInstance().setAcceptCookie(true)

    return WebView(context).apply {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Bridge for awaiting in-page fetch() results. Scoped to this
        // first-party login WebView only.
        addJavascriptInterface(bridge, "AntraBridge")
        settings.apply {
            javaScriptEnabled = true     // Cloudflare challenge is JS
            domStorageEnabled = true     // antra SPA uses localStorage
            // Pin the UA to the shared fingerprint so the cf_clearance this
            // WebView mints matches what the OkHttp interceptor replays.
            userAgentString = AntraFingerprint.USER_AGENT
            mediaPlaybackRequiresUserGesture = true
        }
        Log.i(TAG, "WebView UA=${settings.userAgentString}")

        // DIAGNOSTIC: surface page-load lifecycle + HTTP/net errors so a
        // blank/white render is explainable from logcat (Cloudflare block?
        // net error? SPA failure?).
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                Log.i(TAG, "onPageStarted url=$url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "onPageFinished url=$url title='${view?.title}'")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "onReceivedError main-frame url=${request.url} code=${error?.errorCode} desc=${error?.description}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "onReceivedHttpError main-frame url=${request.url} status=${errorResponse?.statusCode} (${errorResponse?.reasonPhrase})")
                }
            }
        }

        // DIAGNOSTIC: JS console → reveals SPA errors and Cloudflare
        // challenge messages that would otherwise be invisible.
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                Log.i(TAG, "console[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
        }

        // A login WebView must never download files. Swallow any download
        // navigation (this is what white-screened after "download track"),
        // and log it so we can see if antra routes through a blob/file URL.
        setDownloadListener { url, _, _, mime, _ ->
            Log.w(TAG, "DownloadListener swallowed download url=${url.take(120)} mime=$mime")
        }

        loadUrl(ANTRA_URL)
    }
}

private const val TAG = "AntraConnect"
private const val ANTRA_URL = "https://antra.hoshi.cfd/"
private const val POLL_INTERVAL_MS = 800L
private const val AUTH_TIMEOUT_MS = 6_000L
// antra's login cookie is `antra_session` (verified on-device 2026-06-09).
// The boundary `(?:^|;\s*)` ensures we don't match a substring of some other
// cookie name.
private val SESSION_REGEX = Regex("(?:^|;\\s*)antra_session=([^;\\s]+)")
private val CF_CLEARANCE_REGEX = Regex("(?:^|;\\s*)cf_clearance=([^;\\s]+)")
