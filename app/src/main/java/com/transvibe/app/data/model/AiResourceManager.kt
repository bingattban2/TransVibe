package com.transvibe.app.data.model

import android.content.Context
import android.os.StatFs
import com.transvibe.app.domain.model.AiCapability
import com.transvibe.app.domain.model.AiCapabilitySnapshot
import com.transvibe.app.domain.model.AiCapabilityState
import com.transvibe.app.domain.model.AiResourceMetadata
import com.transvibe.app.domain.model.AiResourceSnapshot
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ModelCatalog
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.domain.model.ProcessingCapabilityPlanner
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.StartupState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.translation.TranslationEngineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiResourceManager(
    context: Context,
    private val store: ModelStore,
    private val translationEngines: TranslationEngineFactory,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("ai_resources", Context.MODE_PRIVATE)
    private val defaultModelId = ModelCatalog.recommended.id
    private val _snapshot = MutableStateFlow(AiResourceSnapshot())
    val snapshot: StateFlow<AiResourceSnapshot> = _snapshot.asStateFlow()
    private val _startupState = MutableStateFlow<StartupState>(StartupState.InitializingRepositories)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    val selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL, defaultModelId)
            ?.takeIf { id -> ModelCatalog.whisperModels.any { it.id == id } }
            ?: defaultModelId

    init {
        scope.launch { refreshStartupResources() }
    }

    fun setSelectedModel(modelId: String) {
        require(ModelCatalog.whisperModels.any { it.id == modelId }) { "نموذج Whisper غير معروف" }
        prefs.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
        scope.launch { refreshModel(selectedModel()) }
    }

    /** Startup validates only the selected speech model. Optional capabilities stay independent. */
    suspend fun refreshStartupResources() {
        _snapshot.update { it.copy(checking = true, errorMessage = null) }
        try {
            _startupState.value = StartupState.InspectingSelectedModel
            val model = selectedModel()
            _startupState.value = StartupState.ValidatingSelectedWhisperFile
            val integrity = store.inspectAndRecover(model)
            _startupState.value = StartupState.DeterminingSelectedResourceState
            val current = _snapshot.value
            val next = current.copy(
                checking = false,
                modelStates = current.modelStates + (model.id to integrity),
                capabilities = current.capabilities.withWhisperIntegrity(integrity),
                metadata = metadataFor(model),
                errorMessage = integrityError(integrity)
            )
            _snapshot.value = next
            _startupState.value = StartupState.Resolved(next)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            val failed = _snapshot.value.copy(
                checking = false,
                capabilities = _snapshot.value.capabilities.copy(
                    speechRecognition = AiCapabilityState.Error("تعذر التحقق من نموذج Whisper المحدد."),
                    speechToEnglishTranslation = AiCapabilityState.Error("تعذر التحقق من قدرة Whisper على الترجمة إلى الإنجليزية.")
                ),
                errorMessage = "تعذر التحقق من نموذج Whisper المحدد."
            )
            _snapshot.value = failed
            _startupState.value = StartupState.Resolved(failed)
        }
    }

    suspend fun refreshAll() {
        _snapshot.update { it.copy(checking = true, errorMessage = null) }
        try {
            val states = buildMap {
                for (model in ModelCatalog.whisperModels) put(model.id, store.inspectAndRecover(model))
            }
            val selected = selectedModel()
            val selectedIntegrity = states[selected.id] ?: ModelIntegrityState.MISSING
            val targetTranslation = checkTargetTranslationState()
            _snapshot.value = AiResourceSnapshot(
                checking = false,
                modelStates = states,
                capabilities = AiCapabilitySnapshot(
                    speechRecognition = capabilityFromWhisper(selectedIntegrity),
                    speechToEnglishTranslation = capabilityFromWhisper(selectedIntegrity),
                    targetLanguageTranslation = targetTranslation,
                    vad = AiCapabilityState.Ready
                ),
                metadata = metadataFor(selected),
                errorMessage = integrityError(selectedIntegrity)
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            _snapshot.update { current ->
                current.copy(
                    checking = false,
                    errorMessage = "تعذر التحقق من بعض قدرات الذكاء الاصطناعي."
                )
            }
        }
    }

    suspend fun refreshModel(model: WhisperModel): ModelIntegrityState {
        _snapshot.update { current ->
            val capabilities = if (model.id == selectedModelId) {
                current.capabilities.copy(
                    speechRecognition = AiCapabilityState.Checking,
                    speechToEnglishTranslation = AiCapabilityState.Checking
                )
            } else current.capabilities
            current.copy(modelStates = current.modelStates + (model.id to ModelIntegrityState.VERIFYING), capabilities = capabilities)
        }
        val integrity = try {
            store.inspectAndRecover(model)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            ModelIntegrityState.VERIFYING
        }
        _snapshot.update { current ->
            current.copy(
                modelStates = current.modelStates + (model.id to integrity),
                capabilities = if (model.id == selectedModelId) current.capabilities.withWhisperIntegrity(integrity) else current.capabilities,
                metadata = if (model.id == selectedModelId) metadataFor(model) else current.metadata,
                errorMessage = if (model.id == selectedModelId) integrityError(integrity) else current.errorMessage
            )
        }
        return integrity
    }

    suspend fun refreshTranslation(
        sourceLanguage: LanguageCode = LanguageCode.ENGLISH,
        targetLanguage: LanguageCode = LanguageCode.ARABIC
    ): Boolean {
        val state = checkTargetTranslationState(sourceLanguage, targetLanguage)
        _snapshot.update { current ->
            current.copy(
                capabilities = current.capabilities.copy(targetLanguageTranslation = state),
                metadata = metadataFor(selectedModel())
            )
        }
        return state == AiCapabilityState.Ready
    }

    fun markModel(model: WhisperModel, state: ModelIntegrityState) {
        _snapshot.update { current ->
            current.copy(
                modelStates = current.modelStates + (model.id to state),
                capabilities = if (model.id == selectedModelId) current.capabilities.withWhisperIntegrity(state) else current.capabilities,
                metadata = if (model.id == selectedModelId) metadataFor(model) else current.metadata
            )
        }
    }

    fun markDownloading(model: WhisperModel, progress: Int) {
        if (model.id == selectedModelId) {
            _snapshot.update { current ->
                current.copy(
                    capabilities = current.capabilities.copy(
                        speechRecognition = AiCapabilityState.Checking,
                        speechToEnglishTranslation = AiCapabilityState.Checking
                    ),
                    metadata = metadataFor(model),
                    errorMessage = "جارٍ تنزيل نموذج Whisper: ${progress.coerceIn(0, 100)}%"
                )
            }
        }
    }

    fun markNetworkUnavailable() {
        _snapshot.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت لتنزيل المورد المطلوب.") }
    }

    /** Validates only capabilities required by the concrete requested pipeline. */
    suspend fun checkPipeline(model: WhisperModel, request: ProcessingResourceRequest): AiResourceState {
        val modelState = refreshModel(model)
        modelStateFailure(model, modelState)?.let { return it }

        val plan = ProcessingCapabilityPlanner.plan(request)
        if (AiCapability.TARGET_LANGUAGE_TRANSLATION in plan.required &&
            !refreshTranslation(LanguageCode.ENGLISH, LanguageCode.of(request.targetLanguage))
        ) {
            return AiResourceState.MissingTranslationModel
        }
        if (AiCapability.VAD in plan.required && snapshot.value.capabilities.vad != AiCapabilityState.Ready) {
            return AiResourceState.MissingCapability(AiCapability.VAD)
        }
        // Whisper's multilingual model provides recognition and speech->English translation
        // through the same validated model binary, so those capabilities share model integrity.
        return AiResourceState.Ready
    }

    private fun modelStateFailure(model: WhisperModel, state: ModelIntegrityState): AiResourceState? = when (state) {
        ModelIntegrityState.MISSING -> storageAwareMissing(model)
        ModelIntegrityState.BINARY_CORRUPTED -> AiResourceState.CorruptedModel(model.id)
        ModelIntegrityState.METADATA_MISSING, ModelIntegrityState.METADATA_STALE -> AiResourceState.Error("تعذر إصلاح بيانات النموذج المحلية")
        ModelIntegrityState.VERIFYING -> AiResourceState.Error("جارٍ التحقق من النموذج")
        ModelIntegrityState.READY -> null
    }

    private fun selectedModel(): WhisperModel = ModelCatalog.byId(selectedModelId) ?: ModelCatalog.recommended

    private fun AiCapabilitySnapshot.withWhisperIntegrity(integrity: ModelIntegrityState): AiCapabilitySnapshot {
        val capability = capabilityFromWhisper(integrity)
        return copy(speechRecognition = capability, speechToEnglishTranslation = capability)
    }

    private fun capabilityFromWhisper(integrity: ModelIntegrityState): AiCapabilityState = when (integrity) {
        ModelIntegrityState.READY -> AiCapabilityState.Ready
        ModelIntegrityState.VERIFYING -> AiCapabilityState.Checking
        ModelIntegrityState.MISSING -> AiCapabilityState.Missing("نموذج Whisper غير مثبت")
        ModelIntegrityState.BINARY_CORRUPTED -> AiCapabilityState.Error("نموذج Whisper تالف")
        ModelIntegrityState.METADATA_MISSING, ModelIntegrityState.METADATA_STALE -> AiCapabilityState.Error("بيانات نموذج Whisper تحتاج إلى إصلاح")
    }

    private fun integrityError(integrity: ModelIntegrityState): String? = when (integrity) {
        ModelIntegrityState.READY, ModelIntegrityState.MISSING, ModelIntegrityState.VERIFYING -> null
        ModelIntegrityState.BINARY_CORRUPTED -> "نموذج Whisper المحدد تالف."
        ModelIntegrityState.METADATA_MISSING, ModelIntegrityState.METADATA_STALE -> "تعذر إصلاح بيانات النموذج المحلية."
    }

    private fun storageAwareMissing(model: WhisperModel): AiResourceState {
        val partial = store.partial(model).takeIf { it.isFile }?.length() ?: 0L
        val required = (model.fileSizeBytes - partial).coerceAtLeast(0L) + STORAGE_RESERVE_BYTES
        val available = runCatching { StatFs(store.whisperDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        return if (available < required) AiResourceState.InsufficientStorage(required, available)
        else AiResourceState.MissingWhisperModel
    }

    private fun metadataFor(model: WhisperModel): AiResourceMetadata {
        val final = store.file(model)
        val partial = store.partial(model)
        return AiResourceMetadata(
            selectedModelId = model.id,
            selectedModelSizeBytes = final.takeIf { it.isFile }?.length() ?: 0L,
            expectedModelSizeBytes = model.fileSizeBytes,
            partialBytes = partial.takeIf { it.isFile }?.length() ?: 0L,
            lastCheckedEpochMs = System.currentTimeMillis()
        )
    }

    private suspend fun checkTargetTranslationState(
        sourceLanguage: LanguageCode = LanguageCode.ENGLISH,
        targetLanguage: LanguageCode = LanguageCode.ARABIC
    ): AiCapabilityState {
        val engine = translationEngines.create(sourceLanguage, targetLanguage)
        return try {
            if (engine.isReady()) AiCapabilityState.Ready
            else AiCapabilityState.Missing("حزمة الترجمة ${sourceLanguage.value} → ${targetLanguage.value} غير مثبتة")
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            AiCapabilityState.Error("تعذر التحقق من حزمة الترجمة ${sourceLanguage.value} → ${targetLanguage.value}")
        } finally {
            engine.close()
        }
    }

    private companion object {
        const val KEY_SELECTED_MODEL = "selected_whisper_model"
        const val STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L
    }
}
