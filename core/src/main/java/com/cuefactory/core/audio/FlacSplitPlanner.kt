package com.cuefactory.core.audio

import com.cuefactory.core.model.CueSheet
import com.cuefactory.core.model.SampleRange
import com.cuefactory.core.model.SplitError
import com.cuefactory.core.model.SplitMethod

/**
 * Plans per-track split method.
 *
 * Best-practice policy (when [allowHybrid] is true, the product default):
 * - both ends on real frame boundaries → [SplitMethod.FrameCopy]
 * - otherwise → [SplitMethod.HybridBoundary] (partial edges re-encode, interior copy)
 * - hybrid disabled / reencode-only → [SplitMethod.LosslessReencode]
 *
 * Without [frameIndex], fixed-block streams use a cheap modulus pre-check;
 * variable-block streams never claim FrameCopy until an index proves boundaries.
 */
class FlacSplitPlanner {

    data class PlannedTrack(
        val trackIndex: Int,
        val trackNumber: Int,
        val title: String?,
        val range: SampleRange,
        val method: SplitMethod,
        val rejection: SplitError? = null,
        val note: String? = null,
    )

    data class Plan(
        val sampleRate: Int,
        val totalSamples: Long,
        val fixedBlockSize: Int?,
        val tracks: List<PlannedTrack>,
        val indexVerified: Boolean = false,
    ) {
        val frameCopyCount: Int
            get() = tracks.count { it.method == SplitMethod.FrameCopy && it.rejection == null }
        val hybridCount: Int
            get() = tracks.count { it.method == SplitMethod.HybridBoundary && it.rejection == null }
        val reencodeCount: Int
            get() = tracks.count { it.method == SplitMethod.LosslessReencode }
    }

    fun plan(
        sheet: CueSheet,
        streamInfo: FlacMetadataReader.StreamInfo,
        allowReencode: Boolean = true,
        allowHybrid: Boolean = true,
        frameIndex: FlacFrameIndex.Index? = null,
    ): Plan {
        val ranges = TrackRangeCalculator.ranges(
            sheet = sheet,
            totalSamples = streamInfo.totalSamples,
            sampleRate = streamInfo.sampleRate,
        )
        val fixed = streamInfo.minBlockSize
            .takeIf { it > 0 && it == streamInfo.maxBlockSize }

        val tracks = ranges.map { tr ->
            val aligned = isAligned(
                sampleStart = tr.range.startSample,
                sampleEnd = tr.range.endSample,
                fixedBlockSize = fixed,
                totalSamples = streamInfo.totalSamples,
                frameIndex = frameIndex,
            )
            when {
                aligned -> PlannedTrack(
                    trackIndex = tr.trackIndex,
                    trackNumber = tr.track.number,
                    title = tr.track.title,
                    range = tr.range,
                    method = SplitMethod.FrameCopy,
                    note = if (frameIndex != null) {
                        "frame-aligned (index verified)"
                    } else {
                        "frame-aligned (fixed-block heuristic)"
                    },
                )
                allowHybrid && allowReencode -> PlannedTrack(
                    trackIndex = tr.trackIndex,
                    trackNumber = tr.track.number,
                    title = tr.track.title,
                    range = tr.range,
                    method = SplitMethod.HybridBoundary,
                    note = "unaligned; hybrid = edge re-encode + interior frame copy",
                )
                allowReencode -> PlannedTrack(
                    trackIndex = tr.trackIndex,
                    trackNumber = tr.track.number,
                    title = tr.track.title,
                    range = tr.range,
                    method = SplitMethod.LosslessReencode,
                    note = "CUE boundary not on FLAC frame edge; lossless re-encode",
                )
                else -> PlannedTrack(
                    trackIndex = tr.trackIndex,
                    trackNumber = tr.track.number,
                    title = tr.track.title,
                    range = tr.range,
                    method = SplitMethod.FrameCopy,
                    rejection = SplitError.UnsafeFrameBoundary,
                    note = "unaligned and re-encode/hybrid disabled",
                )
            }
        }

        return Plan(
            sampleRate = streamInfo.sampleRate,
            totalSamples = streamInfo.totalSamples,
            fixedBlockSize = fixed,
            tracks = tracks,
            indexVerified = frameIndex != null,
        )
    }

    private fun isAligned(
        sampleStart: Long,
        sampleEnd: Long,
        fixedBlockSize: Int?,
        totalSamples: Long,
        frameIndex: FlacFrameIndex.Index?,
    ): Boolean {
        if (frameIndex != null) {
            return frameIndex.findBoundary(sampleStart) != null &&
                frameIndex.findBoundary(sampleEnd) != null
        }
        return isAlignedHeuristic(sampleStart, fixedBlockSize, totalSamples) &&
            isAlignedHeuristic(sampleEnd, fixedBlockSize, totalSamples)
    }

    private fun isAlignedHeuristic(sample: Long, fixedBlockSize: Int?, totalSamples: Long): Boolean {
        if (sample == 0L || sample == totalSamples) return true
        if (fixedBlockSize == null || fixedBlockSize <= 0) return false
        return sample % fixedBlockSize == 0L
    }
}
