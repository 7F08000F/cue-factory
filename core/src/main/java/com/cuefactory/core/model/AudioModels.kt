package com.cuefactory.core.model

/**
 * Where a CUE was discovered for an audio file.
 */
enum class CueOrigin {
    /** Sidecar `.cue` next to audio (or path from match rules). */
    External,

    /** Vorbis comment `cuesheet=` / `CUESHEET`. */
    EmbeddedVorbis,

    /** Native FLAC METADATA_BLOCK_CUESHEET (type 5). */
    EmbeddedNative,
}

enum class AudioContainer {
    Flac,
    Wav,
    Ape,
    Other,
}

/**
 * A candidate whole-album audio file discovered by scan.
 */
data class AudioSource(
    val path: String,
    val fileName: String,
    val parentDir: String,
    val container: AudioContainer = AudioContainer.Flac,
    val sizeBytes: Long? = null,
    /** Sample rate if already probed; null until metadata read. */
    val sampleRate: Int? = null,
    val totalSamples: Long? = null,
)

/**
 * A CUE text source (path and/or embedded payload reference).
 */
data class CueSource(
    val origin: CueOrigin,
    /** Filesystem path for external CUE; for embedded may equal audio path. */
    val path: String,
    val displayName: String,
    /** Preloaded text if already extracted (e.g. from vorbis). */
    val preloadedText: String? = null,
    val charsetName: String? = null,
)
