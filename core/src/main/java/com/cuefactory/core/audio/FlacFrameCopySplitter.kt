package com.cuefactory.core.audio

import com.cuefactory.core.model.SampleRange
import com.cuefactory.core.model.SplitError
import com.cuefactory.core.model.SplitMethod
import com.cuefactory.core.model.SplitOutcome
import java.io.File
import java.io.RandomAccessFile

/**
 * Zero audio-reencode FLAC splitter for frame-aligned sample ranges.
 *
 * Copies complete compressed frames and rebuilds STREAMINFO / tags.
 * Does **not** re-encode subframe payloads. MD5 is written as unknown (zeros)
 * so the hot path stays I/O-bound; callers that need MD5 can verify separately.
 */
class FlacFrameCopySplitter(
    private val indexBuilder: FlacFrameIndex = FlacFrameIndex(),
) {
    data class TrackRequest(
        val trackIndex: Int,
        val trackNumber: Int,
        val range: SampleRange,
        val outputFile: File,
        val sampleRate: Int,
        val metadata: Map<String, String> = emptyMap(),
    )

    fun split(
        sourceFlac: File,
        request: TrackRequest,
        prebuiltIndex: FlacFrameIndex.Index? = null,
    ): SplitOutcome {
        if (!sourceFlac.isFile) {
            return fail(request, SplitError.IoError, "Source missing: ${sourceFlac.path}")
        }
        request.outputFile.parentFile?.mkdirs()

        return try {
            val index = prebuiltIndex ?: indexBuilder.build(sourceFlac)
            val slice = index.slice(request.range.startSample, request.range.endSample)
                ?: return fail(
                    request,
                    SplitError.UnsafeFrameBoundary,
                    "Range not on FLAC frame boundary: ${request.range.startSample}..${request.range.endSample}",
                )
            if (slice.frames.isEmpty() && request.range.lengthSamples != 0L) {
                return fail(request, SplitError.InvalidRange, "Empty frame slice for non-empty range")
            }

            val tmp = File(request.outputFile.parentFile, request.outputFile.name + ".partial")
            if (tmp.exists()) tmp.delete()

            writeRemux(
                source = sourceFlac,
                slice = slice,
                streamInfo = index.streamInfo,
                metadata = request.metadata,
                output = tmp,
            )

            // Validate STREAMINFO total samples without full decode.
            val written = FlacMetadataReader().read(tmp).streamInfo
            if (written.totalSamples != request.range.lengthSamples) {
                tmp.delete()
                return fail(
                    request,
                    SplitError.EncoderError,
                    "FrameCopy sample count mismatch: expected ${request.range.lengthSamples} got ${written.totalSamples}",
                )
            }

            if (request.outputFile.exists()) request.outputFile.delete()
            if (!tmp.renameTo(request.outputFile)) {
                tmp.copyTo(request.outputFile, overwrite = true)
                tmp.delete()
            }

            SplitOutcome.Success(
                trackIndex = request.trackIndex,
                trackNumber = request.trackNumber,
                outputPath = request.outputFile.absolutePath,
                durationMs = request.range.durationMs(request.sampleRate),
                method = SplitMethod.FrameCopy,
                range = request.range,
            )
        } catch (e: Exception) {
            request.outputFile.delete()
            File(request.outputFile.parentFile, request.outputFile.name + ".partial").delete()
            fail(request, SplitError.EncoderError, e.message ?: e.toString())
        }
    }

    private fun writeRemux(
        source: File,
        slice: FlacFrameIndex.FrameSlice,
        streamInfo: FlacMetadataReader.StreamInfo,
        metadata: Map<String, String>,
        output: File,
    ) {
        val minBlock = slice.frames.minOfOrNull { it.sampleCount } ?: streamInfo.minBlockSize
        val maxBlock = slice.frames.maxOfOrNull { it.sampleCount } ?: streamInfo.maxBlockSize
        // Renumber may shrink/grow UTF-8 frame numbers → byte lengths change.
        // Leave frame-size fields unknown so re-index never overshoots.
        val minFrame = 0
        val maxFrame = 0
        val totalSamples = slice.endSample - slice.startSample

        val streamInfoBlock = buildStreamInfoBlock(
            minBlockSize = minBlock,
            maxBlockSize = maxBlock,
            minFrameSize = minFrame,
            maxFrameSize = maxFrame,
            sampleRate = streamInfo.sampleRate,
            channels = streamInfo.channels,
            bitsPerSample = streamInfo.bitsPerSample,
            totalSamples = totalSamples,
            md5 = ByteArray(16), // unknown / not computed — keeps frame-copy fast
        )
        val commentBlock = buildVorbisCommentBlock(metadata)

        RandomAccessFile(output, "rw").use { out ->
            out.setLength(0)
            out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
            // STREAMINFO is not last
            writeMetadataBlock(out, type = 0, isLast = false, payload = streamInfoBlock)
            // VORBIS_COMMENT is last (unless empty — still write a minimal vendor tag)
            writeMetadataBlock(out, type = 4, isLast = true, payload = commentBlock)

            // Renumber so the track starts at frame 0. Uniform block-size slices keep
            // fixed strategy (byte-identical when already 0-based). Without renumbering,
            // non-first tracks keep album-absolute frame ids and many players refuse
            // to play (Poweramp is more tolerant).
            FlacFrameRenumber.copyRenumbered(
                source = source,
                frames = slice.frames,
                out = out,
                baseSample = 0L,
                baseFrameIndex = 0,
                mode = FlacFrameRenumber.preferredMode(slice.frames),
            )
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

        // 20 bits sample rate | 3 bits (channels-1) | 5 bits (bps-1) | 36 bits total samples
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

    private fun buildVorbisCommentBlock(metadata: Map<String, String>): ByteArray {
        val vendor = "CueFactory-FrameCopy"
        val tags = metadata.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .map { "${FlacRemuxWriter.normalizeVorbisKey(it.key)}=${it.value}" }
            .distinct()

        // little-endian lengths
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

    private fun fail(
        request: TrackRequest,
        reason: SplitError,
        message: String,
    ) = SplitOutcome.Failure(
        trackIndex = request.trackIndex,
        trackNumber = request.trackNumber,
        reason = reason,
        message = message,
    )
}
