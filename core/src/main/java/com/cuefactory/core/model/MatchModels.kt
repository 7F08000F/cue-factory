package com.cuefactory.core.model

/**
 * Why an automatic match was accepted (precise rules only).
 */
enum class MatchRule {
    Basename,
    CueFileDirective,
    UniquePairInDirectory,
    EmbeddedCue,
    UserConfirmed,
}

/**
 * A precise or user-confirmed pairing ready for the job queue.
 */
data class MatchedAlbum(
    val audio: AudioSource,
    val cue: CueSource,
    val sheet: CueSheet,
    val rule: MatchRule,
)

/**
 * Ambiguous set that must not auto-start a split.
 */
data class AmbiguousMatch(
    val audioCandidates: List<AudioSource>,
    val cueCandidates: List<CueSource>,
    val message: String,
)

/**
 * Scan item that has audio but no usable CUE yet.
 */
data class UnmatchedAudio(
    val audio: AudioSource,
    val reason: String,
)

sealed interface MatchOutcome {
    data class Matched(val album: MatchedAlbum) : MatchOutcome
    data class Ambiguous(val ambiguity: AmbiguousMatch) : MatchOutcome
    data class Unmatched(val item: UnmatchedAudio) : MatchOutcome
}

/**
 * Aggregate result of running the precise match engine on a scan.
 */
data class MatchReport(
    val matched: List<MatchedAlbum> = emptyList(),
    val ambiguous: List<AmbiguousMatch> = emptyList(),
    val unmatched: List<UnmatchedAudio> = emptyList(),
)
