package com.transvibe.app.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.transvibe.app.BuildConfig
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SessionRepository
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import com.transvibe.app.domain.repository.VideoSelectionGateway
import com.transvibe.app.domain.repository.PlayerGateway
import com.transvibe.app.domain.repository.ProcessingPreflight
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import com.transvibe.app.domain.repository.StorageInspector
import com.transvibe.app.presentation.history.HistoryViewModel
import com.transvibe.app.presentation.home.HomeViewModel
import com.transvibe.app.presentation.models.ModelsViewModel
import com.transvibe.app.presentation.player.PlayerViewModel
import com.transvibe.app.presentation.processing.ProcessingViewModel
import com.transvibe.app.presentation.settings.SettingsViewModel

class TransVibeViewModelFactory(
    private val modelRepository: ModelRepository,
    private val processingRepository: ProcessingRepository,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val translationPackRepository: TranslationPackRepository,
    private val videoSelectionRepository: VideoSelectionGateway,
    private val processVideoPreflight: ProcessingPreflight,
    private val processingTaskController: ProcessingTaskGateway,
    private val playerRepository: PlayerGateway,
    private val storageRepository: StorageInspector
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
            modelRepository,
            settingsRepository,
            translationPackRepository,
            videoSelectionRepository,
            processVideoPreflight,
            processingTaskController
        )
        modelClass.isAssignableFrom(ProcessingViewModel::class.java) -> ProcessingViewModel(processingRepository, processingTaskController)
        modelClass.isAssignableFrom(ModelsViewModel::class.java) -> ModelsViewModel(modelRepository, translationPackRepository)
        modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(sessionRepository, processingRepository)
        modelClass.isAssignableFrom(PlayerViewModel::class.java) -> PlayerViewModel(processingRepository, settingsRepository, playerRepository)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(settingsRepository, modelRepository, storageRepository, BuildConfig.VERSION_NAME)
        else -> error("Unknown ViewModel ${modelClass.name}")
    } as T
}
