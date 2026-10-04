package com.transvibe.app.data.subtitle

import android.content.ContentResolver
import android.net.Uri
import com.transvibe.app.domain.model.SubtitleSegment
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Production subtitle formatter/exporter. Output is UTF-8 plain text, never image-based. */
object SubtitleExporter {
    private const val CRLF = "\r\n"

    fun formatSrt(segments: List<SubtitleSegment>): String = buildString {
        normalized(segments).forEachIndexed { index, cue ->
            append(index + 1).append(CRLF)
            append(srtTime(cue.startMs)).append(" --> ").append(srtTime(cue.endMs)).append(CRLF)
            append(cue.text.replace("\n", CRLF)).append(CRLF).append(CRLF)
        }
    }

    fun formatVtt(segments: List<SubtitleSegment>): String = buildString {
        append("WEBVTT").append(CRLF).append(CRLF)
        normalized(segments).forEach { cue ->
            append(vttTime(cue.startMs)).append(" --> ").append(vttTime(cue.endMs)).append(CRLF)
            append(escapeVtt(cue.text).replace("\n", CRLF)).append(CRLF).append(CRLF)
        }
    }

    fun writeSrt(file: File, segments: List<SubtitleSegment>) = file.writeText(formatSrt(segments), Charsets.UTF_8)
    fun writeVtt(file: File, segments: List<SubtitleSegment>) = file.writeText(formatVtt(segments), Charsets.UTF_8)

    fun writeSrt(resolver: ContentResolver, destination: Uri, segments: List<SubtitleSegment>) =
        writeUtf8(resolver, destination, formatSrt(segments))

    fun writeVtt(resolver: ContentResolver, destination: Uri, segments: List<SubtitleSegment>) =
        writeUtf8(resolver, destination, formatVtt(segments))

    fun writeSrtBatches(file: File, batches: Sequence<List<SubtitleSegment>>) {
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            var cueIndex = 1
            for (batch in batches) {
                for (segment in batch) {
                    val cue = normalizedCue(segment) ?: continue
                    writer.append(cueIndex++.toString()).append(CRLF)
                    writer.append(srtTime(cue.startMs)).append(" --> ").append(srtTime(cue.endMs)).append(CRLF)
                    writer.append(cue.text.replace("\n", CRLF)).append(CRLF).append(CRLF)
                }
            }
        }
    }

    fun writeVttBatches(file: File, batches: Sequence<List<SubtitleSegment>>) {
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append("WEBVTT").append(CRLF).append(CRLF)
            for (batch in batches) {
                for (segment in batch) {
                    val cue = normalizedCue(segment) ?: continue
                    writer.append(vttTime(cue.startMs)).append(" --> ").append(vttTime(cue.endMs)).append(CRLF)
                    writer.append(escapeVtt(cue.text).replace("\n", CRLF)).append(CRLF).append(CRLF)
                }
            }
        }
    }

    private fun writeUtf8(resolver: ContentResolver, destination: Uri, text: String) {
        val out = resolver.openOutputStream(destination, "w") ?: error("تعذر فتح ملف الحفظ")
        out.use { stream: OutputStream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.flush() }
    }

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private fun normalizedCue(s: SubtitleSegment): Cue? {
        val start = s.startMs.coerceAtLeast(0L)
        val end = s.endMs.coerceAtLeast(start + 1L)
        val text = cleanText(s.translatedText ?: s.englishText ?: s.originalText)
        return text.takeIf { it.isNotBlank() }?.let { Cue(start, end, it) }
    }

    private fun normalized(segments: List<SubtitleSegment>): List<Cue> = segments
        .asSequence()
        .map { s ->
            val start = s.startMs.coerceAtLeast(0L)
            val end = s.endMs.coerceAtLeast(start + 1L)
            Cue(start, end, cleanText(s.translatedText ?: s.englishText ?: s.originalText))
        }
        .filter { it.text.isNotBlank() }
        .sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs })
        .toList()

    private fun cleanText(value: String): String = value
        .replace("\u0000", "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()

    private fun escapeVtt(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun srtTime(ms: Long): String = time(ms.coerceAtLeast(0L), ',')
    private fun vttTime(ms: Long): String = time(ms.coerceAtLeast(0L), '.')

    private fun time(ms: Long, separator: Char): String {
        val hours = ms / 3_600_000L
        val minutes = (ms / 60_000L) % 60L
        val seconds = (ms / 1_000L) % 60L
        val millis = ms % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", hours, minutes, seconds, separator, millis)
    }
}
