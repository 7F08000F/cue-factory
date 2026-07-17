package com.cuefactory.core.audio

import com.cuefactory.core.model.CueSheet
import com.cuefactory.core.model.CueTrack
import com.cuefactory.core.model.SampleRange

/**
 * Computes per-track sample ranges from a CUE sheet.
 *
 * Policy (MVP): track start = INDEX 01 (or first index); track end = next track start
 * (or totalSamples). Pregap (INDEX 00) is not merged into the previous track file.
 */
object TrackRangeCalculator {

    data class TrackRange(
        val track: CueTrack,
        val trackIndex: Int,
        val range: SampleRange,
    )

    fun ranges(
        sheet: CueSheet,
        totalSamples: Long,
        sampleRate: Int = 44_100,
    ): List<TrackRange> {
        require(totalSamples >= 0)
        require(sampleRate > 0)
        val tracks = sheet.allTracks
        if (tracks.isEmpty()) return emptyList()

        val starts = tracks.map { track ->
            val idx = track.startIndex()
                ?: error("Track ${track.number} has no INDEX")
            idx.time.toSamples(sampleRate)
        }

        return tracks.mapIndexed { i, track ->
            val start = starts[i].coerceIn(0L, totalSamples)
            val end = if (i + 1 < starts.size) {
                starts[i + 1].coerceIn(0L, totalSamples)
            } else {
                totalSamples
            }
            val safeEnd = end.coerceAtLeast(start)
            TrackRange(
                track = track,
                trackIndex = i,
                range = SampleRange(startSample = start, endSample = safeEnd),
            )
        }
    }
}
