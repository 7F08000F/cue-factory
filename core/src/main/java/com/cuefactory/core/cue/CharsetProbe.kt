package com.cuefactory.core.cue

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Detects a plausible charset for CUE sheet bytes.
 *
 * Order (auto): UTF-8 (strict, with BOM) → Windows-1251 → IBM866 (CP866) →
 * other registered fallbacks → ISO-8859-1 last resort.
 */
object CharsetProbe {

    data class ProbeResult(
        val charset: Charset,
        val charsetName: String,
        val confidence: Confidence,
        val decodedPreview: String,
    )

    enum class Confidence {
        Bom,
        StrictUtf8,
        Heuristic,
        Fallback,
        Explicit,
    }

    val defaultCandidates: List<Charset> = listOf(
        StandardCharsets.UTF_8,
        charsetOrNull("windows-1251"),
        charsetOrNull("IBM866"),
        charsetOrNull("KOI8-R"),
        charsetOrNull("GBK"),
        charsetOrNull("GB18030"),
        charsetOrNull("Big5"),
        charsetOrNull("Shift_JIS"),
        charsetOrNull("EUC-KR"),
        charsetOrNull("windows-1252"),
        StandardCharsets.ISO_8859_1,
    ).filterNotNull()

    /** UI-facing charset choices for manual override. */
    val uiChoices: List<Pair<String?, String>> = listOf(
        null to "自动探测",
        "UTF-8" to "UTF-8",
        "windows-1251" to "Windows-1251 (俄文)",
        "IBM866" to "CP866 (DOS 俄文)",
        "KOI8-R" to "KOI8-R",
        "GBK" to "GBK (简中)",
        "GB18030" to "GB18030",
        "Shift_JIS" to "Shift_JIS (日文)",
        "Big5" to "Big5 (繁中)",
        "EUC-KR" to "EUC-KR (韩文)",
        "windows-1252" to "Windows-1252",
        "ISO-8859-1" to "ISO-8859-1",
    )

    fun probe(bytes: ByteArray, candidates: List<Charset> = defaultCandidates): ProbeResult {
        if (bytes.isEmpty()) {
            return ProbeResult(
                charset = StandardCharsets.UTF_8,
                charsetName = "UTF-8",
                confidence = Confidence.Fallback,
                decodedPreview = "",
            )
        }

        // UTF-8 BOM — still score vs others only if needed; BOM is authoritative
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            val text = String(bytes, StandardCharsets.UTF_8)
            return ProbeResult(
                charset = StandardCharsets.UTF_8,
                charsetName = "UTF-8",
                confidence = Confidence.Bom,
                decodedPreview = preview(text),
            )
        }

        // UTF-16 BOM
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if (b0 == 0xFF && b1 == 0xFE) {
                val cs = StandardCharsets.UTF_16LE
                val text = String(bytes, cs)
                return ProbeResult(cs, "UTF-16LE", Confidence.Bom, preview(text))
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                val cs = StandardCharsets.UTF_16BE
                val text = String(bytes, cs)
                return ProbeResult(cs, "UTF-16BE", Confidence.Bom, preview(text))
            }
        }

        // Strict UTF-8 that yields real Unicode scripts (or pure ASCII CUE) is preferred.
        // CP1251/CP866 Russian almost always fails strict UTF-8 — then 8-bit wins.
        // Never let single-byte re-interpret of valid UTF-8 Japanese/ASCII beat UTF-8.
        val utf8Text = decodeStrict(bytes, StandardCharsets.UTF_8)
        if (utf8Text != null) {
            val uScore = scoreDecoded(utf8Text, StandardCharsets.UTF_8)
            if (uScore.cjk > 0 || uScore.cyrillic > 0 || !bytes.any { it < 0 }) {
                // pure ASCII: all bytes >= 0 in signed byte is wrong check — use unsigned
            }
            val hasHigh = bytes.any { (it.toInt() and 0xff) >= 0x80 }
            if (uScore.cjk > 0 || uScore.cyrillic > 0 || !hasHigh) {
                return ProbeResult(
                    charset = StandardCharsets.UTF_8,
                    charsetName = "UTF-8",
                    confidence = if (!hasHigh) Confidence.StrictUtf8 else Confidence.Heuristic,
                    decodedPreview = preview(utf8Text),
                )
            }
            // Valid UTF-8 with only high-bit Latin (rare) — still compare below
        }

        var best: ProbeResult? = null
        var bestRaw = Int.MIN_VALUE
        for (cs in candidates) {
            val decoded = decodeStrict(bytes, cs) ?: continue
            val score = scoreDecoded(decoded, cs)
            // Single-byte encodings need real script signal for Russian/CJK albums
            val name = cs.name().uppercase()
            val isEightBit = name != "UTF-8" && !name.startsWith("UTF-16")
            if (isEightBit && score.cyrillic < 3 && score.cjk < 3) {
                continue // skip pure-ASCII-looking 8-bit picks
            }
            val conf = when {
                cs == StandardCharsets.UTF_8 && score.strictUtf8 -> Confidence.StrictUtf8
                score.value >= 80 -> Confidence.Heuristic
                else -> Confidence.Fallback
            }
            val candidate = ProbeResult(cs, displayName(cs), conf, preview(decoded))
            val total = score.value
            if (best == null || total > bestRaw || (total == bestRaw && prefer(cs, best.charset))) {
                best = candidate
                bestRaw = total
            }
        }

        return best ?: ProbeResult(
            charset = StandardCharsets.ISO_8859_1,
            charsetName = "ISO-8859-1",
            confidence = Confidence.Fallback,
            decodedPreview = preview(String(bytes, StandardCharsets.ISO_8859_1)),
        )
    }

    fun decode(
        bytes: ByteArray,
        charsetName: String? = null,
    ): Pair<String, ProbeResult> {
        if (charsetName != null) {
            val cs = Charset.forName(charsetName)
            val text = String(bytes, cs)
            val result = ProbeResult(cs, displayName(cs), Confidence.Explicit, preview(text))
            return text to result
        }
        val result = probe(bytes)
        return String(bytes, result.charset) to result
    }

    private fun bestScore(r: ProbeResult): Int = when (r.confidence) {
        Confidence.Bom -> 1000
        Confidence.StrictUtf8 -> 900
        Confidence.Explicit -> 800
        Confidence.Heuristic -> 500
        Confidence.Fallback -> 100
    } + r.decodedPreview.length.coerceAtMost(50)

    private fun prefer(a: Charset, b: Charset): Boolean {
        val order = defaultCandidates.map { it.name().uppercase() }
        val ia = order.indexOf(a.name().uppercase()).let { if (it < 0) 99 else it }
        val ib = order.indexOf(b.name().uppercase()).let { if (it < 0) 99 else it }
        return ia < ib
    }

    private data class Score(
        val value: Int,
        val strictUtf8: Boolean,
        val replacementCount: Int,
        val cyrillic: Int,
        val cjk: Int,
    )

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun scoreDecoded(text: String, charset: Charset): Score {
        var score = 0
        var replacement = 0
        var cyrillic = 0
        var cjk = 0
        var control = 0
        var printable = 0
        var suspiciousLatin = 0 // high-bit latin often means wrong charset (mojibake)

        for (ch in text) {
            when {
                ch == '\uFFFD' -> replacement++
                ch == '\n' || ch == '\r' || ch == '\t' -> printable++
                ch.code in 32..126 -> printable++
                ch in '\u0400'..'\u04FF' -> {
                    cyrillic++
                    printable++
                }
                ch in '\u3000'..'\u9FFF' || ch in '\uFF00'..'\uFFEF' -> {
                    cjk++
                    printable++
                }
                ch.code in 0x80..0xFF -> {
                    // Latin-1 supplement in a "UTF-8" decode of CP1251 is rare;
                    // in ISO-8859-1/1252 wrong picks these explode.
                    suspiciousLatin++
                    printable++
                }
                ch.isISOControl() -> control++
                else -> printable++
            }
        }

        score += printable
        score -= replacement * 50
        score -= control * 10
        score -= suspiciousLatin * 3

        val upper = text.uppercase()
        val keywords = listOf("FILE", "TRACK", "INDEX", "TITLE", "PERFORMER", "REM")
        val keywordHits = keywords.count { it in upper }
        score += keywordHits * 40

        // TITLE/PERFORMER lines with non-ASCII content boost real encodings
        val titleLines = text.lineSequence().count {
            val t = it.trim().uppercase()
            (t.startsWith("TITLE") || t.startsWith("PERFORMER")) &&
                it.any { ch -> ch.code > 127 }
        }
        score += titleLines * 25

        val name = charset.name().uppercase()
        if (cyrillic > 0) {
            score += cyrillic * 4
            score += when {
                "1251" in name -> 120
                name == "IBM866" || "866" in name -> 100
                name.contains("KOI8") -> 100
                name == "UTF-8" -> 50
                else -> 10
            }
        }
        if (cjk > 0) {
            score += cjk * 3
            score += when {
                name.contains("GB") || name.contains("BIG5") -> 100
                name.contains("SHIFT") || name.contains("EUC") -> 90
                name == "UTF-8" -> 60
                else -> 10
            }
        }

        val strictUtf8 = charset == StandardCharsets.UTF_8 && replacement == 0
        // Pure ASCII / UTF-8 without foreign scripts: prefer UTF-8 over 8-bit code pages
        if (strictUtf8 && cyrillic == 0 && cjk == 0) {
            score += 200
            // Penalize code pages that "succeed" on pure ASCII by inventing high-bit noise
            if (suspiciousLatin > 0) score -= suspiciousLatin * 10
        }

        return Score(score, strictUtf8, replacement, cyrillic, cjk)
    }

    private fun preview(text: String, maxChars: Int = 200): String =
        text.replace("\r\n", "\n").take(maxChars)

    private fun displayName(cs: Charset): String = when (cs.name().uppercase()) {
        "UTF-8" -> "UTF-8"
        "WINDOWS-1251" -> "windows-1251"
        "IBM866" -> "IBM866"
        "KOI8-R" -> "KOI8-R"
        "GBK" -> "GBK"
        "GB18030" -> "GB18030"
        "BIG5" -> "Big5"
        "SHIFT_JIS" -> "Shift_JIS"
        "EUC-KR" -> "EUC-KR"
        "WINDOWS-1252" -> "windows-1252"
        "ISO-8859-1" -> "ISO-8859-1"
        else -> cs.name()
    }

    private fun charsetOrNull(name: String): Charset? =
        try {
            Charset.forName(name)
        } catch (_: Exception) {
            null
        }
}
