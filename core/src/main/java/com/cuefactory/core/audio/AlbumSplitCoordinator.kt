package com.cuefactory.core.audio

import com.cuefactory.core.model.AlbumSplitResult
import com.cuefactory.core.model.MatchedAlbum
import com.cuefactory.core.model.SplitError
import com.cuefactory.core.model.SplitMethod
import com.cuefactory.core.model.SplitOutcome
import com.cuefactory.core.model.TrackNameContext
import com.cuefactory.core.output.TemplateRenderer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates planning + per-track split for one album.
 *
 * Best-practice flow (Auto / hybrid enabled):
 * 1. Build frame index once (needed for FrameCopy + HybridBoundary)
 * 2. Plan: aligned → FrameCopy; unaligned → HybridBoundary; else reencode
 * 3. Execute; hybrid failures fall back to full LosslessReencode when allowed
 */
class AlbumSplitCoordinator(
    private val metadataReader: FlacMetadataReader = FlacMetadataReader(),
    private val planner: FlacSplitPlanner = FlacSplitPlanner(),
    private val ffmpegSplitter: FfmpegFlacSplitter = FfmpegFlacSplitter(),
    private val frameCopySplitter: FlacFrameCopySplitter = FlacFrameCopySplitter(),
    private val hybridSplitter: FlacHybridSplitter = FlacHybridSplitter(
        frameCopy = frameCopySplitter,
        reencoder = ffmpegSplitter,
    ),
    private val frameIndexBuilder: FlacFrameIndex = FlacFrameIndex(),
    private val templateRenderer: TemplateRenderer = TemplateRenderer(),
) {
    data class Request(
        val album: MatchedAlbum,
        val outputRoot: File,
        val directoryTemplate: String = "{album_artist}/{album}",
        val fileNameTemplate: String = "{track:02d}. {title}",
        val workerThreads: Int = 2,
        /** When false, unaligned tracks fail (FrameCopyOnly). */
        val allowReencode: Boolean = true,
        /**
         * When false (AlwaysReencode), skip index / FrameCopy / Hybrid and
         * always full-range lossless re-encode.
         */
        val preferFrameCopy: Boolean = true,
        /**
         * When true (Auto default), unaligned tracks use HybridBoundary.
         * When false with allowReencode, unaligned tracks use full LosslessReencode.
         */
        val allowHybrid: Boolean = true,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val onTrackDone: ((SplitOutcome) -> Unit)? = null,
        /**
         * Phase / status updates before any track finishes (indexing can take
         * seconds on large albums and previously left the UI at 0% with no message).
         * [progressHint] is 0..1 and should stay below the first-track fraction.
         */
        val onPhase: ((message: String, progressHint: Float) -> Unit)? = null,
        val onTrackStart: ((trackNumber: Int, method: SplitMethod) -> Unit)? = null,
    )

    fun split(request: Request): AlbumSplitResult {
        val source = File(request.album.audio.path)
        request.onPhase?.invoke("读取元数据…", 0.01f)
        val meta = metadataReader.read(source)

        if (!request.preferFrameCopy) {
            request.onPhase?.invoke("规划整轨重编码…", 0.03f)
            val forced = planner.plan(
                sheet = request.album.sheet,
                streamInfo = meta.streamInfo,
                allowReencode = true,
                allowHybrid = false,
                frameIndex = null,
            ).let { plan ->
                plan.copy(
                    tracks = plan.tracks.map { t ->
                        t.copy(
                            method = SplitMethod.LosslessReencode,
                            rejection = null,
                            note = "settings: always lossless re-encode",
                        )
                    },
                    indexVerified = false,
                )
            }
            return runTracks(request, source, forced, frameIndex = null)
        }

        // Index once: required for verified FrameCopy and for Hybrid interior copy.
        // This is often the long prep phase on multi-hundred-MB albums.
        val indexT0 = System.currentTimeMillis()
        request.onPhase?.invoke("建立帧索引…", 0.02f)
        val frameIndex = runCatching {
            frameIndexBuilder.build(source) { scanned, total ->
                if (total <= 0L) return@build
                // Map 0..100% of file scan into prep progress 2%..6%.
                val frac = (scanned.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                val pct = (frac * 100).toInt()
                val hint = (0.02f + 0.04f * frac.toFloat()).coerceIn(0.02f, 0.06f)
                request.onPhase?.invoke("建立帧索引 $pct%…", hint)
            }
        }.getOrNull()
        val indexMs = System.currentTimeMillis() - indexT0
        if (frameIndex != null) {
            request.onPhase?.invoke(
                "帧索引完成 ${frameIndex.frames.size} 帧 · ${indexMs}ms",
                0.06f,
            )
        } else {
            request.onPhase?.invoke("帧索引失败，将回退整轨重编码…", 0.04f)
        }

        request.onPhase?.invoke("规划切分方式…", 0.07f)
        val plan = if (frameIndex != null) {
            planner.plan(
                sheet = request.album.sheet,
                streamInfo = meta.streamInfo,
                allowReencode = request.allowReencode,
                allowHybrid = request.allowHybrid && request.allowReencode,
                frameIndex = frameIndex,
            )
        } else {
            val heuristic = planner.plan(
                sheet = request.album.sheet,
                streamInfo = meta.streamInfo,
                allowReencode = request.allowReencode,
                allowHybrid = false, // no index → cannot hybrid safely
                frameIndex = null,
            )
            if (request.allowReencode) {
                heuristic.copy(
                    tracks = heuristic.tracks.map { t ->
                        when {
                            t.method == SplitMethod.FrameCopy && t.rejection == null -> t.copy(
                                method = SplitMethod.LosslessReencode,
                                note = "frame index unavailable; demoted to lossless re-encode",
                            )
                            t.method == SplitMethod.HybridBoundary -> t.copy(
                                method = SplitMethod.LosslessReencode,
                                note = "frame index unavailable; demoted to lossless re-encode",
                            )
                            else -> t
                        }
                    },
                    indexVerified = false,
                )
            } else {
                heuristic.copy(
                    tracks = heuristic.tracks.map { t ->
                        if (t.rejection == null && t.method != SplitMethod.FrameCopy) {
                            t.copy(
                                rejection = SplitError.UnsafeFrameBoundary,
                                note = "frame index unavailable and re-encode disabled",
                            )
                        } else if (t.method == SplitMethod.FrameCopy && t.rejection == null) {
                            // Still need index for verified copy; fail closed.
                            t.copy(
                                rejection = SplitError.UnsafeFrameBoundary,
                                note = "frame index unavailable for FrameCopy",
                            )
                        } else {
                            t
                        }
                    },
                )
            }
        }

        val methodSummary = plan.tracks.groupingBy { it.method }.eachCount()
            .entries.joinToString(" ") { "${it.key.shortLabel()}=${it.value}" }
        request.onPhase?.invoke("开始切分 · $methodSummary", 0.08f)

        return runTracks(request, source, plan, frameIndex)
    }

    private fun SplitMethod.shortLabel(): String = when (this) {
        SplitMethod.FrameCopy -> "FrameCopy"
        SplitMethod.HybridBoundary -> "Hybrid"
        SplitMethod.LosslessReencode -> "Reencode"
    }

    private fun runTracks(
        request: Request,
        source: File,
        plan: FlacSplitPlanner.Plan,
        frameIndex: FlacFrameIndex.Index?,
    ): AlbumSplitResult {
        val sheet = request.album.sheet
        val outcomes = ArrayList<SplitOutcome?>(plan.tracks.size).apply {
            repeat(plan.tracks.size) { add(null) }
        }

        val pool = Executors.newFixedThreadPool(request.workerThreads.coerceIn(1, 32))
        try {
            val futures = mutableListOf<Future<*>>()
            for (track in plan.tracks) {
                futures += pool.submit {
                    if (request.cancelled.get()) {
                        val o = SplitOutcome.Failure(
                            trackIndex = track.trackIndex,
                            trackNumber = track.trackNumber,
                            reason = SplitError.Cancelled,
                            message = "cancelled",
                        )
                        synchronized(outcomes) { outcomes[track.trackIndex] = o }
                        request.onTrackDone?.invoke(o)
                        return@submit
                    }
                    if (track.rejection != null) {
                        val o = SplitOutcome.Failure(
                            trackIndex = track.trackIndex,
                            trackNumber = track.trackNumber,
                            reason = track.rejection,
                            message = track.note ?: track.rejection.name,
                        )
                        synchronized(outcomes) { outcomes[track.trackIndex] = o }
                        request.onTrackDone?.invoke(o)
                        return@submit
                    }

                    val cueTrack = sheet.allTracks[track.trackIndex]
                    val ctx = TrackNameContext(
                        title = cueTrack.title ?: "Track ${track.trackNumber}",
                        track = track.trackNumber,
                        artist = cueTrack.performer ?: sheet.performer,
                        album = sheet.title,
                        albumArtist = sheet.performer,
                        year = sheet.date,
                        genre = sheet.genre,
                    )
                    val relative = templateRenderer.renderRelativePath(
                        com.cuefactory.core.model.OutputTemplate(
                            directoryTemplate = request.directoryTemplate,
                            fileNameTemplate = request.fileNameTemplate,
                        ),
                        ctx,
                    )
                    val outFile = File(request.outputRoot, relative)
                    // Vorbis / player-friendly keys. ALBUMARTIST (no underscore) is the de-facto
                    // standard; album_artist is also passed for ffmpeg -metadata mapping.
                    val albumArtist = sheet.performer.orEmpty()
                    val trackArtist = cueTrack.performer ?: sheet.performer.orEmpty()
                    val tags = linkedMapOf(
                        "title" to (cueTrack.title ?: ""),
                        "artist" to trackArtist,
                        "album" to (sheet.title ?: ""),
                        "albumartist" to albumArtist,
                        "album_artist" to albumArtist,
                        "track" to track.trackNumber.toString(),
                        "tracknumber" to track.trackNumber.toString(),
                        "genre" to (sheet.genre ?: ""),
                        "date" to (sheet.date ?: ""),
                    ).filterValues { it.isNotBlank() }

                    request.onTrackStart?.invoke(track.trackNumber, track.method)
                    val t0 = System.currentTimeMillis()
                    val outcome = executeTrack(
                        request = request,
                        source = source,
                        track = track,
                        plan = plan,
                        frameIndex = frameIndex,
                        outFile = outFile,
                        tags = tags,
                    )
                    val elapsed = System.currentTimeMillis() - t0
                    val reported = when (outcome) {
                        is SplitOutcome.Success -> outcome
                        is SplitOutcome.Failure -> outcome.copy(
                            message = "${outcome.message} (${elapsed}ms)",
                        )
                    }
                    synchronized(outcomes) { outcomes[track.trackIndex] = reported }
                    request.onTrackDone?.invoke(reported)
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        return AlbumSplitResult(
            album = request.album,
            outcomes = outcomes.mapIndexed { i, o ->
                o ?: SplitOutcome.Failure(
                    trackIndex = i,
                    trackNumber = null,
                    reason = SplitError.Unknown,
                    message = "missing outcome",
                )
            },
        )
    }

    private fun executeTrack(
        request: Request,
        source: File,
        track: FlacSplitPlanner.PlannedTrack,
        plan: FlacSplitPlanner.Plan,
        frameIndex: FlacFrameIndex.Index?,
        outFile: File,
        tags: Map<String, String>,
    ): SplitOutcome {
        fun reencode() = ffmpegSplitter.splitLossless(
            sourceFlac = source,
            request = FfmpegFlacSplitter.TrackRequest(
                trackIndex = track.trackIndex,
                trackNumber = track.trackNumber,
                range = track.range,
                outputFile = outFile,
                sampleRate = plan.sampleRate,
                metadata = tags,
            ),
        )

        return when (track.method) {
            SplitMethod.LosslessReencode -> reencode()
            SplitMethod.FrameCopy -> {
                if (frameIndex == null) {
                    if (request.allowReencode) reencode()
                    else SplitOutcome.Failure(
                        trackIndex = track.trackIndex,
                        trackNumber = track.trackNumber,
                        reason = SplitError.UnsafeFrameBoundary,
                        message = "Frame index unavailable for FrameCopy",
                    )
                } else {
                    frameCopySplitter.split(
                        sourceFlac = source,
                        request = FlacFrameCopySplitter.TrackRequest(
                            trackIndex = track.trackIndex,
                            trackNumber = track.trackNumber,
                            range = track.range,
                            outputFile = outFile,
                            sampleRate = plan.sampleRate,
                            metadata = tags,
                        ),
                        prebuiltIndex = frameIndex,
                    )
                }
            }
            SplitMethod.HybridBoundary -> {
                if (frameIndex == null) {
                    if (request.allowReencode) reencode()
                    else SplitOutcome.Failure(
                        trackIndex = track.trackIndex,
                        trackNumber = track.trackNumber,
                        reason = SplitError.UnsafeFrameBoundary,
                        message = "Frame index unavailable for Hybrid",
                    )
                } else {
                    val hybrid = hybridSplitter.split(
                        sourceFlac = source,
                        request = FlacHybridSplitter.TrackRequest(
                            trackIndex = track.trackIndex,
                            trackNumber = track.trackNumber,
                            range = track.range,
                            outputFile = outFile,
                            sampleRate = plan.sampleRate,
                            metadata = tags,
                        ),
                        prebuiltIndex = frameIndex,
                    )
                    if (hybrid is SplitOutcome.Failure && request.allowReencode) {
                        // Exact fallback — never leave a bad partial as success.
                        outFile.delete()
                        reencode()
                    } else {
                        hybrid
                    }
                }
            }
        }
    }
}
