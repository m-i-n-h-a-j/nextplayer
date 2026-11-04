package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Loudness normalization of interleaved audio.
 *
 * The gain follows FFmpeg's dynamic `loudnorm` mode: short-term loudness is moved to the target,
 * while up to half of the loudness range around the program level is preserved. A limiter then
 * catches peaks that the slow gain cannot react to.
 *
 * In single pass the program level is estimated while playing. Two pass replaces the estimate
 * with [LoudnessSettings.measuredProgramLufs] once the first pass has measured the whole track.
 */
internal class LoudnessNormalizer(sampleRate: Int, private val channelCount: Int) {
    private val meter = LoudnessMeter(
        sampleRate = sampleRate,
        channelCount = channelCount,
    )
    private val framesPerBlock = sampleRate / BLOCKS_PER_SECOND

    /** Frames that a sample stays in the limiter's delay line before it is played. */
    val latencyFrames = sampleRate * LIMITER_LOOKAHEAD_MILLIS / 1000

    private val limiter = PeakLimiter(
        sampleRate = sampleRate,
        lookaheadFrames = latencyFrames,
    )
    private val delayLine = FloatArray(latencyFrames * channelCount)
    private var delayPosition = 0

    private var targetGainDb = 0.0
    private var blockGainDb = 0.0

    // The gain moves linearly from block to block and lands exactly on rampTarget, so a ramp
    // cut short by a seek cannot overshoot, and a disabled normalizer returns to exactly 1.
    private var gain = 1f
    private var gainStep = 0f
    private var rampTarget = 1f
    private var rampFramesLeft = 0

    /** Normalizes [frameCount] frames of [samples] in place. */
    fun process(samples: FloatArray, frameCount: Int, settings: LoudnessSettings) {
        val peakLimit = dbToLinear(settings.peakLimitDb.toDouble())
        for (frame in 0 until frameCount) {
            val offset = frame * channelCount
            if (meter.addFrame(
                    samples = samples,
                    offset = offset,
                )
            ) {
                updateGain(settings = settings)
            }
            advanceGain()
            val peak = amplifyPeak(
                samples = samples,
                offset = offset,
            )
            exchangeWithDelayLine(
                samples = samples,
                offset = offset,
                limiterGain = limiter.nextGain(
                    peak = peak,
                    ceiling = ceiling(
                        enabled = settings.enabled,
                        peakLimit = peakLimit,
                    ),
                ),
            )
        }
    }

    /** Drops audio held for playback after a seek. The gain stays, so the level does not jump. */
    fun clearCurrentAudio() {
        finishRamp()
        meter.clearCurrentAudio()
        limiter.reset()
        delayLine.fill(0f)
        delayPosition = 0
    }

    private fun updateGain(settings: LoudnessSettings) {
        meter.knownProgramLufs = settings.measuredProgramLufs
        val shortTerm = meter.shortTermLufs
        targetGainDb = when {
            !settings.enabled -> 0.0

            shortTerm == Double.NEGATIVE_INFINITY ->
                settings.measuredProgramLufs?.let { settings.targetLufs - it } ?: targetGainDb

            else -> {
                val keptRange = (shortTerm - meter.programLufs).coerceIn(
                    -settings.loudnessRangeLu / 2.0,
                    settings.loudnessRangeLu / 2.0,
                )
                settings.targetLufs + keptRange - shortTerm
            }
        }.coerceIn(
            -MAX_GAIN_DB,
            MAX_GAIN_DB,
        )
        val smoothing = if (targetGainDb < blockGainDb) GAIN_DOWN_SMOOTHING else GAIN_UP_SMOOTHING
        blockGainDb += (targetGainDb - blockGainDb) * smoothing
        if (!settings.enabled && abs(blockGainDb) < UNITY_SNAP_DB) blockGainDb = 0.0

        rampTarget = dbToLinear(blockGainDb)
        rampFramesLeft = framesPerBlock
        gainStep = (rampTarget - gain) / framesPerBlock
    }

    private fun advanceGain() {
        if (rampFramesLeft == 0) return
        gain = if (--rampFramesLeft == 0) rampTarget else gain + gainStep
    }

    private fun finishRamp() {
        gain = rampTarget
        rampFramesLeft = 0
    }

    /**
     * The limiter only works while enabled, except that fading back to unity after being disabled
     * can still push peaks past full scale, which would clip.
     */
    private fun ceiling(enabled: Boolean, peakLimit: Float): Float = when {
        enabled -> peakLimit
        gain > 1f || rampTarget > 1f -> FULL_SCALE
        else -> NO_CEILING
    }

    private fun amplifyPeak(samples: FloatArray, offset: Int): Float {
        var peak = 0f
        for (index in offset until offset + channelCount) {
            samples[index] *= gain
            peak = max(
                peak,
                abs(samples[index]),
            )
        }
        return peak
    }

    /** Swaps the frame at [offset] with the oldest delayed frame, applying [limiterGain]. */
    private fun exchangeWithDelayLine(samples: FloatArray, offset: Int, limiterGain: Float) {
        for (channel in 0 until channelCount) {
            val index = offset + channel
            val delayed = delayLine[delayPosition + channel]
            delayLine[delayPosition + channel] = samples[index]
            samples[index] = delayed * limiterGain
        }
        delayPosition = (delayPosition + channelCount) % delayLine.size
    }

    private fun dbToLinear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    private companion object {
        const val BLOCKS_PER_SECOND = 10
        const val LIMITER_LOOKAHEAD_MILLIS = 5
        const val MAX_GAIN_DB = 24.0
        const val FULL_SCALE = 1f
        const val NO_CEILING = Float.MAX_VALUE

        // Below this, a disabled normalizer is at unity and passes audio through unchanged.
        const val UNITY_SNAP_DB = 0.05

        // Share of the remaining gain change applied per 100 ms block. Turning down faster than
        // up keeps sudden loud scenes under control without audible pumping in quiet ones.
        const val GAIN_DOWN_SMOOTHING = 0.2
        const val GAIN_UP_SMOOTHING = 0.05
    }
}

/** Target levels of [LoudnessNormalizer]; defaults match no normalization being applied. */
data class LoudnessSettings(
    val enabled: Boolean = false,
    val targetLufs: Float = 0f,
    val loudnessRangeLu: Float = 0f,
    val peakLimitDb: Float = 0f,
    val measuredProgramLufs: Double? = null,
)
