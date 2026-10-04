package com.transvibe.app.domain.runtime

import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.WhisperModelType
import kotlinx.coroutines.Dispatchers
import kotlin.math.max

/**
 * User-visible processing policy.
 *
 * FAST and BALANCED both use the minimum Whisper pass count needed for the requested output.
 * PRESERVE_ORIGINAL may perform a second Whisper pass for non-English source speech so the
 * original-language transcript can be retained alongside the English intermediate text.
 */
enum class ProcessingProfile(
    val preserveOriginalTranscript: Boolean,
    val maxForeignLanguageWhisperPasses: Int
) {
    FAST(preserveOriginalTranscript = false, maxForeignLanguageWhisperPasses = 1),
    BALANCED(preserveOriginalTranscript = false, maxForeignLanguageWhisperPasses = 1),
    PRESERVE_ORIGINAL(preserveOriginalTranscript = true, maxForeignLanguageWhisperPasses = 2);

    companion object {
        /** POWER_SAVE was a pre-Phase-22 UI mode. Keep old persisted jobs runnable. */
        fun fromPersisted(value: String): ProcessingProfile = when (value) {
            FAST.name -> FAST
            PRESERVE_ORIGINAL.name -> PRESERVE_ORIGINAL
            else -> BALANCED
        }
    }
}

data class ProcessingTuning(
    val whisperThreads: Int,
    val audioChunkSeconds: Int,
    val channelCapacity: Int = 1
)

object ProcessingRuntime {
    /** Whisper remains strictly single-consumer even while decoding is allowed to prefetch one chunk. */
    val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun tuning(
        model: WhisperModel,
        profile: ProcessingProfile = ProcessingProfile.BALANCED,
        processors: Int = Runtime.getRuntime().availableProcessors(),
        thermallyConstrained: Boolean = false
    ): ProcessingTuning {
        val cores = processors.coerceAtLeast(1)
        val reserve = when {
            cores >= 8 -> 3
            cores >= 4 -> 2
            else -> 1
        }
        val sustainable = max(1, cores - reserve)
        val modelCap = when (model.type) {
            WhisperModelType.TINY -> 4
            WhisperModelType.BASE -> 3
            WhisperModelType.SMALL -> 3
            WhisperModelType.MEDIUM, WhisperModelType.LARGE, WhisperModelType.TURBO, WhisperModelType.MULTILINGUAL, WhisperModelType.CUSTOM -> 2
        }
        val profileCap = when (profile) {
            ProcessingProfile.FAST -> 6
            ProcessingProfile.BALANCED, ProcessingProfile.PRESERVE_ORIGINAL -> 4
        }
        val effectiveCap = if (thermallyConstrained) 2 else profileCap
        val threads = sustainable.coerceAtMost(modelCap).coerceAtMost(effectiveCap).coerceAtLeast(1)
        val chunk = if (thermallyConstrained) {
            20
        } else when (profile) {
            ProcessingProfile.FAST -> 40
            ProcessingProfile.BALANCED, ProcessingProfile.PRESERVE_ORIGINAL -> 30
        }
        return ProcessingTuning(threads, chunk, channelCapacity = if (thermallyConstrained) 0 else 1)
    }
}
