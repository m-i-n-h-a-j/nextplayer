package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses the integrated loudness cases of EBU Tech 3341, which a R128 meter must pass. */
class IntegratedLoudnessTest {

    @Test
    fun steadySineReadsAtItsPeakLevel() {
        val lufs = measure(
            Tone(
                levelDb = -23f,
                seconds = 20,
            ),
        )

        assertEquals(
            -23.0,
            lufs!!,
            0.1,
        )
    }

    @Test
    fun relativeGateIgnoresQuietPassages() {
        val lufs = measure(
            Tone(
                levelDb = -36f,
                seconds = 10,
            ),
            Tone(
                levelDb = -23f,
                seconds = 60,
            ),
            Tone(
                levelDb = -36f,
                seconds = 10,
            ),
        )

        assertEquals(
            -23.0,
            lufs!!,
            0.1,
        )
    }

    @Test
    fun absoluteGateIgnoresNearSilence() {
        val lufs = measure(
            Tone(
                levelDb = -72f,
                seconds = 10,
            ),
            Tone(
                levelDb = -36f,
                seconds = 10,
            ),
            Tone(
                levelDb = -23f,
                seconds = 60,
            ),
            Tone(
                levelDb = -36f,
                seconds = 10,
            ),
            Tone(
                levelDb = -72f,
                seconds = 10,
            ),
        )

        assertEquals(
            -23.0,
            lufs!!,
            0.1,
        )
    }

    @Test
    fun silenceHasNoLoudness() {
        assertNull(
            measure(
                Tone(
                    levelDb = null,
                    seconds = 10,
                ),
            ),
        )
    }

    @Test
    fun seekKeepsWhatWasMeasured() {
        val loudness = IntegratedLoudness()
        val meter = LoudnessMeter(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        meter.feed(
            tone = Tone(
                levelDb = -23f,
                seconds = 10,
            ),
            into = loudness,
        )

        loudness.clearCurrentAudio()
        meter.clearCurrentAudio()
        meter.feed(
            tone = Tone(
                levelDb = -23f,
                seconds = 10,
            ),
            into = loudness,
        )

        assertEquals(
            -23.0,
            loudness.lufs!!,
            0.1,
        )
    }

    private fun measure(vararg tones: Tone): Double? {
        val loudness = IntegratedLoudness()
        val meter = LoudnessMeter(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        tones.forEach {
            meter.feed(
                tone = it,
                into = loudness,
            )
        }
        return loudness.lufs
    }

    private fun LoudnessMeter.feed(tone: Tone, into: IntegratedLoudness) {
        val amplitude = tone.levelDb?.let { 10f.pow(it / 20f) } ?: 0f
        val frame = FloatArray(CHANNELS)
        for (index in 0 until SAMPLE_RATE * tone.seconds) {
            frame.fill(amplitude * sin(2 * PI * SINE_HERTZ * index / SAMPLE_RATE).toFloat())
            if (addFrame(
                    samples = frame,
                    offset = 0,
                )
            ) {
                into.addBlock(energy = lastBlockEnergy)
            }
        }
    }

    /** A stereo sine at [levelDb] peak on both channels, or silence when it is null. */
    private data class Tone(val levelDb: Float?, val seconds: Int)

    private companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val SINE_HERTZ = 997.0
    }
}
