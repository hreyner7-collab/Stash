package com.stash.feature.settings.components

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.onedrive.OneDriveAuthStore
import com.stash.core.data.onedrive.OneDriveClient
import com.stash.core.data.onedrive.OneDriveSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Self-contained "Sync to OneDrive" settings section: connect via the
 * Microsoft device-code flow (no WebView — a short code typed at
 * microsoft.com/devicelogin), an auto-sync toggle, and a manual
 * "Sync now" with live progress. Owns its own ViewModel so the main
 * settings wiring stays untouched.
 */
@HiltViewModel
class OneDriveSyncViewModel @Inject constructor(
    private val client: OneDriveClient,
    private val authStore: OneDriveAuthStore,
    private val syncManager: OneDriveSyncManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    data class UiState(
        val accountName: String? = null,
        val showLogin: Boolean = false,
        val exchanging: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val autoSync: StateFlow<Boolean> = authStore.autoSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val progress: StateFlow<OneDriveSyncManager.SyncProgress> = syncManager.progress

    /** Microsoft login page for the in-app WebView. */
    val loginUrl: String get() = client.authorizationUrl()

    init {
        viewModelScope.launch {
            authStore.accountName.collect { name ->
                _state.value = _state.value.copy(accountName = name)
            }
        }
    }

    fun connect() {
        _state.value = _state.value.copy(showLogin = true, error = null)
    }

    fun dismissLogin() {
        _state.value = _state.value.copy(showLogin = false)
    }

    /** True when the WebView hit the post-login redirect. */
    fun isRedirectUrl(url: String): Boolean = client.isRedirect(url)

    /** Called by the WebView when the redirect lands — finishes sign-in. */
    fun onRedirect(url: String) {
        val code = client.extractCode(url)
        _state.value = _state.value.copy(showLogin = false, exchanging = code != null)
        if (code == null) {
            _state.value = _state.value.copy(error = "Sign-in was cancelled or denied.")
            return
        }
        viewModelScope.launch {
            val account = client.exchangeAuthCode(code)
            _state.value = if (account != null) {
                UiState(accountName = account)
            } else {
                UiState(error = "Sign-in didn't complete — try again.")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            authStore.disconnect()
            _state.value = UiState()
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            authStore.setAutoSync(enabled)
            if (enabled) syncNow()
        }
    }

    val scheduleDays: StateFlow<Int> = authStore.syncScheduleDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 0 = off, 1 = daily, 7 = weekly, 30 = monthly. Persists the choice
     * AND (re)programs the WorkManager alarm that makes it actually run. */
    fun setSchedule(days: Int) {
        viewModelScope.launch {
            authStore.setSyncScheduleDays(days)
            com.stash.core.data.onedrive.OneDriveScheduledSyncWorker.reschedule(appContext, days)
        }
    }

    /** Foreground-service-backed: keeps syncing with the app closed. */
    fun syncNow() = com.stash.core.data.onedrive.OneDriveSyncService.start(appContext)

    fun stopSync() = syncManager.stopSync()
}

@Composable
fun OneDriveSyncSection(
    modifier: Modifier = Modifier,
    viewModel: OneDriveSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val autoSync by viewModel.autoSync.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val scheduleDays by viewModel.scheduleDays.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("OneDrive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    state.accountName?.let { "Connected: $it" } ?: "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.accountName == null) {
                Button(onClick = viewModel::connect, enabled = !state.exchanging) {
                    Text(if (state.exchanging) "Finishing…" else "Connect")
                }
            } else {
                OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
            }
        }

        // In-app Microsoft login (same UX as the YouTube/antra connects):
        // full-screen WebView; the post-login redirect is intercepted and
        // exchanged for tokens — the page itself never loads.
        if (state.showLogin) {
            OneDriveLoginDialog(
                loginUrl = viewModel.loginUrl,
                isRedirect = viewModel::isRedirectUrl,
                onRedirect = viewModel::onRedirect,
                onClose = viewModel::dismissLogin,
            )
        }

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (state.accountName != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sync to OneDrive", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Keeps your whole library in your OneDrive automatically — no downloads needed; synced songs stream instantly anywhere",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoSync, onCheckedChange = viewModel::setAutoSync)
            }

            // Scheduled sync cadence — programs a real WorkManager alarm.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Scheduled sync", style = MaterialTheme.typography.bodyLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScheduleChip("Off", 0, scheduleDays, viewModel::setSchedule)
                    ScheduleChip("Daily", 1, scheduleDays, viewModel::setSchedule)
                    ScheduleChip("Weekly", 7, scheduleDays, viewModel::setSchedule)
                    ScheduleChip("Monthly", 30, scheduleDays, viewModel::setSchedule)
                }
            }

            if (progress.running) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.percent}% — uploading ${(progress.done + 1).coerceAtMost(progress.total)} of ${progress.total}" +
                            (progress.uploading?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = viewModel::stopSync) { Text("Stop syncing") }
                }
            } else {
                OutlinedButton(onClick = viewModel::syncNow) { Text("Sync now") }
                if (progress.done > 0 || progress.lastError != null) {
                    Text(
                        progress.lastError ?: "Last sync: ${progress.done} uploaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleChip(
    label: String,
    days: Int,
    selectedDays: Int,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selectedDays == days,
        onClick = { onSelect(days) },
        label = { Text(label) },
    )
}

/**
 * Full-screen in-app Microsoft sign-in. Mirrors the app's antra/captcha
 * WebView screens: a dialog-hosted WebView pointed at the Microsoft
 * login page; [isRedirect] spots the post-login redirect and [onRedirect]
 * receives it (the redirect page itself is never loaded).
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OneDriveLoginDialog(
    loginUrl: String,
    isRedirect: (String) -> Boolean,
    onRedirect: (String) -> Unit,
    onClose: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sign in to Microsoft",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return if (isRedirect(url)) {
                                    onRedirect(url)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        loadUrl(loginUrl)
                    }
                },
            )
        }
    }
}
