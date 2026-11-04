package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.anilbeesetti.nextplayer.core.model.LoudnessNormalizationMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysis
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysisStatus
import org.junit.Assert.assertEquals
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
    fun finishedAnalysisAnnouncesTheSwitchToTwoPassForAWhile() {
        var analysis by mutableStateOf(
            LoudnessAnalysis(
                status = LoudnessAnalysisStatus.ANALYZING,
                progress = 0.5f,
            ),
        )
        var notice: LoudnessNotice? = null
        composeRule.setContent {
            notice = rememberLoudnessNotice(
                analysis = analysis,
                isTwoPass = true,
            )
        }
        composeRule.waitForIdle()
        assertEquals(
            LoudnessNotice.Analyzing(progress = 0.5f),
            notice,
        )

        composeRule.mainClock.autoAdvance = false
        analysis = LoudnessAnalysis(
            status = LoudnessAnalysisStatus.MEASURED,
            programLufs = -18.0,
        )
        advanceTwoFrames()
        assertEquals(
            LoudnessNotice.SwitchedToTwoPass,
            notice,
        )

        composeRule.mainClock.advanceTimeBy(milliseconds = 5_000)
        assertNull(notice)
    }

    @Test
    fun measurementFromTheCacheNeedsNoNotice() {
        var analysis by mutableStateOf(LoudnessAnalysis())
        var composedStatus: LoudnessAnalysisStatus? = null
        var notice: LoudnessNotice? = null
        composeRule.setContent {
            composedStatus = analysis.status
            notice = rememberLoudnessNotice(
                analysis = analysis,
                isTwoPass = true,
            )
        }
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        analysis = LoudnessAnalysis(
            status = LoudnessAnalysisStatus.MEASURED,
            programLufs = -18.0,
        )
        advanceTwoFrames()

        assertEquals(
            LoudnessAnalysisStatus.MEASURED,
            composedStatus,
        )
        assertNull(notice)
    }

    @Test
    fun singlePassShowsNoAnalysisNotice() {
        var notice: LoudnessNotice? = LoudnessNotice.SwitchedToTwoPass
        composeRule.setContent {
            notice = rememberLoudnessNotice(
                analysis = LoudnessAnalysis(status = LoudnessAnalysisStatus.ANALYZING),
                isTwoPass = false,
            )
        }
        composeRule.waitForIdle()

        assertNull(notice)
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

    /** Recomposes with a state written by the test, then with what its effects changed. */
    private fun advanceTwoFrames() {
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun audioPanelSwitchesToSinglePass() {
        var selectedMode: LoudnessNormalizationMode? = null
        composeRule.setContent {
            NextPlayerTheme {
                LoudnessModeSettings(
                    mode = LoudnessNormalizationMode.TWO_PASS,
                    analysis = LoudnessAnalysis(),
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
