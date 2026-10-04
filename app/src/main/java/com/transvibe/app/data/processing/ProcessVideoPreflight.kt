package com.transvibe.app.data.processing

import android.net.Uri
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.ProcessingErrorCode
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.language.SourceLanguageMode
import com.transvibe.app.domain.language.SourceLanguagePolicy
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.domain.repository.ProcessingPreflight
import kotlinx.coroutines.CancellationException

/** Enforces preflight against the capabilities required by the user's requested pipeline. */
class ProcessVideoPreflight(
    private val resources: AiResourceManager,
    private val sourceInspector: MediaSourceInspector
) : ProcessingPreflight {
    override suspend fun validate(
        video: SelectedVideo?,
        model: WhisperModel,
        request: ProcessingResourceRequest
    ): ProcessingError? {
        if (video == null || video.uriString.isBlank()) {
            return ProcessingError(ProcessingErrorCode.INVALID_VIDEO, "اختر فيديو صالحًا قبل بدء المعالجة.", retryable = false)
        }
        val uri = runCatching { Uri.parse(video.uriString) }.getOrNull()
            ?: return ProcessingError(ProcessingErrorCode.INVALID_VIDEO, "تعذر قراءة عنوان الفيديو المحدد.")

        if (model.englishOnly) {
            val source = SourceLanguagePolicy.parse(request.sourceLanguage)
            if (source.mode != SourceLanguageMode.MANUAL || source.manualLanguage != "en") {
                return ProcessingError(
                    ProcessingErrorCode.INVALID_CONFIGURATION,
                    "النموذج ${model.displayName} مخصص للإنجليزية فقط. اختر لغة المصدر يدوياً: الإنجليزية، أو استخدم نموذج Whisper متعدد اللغات.",
                    retryable = false
                )
            }
        }

        val resourceState = resources.checkPipeline(model, request)
        if (resourceState != AiResourceState.Ready) return ProcessingErrorMapper.fromResource(resourceState)

        return try {
            sourceInspector.ensureReadable(uri)
            sourceInspector.inspectAudio(uri)
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ProcessingErrorMapper.fromThrowable(t)
        }
    }
}
