package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Measures EBU R128 loudness from interleaved audio frames, in blocks of 100 ms.
 *
 * Blocks far below the program level are pauses, so they are left out of both measurements.
 * Otherwise the end of a pause would read as a quiet scene and the gain would pump up.
 *
 * While playing, [programLufs] is a running estimate that slowly forgets old audio, until
 * [knownProgramLufs] replaces it with a measurement of the whole track.
 */
internal class LoudnessMeter(
    sampleRate: Int,
    private val channelCount: Int,
    private val programMemoryBlocks: Int = RUNNING_MEMORY_BLOCKS,
) {
    private val channelWeights = DoubleArray(channelCount) {
        channelWeight(
            it,
            channelCount,
        )
    }
    private val filters = Array(channelCount) { KWeightingFilter(sampleRate) }
    private val framesPerBlock = sampleRate / BLOCKS_PER_SECOND

    private val blockEnergies = DoubleArray(SHORT_TERM_BLOCKS)
    private var nextBlock = 0
    private var filledBlocks = 0
    private var blockEnergy = 0.0
    private var blockFrames = 0

    private var programEnergy = 0.0
    private var programBlocks = 0

    /** Loudness of the last 3 active seconds, or negative infinity until enough was measured. */
    var shortTermLufs = Double.NEGATIVE_INFINITY
        private set

    /** Loudness of the whole track, when a first pass has measured it. */
    var knownProgramLufs: Double? = null

    /** Loudness of the program, or negative infinity before any audio passed the gates. */
    val programLufs: Double
        get() = knownProgramLufs ?: energyToLufs(energy = programEnergy)

    /** Adds the frame starting at [offset] and returns true if it completed a block. */
    fun addFrame(samples: FloatArray, offset: Int): Boolean {
        for (channel in 0 until channelCount) {
            val filtered = filters[channel].process(sample = samples[offset + channel].toDouble())
            blockEnergy += channelWeights[channel] * filtered * filtered
        }
        if (++blockFrames < framesPerBlock) return false

        finishBlock()
        return true
    }

    /** Forgets the audio in progress after a seek, but keeps the program estimate. */
    fun clearCurrentAudio() {
        filters.forEach { it.reset() }
        nextBlock = 0
        filledBlocks = 0
        blockEnergy = 0.0
        blockFrames = 0
        shortTermLufs = Double.NEGATIVE_INFINITY
    }

    private fun finishBlock() {
        val energy = blockEnergy / framesPerBlock
        blockEnergy = 0.0
        blockFrames = 0
        if (isPause(lufs = energyToLufs(energy = energy))) return

        blockEnergies[nextBlock] = energy
        nextBlock = (nextBlock + 1) % SHORT_TERM_BLOCKS
        filledBlocks = (filledBlocks + 1).coerceAtMost(SHORT_TERM_BLOCKS)
        if (filledBlocks < MIN_BLOCKS) return

        val shortTermEnergy = blockEnergies.take(filledBlocks).average()
        shortTermLufs = energyToLufs(energy = shortTermEnergy)
        programBlocks = (programBlocks + 1).coerceAtMost(programMemoryBlocks)
        programEnergy += (shortTermEnergy - programEnergy) / programBlocks
    }

    private fun isPause(lufs: Double): Boolean =
        lufs <= ABSOLUTE_GATE_LUFS || lufs <= programLufs + RELATIVE_GATE_LU

    private fun energyToLufs(energy: Double): Double = if (energy > 0.0) {
        LUFS_OFFSET + 10.0 * log10(energy)
    } else {
        Double.NEGATIVE_INFINITY
    }

    companion object {
        /** Makes [programLufs] cover everything measured, for analyzing a whole track. */
        const val WHOLE_TRACK = Int.MAX_VALUE

        private const val BLOCKS_PER_SECOND = 10
        private const val SHORT_TERM_BLOCKS = 30
        private const val MIN_BLOCKS = 4
        private const val RUNNING_MEMORY_BLOCKS = 5 * 60 * BLOCKS_PER_SECOND

        private const val LUFS_OFFSET = -0.691
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = -20.0

        private const val FIRST_SURROUND_CHANNEL = 4
        private const val LFE_CHANNEL = 3
        private const val MIN_SURROUND_CHANNELS = 6
        private const val SURROUND_WEIGHT = 1.41

        /** BS.1770 channel weights, using Android's order: FL, FR, FC, LFE, then surrounds. */
        private fun channelWeight(channel: Int, channelCount: Int): Double = when {
            channelCount < MIN_SURROUND_CHANNELS -> 1.0
            channel == LFE_CHANNEL -> 0.0
            channel >= FIRST_SURROUND_CHANNEL -> SURROUND_WEIGHT
            else -> 1.0
        }
    }
}

/** The BS.1770 K-weighting curve: a high shelf followed by a high pass, for any sample rate. */
private class KWeightingFilter(sampleRate: Int) {
    private val shelf = highShelf(sampleRate = sampleRate)
    private val highPass = highPass(sampleRate = sampleRate)

    fun process(sample: Double): Double = highPass.process(sample = shelf.process(sample = sample))

    fun reset() {
        shelf.reset()
        highPass.reset()
    }

    private companion object {
        const val SHELF_FREQUENCY = 1681.974450955533
        const val SHELF_GAIN_DB = 3.999843853973347
        const val SHELF_Q = 0.7071752369554196
        const val SHELF_BANDWIDTH_EXPONENT = 0.4996667741545416
        const val HIGH_PASS_FREQUENCY = 38.13547087602444
        const val HIGH_PASS_Q = 0.5003270373238773

        fun highShelf(sampleRate: Int): Biquad {
            val k = tan(PI * SHELF_FREQUENCY / sampleRate)
            val highGain = 10.0.pow(SHELF_GAIN_DB / 20.0)
            val bandGain = highGain.pow(SHELF_BANDWIDTH_EXPONENT)
            val a0 = 1.0 + k / SHELF_Q + k * k
            return Biquad(
                b0 = (highGain + bandGain * k / SHELF_Q + k * k) / a0,
                b1 = 2.0 * (k * k - highGain) / a0,
                b2 = (highGain - bandGain * k / SHELF_Q + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / SHELF_Q + k * k) / a0,
            )
        }

        fun highPass(sampleRate: Int): Biquad {
            val k = tan(PI * HIGH_PASS_FREQUENCY / sampleRate)
            val a0 = 1.0 + k / HIGH_PASS_Q + k * k
            return Biquad(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / HIGH_PASS_Q + k * k) / a0,
            )
        }
    }
}

private class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var state1 = 0.0
    private var state2 = 0.0

    fun process(sample: Double): Double {
        val output = b0 * sample + state1
        state1 = b1 * sample - a1 * output + state2
        state2 = b2 * sample - a2 * output
        return output
    }

    fun reset() {
        state1 = 0.0
        state2 = 0.0
    }
}
