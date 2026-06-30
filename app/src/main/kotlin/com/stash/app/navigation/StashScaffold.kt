package com.stash.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stash.app.RequestNotificationPermissionOnce
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import com.stash.feature.nowplaying.MiniPlayer

/**
 * Root scaffold for the Stash app.
 *
 * Hosts the [StashNavHost], bottom navigation bar, and the [MiniPlayer]
 * which sits between the content area and the navigation bar.
 */
@Composable
fun StashScaffold(
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Whether a detail screen is currently in multi-select mode. Detail screens
    // signal this via `onSelectionModeChanged`; while it is true we hide the
    // whole bottom chrome (mini-player AND nav bar) so the screen's own bottom
    // selection action bar owns the bottom edge instead of stacking on / being
    // crowded by it (premium multi-select pattern, avoids mis-taps).
    var selectionActive by remember { mutableStateOf(false) }

    // Safeguard: a selection-capable screen normally clears its selection on
    // every exit path (✕ / Back / last-deselect), which fires
    // `onSelectionModeChanged(false)` before it leaves composition. Resetting on
    // route change as well guarantees the mini-player can never stay hidden if a
    // screen leaves the stack without that signal landing.
    LaunchedEffect(currentRoute) { selectionActive = false }

    // Android 13+ runtime permission for notifications. One-shot per install.
    RequestNotificationPermissionOnce()

    // Process notification deep-link extras handed in from MainActivity.
    // Only one target right now (the captcha verifier); easy to extend
    // when more deep-link surfaces show up.
    LaunchedEffect(pendingDeepLink) {
        when (pendingDeepLink) {
            CaptchaExpiredNotifier.DEEP_LINK_TARGET -> {
                // Push Settings onto the back stack BEFORE the captcha screen.
                // SquidWtfCaptchaRoute reaches into the SettingsViewModel via
                // `navController.getBackStackEntry(SettingsRoute)` to share the
                // ViewModel's cookie-write callback — that throws
                // IllegalArgumentException ("No destination with route
                // SettingsRoute in BackStack") if Settings isn't already on the
                // stack. Cold-start from a notification has no stack history,
                // so we synthesize the parent here. When the user closes the
                // captcha screen they land on Settings — natural UX.
                navController.navigate(SettingsRoute) {
                    launchSingleTop = true
                }
                navController.navigate(SquidWtfCaptchaRoute) {
                    launchSingleTop = true
                }
                onDeepLinkConsumed()
            }
            null -> Unit
            else -> onDeepLinkConsumed()  // unknown target — clear so we don't loop
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Use Scaffold's default safe-drawing insets so screens automatically
        // avoid the status bar (top) and gesture / 3-button nav (bottom).
        // The previous `WindowInsets(0.dp)` override was leaking content under
        // the system status bar — Pixel 6 Pro and similar devices on Android
        // 15+ where edge-to-edge is enforced. Reported via Twitter
        // (https://x.com/tekno_deha1/status/...).
        bottomBar = {
            // While a screen is selecting, render no bottom chrome at all — the
            // screen's own selection action bar (which handles its own nav insets)
            // takes the bottom edge. This drops innerPadding.bottom to 0 so the
            // content extends full-height behind that action bar.
            if (!selectionActive) {
                // The mini-player is the tap target that OPENS Now Playing,
                // so it's redundant (and was overlapping the artwork) once
                // you're on that full screen. Hide it there; it returns the
                // instant you navigate away. The nav bar stays.
                val onNowPlaying = currentRoute == NowPlayingRoute::class.qualifiedName
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    if (!onNowPlaying) {
                        MiniPlayer(
                            onExpand = {
                                navController.navigate(NowPlayingRoute) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }

                    StashBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { dest ->
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        StashNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            onSelectionModeChanged = { selectionActive = it },
        )
    }
}

@Composable
private fun StashBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    val level = com.stash.core.ui.components.LocalGlassIntensity.current.coerceIn(0f, 1f)

    // Liquid-glass nav bar, driven by the Settings slider: at see-through
    // (0) the bar is almost fully transparent with just a bright specular
    // rim, so the content scrolls visibly behind the icons; at solid (1)
    // it's an opaque surface. The icons/labels stay fully legible
    // throughout. `lerp` interpolates continuously as the slider moves.
    val glassFill = androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface.copy(
                alpha = androidx.compose.ui.util.lerp(0.04f, 0.92f, level),
            ),
            MaterialTheme.colorScheme.surface.copy(
                alpha = androidx.compose.ui.util.lerp(0.10f, 0.98f, level),
            ),
        ),
    )
    val rimAlpha = androidx.compose.ui.util.lerp(0.50f, 0.12f, level)

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(glassFill),
    ) {
        // Bright specular rim along the very top edge of the bar.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(androidx.compose.ui.Alignment.TopCenter)
                .height(1.dp)
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = rimAlpha)),
        )

        NavigationBar(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0.dp),
        ) {
        TopLevelDestination.entries.forEach { dest ->
            val isSelected = currentRoute == dest.route::class.qualifiedName

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(dest) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.label,
                    )
                },
                label = {
                    Text(text = dest.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = extendedColors.textTertiary,
                    unselectedTextColor = extendedColors.textTertiary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            )
        }
        }
    }
}
