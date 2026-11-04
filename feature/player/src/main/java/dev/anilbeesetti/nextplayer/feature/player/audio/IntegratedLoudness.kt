package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.floor

/**
 * Integrated loudness of a whole track, as defined by ITU-R BS.1770 and EBU R128.
 *
 * Takes the 100 ms block energies of [LoudnessMeter] and joins every four of them into 400 ms
 * gating blocks that overlap by 75%. Blocks below -70 LUFS are dropped, then blocks more than
 * 10 LU below the loudness of the rest. A histogram keeps memory constant for any track length.
 */
internal class IntegratedLoudness {
    private val recentEnergies = DoubleArray(BLOCKS_PER_GATING_BLOCK)
    private var recentCount = 0
    private var recentPosition = 0

    private val binEnergies = DoubleArray(BIN_COUNT)
    private val binBlocks = LongArray(BIN_COUNT)

    /** The integrated loudness in LUFS, or null if no gating block was louder than -70 LUFS. */
    val lufs: Double?
        get() {
            val absoluteGated = gatedMean(fromBin = 0) ?: return null
            val relativeGate = LoudnessMeter.energyToLufs(energy = absoluteGated) + RELATIVE_GATE_LU
            val fromBin = binOf(lufs = relativeGate).coerceAtLeast(minimumValue = 0)
            return gatedMean(fromBin = fromBin)?.let { LoudnessMeter.energyToLufs(energy = it) }
        }

    /** Adds the energy of the next 100 ms block. */
    fun addBlock(energy: Double) {
        recentEnergies[recentPosition] = energy
        recentPosition = (recentPosition + 1) % BLOCKS_PER_GATING_BLOCK
        recentCount = (recentCount + 1).coerceAtMost(maximumValue = BLOCKS_PER_GATING_BLOCK)
        if (recentCount < BLOCKS_PER_GATING_BLOCK) return

        val gatingEnergy = recentEnergies.average()
        val loudness = LoudnessMeter.energyToLufs(energy = gatingEnergy)
        if (loudness <= LoudnessMeter.ABSOLUTE_GATE_LUFS) return

        val bin = binOf(lufs = loudness).coerceIn(
            minimumValue = 0,
            maximumValue = BIN_COUNT - 1,
        )
        binEnergies[bin] += gatingEnergy
        binBlocks[bin]++
    }

    /** Starts the gating blocks over, as after a seek, but keeps what was measured. */
    fun clearCurrentAudio() {
        recentCount = 0
        recentPosition = 0
    }

    private fun gatedMean(fromBin: Int): Double? {
        var energy = 0.0
        var blocks = 0L
        for (bin in fromBin until BIN_COUNT) {
            energy += binEnergies[bin]
            blocks += binBlocks[bin]
        }
        return if (blocks > 0) energy / blocks else null
    }

    private fun binOf(lufs: Double): Int =
        floor((lufs - LoudnessMeter.ABSOLUTE_GATE_LUFS) / BIN_WIDTH_LU).toInt()

    private companion object {
        const val BLOCKS_PER_GATING_BLOCK = 4
        const val RELATIVE_GATE_LU = -10.0

        // Bins only decide which blocks pass the relative gate; their energy is summed exactly.
        const val BIN_WIDTH_LU = 0.01
        const val BIN_COUNT = 8000
    }
}
