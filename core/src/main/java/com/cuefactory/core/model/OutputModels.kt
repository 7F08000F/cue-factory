package com.cuefactory.core.model

enum class FileConflictPolicy {
    /** Leave existing file; skip writing this track. */
    Skip,

    /** Append numeric suffix before extension. */
    Suffix,

    /** Overwrite only when user explicitly chose this policy. */
    Overwrite,
}

/**
 * Naming / path templates. `{title}` must be usable in file name template.
 */
data class OutputTemplate(
    /** e.g. `{album_artist}/{album}` under output root. */
    val directoryTemplate: String = "{album_artist}/{album}",
    /** e.g. `{track:02d}. {title}`. */
    val fileNameTemplate: String = "{track:02d}. {title}",
    val extension: String = "flac",
) {
    init {
        require(fileNameTemplate.contains("{title}")) {
            "fileNameTemplate must include {title}"
        }
    }
}

data class OutputSettings(
    val rootPath: String,
    val template: OutputTemplate = OutputTemplate(),
    val conflictPolicy: FileConflictPolicy = FileConflictPolicy.Skip,
)

/**
 * Variables available to template rendering (null → empty or fallback in renderer).
 */
data class TrackNameContext(
    val title: String,
    val track: Int,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val disc: Int? = null,
)
