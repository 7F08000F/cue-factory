package com.cuefactory.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Shared helpers for writing a minimal FLAC container (STREAMINFO + Vorbis tags)
 * and appending compressed frame bytes.
 */
internal object FlacRemuxWriter {
    fun writeContainer(
        output: File,
        streamInfo: FlacMetadataReader.StreamInfo,
        totalSamples: Long,
        minBlockSize: Int,
        maxBlockSize: Int,
        minFrameSize: Int,
        maxFrameSize: Int,
        metadata: Map<String, String>,
        vendor: String,
        writeAudio: (RandomAccessFile) -> Unit,
    ) {
        val streamInfoBlock = buildStreamInfoBlock(
            minBlockSize = minBlockSize.coerceAtLeast(0),
            maxBlockSize = maxBlockSize.coerceAtLeast(minBlockSize.coerceAtLeast(0)),
            minFrameSize = minFrameSize.coerceAtLeast(0),
            maxFrameSize = maxFrameSize.coerceAtLeast(minFrameSize.coerceAtLeast(0)),
            sampleRate = streamInfo.sampleRate,
            channels = streamInfo.channels,
            bitsPerSample = streamInfo.bitsPerSample,
            totalSamples = totalSamples,
            md5 = ByteArray(16),
        )
        val commentBlock = buildVorbisCommentBlock(metadata, vendor)
        RandomAccessFile(output, "rw").use { out ->
            out.setLength(0)
            out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
            writeMetadataBlock(out, type = 0, isLast = false, payload = streamInfoBlock)
            writeMetadataBlock(out, type = 4, isLast = true, payload = commentBlock)
            writeAudio(out)
        }
    }

    fun copyBytes(src: File, srcOffset: Long, length: Long, out: RandomAccessFile) {
        if (length <= 0) return
        RandomAccessFile(src, "r").use { input ->
            input.seek(srcOffset)
            var remaining = length
            val buf = ByteArray(256 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) throw IllegalStateException("Unexpected EOF copying FLAC frames")
                out.write(buf, 0, n)
                remaining -= n
            }
        }
    }

    fun audioDataOffset(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))) {
                throw IllegalArgumentException("Not a FLAC file: ${file.path}")
            }
            var last = false
            while (!last) {
                val header = raf.read()
                if (header < 0) throw IllegalArgumentException("Truncated metadata")
                last = (header and 0x80) != 0
                val size = readUint24(raf)
                raf.skipBytes(size)
            }
            return raf.filePointer
        }
    }

    private fun writeMetadataBlock(
        out: RandomAccessFile,
        type: Int,
        isLast: Boolean,
        payload: ByteArray,
    ) {
        val header = (if (isLast) 0x80 else 0) or (type and 0x7F)
        out.write(header)
        val size = payload.size
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(payload)
    }

    private fun buildStreamInfoBlock(
        minBlockSize: Int,
        maxBlockSize: Int,
        minFrameSize: Int,
        maxFrameSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        md5: ByteArray,
    ): ByteArray {
        require(md5.size == 16)
        require(sampleRate in 1..0xFFFFF)
        require(channels in 1..8)
        require(bitsPerSample in 4..32)
        require(totalSamples in 0..0xFFFFFFFFFL)

        val out = ByteArray(34)
        out[0] = ((minBlockSize shr 8) and 0xFF).toByte()
        out[1] = (minBlockSize and 0xFF).toByte()
        out[2] = ((maxBlockSize shr 8) and 0xFF).toByte()
        out[3] = (maxBlockSize and 0xFF).toByte()
        out[4] = ((minFrameSize shr 16) and 0xFF).toByte()
        out[5] = ((minFrameSize shr 8) and 0xFF).toByte()
        out[6] = (minFrameSize and 0xFF).toByte()
        out[7] = ((maxFrameSize shr 16) and 0xFF).toByte()
        out[8] = ((maxFrameSize shr 8) and 0xFF).toByte()
        out[9] = (maxFrameSize and 0xFF).toByte()

        var packed = 0L
        packed = packed or ((sampleRate.toLong() and 0xFFFFF) shl 44)
        packed = packed or (((channels - 1).toLong() and 0x7) shl 41)
        packed = packed or (((bitsPerSample - 1).toLong() and 0x1F) shl 36)
        packed = packed or (totalSamples and 0xFFFFFFFFFL)
        for (i in 0 until 8) {
            out[10 + i] = ((packed shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        System.arraycopy(md5, 0, out, 18, 16)
        return out
    }

    private fun buildVorbisCommentBlock(
        metadata: Map<String, String>,
        vendor: String,
    ): ByteArray {
        val tags = metadata.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .map { "${normalizeVorbisKey(it.key)}=${it.value}" }
            .distinct()

        fun le(n: Int): ByteArray = byteArrayOf(
            (n and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(),
            ((n shr 24) and 0xFF).toByte(),
        )

        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val tagBytes = tags.map { it.toByteArray(Charsets.UTF_8) }
        val size = 4 + vendorBytes.size + 4 + tagBytes.sumOf { 4 + it.size }
        val out = ByteArray(size)
        var pos = 0
        fun put(b: ByteArray) {
            System.arraycopy(b, 0, out, pos, b.size)
            pos += b.size
        }
        put(le(vendorBytes.size))
        put(vendorBytes)
        put(le(tagBytes.size))
        for (t in tagBytes) {
            put(le(t.size))
            put(t)
        }
        return out
    }

    /** Map common aliases to player-friendly Vorbis keys (e.g. album_artist → ALBUMARTIST). */
    fun normalizeVorbisKey(key: String): String {
        val k = key.trim().uppercase().replace('-', '_')
        return when (k) {
            "ALBUM_ARTIST", "ALBUM ARTIST" -> "ALBUMARTIST"
            "TRACK_NUMBER", "TRACKNR" -> "TRACKNUMBER"
            else -> k
        }
    }

    private fun readUint24(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0) throw IllegalArgumentException("Truncated metadata size")
        return (b0 shl 16) or (b1 shl 8) or b2
    }
}
