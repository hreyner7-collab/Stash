package com.stash.feature.home.streaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.stash.core.ui.theme.StashTheme

/**
 * Average size estimate (in bytes) of a single downloaded FLAC track,
 * used by [DownloadOrStartFreshPrompt] to compute the "Download all
 * (~N MB)" hint.
 *
 * Picked at ~35 MB because the Stash FLAC corpus that ships through
 * Kennyy's Qobuz proxy is dominated by 3–5 minute pop/rock tracks at
 * 16-bit / 44.1 kHz, which land squarely in the 30–40 MB range on disk.
 * Hi-res rows (24/96) skew higher but are a minority — the constant is
 * deliberately a rough estimate, not a precise sum, because the user
 * decides based on order-of-magnitude (is this a 200 MB action or a
 * 20 GB one?).
 *
 * If we ever need a sharper number, [com.stash.core.data.db.dao.TrackDao.getFlacStorageBytes]
 * / [com.stash.core.data.db.dao.TrackDao.getFlacCount] yields the actual
 * library average — but pulling that read into the toggle's tap path
 * adds latency for negligible UX gain.
 */
internal const val ESTIMATED_FLAC_BYTES_PER_TRACK: Long = 35L * 1024L * 1024L

/**
 * Discriminator for the in-flight streaming-mode toggle. Set by
 * `HomeScreen` when the user taps the `StreamingModeToggle`; the
 * matching `StreamingModePrompt` reads the embedded counts and
 * renders the appropriate dialog. Lives here (not in `HomeUiState`)
 * because it's a screen-local transient — the ViewModel doesn't
 * need to know whether a dialog is currently visible.
 */
sealed interface PendingStreamingToggle {
    /**
     * User flipped the switch from Off to On. Carries the
     * downloaded-track snapshot for the "release N tracks (Y MB)"
     * CTA inside [KeepOrReleaseDownloadsPrompt].
     */
    data class OffToOn(
        val downloadedCount: Int,
        val downloadedBytes: Long,
    ) : PendingStreamingToggle

    /**
     * User flipped the switch from On to Off. Carries the
     * stream-only-row count for the "download all (~W MB)" CTA
     * inside [DownloadOrStartFreshPrompt]. Estimated bytes are
     * computed at the render site from `streamableCount *
     * [ESTIMATED_FLAC_BYTES_PER_TRACK]`.
     */
    data class OnToOff(
        val streamableCount: Int,
    ) : PendingStreamingToggle
}

/**
 * Off → On transition prompt. Asks the user whether to keep their
 * existing downloads on disk or release the space, now that the
 * Online-Streaming Engine will be serving playback from the proxy.
 *
 * Doubles as the privacy disclosure surface for the streaming feature:
 * users tap into streaming mode here, so the dialog is where they learn
 * playback routes through the community Qobuz proxy and that catalog
 * coverage isn't guaranteed. The disclosure copy is intentionally a
 * single sentence — Settings will host a longer write-up in a later task.
 *
 * @param downloadedCount how many tracks Stash currently has on disk.
 * @param downloadedBytes total disk usage of those tracks.
 * @param onKeep tapped when the user wants to enable streaming AND
 *   keep their downloaded copies. Routes to
 *   `applyStreamingMode(enabled = true, releaseDownloads = false)`.
 * @param onRelease tapped when the user wants to enable streaming AND
 *   release the disk space. Routes to
 *   `applyStreamingMode(enabled = true, releaseDownloads = true)`,
 *   which enqueues `ReleaseDownloadsWorker`.
 * @param onDismiss tapped when the user dismisses without committing
 *   — the toggle reverts to its prior Off state.
 */
@Composable
fun KeepOrReleaseDownloadsPrompt(
    downloadedCount: Int,
    downloadedBytes: Long,
    onKeep: () -> Unit,
    onRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on streaming?") },
        text = {
            Column {
                Text(
                    "Streaming plays tracks directly from a community Qobuz " +
                        "proxy. Quality is FLAC where available, and tracks " +
                        "not in the proxy's catalog will show as unavailable.",
                )
                if (downloadedCount > 0) {
                    Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                    Text(
                        text = "You have ${formatTrackCount(downloadedCount)} on " +
                            "disk (${formatBytes(downloadedBytes)}). Keep them or " +
                            "release the space?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            // The "release" branch is the destructive one — colored
            // with the error tint to match the rest of the app's
            // "this deletes files" buttons (see LibraryScreen's
            // delete-track dialog for the same treatment).
            TextButton(onClick = onRelease) {
                Text(
                    text = if (downloadedCount > 0) {
                        "Release ${formatTrackCount(downloadedCount)} (${formatBytes(downloadedBytes)})"
                    } else {
                        "Turn on"
                    },
                    color = if (downloadedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            if (downloadedCount > 0) {
                TextButton(onClick = onKeep) {
                    Text("Keep downloads")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

/**
 * On → Off transition prompt. Asks the user whether to bulk-download
 * every streamable row before flipping back to offline mode, or to
 * just turn streaming off and let those rows disappear from the
 * library (they remain in the database, just hidden by the
 * `includeStreamable` query gate).
 *
 * @param streamableCount number of tracks currently visible in the
 *   library that exist only as stream-only rows (no local copy).
 * @param estimatedBytes rough size of those rows if all were
 *   downloaded — see [ESTIMATED_FLAC_BYTES_PER_TRACK].
 * @param onDownload tapped to enqueue downloads for every streamable
 *   row before disabling streaming. Routes to
 *   `applyStreamingMode(enabled = false, downloadAllStreamable = true)`.
 * @param onStartFresh tapped to flip the pref without enqueuing any
 *   downloads. Streamable-only rows disappear from library queries on
 *   the next emission. Routes to
 *   `applyStreamingMode(enabled = false, downloadAllStreamable = false)`.
 * @param onDismiss tapped when the user backs out — the toggle
 *   reverts to its prior On state.
 */
@Composable
fun DownloadOrStartFreshPrompt(
    streamableCount: Int,
    estimatedBytes: Long,
    onDownload: () -> Unit,
    onStartFresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn off streaming?") },
        text = {
            Column {
                if (streamableCount > 0) {
                    Text(
                        "${formatTrackCount(streamableCount)} in your library " +
                            "are stream-only — they'll disappear when streaming " +
                            "is off. Download them now or start fresh?",
                    )
                } else {
                    Text(
                        "Streaming will turn off. Playback returns to your " +
                            "downloaded library only.",
                    )
                }
            }
        },
        confirmButton = {
            if (streamableCount > 0) {
                TextButton(onClick = onDownload) {
                    Text("Download all (~${formatBytes(estimatedBytes)})")
                }
            } else {
                TextButton(onClick = onStartFresh) {
                    Text("Turn off")
                }
            }
        },
        dismissButton = {
            if (streamableCount > 0) {
                TextButton(onClick = onStartFresh) {
                    Text("Start fresh")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

// ── Formatting helpers ────────────────────────────────────────────────────
//
// Local to this file because the prompts are the only callers — promoting
// to a shared module is premature. If a third surface ever needs the same
// "X tracks (Y MB)" rendering, lift this then.

private fun formatTrackCount(count: Int): String =
    if (count == 1) "1 track" else "$count tracks"

/**
 * Render a byte count as a human-readable string (MB / GB). Mirrors the
 * order-of-magnitude precision the rest of Stash uses on the Home sync
 * card — exact bytes aren't useful in a confirmation dialog, "1.2 GB"
 * vs "1.27 GB" is just noise.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}

// ── Previews ──────────────────────────────────────────────────────────────

@Preview(name = "KeepOrRelease — typical", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewKeepOrReleaseTypical() {
    StashTheme {
        KeepOrReleaseDownloadsPrompt(
            downloadedCount = 1247,
            downloadedBytes = 38L * 1024L * 1024L * 1024L,
            onKeep = {},
            onRelease = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "KeepOrRelease — empty", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewKeepOrReleaseEmpty() {
    StashTheme {
        KeepOrReleaseDownloadsPrompt(
            downloadedCount = 0,
            downloadedBytes = 0L,
            onKeep = {},
            onRelease = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "DownloadOrStartFresh — typical", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewDownloadOrStartFreshTypical() {
    StashTheme {
        DownloadOrStartFreshPrompt(
            streamableCount = 423,
            estimatedBytes = 423L * ESTIMATED_FLAC_BYTES_PER_TRACK,
            onDownload = {},
            onStartFresh = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "DownloadOrStartFresh — empty", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewDownloadOrStartFreshEmpty() {
    StashTheme {
        DownloadOrStartFreshPrompt(
            streamableCount = 0,
            estimatedBytes = 0L,
            onDownload = {},
            onStartFresh = {},
            onDismiss = {},
        )
    }
}
