package dev.anilbeesetti.nextplayer.feature.player.audio

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * First pass of two-pass normalization: measures the integrated loudness of a whole audio track.
 *
 * The track is decoded by a silent player that uses the same decoders as playback, so every format
 * that plays can be measured. It runs at background priority, so playback keeps the CPU it needs.
 * Results are cached per media item and track, as a full decode takes a while and drains the
 * battery.
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

    /** Returns the loudness of the [audioFormat] track if an earlier analysis measured it. */
    suspend fun cachedProgramLufs(mediaItem: MediaItem, audioFormat: Format): Double? = readCache(
        file = cacheFile(
            mediaId = mediaItem.mediaId,
            audioFormat = audioFormat,
        ),
    )

    /**
     * Measures the integrated loudness of the [audioFormat] track in LUFS, or returns null if it
     * could not be measured. [onProgress] is called on the main thread with the share measured so
     * far, from 0 to 1, or with null while the duration is unknown. Must be called on the main
     * thread, which owns the silent player.
     */
    suspend fun measureProgramLufs(
        mediaItem: MediaItem,
        audioFormat: Format,
        onProgress: (Float?) -> Unit = {},
    ): Double? {
        val programLufs = decodeAndMeasure(
            mediaItem = mediaItem,
            audioFormat = audioFormat,
            onProgress = onProgress,
        ) ?: return null
        writeCache(
            file = cacheFile(
                mediaId = mediaItem.mediaId,
                audioFormat = audioFormat,
            ),
            programLufs = programLufs,
        )
        return programLufs
    }

    private suspend fun readCache(file: File): Double? = withContext(Dispatchers.IO) {
        try {
            file.takeIf { it.exists() }?.readText()?.toDoubleOrNull()?.takeIf { it.isFinite() }
        } catch (_: IOException) {
            null
        }
    }

    /** Writes a temporary file and renames it, so a cut-off write is never read back. */
    private suspend fun writeCache(file: File, programLufs: Double) = withContext(Dispatchers.IO) {
        val partialFile = File(
            cacheDir,
            "${file.name}.tmp",
        )
        try {
            cacheDir.mkdirs()
            partialFile.writeText(programLufs.toString())
            if (!partialFile.renameTo(file)) partialFile.delete()
        } catch (_: IOException) {
            partialFile.delete()
        }
    }

    private suspend fun decodeAndMeasure(
        mediaItem: MediaItem,
        audioFormat: Format,
        onProgress: (Float?) -> Unit,
    ): Double? {
        val playbackThread = HandlerThread(
            PLAYBACK_THREAD_NAME,
            Process.THREAD_PRIORITY_BACKGROUND,
        ).apply { start() }
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
            .setPlaybackLooper(playbackThread.looper)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(
                C.TRACK_TYPE_TEXT,
                true,
            )
            .build()

        return try {
            coroutineScope {
                val measurement = async {
                    awaitMeasurement(
                        player = player,
                        sink = sink,
                        mediaItem = mediaItem,
                        audioFormat = audioFormat,
                    )
                }
                var lastMeasuredUs = -1L
                var stalledMillis = 0L
                while (withTimeoutOrNull(PROGRESS_INTERVAL_MILLIS) { measurement.join() } == null) {
                    val measuredUs = sink.measuredUs
                    stalledMillis = if (measuredUs == lastMeasuredUs) {
                        stalledMillis + PROGRESS_INTERVAL_MILLIS
                    } else {
                        0L
                    }
                    lastMeasuredUs = measuredUs
                    if (stalledMillis >= STALL_TIMEOUT_MILLIS) {
                        measurement.cancel()
                        return@coroutineScope null
                    }
                    onProgress(
                        player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let {
                            (measuredUs / 1000f / it).coerceIn(
                                0f,
                                1f,
                            )
                        },
                    )
                }
                measurement.await()
            }
        } finally {
            player.release()
            playbackThread.quitSafely()
        }
    }

    private suspend fun awaitMeasurement(
        player: ExoPlayer,
        sink: LoudnessMeasuringAudioSink,
        mediaItem: MediaItem,
        audioFormat: Format,
    ): Double? = suspendCancellableCoroutine { continuation ->
        // The end of stream arrives on the playback thread, errors on the main thread.
        val isFinished = AtomicBoolean(false)
        fun finish(programLufs: Double?) {
            if (isFinished.compareAndSet(
                    false,
                    true,
                )
            ) {
                continuation.resume(programLufs)
            }
        }

        // The player's own ended state is not reliable here: it waits for the position to reach
        // the duration, which video or a sink without a clock may never let happen.
        sink.onEndOfStream = { finish(programLufs = sink.programLufs) }
        player.addListener(
            object : Player.Listener {
                private var isTrackChosen = false

                override fun onTracksChanged(tracks: Tracks) {
                    if (isTrackChosen || tracks.isEmpty) return
                    isTrackChosen = true
                    // Measuring a different track would cache a wrong level for this one.
                    if (!player.selectAudioTrack(
                            tracks = tracks,
                            format = audioFormat,
                        )
                    ) {
                        finish(programLufs = null)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Reached without an end of stream when no audio could be decoded at all.
                    if (playbackState == Player.STATE_ENDED) finish(programLufs = sink.programLufs)
                }

                override fun onPlayerError(error: PlaybackException) {
                    finish(programLufs = null)
                }
            },
        )
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    /**
     * Selects the track with [format], restarting from the beginning if the player picked another
     * one by default. Returns false if the track is not there.
     */
    private fun ExoPlayer.selectAudioTrack(tracks: Tracks, format: Format): Boolean {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            val trackIndex = (0 until group.length).firstOrNull {
                group.getTrackFormat(it).isSameTrackAs(other = format)
            } ?: continue
            if (group.isTrackSelected(trackIndex)) return true

            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(
                        group.mediaTrackGroup,
                        trackIndex,
                    ),
                )
                .build()
            seekTo(0)
            return true
        }
        return false
    }

    private fun Format.isSameTrackAs(other: Format): Boolean = id == other.id &&
        sampleMimeType == other.sampleMimeType &&
        channelCount == other.channelCount &&
        sampleRate == other.sampleRate &&
        language == other.language

    /** Identifies the track by its media and format, so a different track never shares a result. */
    private fun cacheFile(mediaId: String, audioFormat: Format): File {
        val key = listOf(
            CACHE_VERSION,
            mediaId,
            audioFormat.id,
            audioFormat.sampleMimeType,
            audioFormat.codecs,
            audioFormat.channelCount,
            audioFormat.sampleRate,
            audioFormat.bitrate,
            audioFormat.language,
        ).joinToString(separator = "|")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(
            cacheDir,
            digest.joinToString(separator = "") { "%02x".format(it) },
        )
    }

    private companion object {
        const val CACHE_DIR_NAME = "loudness"

        // Raise when the measurement changes, so results of an older version are not used.
        const val CACHE_VERSION = 2

        const val PLAYBACK_THREAD_NAME = "LoudnessAnalyzer"
        const val MIN_BUFFER_MILLIS = 15_000
        const val MAX_BUFFER_MILLIS = 30_000
        const val PROGRESS_INTERVAL_MILLIS = 500L

        // Gives up when decoding stops moving, instead of analyzing forever.
        const val STALL_TIMEOUT_MILLIS = 30_000L
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
 * [sink] only answers format queries, so no audio output is ever opened. Everything but
 * [measuredUs] is used on the playback thread only.
 */
@UnstableApi
private class LoudnessMeasuringAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var loudness = IntegratedLoudness()
    private var meter: LoudnessMeter? = null
    private var format: Format? = null
    private var samples = FloatArray(0)
    private var shortSamples = ShortArray(0)
    private var positionUs = 0L
    private var isEnded = false

    // Held in a property, so the playback thread allocates nothing per buffer.
    private val addBlock: (Double) -> Unit = { loudness.addBlock(energy = it) }

    /** Duration of the audio measured so far, read on the main thread to report progress. */
    @Volatile
    var measuredUs = 0L
        private set

    /** Called on the playback thread once all decoded audio was measured. */
    var onEndOfStream: () -> Unit = {}

    /** The measured loudness, or null if nothing loud enough was measured. */
    val programLufs: Double?
        get() = loudness.lufs

    override fun getFormatSupport(format: Format): Int = if (format.isMeasurable()) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else {
        AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun supportsFormat(format: Format): Boolean = format.isMeasurable()

    /** A new format in the middle of the track keeps what was measured with the old one. */
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val newFormat = audioSinkConfig.format
        val previousFormat = format
        format = newFormat
        if (previousFormat != null && previousFormat.sampleRate == newFormat.sampleRate &&
            previousFormat.channelCount == newFormat.channelCount
        ) {
            return
        }
        meter = LoudnessMeter(
            sampleRate = newFormat.sampleRate,
            channelCount = newFormat.channelCount,
        )
        loudness.clearCurrentAudio()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val format = format ?: return false
        val meter = meter ?: return false
        val sampleCount = buffer.remaining() / format.pcmEncoding.bytesPerSample()
        if (samples.size < sampleCount) samples = FloatArray(sampleCount)
        buffer.order(ByteOrder.nativeOrder()).readSamples(
            encoding = format.pcmEncoding,
            count = sampleCount,
        )
        val frameCount = sampleCount / format.channelCount
        meter.addFrames(
            samples = samples,
            frameCount = frameCount,
            onBlock = addBlock,
        )
        measuredUs += frameCount * C.MICROS_PER_SECOND / format.sampleRate
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

    /** Runs before playback and when switching to the right track, which restarts the track. */
    override fun flush() {
        isEnded = false
        loudness = IntegratedLoudness()
        meter?.clearCurrentAudio()
        measuredUs = 0L
    }

    override fun play() = Unit

    override fun pause() = Unit

    override fun reset() = Unit

    private fun Format.isMeasurable(): Boolean = pcmEncoding in MEASURABLE_ENCODINGS

    private fun Int.bytesPerSample(): Int = when (this) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        else -> 4
    }

    /**
     * Reads all [count] samples into [samples]. The usual encodings are copied in bulk, which is
     * much faster than reading sample by sample.
     */
    private fun ByteBuffer.readSamples(encoding: Int, count: Int) {
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> asFloatBuffer().get(
                samples,
                0,
                count,
            )

            C.ENCODING_PCM_16BIT -> {
                if (shortSamples.size < count) shortSamples = ShortArray(count)
                asShortBuffer().get(
                    shortSamples,
                    0,
                    count,
                )
                for (index in 0 until count) samples[index] = shortSamples[index] / PCM_16BIT_SCALE
            }

            C.ENCODING_PCM_32BIT ->
                for (index in 0 until count) samples[index] = getInt() / PCM_32BIT_SCALE

            else -> for (index in 0 until count) {
                val low = get().toInt() and BYTE_MASK
                val middle = get().toInt() and BYTE_MASK
                val high = get().toInt()
                samples[index] = ((high shl 16) or (middle shl 8) or low) / PCM_24BIT_SCALE
            }
        }
        // The bulk copies read through a view, which leaves the position of the buffer behind.
        position(limit())
    }

    private companion object {
        val MEASURABLE_ENCODINGS = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
        const val PCM_16BIT_SCALE = 32768f
        const val PCM_24BIT_SCALE = 8388608f
        const val PCM_32BIT_SCALE = 2147483648f
        const val BYTE_MASK = 0xFF
    }
}
