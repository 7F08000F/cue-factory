package com.cuefactory.core.model

enum class JobState {
    Pending,
    Running,
    Cancelling,
    Succeeded,
    PartialSuccess,
    Failed,
    Cancelled,
}

/**
 * One queue unit: typically one matched album to split.
 */
data class SplitJob(
    val id: String,
    val album: MatchedAlbum,
    val state: JobState = JobState.Pending,
    /** 0.0 .. 1.0 overall progress when known. */
    val progress: Float = 0f,
    val completedTracks: Int = 0,
    val totalTracks: Int = album.sheet.trackCount,
    val message: String? = null,
    val result: AlbumSplitResult? = null,
)

/**
 * Runtime concurrency knobs (user-adjustable).
 */
data class ConcurrencyConfig(
    /** How many album jobs run at once. */
    val jobParallelism: Int = 1,
    /** Worker threads for CPU/IO bound split work. */
    val workerThreads: Int = 2,
) {
    init {
        require(jobParallelism in 1..16) { "jobParallelism must be 1..16" }
        require(workerThreads in 1..32) { "workerThreads must be 1..32" }
    }

    companion object {
        /** Prefer stability over peak throughput (mobile + large ffmpeg). */
        val Balanced = ConcurrencyConfig(jobParallelism = 1, workerThreads = 1)
        val Performance = ConcurrencyConfig(jobParallelism = 1, workerThreads = 2)
    }
}

/**
 * Split policy on the hybrid-only branch.
 *
 * Only [Hybrid] is supported:
 * - frame-aligned → [SplitMethod.FrameCopy]
 * - unaligned → [SplitMethod.HybridBoundary]
 * - hybrid failure → full [SplitMethod.LosslessReencode]
 *
 * Legacy names [Auto]/[AlwaysReencode]/[FrameCopyOnly] remain as aliases so
 * older prefs/smoke extras still deserialize without crashing; they all map to
 * Hybrid behavior at runtime.
 */
enum class SplitMode {
    /** Sole supported policy on this branch (see class KDoc). */
    Hybrid,

    /** @deprecated Alias of [Hybrid] for prefs compatibility. */
    Auto,

    /** @deprecated Alias of [Hybrid] — full reencode-only is not offered on this branch. */
    AlwaysReencode,

    /** @deprecated Alias of [Hybrid] — pure FrameCopyOnly is not offered on this branch. */
    FrameCopyOnly,

    ;

    /** Canonical policy used by the queue (always Hybrid on this branch). */
    fun effective(): SplitMode = Hybrid
}
