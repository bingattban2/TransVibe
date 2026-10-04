package com.transvibe.app.domain.whisper

import com.transvibe.app.domain.model.SubtitleSegment
import java.util.ArrayDeque

/**
 * Small, bounded prompt made only from subtitle text that has already been finalized/persisted.
 * Text from the region currently being decoded is never added before that region completes.
 */
class RollingWhisperPrompt(
    private val maxSentences: Int = 3,
    private val maxCharacters: Int = 240
) {
    init {
        require(maxSentences in 1..6)
        require(maxCharacters in 64..512)
    }

    private val sentences = ArrayDeque<String>(maxSentences)

    fun seed(finalized: List<SubtitleSegment>, textSelector: (SubtitleSegment) -> String?) {
        sentences.clear()
        finalized.takeLast(maxSentences).forEach { offerFinalizedText(textSelector(it)) }
    }

    fun offerFinalized(segment: SubtitleSegment) = offerFinalizedText(promptText(segment))

    fun offerFinalizedText(rawText: String?) {
        val text = rawText.orEmpty().sanitize() ?: return
        if (isLikelyHallucination(text)) return
        if (sentences.lastOrNull()?.normalized() == text.normalized()) return
        sentences.addLast(text)
        while (sentences.size > maxSentences) sentences.removeFirst()
        trimToCharacterBudget()
    }

    fun valueOrNull(): String? = sentences.joinToString(" ").trim().takeIf { it.isNotBlank() }

    private fun trimToCharacterBudget() {
        while (sentences.size > 1 && sentences.sumOf { it.length } + sentences.size - 1 > maxCharacters) {
            sentences.removeFirst()
        }
        if (sentences.size == 1 && sentences.first().length > maxCharacters) {
            val clipped = sentences.removeFirst().takeLast(maxCharacters).trimStart()
            if (clipped.isNotBlank()) sentences.addLast(clipped)
        }
    }

    private fun promptText(segment: SubtitleSegment): String = when {
        !segment.englishText.isNullOrBlank() -> segment.englishText
        segment.originalText.isNotBlank() -> segment.originalText
        else -> segment.translatedText.orEmpty()
    }

    private fun String.sanitize(): String? {
        val cleaned = replace(Regex("\\s+"), " ").trim()
        return cleaned.takeIf { it.length in 2..160 }
    }

    private fun String.normalized(): String = lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun isLikelyHallucination(text: String): Boolean {
        val normalized = text.normalized()
        if (normalized.isBlank()) return true
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.size >= 6) {
            val mostCommon = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (mostCommon.toDouble() / words.size >= 0.55) return true
        }
        val half = normalized.length / 2
        if (normalized.length >= 24 && half > 0) {
            val left = normalized.substring(0, half).trim()
            val right = normalized.substring(half).trim()
            if (left.length >= 8 && left == right) return true
        }
        return false
    }
}
