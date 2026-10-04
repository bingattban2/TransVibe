package com.transvibe.app.data.error

import com.transvibe.app.domain.error.ProcessingErrorCode
import com.transvibe.app.nativebridge.whisper.NativeBridgeException
import com.transvibe.app.nativebridge.whisper.NativeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingErrorMapperTest {
    @Test
    fun nativeInferenceFailureMapsToStableDomainCode() {
        val error = ProcessingErrorMapper.fromThrowable(
            NativeBridgeException(NativeErrorCode.INFERENCE_FAILED, "internal native detail")
        )
        assertEquals(ProcessingErrorCode.NATIVE_INFERENCE_FAILED, error.code)
        assertEquals("تعذر إكمال التعرف على الكلام باستخدام Whisper.", error.userMessage)
    }

    @Test
    fun outOfMemoryHasDedicatedUserSafeError() {
        val error = ProcessingErrorMapper.fromThrowable(OutOfMemoryError("internal allocator detail"))
        assertEquals(ProcessingErrorCode.OUT_OF_MEMORY, error.code)
    }
}
