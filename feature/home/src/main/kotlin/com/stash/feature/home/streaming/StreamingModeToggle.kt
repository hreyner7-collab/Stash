package com.stash.feature.home.streaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.common.constants.StashConstants
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme

/**
 * Master toggle for the Online-Streaming Engine, surfaced at the top of
 * the Home screen. Mirrors the single-row-switch pattern used by the
 * Loudness Normalization card in `EqualizerScreen` so users get a
 * consistent visual language across master toggles.
 *
 * Visibility is gated on [StashConstants.STREAMING_ENGINE_ENABLED], the
 * feature-module-visible mirror of `BuildConfig.STREAMING_ENGINE_ENABLED`.
 * Until the kill-switch is flipped on (Task 23 of the online-streaming
 * plan) this composable is a no-op — it renders nothing.
 *
 * @param enabled current streaming-mode preference, observed from
 *   `StreamingPreference.enabled` via the host ViewModel.
 * @param onToggle invoked with the user's intended new value; the
 *   host ViewModel wraps `MusicRepository.applyStreamingMode(enabled)`
 *   so workers + prefs stay in sync. Task 17 will insert a prompt
 *   sheet between the user tap and this callback — until then the
 *   toggle calls `applyStreamingMode` directly with the safe defaults
 *   (keep downloads, no bulk-download of streamables).
 */
@Composable
fun StreamingModeToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Build-time kill-switch — Task 23 of the online-streaming-engine
    // plan flips this once end-to-end validation is done. Until then
    // we hide the affordance entirely so contributors building from
    // source don't accidentally exercise an unfinished pipeline.
    if (!StashConstants.STREAMING_ENGINE_ENABLED) return

    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Online",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Stream from your synced library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
    }
}

// ── Previews ───────────────────────────────────────────────────────────────
//
// The composable early-returns when [StashConstants.STREAMING_ENGINE_ENABLED]
// is `false`, which means the previews below would also render nothing while
// the kill-switch is off. The dedicated [PreviewStreamingModeRow] composable
// renders the same row WITHOUT the gate so designers can iterate on the
// look without flipping the flag in source.

@Preview(name = "On", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewStreamingModeToggleOn() {
    StashTheme {
        PreviewStreamingModeRow(enabled = true)
    }
}

@Preview(name = "Off", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewStreamingModeToggleOff() {
    StashTheme {
        PreviewStreamingModeRow(enabled = false)
    }
}

/**
 * Gate-bypassing copy of the toggle row used by `@Preview` functions
 * so the rendered preview doesn't depend on the build-time flag. Kept
 * `internal` + restricted to this file so production callers can't
 * accidentally render the toggle while the engine is still off.
 */
@Composable
private fun PreviewStreamingModeRow(enabled: Boolean) {
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Online",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Stream from your synced library",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {},
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
    }
}
