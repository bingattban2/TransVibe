package com.transvibe.app.data.subtitle

import com.transvibe.app.domain.model.SubtitleSegment

/** Immutable, indexed subtitle lookup for player synchronization. */
class SubtitleTimeline(segments: List<SubtitleSegment>) {
    private val items = segments.sortedBy { it.startMs }

    fun at(positionMs: Long): SubtitleSegment? {
        if (items.isEmpty()) return null
        var low = 0
        var high = items.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (items[mid].startMs <= positionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null
        val current = items[candidate]
        if (positionMs < current.endMs) return current
        // Handles rare overlapping segments without scanning the whole track.
        for (i in candidate - 1 downTo (candidate - 3).coerceAtLeast(0)) {
            val prior = items[i]
            if (positionMs >= prior.startMs && positionMs < prior.endMs) return prior
        }
        return null
    }
}
