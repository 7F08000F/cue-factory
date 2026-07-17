package com.cuefactory.app.settings

import android.content.Context
import com.cuefactory.core.model.ConcurrencyConfig
import com.cuefactory.core.model.FileConflictPolicy
import com.cuefactory.core.model.OutputTemplate
import com.cuefactory.core.model.SplitMode
import com.cuefactory.core.settings.AppSettings
import com.cuefactory.core.settings.SettingsRepository

class PrefsSettingsRepository(
    context: Context,
) : SettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): AppSettings {
        val job = prefs.getInt(KEY_JOB, ConcurrencyConfig.Balanced.jobParallelism)
        val workers = prefs.getInt(KEY_WORKERS, ConcurrencyConfig.Balanced.workerThreads)
        return AppSettings(
            outputRootPath = prefs.getString(KEY_OUTPUT, "") ?: "",
            outputTemplate = OutputTemplate(
                directoryTemplate = prefs.getString(KEY_DIR_TPL, "{album_artist}/{album}")
                    ?: "{album_artist}/{album}",
                fileNameTemplate = prefs.getString(KEY_FILE_TPL, "{track:02d}. {title}")
                    ?: "{track:02d}. {title}",
            ),
            conflictPolicy = runCatching {
                FileConflictPolicy.valueOf(
                    prefs.getString(KEY_CONFLICT, FileConflictPolicy.Skip.name)
                        ?: FileConflictPolicy.Skip.name,
                )
            }.getOrDefault(FileConflictPolicy.Skip),
            concurrency = ConcurrencyConfig(
                jobParallelism = job.coerceIn(1, 16),
                workerThreads = workers.coerceIn(1, 32),
            ),
            cueCharset = prefs.getString(KEY_CHARSET, null)?.ifBlank { null },
            copyCoverArt = prefs.getBoolean(KEY_COVER, true),
            ffmpegPath = prefs.getString(KEY_FFMPEG, "ffmpeg") ?: "ffmpeg",
            lastScanPath = prefs.getString(KEY_SCAN, "") ?: "",
            // hybrid-only branch: normalize any legacy stored value to Hybrid.
            splitMode = runCatching {
                SplitMode.valueOf(
                    prefs.getString(KEY_SPLIT_MODE, SplitMode.Hybrid.name)
                        ?: SplitMode.Hybrid.name,
                ).effective()
            }.getOrDefault(SplitMode.Hybrid),
        )
    }

    override fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(get()).let { s ->
            s.copy(splitMode = s.splitMode.effective())
        }
        prefs.edit()
            .putString(KEY_OUTPUT, next.outputRootPath)
            .putString(KEY_DIR_TPL, next.outputTemplate.directoryTemplate)
            .putString(KEY_FILE_TPL, next.outputTemplate.fileNameTemplate)
            .putString(KEY_CONFLICT, next.conflictPolicy.name)
            .putInt(KEY_JOB, next.concurrency.jobParallelism)
            .putInt(KEY_WORKERS, next.concurrency.workerThreads)
            .putString(KEY_CHARSET, next.cueCharset)
            .putBoolean(KEY_COVER, next.copyCoverArt)
            .putString(KEY_FFMPEG, next.ffmpegPath)
            .putString(KEY_SCAN, next.lastScanPath)
            .putString(KEY_SPLIT_MODE, SplitMode.Hybrid.name)
            .apply()
        return next
    }

    companion object {
        private const val PREFS = "cue_factory_settings"
        private const val KEY_OUTPUT = "output_root"
        private const val KEY_DIR_TPL = "dir_tpl"
        private const val KEY_FILE_TPL = "file_tpl"
        private const val KEY_CONFLICT = "conflict"
        private const val KEY_JOB = "job_par"
        private const val KEY_WORKERS = "workers"
        private const val KEY_CHARSET = "charset"
        private const val KEY_COVER = "cover"
        private const val KEY_FFMPEG = "ffmpeg"
        private const val KEY_SCAN = "scan_path"
        private const val KEY_SPLIT_MODE = "split_mode"
    }
}
