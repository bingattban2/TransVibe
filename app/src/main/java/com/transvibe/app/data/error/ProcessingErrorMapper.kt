package com.transvibe.app.data.error

import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.ProcessingErrorCode
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.media.decoder.AudioDecoder
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.nativebridge.whisper.NativeBridgeException
import com.transvibe.app.nativebridge.whisper.NativeErrorCode
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Maps implementation failures into stable domain errors. Technical details stay in debug logs. */
object ProcessingErrorMapper {
    private const val TAG = "ProcessingError"

    fun fromThrowable(error: Throwable): ProcessingError {
        AppLogger.e(TAG, "Processing failure: ${error::class.java.simpleName}", error)
        return when (error) {
            is CancellationException -> ProcessingError(ProcessingErrorCode.CANCELLED, "تم إلغاء المعالجة.")
            is OutOfMemoryError -> ProcessingError(ProcessingErrorCode.OUT_OF_MEMORY, "الذاكرة غير كافية. جرّب نموذج Whisper أصغر وأغلق التطبيقات الأخرى.")
            is SecurityException -> ProcessingError(ProcessingErrorCode.PERMISSION_DENIED, "لا يملك التطبيق إذن الوصول إلى الملف. اختره من جديد.")
            is FileNotFoundException -> ProcessingError(ProcessingErrorCode.INVALID_VIDEO, "تعذر العثور على ملف الفيديو أو لم يعد متاحًا.")
            is MediaSourceInspector.InspectionException.PermissionDenied -> ProcessingError(ProcessingErrorCode.PERMISSION_DENIED, "لا يملك التطبيق إذن الوصول إلى الفيديو. اختره من جديد.")
            is MediaSourceInspector.InspectionException.NoAudioTrack -> ProcessingError(ProcessingErrorCode.NO_AUDIO_TRACK, "الفيديو لا يحتوي على مسار صوتي.", false)
            is MediaSourceInspector.InspectionException.UnsupportedCodec -> ProcessingError(ProcessingErrorCode.UNSUPPORTED_CODEC, "ترميز الصوت في هذا الفيديو غير مدعوم على الجهاز.")
            is MediaSourceInspector.InspectionException.InvalidVideo -> ProcessingError(ProcessingErrorCode.INVALID_VIDEO, "الملف المحدد ليس فيديو صالحًا أو لم يعد متاحًا.")
            is AudioDecoder.AudioPipelineException.NoAudioTrack -> ProcessingError(ProcessingErrorCode.NO_AUDIO_TRACK, "الفيديو لا يحتوي على مسار صوتي.", false)
            is AudioDecoder.AudioPipelineException.UnsupportedCodec, is AudioDecoder.AudioPipelineException.UnsupportedPcm -> ProcessingError(ProcessingErrorCode.UNSUPPORTED_CODEC, "ترميز الصوت في هذا الفيديو غير مدعوم على الجهاز.")
            is AudioDecoder.AudioPipelineException.OpenSource -> ProcessingError(ProcessingErrorCode.INVALID_VIDEO, "تعذر فتح الفيديو. اختر ملفًا صالحًا وحاول مرة أخرى.")
            is AudioDecoder.AudioPipelineException -> ProcessingError(ProcessingErrorCode.DECODING_FAILED, "تعذر فك ترميز الصوت من الفيديو.")
            is NativeBridgeException -> native(error)
            is IOException -> ProcessingError(ProcessingErrorCode.FILE_WRITE_FAILED, "تعذر قراءة أو كتابة أحد ملفات المعالجة. تحقق من المساحة وحاول مرة أخرى.")
            else -> ProcessingError(ProcessingErrorCode.UNKNOWN, "حدث خطأ غير متوقع أثناء معالجة الفيديو.")
        }
    }

    fun fromDownloadThrowable(error: Throwable): ProcessingError {
        AppLogger.e(TAG, "Model download failure: ${error::class.java.simpleName}", error)
        val message = error.message.orEmpty()
        return when {
            error is CancellationException -> ProcessingError(ProcessingErrorCode.CANCELLED, "تم إلغاء التنزيل.")
            error is UnknownHostException || error is SocketTimeoutException -> ProcessingError(ProcessingErrorCode.NETWORK_UNAVAILABLE, "تعذر الاتصال بالخادم. تحقق من اتصال الإنترنت ثم أعد المحاولة.")
            error is SecurityException -> ProcessingError(ProcessingErrorCode.PERMISSION_DENIED, "لا يملك التطبيق إذن قراءة ملف النموذج المحدد.")
            message.contains("مساحة التخزين", true) -> ProcessingError(ProcessingErrorCode.INSUFFICIENT_STORAGE, "مساحة التخزين غير كافية لتنزيل النموذج.")
            message.contains("libc++_shared.so", true) || message.contains("محرك Whisper", true) ->
                ProcessingError(ProcessingErrorCode.NATIVE_INITIALIZATION_FAILED, message.ifBlank { "تعذر تهيئة محرك Whisper." }, retryable = false)
            else -> ProcessingError(ProcessingErrorCode.DOWNLOAD_FAILED, "فشل تنزيل نموذج الذكاء الاصطناعي. أعد المحاولة بعد التحقق من الاتصال والمساحة.")
        }
    }

    fun fromResource(state: AiResourceState): ProcessingError = when (state) {
        AiResourceState.MissingWhisperModel -> ProcessingError(ProcessingErrorCode.MODEL_MISSING, "نموذج Whisper المطلوب غير مثبت.")
        AiResourceState.MissingTranslationModel -> ProcessingError(ProcessingErrorCode.TRANSLATION_MODEL_MISSING, "حزمة الترجمة الإنجليزية إلى العربية غير مثبتة.")
        is AiResourceState.MissingCapability -> ProcessingError(ProcessingErrorCode.UNKNOWN, "إحدى قدرات الذكاء الاصطناعي المطلوبة للمسار المحدد غير جاهزة.")
        is AiResourceState.CorruptedModel -> ProcessingError(ProcessingErrorCode.MODEL_CORRUPTED, "نموذج Whisper تالف ويجب إعادة تنزيله.")
        is AiResourceState.InsufficientStorage -> ProcessingError(ProcessingErrorCode.INSUFFICIENT_STORAGE, "مساحة التخزين غير كافية لإكمال العملية.")
        AiResourceState.NetworkUnavailable -> ProcessingError(ProcessingErrorCode.NETWORK_UNAVAILABLE, "لا يوجد اتصال بالإنترنت.")
        is AiResourceState.Downloading -> ProcessingError(ProcessingErrorCode.MODEL_MISSING, "لا يزال نموذج Whisper قيد التنزيل.")
        is AiResourceState.Error -> ProcessingError(ProcessingErrorCode.UNKNOWN, "تعذر التحقق من مكونات الذكاء الاصطناعي.")
        AiResourceState.Ready -> ProcessingError(ProcessingErrorCode.UNKNOWN, "تعذر بدء المعالجة.")
    }

    private fun native(error: NativeBridgeException): ProcessingError = when (error.code) {
        NativeErrorCode.MODEL_INVALID, NativeErrorCode.MODEL_NOT_FOUND, NativeErrorCode.MODEL_NOT_READABLE -> ProcessingError(ProcessingErrorCode.MODEL_CORRUPTED, "ملف نموذج Whisper غير صالح أو غير قابل للقراءة.")
        NativeErrorCode.INIT_FAILED -> ProcessingError(ProcessingErrorCode.NATIVE_INITIALIZATION_FAILED, "تعذر تهيئة محرك Whisper. تحقق من النموذج ثم أعد المحاولة.")
        NativeErrorCode.INFERENCE_FAILED, NativeErrorCode.INVALID_RESULT -> ProcessingError(ProcessingErrorCode.NATIVE_INFERENCE_FAILED, "تعذر إكمال التعرف على الكلام باستخدام Whisper.")
        NativeErrorCode.CANCELLED -> ProcessingError(ProcessingErrorCode.CANCELLED, "تم إلغاء المعالجة.")
        NativeErrorCode.INVALID_HANDLE, NativeErrorCode.INVALID_AUDIO, NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.ALLOCATION_FAILED, NativeErrorCode.UNKNOWN -> ProcessingError(ProcessingErrorCode.JNI_FAILURE, "حدث خلل في محرك المعالجة المحلي. أعد المحاولة.")
    }
}
