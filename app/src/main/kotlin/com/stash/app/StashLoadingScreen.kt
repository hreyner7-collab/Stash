package com.stash.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Brief branded warm-up screen shown the moment the app launches. While it's
 * up, the background warm-up (library Piped pre-resolution, server-list fetch,
 * audio engine) gets a head start, so the main UI lands ready-to-play.
 *
 * Streaming-app safe: the caller dismisses this on a HARD CAP (~2-2.5 s), so a
 * slow or failed network can never strand the user here — the rest of the
 * library keeps warming in the background after dismissal.
 */
@Composable
fun StashLoadingScreen() {
    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(
                id = if (isDark) R.drawable.wordmark_stash_dark else R.drawable.wordmark_stash_light,
            ),
            contentDescription = "Stash",
            modifier = Modifier.height(64.dp),
        )
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Getting your music ready…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
