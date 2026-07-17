package com.cuefactory.core.audio

import com.cuefactory.core.model.SampleRange
import com.cuefactory.core.model.SplitError
import com.cuefactory.core.model.SplitMethod
import com.cuefactory.core.model.SplitOutcome
import java.io.File

/**
 * Best-practice exact splitter for unaligned CUE ranges:
 * - leading/trailing partial frames: lossless re-encode (ffmpeg atrim)
 * - fully interior frames: zero-reencode byte copy
 *
 * PCM sample count matches the CUE range. When there are no interior full
 * frames, falls through to full-range re-encode (same cost as pure reencode).
 */
class FlacHybridSplitter(
    private val indexBuilder: FlacFrameIndex = FlacFrameIndex(),
    private val frameCopy: FlacFrameCopySplitter = FlacFrameCopySplitter(indexBuilder),
    private val reencoder: FfmpegFlacSplitter = FfmpegFlacSplitter(),
) {
    data class TrackRequest(
        val trackIndex: Int,
        val trackNumber: Int,
        val range: SampleRange,
        val outputFile: File,
        val sampleRate: Int,
        val metadata: Map<String, String> = emptyMap(),
    )

    fun split(
        sourceFlac: File,
        request: TrackRequest,
        prebuiltIndex: FlacFrameIndex.Index? = null,
    ): SplitOutcome {
        if (!sourceFlac.isFile) {
            return fail(request, SplitError.IoError, "Source missing: ${sourceFlac.path}")
        }
        request.outputFile.parentFile?.mkdirs()

        return try {
            val index = prebuiltIndex ?: indexBuilder.build(sourceFlac)
            val start = request.range.startSample
            val end = request.range.endSample
            if (end < start) {
                return fail(request, SplitError.InvalidRange, "empty/negative range")
            }

            // Fully aligned → pure frame copy.
            if (index.findBoundary(start) != null && index.findBoundary(end) != null) {
                return frameCopy.split(
                    sourceFlac,
                    FlacFrameCopySplitter.TrackRequest(
                        trackIndex = request.trackIndex,
                        trackNumber = request.trackNumber,
                        range = request.range,
                        outputFile = request.outputFile,
                        sampleRate = request.sampleRate,
                        metadata = request.metadata,
                    ),
                    prebuiltIndex = index,
                )
            }

            val plan = planHybrid(index, start, end)
                ?: return fail(
                    request,
                    SplitError.UnsafeFrameBoundary,
                    "Hybrid plan unavailable for ${start}..$end",
                )

            // No full interior frames → full-range reencode (no hybrid benefit).
            if (plan.middleFrames.isEmpty()) {
                return reencoder.splitLossless(
                    sourceFlac,
                    FfmpegFlacSplitter.TrackRequest(
                        trackIndex = request.trackIndex,
                        trackNumber = request.trackNumber,
                        range = request.range,
                        outputFile = request.outputFile,
                        sampleRate = request.sampleRate,
                        metadata = request.metadata,
                    ),
                )
            }

            val workDir = File(
                request.outputFile.parentFile,
                ".hybrid-${request.trackNumber}-${System.nanoTime()}",
            )
            workDir.mkdirs()
            try {
                var leadFile: File? = null
                var trailFile: File? = null

                plan.leading?.let { lead ->
                    val f = File(workDir, "lead.flac")
                    val r = reencodeEdge(sourceFlac, request, lead, f, index)
                    if (r !is SplitOutcome.Success) return r
                    leadFile = f
                }
                plan.trailing?.let { trail ->
                    val f = File(workDir, "trail.flac")
                    val r = reencodeEdge(sourceFlac, request, trail, f, index)
                    if (r !is SplitOutcome.Success) return r
                    trailFile = f
                }

                val tmp = File(request.outputFile.parentFile, request.outputFile.name + ".partial")
                if (tmp.exists()) tmp.delete()
                stitchHybrid(
                    sourceFlac = sourceFlac,
                    streamInfo = index.streamInfo,
                    leadFile = leadFile,
                    middleFrames = plan.middleFrames,
                    trailFile = trailFile,
                    totalSamples = request.range.lengthSamples,
                    metadata = request.metadata,
                    output = tmp,
                )

                val written = FlacMetadataReader().read(tmp).streamInfo
                if (written.totalSamples != request.range.lengthSamples) {
                    tmp.delete()
                    return fail(
                        request,
                        SplitError.EncoderError,
                        "Hybrid sample count mismatch: expected ${request.range.lengthSamples} got ${written.totalSamples}",
                    )
                }
                if (written.sampleRate != index.streamInfo.sampleRate ||
                    written.bitsPerSample != index.streamInfo.bitsPerSample
                ) {
                    tmp.delete()
                    return fail(request, SplitError.EncoderError, "Hybrid stream params mismatch")
                }

                if (request.outputFile.exists()) request.outputFile.delete()
                if (!tmp.renameTo(request.outputFile)) {
                    tmp.copyTo(request.outputFile, overwrite = true)
                    tmp.delete()
                }

                SplitOutcome.Success(
                    trackIndex = request.trackIndex,
                    trackNumber = request.trackNumber,
                    outputPath = request.outputFile.absolutePath,
                    durationMs = request.range.durationMs(request.sampleRate),
                    method = SplitMethod.HybridBoundary,
                    range = request.range,
                )
            } finally {
                workDir.deleteRecursively()
            }
        } catch (e: Exception) {
            request.outputFile.delete()
            File(request.outputFile.parentFile, request.outputFile.name + ".partial").delete()
            fail(request, SplitError.EncoderError, e.message ?: e.toString())
        }
    }

    data class HybridPlan(
        val leading: SampleRange?,
        val middleFrames: List<FlacFrameIndex.Frame>,
        val trailing: SampleRange?,
    )

    fun planHybrid(
        index: FlacFrameIndex.Index,
        start: Long,
        end: Long,
    ): HybridPlan? {
        if (end < start) return null
        if (start == end) return HybridPlan(null, emptyList(), null)
        val frames = index.frames
        if (frames.isEmpty()) return null
        if (start < 0 || end > index.totalSamplesFromFrames) return null

        val firstIdx = frames.indexOfFirst { start < it.endSample }
        if (firstIdx < 0) return null
        val lastIdx = frames.indexOfLast { it.startSample < end }
        if (lastIdx < 0 || lastIdx < firstIdx) return null

        val first = frames[firstIdx]
        val last = frames[lastIdx]

        val leading = if (start > first.startSample) {
            SampleRange(start, minOf(end, first.endSample)).takeIf { it.lengthSamples > 0 }
        } else {
            null
        }
        val trailing = if (end < last.endSample && lastIdx != firstIdx) {
            SampleRange(maxOf(start, last.startSample), end).takeIf { it.lengthSamples > 0 }
        } else if (end < last.endSample && lastIdx == firstIdx) {
            // Entire range inside one frame — no separate trailing; leading covers it.
            null
        } else {
            null
        }

        // Single-frame partial range: only leading covers [start,end]
        if (firstIdx == lastIdx && leading == null && start == first.startSample && end < first.endSample) {
            return HybridPlan(
                leading = SampleRange(start, end),
                middleFrames = emptyList(),
                trailing = null,
            )
        }
        if (firstIdx == lastIdx && start > first.startSample) {
            return HybridPlan(
                leading = SampleRange(start, end),
                middleFrames = emptyList(),
                trailing = null,
            )
        }

        val midFrom = if (leading != null) firstIdx + 1 else firstIdx
        val midToExclusive = if (trailing != null) lastIdx else lastIdx + 1
        val middle = if (midFrom < midToExclusive) {
            frames.subList(midFrom, midToExclusive)
        } else {
            emptyList()
        }

        return HybridPlan(leading, middle, trailing)
    }

    /**
     * Re-encode a short edge range by first remuxing only the covering frame(s)
     * into a tiny FLAC, then atrimming — avoids decoding the whole album for each edge.
     */
    private fun reencodeEdge(
        sourceFlac: File,
        request: TrackRequest,
        range: SampleRange,
        output: File,
        index: FlacFrameIndex.Index,
    ): SplitOutcome {
        val window = coveringFrames(index, range)
            ?: return reencoder.splitLossless(
                sourceFlac,
                FfmpegFlacSplitter.TrackRequest(
                    trackIndex = request.trackIndex,
                    trackNumber = request.trackNumber,
                    range = range,
                    outputFile = output,
                    sampleRate = request.sampleRate,
                    metadata = emptyMap(),
                ),
            )
        return reencoder.splitLossless(
            sourceFlac,
            FfmpegFlacSplitter.TrackRequest(
                trackIndex = request.trackIndex,
                trackNumber = request.trackNumber,
                range = range,
                outputFile = output,
                sampleRate = request.sampleRate,
                metadata = emptyMap(),
            ),
            sourceWindow = window,
        )
    }

    private fun coveringFrames(
        index: FlacFrameIndex.Index,
        range: SampleRange,
    ): FfmpegFlacSplitter.SourceWindow? {
        val frames = index.frames
        if (frames.isEmpty()) return null
        val firstIdx = frames.indexOfFirst { range.startSample < it.endSample }
        val lastIdx = frames.indexOfLast { it.startSample < range.endSample }
        if (firstIdx < 0 || lastIdx < firstIdx) return null
        val slice = frames.subList(firstIdx, lastIdx + 1)
        return FfmpegFlacSplitter.SourceWindow(
            frames = slice,
            windowStartSample = slice.first().startSample,
        )
    }

    /**
     * Write final track in one pass: optional lead (tiny reencode) +
     * renumbered middle frames from source + optional trail.
     * Avoids building a full mid.flac and re-indexing it.
     */
    private fun stitchHybrid(
        sourceFlac: File,
        streamInfo: FlacMetadataReader.StreamInfo,
        leadFile: File?,
        middleFrames: List<FlacFrameIndex.Frame>,
        trailFile: File?,
        totalSamples: Long,
        metadata: Map<String, String>,
        output: File,
    ) {
        var minBlock = Int.MAX_VALUE
        var maxBlock = 0
        fun consider(minB: Int, maxB: Int) {
            if (minB > 0) minBlock = minOf(minBlock, minB)
            if (maxB > 0) maxBlock = maxOf(maxBlock, maxB)
        }
        leadFile?.let {
            val m = FlacMetadataReader().read(it).streamInfo
            consider(m.minBlockSize, m.maxBlockSize)
        }
        if (middleFrames.isNotEmpty()) {
            consider(middleFrames.minOf { it.sampleCount }, middleFrames.maxOf { it.sampleCount })
        }
        trailFile?.let {
            val m = FlacMetadataReader().read(it).streamInfo
            consider(m.minBlockSize, m.maxBlockSize)
        }
        if (minBlock == Int.MAX_VALUE) minBlock = streamInfo.minBlockSize.coerceAtLeast(1)
        maxBlock = maxBlock.coerceAtLeast(minBlock)

        FlacRemuxWriter.writeContainer(
            output = output,
            streamInfo = streamInfo,
            totalSamples = totalSamples,
            minBlockSize = minBlock,
            maxBlockSize = maxBlock,
            minFrameSize = 0,
            maxFrameSize = 0,
            metadata = metadata,
            vendor = "CueFactory-Hybrid",
        ) { out ->
            var sampleCursor = 0L
            var frameCursor = 0

            leadFile?.let { lead ->
                val (nFrames, nSamples) = FlacFrameRenumber.copyFileAudioRenumbered(
                    source = lead,
                    out = out,
                    baseSample = sampleCursor,
                    baseFrameIndex = frameCursor,
                )
                sampleCursor += nSamples
                frameCursor += nFrames
            }

            if (middleFrames.isNotEmpty()) {
                // Always variable sample numbers: lead/trail edges may use a different
                // block size than the source middle frames.
                val (nFrames, nSamples) = FlacFrameRenumber.copyRenumbered(
                    source = sourceFlac,
                    frames = middleFrames,
                    out = out,
                    baseSample = sampleCursor,
                    baseFrameIndex = frameCursor,
                    mode = FlacFrameRenumber.Mode.VariableSampleNumber,
                )
                sampleCursor += nSamples
                frameCursor += nFrames
            }

            trailFile?.let { trail ->
                val (nFrames, nSamples) = FlacFrameRenumber.copyFileAudioRenumbered(
                    source = trail,
                    out = out,
                    baseSample = sampleCursor,
                    baseFrameIndex = frameCursor,
                )
                sampleCursor += nSamples
                frameCursor += nFrames
            }

            if (sampleCursor != totalSamples) {
                throw IllegalStateException(
                    "Stitch sample sum $sampleCursor != expected $totalSamples",
                )
            }
        }
    }

    private fun fail(
        request: TrackRequest,
        reason: SplitError,
        message: String,
    ) = SplitOutcome.Failure(
        trackIndex = request.trackIndex,
        trackNumber = request.trackNumber,
        reason = reason,
        message = message,
    )
}
