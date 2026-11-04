package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class LoudnessNormalizationMode {
    /** Estimates the loudness while playing. Starts right away and works for any source. */
    SINGLE_PASS,

    /** Measures the whole track first, and runs single pass until the measurement is done. */
    TWO_PASS,
}
