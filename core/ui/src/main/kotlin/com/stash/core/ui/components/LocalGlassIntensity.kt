package com.stash.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.stash.core.model.GlassIntensity

/**
 * App-wide Liquid Glass level (0..1), provided once near the theme root
 * (MainActivity) from the user's [com.stash.core.data.prefs.GlassPreference]
 * and read by every glass surface ([GlassCard], the bottom nav bar) so
 * the slider in Settings restyles the whole app live.
 *
 * 0 = completely see-through, 1 = solid. `static` because it changes only
 * while the user drags the slider.
 */
val LocalGlassIntensity = staticCompositionLocalOf { GlassIntensity.DEFAULT }
