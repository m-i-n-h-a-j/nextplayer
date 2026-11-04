package dev.anilbeesetti.nextplayer.feature.player.audio

import kotlin.math.exp
import kotlin.math.min

/**
 * Computes the gain that keeps delayed audio under a ceiling.
 *
 * Each frame is seen [lookaheadFrames] before it is played. The gain required by the quietest
 * moment in that span is averaged over the same span, so it fades down smoothly and has fully
 * arrived when the peak is played. Afterwards the gain recovers slowly.
 */
internal class PeakLimiter(sampleRate: Int, private val lookaheadFrames: Int) {
    private val releasePerFrame = 1f - exp(-1f / (RELEASE_SECONDS * sampleRate))

    private val requiredGains = SlidingMinimum(size = lookaheadFrames + 1)
    private val fadeFrames = FloatArray(lookaheadFrames) { 1f }
    private var fadePosition = 0
    private var fadeSum = lookaheadFrames.toDouble()
    private var heldGain = 1f

    /** Returns the gain for the frame leaving the delay, given the [peak] entering it. */
    fun nextGain(peak: Float, ceiling: Float): Float {
        val requiredGain = if (peak > ceiling) ceiling / peak else 1f
        val lowestGain = requiredGains.add(value = requiredGain)
        heldGain = if (lowestGain < heldGain) {
            lowestGain
        } else {
            val released = heldGain + (lowestGain - heldGain) * releasePerFrame
            // In float, the release stalls a hair short of its goal once a step is too small to
            // change the gain, which would leave the audio slightly quieter for good.
            if (released == heldGain) lowestGain else released
        }

        fadeSum += heldGain - fadeFrames[fadePosition]
        fadeFrames[fadePosition] = heldGain
        fadePosition = (fadePosition + 1) % lookaheadFrames
        return min(
            (fadeSum / lookaheadFrames).toFloat(),
            1f,
        )
    }

    fun reset() {
        requiredGains.reset()
        fadeFrames.fill(1f)
        fadePosition = 0
        fadeSum = lookaheadFrames.toDouble()
        heldGain = 1f
    }

    private companion object {
        const val RELEASE_SECONDS = 0.1f
    }
}

/** Tracks the minimum of the last [size] values in constant time per value. */
private class SlidingMinimum(private val size: Int) {
    private val values = FloatArray(size)
    private val indices = LongArray(size)
    private var first = 0
    private var count = 0
    private var nextIndex = 0L

    fun add(value: Float): Float {
        if (count > 0 && indices[first] <= nextIndex - size) {
            first = (first + 1) % size
            count--
        }
        while (count > 0 && values[(first + count - 1) % size] >= value) count--

        val last = (first + count) % size
        values[last] = value
        indices[last] = nextIndex++
        count++
        return values[first]
    }

    fun reset() {
        first = 0
        count = 0
        nextIndex = 0L
    }
}
