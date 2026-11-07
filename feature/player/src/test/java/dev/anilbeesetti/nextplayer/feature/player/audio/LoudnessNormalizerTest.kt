package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessNormalizerTest {
    private val settings = LoudnessSettings(
        enabled = true,
        targetLufs = -14f,
        loudnessRangeLu = 7f,
        peakLimitDb = -1f,
    )

    @Test
    fun meterReadsStereoSineAtItsPeakLevel() {
        val meter = LoudnessMeter(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        val samples = sine(
            levelDb = -23f,
            seconds = 5,
        )

        for (frame in 0 until samples.size / CHANNELS) {
            meter.addFrame(
                samples = samples,
                offset = frame * CHANNELS,
            )
        }

        assertEquals(
            -23.0,
            meter.shortTermLufs,
            0.2,
        )
    }

    @Test
    fun quietAudioIsRaisedToTarget() {
        val output = normalize(
            sine(
                levelDb = -35f,
                seconds = 60,
            ),
            settings = settings,
        )

        assertEquals(
            -14.0,
            rmsSineLevelDb(output.lastSecond()),
            0.5,
        )
    }

    @Test
    fun loudAudioIsLoweredToTarget() {
        val output = normalize(
            sine(
                levelDb = -3f,
                seconds = 60,
            ),
            settings = settings,
        )

        assertEquals(
            -14.0,
            rmsSineLevelDb(output.lastSecond()),
            0.5,
        )
    }

    @Test
    fun peaksStayUnderLimitWhenLoudSceneFollowsQuietOne() {
        val quiet = sine(
            levelDb = -40f,
            seconds = 30,
        )
        val loud = sine(
            levelDb = 0f,
            seconds = 10,
        )

        val output = normalize(
            quiet + loud,
            settings = settings,
        )

        val limit = 10f.pow(settings.peakLimitDb / 20f)
        assertTrue(output.maxOf { abs(it) } <= limit + 0.001f)
    }

    @Test
    fun silenceKeepsCurrentGain() {
        val speech = sine(
            levelDb = -30f,
            seconds = 30,
        )
        val output = normalize(
            speech + FloatArray(SAMPLE_RATE * CHANNELS * 10) + speech,
            settings = settings,
        )

        val resumed = output.copyOfRange(
            40 * SAMPLE_RATE * CHANNELS,
            41 * SAMPLE_RATE * CHANNELS,
        )
        assertEquals(
            -14.0,
            rmsSineLevelDb(resumed),
            1.0,
        )
    }

    @Test
    fun measuredProgramLoudnessKeepsQuietSceneBelowTarget() {
        val output = normalize(
            sine(
                levelDb = -30f,
                seconds = 60,
            ),
            settings = settings.copy(measuredProgramLufs = -20.0),
        )

        assertEquals(
            -17.5,
            rmsSineLevelDb(output.lastSecond()),
            0.5,
        )
    }

    @Test
    fun newTargetAppliesToTheMeasuredLoudnessWithoutMeasuringAgain() {
        val normalizer = LoudnessNormalizer(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        val measured = settings.copy(measuredProgramLufs = -20.0)
        normalizer.processAll(
            samples = sine(
                levelDb = -30f,
                seconds = 60,
            ),
            settings = measured,
        )
        val output = sine(
            levelDb = -30f,
            seconds = 30,
        )

        // Only the settings change: the measurement of the track stays the same.
        normalizer.processAll(
            samples = output,
            settings = measured.copy(targetLufs = -20f),
        )

        assertEquals(
            -23.5,
            rmsSineLevelDb(output.lastSecond()),
            0.5,
        )
    }

    @Test
    fun disabledSettingsOnlyDelayTheAudio() {
        val input = sine(
            levelDb = -35f,
            seconds = 2,
        )
        val normalizer = LoudnessNormalizer(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        val output = input.copyOf()

        normalizer.process(
            samples = output,
            frameCount = output.size / CHANNELS,
            settings = LoudnessSettings(enabled = false),
        )

        val delaySamples = normalizer.latencyFrames * CHANNELS
        assertArrayEquals(
            input.copyOf(input.size - delaySamples),
            output.copyOfRange(
                delaySamples,
                output.size,
            ),
            0f,
        )
    }

    @Test
    fun measuredLoudnessFarBelowTargetIsLimitedToMaxGain() {
        val normalizer = LoudnessNormalizer(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        val measured = settings.copy(measuredProgramLufs = -70.0)
        // Nothing here passes the absolute gate, so the gain comes from the measurement alone.
        normalizer.processAll(
            samples = FloatArray(SAMPLE_RATE * CHANNELS * 30),
            settings = measured,
        )
        val output = sine(
            levelDb = -75f,
            seconds = 5,
        )

        normalizer.processAll(
            samples = output,
            settings = measured,
        )

        assertEquals(
            -75.0 + 24.0,
            rmsSineLevelDb(output.lastSecond()),
            0.5,
        )
    }

    @Test
    fun disablingWhileRaisedDoesNotClipLoudAudio() {
        val normalizer = LoudnessNormalizer(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        normalizer.processAll(
            samples = sine(
                levelDb = -35f,
                seconds = 30,
            ),
            settings = settings,
        )
        val loud = sine(
            levelDb = -3f,
            seconds = 2,
        )

        normalizer.processAll(
            samples = loud,
            settings = LoudnessSettings(enabled = false),
        )

        assertTrue(loud.maxOf { abs(it) } <= 1f + 0.001f)
    }

    @Test
    fun disablingFadesBackToUnchangedAudio() {
        val normalizer = LoudnessNormalizer(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        )
        normalizer.processAll(
            samples = sine(
                levelDb = -35f,
                seconds = 30,
            ),
            settings = settings,
        )
        normalizer.processAll(
            samples = sine(
                levelDb = -35f,
                seconds = 10,
            ),
            settings = LoudnessSettings(enabled = false),
        )
        val input = sine(
            levelDb = -35f,
            seconds = 1,
        )
        val output = input.copyOf()

        normalizer.processAll(
            samples = output,
            settings = LoudnessSettings(enabled = false),
        )

        val delaySamples = normalizer.latencyFrames * CHANNELS
        assertArrayEquals(
            input.copyOf(input.size - delaySamples),
            output.copyOfRange(
                delaySamples,
                output.size,
            ),
            0f,
        )
    }

    private fun LoudnessNormalizer.processAll(samples: FloatArray, settings: LoudnessSettings) {
        process(
            samples = samples,
            frameCount = samples.size / CHANNELS,
            settings = settings,
        )
    }

    private fun normalize(input: FloatArray, settings: LoudnessSettings): FloatArray {
        val output = input.copyOf()
        LoudnessNormalizer(
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNELS,
        ).process(
            samples = output,
            frameCount = output.size / CHANNELS,
            settings = settings,
        )
        return output
    }

    private fun sine(levelDb: Float, seconds: Int): FloatArray {
        val amplitude = 10f.pow(levelDb / 20f)
        val frames = SAMPLE_RATE * seconds
        return FloatArray(frames * CHANNELS) { index ->
            val frame = index / CHANNELS
            amplitude * sin(2 * PI * SINE_HERTZ * frame / SAMPLE_RATE).toFloat()
        }
    }

    private fun FloatArray.lastSecond(): FloatArray = copyOfRange(
        size - SAMPLE_RATE * CHANNELS,
        size,
    )

    /** Peak level of a sine from its RMS, which equals LUFS for a stereo sine near 1 kHz. */
    private fun rmsSineLevelDb(samples: FloatArray): Double {
        val meanSquare = samples.sumOf { it.toDouble() * it } / samples.size
        return 10 * log10(2 * meanSquare)
    }

    private companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val SINE_HERTZ = 997.0
    }
}
