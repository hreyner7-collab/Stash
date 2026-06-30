package com.stash.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * Liquid-glass card, driven continuously by the user's Liquid Glass
 * slider ([LocalGlassIntensity], 0..1):
 *
 *  - **level 0 (see-through)** — almost no fill, a bright specular rim and
 *    a strong "wet" sheen: clear glass you can see the backdrop through.
 *  - **level 1 (solid)** — opaque flat fill, no rim glow, no sheen.
 *
 * The Apple Liquid Glass cues (frosted fill, specular rim, diagonal
 * sheen) are all interpolated between those endpoints so dragging the
 * slider visibly morphs every card in the app at once.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val level = LocalGlassIntensity.current.coerceIn(0f, 1f)
    val shape = MaterialTheme.shapes.large

    // Frosted fill: translucent → opaque as the slider moves to solid.
    val fill = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = lerp(0.03f, 0.40f, level)),
            Color.White.copy(alpha = lerp(0.00f, 0.30f, level)),
        ),
    )
    // Specular rim: brightest when see-through (that's the only cue then),
    // fading as the fill takes over toward solid.
    val rim = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = lerp(0.55f, 0.14f, level)),
            Color.White.copy(alpha = lerp(0.10f, 0.05f, level)),
        ),
    )
    // Diagonal "wet" sheen across the upper-left — vanishes toward solid.
    val sheen = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = lerp(0.18f, 0.0f, level)),
            Color.Transparent,
        ),
        start = Offset(0f, 0f),
        end = Offset(420f, 320f),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill, shape)
            .background(sheen, shape)
            .border(BorderStroke(1.dp, rim), shape),
    ) {
        Box(modifier = Modifier.padding(16.dp), content = content)
    }
}
