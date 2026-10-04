package com.transvibe.app.data.model

import android.net.Uri
import com.transvibe.app.data.download.ModelDownloadManager
import com.transvibe.app.domain.model.AiResourceSnapshot
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.ModelCatalog
import com.transvibe.app.domain.model.StartupState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ModelManagementRepository(
    private val resources: AiResourceManager,
    private val downloads: ModelDownloadManager
) : ModelRepository {
    override val resourceSnapshot: StateFlow<AiResourceSnapshot> = resources.snapshot
    override val startupState: StateFlow<StartupState> = resources.startupState
    private val _selectedModelId = MutableStateFlow(resources.selectedModelId)
    override val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    override fun selectModel(id: String) {
        resources.setSelectedModel(id)
        _selectedModelId.value = id
    }

    override fun selectedModel(): WhisperModel = ModelCatalog.byId(_selectedModelId.value) ?: ModelCatalog.recommended
    override fun downloadState(model: WhisperModel): StateFlow<DownloadProgress> = downloads.state(model)
    override fun download(model: WhisperModel) = downloads.download(model)
    override fun retry(model: WhisperModel) = downloads.retry(model)
    override fun importModel(model: WhisperModel, sourceUri: String) = downloads.importModel(model, Uri.parse(sourceUri))
    override fun pause(model: WhisperModel) = downloads.pause(model)
    override fun cancel(model: WhisperModel) = downloads.cancel(model)
    override fun delete(model: WhisperModel) = downloads.delete(model)
    override suspend fun refreshAll() = resources.refreshAll()
}
