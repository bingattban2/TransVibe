package com.transvibe.app.data.subtitle

import com.transvibe.app.domain.model.SubtitleSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SegmentLanguageAligner {
    fun combineOriginalAndEnglish(
        original: List<SubtitleSegment>,
        english: List<SubtitleSegment>,
        idStart: Long = 0L
    ): List<SubtitleSegment> {
        if (original.isEmpty() && english.isEmpty()) return emptyList()
        val basis = if (original.isNotEmpty()) original else english
        return basis.mapIndexedNotNull { index, source ->
            val sourceText = source.originalText.trim()
            val match = bestMatch(source, english)
            val englishText = match?.let { (it.englishText ?: it.originalText).trim() }.orEmpty().ifBlank { null }
            val originalText = sourceText.ifBlank {
                bestMatch(source, original)?.originalText?.trim().orEmpty()
            }
            if (originalText.isBlank() && englishText.isNullOrBlank()) null
            else SubtitleSegment(
                id = idStart + index,
                startMs = source.startMs.coerceAtLeast(0L),
                endMs = source.endMs.coerceAtLeast(source.startMs + 1L),
                originalText = originalText,
                englishText = englishText,
                translatedText = null
            )
        }
    }

    private fun bestMatch(target: SubtitleSegment, candidates: List<SubtitleSegment>): SubtitleSegment? {
        if (candidates.isEmpty()) return null
        val targetMid = (target.startMs + target.endMs) / 2L
        return candidates.maxByOrNull { candidate ->
            val overlap = max(0L, min(target.endMs, candidate.endMs) - max(target.startMs, candidate.startMs))
            val distance = abs(targetMid - (candidate.startMs + candidate.endMs) / 2L)
            overlap * 10_000L - distance
        }
    }
}
