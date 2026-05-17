package com.stash.feature.home.streaming

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.stash.core.ui.theme.StashTheme

/**
 * v0.9.30 Path A privacy disclosure shown the first time the user enables
 * streaming. Single button, informational only — the toggle has already
 * flipped by the time this renders.
 *
 * Library remains downloaded-only regardless of the streaming pref; this
 * dialog clarifies that streaming = search-tap playback via the community
 * Qobuz proxy.
 */
@Composable
fun StreamingDisclosureDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online streaming") },
        text = {
            Text(
                "Stash streams via a community Qobuz proxy. Quality is FLAC " +
                    "where available, and tracks not in the catalog can't be " +
                    "streamed.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        },
    )
}

@Preview(name = "Disclosure", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewStreamingDisclosureDialog() {
    StashTheme {
        StreamingDisclosureDialog(onDismiss = {})
    }
}
