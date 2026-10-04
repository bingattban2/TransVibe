package com.transvibe.app.data.subtitle

import com.transvibe.app.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePostProcessorTest {
    @Test fun overlappingDuplicateIsMerged() {
        val list = mutableListOf(SubtitleSegment(0, 29_100, 30_400, "hello world", "hello world"))
        val added = SubtitlePostProcessor.acceptWithDedup(list, SubtitleSegment(1, 29_500, 31_000, "hello world", "hello world"))
        assertFalse(added)
        assertEquals(1, list.size)
        assertEquals(31_000, list[0].endMs)
    }

    @Test fun partialWordOverlapAtChunkBoundaryIsTrimmed() {
        val list = mutableListOf(SubtitleSegment(0, 28_000, 30_000, "we say hello world", "we say hello world"))
        val added = SubtitlePostProcessor.acceptWithDedup(
            list,
            SubtitleSegment(1, 29_500, 31_500, "hello world again today", "hello world again today")
        )
        assertTrue(added)
        assertEquals("again today", list.last().englishText)
    }

    @Test fun shortAdjacentCuesAreAssembled() {
        val out = SubtitlePostProcessor.assemble(listOf(
            SubtitleSegment(0, 0, 900, "I did", "I did"),
            SubtitleSegment(1, 1_050, 1_900, "not say that", "not say that")
        ))
        assertEquals(1, out.size)
        assertEquals("I did not say that", out[0].englishText)
    }

    @Test fun timestampsRemainMonotonic() {
        val out = SubtitlePostProcessor.assemble(listOf(
            SubtitleSegment(0, 2_000, 3_000, "first", "first"),
            SubtitleSegment(1, 1_900, 2_500, "second", "second")
        ))
        for (i in 1 until out.size) {
            assertTrue(out[i].startMs >= out[i - 1].startMs)
            assertTrue(out[i].endMs >= out[i - 1].endMs)
            assertTrue(out[i].endMs > out[i].startMs)
        }
    }
}
