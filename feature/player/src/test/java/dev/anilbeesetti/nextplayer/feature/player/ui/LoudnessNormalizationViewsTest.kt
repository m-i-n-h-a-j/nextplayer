package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anilbeesetti.nextplayer.core.model.LoudnessNormalizationMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysis
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysisStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoudnessNormalizationViewsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outlineAnimatesOnlyWhileAnalyzing() {
        var isAnalyzing by mutableStateOf(true)
        var angle: State<Float>? = null
        composeRule.setContent {
            angle = rememberAnalyzingOutlineAngle(isAnalyzing = isAnalyzing)
        }
        composeRule.runOnIdle { assertNotNull(angle) }

        isAnalyzing = false

        composeRule.runOnIdle { assertNull(angle) }
    }

    @Test
    fun audioPanelTellsThatSinglePassRunsUntilTheAnalysisIsDone() {
        composeRule.setContent {
            NextPlayerTheme {
                LoudnessModeSettings(
                    mode = LoudnessNormalizationMode.TWO_PASS,
                    analysis = LoudnessAnalysis(
                        status = LoudnessAnalysisStatus.ANALYZING,
                        progress = 0.42f,
                    ),
                    targetLufs = -14f,
                    onModeSelected = {},
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.loudness_status_analyzing_progress,
                42,
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.loudness_status_analyzing_fallback),
        ).assertExists()
    }

    @Test
    fun audioPanelShowsTheMeasurementWithTheCurrentTarget() {
        composeRule.setContent {
            NextPlayerTheme {
                LoudnessModeSettings(
                    mode = LoudnessNormalizationMode.TWO_PASS,
                    analysis = LoudnessAnalysis(
                        status = LoudnessAnalysisStatus.MEASURED,
                        programLufs = -18.25,
                    ),
                    targetLufs = -20f,
                    onModeSelected = {},
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(
                R.string.loudness_status_two_pass,
                -18.25,
                -20,
            ),
        ).assertExists()
    }

    @Test
    fun audioPanelSwitchesToSinglePass() {
        var selectedMode: LoudnessNormalizationMode? = null
        composeRule.setContent {
            NextPlayerTheme {
                LoudnessModeSettings(
                    mode = LoudnessNormalizationMode.TWO_PASS,
                    analysis = LoudnessAnalysis(),
                    targetLufs = -14f,
                    onModeSelected = { selectedMode = it },
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.loudness_single_pass),
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(
                LoudnessNormalizationMode.SINGLE_PASS,
                selectedMode,
            )
        }
    }
}
