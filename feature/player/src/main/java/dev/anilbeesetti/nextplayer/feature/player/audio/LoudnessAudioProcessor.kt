package dev.anilbeesetti.nextplayer.feature.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Applies [LoudnessNormalizer] to decoded audio.
 *
 * The processor stays active while [settings] are disabled and fades back to unity gain, because
 * Media3 only adds or removes processors when the audio sink is reconfigured.
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {

    /** Read from the playback thread, so a new value applies while audio is playing. */
    @Volatile
    var settings = LoudnessSettings()

    private var normalizer: LoudnessNormalizer? = null
    private var normalizerFormat = AudioFormat.NOT_SET
    private var samples = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val normalizer = normalizer ?: return
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        val frameCount = inputSize / inputAudioFormat.bytesPerFrame
        val sampleCount = frameCount * inputAudioFormat.channelCount
        if (samples.size < sampleCount) samples = FloatArray(sampleCount)

        for (index in 0 until sampleCount) samples[index] = inputBuffer.readSample()
        normalizer.process(
            samples = samples,
            frameCount = frameCount,
            settings = settings,
        )
        writeOutput(
            sampleCount = sampleCount,
            size = inputSize,
        )
    }

    /** Pushes silence through the delay line, so the end of the stream is not cut off. */
    override fun onQueueEndOfStream() {
        val normalizer = normalizer ?: return
        val frameCount = normalizer.latencyFrames
        val sampleCount = frameCount * inputAudioFormat.channelCount
        if (samples.size < sampleCount) samples = FloatArray(sampleCount)

        samples.fill(
            element = 0f,
            fromIndex = 0,
            toIndex = sampleCount,
        )
        normalizer.process(
            samples = samples,
            frameCount = frameCount,
            settings = settings,
        )
        writeOutput(
            sampleCount = sampleCount,
            size = frameCount * inputAudioFormat.bytesPerFrame,
        )
    }

    /** Runs on every seek too. The normalizer survives it, so the volume does not dip. */
    override fun onFlush(streamMetadata: StreamMetadata) {
        if (normalizer == null || normalizerFormat != inputAudioFormat) {
            normalizerFormat = inputAudioFormat
            normalizer = LoudnessNormalizer(
                sampleRate = inputAudioFormat.sampleRate,
                channelCount = inputAudioFormat.channelCount,
            )
        } else {
            normalizer?.clearCurrentAudio()
        }
    }

    private fun writeOutput(sampleCount: Int, size: Int) {
        val output = replaceOutputBuffer(size)
        for (index in 0 until sampleCount) output.writeSample(sample = samples[index])
        output.flip()
    }

    private fun ByteBuffer.readSample(): Float = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_FLOAT -> getFloat()
        else -> getShort() / PCM_16BIT_SCALE
    }

    private fun ByteBuffer.writeSample(sample: Float) {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> putFloat(sample)

            else -> {
                val scaled = (sample * PCM_16BIT_SCALE).roundToInt()
                putShort(
                    scaled.coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    ).toShort(),
                )
            }
        }
    }

    private companion object {
        const val PCM_16BIT_SCALE = 32768f
    }
}
