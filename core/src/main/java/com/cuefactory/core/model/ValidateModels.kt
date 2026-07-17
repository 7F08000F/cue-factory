package com.cuefactory.core.model

enum class ValidateStatus {
    Passed,
    Failed,
    Skipped,
}

data class TrackValidation(
    val trackIndex: Int,
    val trackNumber: Int,
    val outputPath: String,
    val status: ValidateStatus,
    val expectedDurationMs: Long?,
    val actualDurationMs: Long?,
    val decodeOk: Boolean,
    val splitMethod: SplitMethod?,
    val message: String? = null,
)

data class AlbumValidationReport(
    val albumPath: String,
    val tracks: List<TrackValidation>,
) {
    val allPassed: Boolean
        get() = tracks.isNotEmpty() && tracks.all { it.status == ValidateStatus.Passed }
}
