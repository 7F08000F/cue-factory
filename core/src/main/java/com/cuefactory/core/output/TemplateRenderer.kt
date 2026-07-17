package com.cuefactory.core.output

import com.cuefactory.core.model.OutputTemplate
import com.cuefactory.core.model.TrackNameContext
import java.io.File

/**
 * Renders directory / file name templates and sanitizes path segments.
 */
class TemplateRenderer(
    private val invalidReplacement: String = "_",
) {
    fun renderDirectory(template: String, ctx: TrackNameContext): String {
        val raw = applyVars(template, ctx)
        return raw.split('/', '\\')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString(File.separator) { sanitizeSegment(it) }
    }

    fun renderFileName(template: String, ctx: TrackNameContext, extension: String): String {
        val base = sanitizeSegment(applyVars(template, ctx).replace('/', invalidReplacement[0]).replace('\\', invalidReplacement[0]))
        val ext = extension.removePrefix(".")
        return if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
    }

    fun renderRelativePath(template: OutputTemplate, ctx: TrackNameContext): String {
        val dir = renderDirectory(template.directoryTemplate, ctx)
        val file = renderFileName(template.fileNameTemplate, ctx, template.extension)
        return if (dir.isEmpty()) file else dir + File.separator + file
    }

    fun applyVars(template: String, ctx: TrackNameContext): String {
        var out = template
        out = replaceTrackPattern(out, ctx.track)
        val vars = mapOf(
            "title" to (ctx.title),
            "artist" to (ctx.artist ?: ""),
            "album" to (ctx.album ?: ""),
            "album_artist" to (ctx.albumArtist ?: ctx.artist ?: ""),
            "year" to (ctx.year ?: ""),
            "genre" to (ctx.genre ?: ""),
            "disc" to (ctx.disc?.toString() ?: ""),
            "track" to ctx.track.toString(),
        )
        for ((key, value) in vars) {
            out = out.replace("{$key}", value, ignoreCase = true)
        }
        // Strip any remaining unknown {placeholders}
        out = out.replace(Regex("""\{[^{}]+\}"""), "")
        return out.trim()
    }

    /**
     * Supports `{track}` and `{track:02d}` style width.
     */
    private fun replaceTrackPattern(template: String, track: Int): String {
        val re = Regex("""\{track(?::0(\d+)d)?\}""", RegexOption.IGNORE_CASE)
        return re.replace(template) { m ->
            val width = m.groupValues.getOrNull(1)?.toIntOrNull()
            if (width != null) track.toString().padStart(width, '0') else track.toString()
        }
    }

    fun sanitizeSegment(segment: String): String {
        val illegal = Regex("""[<>:"/\\|?*\u0000-\u001F]""")
        var s = segment.replace(illegal, invalidReplacement)
        s = s.trim().trimEnd('.')
        if (s.isEmpty()) s = "_"
        // Windows reserved device names
        val reserved = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
        if (s.uppercase() in reserved) s = "_$s"
        return s
    }
}
