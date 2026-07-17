package com.cuefactory.core.cue

import com.cuefactory.core.model.CueParseResult
import java.nio.charset.Charset

/**
 * Loads CUE bytes with auto or explicit charset, then parses.
 */
class CueLoader(
    private val parser: CueParser = CueParser(),
) {
    data class LoadResult(
        val parse: CueParseResult,
        val probe: CharsetProbe.ProbeResult,
    )

    fun load(
        bytes: ByteArray,
        charsetName: String? = null,
    ): LoadResult {
        val (text, probe) = CharsetProbe.decode(bytes, charsetName)
        val parse = parser.parseText(text, sourceCharset = probe.charsetName)
        return LoadResult(parse = parse, probe = probe)
    }

    fun loadWithCharset(
        bytes: ByteArray,
        charset: Charset,
    ): LoadResult {
        return load(bytes, charsetName = charset.name())
    }
}
