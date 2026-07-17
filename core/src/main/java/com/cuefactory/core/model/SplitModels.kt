package com.cuefactory.core.model

enum class SplitMethod {
    /** FLAC frame-level copy without re-encoding audio samples. */
    FrameCopy,

    /**
     * Decode + FLAC re-encode at exact sample range.
     * Still lossless PCM; used when CUE boundaries are not frame-aligned.
     * Not a lossy transcode.
     */
    LosslessReencode,

    /**
     * Exact sample range: re-encode only leading/trailing partial frames,
     * stream-copy fully interior frames. Best practice for unaligned CUE cuts.
     */
    HybridBoundary,
}

enum class SplitError {
    UnsafeFrameBoundary,
    InvalidRange,
    IoError,
    EncoderError,
    Cancelled,
    UnsupportedContainer,
    MissingMetadata,
    Unknown,
}

/**
 * Inclusive-exclusive sample range for one output track.
 */
data class SampleRange(
    val startSample: Long,
    val endSample: Long,
) {
    init {
        require(startSample >= 0) { "startSample must be >= 0" }
        require(endSample >= startSample) { "endSample must be >= startSample" }
    }

    val lengthSamples: Long get() = endSample - startSample

    fun durationMs(sampleRate: Int): Long {
        require(sampleRate > 0)
        return lengthSamples * 1000L / sampleRate
    }
}

sealed interface SplitOutcome {
    data class Success(
        val trackIndex: Int,
        val trackNumber: Int,
        val outputPath: String,
        val durationMs: Long,
        val method: SplitMethod,
        val range: SampleRange,
    ) : SplitOutcome

    data class Failure(
        val trackIndex: Int,
        val trackNumber: Int?,
        val reason: SplitError,
        val message: String,
    ) : SplitOutcome
}

data class AlbumSplitResult(
    val album: MatchedAlbum,
    val outcomes: List<SplitOutcome>,
) {
    val successes: List<SplitOutcome.Success>
        get() = outcomes.filterIsInstance<SplitOutcome.Success>()

    val failures: List<SplitOutcome.Failure>
        get() = outcomes.filterIsInstance<SplitOutcome.Failure>()

    val isFullSuccess: Boolean
        get() = outcomes.isNotEmpty() && failures.isEmpty()
}
