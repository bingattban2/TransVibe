package com.transvibe.app

import android.app.Application
import com.transvibe.app.data.download.ModelDownloadManager
import com.transvibe.app.data.media.VideoSelectionRepository
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.data.model.ModelManagementRepository
import com.transvibe.app.data.model.ModelStore
import com.transvibe.app.data.model.TranslationPackDataRepository
import com.transvibe.app.data.network.NetworkMonitor
import com.transvibe.app.data.persistence.AppPreferencesRepository
import com.transvibe.app.data.persistence.LegacySessionMigrator
import com.transvibe.app.data.persistence.RoomProcessingHistoryRepository
import com.transvibe.app.data.persistence.RoomProcessingRepository
import com.transvibe.app.data.persistence.TransVibeDatabase
import com.transvibe.app.data.player.PlayerRepository
import com.transvibe.app.data.processing.ProcessVideoPreflight
import com.transvibe.app.data.processing.ProcessingTaskController
import com.transvibe.app.data.storage.StorageRepository
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SessionRepository
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.media.vad.LightweightVoiceActivityDetector
import com.transvibe.app.nativebridge.whisper.NativeWhisperModelBinaryValidator
import com.transvibe.app.presentation.navigation.TransVibeViewModelFactory
import com.transvibe.app.service.ProcessingServiceRuntimeRegistry
import com.transvibe.app.service.SubtitleProcessingDependencies
import com.transvibe.app.service.SubtitleProcessingService
import com.transvibe.app.translation.DefaultTranslationEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application composition root.
 *
 * Owns application-scoped dependencies, constructs the object graph lazily, and injects
 * Android-owned components explicitly. Consumers never receive AppGraph itself.
 */
internal class AppGraph(private val app: Application) {
    private val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val modelBinaryValidator by lazy { NativeWhisperModelBinaryValidator() }
    private val modelStore by lazy { ModelStore(app, modelBinaryValidator) }
    private val networkMonitor by lazy { NetworkMonitor(app) }
    private val translationEngines by lazy { DefaultTranslationEngineFactory() }
    private val resources by lazy { AiResourceManager(app, modelStore, translationEngines, applicationScope) }
    private val mediaInspector by lazy { MediaSourceInspector(app) }
    private val voiceActivityDetector by lazy { LightweightVoiceActivityDetector() }
    private val processVideoPreflight by lazy { ProcessVideoPreflight(resources, mediaInspector) }
    private val downloads by lazy {
        ModelDownloadManager(app, modelStore, resources, networkMonitor, modelBinaryValidator, applicationScope)
    }
    private val modelRepository: ModelRepository by lazy { ModelManagementRepository(resources, downloads) }
    private val settingsRepository: SettingsRepository by lazy { AppPreferencesRepository(app) }
    private val translationPackRepository: TranslationPackRepository by lazy {
        TranslationPackDataRepository(networkMonitor, settingsRepository, applicationScope)
    }
    private val videoSelectionRepository by lazy { VideoSelectionRepository(app) }
    private val playerRepository by lazy { PlayerRepository(app) }
    private val storageRepository by lazy { StorageRepository(app) }
    private val database by lazy { TransVibeDatabase.create(app) }
    private val processingDao by lazy { database.processingDao() }
    private val legacySessionMigrator by lazy { LegacySessionMigrator(app, processingDao) }
    private val sessionRepository: SessionRepository by lazy {
        RoomProcessingHistoryRepository(processingDao, legacySessionMigrator)
    }
    private val processingRepository: ProcessingRepository by lazy {
        RoomProcessingRepository(processingDao, sessionRepository)
    }
    private val processingTaskController by lazy { ProcessingTaskController(app, processingRepository) }

    val viewModelFactory: TransVibeViewModelFactory by lazy {
        TransVibeViewModelFactory(
            modelRepository = modelRepository,
            processingRepository = processingRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            translationPackRepository = translationPackRepository,
            videoSelectionRepository = videoSelectionRepository,
            processVideoPreflight = processVideoPreflight,
            processingTaskController = processingTaskController,
            playerRepository = playerRepository,
            storageRepository = storageRepository
        )
    }

    init {
        applicationScope.launch {
            legacySessionMigrator.migrateOnce()
            processingRepository.reconcileRunningJobs(ProcessingServiceRuntimeRegistry::isActive)
        }
    }

    fun inject(service: SubtitleProcessingService) {
        service.inject(
            SubtitleProcessingDependencies(
                applicationScope = applicationScope,
                processingRepository = processingRepository,
                resources = resources,
                mediaInspector = mediaInspector,
                modelStore = modelStore,
                translationEngines = translationEngines,
                voiceActivityDetector = voiceActivityDetector
            )
        )
    }
}
