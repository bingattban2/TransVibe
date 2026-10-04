package com.transvibe.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: com.transvibe.app.domain.repository.SettingsRepository,
    private val models: com.transvibe.app.domain.repository.ModelRepository,
    private val storage: com.transvibe.app.domain.repository.StorageInspector,
    val appVersion: String
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )
    val selectedModelId = models.selectedModelId
    private val _storageInfo = MutableStateFlow(StorageInfo())
    val storageInfo: StateFlow<StorageInfo> = _storageInfo.asStateFlow()

    fun selectModel(id: String) = models.selectModel(id)
    fun setKeepScreenOnDuringPlayback(v: Boolean) = viewModelScope.launch { settings.setKeepScreenOnDuringPlayback(v) }
    fun setPreferredExportFormat(v: String) = viewModelScope.launch { settings.setPreferredExportFormat(v) }
    fun setProcessingProfile(v: String) = viewModelScope.launch { settings.setProcessingProfile(v) }
    fun setApplicationLanguage(v: String) = viewModelScope.launch { settings.setApplicationLanguage(v) }
    fun setVideoSpeechLanguage(v: String) = viewModelScope.launch { settings.setVideoSpeechLanguage(v) }
    fun setSubtitleTargetLanguage(v: String) = viewModelScope.launch { settings.setSubtitleTargetLanguage(v) }
    fun setWifiOnlyTranslationDownloads(v: Boolean) = viewModelScope.launch { settings.setWifiOnlyTranslationDownloads(v) }
    fun setSubtitleTextScale(v: Float) = viewModelScope.launch { settings.setSubtitleTextScale(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { settings.setThemeMode(v) }
    fun refreshStorageInfo() = viewModelScope.launch(Dispatchers.IO) { _storageInfo.value = storage.inspect() }
}
