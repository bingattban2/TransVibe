package com.transvibe.app.media.inspector

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Lightweight preflight inspection. It never decodes or buffers the source media. */
class MediaSourceInspector(private val context: Context) {
    data class AudioInfo(
        val mime: String,
        val durationUs: Long,
        val sampleRate: Int?,
        val channelCount: Int?
    )

    sealed class InspectionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class InvalidVideo(cause: Throwable? = null) : InspectionException("الملف المحدد ليس فيديو صالحًا أو لم يعد متاحًا.", cause)
        class PermissionDenied(cause: Throwable? = null) : InspectionException("لا يملك التطبيق إذن الوصول إلى الفيديو.", cause)
        class NoAudioTrack : InspectionException("الفيديو لا يحتوي على مسار صوتي.")
        class UnsupportedCodec(val mime: String) : InspectionException("ترميز الصوت غير مدعوم على هذا الجهاز: $mime")
    }

    suspend fun ensureReadable(uri: Uri) = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.fileDescriptor.valid().not()) throw InspectionException.InvalidVideo()
            } ?: throw InspectionException.InvalidVideo()
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            throw InspectionException.PermissionDenied(se)
        } catch (e: InspectionException) {
            throw e
        } catch (t: Throwable) {
            throw InspectionException.InvalidVideo(t)
        }
    }

    suspend fun inspectAudio(uri: Uri): AudioInfo = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val extractor = MediaExtractor()
        try {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length >= 0L) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    else extractor.setDataSource(afd.fileDescriptor)
                } ?: throw InspectionException.InvalidVideo()
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                throw InspectionException.PermissionDenied(se)
            } catch (e: InspectionException) {
                throw e
            } catch (t: Throwable) {
                throw InspectionException.InvalidVideo(t)
            }

            coroutineContext.ensureActive()
            var hasVideo = false
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                coroutineContext.ensureActive()
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (audioFormat == null && mime.startsWith("audio/")) audioFormat = format
            }
            if (!hasVideo) throw InspectionException.InvalidVideo()
            val format = audioFormat ?: throw InspectionException.NoAudioTrack()
            val mime = format.getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("audio/") }
                ?: throw InspectionException.NoAudioTrack()
            if (!hasDecoder(mime)) throw InspectionException.UnsupportedCodec(mime)

            AudioInfo(
                mime = mime,
                durationUs = format.longOrNull(MediaFormat.KEY_DURATION) ?: -1L,
                sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
}
