package com.transvibe.app.nativebridge.whisper

import com.transvibe.app.domain.repository.WhisperModelBinaryValidator
import com.transvibe.app.domain.runtime.ProcessingRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Semantic model probe after size/SHA validation and before atomic installation. */
class NativeWhisperModelBinaryValidator : WhisperModelBinaryValidator {
    @Volatile private var lastFailure: String? = null

    override suspend fun validate(candidatePath: String): Boolean = withContext(ProcessingRuntime.inferenceDispatcher) {
        var handle = 0L
        lastFailure = null
        try {
            handle = WhisperNative.createContext(candidatePath)
            handle != 0L
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val detail = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            lastFailure = if (detail.contains("libc++_shared.so", ignoreCase = true)) {
                "تعذر تحميل محرك Whisper لأن libc++_shared.so غير موجود داخل التطبيق. أعد بناء libtransvibe_native.so باستخدام C++ static runtime ثم أعد بناء التطبيق."
            } else {
                "فشل فتح نموذج Whisper داخل المحرك الأصلي: ${t.message ?: t::class.java.simpleName}"
            }
            false
        } finally {
            if (handle != 0L) runCatching { WhisperNative.releaseContext(handle) }
        }
    }

    override fun validationFailureMessage(): String? = lastFailure
}
