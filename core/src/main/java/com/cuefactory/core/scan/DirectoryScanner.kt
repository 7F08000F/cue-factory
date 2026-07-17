package com.cuefactory.core.scan

import com.cuefactory.core.audio.FlacMetadataReader
import com.cuefactory.core.model.AudioContainer
import com.cuefactory.core.model.AudioSource
import com.cuefactory.core.model.CueOrigin
import com.cuefactory.core.model.CueSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recursive directory scan for whole-album audio + external CUE files.
 * Optionally probes FLAC for embedded `cuesheet=` (Vorbis).
 */
class DirectoryScanner(
    private val metadataReader: FlacMetadataReader = FlacMetadataReader(),
    private val probeEmbeddedCue: Boolean = true,
) {
    data class Progress(
        val visitedFiles: Int,
        val audioFound: Int,
        val cueFound: Int,
        val currentPath: String?,
    )

    data class Result(
        val audioSources: List<AudioSource>,
        val externalCues: List<CueSource>,
        /** audio absolute path → embedded cue source */
        val embeddedByAudioPath: Map<String, CueSource>,
        val errors: List<String> = emptyList(),
        val cancelled: Boolean = false,
    )

    fun scan(
        roots: List<File>,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: ((Progress) -> Unit)? = null,
    ): Result {
        val audio = linkedMapOf<String, AudioSource>()
        val cues = linkedMapOf<String, CueSource>()
        val embedded = linkedMapOf<String, CueSource>()
        val errors = mutableListOf<String>()
        var visited = 0

        fun emit(current: String?) {
            onProgress?.invoke(
                Progress(
                    visitedFiles = visited,
                    audioFound = audio.size,
                    cueFound = cues.size,
                    currentPath = current,
                ),
            )
        }

        for (root in roots) {
            if (cancelled.get()) break
            if (!root.exists()) {
                errors += "Missing path: ${root.path}"
                continue
            }
            walk(root, cancelled) { file ->
                visited++
                if (visited % 25 == 0) emit(file.path)
                val name = file.name
                val lower = name.lowercase()
                when {
                    lower.endsWith(".cue") -> {
                        cues[file.absolutePath] = CueSource(
                            origin = CueOrigin.External,
                            path = file.absolutePath,
                            displayName = name,
                            preloadedText = null,
                            charsetName = null,
                        )
                    }
                    isAudioName(lower) -> {
                        val container = containerOf(lower)
                        val source = AudioSource(
                            path = file.absolutePath,
                            fileName = name,
                            parentDir = file.parentFile?.absolutePath ?: "",
                            container = container,
                            sizeBytes = file.length(),
                        )
                        audio[file.absolutePath] = source
                        if (probeEmbeddedCue && container == AudioContainer.Flac) {
                            try {
                                val meta = metadataReader.read(file)
                                val text = meta.embeddedCueSheetText()
                                if (!text.isNullOrBlank()) {
                                    embedded[file.absolutePath] = CueSource(
                                        origin = CueOrigin.EmbeddedVorbis,
                                        path = file.absolutePath,
                                        displayName = "$name (embedded cuesheet)",
                                        preloadedText = text,
                                        charsetName = "UTF-8",
                                    )
                                }
                            } catch (e: Exception) {
                                errors += "FLAC probe failed ${file.name}: ${e.message}"
                            }
                        }
                    }
                }
            }
        }
        emit(null)
        return Result(
            audioSources = audio.values.toList(),
            externalCues = cues.values.toList(),
            embeddedByAudioPath = embedded.toMap(),
            errors = errors,
            cancelled = cancelled.get(),
        )
    }

    private fun walk(root: File, cancelled: AtomicBoolean, onFile: (File) -> Unit) {
        if (cancelled.get()) return
        if (root.isFile) {
            onFile(root)
            return
        }
        if (!root.isDirectory) return
        val children = root.listFiles() ?: return
        for (child in children.sortedBy { it.name.lowercase() }) {
            if (cancelled.get()) return
            if (child.isDirectory) {
                // skip common noise / previous split outputs
                val n = child.name
                if (n.startsWith(".")) continue
                if (n.equals("out", ignoreCase = true) ||
                    n.equals("output", ignoreCase = true) ||
                    n.equals("split", ignoreCase = true)
                ) {
                    continue
                }
                walk(child, cancelled, onFile)
            } else if (child.isFile) {
                onFile(child)
            }
        }
    }

    companion object {
        fun isAudioName(lower: String): Boolean =
            lower.endsWith(".flac") ||
                lower.endsWith(".wav") ||
                lower.endsWith(".ape") ||
                lower.endsWith(".wv") ||
                lower.endsWith(".tak") ||
                lower.endsWith(".aiff") ||
                lower.endsWith(".aif")

        fun containerOf(lower: String): AudioContainer = when {
            lower.endsWith(".flac") -> AudioContainer.Flac
            lower.endsWith(".wav") || lower.endsWith(".aiff") || lower.endsWith(".aif") ->
                AudioContainer.Wav
            lower.endsWith(".ape") -> AudioContainer.Ape
            else -> AudioContainer.Other
        }
    }
}
