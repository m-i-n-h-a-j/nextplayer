package dev.anilbeesetti.nextplayer.feature.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakLimiterTest {

    @Test
    fun peakIsTurnedDownWhenItLeavesTheDelay() {
        val limiter = PeakLimiter(
            sampleRate = SAMPLE_RATE,
            lookaheadFrames = LOOKAHEAD,
        )
        val gains = List(size = LOOKAHEAD + 1) { frame ->
            limiter.nextGain(
                peak = if (frame == 0) 2f else 0.1f,
                ceiling = 1f,
            )
        }

        assertTrue(gains.last() <= 0.5f)
    }

    @Test
    fun gainRecoversExactlyToUnity() {
        val limiter = PeakLimiter(
            sampleRate = SAMPLE_RATE,
            lookaheadFrames = LOOKAHEAD,
        )
        limiter.nextGain(
            peak = 2f,
            ceiling = 1f,
        )

        var gain = 0f
        repeat(times = SAMPLE_RATE * 5) {
            gain = limiter.nextGain(
                peak = 0.1f,
                ceiling = 1f,
            )
        }

        assertEquals(
            1f,
            gain,
            0f,
        )
    }

    private companion object {
        const val SAMPLE_RATE = 48000
        const val LOOKAHEAD = 240
    }
}
