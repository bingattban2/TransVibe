package com.transvibe.app.domain.error

/** Stable application error domain. UI receives only safe, localized messages. */
enum class ProcessingErrorCode {
    INVALID_VIDEO, INVALID_CONFIGURATION, NO_AUDIO_TRACK, UNSUPPORTED_CODEC, MODEL_MISSING, MODEL_CORRUPTED,
    INSUFFICIENT_STORAGE, NETWORK_UNAVAILABLE, DOWNLOAD_FAILED, TRANSLATION_MODEL_MISSING,
    NATIVE_INITIALIZATION_FAILED, NATIVE_INFERENCE_FAILED, JNI_FAILURE, OUT_OF_MEMORY,
    DECODING_FAILED, PERMISSION_DENIED, FILE_WRITE_FAILED, CANCELLED, UNKNOWN, FOREGROUND_TIMEOUT
}

data class ProcessingError(
    val code: ProcessingErrorCode,
    val userMessage: String,
    val retryable: Boolean = true
)
