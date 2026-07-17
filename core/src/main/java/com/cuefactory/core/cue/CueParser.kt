package com.cuefactory.core.cue

import com.cuefactory.core.model.CueFile
import com.cuefactory.core.model.CueIndex
import com.cuefactory.core.model.CueParseError
import com.cuefactory.core.model.CueParseResult
import com.cuefactory.core.model.CueSheet
import com.cuefactory.core.model.CueTrack
import java.nio.charset.Charset

/**
 * Tolerant CUE sheet parser.
 *
 * - Supports album/file/track TITLE, PERFORMER, SONGWRITER, INDEX, ISRC, CATALOG
 * - Keeps unknown REM keys (including REPLAYGAIN_*)
 * - Does not fail the whole sheet on a single unknown command line
 * - INDEX 01 is the preferred track start (see [CueTrack.startIndex])
 */
class CueParser {

    fun parseText(text: String, sourceCharset: String? = null): CueParseResult {
        val normalized = text
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (normalized.isBlank()) {
            return CueParseResult.Failure(CueParseError.EmptyInput, "CUE text is empty")
        }
        return try {
            val sheet = parseLines(normalized.lineSequence().withIndex().toList(), sourceCharset)
            if (sheet.trackCount == 0) {
                CueParseResult.Failure(CueParseError.NoTracks, "CUE contains no TRACK entries")
            } else {
                CueParseResult.Success(sheet)
            }
        } catch (e: ParseException) {
            CueParseResult.Failure(e.error, e.message ?: "parse error", e.lineNumber)
        }
    }

    fun parseBytes(
        bytes: ByteArray,
        charset: Charset = Charsets.UTF_8,
    ): CueParseResult {
        val text = try {
            String(bytes, charset)
        } catch (e: Exception) {
            return CueParseResult.Failure(
                CueParseError.UnsupportedCharset,
                "Failed to decode CUE as ${charset.name()}: ${e.message}",
            )
        }
        return parseText(text, sourceCharset = charset.name())
    }

    private fun parseLines(
        lines: List<IndexedValue<String>>,
        sourceCharset: String?,
    ): CueSheet {
        var title: String? = null
        var performer: String? = null
        var songwriter: String? = null
        var catalog: String? = null
        var genre: String? = null
        var date: String? = null
        val rem = linkedMapOf<String, String>()

        val files = mutableListOf<CueFile>()
        var currentFileName: String? = null
        var currentFileType: String = "WAVE"
        var currentTracks = mutableListOf<CueTrack>()

        var trackNumber: Int? = null
        var trackType: String = "AUDIO"
        var trackTitle: String? = null
        var trackPerformer: String? = null
        var trackSongwriter: String? = null
        var trackIsrc: String? = null
        var trackIndexes = mutableListOf<CueIndex>()
        var trackRem = linkedMapOf<String, String>()

        fun flushTrack() {
            val num = trackNumber ?: return
            currentTracks += CueTrack(
                number = num,
                type = trackType,
                title = trackTitle,
                performer = trackPerformer,
                songwriter = trackSongwriter,
                isrc = trackIsrc,
                indexes = trackIndexes.toList(),
                rem = trackRem.toMap(),
            )
            trackNumber = null
            trackType = "AUDIO"
            trackTitle = null
            trackPerformer = null
            trackSongwriter = null
            trackIsrc = null
            trackIndexes = mutableListOf()
            trackRem = linkedMapOf()
        }

        fun flushFile() {
            flushTrack()
            val name = currentFileName
            if (name != null) {
                files += CueFile(
                    name = name,
                    fileType = currentFileType,
                    tracks = currentTracks.toList(),
                )
            } else if (currentTracks.isNotEmpty()) {
                // Tracks without FILE — still keep them under empty file name for robustness
                files += CueFile(
                    name = "",
                    fileType = currentFileType,
                    tracks = currentTracks.toList(),
                )
            }
            currentFileName = null
            currentFileType = "WAVE"
            currentTracks = mutableListOf()
        }

        for ((index, rawLine) in lines) {
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val tokens = tokenize(line)
            if (tokens.isEmpty()) continue
            val cmd = tokens[0].uppercase()

            when (cmd) {
                "REM" -> {
                    if (tokens.size >= 2) {
                        val key = tokens[1].uppercase()
                        val value = if (tokens.size >= 3) {
                            unquote(tokens.drop(2).joinToString(" "))
                        } else {
                            ""
                        }
                        when (key) {
                            "GENRE" -> if (trackNumber == null) genre = value else trackRem[key] = value
                            "DATE" -> if (trackNumber == null) date = value else trackRem[key] = value
                            else -> {
                                if (trackNumber == null) rem[key] = value else trackRem[key] = value
                            }
                        }
                    }
                }

                "TITLE" -> {
                    val value = unquote(tokens.drop(1).joinToString(" "))
                    if (trackNumber == null) title = value else trackTitle = value
                }

                "PERFORMER" -> {
                    val value = unquote(tokens.drop(1).joinToString(" "))
                    if (trackNumber == null) performer = value else trackPerformer = value
                }

                "SONGWRITER" -> {
                    val value = unquote(tokens.drop(1).joinToString(" "))
                    if (trackNumber == null) songwriter = value else trackSongwriter = value
                }

                "CATALOG" -> {
                    if (tokens.size >= 2) catalog = tokens[1]
                }

                "FILE" -> {
                    flushFile()
                    if (tokens.size < 2) {
                        throw ParseException(
                            CueParseError.InvalidSyntax,
                            "FILE missing name",
                            lineNumber,
                        )
                    }
                    // FILE "name" TYPE — type is last token if unquoted
                    val rest = tokens.drop(1)
                    val typeCandidate = rest.lastOrNull()?.uppercase()
                    val knownTypes = setOf(
                        "WAVE", "MP3", "AIFF", "BINARY", "MOTOROLA", "FLAC", "APE", "WV",
                    )
                    if (rest.size >= 2 && typeCandidate in knownTypes) {
                        currentFileType = typeCandidate!!
                        currentFileName = unquote(rest.dropLast(1).joinToString(" "))
                    } else {
                        currentFileName = unquote(rest.joinToString(" "))
                        currentFileType = "WAVE"
                    }
                }

                "TRACK" -> {
                    flushTrack()
                    if (tokens.size < 2) {
                        throw ParseException(
                            CueParseError.InvalidSyntax,
                            "TRACK missing number",
                            lineNumber,
                        )
                    }
                    trackNumber = tokens[1].toIntOrNull()
                        ?: throw ParseException(
                            CueParseError.InvalidSyntax,
                            "TRACK number invalid: ${tokens[1]}",
                            lineNumber,
                        )
                    trackType = tokens.getOrNull(2)?.uppercase() ?: "AUDIO"
                }

                "INDEX" -> {
                    if (trackNumber == null) continue
                    if (tokens.size < 3) {
                        throw ParseException(
                            CueParseError.InvalidSyntax,
                            "INDEX needs number and time",
                            lineNumber,
                        )
                    }
                    val idxNum = tokens[1].toIntOrNull()
                        ?: throw ParseException(
                            CueParseError.InvalidSyntax,
                            "INDEX number invalid",
                            lineNumber,
                        )
                    val time = CueTimeParser.parse(tokens[2])
                        ?: throw ParseException(
                            CueParseError.InvalidTime,
                            "Invalid INDEX time: ${tokens[2]}",
                            lineNumber,
                        )
                    trackIndexes += CueIndex(idxNum, time)
                }

                "ISRC" -> {
                    if (trackNumber != null && tokens.size >= 2) {
                        trackIsrc = tokens[1]
                    }
                }

                "FLAGS", "PREGAP", "POSTGAP", "CDTEXTFILE" -> {
                    // Recognized but not modeled deeply in MVP — ignore safely
                }

                else -> {
                    // Unknown command: tolerate (custom tools, garbage)
                }
            }
        }

        flushFile()

        return CueSheet(
            title = title,
            performer = performer,
            songwriter = songwriter,
            catalog = catalog,
            genre = genre,
            date = date,
            rem = rem.toMap(),
            files = files.toList(),
            sourceCharset = sourceCharset,
        )
    }

    /**
     * Tokenize a CUE line respecting double-quoted strings.
     */
    private fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    inQuote = !inQuote
                    sb.append(c)
                }
                !inQuote && c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        out += sb.toString()
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun unquote(raw: String): String {
        val t = raw.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }

    private class ParseException(
        val error: CueParseError,
        message: String,
        val lineNumber: Int? = null,
    ) : Exception(message)
}
