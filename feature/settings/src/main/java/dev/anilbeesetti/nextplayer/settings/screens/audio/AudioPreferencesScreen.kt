package dev.anilbeesetti.nextplayer.settings.screens.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSlider
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSwitch
import dev.anilbeesetti.nextplayer.core.ui.components.RadioTextButton
import dev.anilbeesetti.nextplayer.core.ui.components.rememberTvListFocusRequester
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.settings.composables.OptionsDialog
import dev.anilbeesetti.nextplayer.settings.utils.LocalesHelper
import kotlin.math.roundToInt

@Composable
fun AudioPreferencesScreen(viewModel: AudioPreferencesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AudioPreferencesScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun AudioPreferencesScreenContent(
    state: AudioPreferencesUiState,
    onAction: (AudioPreferencesUiEvent) -> Unit,
) {
    val languages = remember {
        listOf(
            element = Pair(
                first = "None",
                second = "",
            ),
        ) +
            LocalesHelper.getAvailableLocales()
    }

    val listFocusRequester = rememberTvListFocusRequester()
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.audio),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = {
                            onAction(AudioPreferencesUiEvent.NavigateUp)
                        },
                        modifier = Modifier.tvFocusDown(target = listFocusRequester),
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .tvListFocus(focusRequester = listFocusRequester)
                .padding(paddingValues = innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.playback))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.preferred_audio_lang),
                    description =
                        LocalesHelper.getLocaleDisplayLanguage(
                            state.preferences.preferredAudioLanguage,
                        )
                            .takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.preferred_audio_lang_description),
                    icon = NextIcons.Language,
                    onClick = {
                        onAction(
                            AudioPreferencesUiEvent.ShowDialog(
                                value = AudioPreferenceDialog.AudioLanguageDialog,
                            ),
                        )
                    },
                    isFirstItem = true,
                )
                PreferenceSwitch(
                    title = stringResource(R.string.require_audio_focus),
                    description = stringResource(R.string.require_audio_focus_desc),
                    icon = NextIcons.Focus,
                    isChecked = state.preferences.requireAudioFocus,
                    onClick = { onAction(AudioPreferencesUiEvent.ToggleRequireAudioFocus) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.pause_on_headset_disconnect),
                    description = stringResource(id = R.string.pause_on_headset_disconnect_desc),
                    icon = NextIcons.HeadsetOff,
                    isChecked = state.preferences.pauseOnHeadsetDisconnect,
                    onClick = { onAction(AudioPreferencesUiEvent.TogglePauseOnHeadsetDisconnect) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.system_volume_panel),
                    description = stringResource(id = R.string.system_volume_panel_desc),
                    icon = NextIcons.Headset,
                    isChecked = state.preferences.showSystemVolumePanel,
                    onClick = { onAction(AudioPreferencesUiEvent.ToggleShowSystemVolumePanel) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.volume_boost),
                    description = stringResource(id = R.string.volume_boost_desc),
                    icon = NextIcons.VolumeUp,
                    isChecked = state.preferences.enableVolumeBoost,
                    onClick = { onAction(AudioPreferencesUiEvent.ToggleVolumeBoost) },
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.loudness_normalization))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                val enabled = state.preferences.enableLoudnessNormalization
                PreferenceSwitch(
                    title = stringResource(id = R.string.loudness_normalization),
                    description = stringResource(id = R.string.loudness_normalization_desc),
                    icon = NextIcons.VolumeUp,
                    isChecked = enabled,
                    onClick = { onAction(AudioPreferencesUiEvent.ToggleLoudnessNormalization) },
                    isFirstItem = true,
                )
                LoudnessSlider(
                    title = stringResource(id = R.string.loudness_target),
                    description = stringResource(
                        id = R.string.loudness_target_value,
                        state.preferences.loudnessTargetLufs.roundToInt(),
                    ),
                    resetDescription = stringResource(id = R.string.reset_loudness_target),
                    enabled = enabled,
                    value = state.preferences.loudnessTargetLufs,
                    valueRange = -30f..-5f,
                    steps = 24,
                    defaultValue = PlayerPreferences.DEFAULT_LOUDNESS_TARGET_LUFS,
                    onValueChange = { onAction(AudioPreferencesUiEvent.UpdateLoudnessTarget(it)) },
                )
                LoudnessSlider(
                    title = stringResource(id = R.string.loudness_range),
                    description = stringResource(
                        id = R.string.loudness_range_value,
                        state.preferences.loudnessRangeLu.roundToInt(),
                    ),
                    resetDescription = stringResource(id = R.string.reset_loudness_range),
                    enabled = enabled,
                    value = state.preferences.loudnessRangeLu,
                    valueRange = 1f..20f,
                    steps = 18,
                    defaultValue = PlayerPreferences.DEFAULT_LOUDNESS_RANGE_LU,
                    onValueChange = { onAction(AudioPreferencesUiEvent.UpdateLoudnessRange(it)) },
                )
                LoudnessSlider(
                    title = stringResource(id = R.string.loudness_peak_limit),
                    description = stringResource(
                        id = R.string.loudness_peak_limit_value,
                        state.preferences.loudnessPeakLimitDb,
                    ),
                    resetDescription = stringResource(id = R.string.reset_loudness_peak_limit),
                    enabled = enabled,
                    value = state.preferences.loudnessPeakLimitDb,
                    valueRange = -6f..0f,
                    steps = 11,
                    defaultValue = PlayerPreferences.DEFAULT_LOUDNESS_PEAK_LIMIT_DB,
                    onValueChange = {
                        onAction(AudioPreferencesUiEvent.UpdateLoudnessPeakLimit(it))
                    },
                    isLastItem = true,
                )
            }
        }

        state.showDialog?.let { showDialog ->
            when (showDialog) {
                AudioPreferenceDialog.AudioLanguageDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.preferred_audio_lang),
                        onDismissClick = {
                            onAction(AudioPreferencesUiEvent.ShowDialog(value = null))
                        },
                    ) {
                        items(items = languages) {
                            RadioTextButton(
                                text = it.first,
                                selected = it.second == state.preferences.preferredAudioLanguage,
                                onClick = {
                                    onAction(AudioPreferencesUiEvent.UpdateAudioLanguage(it.second))
                                    onAction(AudioPreferencesUiEvent.ShowDialog(value = null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoudnessSlider(
    title: String,
    description: String,
    resetDescription: String,
    enabled: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    defaultValue: Float,
    onValueChange: (Float) -> Unit,
    isLastItem: Boolean = false,
) {
    PreferenceSlider(
        title = title,
        description = description,
        enabled = enabled,
        value = value,
        valueRange = valueRange,
        steps = steps,
        onValueChange = onValueChange,
        onReset = { onValueChange(defaultValue) },
        isLastItem = isLastItem,
        trailingContent = {
            FilledIconButton(
                onClick = { onValueChange(defaultValue) },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = NextIcons.History,
                    contentDescription = resetDescription,
                )
            }
        },
    )
}

@PreviewLightDark
@Composable
private fun AudioPreferencesScreenPreview() {
    NextPlayerTheme {
        AudioPreferencesScreenContent(
            state = AudioPreferencesUiState(),
            onAction = {},
        )
    }
}
