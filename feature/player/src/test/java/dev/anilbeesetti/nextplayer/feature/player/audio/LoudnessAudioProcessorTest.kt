package dev.anilbeesetti.nextplayer.feature.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessAudioProcessorTest {
    private val format = AudioFormat(
        48000,
        2,
        C.ENCODING_PCM_16BIT,
    )

    @Test
    fun gainSurvivesResetAndFlush() {
        val processor = LoudnessAudioProcessor()
        processor.settings = LoudnessSettings(
            enabled = true,
            targetLufs = -14f,
            loudnessRangeLu = 7f,
            peakLimitDb = -1f,
        )
        processor.configure(format)
        processor.flush(StreamMetadata.DEFAULT)
        val settledPeak = (0 until 300).maxOf { block ->
            val peak = processor.peakOfNextBlock()
            if (block > 290) peak else 0
        }

        processor.reset()
        processor.configure(format)
        processor.flush(StreamMetadata.DEFAULT)
        processor.peakOfNextBlock()

        assertTrue(processor.peakOfNextBlock() > settledPeak * 0.8)
    }

    /** Sends 100 ms of a quiet 1 kHz sine and returns the output's highest sample. */
    private fun LoudnessAudioProcessor.peakOfNextBlock(): Int {
        val frames = 4800
        val input = ByteBuffer.allocateDirect(frames * format.bytesPerFrame)
            .order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val sample = (300 * sin(2 * PI * 1000 * frame / 48000)).toInt().toShort()
            input.putShort(sample)
            input.putShort(sample)
        }
        input.flip()
        queueInput(input)
        val output = getOutput().order(ByteOrder.nativeOrder()).asShortBuffer()
        return (0 until output.remaining()).maxOf { abs(output[it].toInt()) }
    }

    @Test
    fun rejectsEncodingsItCannotConvert() {
        val processor = LoudnessAudioProcessor()

        val output = processor.configure(
            AudioFormat(
                48000,
                2,
                C.ENCODING_PCM_24BIT,
            ),
        )

        assertEquals(
            AudioFormat.NOT_SET,
            output,
        )
        assertFalse(processor.isActive)
    }

    @Test
    fun endOfStreamDrainsTheDelayLine() {
        val processor = LoudnessAudioProcessor()
        processor.configure(format)
        processor.flush(StreamMetadata.DEFAULT)
        val frames = 4800
        val input = ByteBuffer.allocateDirect(frames * format.bytesPerFrame)
            .order(ByteOrder.nativeOrder())

        processor.queueInput(input)
        val outputBytes = processor.getOutput().remaining()
        processor.queueEndOfStream()
        val tailBytes = processor.getOutput().remaining()

        assertEquals(
            input.capacity(),
            outputBytes,
        )
        assertEquals(
            48000 * 5 / 1000 * format.bytesPerFrame,
            tailBytes,
        )
        assertTrue(processor.isEnded)
    }
}
