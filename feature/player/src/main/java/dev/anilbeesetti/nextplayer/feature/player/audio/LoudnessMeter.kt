package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.min
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
internal class LoudnessMeter(sampleRate: Int, private val channelCount: Int) {
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

    /** Mean square of the last completed block, K-weighted and before any gating. */
    var lastBlockEnergy = 0.0
        private set

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
        addToBlock(
            samples = samples,
            offset = offset,
            frameCount = 1,
        )
        if (blockFrames < framesPerBlock) return false

        finishBlock()
        return true
    }

    /**
     * Adds the first [frameCount] frames of [samples], calling [onBlock] with the energy of every
     * block they complete. Much faster than adding frame by frame, as each channel is filtered in
     * one run.
     */
    fun addFrames(samples: FloatArray, frameCount: Int, onBlock: (Double) -> Unit) {
        var frame = 0
        while (frame < frameCount) {
            val count = min(
                frameCount - frame,
                framesPerBlock - blockFrames,
            )
            addToBlock(
                samples = samples,
                offset = frame * channelCount,
                frameCount = count,
            )
            frame += count
            if (blockFrames == framesPerBlock) {
                finishBlock()
                onBlock(lastBlockEnergy)
            }
        }
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

    /** Adds [frameCount] frames starting at [offset], which must fit in the current block. */
    private fun addToBlock(samples: FloatArray, offset: Int, frameCount: Int) {
        for (channel in 0 until channelCount) {
            val weight = channelWeights[channel]
            // The LFE channel does not count, so there is no need to filter it.
            if (weight == 0.0) continue
            blockEnergy += weight * filters[channel].energyOf(
                samples = samples,
                start = offset + channel,
                stride = channelCount,
                count = frameCount,
            )
        }
        blockFrames += frameCount
    }

    private fun finishBlock() {
        val energy = blockEnergy / framesPerBlock
        lastBlockEnergy = energy
        blockEnergy = 0.0
        blockFrames = 0
        if (isPause(lufs = energyToLufs(energy = energy))) return

        blockEnergies[nextBlock] = energy
        nextBlock = (nextBlock + 1) % SHORT_TERM_BLOCKS
        filledBlocks = (filledBlocks + 1).coerceAtMost(SHORT_TERM_BLOCKS)
        if (filledBlocks < MIN_BLOCKS) return

        // A loop instead of take().average(), so the audio thread allocates nothing per block.
        var energySum = 0.0
        for (block in 0 until filledBlocks) energySum += blockEnergies[block]
        val shortTermEnergy = energySum / filledBlocks
        shortTermLufs = energyToLufs(energy = shortTermEnergy)
        programBlocks = (programBlocks + 1).coerceAtMost(PROGRAM_MEMORY_BLOCKS)
        programEnergy += (shortTermEnergy - programEnergy) / programBlocks
    }

    private fun isPause(lufs: Double): Boolean =
        lufs <= ABSOLUTE_GATE_LUFS || lufs <= programLufs + RELATIVE_GATE_LU

    companion object {
        const val BLOCKS_PER_SECOND = 10
        const val ABSOLUTE_GATE_LUFS = -70.0

        private const val SHORT_TERM_BLOCKS = 30
        private const val MIN_BLOCKS = 4
        private const val PROGRAM_MEMORY_BLOCKS = 5 * 60 * BLOCKS_PER_SECOND

        private const val LUFS_OFFSET = -0.691
        private const val RELATIVE_GATE_LU = -20.0

        private const val FIRST_SURROUND_CHANNEL = 4
        private const val LFE_CHANNEL = 3
        private const val MIN_SURROUND_CHANNELS = 6
        private const val SURROUND_WEIGHT = 1.41

        /** Converts a K-weighted mean square to LUFS, or negative infinity for silence. */
        fun energyToLufs(energy: Double): Double = if (energy > 0.0) {
            LUFS_OFFSET + 10.0 * log10(energy)
        } else {
            Double.NEGATIVE_INFINITY
        }

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

    private var shelfState1 = 0.0
    private var shelfState2 = 0.0
    private var highPassState1 = 0.0
    private var highPassState2 = 0.0

    /**
     * Filters [count] samples that are [stride] apart from [start] and returns the sum of their
     * squares. Coefficients and state are held in locals for the run, which is what makes
     * filtering a whole run faster than one sample at a time.
     */
    fun energyOf(samples: FloatArray, start: Int, stride: Int, count: Int): Double {
        val shelfB0 = shelf.b0
        val shelfB1 = shelf.b1
        val shelfB2 = shelf.b2
        val shelfA1 = shelf.a1
        val shelfA2 = shelf.a2
        val highPassA1 = highPass.a1
        val highPassA2 = highPass.a2
        var shelf1 = shelfState1
        var shelf2 = shelfState2
        var highPass1 = highPassState1
        var highPass2 = highPassState2
        var energy = 0.0
        var index = start
        val end = start + count * stride
        while (index < end) {
            val sample = samples[index].toDouble()
            val shelved = shelfB0 * sample + shelf1
            shelf1 = shelfB1 * sample - shelfA1 * shelved + shelf2
            shelf2 = shelfB2 * sample - shelfA2 * shelved
            // The numerator of the high pass is always 1, -2, 1.
            val filtered = shelved + highPass1
            highPass1 = -2.0 * shelved - highPassA1 * filtered + highPass2
            highPass2 = shelved - highPassA2 * filtered
            energy += filtered * filtered
            index += stride
        }
        shelfState1 = shelf1
        shelfState2 = shelf2
        highPassState1 = highPass1
        highPassState2 = highPass2
        return energy
    }

    fun reset() {
        shelfState1 = 0.0
        shelfState2 = 0.0
        highPassState1 = 0.0
        highPassState2 = 0.0
    }

    /** Coefficients of a biquad filter, normalized so that a0 is 1. */
    private class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

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
