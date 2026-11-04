package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.LoudnessNormalizationMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysis
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysisStatus
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** A short note about two-pass analysis, shown over the video next to the player controls. */
sealed interface LoudnessNotice {
    data class Analyzing(val progress: Float?) : LoudnessNotice

    data object SwitchedToTwoPass : LoudnessNotice
}

/**
 * Returns the note to show for [analysis] in two pass: while the first pass runs, and for a few
 * seconds after it switched to two pass. A result read from the cache switches right away, so it
 * needs no note.
 */
@Composable
internal fun rememberLoudnessNotice(
    analysis: LoudnessAnalysis,
    isTwoPass: Boolean,
): LoudnessNotice? {
    var previousStatus by remember { mutableStateOf(analysis.status) }
    var hasJustSwitched by remember { mutableStateOf(false) }
    LaunchedEffect(analysis.status) {
        hasJustSwitched = previousStatus == LoudnessAnalysisStatus.ANALYZING &&
            analysis.status == LoudnessAnalysisStatus.MEASURED
        previousStatus = analysis.status
        if (hasJustSwitched) {
            delay(SWITCHED_NOTICE_DURATION)
            hasJustSwitched = false
        }
    }
    return when {
        !isTwoPass -> null

        analysis.status == LoudnessAnalysisStatus.ANALYZING -> LoudnessNotice.Analyzing(
            analysis.progress,
        )

        hasJustSwitched -> LoudnessNotice.SwitchedToTwoPass

        else -> null
    }
}

@Composable
internal fun LoudnessNoticeChip(notice: LoudnessNotice, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (notice) {
                is LoudnessNotice.Analyzing -> {
                    val progressModifier = Modifier.size(20.dp)
                    if (notice.progress != null) {
                        CircularProgressIndicator(
                            progress = { notice.progress },
                            modifier = progressModifier,
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = progressModifier,
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                    Column {
                        Text(
                            text = analyzingText(progress = notice.progress),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = stringResource(R.string.loudness_status_analyzing_fallback),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }

                LoudnessNotice.SwitchedToTwoPass -> {
                    Icon(
                        imageVector = NextIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.loudness_status_switched_to_two_pass),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Chooses between single and two pass, and tells what the chosen mode is doing right now. */
@Composable
internal fun LoudnessModeSettings(
    mode: LoudnessNormalizationMode,
    analysis: LoudnessAnalysis,
    onModeSelected: (LoudnessNormalizationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val modes = listOf(
            LoudnessNormalizationMode.TWO_PASS,
            LoudnessNormalizationMode.SINGLE_PASS,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { onModeSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = modes.size,
                    ),
                ) {
                    Text(text = stringResource(id = option.labelRes))
                }
            }
        }
        LoudnessStatus(
            mode = mode,
            analysis = analysis,
        )
    }
}

@Composable
private fun LoudnessStatus(mode: LoudnessNormalizationMode, analysis: LoudnessAnalysis) {
    val statusColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statusStyle = MaterialTheme.typography.bodyMedium
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (mode == LoudnessNormalizationMode.SINGLE_PASS) {
            Text(
                text = stringResource(R.string.loudness_status_single_pass),
                style = statusStyle,
                color = statusColor,
            )
            return@Column
        }
        when (analysis.status) {
            LoudnessAnalysisStatus.NONE -> Unit

            LoudnessAnalysisStatus.ANALYZING -> {
                Text(
                    text = analyzingText(progress = analysis.progress),
                    style = MaterialTheme.typography.titleSmall,
                )
                val progress = analysis.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(R.string.loudness_status_analyzing_fallback),
                    style = statusStyle,
                    color = statusColor,
                )
            }

            LoudnessAnalysisStatus.MEASURED -> Text(
                text = stringResource(
                    R.string.loudness_status_two_pass,
                    analysis.programLufs ?: 0.0,
                ),
                style = statusStyle,
                color = statusColor,
            )

            LoudnessAnalysisStatus.UNAVAILABLE_FOR_STREAM -> Text(
                text = stringResource(R.string.loudness_status_stream),
                style = statusStyle,
                color = statusColor,
            )

            LoudnessAnalysisStatus.FAILED -> Text(
                text = stringResource(R.string.loudness_status_failed),
                style = statusStyle,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun analyzingText(progress: Float?): String = if (progress != null) {
    stringResource(
        R.string.loudness_status_analyzing_progress,
        (progress * 100).roundToInt(),
    )
} else {
    stringResource(R.string.loudness_status_analyzing)
}

private val LoudnessNormalizationMode.labelRes: Int
    get() = when (this) {
        LoudnessNormalizationMode.TWO_PASS -> R.string.loudness_two_pass
        LoudnessNormalizationMode.SINGLE_PASS -> R.string.loudness_single_pass
    }

private val SWITCHED_NOTICE_DURATION = 4.seconds
