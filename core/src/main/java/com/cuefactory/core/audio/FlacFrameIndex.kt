package com.cuefactory.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Indexes FLAC audio frames without decoding PCM.
 *
 * Strategy:
 * 1. Memory-map the file (single sequential view).
 * 2. Parse STREAMINFO from the map (no second open).
 * 3. Walk frames: zero-alloc header parse → CRC-16 end search using
 *    STREAMINFO min/max frame size to skip impossible positions.
 *
 * Spec: https://xiph.org/flac/format.html#frame
 */
class FlacFrameIndex {

    data class Frame(
        val index: Int,
        val startSample: Long,
        val sampleCount: Int,
        val byteOffset: Long,
        val byteLength: Int,
    ) {
        val endSample: Long get() = startSample + sampleCount
        val endByte: Long get() = byteOffset + byteLength
    }

    data class Index(
        val streamInfo: FlacMetadataReader.StreamInfo,
        val audioDataOffset: Long,
        val frames: List<Frame>,
    ) {
        val totalSamplesFromFrames: Long
            get() = frames.sumOf { it.sampleCount.toLong() }

        fun findBoundary(sample: Long): FrameBoundary? {
            if (frames.isEmpty()) return null
            if (sample == 0L) {
                val first = frames.first()
                return FrameBoundary(frameIndex = 0, sample = 0L, byteOffset = first.byteOffset)
            }
            if (sample == totalSamplesFromFrames) {
                val last = frames.last()
                return FrameBoundary(
                    frameIndex = frames.size,
                    sample = sample,
                    byteOffset = last.endByte,
                )
            }
            val idx = frames.binarySearchBy(sample) { it.startSample }
            if (idx >= 0) {
                val f = frames[idx]
                return FrameBoundary(frameIndex = idx, sample = sample, byteOffset = f.byteOffset)
            }
            return null
        }

        fun slice(startSample: Long, endSample: Long): FrameSlice? {
            if (endSample < startSample) return null
            val start = findBoundary(startSample) ?: return null
            val end = findBoundary(endSample) ?: return null
            if (start.frameIndex > end.frameIndex) return null
            val selected = frames.subList(start.frameIndex, end.frameIndex)
            if (selected.isEmpty()) {
                return FrameSlice(
                    startSample = startSample,
                    endSample = endSample,
                    byteOffset = start.byteOffset,
                    byteLength = 0,
                    frames = emptyList(),
                )
            }
            val first = selected.first()
            val last = selected.last()
            return FrameSlice(
                startSample = startSample,
                endSample = endSample,
                byteOffset = first.byteOffset,
                byteLength = (last.endByte - first.byteOffset).toInt(),
                frames = selected,
            )
        }
    }

    data class FrameBoundary(
        val frameIndex: Int,
        val sample: Long,
        val byteOffset: Long,
    )

    data class FrameSlice(
        val startSample: Long,
        val endSample: Long,
        val byteOffset: Long,
        val byteLength: Int,
        val frames: List<Frame>,
    )

    /**
     * @param onProgress optional (bytesScanned, totalBytes) for UI; throttled by caller if needed.
     */
    fun build(
        file: File,
        onProgress: ((bytesScanned: Long, totalBytes: Long) -> Unit)? = null,
    ): Index {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val fileLen = channel.size()
            if (fileLen > Int.MAX_VALUE) {
                throw IllegalArgumentException(
                    "FLAC larger than 2GiB is not supported by mmap frame index (${fileLen} bytes)",
                )
            }
            val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileLen)
            map.order(ByteOrder.BIG_ENDIAN)

            val meta = parseStreamInfoAndAudioOffset(map)
            val streamInfo = meta.streamInfo
            val metaEnd = meta.audioOffset
            val minFrameHint = streamInfo.minFrameSize.coerceAtLeast(0)
            val maxFrameHint = streamInfo.maxFrameSize.coerceAtLeast(0)

            val frames = ArrayList<Frame>(estimateFrameCapacity(streamInfo))
            var pos = metaEnd
            var sampleCursor = 0L
            var index = 0
            val len = fileLen
            val audioSpan = (len - metaEnd).coerceAtLeast(1L)
            // Report ~every 4% of file (or every ~64 frames on tiny files).
            val progressStride = (audioSpan / 25L).coerceAtLeast(256L * 1024L)
            var nextProgressAt = metaEnd + progressStride
            onProgress?.invoke(metaEnd, len)

            while (pos + MIN_FRAME_BYTES <= len) {
                val header = parseHeaderAt(map, pos)
                    ?: throw IllegalArgumentException("Invalid FLAC frame header at offset $pos")
                val next = findFrameEnd(
                    map = map,
                    frameStart = pos,
                    headerSize = header.headerSize,
                    fileLen = len,
                    minFrameSizeHint = minFrameHint,
                    maxFrameSizeHint = maxFrameHint,
                ) ?: throw IllegalArgumentException("Cannot locate end of FLAC frame at offset $pos")
                val length = (next - pos).toInt()
                if (length < MIN_FRAME_BYTES) {
                    throw IllegalArgumentException("Degenerate FLAC frame length $length at $pos")
                }
                frames += Frame(
                    index = index,
                    startSample = sampleCursor,
                    sampleCount = header.blockSize,
                    byteOffset = pos,
                    byteLength = length,
                )
                sampleCursor += header.blockSize
                pos = next
                index++
                if (onProgress != null && pos >= nextProgressAt) {
                    onProgress(pos, len)
                    nextProgressAt = pos + progressStride
                }
            }
            onProgress?.invoke(len, len)
            if (pos != len) {
                throw IllegalArgumentException("Trailing bytes after last FLAC frame: pos=$pos len=$len")
            }
            if (streamInfo.totalSamples > 0 && sampleCursor != streamInfo.totalSamples) {
                throw IllegalArgumentException(
                    "Frame sample sum $sampleCursor != STREAMINFO.totalSamples ${streamInfo.totalSamples}",
                )
            }
            return Index(
                streamInfo = streamInfo,
                audioDataOffset = metaEnd,
                frames = frames,
            )
        }
    }

    private data class HeaderInfo(
        val blockSize: Int,
        val headerSize: Int,
    )

    private data class MappedMeta(
        val streamInfo: FlacMetadataReader.StreamInfo,
        val audioOffset: Long,
    )

    /**
     * Walk metadata blocks once; extract STREAMINFO and return audio data offset.
     * Avoids a second RandomAccessFile open via [FlacMetadataReader].
     */
    private fun parseStreamInfoAndAudioOffset(map: ByteBuffer): MappedMeta {
        if (map.capacity() < 4) throw IllegalArgumentException("Not a FLAC file")
        if (map.get(0) != 'f'.code.toByte() ||
            map.get(1) != 'L'.code.toByte() ||
            map.get(2) != 'a'.code.toByte() ||
            map.get(3) != 'C'.code.toByte()
        ) {
            throw IllegalArgumentException("Not a FLAC file")
        }
        var pos = 4
        var last = false
        val cap = map.capacity()
        var streamInfo: FlacMetadataReader.StreamInfo? = null
        while (!last) {
            if (pos + 4 > cap) throw IllegalArgumentException("Truncated metadata")
            val header = map.get(pos).toInt() and 0xFF
            last = (header and 0x80) != 0
            val type = header and 0x7F
            val size = ((map.get(pos + 1).toInt() and 0xFF) shl 16) or
                ((map.get(pos + 2).toInt() and 0xFF) shl 8) or
                (map.get(pos + 3).toInt() and 0xFF)
            val payloadStart = pos + 4
            if (payloadStart + size > cap) throw IllegalArgumentException("Truncated metadata payload")
            if (type == 0) {
                if (size < 34) throw IllegalArgumentException("STREAMINFO too short")
                streamInfo = decodeStreamInfo(map, payloadStart)
            }
            pos = payloadStart + size
        }
        val info = streamInfo
            ?: throw IllegalArgumentException("Missing STREAMINFO metadata block")
        return MappedMeta(streamInfo = info, audioOffset = pos.toLong())
    }

    private fun decodeStreamInfo(map: ByteBuffer, off: Int): FlacMetadataReader.StreamInfo {
        val minBlock = ((map.get(off).toInt() and 0xFF) shl 8) or (map.get(off + 1).toInt() and 0xFF)
        val maxBlock = ((map.get(off + 2).toInt() and 0xFF) shl 8) or (map.get(off + 3).toInt() and 0xFF)
        val minFrame = ((map.get(off + 4).toInt() and 0xFF) shl 16) or
            ((map.get(off + 5).toInt() and 0xFF) shl 8) or
            (map.get(off + 6).toInt() and 0xFF)
        val maxFrame = ((map.get(off + 7).toInt() and 0xFF) shl 16) or
            ((map.get(off + 8).toInt() and 0xFF) shl 8) or
            (map.get(off + 9).toInt() and 0xFF)
        var packed = 0L
        for (i in 0 until 8) {
            packed = (packed shl 8) or (map.get(off + 10 + i).toInt() and 0xFF).toLong()
        }
        val sampleRate = ((packed ushr 44) and 0xFFFFF).toInt()
        val channels = (((packed ushr 41) and 0x7).toInt()) + 1
        val bitsPerSample = (((packed ushr 36) and 0x1F).toInt()) + 1
        val totalSamples = packed and 0xFFFFFFFFFL
        val md5 = ByteArray(16)
        for (i in 0 until 16) {
            md5[i] = map.get(off + 18 + i)
        }
        return FlacMetadataReader.StreamInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = totalSamples,
            minBlockSize = minBlock,
            maxBlockSize = maxBlock,
            minFrameSize = minFrame,
            maxFrameSize = maxFrame,
        )
    }

    /**
     * Zero-allocation frame header parse (reads directly from mmap).
     */
    private fun parseHeaderAt(map: ByteBuffer, offset: Long): HeaderInfo? {
        val fileLen = map.capacity().toLong()
        if (offset + 6 > fileLen) return null
        val o = offset.toInt()
        val b0 = map.get(o).toInt() and 0xFF
        val b1 = map.get(o + 1).toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xFE) != 0xF8) return null
        if ((b1 and 0x02) != 0) return null
        val b2 = map.get(o + 2).toInt() and 0xFF
        val b3 = map.get(o + 3).toInt() and 0xFF
        val blockSizeCode = (b2 and 0xF0) shr 4
        val sampleRateCode = b2 and 0x0F
        val channelAssign = (b3 and 0xF0) shr 4
        val sampleSizeCode = (b3 and 0x0E) shr 1
        if ((b3 and 0x01) != 0) return null
        if (channelAssign > 10) return null
        if (sampleSizeCode == 3) return null

        // Header CRC-8 covers bytes from sync through the last optional field
        // (before the CRC byte). Max theoretical header is small; use stack-like
        // small buffer without heap churn per false-positive sync.
        val maxHeader = minOf(MAX_HEADER_BYTES, (fileLen - offset).toInt())
        if (maxHeader < 6) return null

        // Parse number + optional fields with direct map access, then CRC the range.
        var pos = 4
        val utfLen = utf8CodedLength(map, o + pos, maxHeader - pos) ?: return null
        pos += utfLen
        val blockSize = decodeBlockSizeFromMap(blockSizeCode, map, o, pos, maxHeader) ?: return null
        pos = blockSize.next
        val srNext = skipSampleRateExtra(sampleRateCode, pos, maxHeader) ?: return null
        pos = srNext
        if (pos >= maxHeader) return null

        val storedCrc = map.get(o + pos).toInt() and 0xFF
        val calcCrc = FlacCrc.crc8(map, o, pos)
        if (storedCrc != calcCrc) return null
        pos++
        if (blockSize.value <= 0) return null
        return HeaderInfo(blockSize = blockSize.value, headerSize = pos)
    }

    private data class DecodedInt(val value: Int, val next: Int)

    /** Returns byte length of UTF-8 coded number, or null if invalid. */
    private fun utf8CodedLength(map: ByteBuffer, offset: Int, limitFromOffset: Int): Int? {
        if (limitFromOffset <= 0) return null
        val first = map.get(offset).toInt() and 0xFF
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
        if (bytes > limitFromOffset) return null
        for (i in 1 until bytes) {
            val c = map.get(offset + i).toInt() and 0xFF
            if (c and 0xC0 != 0x80) return null
        }
        return bytes
    }

    private fun decodeBlockSizeFromMap(
        code: Int,
        map: ByteBuffer,
        base: Int,
        offset: Int,
        maxHeader: Int,
    ): DecodedInt? {
        return when (code) {
            0 -> null
            1 -> DecodedInt(192, offset)
            in 2..5 -> DecodedInt(576 shl (code - 2), offset)
            6 -> {
                if (offset >= maxHeader) return null
                DecodedInt((map.get(base + offset).toInt() and 0xFF) + 1, offset + 1)
            }
            7 -> {
                if (offset + 1 >= maxHeader) return null
                val v = ((map.get(base + offset).toInt() and 0xFF) shl 8) or
                    (map.get(base + offset + 1).toInt() and 0xFF)
                DecodedInt(v + 1, offset + 2)
            }
            in 8..15 -> DecodedInt(256 shl (code - 8), offset)
            else -> null
        }
    }

    private fun skipSampleRateExtra(code: Int, offset: Int, maxHeader: Int): Int? {
        return when (code) {
            12 -> if (offset + 1 <= maxHeader) offset + 1 else null
            13, 14 -> if (offset + 2 <= maxHeader) offset + 2 else null
            else -> offset
        }
    }

    /**
     * Find frame end with bulk mmap copies (avoid per-byte DirectByteBuffer.get).
     *
     * STREAMINFO maxFrameSize caps the search window; if wrong, fall back to EOF.
     * min==max exact length is tried first when both are set.
     */
    private fun findFrameEnd(
        map: ByteBuffer,
        frameStart: Long,
        headerSize: Int,
        fileLen: Long,
        minFrameSizeHint: Int,
        maxFrameSizeHint: Int,
    ): Long? {
        val minByHeader = frameStart + headerSize + 2
        if (minByHeader > fileLen) return null

        val start = frameStart.toInt()
        val len = fileLen.toInt()
        if (start + 2 > len) return null

        // Exact fixed frame size fast path (only when STREAMINFO min==max > 0).
        if (minFrameSizeHint > 0 && minFrameSizeHint == maxFrameSizeHint) {
            val end = frameStart + minFrameSizeHint
            if (end <= fileLen && end >= minByHeader) {
                val endI = end.toInt()
                val crc = FlacCrc.crc16Update(0, map, start, endI - 2 - start)
                val stored = readU16(map, endI - 2)
                if (crc == stored) {
                    if (end == fileLen) return fileLen
                    if (parseHeaderAt(map, end) != null) return end
                }
            }
        }

        val minEnd = minByHeader.toInt()
        val maxEndExclusive = if (maxFrameSizeHint > 0) {
            minOf(len, (frameStart + maxFrameSizeHint).toInt())
        } else {
            len
        }
        if (minEnd > maxEndExclusive) return null

        val found = scanFrameEndBulk(
            map = map,
            frameStart = start,
            searchFrom = minEnd,
            searchToExclusive = maxEndExclusive,
            fileLen = len,
        )
        if (found != null) return found

        // Fallback when maxFrameSize hint was too tight.
        if (maxFrameSizeHint > 0 && maxEndExclusive < len) {
            return scanFrameEndBulk(
                map = map,
                frameStart = start,
                searchFrom = minEnd,
                searchToExclusive = len,
                fileLen = len,
            )
        }
        return null
    }

    /**
     * Bulk-window scan for next frame boundary.
     * CRC covers [frameStart, candidate-2); candidate is next sync or EOF.
     */
    private fun scanFrameEndBulk(
        map: ByteBuffer,
        frameStart: Int,
        searchFrom: Int,
        searchToExclusive: Int,
        fileLen: Int,
    ): Long? {
        if (searchFrom > searchToExclusive) return null

        var crc = 0
        var crcEnd = frameStart // exclusive

        // Pre-CRC up to first candidate-2.
        val firstCrcTarget = searchFrom - 2
        if (firstCrcTarget > crcEnd) {
            crc = FlacCrc.crc16Update(crc, map, crcEnd, firstCrcTarget - crcEnd)
            crcEnd = firstCrcTarget
        }

        val win = scanWindow()
        val dup = map.duplicate()
        var pos = searchFrom

        while (pos <= searchToExclusive) {
            if (pos == fileLen || pos == searchToExclusive && searchToExclusive == fileLen) {
                // EOF candidate
                val target = fileLen - 2
                if (target > crcEnd) {
                    crc = FlacCrc.crc16Update(crc, map, crcEnd, target - crcEnd)
                    crcEnd = target
                }
                if (crcEnd == fileLen - 2 && readU16(map, fileLen - 2) == crc) {
                    return fileLen.toLong()
                }
                break
            }
            if (pos >= searchToExclusive) break

            val remaining = searchToExclusive - pos
            val n = minOf(remaining, win.size)
            dup.position(pos)
            dup.get(win, 0, n)

            var i = 0
            while (i < n) {
                // Find next 0xFF in window
                while (i < n && (win[i].toInt() and 0xFF) != 0xFF) i++
                if (i >= n) break

                val abs = pos + i
                if (abs >= searchToExclusive) break

                // Need second sync byte
                val s1 = if (i + 1 < n) {
                    win[i + 1].toInt() and 0xFF
                } else if (abs + 1 < fileLen) {
                    map.get(abs + 1).toInt() and 0xFF
                } else {
                    -1
                }

                if (s1 >= 0 && (s1 and 0xFE) == 0xF8 && (s1 and 0x02) == 0) {
                    val target = abs - 2
                    if (target > crcEnd) {
                        crc = FlacCrc.crc16Update(crc, map, crcEnd, target - crcEnd)
                        crcEnd = target
                    }
                    if (crcEnd == target && readU16(map, abs - 2) == crc) {
                        if (parseHeaderAt(map, abs.toLong()) != null) {
                            return abs.toLong()
                        }
                    }
                }
                i++
            }
            pos += n
        }

        // Final EOF if search window includes file end and we did not return.
        if (searchToExclusive == fileLen && fileLen >= frameStart + 2) {
            val target = fileLen - 2
            if (target >= crcEnd) {
                if (target > crcEnd) {
                    crc = FlacCrc.crc16Update(crc, map, crcEnd, target - crcEnd)
                }
                if (readU16(map, fileLen - 2) == crc) return fileLen.toLong()
            }
        }
        return null
    }

    private fun readU16(map: ByteBuffer, offset: Int): Int {
        return ((map.get(offset).toInt() and 0xFF) shl 8) or (map.get(offset + 1).toInt() and 0xFF)
    }

    private fun scanWindow(): ByteArray {
        var buf = SCAN_WINDOW.get()
        if (buf == null) {
            buf = ByteArray(64 * 1024)
            SCAN_WINDOW.set(buf)
        }
        return buf
    }

    private val SCAN_WINDOW = ThreadLocal<ByteArray>()

    private fun estimateFrameCapacity(info: FlacMetadataReader.StreamInfo): Int {
        val block = when {
            info.maxBlockSize > 0 -> info.maxBlockSize
            info.minBlockSize > 0 -> info.minBlockSize
            else -> 4096
        }
        if (info.totalSamples <= 0L) return 1024
        return ((info.totalSamples + block - 1) / block).toInt().coerceAtLeast(16)
    }

    companion object {
        private const val MIN_FRAME_BYTES = 6
        private const val MAX_HEADER_BYTES = 32
    }
}
