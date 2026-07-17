package com.cuefactory.core.job

import com.cuefactory.core.audio.AlbumSplitCoordinator
import com.cuefactory.core.model.AlbumSplitResult
import com.cuefactory.core.model.ConcurrencyConfig
import com.cuefactory.core.model.JobState
import com.cuefactory.core.model.MatchedAlbum
import com.cuefactory.core.model.SplitJob
import com.cuefactory.core.model.SplitMode
import com.cuefactory.core.model.SplitOutcome
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory multi-album split queue with adjustable job parallelism.
 */
class SplitJobQueue(
    private val coordinatorFactory: () -> AlbumSplitCoordinator = { AlbumSplitCoordinator() },
    initialConcurrency: ConcurrencyConfig = ConcurrencyConfig.Balanced,
    private val listener: ((List<SplitJob>) -> Unit)? = null,
) {
    private val concurrency = AtomicReference(initialConcurrency)
    private val jobs = ConcurrentHashMap<String, SplitJob>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val runningFutures = ConcurrentHashMap<String, Future<*>>()
    private val lock = Any()
    private var pool = Executors.newFixedThreadPool(initialConcurrency.jobParallelism)

    fun snapshot(): List<SplitJob> =
        jobs.values.sortedBy { it.id }

    fun setConcurrency(config: ConcurrencyConfig) {
        concurrency.set(config)
        // Affects newly scheduled work; running jobs keep their workerThreads.
        rebuildPoolIfIdle(config.jobParallelism)
    }

    fun enqueue(
        album: MatchedAlbum,
        outputRoot: File,
        directoryTemplate: String = "{album_artist}/{album}",
        fileNameTemplate: String = "{track:02d}. {title}",
        splitMode: SplitMode = SplitMode.Hybrid,
    ): String {
        val id = UUID.randomUUID().toString()
        val job = SplitJob(
            id = id,
            album = album,
            state = JobState.Pending,
            totalTracks = album.sheet.trackCount,
        )
        jobs[id] = job
        cancelFlags[id] = AtomicBoolean(false)
        publish()
        schedule(id, outputRoot, directoryTemplate, fileNameTemplate, splitMode)
        return id
    }

    fun cancel(jobId: String) {
        cancelFlags[jobId]?.set(true)
        update(jobId) { job ->
            if (job.state == JobState.Running || job.state == JobState.Pending) {
                job.copy(state = JobState.Cancelling, message = "cancelling…")
            } else {
                job
            }
        }
        runningFutures[jobId]?.cancel(true)
    }

    fun retry(
        jobId: String,
        outputRoot: File,
        directoryTemplate: String = "{album_artist}/{album}",
        fileNameTemplate: String = "{track:02d}. {title}",
        splitMode: SplitMode = SplitMode.Hybrid,
    ) {
        val existing = jobs[jobId] ?: return
        if (existing.state != JobState.Failed &&
            existing.state != JobState.Cancelled &&
            existing.state != JobState.PartialSuccess
        ) {
            return
        }
        cancelFlags[jobId] = AtomicBoolean(false)
        update(jobId) {
            it.copy(
                state = JobState.Pending,
                progress = 0f,
                completedTracks = 0,
                message = "retry queued",
                result = null,
            )
        }
        schedule(jobId, outputRoot, directoryTemplate, fileNameTemplate, splitMode)
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    private fun schedule(
        jobId: String,
        outputRoot: File,
        directoryTemplate: String,
        fileNameTemplate: String,
        splitMode: SplitMode,
    ) {
        val future = pool.submit {
            val job = jobs[jobId] ?: return@submit
            val flag = cancelFlags[jobId] ?: AtomicBoolean(false)
            update(jobId) {
                it.copy(state = JobState.Running, message = "准备中…", progress = 0f, completedTracks = 0)
            }
            try {
                val coordinator = coordinatorFactory()
                val cfg = concurrency.get()
                // Product policy is Hybrid; legacy enum aliases are ignored at runtime.
                val totalTracks = job.totalTracks.coerceAtLeast(1)
                // Reserve ~8% of the bar for index/plan so UI is not stuck at 0%.
                val prepWeight = 0.08f
                val trackWeight = 1f - prepWeight

                val result: AlbumSplitResult = coordinator.split(
                    AlbumSplitCoordinator.Request(
                        album = job.album,
                        outputRoot = outputRoot,
                        directoryTemplate = directoryTemplate,
                        fileNameTemplate = fileNameTemplate,
                        workerThreads = cfg.workerThreads,
                        allowReencode = true,
                        preferFrameCopy = true,
                        allowHybrid = true,
                        cancelled = flag,
                        onPhase = { message, hint ->
                            update(jobId) { current ->
                                current.copy(
                                    message = message,
                                    progress = hint.coerceIn(0f, prepWeight),
                                )
                            }
                        },
                        onTrackStart = { trackNumber, method ->
                            update(jobId) { current ->
                                val methodLabel = when (method) {
                                    com.cuefactory.core.model.SplitMethod.FrameCopy -> "FrameCopy"
                                    com.cuefactory.core.model.SplitMethod.HybridBoundary -> "Hybrid"
                                    com.cuefactory.core.model.SplitMethod.LosslessReencode -> "Reencode"
                                }
                                current.copy(
                                    message = "正在切分轨 $trackNumber · $methodLabel",
                                )
                            }
                        },
                        onTrackDone = { outcome ->
                            update(jobId) { current ->
                                val done = current.completedTracks + 1
                                val msg = when (outcome) {
                                    is SplitOutcome.Success ->
                                        "轨 ${outcome.trackNumber} 完成 · ${outcome.method.name}"
                                    is SplitOutcome.Failure ->
                                        "轨 ${outcome.trackNumber ?: "?"} 失败: ${outcome.message}"
                                }
                                current.copy(
                                    completedTracks = done,
                                    progress = prepWeight + trackWeight * done.toFloat() / totalTracks,
                                    message = msg,
                                )
                            }
                        },
                    ),
                )
                val state = when {
                    flag.get() && result.successes.isEmpty() -> JobState.Cancelled
                    result.isFullSuccess -> JobState.Succeeded
                    result.successes.isNotEmpty() -> JobState.PartialSuccess
                    flag.get() -> JobState.Cancelled
                    else -> JobState.Failed
                }
                val boundaryFails = result.failures.count {
                    it.reason == com.cuefactory.core.model.SplitError.UnsafeFrameBoundary
                }
                val summary = buildString {
                    append(result.successes.size).append('/').append(result.outcomes.size)
                    append(" ok")
                    if (result.failures.isNotEmpty()) {
                        append(", ").append(result.failures.size).append(" fail")
                    }
                    if (boundaryFails > 0) {
                        append(" (")
                        append(boundaryFails)
                        append(" 边界未对齐)")
                    }
                    append(" · Hybrid")
                }
                update(jobId) {
                    it.copy(
                        state = state,
                        progress = if (state == JobState.Succeeded) 1f else it.progress,
                        result = result,
                        message = summary,
                    )
                }
            } catch (e: Exception) {
                update(jobId) {
                    it.copy(
                        state = if (flag.get()) JobState.Cancelled else JobState.Failed,
                        message = e.message ?: e.toString(),
                    )
                }
            } finally {
                runningFutures.remove(jobId)
            }
        }
        runningFutures[jobId] = future
    }

    private fun trackMessage(outcome: SplitOutcome): String = when (outcome) {
        is SplitOutcome.Success -> "track ${outcome.trackNumber} ok"
        is SplitOutcome.Failure -> "track ${outcome.trackNumber ?: "?"} fail: ${outcome.reason}"
    }

    private fun update(jobId: String, transform: (SplitJob) -> SplitJob) {
        synchronized(lock) {
            val cur = jobs[jobId] ?: return
            jobs[jobId] = transform(cur)
        }
        publish()
    }

    private fun publish() {
        listener?.invoke(snapshot())
    }

    private fun rebuildPoolIfIdle(parallelism: Int) {
        synchronized(lock) {
            if (runningFutures.isEmpty()) {
                pool.shutdownNow()
                pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 16))
            }
        }
    }
}
