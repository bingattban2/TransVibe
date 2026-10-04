package com.transvibe.app.domain.model

import com.transvibe.app.domain.language.SourceLanguageMode
import com.transvibe.app.domain.language.SourceLanguagePolicy

import com.transvibe.app.domain.error.ProcessingError

enum class WhisperModelType {
    TINY,
    BASE,
    SMALL,
    MEDIUM,
    LARGE,
    TURBO,
    MULTILINGUAL,
    CUSTOM
}

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val estimatedRamBytes: Long?,
    val type: WhisperModelType,
    val descriptionAr: String,
    val recommended: Boolean = false,
    val englishOnly: Boolean = false
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\'))
        require(downloadUrl.startsWith("https://")) { "Model downloads must use HTTPS" }
        require(fileSizeBytes > 0)
        require(estimatedRamBytes == null || estimatedRamBytes > 0)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "A trusted SHA-256 is required for every downloadable model" }
    }
}

data class SubtitleSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val englishText: String? = null,
    val translatedText: String? = null
) {
    init {
        require(startMs >= 0L) { "startMs must be >= 0" }
        require(endMs > startMs) { "endMs must be greater than startMs" }
    }
}

enum class ProcessingOutcome { COMPLETED, FAILED, CANCELLED }

data class ProcessingHistorySummary(
    val jobId: String,
    val displayName: String,
    val modelId: String,
    val detectedLanguage: String,
    val state: ProcessingJobState,
    val sourceUriPersistable: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

data class ProcessingSession(
    val id: String,
    val videoUri: String,
    val videoName: String,
    val modelId: String,
    val detectedLanguage: String,
    val targetLanguage: String = "ar",
    val createdAtEpochMs: Long,
    val srtPath: String,
    val vttPath: String,
    val segments: List<SubtitleSegment>,
    val sourceUriPersistable: Boolean = false,
    val outcome: ProcessingOutcome = ProcessingOutcome.COMPLETED
)

enum class ModelIntegrityState {
    READY,
    MISSING,
    BINARY_CORRUPTED,
    METADATA_MISSING,
    METADATA_STALE,
    VERIFYING
}


enum class TranslationResourceState {
    READY,
    MISSING,
    DOWNLOADING,
    ERROR,
    NEEDS_UPDATE
}

data class TranslationPackInfo(
    val language: LanguageCode,
    val state: TranslationResourceState,
    val approximateBytes: Long = 0L,
    val errorMessage: String? = null
)

data class TranslationPackStorage(
    val installedPackCount: Int = 0,
    val approximateBytes: Long = 0L
)

data class AiResourceMetadata(
    val selectedModelId: String = "tiny",
    val selectedModelSizeBytes: Long = 0L,
    val expectedModelSizeBytes: Long = 0L,
    val partialBytes: Long = 0L,
    val lastCheckedEpochMs: Long = 0L
)

enum class AiCapability {
    SPEECH_RECOGNITION,
    SPEECH_TO_ENGLISH_TRANSLATION,
    TARGET_LANGUAGE_TRANSLATION,
    VAD
}

sealed interface AiCapabilityState {
    data object Checking : AiCapabilityState
    data object Ready : AiCapabilityState
    data class Missing(val reason: String? = null) : AiCapabilityState
    data class Error(val message: String) : AiCapabilityState
    data object NotConfigured : AiCapabilityState
}

data class AiCapabilitySnapshot(
    val speechRecognition: AiCapabilityState = AiCapabilityState.Checking,
    val speechToEnglishTranslation: AiCapabilityState = AiCapabilityState.Checking,
    val targetLanguageTranslation: AiCapabilityState = AiCapabilityState.Checking,
    val vad: AiCapabilityState = AiCapabilityState.Ready
) {
    fun state(capability: AiCapability): AiCapabilityState = when (capability) {
        AiCapability.SPEECH_RECOGNITION -> speechRecognition
        AiCapability.SPEECH_TO_ENGLISH_TRANSLATION -> speechToEnglishTranslation
        AiCapability.TARGET_LANGUAGE_TRANSLATION -> targetLanguageTranslation
        AiCapability.VAD -> vad
    }
}

data class ProcessingResourceRequest(
    val sourceLanguage: String,
    val targetLanguage: String = "ar",
    val vadRequested: Boolean = false
)

data class ProcessingCapabilityPlan(
    val required: Set<AiCapability>,
    val conditional: Set<AiCapability> = emptySet()
)

sealed interface ProcessingResourceReadiness {
    data class Ready(
        val plan: ProcessingCapabilityPlan
    ) : ProcessingResourceReadiness

    data class Waiting(
        val plan: ProcessingCapabilityPlan,
        val unresolved: Map<AiCapability, AiCapabilityState>
    ) : ProcessingResourceReadiness
}

object ProcessingCapabilityPlanner {
    fun plan(request: ProcessingResourceRequest): ProcessingCapabilityPlan {
        val sourcePolicy = SourceLanguagePolicy.parse(request.sourceLanguage)
        val source = if (sourcePolicy.mode == SourceLanguageMode.MANUAL) sourcePolicy.manualLanguage.orEmpty() else "auto"
        val target = request.targetLanguage.trim().lowercase()
        val required = linkedSetOf(AiCapability.SPEECH_RECOGNITION)
        val conditional = linkedSetOf<AiCapability>()

        if (source == "auto") {
            // The detected branch is not known yet. Only the capabilities that may be needed
            // after detection are marked conditional; nothing is downloaded pre-emptively.
            if (target == "en") {
                conditional += AiCapability.SPEECH_TO_ENGLISH_TRANSLATION
            } else {
                conditional += AiCapability.SPEECH_TO_ENGLISH_TRANSLATION
                conditional += AiCapability.TARGET_LANGUAGE_TRANSLATION
            }
        } else if (source != target) {
            when {
                target == "en" -> required += AiCapability.SPEECH_TO_ENGLISH_TRANSLATION
                source == "en" -> required += AiCapability.TARGET_LANGUAGE_TRANSLATION
                else -> {
                    required += AiCapability.SPEECH_TO_ENGLISH_TRANSLATION
                    required += AiCapability.TARGET_LANGUAGE_TRANSLATION
                }
            }
        }
        if (request.vadRequested) required += AiCapability.VAD
        return ProcessingCapabilityPlan(required = required, conditional = conditional)
    }

    fun readiness(
        request: ProcessingResourceRequest,
        capabilities: AiCapabilitySnapshot
    ): ProcessingResourceReadiness {
        val plan = plan(request)
        val unresolved = plan.required.mapNotNull { capability ->
            val state = capabilities.state(capability)
            if (state == AiCapabilityState.Ready) null else capability to state
        }.toMap()
        return if (unresolved.isEmpty()) ProcessingResourceReadiness.Ready(plan)
        else ProcessingResourceReadiness.Waiting(plan, unresolved)
    }
}

data class AiResourceSnapshot(
    val checking: Boolean = true,
    val modelStates: Map<String, ModelIntegrityState> = emptyMap(),
    val capabilities: AiCapabilitySnapshot = AiCapabilitySnapshot(),
    val metadata: AiResourceMetadata = AiResourceMetadata(),
    val errorMessage: String? = null
) {
    fun modelState(modelId: String): ModelIntegrityState = modelStates[modelId] ?: ModelIntegrityState.MISSING
    fun whisperReadyFor(modelId: String): Boolean = !checking && modelState(modelId) == ModelIntegrityState.READY
    fun readinessFor(request: ProcessingResourceRequest): ProcessingResourceReadiness =
        ProcessingCapabilityPlanner.readiness(request, capabilities)
}

sealed interface StartupState {
    data object InitializingRepositories : StartupState
    data object InspectingSelectedModel : StartupState
    data object ValidatingSelectedWhisperFile : StartupState
    data object DeterminingSelectedResourceState : StartupState
    data class Resolved(val resources: AiResourceSnapshot) : StartupState
}

/** Detailed validation outcome for a single requested pipeline, not global AI readiness. */
sealed interface AiResourceState {
    data object Ready : AiResourceState
    data object MissingWhisperModel : AiResourceState
    data object MissingTranslationModel : AiResourceState
    data class MissingCapability(val capability: AiCapability) : AiResourceState
    data class Downloading(val modelId: String? = null, val progress: Int = 0) : AiResourceState
    data class CorruptedModel(val modelId: String? = null) : AiResourceState
    data class InsufficientStorage(val requiredBytes: Long = 0L, val availableBytes: Long = 0L) : AiResourceState
    data object NetworkUnavailable : AiResourceState
    data class Error(val message: String) : AiResourceState
}


sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Preparing(val progress: Float = 2f) : ProcessingState
    data class ExtractingAudio(val progress: Float) : ProcessingState
    data class Recognizing(val progress: Float, val language: String?) : ProcessingState
    data class Translating(val progress: Float) : ProcessingState
    data class Exporting(val progress: Float) : ProcessingState
    data class Completed(val result: ProcessingResult) : ProcessingState
    data class Error(val error: ProcessingError) : ProcessingState
    data object Cancelled : ProcessingState
}

data class ProcessingResult(val session: ProcessingSession)

data class AppPreferences(
    val keepScreenOnDuringPlayback: Boolean = false,
    val preferredExportFormat: String = "srt",
    val processingProfile: String = "BALANCED",
    val applicationLanguage: String = "system",
    val videoSpeechLanguage: String = "auto_video",
    val subtitleTargetLanguage: String = "ar",
    val wifiOnlyTranslationDownloads: Boolean = true,
    val subtitleTextScale: Float = 1.0f,
    val themeMode: String = "system"
)


data class SourceAccessState(val sessionId: String, val accessible: Boolean)

data class StorageInfo(
    val modelBytes: Long = 0L,
    val sessionBytes: Long = 0L,
    val freeBytes: Long = 0L
)

data class SelectedVideo(val uriString: String, val displayName: String, val persistableAccess: Boolean = false)

data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val percent: Int = 0,
    val status: Status = Status.IDLE,
    val message: String? = null,
    val error: ProcessingError? = null
) {
    enum class Status { IDLE, DOWNLOADING, RETRYING, PAUSED, VERIFYING, IMPORTING, INSTALLED, FAILED, CANCELLED }
}

object ModelCatalog {
    private const val CLASSIC_REV = "80da2d8bfee42b0e836fc3a9890373e5defc00a6"
    private const val LARGE_V3_REV = "362722b3fdcd2300b58a8286933ead1c48619667"
    private const val TURBO_REV = "98aa99a0a9db05ae2342309f5096248665f7cba3"
    private fun url(revision: String, fileName: String): String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/$revision/$fileName?download=true"

    val whisperModels: List<WhisperModel> = listOf(
        WhisperModel(
            id = "tiny", displayName = "Whisper Tiny", fileName = "ggml-tiny.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-tiny.bin"),
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            fileSizeBytes = 77_691_713L, estimatedRamBytes = 390L * 1024L * 1024L,
            type = WhisperModelType.TINY,
            descriptionAr = "أخف نموذج متعدد اللغات؛ الأسرع والأقل استهلاكاً للذاكرة، ومناسب لمعظم الهواتف.",
            recommended = true
        ),
        WhisperModel(
            id = "tiny-en", displayName = "Whisper Tiny.en", fileName = "ggml-tiny.en.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-tiny.en.bin"),
            sha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
            fileSizeBytes = 77_704_715L, estimatedRamBytes = 390L * 1024L * 1024L,
            type = WhisperModelType.TINY,
            descriptionAr = "نسخة Tiny مخصصة للإنجليزية فقط؛ لا تستخدمها لمصادر عربية أو متعددة اللغات.",
            englishOnly = true
        ),
        WhisperModel(
            id = "base", displayName = "Whisper Base", fileName = "ggml-base.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-base.bin"),
            sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            fileSizeBytes = 147_951_465L, estimatedRamBytes = 500L * 1024L * 1024L,
            type = WhisperModelType.BASE,
            descriptionAr = "دقة أعلى من Tiny مع استهلاك متوسط للذاكرة؛ خيار متوازن للهواتف الحديثة."
        ),
        WhisperModel(
            id = "base-en", displayName = "Whisper Base.en", fileName = "ggml-base.en.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-base.en.bin"),
            sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
            fileSizeBytes = 147_964_211L, estimatedRamBytes = 500L * 1024L * 1024L,
            type = WhisperModelType.BASE,
            descriptionAr = "نسخة Base للإنجليزية فقط؛ أدق من Tiny.en وتحتاج ذاكرة أكبر قليلاً.",
            englishOnly = true
        ),
        WhisperModel(
            id = "small", displayName = "Whisper Small", fileName = "ggml-small.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-small.bin"),
            sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            fileSizeBytes = 487_601_967L, estimatedRamBytes = 1_024L * 1024L * 1024L,
            type = WhisperModelType.SMALL,
            descriptionAr = "دقة جيدة متعددة اللغات، لكن المعالجة أبطأ وتحتاج قرابة 1 GB من RAM."
        ),
        WhisperModel(
            id = "small-en", displayName = "Whisper Small.en", fileName = "ggml-small.en.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-small.en.bin"),
            sha256 = "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d",
            fileSizeBytes = 487_614_201L, estimatedRamBytes = 1_024L * 1024L * 1024L,
            type = WhisperModelType.SMALL,
            descriptionAr = "نسخة Small مخصصة للإنجليزية فقط؛ دقة أعلى مقابل استهلاك RAM أكبر.",
            englishOnly = true
        ),
        WhisperModel(
            id = "medium", displayName = "Whisper Medium", fileName = "ggml-medium.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-medium.bin"),
            sha256 = "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208",
            fileSizeBytes = 1_533_763_059L, estimatedRamBytes = 2_600L * 1024L * 1024L,
            type = WhisperModelType.MEDIUM,
            descriptionAr = "دقة مرتفعة متعددة اللغات؛ مناسب للأجهزة ذات RAM كبيرة ومعالج قوي."
        ),
        WhisperModel(
            id = "medium-en", displayName = "Whisper Medium.en", fileName = "ggml-medium.en.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-medium.en.bin"),
            sha256 = "cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356",
            fileSizeBytes = 1_533_774_781L, estimatedRamBytes = 2_600L * 1024L * 1024L,
            type = WhisperModelType.MEDIUM,
            descriptionAr = "نسخة Medium للإنجليزية فقط؛ دقيقة لكنها ثقيلة على الهواتف متوسطة الذاكرة.",
            englishOnly = true
        ),
        WhisperModel(
            id = "large-v1", displayName = "Whisper Large v1", fileName = "ggml-large-v1.bin",
            downloadUrl = url(CLASSIC_REV, "ggml-large-v1.bin"),
            sha256 = "7d99f41a10525d0206bddadd86760181fa920438b6b33237e3118ff6c83bb53d",
            fileSizeBytes = 3_094_623_691L, estimatedRamBytes = 4_700L * 1024L * 1024L,
            type = WhisperModelType.LARGE,
            descriptionAr = "Large v1 متعدد اللغات؛ دقة عالية جداً ومتطلبات ذاكرة ومعالجة كبيرة."
        ),
        WhisperModel(
            id = "large-v2", displayName = "Whisper Large v2", fileName = "ggml-large-v2.bin",
            downloadUrl = url(LARGE_V3_REV, "ggml-large-v2.bin"),
            sha256 = "9a423fe4d40c82774b6af34115b8b935f34152246eb19e80e376071d3f999487",
            fileSizeBytes = 3_094_623_691L, estimatedRamBytes = 4_700L * 1024L * 1024L,
            type = WhisperModelType.LARGE,
            descriptionAr = "Large v2 متعدد اللغات؛ تحسينات دقة مقارنة بالإصدار الأول مع كلفة تشغيل مرتفعة."
        ),
        WhisperModel(
            id = "large-v3", displayName = "Whisper Large v3", fileName = "ggml-large-v3.bin",
            downloadUrl = url(LARGE_V3_REV, "ggml-large-v3.bin"),
            sha256 = "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2",
            fileSizeBytes = 3_095_033_483L, estimatedRamBytes = 4_700L * 1024L * 1024L,
            type = WhisperModelType.LARGE,
            descriptionAr = "أقوى نموذج Large متعدد اللغات في السلسلة الكاملة؛ يحتاج جهازاً بذاكرة كبيرة جداً."
        ),
        WhisperModel(
            id = "large-v3-turbo", displayName = "Whisper Large v3 Turbo", fileName = "ggml-large-v3-turbo.bin",
            downloadUrl = url(TURBO_REV, "ggml-large-v3-turbo.bin"),
            sha256 = "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69",
            fileSizeBytes = 1_624_555_275L, estimatedRamBytes = 2_700L * 1024L * 1024L,
            type = WhisperModelType.TURBO,
            descriptionAr = "Large v3 Turbo متعدد اللغات؛ أسرع من Large v3 مع دقة قوية وحجم أقل، لكنه ما يزال ثقيلاً على الهواتف."
        )
    )

    val recommended: WhisperModel = whisperModels.first { it.recommended }

    fun byId(id: String): WhisperModel? = whisperModels.firstOrNull { it.id == id }
}

