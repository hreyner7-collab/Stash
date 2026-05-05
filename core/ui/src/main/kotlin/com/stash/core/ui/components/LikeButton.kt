package com.stash.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v0.9.13: Heart icon for liking a track. Tap fires fan-out to user's
 * default destinations; long-press opens [LikeDestinationSheet] for
 * per-track override. State (filled vs outlined) reflects whether ANY
 * destination is already saved — dedup is per-destination at the
 * dispatcher level.
 *
 * @param likedAny  True if the track is saved to at least one
 *                  destination (Stash, Spotify, or YT Music).
 * @param onTap     Fires the default-destination fan-out.
 * @param onLongPress  Opens the per-track override sheet.
 * @param tint      Icon tint when not liked. Liked state always uses
 *                  the theme's `error` (red) so it pops against any
 *                  background.
 * @param size      Icon size. Default 24.dp matches Material3 IconButton.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LikeButton(
    likedAny: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 24.dp,
) {
    Icon(
        imageVector = if (likedAny) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        contentDescription = if (likedAny) "Unlike" else "Like",
        tint = if (likedAny) MaterialTheme.colorScheme.error else (tint ?: MaterialTheme.colorScheme.onSurface),
        modifier = modifier
            .size(size)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                role = Role.Button,
            ),
    )
}
