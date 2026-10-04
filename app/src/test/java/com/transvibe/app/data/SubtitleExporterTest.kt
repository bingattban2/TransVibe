package com.transvibe.app.data.subtitle

import com.transvibe.app.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleExporterTest {
    @Test
    fun srt_isOrderedNumberedUtf8PlainTextAndUsesCommaMillis() {
        val input = listOf(
            SubtitleSegment(2, 8_100, 10_000, "ثانٍ", arabicText = "ثانٍ"),
            SubtitleSegment(1, 5_250, 8_100, "مرحباً بكم", arabicText = "مرحباً بكم")
        )
        val value = SubtitleExporter.formatSrt(input)
        val expected = "1\r\n00:00:05,250 --> 00:00:08,100\r\nمرحباً بكم\r\n\r\n" +
            "2\r\n00:00:08,100 --> 00:00:10,000\r\nثانٍ\r\n\r\n"
        assertEquals(expected, value)
        assertFalse(value.contains("\u0000"))
    }

    @Test
    fun webVtt_hasHeaderDotMillisAndCleanText() {
        val input = listOf(SubtitleSegment(1, 5_250, 8_100, " x ", arabicText = " مرحباً\u0000 بكم "))
        val value = SubtitleExporter.formatVtt(input)
        assertTrue(value.startsWith("WEBVTT\r\n\r\n"))
        assertTrue(value.contains("00:00:05.250 --> 00:00:08.100"))
        assertTrue(value.contains("مرحباً بكم"))
        assertFalse(value.contains("\u0000"))
    }

    @Test
    fun emptySubtitleTextDoesNotCreateInvalidCue() {
        val input = listOf(SubtitleSegment(1, 0, 1, "   "))
        assertEquals("", SubtitleExporter.formatSrt(input))
        assertEquals("WEBVTT\r\n\r\n", SubtitleExporter.formatVtt(input))
    }
}
