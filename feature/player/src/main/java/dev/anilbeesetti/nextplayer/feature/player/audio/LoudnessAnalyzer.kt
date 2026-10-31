package dev.anilbeesetti.nextplayer.feature.player.audio

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * First pass of two-pass normalization: measures the loudness of a whole audio track.
 *
 * The track is decoded by a silent player that uses the same decoders as playback, so every format
 * that plays can be measured. Results are cached per media item and track, as a full decode takes
 * a while and drains the battery.
 */
@UnstableApi
internal class LoudnessAnalyzer(
    private val context: Context,
    private val createMediaSourceFactory: () -> MediaSource.Factory,
) {
    private val cacheDir = File(
        context.cacheDir,
        CACHE_DIR_NAME,
    )

    /** Returns the program loudness in LUFS, or null if the track could not be measured. */
    suspend fun measureProgramLufs(mediaItem: MediaItem, audioFormatId: String?): Double? {
        val cacheFile = File(
            cacheDir,
            cacheName(
                mediaId = mediaItem.mediaId,
                audioFormatId = audioFormatId,
            ),
        )
        withContext(Dispatchers.IO) {
            cacheFile.takeIf { it.exists() }?.readText()?.toDoubleOrNull()
        }
            ?.let { return it }

        val programLufs = decodeAndMeasure(
            mediaItem = mediaItem,
            audioFormatId = audioFormatId,
        )
        if (programLufs != null) {
            withContext(Dispatchers.IO) {
                cacheDir.mkdirs()
                cacheFile.writeText(programLufs.toString())
            }
        }
        return programLufs
    }

    /** Must be called on the main thread, which owns the silent player. */
    private suspend fun decodeAndMeasure(mediaItem: MediaItem, audioFormatId: String?): Double? {
        val sink = LoudnessMeasuringAudioSink(DefaultAudioSink.Builder(context).build())
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(
                AudioOnlyRenderersFactory(
                    context,
                    sink,
                ),
            )
            .setMediaSourceFactory(createMediaSourceFactory())
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MILLIS,
                        MAX_BUFFER_MILLIS,
                        0,
                        0,
                    )
                    .build(),
            )
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(
                C.TRACK_TYPE_TEXT,
                true,
            )
            .build()

        return try {
            suspendCancellableCoroutine { continuation ->
                // The player's own ended state is not reliable here: it waits for the position to
                // reach the duration, which video or a sink without a clock may never let happen.
                sink.onEndOfStream = {
                    if (continuation.isActive) continuation.resume(sink.programLufs)
                }
                player.addListener(
                    object : Player.Listener {
                        private var isTrackChosen = false

                        override fun onTracksChanged(tracks: Tracks) {
                            if (isTrackChosen || tracks.isEmpty) return
                            isTrackChosen = true
                            player.selectAudioTrack(
                                tracks = tracks,
                                formatId = audioFormatId,
                            )
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
        } finally {
            player.release()
        }
    }

    /** Restarts on the track being played if the player picked a different one by default. */
    private fun ExoPlayer.selectAudioTrack(tracks: Tracks, formatId: String?) {
        val group = tracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_AUDIO && group.mediaTrackGroup.getFormat(0).id == formatId
        } ?: return
        if (group.isSelected) return

        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(
                    group.mediaTrackGroup,
                    0,
                ),
            )
            .build()
        seekTo(0)
    }

    private fun cacheName(mediaId: String, audioFormatId: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$mediaId|$audioFormatId".toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val CACHE_DIR_NAME = "loudness"
        const val MIN_BUFFER_MILLIS = 15_000
        const val MAX_BUFFER_MILLIS = 30_000
    }
}

/** Renders nothing but audio, into [audioSink]. */
@UnstableApi
private class AudioOnlyRenderersFactory(context: Context, private val audioSink: AudioSink) :
    NextRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = audioSink

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) = Unit
}

/**
 * Measures decoded audio instead of playing it, as fast as it arrives.
 *
 * [sink] only answers format queries, so no audio output is ever opened.
 */
@UnstableApi
private class LoudnessMeasuringAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile
    private var meter: LoudnessMeter? = null
    private var format: Format? = null
    private var samples = FloatArray(0)
    private var positionUs = 0L
    private var isEnded = false

    /** Called on the playback thread once all decoded audio was measured. */
    var onEndOfStream: () -> Unit = {}

    /** The measured loudness, or null if nothing could be measured. */
    val programLufs: Double?
        get() = meter?.programLufs?.takeIf { it.isFinite() }

    override fun getFormatSupport(format: Format): Int = if (format.isMeasurable()) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else {
        AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun supportsFormat(format: Format): Boolean = format.isMeasurable()

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        format = audioSinkConfig.format
        resetMeter()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val format = format ?: return false
        val meter = meter ?: return false
        val isFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) Float.SIZE_BYTES else Short.SIZE_BYTES
        val sampleCount = buffer.remaining() / bytesPerSample
        if (samples.size < sampleCount) samples = FloatArray(sampleCount)

        val input = buffer.order(ByteOrder.nativeOrder())
        for (index in 0 until sampleCount) {
            samples[index] = if (isFloat) input.getFloat() else input.getShort() / PCM_16BIT_SCALE
        }
        for (frame in 0 until sampleCount / format.channelCount) {
            meter.addFrame(
                samples = samples,
                offset = frame * format.channelCount,
            )
        }
        positionUs = presentationTimeUs
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUs

    override fun playToEndOfStream() {
        if (!isEnded) onEndOfStream()
        isEnded = true
    }

    override fun isEnded(): Boolean = isEnded

    override fun hasPendingData(): Boolean = !isEnded

    override fun flush() {
        isEnded = false
        resetMeter()
    }

    override fun play() = Unit

    override fun pause() = Unit

    override fun reset() = Unit

    private fun resetMeter() {
        val format = format ?: return
        meter = LoudnessMeter(
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            programMemoryBlocks = LoudnessMeter.WHOLE_TRACK,
        )
    }

    private fun Format.isMeasurable(): Boolean =
        pcmEncoding == C.ENCODING_PCM_16BIT || pcmEncoding == C.ENCODING_PCM_FLOAT

    private companion object {
        const val PCM_16BIT_SCALE = 32768f
    }
}
