package com.cuefactory.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuefactory.app.service.SplitForegroundService
import com.cuefactory.app.util.AppLog
import com.cuefactory.app.util.StorageAccess
import com.cuefactory.core.match.MatchEngine
import com.cuefactory.core.model.AmbiguousMatch
import com.cuefactory.core.model.JobState
import com.cuefactory.core.model.MatchReport
import com.cuefactory.core.model.MatchedAlbum
import com.cuefactory.core.model.SplitJob
import com.cuefactory.core.model.UnmatchedAudio
import com.cuefactory.core.scan.DirectoryScanner
import com.cuefactory.core.settings.AppSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper

data class UiState(
    val settings: AppSettings = AppSettings(),
    val scanPathInput: String = "",
    val scanning: Boolean = false,
    val scanProgress: DirectoryScanner.Progress? = null,
    val scanErrors: List<String> = emptyList(),
    val matched: List<MatchedAlbum> = emptyList(),
    val ambiguous: List<AmbiguousMatch> = emptyList(),
    val unmatched: List<UnmatchedAudio> = emptyList(),
    val selectedMatchedPaths: Set<String> = emptySet(),
    val jobs: List<SplitJob> = emptyList(),
    val statusMessage: String? = null,
    val activeTab: Int = 0,
    /** Android 11+ all-files access; required for path-based Music/album scans. */
    val hasAllFilesAccess: Boolean = StorageAccess.hasAllFilesAccess(),
)

class CueFactoryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)
    private val scanCancel = AtomicBoolean(false)

    private val _state = MutableStateFlow(
        UiState(
            settings = container.settingsRepository.get(),
            scanPathInput = container.settingsRepository.get().lastScanPath.ifBlank {
                // App cannot read Termux $HOME; samples staged on shared storage
                "/storage/emulated/0/CueFactory/samples"
            },
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastJobUiPostMs = 0L
    @Volatile private var pendingJobs: List<SplitJob>? = null
    private val jobUiFlush = Runnable {
        val jobs = pendingJobs ?: return@Runnable
        pendingJobs = null
        lastJobUiPostMs = System.currentTimeMillis()
        // Log phase / track milestones (throttled by 250ms UI flush) + terminals.
        jobs.forEach { job ->
            val terminal = job.state == JobState.Succeeded ||
                job.state == JobState.Failed ||
                job.state == JobState.PartialSuccess ||
                job.state == JobState.Cancelled
            val milestone = job.state == JobState.Running && !job.message.isNullOrBlank()
            if (terminal || milestone) {
                AppLog.i(
                    "job ${job.id.take(8)} ${job.state} " +
                        "${job.completedTracks}/${job.totalTracks} " +
                        "p=${"%.0f".format(job.progress * 100)}% " +
                        (job.message ?: ""),
                )
            }
        }
        _state.update { it.copy(jobs = jobs) }
    }
    private val jobListener: (List<SplitJob>) -> Unit = { jobs ->
        // Throttle UI rebuilds (multi-album track callbacks flood Compose)
        pendingJobs = jobs
        val now = System.currentTimeMillis()
        val due = now - lastJobUiPostMs >= 250
        val terminal = jobs.any {
            it.state == JobState.Succeeded ||
                it.state == JobState.Failed ||
                it.state == JobState.PartialSuccess ||
                it.state == JobState.Cancelled
        }
        mainHandler.removeCallbacks(jobUiFlush)
        if (due || terminal) {
            mainHandler.post(jobUiFlush)
        } else {
            mainHandler.postDelayed(jobUiFlush, 250L)
        }
    }

    init {
        container.addJobListener(jobListener)
        AppLog.i("ViewModel ready · scan=${_state.value.scanPathInput}")
    }

    override fun onCleared() {
        container.removeJobListener(jobListener)
        super.onCleared()
    }

    fun setTab(index: Int) {
        _state.update { it.copy(activeTab = index) }
    }

    fun updateScanPath(path: String) {
        _state.update { it.copy(scanPathInput = path) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = container.settingsRepository.update(transform)
        container.jobQueue.setConcurrency(next.concurrency)
        _state.update { it.copy(settings = next) }
    }

    fun setStorageAccess(hasAllFiles: Boolean) {
        _state.update { it.copy(hasAllFilesAccess = hasAllFiles) }
    }

    fun startScan() {
        val path = _state.value.scanPathInput.trim()
        if (path.isEmpty()) {
            _state.update { it.copy(statusMessage = "请填写要扫描的目录路径") }
            return
        }
        val root = File(path)
        if (!root.exists()) {
            AppLog.w("scan path missing: $path")
            _state.update { it.copy(statusMessage = "路径不存在：$path") }
            return
        }
        val allFiles = StorageAccess.hasAllFilesAccess()
        _state.update { it.copy(hasAllFilesAccess = allFiles) }
        if (!allFiles) {
            AppLog.w("scan blocked: no MANAGE_EXTERNAL_STORAGE")
            _state.update {
                it.copy(
                    statusMessage = "需要「所有文件访问」权限才能扫描相册/Music 路径。请点「授权存储」后重试。",
                )
            }
            return
        }
        if (!StorageAccess.canListDirectory(root)) {
            AppLog.w("scan path not listable: $path")
            _state.update {
                it.copy(
                    statusMessage = "无法读取目录（权限或路径不可访问）：$path",
                )
            }
            return
        }
        scanCancel.set(false)
        AppLog.i("scan start: $path allFiles=$allFiles")
        _state.update {
            it.copy(
                scanning = true,
                scanProgress = null,
                scanErrors = emptyList(),
                statusMessage = "正在扫描…",
                matched = emptyList(),
                ambiguous = emptyList(),
                unmatched = emptyList(),
                selectedMatchedPaths = emptySet(),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.scanner.scan(
                    roots = listOf(root),
                    cancelled = scanCancel,
                    onProgress = { progress ->
                        if (progress.visitedFiles % 50 == 0) {
                            AppLog.d(
                                "scan progress files=${progress.visitedFiles} " +
                                    "audio=${progress.audioFound} cue=${progress.cueFound}",
                            )
                        }
                        _state.update { ui -> ui.copy(scanProgress = progress) }
                    },
                )
            }
            if (result.cancelled) {
                AppLog.i("scan cancelled")
                _state.update {
                    it.copy(scanning = false, statusMessage = "扫描已取消", scanErrors = result.errors)
                }
                return@launch
            }
            val charset = container.settingsRepository.get().cueCharset
            val report: MatchReport = withContext(Dispatchers.IO) {
                MatchEngine(charsetName = charset).match(
                    audioSources = result.audioSources,
                    externalCues = result.externalCues,
                    embeddedByAudioPath = result.embeddedByAudioPath,
                )
            }
            AppLog.i(
                "scan done audio=${result.audioSources.size} cue=${result.externalCues.size} " +
                    "embedded=${result.embeddedByAudioPath.size} matched=${report.matched.size} " +
                    "amb=${report.ambiguous.size} unmatched=${report.unmatched.size}",
            )
            container.settingsRepository.update { it.copy(lastScanPath = path) }
            _state.update {
                it.copy(
                    scanning = false,
                    settings = container.settingsRepository.get(),
                    matched = report.matched,
                    ambiguous = report.ambiguous,
                    unmatched = report.unmatched,
                    selectedMatchedPaths = report.matched.map { m -> m.audio.path }.toSet(),
                    scanErrors = result.errors,
                    statusMessage = "扫描完成：匹配 ${report.matched.size} · 歧义 ${report.ambiguous.size} · 未匹配 ${report.unmatched.size}",
                    activeTab = 0,
                )
            }
        }
    }

    fun cancelScan() {
        AppLog.i("scan cancel requested")
        scanCancel.set(true)
    }

    fun setGlobalCharset(charsetName: String?) {
        updateSettings { it.copy(cueCharset = charsetName) }
        // Re-parse currently displayed matched albums with new charset
        val current = _state.value.matched
        if (current.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val engine = MatchEngine(charsetName = charsetName)
            val updated = current.map { album ->
                engine.reparseWithCharset(album, charsetName) ?: album
            }
            _state.update {
                it.copy(
                    matched = updated,
                    statusMessage = "已用 ${charsetName ?: "自动"} 重新解析 ${updated.size} 项",
                )
            }
        }
    }

    fun reparseAlbumCharset(audioPath: String, charsetName: String?) {
        val album = _state.value.matched.firstOrNull { it.audio.path == audioPath } ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val engine = MatchEngine(charsetName = charsetName)
            val next = engine.reparseWithCharset(album, charsetName) ?: return@launch
            _state.update { ui ->
                ui.copy(
                    matched = ui.matched.map { if (it.audio.path == audioPath) next else it },
                    statusMessage = "${next.sheet.title ?: next.audio.fileName} · ${charsetName ?: "自动"}",
                )
            }
        }
    }

    fun toggleMatched(path: String) {
        _state.update { ui ->
            val next = ui.selectedMatchedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            ui.copy(selectedMatchedPaths = next)
        }
    }

    fun selectAllMatched(select: Boolean) {
        _state.update { ui ->
            ui.copy(
                selectedMatchedPaths = if (select) {
                    ui.matched.map { it.audio.path }.toSet()
                } else {
                    emptySet()
                },
            )
        }
    }

    fun confirmAmbiguous(ambiguityIndex: Int, audioPath: String, cuePath: String) {
        val amb = _state.value.ambiguous.getOrNull(ambiguityIndex) ?: return
        val audio = amb.audioCandidates.firstOrNull { it.path == audioPath } ?: return
        val cue = amb.cueCandidates.firstOrNull { it.path == cuePath } ?: return
        val album = container.matchEngine.confirm(audio, cue) ?: return
        _state.update { ui ->
            val newAmb = ui.ambiguous.toMutableList().also { it.removeAt(ambiguityIndex) }
            ui.copy(
                matched = ui.matched + album,
                ambiguous = newAmb,
                selectedMatchedPaths = ui.selectedMatchedPaths + album.audio.path,
                statusMessage = "已确认：${album.sheet.title ?: album.audio.fileName}",
            )
        }
    }

    fun enqueueSelected() {
        val ui = _state.value
        val output = ui.settings.outputRootPath.ifBlank {
            "/storage/emulated/0/CueFactory/split-out"
        }
        val outDir = File(output)
        outDir.mkdirs()
        if (ui.settings.outputRootPath.isBlank()) {
            updateSettings { it.copy(outputRootPath = outDir.absolutePath) }
        }
        val selected = ui.matched.filter { it.audio.path in ui.selectedMatchedPaths }
        if (selected.isEmpty()) {
            _state.update { it.copy(statusMessage = "请先勾选要切分的匹配专辑") }
            return
        }
        // Only FLAC for now
        val flacOnly = selected.filter { it.audio.fileName.lowercase().endsWith(".flac") }
        if (flacOnly.isEmpty()) {
            _state.update { it.copy(statusMessage = "当前版本仅支持 FLAC 切分") }
            return
        }
        viewModelScope.launch {
            try {
                // hybrid-only branch: always Hybrid (needs ffmpeg for edge re-encode).
                val mode = com.cuefactory.core.model.SplitMode.Hybrid
                AppLog.i(
                    "enqueue ${flacOnly.size} album(s) → ${outDir.absolutePath} mode=$mode",
                )

                _state.update { it.copy(statusMessage = "正在准备 ffmpeg…") }
                val ffmpeg = withContext(Dispatchers.IO) {
                    container.ensureFfmpeg(forceReinstall = false)
                }
                AppLog.i(
                    "ffmpeg ready=${ffmpeg.ready} path=${ffmpeg.ffmpegPath} " +
                        "lib=${ffmpeg.libraryPath} msg=${ffmpeg.message}",
                )
                if (!ffmpeg.ready) {
                    _state.update {
                        it.copy(
                            statusMessage = "ffmpeg 未就绪：${ffmpeg.message}",
                            settings = container.settingsRepository.get(),
                        )
                    }
                    return@launch
                }
                updateSettings { it.copy(ffmpegPath = ffmpeg.ffmpegPath) }

                // Soft cap only — user stepper still controls 1..8; avoid runaway OOM
                val safeWorkers = ui.settings.concurrency.workerThreads.coerceIn(1, 8)
                val safeJobs = ui.settings.concurrency.jobParallelism.coerceIn(1, 4)
                container.jobQueue.setConcurrency(
                    ui.settings.concurrency.copy(
                        jobParallelism = safeJobs,
                        workerThreads = safeWorkers,
                    ),
                )

                flacOnly.forEach { album ->
                    val id = container.jobQueue.enqueue(
                        album = album,
                        outputRoot = outDir,
                        directoryTemplate = ui.settings.outputTemplate.directoryTemplate,
                        fileNameTemplate = ui.settings.outputTemplate.fileNameTemplate,
                        splitMode = mode,
                    )
                    AppLog.i(
                        "job $id · ${album.sheet.title ?: album.audio.fileName} · " +
                            "${album.sheet.trackCount} tracks · mode=$mode · " +
                            "workers=$safeWorkers",
                    )
                }
                SplitForegroundService.start(getApplication())
                _state.update {
                    it.copy(
                        statusMessage = "已入队 ${flacOnly.size} 个任务（Hybrid）→ ${outDir.absolutePath}",
                        activeTab = 1,
                        settings = container.settingsRepository.get(),
                    )
                }
            } catch (e: Exception) {
                AppLog.e("enqueue crashed: ${e.message}", e)
                _state.update {
                    it.copy(statusMessage = "入队失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    fun cancelJob(id: String) {
        container.jobQueue.cancel(id)
    }

    fun retryJob(id: String) {
        val out = File(
            _state.value.settings.outputRootPath.ifBlank {
                File(getApplication<Application>().getExternalFilesDir(null), "split").absolutePath
            },
        )
        container.jobQueue.retry(
            jobId = id,
            outputRoot = out,
            directoryTemplate = _state.value.settings.outputTemplate.directoryTemplate,
            fileNameTemplate = _state.value.settings.outputTemplate.fileNameTemplate,
            splitMode = com.cuefactory.core.model.SplitMode.Hybrid,
        )
        SplitForegroundService.start(getApplication())
    }

    fun clearStatus() {
        _state.update { it.copy(statusMessage = null) }
    }

    fun hasActiveJobs(jobs: List<SplitJob> = _state.value.jobs): Boolean =
        jobs.any {
            it.state == JobState.Running || it.state == JobState.Pending || it.state == JobState.Cancelling
        }
}
