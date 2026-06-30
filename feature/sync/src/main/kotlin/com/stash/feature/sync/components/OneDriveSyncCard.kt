package com.stash.feature.sync.components

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.onedrive.OneDriveAuthStore
import com.stash.core.data.onedrive.OneDriveSyncManager
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Sync tab's OneDrive card — deliberately the same design language
 * as [SyncHeroCard] directly above it (gradient surface, "LAST SYNC"
 * eyebrow, "time · N tracks" line, live progress while running) so the
 * two syncs read as one coherent surface: Spotify/YT pulls your library
 * IN; this card pushes the audio UP to the user's OneDrive warehouse.
 */
@HiltViewModel
class OneDriveSyncCardViewModel @Inject constructor(
    authStore: OneDriveAuthStore,
    private val syncManager: OneDriveSyncManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    val connected: StateFlow<Boolean> = authStore.accountName
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lastSyncAtMs: StateFlow<Long?> = authStore.lastSyncAtMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalSynced: StateFlow<Int> = authStore.totalSyncedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalSyncedBytes: StateFlow<Long> = authStore.totalSyncedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val progress: StateFlow<OneDriveSyncManager.SyncProgress> = syncManager.progress

    /** Foreground-service-backed start: the pass keeps running with the
     * app backgrounded or the screen off, with a progress notification. */
    fun syncNow() = com.stash.core.data.onedrive.OneDriveSyncService.start(appContext)

    fun stopSync() = syncManager.stopSync()
}

@Composable
fun OneDriveSyncCard(
    modifier: Modifier = Modifier,
    viewModel: OneDriveSyncCardViewModel = hiltViewModel(),
) {
    val connected by viewModel.connected.collectAsState()
    val lastSyncAt by viewModel.lastSyncAtMs.collectAsState()
    val totalSynced by viewModel.totalSynced.collectAsState()
    val progress by viewModel.progress.collectAsState()

    val purple = MaterialTheme.colorScheme.primary
    val cyan = StashTheme.extendedColors.cyan
    val gradient = Brush.linearGradient(
        colors = listOf(
            purple.copy(alpha = 0.18f),
            cyan.copy(alpha = 0.08f),
        ),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, purple.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .background(gradient, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ONEDRIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = StashTheme.extendedColors.purpleLight,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    val body = when {
                        !connected -> "Not connected"
                        lastSyncAt == null -> "Never synced"
                        else -> "${relativeTime(lastSyncAt!!)} · $totalSynced tracks"
                    }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (connected && lastSyncAt != null) {
                    Text(
                        text = if (progress.lastError != null) "Check sync" else "Healthy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (progress.lastError != null) {
                            StashTheme.extendedColors.warning
                        } else {
                            StashTheme.extendedColors.success
                        },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            when {
                !connected -> {
                    Text(
                        text = "Connect OneDrive in Settings to store your songs in your own cloud — synced songs stream instantly from anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                progress.running -> {
                    // Mirrors SyncActionProgress's phase display: a live
                    // progress bar, percentage + time-left, the current
                    // song, the projected storage bill, and a Stop button.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = {
                                if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${progress.percent}% done · ${100 - progress.percent}% left" +
                                (progress.etaMs?.let {
                                    " · about ${com.stash.core.data.onedrive.OneDriveSyncService.formatEta(it)} left"
                                } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Uploading ${(progress.done + 1).coerceAtMost(progress.total)} of ${progress.total}" +
                                (progress.uploading?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress.estimatedFinalBytes > 0) {
                            Text(
                                text = "Estimated storage use: ~${formatGb(progress.estimatedFinalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::stopSync,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop syncing") }
                    }
                }
                else -> {
                    Button(
                        onClick = viewModel::syncNow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Sync to OneDrive")
                    }
                    progress.lastError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = StashTheme.extendedColors.warning,
                        )
                    }
                }
            }
        }
    }
}

private fun relativeTime(epochMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

private fun formatGb(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024 * 1024)
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024))
}
