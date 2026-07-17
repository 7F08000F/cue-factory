package com.cuefactory.core.settings

import com.cuefactory.core.model.ConcurrencyConfig
import com.cuefactory.core.model.FileConflictPolicy
import com.cuefactory.core.model.OutputTemplate
import com.cuefactory.core.model.SplitMode

/**
 * User preferences. Persistence is app-layer; this is the pure model.
 */
data class AppSettings(
    val outputRootPath: String = "",
    val outputTemplate: OutputTemplate = OutputTemplate(),
    val conflictPolicy: FileConflictPolicy = FileConflictPolicy.Skip,
    val concurrency: ConcurrencyConfig = ConcurrencyConfig.Balanced,
    /**
     * Preferred CUE charset: null = auto probe.
     * Examples: "UTF-8", "windows-1251", "IBM866".
     */
    val cueCharset: String? = null,
    /** Copy primary PICTURE block into each split track when present. */
    val copyCoverArt: Boolean = true,
    /** Absolute path to ffmpeg binary used for LosslessReencode. */
    val ffmpegPath: String = "ffmpeg",
    /** Last used scan root path (absolute filesystem path). */
    val lastScanPath: String = "",
    /**
     * Split policy. On the hybrid-only branch this is always treated as
     * [SplitMode.Hybrid] (legacy enum values are accepted but normalized).
     */
    val splitMode: SplitMode = SplitMode.Hybrid,
)
