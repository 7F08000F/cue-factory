package com.cuefactory.core.audio

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Minimal FLAC metadata reader (no decode).
 *
 * Supports STREAMINFO, VORBIS_COMMENT (including `cuesheet=`), and reports
 * whether a native CUESHEET block (type 5) is present.
 */
class FlacMetadataReader {

    data class StreamInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val minBlockSize: Int,
        val maxBlockSize: Int,
        /** STREAMINFO min frame size in bytes (0 = unknown). Used to accelerate indexing. */
        val minFrameSize: Int = 0,
        /** STREAMINFO max frame size in bytes (0 = unknown). */
        val maxFrameSize: Int = 0,
    )

    data class FlacMetadata(
        val streamInfo: StreamInfo,
        val vorbisComments: Map<String, String>,
        /** All comment values for a key (case-insensitive key lookup uses first). */
        val vorbisCommentList: List<Pair<String, String>>,
        val hasNativeCueSheet: Boolean,
        val pictureCount: Int,
    ) {
        fun comment(key: String): String? {
            val want = key.lowercase()
            return vorbisComments.entries.firstOrNull { it.key.lowercase() == want }?.value
        }

        /** Embedded text CUE from vorbis `cuesheet` / `CUESHEET`. */
        fun embeddedCueSheetText(): String? = comment("cuesheet")
    }

    fun read(file: File): FlacMetadata =
        RandomAccessFile(file, "r").use { raf -> read(raf) }

    fun read(path: String): FlacMetadata = read(File(path))

    fun read(input: InputStream): FlacMetadata {
        val header = ByteArray(4)
        readFully(input, header)
        if (!header.contentEquals(FLAC_MAGIC)) {
            throw IllegalArgumentException("Not a FLAC file (missing fLaC magic)")
        }

        var streamInfo: StreamInfo? = null
        val comments = linkedMapOf<String, String>()
        val commentList = mutableListOf<Pair<String, String>>()
        var hasNativeCue = false
        var pictureCount = 0
        var last = false

        while (!last) {
            val headerByte = input.read()
            if (headerByte < 0) throw EOFException("Unexpected EOF in metadata")
            last = (headerByte and 0x80) != 0
            val type = headerByte and 0x7F
            val size = readUint24(input)
            val payload = ByteArray(size)
            if (size > 0) readFully(input, payload)

            when (type) {
                0 -> streamInfo = parseStreamInfo(payload)
                4 -> {
                    val parsed = parseVorbisComment(payload)
                    for ((k, v) in parsed) {
                        commentList += k to v
                        comments.putIfAbsent(k, v)
                    }
                }
                5 -> hasNativeCue = true
                6 -> pictureCount++
                else -> Unit
            }
        }

        val info = streamInfo
            ?: throw IllegalArgumentException("FLAC missing STREAMINFO block")
        return FlacMetadata(
            streamInfo = info,
            vorbisComments = comments.toMap(),
            vorbisCommentList = commentList.toList(),
            hasNativeCueSheet = hasNativeCue,
            pictureCount = pictureCount,
        )
    }

    private fun read(raf: RandomAccessFile): FlacMetadata {
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (!magic.contentEquals(FLAC_MAGIC)) {
            throw IllegalArgumentException("Not a FLAC file (missing fLaC magic)")
        }

        var streamInfo: StreamInfo? = null
        val comments = linkedMapOf<String, String>()
        val commentList = mutableListOf<Pair<String, String>>()
        var hasNativeCue = false
        var pictureCount = 0
        var last = false

        while (!last) {
            val headerByte = raf.read()
            if (headerByte < 0) throw EOFException("Unexpected EOF in metadata")
            last = (headerByte and 0x80) != 0
            val type = headerByte and 0x7F
            val size = readUint24(raf)
            val payload = ByteArray(size)
            if (size > 0) raf.readFully(payload)

            when (type) {
                0 -> streamInfo = parseStreamInfo(payload)
                4 -> {
                    val parsed = parseVorbisComment(payload)
                    for ((k, v) in parsed) {
                        commentList += k to v
                        comments.putIfAbsent(k, v)
                    }
                }
                5 -> hasNativeCue = true
                6 -> pictureCount++
                else -> Unit
            }
        }

        val info = streamInfo
            ?: throw IllegalArgumentException("FLAC missing STREAMINFO block")
        return FlacMetadata(
            streamInfo = info,
            vorbisComments = comments.toMap(),
            vorbisCommentList = commentList.toList(),
            hasNativeCueSheet = hasNativeCue,
            pictureCount = pictureCount,
        )
    }

    private fun parseStreamInfo(payload: ByteArray): StreamInfo {
        require(payload.size >= 18) { "STREAMINFO too short" }
        val minBlock = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val maxBlock = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        val minFrame = if (payload.size >= 10) {
            ((payload[4].toInt() and 0xff) shl 16) or
                ((payload[5].toInt() and 0xff) shl 8) or
                (payload[6].toInt() and 0xff)
        } else {
            0
        }
        val maxFrame = if (payload.size >= 10) {
            ((payload[7].toInt() and 0xff) shl 16) or
                ((payload[8].toInt() and 0xff) shl 8) or
                (payload[9].toInt() and 0xff)
        } else {
            0
        }
        // bytes 10-17: 20 bits sample rate, 3 bits channels-1, 5 bits bits-1; then 36 bits total samples
        val b10 = payload[10].toInt() and 0xff
        val b11 = payload[11].toInt() and 0xff
        val b12 = payload[12].toInt() and 0xff
        val b13 = payload[13].toInt() and 0xff
        val b14 = payload[14].toInt() and 0xff
        val b15 = payload[15].toInt() and 0xff
        val b16 = payload[16].toInt() and 0xff
        val b17 = payload[17].toInt() and 0xff

        val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
        val channels = ((b12 shr 1) and 0x7) + 1
        val bitsPerSample = (((b12 and 0x1) shl 4) or (b13 shr 4)) + 1
        val totalSamples =
            ((b13 and 0x0F).toLong() shl 32) or
                (b14.toLong() shl 24) or
                (b15.toLong() shl 16) or
                (b16.toLong() shl 8) or
                b17.toLong()

        return StreamInfo(
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

    private fun parseVorbisComment(payload: ByteArray): List<Pair<String, String>> {
        if (payload.size < 8) return emptyList()
        var pos = 0
        fun readLeInt(): Int {
            val v = (payload[pos].toInt() and 0xff) or
                ((payload[pos + 1].toInt() and 0xff) shl 8) or
                ((payload[pos + 2].toInt() and 0xff) shl 16) or
                ((payload[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }
        val vendorLen = readLeInt()
        if (vendorLen < 0 || pos + vendorLen > payload.size) return emptyList()
        pos += vendorLen
        if (pos + 4 > payload.size) return emptyList()
        val count = readLeInt()
        val out = mutableListOf<Pair<String, String>>()
        repeat(count.coerceAtLeast(0)) {
            if (pos + 4 > payload.size) return@repeat
            val len = readLeInt()
            if (len < 0 || pos + len > payload.size) return@repeat
            val s = payload.copyOfRange(pos, pos + len).toString(Charsets.UTF_8)
            pos += len
            val eq = s.indexOf('=')
            if (eq > 0) {
                out += s.substring(0, eq) to s.substring(eq + 1)
            }
        }
        return out
    }

    private fun readUint24(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0) throw EOFException()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readUint24(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0) throw EOFException()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    companion object {
        private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    }
}
