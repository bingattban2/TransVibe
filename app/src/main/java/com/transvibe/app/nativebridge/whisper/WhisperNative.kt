package com.transvibe.app.nativebridge.whisper

import androidx.annotation.Keep

import com.transvibe.app.domain.model.SubtitleSegment
import org.json.JSONArray

enum class NativeErrorCode {
    INVALID_HANDLE,
    MODEL_INVALID,
    MODEL_NOT_FOUND,
    MODEL_NOT_READABLE,
    INVALID_AUDIO,
    INVALID_ARGUMENT,
    ALLOCATION_FAILED,
    INIT_FAILED,
    INFERENCE_FAILED,
    INVALID_RESULT,
    CANCELLED,
    UNKNOWN
}

class NativeBridgeException(
    val code: NativeErrorCode,
    internalMessage: String,
    cause: Throwable? = null
) : RuntimeException(internalMessage, cause)

@Keep
object WhisperNative {
    @Volatile private var loadAttempted = false
    @Volatile private var loadFailure: Throwable? = null

    private fun ensureLoaded() {
        if (!loadAttempted) synchronized(this) {
            if (!loadAttempted) {
                loadFailure = runCatching { System.loadLibrary("transvibe_native") }.exceptionOrNull()
                loadAttempted = true
            }
        }
        loadFailure?.let {
            throw NativeBridgeException(
                NativeErrorCode.INIT_FAILED,
                "Precompiled native library libtransvibe_native.so is unavailable for arm64-v8a",
                it
            )
        }
    }

    fun isLibraryAvailable(): Boolean = runCatching { ensureLoaded(); true }.getOrDefault(false)

    private external fun nativeCreateContext(modelPath: String): Long
    private external fun nativeDetectLanguage(handle: Long, audioSamples: FloatArray, threads: Int): String
    private external fun nativeTranscribeJson(handle: Long, audioSamples: FloatArray, language: String?, translate: Boolean, threads: Int): String
    private external fun nativeTranscribeJsonWithPrompt(handle: Long, audioSamples: FloatArray, language: String?, translate: Boolean, threads: Int, prompt: String?): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeReleaseContext(handle: Long)

    fun createContext(modelPath: String): Long = nativeCall { nativeCreateContext(modelPath) }

    fun detectLanguage(handle: Long, audioSamples: FloatArray, threads: Int): String =
        nativeCall { nativeDetectLanguage(handle, audioSamples, threads) }

    data class RecognitionSegment(
        val segment: SubtitleSegment,
        val averageTokenProbability: Float? = null,
        val noSpeechProbability: Float? = null
    )

    fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String? = null
    ): List<SubtitleSegment> = transcribeDetailed(handle, samples, language, translate, threads, prompt).map { it.segment }

    fun transcribeDetailed(
        handle: Long,
        samples: FloatArray,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String? = null
    ): List<RecognitionSegment> {
        val boundedPrompt = prompt?.replace(Regex("\s+"), " ")?.trim()?.take(512)?.takeIf { it.isNotBlank() }
        val json = if (boundedPrompt == null) {
            nativeCall { nativeTranscribeJson(handle, samples, language, translate, threads) }
        } else {
            try {
                nativeCall { nativeTranscribeJsonWithPrompt(handle, samples, language, translate, threads, boundedPrompt) }
            } catch (e: NativeBridgeException) {
                if (e.cause !is UnsatisfiedLinkError && e.code != NativeErrorCode.UNKNOWN) throw e
                // Backward-compatible fallback for an older bundled .so. Rebuilding native enables prompts.
                nativeCall { nativeTranscribeJson(handle, samples, language, translate, threads) }
            }
        }
        val arr = try { JSONArray(json) } catch (t: Throwable) {
            throw NativeBridgeException(NativeErrorCode.INVALID_RESULT, "Native Whisper returned malformed JSON", t)
        }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val start = o.getLong("startMs").coerceAtLeast(0L)
            val end = o.getLong("endMs").coerceAtLeast(start + 1L)
            RecognitionSegment(
                segment = SubtitleSegment(i.toLong(), start, end, o.getString("text")),
                averageTokenProbability = o.optDouble("averageTokenProbability", Double.NaN).takeIf { it.isFinite() && it >= 0.0 }?.toFloat(),
                noSpeechProbability = o.optDouble("noSpeechProbability", Double.NaN).takeIf { it.isFinite() && it >= 0.0 }?.toFloat()
            )
        }
    }

    fun cancel(handle: Long) { nativeCall<Unit> { nativeCancel(handle) } }
    fun releaseContext(handle: Long) { nativeCall<Unit> { nativeReleaseContext(handle) } }

    private inline fun <T> nativeCall(block: () -> T): T = try {
        ensureLoaded()
        block()
    } catch (e: IllegalStateException) {
        throw parse(e)
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (t: Throwable) {
        throw NativeBridgeException(NativeErrorCode.UNKNOWN, "Unexpected JNI bridge failure", t)
    }

    private fun parse(error: IllegalStateException): NativeBridgeException {
        val raw = error.message.orEmpty()
        if (!raw.startsWith(PREFIX)) return NativeBridgeException(NativeErrorCode.UNKNOWN, raw.ifBlank { "Unknown native failure" }, error)
        val parts = raw.split('|', limit = 3)
        val code = parts.getOrNull(1)?.let { runCatching { NativeErrorCode.valueOf(it) }.getOrNull() } ?: NativeErrorCode.UNKNOWN
        val detail = parts.getOrNull(2).orEmpty().ifBlank { "Native operation failed" }
        return NativeBridgeException(code, detail, error)
    }

    private const val PREFIX = "TVE|"
}
