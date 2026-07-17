package com.cuefactory.core.cue

import com.cuefactory.core.model.CueTime

internal object CueTimeParser {
    private val TIME = Regex("""^(\d{1,3}):(\d{2}):(\d{2})$""")

    fun parse(raw: String): CueTime? {
        val m = TIME.matchEntire(raw.trim()) ?: return null
        val minutes = m.groupValues[1].toInt()
        val seconds = m.groupValues[2].toInt()
        val frames = m.groupValues[3].toInt()
        if (seconds !in 0..59 || frames !in 0..74) return null
        if (minutes < 0) return null
        return CueTime(minutes, seconds, frames)
    }
}
