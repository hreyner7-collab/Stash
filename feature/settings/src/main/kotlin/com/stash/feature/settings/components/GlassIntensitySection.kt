package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.prefs.GlassPreference
import com.stash.core.model.GlassIntensity
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.LocalGlassIntensity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Liquid Glass slider — a continuous control (Apple ships the same in
 * iOS 26 System Settings) from completely see-through (0) to solid (1).
 * Dragging it restyles every glass surface in the app live, including the
 * bottom nav bar, via [LocalGlassIntensity].
 */
@HiltViewModel
class GlassIntensityViewModel @Inject constructor(
    private val glassPreference: GlassPreference,
) : ViewModel() {
    val level: StateFlow<Float> = glassPreference.level
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlassIntensity.DEFAULT)

    fun setLevel(value: Float) {
        viewModelScope.launch { glassPreference.setLevel(value) }
    }
}

@Composable
fun GlassIntensitySection(
    modifier: Modifier = Modifier,
    viewModel: GlassIntensityViewModel = hiltViewModel(),
) {
    val persisted by viewModel.level.collectAsState()
    // Local thumb state for smooth dragging; persisted value flows back in
    // when changed (and on first emission).
    var local by remember { mutableFloatStateOf(persisted) }
    LaunchedEffect(persisted) { local = persisted }

    Column(modifier = modifier.fillMaxWidth()) {
        com.stash.core.ui.components.SectionHeader(title = "Appearance")

        // Live preview: this card reflects the slider as you drag (its own
        // CompositionLocal override), so you see the exact glass before the
        // rest of the app catches up.
        CompositionLocalProvider(LocalGlassIntensity provides local) {
            GlassCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Liquid Glass",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Drag to set exactly how see-through the app's glass surfaces are — " +
                            "from fully transparent to solid. Affects cards and the bottom buttons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Slider(
            value = local,
            onValueChange = {
                local = it
                viewModel.setLevel(it)
            },
            valueRange = 0f..1f,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "See-through",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${(local * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Solid",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
