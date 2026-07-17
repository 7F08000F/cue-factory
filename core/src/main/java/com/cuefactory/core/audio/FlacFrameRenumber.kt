package com.cuefactory.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Rewrites FLAC frame headers so a sliced/stitched stream is a valid standalone file.
 *
 * Problems we fix:
 * 1. Frame-copy / hybrid middle keep the parent album's absolute frame/sample numbers
 *    → many players refuse to play (Poweramp is more tolerant).
 * 2. Hybrid lead/trail often have a different block size than the middle. Keeping the
 *    fixed-blocksize strategy makes naive decoders compute pts as
 *    `frame_number * STREAMINFO.blocksize`, so timestamps jump. For mixed block sizes we
 *    emit **variable** strategy with absolute sample numbers.
 *
 * For pure FrameCopy of uniform block-size slices we keep **fixed** strategy and only
 * renumber frame indices 0,1,2… (preserves byte-identical payload when numbers already 0-based).
 *
 * Header CRC-8 and frame CRC-16 are recomputed. UTF-8 coded number length may change.
 */
internal object FlacFrameRenumber {
    private const val MIN_FRAME = 6

    enum class Mode {
        /** Keep fixed strategy; write frame index (0,1,2…). Requires uniform block size. */
        FixedFrameIndex,
        /** Force variable strategy; write absolute sample number. Safe for hybrid stitch. */
        VariableSampleNumber,
    }

    /**
     * Copy [frames] from [source], rewriting headers per [mode].
     *
     * @return pair of (framesWritten, samplesWritten)
     */
    fun copyRenumbered(
        source: File,
        frames: List<FlacFrameIndex.Frame>,
        out: RandomAccessFile,
        baseSample: Long = 0L,
        baseFrameIndex: Int = 0,
        mode: Mode = Mode.VariableSampleNumber,
    ): Pair<Int, Long> {
        if (frames.isEmpty()) return 0 to 0L

        // Fast path: pure FrameCopy of prefix already numbered 0..n-1 with fixed strategy
        // and [mode] FixedFrameIndex — raw byte copy is valid and bit-identical.
        if (mode == Mode.FixedFrameIndex &&
            baseFrameIndex == 0 &&
            baseSample == 0L &&
            frames.first().index == 0
        ) {
            val first = frames.first()
            val last = frames.last()
            FlacRemuxWriter.copyBytes(source, first.byteOffset, last.endByte - first.byteOffset, out)
            return frames.size to frames.sumOf { it.sampleCount.toLong() }
        }

        var sampleCursor = baseSample
        var frameCursor = baseFrameIndex
        var samplesWritten = 0L
        RandomAccessFile(source, "r").use { input ->
            for (frame in frames) {
                val raw = ByteArray(frame.byteLength)
                input.seek(frame.byteOffset)
                input.readFully(raw)
                val rewritten = when (mode) {
                    Mode.FixedFrameIndex -> renumberFrame(
                        raw,
                        number = frameCursor.toLong(),
                        forceVariable = false,
                    )
                    Mode.VariableSampleNumber -> renumberFrame(
                        raw,
                        number = sampleCursor,
                        forceVariable = true,
                    )
                }
                out.write(rewritten)
                sampleCursor += frame.sampleCount
                samplesWritten += frame.sampleCount
                frameCursor++
            }
        }
        return frames.size to samplesWritten
    }

    /**
     * Walk every frame in a FLAC file and rewrite numbers starting at [baseSample].
     * Always uses variable sample numbers (hybrid lead/trail may differ in block size).
     */
    fun copyFileAudioRenumbered(
        source: File,
        out: RandomAccessFile,
        baseSample: Long = 0L,
        baseFrameIndex: Int = 0,
    ): Pair<Int, Long> {
        val index = FlacFrameIndex().build(source)
        return copyRenumbered(
            source = source,
            frames = index.frames,
            out = out,
            baseSample = baseSample,
            baseFrameIndex = baseFrameIndex,
            mode = Mode.VariableSampleNumber,
        )
    }

    /**
     * Prefer fixed frame indices when every frame shares the same sample count;
     * otherwise variable sample numbers (required for hybrid mixed edges).
     */
    fun preferredMode(frames: List<FlacFrameIndex.Frame>): Mode {
        if (frames.isEmpty()) return Mode.FixedFrameIndex
        val n = frames.first().sampleCount
        return if (frames.all { it.sampleCount == n }) Mode.FixedFrameIndex else Mode.VariableSampleNumber
    }

    /**
     * Rewrite coded number; optionally force variable blocking strategy.
     */
    fun renumberFrame(
        frame: ByteArray,
        number: Long,
        forceVariable: Boolean,
    ): ByteArray {
        if (frame.size < MIN_FRAME + 2) {
            throw IllegalArgumentException("Frame too short: ${frame.size}")
        }
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xFE) != 0xF8) {
            throw IllegalArgumentException("Not a FLAC frame sync")
        }
        val utf = readUtf8(frame, 4) ?: throw IllegalArgumentException("Bad frame number UTF-8")
        val oldNumLen = utf.length

        var pos = 4 + oldNumLen
        val blockSizeCode = (frame[2].toInt() and 0xF0) shr 4
        val sampleRateCode = frame[2].toInt() and 0x0F
        pos = skipBlockSizeExtra(blockSizeCode, frame, pos)
        pos = skipSampleRateExtra(sampleRateCode, frame, pos)
        if (pos >= frame.size - 2) {
            throw IllegalArgumentException("Truncated frame header")
        }
        val headerCrcPos = pos
        val storedHeaderCrc = frame[headerCrcPos].toInt() and 0xFF
        val calcHeaderCrc = FlacCrc.crc8(frame, 0, headerCrcPos)
        if (storedHeaderCrc != calcHeaderCrc) {
            throw IllegalArgumentException(
                "Header CRC-8 mismatch (stored=$storedHeaderCrc calc=$calcHeaderCrc)",
            )
        }

        val newUtf = encodeUtf8(number)
        val newB1 = if (forceVariable) {
            (b1 or 0x01).toByte()
        } else {
            // Keep fixed strategy (clear variable bit).
            (b1 and 0xFE).toByte()
        }

        val strategyOk = if (forceVariable) (b1 and 0x01) != 0 else (b1 and 0x01) == 0
        if (strategyOk && newUtf.contentEquals(frame.copyOfRange(4, 4 + oldNumLen))) {
            return frame
        }

        val bodyStart = headerCrcPos + 1 // after CRC-8
        val bodyEnd = frame.size - 2 // exclude CRC-16
        if (bodyEnd < bodyStart) {
            throw IllegalArgumentException("Degenerate frame body")
        }

        val extrasLen = headerCrcPos - (4 + oldNumLen)
        val newHeaderWithoutCrc = ByteArray(4 + newUtf.size + extrasLen)
        newHeaderWithoutCrc[0] = frame[0]
        newHeaderWithoutCrc[1] = newB1
        newHeaderWithoutCrc[2] = frame[2]
        newHeaderWithoutCrc[3] = frame[3]
        System.arraycopy(newUtf, 0, newHeaderWithoutCrc, 4, newUtf.size)
        if (extrasLen > 0) {
            System.arraycopy(frame, 4 + oldNumLen, newHeaderWithoutCrc, 4 + newUtf.size, extrasLen)
        }
        val newHeaderCrc = FlacCrc.crc8(newHeaderWithoutCrc)

        val out = ByteArray(newHeaderWithoutCrc.size + 1 + (bodyEnd - bodyStart) + 2)
        var o = 0
        System.arraycopy(newHeaderWithoutCrc, 0, out, o, newHeaderWithoutCrc.size)
        o += newHeaderWithoutCrc.size
        out[o++] = newHeaderCrc.toByte()
        System.arraycopy(frame, bodyStart, out, o, bodyEnd - bodyStart)
        o += bodyEnd - bodyStart
        val frameCrc = FlacCrc.crc16(out, 0, o)
        out[o++] = ((frameCrc shr 8) and 0xFF).toByte()
        out[o] = (frameCrc and 0xFF).toByte()
        return out
    }

    private data class Utf8(val value: Long, val length: Int)

    private fun readUtf8(buf: ByteArray, offset: Int): Utf8? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        val bytes = when {
            first < 0x80 -> 1
            first and 0xE0 == 0xC0 -> 2
            first and 0xF0 == 0xE0 -> 3
            first and 0xF8 == 0xF0 -> 4
            first and 0xFC == 0xF8 -> 5
            first and 0xFE == 0xFC -> 6
            first == 0xFE -> 7
            else -> return null
        }
        if (offset + bytes > buf.size) return null
        var v = when (bytes) {
            1 -> first.toLong()
            else -> (first and (0x7F shr bytes)).toLong()
        }
        for (i in 1 until bytes) {
            val c = buf[offset + i].toInt() and 0xFF
            if (c and 0xC0 != 0x80) return null
            v = (v shl 6) or (c and 0x3F).toLong()
        }
        return Utf8(v, bytes)
    }

    /** FLAC "UTF-8" coded number (up to 36-bit payload, 1–7 bytes). */
    internal fun encodeUtf8(n: Long): ByteArray {
        require(n >= 0) { "negative coded number" }
        if (n < 0x80) return byteArrayOf(n.toByte())
        // data-bit capacities for 2..7 byte encodings
        val caps = intArrayOf(0, 7, 11, 16, 21, 26, 31, 36)
        for (nbytes in 2..7) {
            val cap = caps[nbytes]
            if (n < (1L shl cap) || nbytes == 7) {
                val firstDataBits = (7 - nbytes).coerceAtLeast(0)
                val shift = 6 * (nbytes - 1)
                var first = ((1 shl nbytes) - 1) shl (8 - nbytes)
                if (firstDataBits > 0) {
                    first = first or (((n shr shift).toInt()) and ((1 shl firstDataBits) - 1))
                }
                val out = ByteArray(nbytes)
                out[0] = first.toByte()
                var idx = 1
                for (k in (nbytes - 2) downTo 0) {
                    out[idx++] = (0x80 or (((n shr (6 * k)).toInt()) and 0x3F)).toByte()
                }
                return out
            }
        }
        throw IllegalArgumentException("number too large for FLAC UTF-8: $n")
    }

    private fun skipBlockSizeExtra(code: Int, buf: ByteArray, offset: Int): Int {
        return when (code) {
            6 -> offset + 1
            7 -> offset + 2
            else -> offset
        }.also {
            if (it > buf.size) throw IllegalArgumentException("blocksize extra past end")
        }
    }

    private fun skipSampleRateExtra(code: Int, buf: ByteArray, offset: Int): Int {
        return when (code) {
            12 -> offset + 1
            13, 14 -> offset + 2
            else -> offset
        }.also {
            if (it > buf.size) throw IllegalArgumentException("samplerate extra past end")
        }
    }
}
