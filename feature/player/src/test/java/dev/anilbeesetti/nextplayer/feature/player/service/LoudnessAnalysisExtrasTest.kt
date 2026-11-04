package dev.anilbeesetti.nextplayer.feature.player.service

import android.os.Bundle
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysis
import dev.anilbeesetti.nextplayer.feature.player.model.LoudnessAnalysisStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [28],
)
class LoudnessAnalysisExtrasTest {
    @Test
    fun extrasCarryProgressWhileAnalyzing() {
        val analysis = LoudnessAnalysis(
            status = LoudnessAnalysisStatus.ANALYZING,
            progress = 0.42f,
        )

        assertEquals(
            analysis,
            Bundle().apply { putLoudnessAnalysis(analysis) }.loudnessAnalysis(),
        )
    }

    @Test
    fun extrasCarryMeasuredLoudness() {
        val analysis = LoudnessAnalysis(
            status = LoudnessAnalysisStatus.MEASURED,
            programLufs = -18.25,
        )

        assertEquals(
            analysis,
            Bundle().apply { putLoudnessAnalysis(analysis) }.loudnessAnalysis(),
        )
    }

    @Test
    fun missingExtrasMeanNothingIsAnalyzed() {
        assertEquals(
            LoudnessAnalysis(),
            Bundle.EMPTY.loudnessAnalysis(),
        )
    }
}
