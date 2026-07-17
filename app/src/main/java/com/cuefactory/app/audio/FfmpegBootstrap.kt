package com.cuefactory.app.audio

import android.content.Context
import android.os.Build
import com.cuefactory.app.util.AppLog
import java.io.File

/**
 * Locates the bundled minimal FFmpeg CLI.
 *
 * The app packages a trimmed FFmpeg 8.1.2 build in `jniLibs/arm64-v8a`:
 * FLAC demux/decode/encode, file protocol, atrim and asetpts only.
 * Android extracts it under [android.content.pm.ApplicationInfo.nativeLibraryDir],
 * which is private to the APK — no engine files are exposed on shared storage.
 */
object FfmpegBootstrap {
    data class Result(
        val ffmpegPath: String,
        val libraryPath: String,
        val ready: Boolean,
        val message: String,
        /** Full argv prefix for ProcessBuilder. */
        val launchPrefix: List<String> = listOf(ffmpegPath),
    )

    fun ensure(context: Context, forceReinstall: Boolean = false): Result {
        // forceReinstall is retained for caller API compatibility; bundled libs need no install.
        @Suppress("UNUSED_VARIABLE")
        val ignored = forceReinstall

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDir, "libffmpeg.so")
        if (!binary.isFile) {
            val message = "内置轻量 FFmpeg 缺失：${binary.absolutePath}"
            AppLog.e(message)
            return Result("", nativeDir.absolutePath, false, message, emptyList())
        }

        // App-data runtime writes cannot execute on Android 10+; this is a packaged native lib.
        // linker64 works reliably for a CLI-shaped ELF whether direct execution is permitted or not.
        val launch = launchCommand(binary)
        val error = probe(launch, nativeDir.absolutePath)
        if (error != null) {
            AppLog.e("bundled FFmpeg probe failed: $error")
            return Result(
                binary.absolutePath,
                nativeDir.absolutePath,
                false,
                "内置轻量 FFmpeg 自检失败：$error",
                launch,
            )
        }

        AppLog.i("bundled slim FFmpeg ready: ${binary.absolutePath}")
        return Result(
            ffmpegPath = binary.absolutePath,
            libraryPath = nativeDir.absolutePath,
            ready = true,
            message = "内置轻量 FFmpeg 8.1.2",
            launchPrefix = launch,
        )
    }

    private fun launchCommand(binary: File): List<String> {
        val linker = when {
            Build.VERSION.SDK_INT >= 29 && File("/system/bin/linker64").canExecute() ->
                "/system/bin/linker64"
            Build.VERSION.SDK_INT >= 29 && File("/system/bin/linker").canExecute() ->
                "/system/bin/linker"
            else -> null
        }
        return if (linker != null) listOf(linker, binary.absolutePath) else listOf(binary.absolutePath)
    }

    /** null on success, otherwise a bounded diagnostic. */
    private fun probe(launch: List<String>, libraryPath: String): String? {
        return try {
            val process = ProcessBuilder(launch + "-version")
                .directory(File(libraryPath))
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = libraryPath
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit == 0 && output.contains("ffmpeg version")) {
                null
            } else {
                output.lineSequence().firstOrNull()?.take(400) ?: "exit $exit"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
