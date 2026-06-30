package com.stash.core.model

/**
 * Liquid Glass strength as a continuous 0..1 level, set by the slider at
 * the bottom of Settings (Apple ships the same continuous control in iOS
 * 26 System Settings).
 *
 * - **0.0** — completely see-through: glass surfaces are barely-there,
 *   only a faint specular rim remains so they still read as glass.
 * - **1.0** — solid: opaque, flat surfaces with no translucency.
 *
 * The default sits in the middle.
 */
object GlassIntensity {
    const val MIN = 0f
    const val MAX = 1f
    const val DEFAULT = 0.5f
}
