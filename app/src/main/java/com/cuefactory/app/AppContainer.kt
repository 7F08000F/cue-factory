package com.cuefactory.app

import android.content.Context
import com.cuefactory.app.audio.FfmpegBootstrap
import com.cuefactory.app.settings.PrefsSettingsRepository
import com.cuefactory.core.audio.AlbumSplitCoordinator
import com.cuefactory.core.audio.FfmpegFlacSplitter
import com.cuefactory.core.audio.FlacFrameCopySplitter
import com.cuefactory.core.audio.FlacHybridSplitter
import com.cuefactory.core.job.SplitJobQueue
import com.cuefactory.core.match.MatchEngine
import com.cuefactory.core.scan.DirectoryScanner
import com.cuefactory.core.settings.AppSettings
import com.cuefactory.core.settings.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settingsRepository: SettingsRepository = PrefsSettingsRepository(appContext)

    private val jobListeners = CopyOnWriteArrayList<(List<com.cuefactory.core.model.SplitJob>) -> Unit>()

    @Volatile
    private var ffmpegReady: FfmpegBootstrap.Result? = null

    val jobQueue: SplitJobQueue = SplitJobQueue(
        coordinatorFactory = {
            val ffmpeg = ensureFfmpeg()
            val ffmpegSplitter = FfmpegFlacSplitter(
                ffmpegPath = ffmpeg.ffmpegPath,
                libraryPath = ffmpeg.libraryPath.ifBlank { null },
                launchPrefix = ffmpeg.launchPrefix,
            )
            val frameCopy = FlacFrameCopySplitter()
            AlbumSplitCoordinator(
                ffmpegSplitter = ffmpegSplitter,
                frameCopySplitter = frameCopy,
                hybridSplitter = FlacHybridSplitter(
                    frameCopy = frameCopy,
                    reencoder = ffmpegSplitter,
                ),
            )
        },
        initialConcurrency = settingsRepository.get().concurrency,
        listener = { jobs -> jobListeners.forEach { it(jobs) } },
    )

    val scanner = DirectoryScanner(probeEmbeddedCue = true)
    val matchEngine = MatchEngine()

    fun ensureFfmpeg(forceReinstall: Boolean = false): FfmpegBootstrap.Result {
        if (!forceReinstall) {
            ffmpegReady?.let { if (it.ready) return it }
        }
        val boot = FfmpegBootstrap.ensure(appContext, forceReinstall = forceReinstall)
        ffmpegReady = boot
        if (boot.ready) {
            settingsRepository.update { it.copy(ffmpegPath = boot.ffmpegPath) }
        }
        return boot
    }

    fun addJobListener(listener: (List<com.cuefactory.core.model.SplitJob>) -> Unit) {
        jobListeners += listener
        listener(jobQueue.snapshot())
    }

    fun removeJobListener(listener: (List<com.cuefactory.core.model.SplitJob>) -> Unit) {
        jobListeners -= listener
    }

    fun applySettings(settings: AppSettings) {
        settingsRepository.update { settings }
        jobQueue.setConcurrency(settings.concurrency)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
        }
    }
}
