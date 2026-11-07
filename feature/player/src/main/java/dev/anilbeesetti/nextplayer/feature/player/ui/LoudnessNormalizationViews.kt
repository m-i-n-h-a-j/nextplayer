package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Matrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.LoudnessNormalizationMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysis
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysisStatus
import kotlin.math.roundToInt

/**
 * Animates the light that circles the audio panel while the first pass runs, or returns null when
 * there is nothing to show. Only runs while the panel is shown, as it draws every frame.
 */
@Composable
internal fun rememberAnalyzingOutlineAngle(isAnalyzing: Boolean): State<Float>? {
    if (!isAnalyzing) return null
    return rememberInfiniteTransition(label = "AnalyzingOutline").animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = OUTLINE_LAP_MILLIS,
                easing = LinearEasing,
            ),
        ),
        label = "AnalyzingOutlineAngle",
    )
}

/** Outlines [shape] with a light circling at [angle], a loading animation for the first pass. */
internal fun Modifier.analyzingOutline(angle: State<Float>?, color: Color, shape: Shape): Modifier =
    if (angle == null) {
        this
    } else {
        drawWithContent {
            drawContent()
            val strokeWidth = OUTLINE_WIDTH.toPx()
            inset(inset = strokeWidth / 2) {
                val outline = shape.createOutline(
                    size = size,
                    layoutDirection = layoutDirection,
                    density = this,
                )
                drawOutline(
                    outline = outline,
                    color = color.copy(alpha = OUTLINE_TRACK_ALPHA),
                    style = Stroke(width = strokeWidth),
                )
                val light = SweepGradientShader(
                    center = center,
                    colors = listOf(
                        Color.Transparent,
                        color,
                        Color.Transparent,
                    ),
                    colorStops = listOf(
                        0f,
                        OUTLINE_LIGHT_LENGTH / 2,
                        OUTLINE_LIGHT_LENGTH,
                    ),
                )
                // Reading the angle here only redraws the outline, without recomposing the panel.
                light.setLocalMatrix(
                    Matrix().apply {
                        setRotate(
                            angle.value,
                            center.x,
                            center.y,
                        )
                    },
                )
                drawOutline(
                    outline = outline,
                    brush = ShaderBrush(light),
                    style = Stroke(width = strokeWidth),
                )
            }
        }
    }

/** Chooses between single and two pass, and tells what the chosen mode is doing right now. */
@Composable
internal fun LoudnessModeSettings(
    mode: LoudnessNormalizationMode,
    analysis: LoudnessAnalysis,
    targetLufs: Float,
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
            targetLufs = targetLufs,
        )
    }
}

@Composable
private fun LoudnessStatus(
    mode: LoudnessNormalizationMode,
    analysis: LoudnessAnalysis,
    targetLufs: Float,
) {
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
                // The measurement does not depend on the settings, so a new target applies to it
                // right away; showing both makes that visible.
                text = stringResource(
                    R.string.loudness_status_two_pass,
                    analysis.programLufs ?: 0.0,
                    targetLufs.roundToInt(),
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

private const val FULL_TURN_DEGREES = 360f
private const val OUTLINE_LAP_MILLIS = 1500
private const val OUTLINE_LIGHT_LENGTH = 0.35f
private const val OUTLINE_TRACK_ALPHA = 0.3f
private val OUTLINE_WIDTH = 3.dp
