package com.transvibe.app.presentation.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ProcessingCapabilityPlanner
import com.transvibe.app.domain.model.ProcessingResourceReadiness
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.VideoSelectionGateway
import com.transvibe.app.domain.repository.ProcessingPreflight
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val translationPacks: TranslationPackRepository,
    private val videoSelection: VideoSelectionGateway,
    private val preflight: ProcessingPreflight,
    private val processingTasks: ProcessingTaskGateway
) : ViewModel() {

    val resources = models.resourceSnapshot
    val selectedModelId = models.selectedModelId
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )

    val resourceRequest: StateFlow<ProcessingResourceRequest> = preferences
        .map(::requestFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), requestFor(AppPreferences()))

    val pipelineReadiness: StateFlow<ProcessingResourceReadiness> = combine(resources, resourceRequest) { snapshot, request ->
        snapshot.readinessFor(request)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        resources.value.readinessFor(resourceRequest.value)
    )

    private val _selectedVideo = MutableStateFlow<SelectedVideo?>(null)
    val selectedVideo: StateFlow<SelectedVideo?> = _selectedVideo.asStateFlow()
    private val _preflightBusy = MutableStateFlow(false)
    val preflightBusy: StateFlow<Boolean> = _preflightBusy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val processingReadyChannel = Channel<Unit>(Channel.CONFLATED)
    val processingReady: Flow<Unit> = processingReadyChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            resourceRequest
                .map { request ->
                    val plan = ProcessingCapabilityPlanner.plan(request)
                    com.transvibe.app.domain.model.AiCapability.TARGET_LANGUAGE_TRANSLATION in (plan.required + plan.conditional)
                }
                .distinctUntilChanged()
                .collect { required ->
                    if (required) {
                        translationPacks.isReady(LanguageCode.of(resourceRequest.value.targetLanguage))
                    }
                }
        }
    }

    fun selectVideo(uri: Uri) { _selectedVideo.value = videoSelection.select(uri.toString()) }

    fun requestProcessing() {
        if (_preflightBusy.value) return
        _preflightBusy.value = true
        viewModelScope.launch {
            try {
                val error = preflight.validate(_selectedVideo.value, models.selectedModel(), resourceRequest.value)
                if (error != null) _message.value = error.userMessage else processingReadyChannel.send(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                _preflightBusy.value = false
            }
        }
    }

    fun startProcessing(onStarted: () -> Unit = {}) {
        val video = _selectedVideo.value ?: return
        val modelId = selectedModelId.value
        val prefs = preferences.value
        viewModelScope.launch {
            val result = processingTasks.start(video, modelId, prefs)
            if (result.isSuccess) onStarted() else _message.value = "تعذر بدء مهمة المعالجة. حاول مرة أخرى."
        }
    }

    fun dismissMessage() { _message.value = null }

    private fun requestFor(prefs: AppPreferences) = ProcessingResourceRequest(
        sourceLanguage = prefs.videoSpeechLanguage,
        targetLanguage = prefs.subtitleTargetLanguage,
        vadRequested = true
    )
}
