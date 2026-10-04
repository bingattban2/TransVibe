package com.transvibe.app.presentation.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModelsViewModel(
    private val repository: ModelRepository,
    private val translationPacks: TranslationPackRepository
) : ViewModel() {
    val resources = repository.resourceSnapshot
    val startupState = repository.startupState
    val selectedModelId = repository.selectedModelId
    val translationBusy = translationPacks.busy
    val installedTranslationPacks = translationPacks.installedPacks
    val translationPackStorage = translationPacks.storage
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectModel(id: String) = repository.selectModel(id)
    fun downloadState(model: WhisperModel): StateFlow<DownloadProgress> = repository.downloadState(model)
    fun download(model: WhisperModel) = repository.download(model)
    fun retryDownload(model: WhisperModel) = repository.retry(model)
    fun importModel(model: WhisperModel, uri: Uri) = repository.importModel(model, uri.toString())
    fun pause(model: WhisperModel) = repository.pause(model)
    fun cancelDownload(model: WhisperModel) = repository.cancel(model)
    fun delete(model: WhisperModel) = repository.delete(model)
    fun translationStatus(language: LanguageCode) = translationPacks.status(language)

    fun refreshResources() = viewModelScope.launch {
        repository.refreshAll()
        translationPacks.refreshInstalled()
    }

    fun downloadTranslationPack(language: LanguageCode) = viewModelScope.launch {
        translationPacks.download(language)
    }

    fun removeTranslationPack(language: LanguageCode) = viewModelScope.launch {
        translationPacks.remove(language)
    }

    fun dismissMessage() { _message.value = null }
}
