package com.transvibe.app.data.subtitle

import com.transvibe.app.domain.model.SubtitleSegment
import kotlin.math.max
import kotlin.math.min

object SubtitlePostProcessor {
    fun acceptWithDedup(target: MutableList<SubtitleSegment>, candidate: SubtitleSegment): Boolean {
        var current = candidate
        val text = displayText(current)
        if (text.isBlank() || current.endMs <= current.startMs) return false

        val startIndex = max(0, target.size - 4)
        for (i in target.lastIndex downTo startIndex) {
            val old = target[i]
            if (isDuplicate(old, current)) {
                target[i] = mergeDuplicate(old, current)
                return false
            }
        }

        target.lastOrNull()?.let { previous ->
            if (current.startMs <= previous.endMs + 1_200L) {
                current = trimRepeatedLeadingWords(previous, current) ?: return false
            }
        }
        target += current
        return true
    }

    fun compactAdjacentTail(target: MutableList<SubtitleSegment>): Boolean {
        if (target.size < 2) return false
        val a = target[target.lastIndex - 1]
        val b = target[target.lastIndex]
        val combinedLength = displayText(a).length + displayText(b).length + 1
        val gap = b.startMs - a.endMs
        if (gap !in 0..350 || combinedLength > 90 || (a.endMs - a.startMs) >= 2_500) return false
        target[target.lastIndex - 1] = mergeAdjacent(a, b)
        target.removeAt(target.lastIndex)
        return true
    }

    fun assemble(input: List<SubtitleSegment>): List<SubtitleSegment> {
        if (input.isEmpty()) return emptyList()
        val merged = ArrayList<SubtitleSegment>()
        for (segment in input.sortedWith(compareBy<SubtitleSegment> { it.startMs }.thenBy { it.endMs })) {
            val prev = merged.lastOrNull()
            val combinedLength = if (prev == null) Int.MAX_VALUE else displayText(prev).length + displayText(segment).length + 1
            val gap = if (prev == null) Long.MAX_VALUE else segment.startMs - prev.endMs
            if (prev != null && gap in 0..350 && combinedLength <= 90 && (prev.endMs - prev.startMs) < 2_500) {
                merged[merged.lastIndex] = mergeAdjacent(prev, segment)
            } else merged += segment
        }
        return enforceMonotonicTimeline(merged)
    }

    private fun enforceMonotonicTimeline(input: List<SubtitleSegment>): List<SubtitleSegment> {
        val out = ArrayList<SubtitleSegment>(input.size)
        var lastStart = 0L
        var lastEnd = 0L
        input.forEachIndexed { index, raw ->
            val start = max(raw.startMs.coerceAtLeast(0L), lastStart)
            val end = max(max(raw.endMs, start + 1L), lastEnd)
            val fixed = raw.copy(id = index.toLong(), startMs = start, endMs = end)
            out += fixed
            lastStart = start
            lastEnd = end
        }
        return out
    }

    private fun trimRepeatedLeadingWords(previous: SubtitleSegment, candidate: SubtitleSegment): SubtitleSegment? {
        val previousText = displayText(previous).trim()
        val candidateText = displayText(candidate).trim()
        if (previousText.isBlank() || candidateText.isBlank()) return candidate

        val a = previousText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val b = candidateText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val maxOverlap = min(min(a.size, b.size), 10)
        var overlap = 0
        for (count in maxOverlap downTo 2) {
            val left = a.takeLast(count).map(::normalizeToken)
            val right = b.take(count).map(::normalizeToken)
            if (left == right) { overlap = count; break }
        }
        if (overlap == 0) return candidate
        val trimmed = b.drop(overlap).joinToString(" ").trim()
        if (trimmed.isBlank()) return null
        return replaceDisplayText(candidate, trimmed)
    }

    private fun replaceDisplayText(segment: SubtitleSegment, text: String): SubtitleSegment = when {
        segment.englishText != null -> segment.copy(englishText = text)
        segment.translatedText != null -> segment.copy(translatedText = text)
        else -> segment.copy(originalText = text)
    }

    private fun isDuplicate(a: SubtitleSegment, b: SubtitleSegment): Boolean {
        val overlap = max(0L, min(a.endMs, b.endMs) - max(a.startMs, b.startMs))
        val minDuration = min((a.endMs - a.startMs).coerceAtLeast(1), (b.endMs - b.startMs).coerceAtLeast(1))
        val overlapRatio = overlap.toDouble() / minDuration.toDouble()
        if (overlapRatio < 0.30) return false
        val x = normalize(displayText(a)); val y = normalize(displayText(b))
        if (x.isBlank() || y.isBlank()) return false
        if (x.contains(y) || y.contains(x)) return true
        val xs = x.split(' ').filter { it.isNotBlank() }.toSet(); val ys = y.split(' ').filter { it.isNotBlank() }.toSet()
        val union = (xs + ys).size.coerceAtLeast(1)
        return xs.intersect(ys).size.toDouble() / union >= 0.68
    }

    private fun mergeDuplicate(a: SubtitleSegment, b: SubtitleSegment): SubtitleSegment {
        val preferred = if (displayText(b).length > displayText(a).length) b else a
        return preferred.copy(id = a.id, startMs = min(a.startMs, b.startMs), endMs = max(a.endMs, b.endMs))
    }

    private fun mergeAdjacent(a: SubtitleSegment, b: SubtitleSegment): SubtitleSegment = a.copy(
        endMs = max(a.endMs, b.endMs),
        originalText = join(a.originalText, b.originalText).orEmpty(),
        englishText = join(a.englishText, b.englishText),
        translatedText = join(a.translatedText, b.translatedText)
    )

    private fun join(a: String?, b: String?): String? {
        val x = a?.trim().orEmpty(); val y = b?.trim().orEmpty()
        return when { x.isBlank() && y.isBlank() -> null; x.isBlank() -> y; y.isBlank() -> x; else -> "$x $y" }
    }

    private fun displayText(s: SubtitleSegment) = s.englishText ?: s.translatedText ?: s.originalText
    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    private fun normalizeToken(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
