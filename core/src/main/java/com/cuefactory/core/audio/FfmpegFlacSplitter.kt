package com.cuefactory.core.audio

import com.cuefactory.core.model.SampleRange
import com.cuefactory.core.model.SplitError
import com.cuefactory.core.model.SplitMethod
import com.cuefactory.core.model.SplitOutcome
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Splits FLAC using an external `ffmpeg` binary (lossless re-encode path).
 *
 * Suitable for Termux host tooling and for an app that ships/points to an
 * aarch64 ffmpeg. Frame-copy is not implemented here — see planner.
 *
 * For short Hybrid edge fragments, pass [sourceWindow] so ffmpeg only sees a
 * few frames instead of decoding from the start of a multi-hundred-MB album.
 */
class FfmpegFlacSplitter(
    private val ffmpegPath: String = "ffmpeg",
    private val timeoutSeconds: Long = 600,
    /** Extra LD_LIBRARY_PATH entries (app-private ffmpeg libs). */
    private val libraryPath: String? = null,
    /**
     * Full argv prefix. On Android 10+ this may be
     * `[/system/bin/linker64, /data/.../ffmpeg]` because app-data exec is blocked.
     */
    private val launchPrefix: List<String> = listOf(ffmpegPath),
) {
    data class TrackRequest(
        val trackIndex: Int,
        val trackNumber: Int,
        val range: SampleRange,
        val outputFile: File,
        val sampleRate: Int,
        /** Vorbis-style metadata for ffmpeg -metadata */
        val metadata: Map<String, String> = emptyMap(),
    )

    /**
     * Contiguous source frames covering [range] (or a superset). Used to build a
     * tiny temp FLAC so atrim does not walk the whole album.
     */
    data class SourceWindow(
        val frames: List<FlacFrameIndex.Frame>,
        /** Original sample number of [frames].first().startSample */
        val windowStartSample: Long,
    )

    fun splitLossless(
        sourceFlac: File,
        request: TrackRequest,
        sourceWindow: SourceWindow? = null,
    ): SplitOutcome {
        if (!sourceFlac.isFile) {
            return fail(request, SplitError.IoError, "Source missing: ${sourceFlac.path}")
        }
        request.outputFile.parentFile?.mkdirs()

        val expectedSamples = request.range.lengthSamples
        var tempWindow: File? = null
        return try {
            val (inputFile, atrimStart, atrimEnd) = if (sourceWindow != null && sourceWindow.frames.isNotEmpty()) {
                val winStart = sourceWindow.windowStartSample
                val winEnd = sourceWindow.frames.last().endSample
                if (request.range.startSample < winStart || request.range.endSample > winEnd) {
                    return fail(
                        request,
                        SplitError.InvalidRange,
                        "Range ${request.range} outside window $winStart..$winEnd",
                    )
                }
                val tmp = File(
                    request.outputFile.parentFile,
                    ".ffwin-${request.trackNumber}-${System.nanoTime()}.flac",
                )
                writeWindowFlac(sourceFlac, sourceWindow, tmp)
                tempWindow = tmp
                Triple(
                    tmp,
                    request.range.startSample - winStart,
                    request.range.endSample - winStart,
                )
            } else {
                Triple(sourceFlac, request.range.startSample, request.range.endSample)
            }

            val atrim =
                "atrim=start_sample=$atrimStart:end_sample=$atrimEnd,asetpts=PTS-STARTPTS"

            val cmd = mutableListOf<String>().apply {
                addAll(if (launchPrefix.isNotEmpty()) launchPrefix else listOf(ffmpegPath))
                addAll(
                    listOf(
                        "-y",
                        "-hide_banner",
                        "-loglevel", "error",
                        "-i", inputFile.absolutePath,
                        // Do not accidentally select embedded cover art as output stream.
                        "-map", "0:a:0",
                        "-af", atrim,
                        "-c:a", "flac",
                        // Lossless; 5 is a good speed/size tradeoff for edge fragments.
                        "-compression_level", "5",
                    ),
                )
            }
            for ((k, v) in request.metadata) {
                if (v.isEmpty()) continue
                // ffmpeg flac muxer: album_artist maps more reliably than albumartist alone.
                val key = when (k.lowercase()) {
                    "albumartist" -> "album_artist"
                    "tracknumber" -> "track"
                    else -> k
                }
                cmd += listOf("-metadata", "$key=$v")
            }
            cmd += request.outputFile.absolutePath

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val elf = launchPrefix.lastOrNull() ?: ffmpegPath
            File(elf).parentFile?.let { pb.directory(it) }
            if (!libraryPath.isNullOrBlank()) {
                val env = pb.environment()
                val existing = env["LD_LIBRARY_PATH"]
                env["LD_LIBRARY_PATH"] = if (existing.isNullOrBlank()) {
                    libraryPath
                } else {
                    "$libraryPath:$existing"
                }
            }
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return fail(request, SplitError.EncoderError, "ffmpeg timeout")
            }
            if (proc.exitValue() != 0) {
                val detail = output.replace('\n', ' ').trim().take(800)
                return fail(
                    request,
                    SplitError.EncoderError,
                    "ffmpeg exit ${proc.exitValue()}: $detail",
                )
            }
            if (!request.outputFile.isFile || request.outputFile.length() == 0L) {
                return fail(request, SplitError.IoError, "ffmpeg produced no output")
            }

            val actualSamples = readTotalSamples(request.outputFile)
            if (actualSamples != null && actualSamples != expectedSamples) {
                request.outputFile.delete()
                return fail(
                    request,
                    SplitError.EncoderError,
                    "sample count mismatch: expected $expectedSamples got $actualSamples",
                )
            }

            SplitOutcome.Success(
                trackIndex = request.trackIndex,
                trackNumber = request.trackNumber,
                outputPath = request.outputFile.absolutePath,
                durationMs = request.range.durationMs(request.sampleRate),
                method = SplitMethod.LosslessReencode,
                range = request.range,
            )
        } catch (e: Exception) {
            fail(request, SplitError.EncoderError, e.message ?: e.toString())
        } finally {
            tempWindow?.delete()
        }
    }

    private fun writeWindowFlac(
        source: File,
        window: SourceWindow,
        output: File,
    ) {
        val frames = window.frames
        val streamInfo = FlacMetadataReader().read(source).streamInfo
        val totalSamples = frames.sumOf { it.sampleCount.toLong() }
        FlacRemuxWriter.writeContainer(
            output = output,
            streamInfo = streamInfo,
            totalSamples = totalSamples,
            minBlockSize = frames.minOf { it.sampleCount },
            maxBlockSize = frames.maxOf { it.sampleCount },
            minFrameSize = 0,
            maxFrameSize = 0,
            metadata = emptyMap(),
            vendor = "CueFactory-FfmpegWindow",
        ) { out ->
            FlacFrameRenumber.copyRenumbered(
                source = source,
                frames = frames,
                out = out,
                baseSample = 0L,
                baseFrameIndex = 0,
            )
        }
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

    private fun readTotalSamples(file: File): Long? {
        return try {
            FlacMetadataReader().read(file).streamInfo.totalSamples
        } catch (_: Exception) {
            null
        }
    }
}
