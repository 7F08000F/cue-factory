package com.cuefactory.core.match

import com.cuefactory.core.cue.CueLoader
import com.cuefactory.core.model.AmbiguousMatch
import com.cuefactory.core.model.AudioSource
import com.cuefactory.core.model.CueOrigin
import com.cuefactory.core.model.CueParseResult
import com.cuefactory.core.model.CueSheet
import com.cuefactory.core.model.CueSource
import com.cuefactory.core.model.MatchReport
import com.cuefactory.core.model.MatchRule
import com.cuefactory.core.model.MatchedAlbum
import com.cuefactory.core.model.UnmatchedAudio
import java.io.File

/**
 * Precise CUE↔audio matching. Never auto-picks among ambiguous candidates.
 *
 * Priority when multiple precise rules could apply for the same audio:
 * external (basename / FILE / unique pair) before embedded.
 */
class MatchEngine(
    private val cueLoader: CueLoader = CueLoader(),
    private val charsetName: String? = null,
) {
    /**
     * @param audioSources scanned audio files
     * @param externalCues external .cue files (origin should be [CueOrigin.External])
     * @param embeddedByAudioPath map audio path → embedded cue source (vorbis/native)
     */
    fun match(
        audioSources: List<AudioSource>,
        externalCues: List<CueSource>,
        embeddedByAudioPath: Map<String, CueSource> = emptyMap(),
    ): MatchReport {
        val matched = mutableListOf<MatchedAlbum>()
        val ambiguous = mutableListOf<AmbiguousMatch>()
        val unmatched = mutableListOf<UnmatchedAudio>()
        val usedCuePaths = mutableSetOf<String>()
        val usedAudioPaths = mutableSetOf<String>()

        // Parse external cues once
        val parsedExternal = externalCues.mapNotNull { cue ->
            val sheet = loadSheet(cue) ?: return@mapNotNull null
            ParsedCue(cue, sheet)
        }

        // --- Pass 1: basename (external only) ---
        for (audio in audioSources) {
            if (audio.path in usedAudioPaths) continue
            val audioBase = basenameNoExt(audio.fileName)
            val hits = parsedExternal.filter {
                it.cue.path !in usedCuePaths &&
                    basenameNoExt(it.cue.displayName).equals(audioBase, ignoreCase = true)
            }
            when {
                hits.size == 1 -> {
                    val hit = hits.single()
                    matched += MatchedAlbum(audio, hit.cue, hit.sheet, MatchRule.Basename)
                    usedAudioPaths += audio.path
                    usedCuePaths += hit.cue.path
                }
                hits.size > 1 -> {
                    ambiguous += AmbiguousMatch(
                        audioCandidates = listOf(audio),
                        cueCandidates = hits.map { it.cue },
                        message = "Multiple external CUE files share basename with ${audio.fileName}",
                    )
                    usedAudioPaths += audio.path
                    hits.forEach { usedCuePaths += it.cue.path }
                }
            }
        }

        // --- Pass 2: FILE directive (external) ---
        for (audio in audioSources) {
            if (audio.path in usedAudioPaths) continue
            val hits = parsedExternal.filter { parsed ->
                parsed.cue.path !in usedCuePaths &&
                    sameParent(audio.parentDir, parentOf(parsed.cue.path)) &&
                    fileDirectiveMatches(parsed.sheet, audio.fileName)
            }
            when {
                hits.size == 1 -> {
                    val hit = hits.single()
                    matched += MatchedAlbum(audio, hit.cue, hit.sheet, MatchRule.CueFileDirective)
                    usedAudioPaths += audio.path
                    usedCuePaths += hit.cue.path
                }
                hits.size > 1 -> {
                    ambiguous += AmbiguousMatch(
                        audioCandidates = listOf(audio),
                        cueCandidates = hits.map { it.cue },
                        message = "Multiple CUE FILE directives match ${audio.fileName}",
                    )
                    usedAudioPaths += audio.path
                    hits.forEach { usedCuePaths += it.cue.path }
                }
            }
        }

        // --- Pass 3: unique pair in directory (1 audio + 1 unused external cue) ---
        val audioByDir = audioSources
            .filter { it.path !in usedAudioPaths }
            .groupBy { normalizeDir(it.parentDir) }
        val cueByDir = parsedExternal
            .filter { it.cue.path !in usedCuePaths }
            .groupBy { normalizeDir(parentOf(it.cue.path)) }

        val dirs = (audioByDir.keys + cueByDir.keys).toSet()
        for (dir in dirs) {
            val audios = audioByDir[dir].orEmpty()
            val cues = cueByDir[dir].orEmpty()
            if (audios.size == 1 && cues.size == 1) {
                val a = audios.single()
                val c = cues.single()
                if (a.path in usedAudioPaths || c.cue.path in usedCuePaths) continue
                matched += MatchedAlbum(a, c.cue, c.sheet, MatchRule.UniquePairInDirectory)
                usedAudioPaths += a.path
                usedCuePaths += c.cue.path
            } else if (audios.isNotEmpty() && cues.isNotEmpty() &&
                (audios.size > 1 || cues.size > 1)
            ) {
                // Only report if nothing was already matched from this set
                val freeAudios = audios.filter { it.path !in usedAudioPaths }
                val freeCues = cues.filter { it.cue.path !in usedCuePaths }
                if (freeAudios.size >= 1 && freeCues.size >= 1 &&
                    (freeAudios.size > 1 || freeCues.size > 1)
                ) {
                    ambiguous += AmbiguousMatch(
                        audioCandidates = freeAudios,
                        cueCandidates = freeCues.map { it.cue },
                        message = "Ambiguous multi audio/CUE set in $dir",
                    )
                    freeAudios.forEach { usedAudioPaths += it.path }
                    freeCues.forEach { usedCuePaths += it.cue.path }
                }
            }
        }

        // --- Pass 4: embedded CUE for remaining audio ---
        for (audio in audioSources) {
            if (audio.path in usedAudioPaths) continue
            val embedded = embeddedByAudioPath[audio.path]
            if (embedded == null) {
                unmatched += UnmatchedAudio(audio, "No external or embedded CUE")
                continue
            }
            val sheet = loadSheet(embedded)
            if (sheet == null) {
                unmatched += UnmatchedAudio(audio, "Embedded CUE failed to parse")
                continue
            }
            matched += MatchedAlbum(audio, embedded, sheet, MatchRule.EmbeddedCue)
            usedAudioPaths += audio.path
        }

        return MatchReport(
            matched = matched,
            ambiguous = ambiguous,
            unmatched = unmatched,
        )
    }

    /**
     * Confirm one pairing chosen by the user from an ambiguous set.
     */
    fun confirm(
        audio: AudioSource,
        cue: CueSource,
        sheet: CueSheet? = null,
    ): MatchedAlbum? {
        val resolved = sheet ?: loadSheet(cue) ?: return null
        return MatchedAlbum(audio, cue, resolved, MatchRule.UserConfirmed)
    }

    private fun loadSheet(cue: CueSource): CueSheet? {
        // Prefer raw file bytes so charset auto-detect works (esp. Russian CP1251).
        // preloadedText is only used when there is no readable path (embedded cue).
        val file = File(cue.path)
        val result = if (file.isFile && cue.preloadedText == null) {
            cueLoader.load(file.readBytes(), charsetName = cue.charsetName ?: charsetName)
        } else if (cue.preloadedText != null) {
            // Embedded vorbis is UTF-8 by format; still allow override.
            cueLoader.load(
                cue.preloadedText!!.toByteArray(Charsets.UTF_8),
                charsetName = cue.charsetName ?: "UTF-8",
            )
        } else if (file.isFile) {
            // Have both path + preload: re-read bytes for accurate charset
            cueLoader.load(file.readBytes(), charsetName = cue.charsetName ?: charsetName)
        } else {
            return null
        }
        return (result.parse as? CueParseResult.Success)?.sheet
    }

    /** Re-parse a matched album with a different charset (null = auto). */
    fun reparseWithCharset(album: MatchedAlbum, charsetName: String?): MatchedAlbum? {
        val cue = album.cue.copy(charsetName = charsetName, preloadedText = null)
        val sheet = loadSheet(cue) ?: return null
        return album.copy(cue = cue.copy(preloadedText = album.cue.preloadedText), sheet = sheet)
    }

    private data class ParsedCue(val cue: CueSource, val sheet: CueSheet)

    companion object {
        fun basenameNoExt(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val dot = base.lastIndexOf('.')
            return if (dot > 0) base.substring(0, dot) else base
        }

        fun fileDirectiveMatches(sheet: CueSheet, audioFileName: String): Boolean {
            val audioBase = audioFileName.substringAfterLast('/').substringAfterLast('\\')
            return sheet.files.any { file ->
                val cueFile = file.name.substringAfterLast('/').substringAfterLast('\\')
                cueFile.equals(audioBase, ignoreCase = true)
            }
        }

        fun sameParent(a: String, b: String): Boolean =
            normalizeDir(a) == normalizeDir(b)

        fun parentOf(path: String): String {
            val f = File(path)
            return f.parent ?: ""
        }

        fun normalizeDir(dir: String): String =
            dir.trimEnd('/', '\\').lowercase()
    }
}
