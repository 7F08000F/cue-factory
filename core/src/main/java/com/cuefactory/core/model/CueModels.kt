package com.cuefactory.core.model

/**
 * CD sector time: MM:SS:FF where FF is 0..74 (75 sectors per second).
 */
data class CueTime(
    val minutes: Int,
    val seconds: Int,
    val frames: Int,
) {
    init {
        require(minutes >= 0) { "minutes must be >= 0" }
        require(seconds in 0..59) { "seconds must be 0..59" }
        require(frames in 0..74) { "frames must be 0..74" }
    }

    /** Absolute offset in CD sectors (1/75 s). */
    fun toSectors(): Long =
        minutes * 60L * 75L + seconds * 75L + frames

    /** Sample offset at [sampleRate] (typically 44100). */
    fun toSamples(sampleRate: Int = 44_100): Long {
        require(sampleRate > 0)
        // samples = sectors * sampleRate / 75
        return toSectors() * sampleRate / 75L
    }

    fun toMillis(sampleRate: Int = 44_100): Long =
        toSamples(sampleRate) * 1000L / sampleRate

    companion object {
        val ZERO = CueTime(0, 0, 0)

        fun fromSectors(sectors: Long): CueTime {
            require(sectors >= 0)
            val minutes = (sectors / (60 * 75)).toInt()
            val rem = (sectors % (60 * 75)).toInt()
            val seconds = rem / 75
            val frames = rem % 75
            return CueTime(minutes, seconds, frames)
        }
    }
}

data class CueIndex(
    /** Usually 0 (pregap) or 1 (start). */
    val number: Int,
    val time: CueTime,
)

data class CueTrack(
    /** 1-based track number from CUE. */
    val number: Int,
    val type: String = "AUDIO",
    val title: String? = null,
    val performer: String? = null,
    val songwriter: String? = null,
    val isrc: String? = null,
    val indexes: List<CueIndex> = emptyList(),
    /** Unparsed or non-standard REM / flags kept for diagnostics. */
    val rem: Map<String, String> = emptyMap(),
) {
    fun index(number: Int): CueIndex? = indexes.firstOrNull { it.number == number }

    /** Preferred play start: INDEX 01, else first index. */
    fun startIndex(): CueIndex? = index(1) ?: indexes.minByOrNull { it.number }
}

/**
 * One FILE section in a CUE (may contain multiple tracks).
 */
data class CueFile(
    val name: String,
    val fileType: String = "WAVE",
    val tracks: List<CueTrack> = emptyList(),
)

/**
 * Parsed CUE sheet (album-level metadata + files/tracks).
 */
data class CueSheet(
    val title: String? = null,
    val performer: String? = null,
    val songwriter: String? = null,
    val catalog: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val rem: Map<String, String> = emptyMap(),
    val files: List<CueFile> = emptyList(),
    /** Encoding used when decoding source bytes (if known). */
    val sourceCharset: String? = null,
) {
    val allTracks: List<CueTrack>
        get() = files.flatMap { it.tracks }

    val trackCount: Int get() = allTracks.size
}

sealed interface CueParseResult {
    data class Success(val sheet: CueSheet) : CueParseResult
    data class Failure(
        val reason: CueParseError,
        val message: String,
        val lineNumber: Int? = null,
    ) : CueParseResult
}

enum class CueParseError {
    EmptyInput,
    InvalidSyntax,
    InvalidTime,
    NoTracks,
    UnsupportedCharset,
    IoError,
}
