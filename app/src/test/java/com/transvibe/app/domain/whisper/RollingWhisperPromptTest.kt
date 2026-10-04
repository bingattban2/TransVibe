package com.transvibe.app.domain.whisper

import com.transvibe.app.domain.model.SubtitleSegment
import org.junit.Assert.*
import org.junit.Test

class RollingWhisperPromptTest {
    private fun s(id: Long, text: String) = SubtitleSegment(id, id * 1000, id * 1000 + 900, text, englishText = text)

    @Test fun promptIsBoundedToFinalizedSentences() {
        val prompt = RollingWhisperPrompt(maxSentences = 3, maxCharacters = 120)
        (1L..5L).forEach { prompt.offerFinalized(s(it, "final sentence $it")) }
        val value = prompt.valueOrNull().orEmpty()
        assertFalse(value.contains("final sentence 1"))
        assertTrue(value.contains("final sentence 5"))
    }

    @Test fun duplicateDoesNotPropagate() {
        val prompt = RollingWhisperPrompt()
        prompt.offerFinalized(s(1, "stable final text"))
        prompt.offerFinalized(s(2, "stable final text"))
        assertEquals("stable final text", prompt.valueOrNull())
    }

    @Test fun repetitiveHallucinationIsRejected() {
        val prompt = RollingWhisperPrompt()
        prompt.offerFinalized(s(1, "hello hello hello hello hello hello hello hello"))
        assertNull(prompt.valueOrNull())
    }
}
