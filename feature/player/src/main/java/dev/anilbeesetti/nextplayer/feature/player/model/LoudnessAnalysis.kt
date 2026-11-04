package dev.anilbeesetti.nextplayer.feature.player.model

/** Where the first pass of two-pass loudness normalization is for the playing track. */
enum class LoudnessAnalysisStatus {
    /** Nothing is analyzed: normalization is off, single pass was chosen, or there is no audio. */
    NONE,

    /** The track is being measured. Single pass runs until the measurement is done. */
    ANALYZING,

    /** The track was measured, so two pass is running. */
    MEASURED,

    /** Network streams are not measured, as that would download them in full. */
    UNAVAILABLE_FOR_STREAM,

    /** The track could not be measured, so single pass keeps running. */
    FAILED,
}

data class LoudnessAnalysis(
    val status: LoudnessAnalysisStatus = LoudnessAnalysisStatus.NONE,
    /** Share of the track measured so far, from 0 to 1, or null while it is unknown. */
    val progress: Float? = null,
    /** Integrated loudness of the track, once [status] is [LoudnessAnalysisStatus.MEASURED]. */
    val programLufs: Double? = null,
)
