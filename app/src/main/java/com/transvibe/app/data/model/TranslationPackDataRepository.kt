package com.transvibe.app.data.model

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.transvibe.app.data.network.NetworkMonitor
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.TranslationPackInfo
import com.transvibe.app.domain.model.TranslationPackStorage
import com.transvibe.app.domain.model.TranslationResourceState
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TranslationPackDataRepository(
    private val network: NetworkMonitor,
    settings: SettingsRepository,
    scope: CoroutineScope
) : TranslationPackRepository {
    private val manager = RemoteModelManager.getInstance()
    private val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        scope,
        SharingStarted.Eagerly,
        AppPreferences()
    )
    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _installedPacks = MutableStateFlow<List<TranslationPackInfo>>(emptyList())
    override val installedPacks: StateFlow<List<TranslationPackInfo>> = _installedPacks.asStateFlow()
    private val _storage = MutableStateFlow(TranslationPackStorage())
    override val storage: StateFlow<TranslationPackStorage> = _storage.asStateFlow()
    private val statuses = mutableMapOf<LanguageCode, MutableStateFlow<TranslationResourceState>>()

    init {
        scope.launch {
            try {
                refreshInstalled()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Keep startup non-fatal; explicit refresh/download calls surface their own failures.
            }
        }
    }

    override fun status(language: LanguageCode): StateFlow<TranslationResourceState> =
        mutableStatus(language).asStateFlow()

    override suspend fun refreshInstalled(): List<TranslationPackInfo> {
        val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        val installed = downloaded.mapNotNull { model ->
            val tag = model.language
            runCatching { LanguageCode.of(tag) }.getOrNull()?.let { language ->
                mutableStatus(language).value = TranslationResourceState.READY
                TranslationPackInfo(language, TranslationResourceState.READY, APPROXIMATE_PACK_BYTES)
            }
        }.sortedBy { it.language.value }

        val installedCodes = installed.map { it.language }.toSet()
        statuses.forEach { (language, state) ->
            if (language != LanguageCode.ENGLISH && language !in installedCodes && state.value != TranslationResourceState.DOWNLOADING) {
                state.value = TranslationResourceState.MISSING
            }
        }
        _installedPacks.value = installed
        _storage.value = TranslationPackStorage(
            installedPackCount = installed.size,
            approximateBytes = installed.size * APPROXIMATE_PACK_BYTES
        )
        return installed
    }

    override suspend fun isReady(language: LanguageCode): Boolean {
        if (language == LanguageCode.ENGLISH) {
            mutableStatus(language).value = TranslationResourceState.READY
            return true
        }
        val ready = manager.isModelDownloaded(remoteModel(language)).await()
        mutableStatus(language).value = if (ready) TranslationResourceState.READY else TranslationResourceState.MISSING
        return ready
    }

    override suspend fun download(language: LanguageCode): Result<Unit> {
        require(language != LanguageCode.AUTO) { "AUTO is not a downloadable translation language" }
        val state = mutableStatus(language)
        if (language == LanguageCode.ENGLISH) {
            state.value = TranslationResourceState.READY
            return Result.success(Unit)
        }
        if (!network.isOnline()) {
            state.value = TranslationResourceState.ERROR
            return Result.failure(IllegalStateException("NETWORK_UNAVAILABLE"))
        }
        if (preferences.value.wifiOnlyTranslationDownloads && !network.isWifiConnected()) {
            state.value = TranslationResourceState.ERROR
            return Result.failure(IllegalStateException("WIFI_REQUIRED"))
        }
        if (isReady(language)) return Result.success(Unit)

        _busy.value = true
        state.value = TranslationResourceState.DOWNLOADING
        return try {
            val conditionsBuilder = DownloadConditions.Builder()
            if (preferences.value.wifiOnlyTranslationDownloads) conditionsBuilder.requireWifi()
            manager.download(remoteModel(language), conditionsBuilder.build()).await()
            state.value = TranslationResourceState.READY
            refreshInstalled()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            state.value = TranslationResourceState.ERROR
            Result.failure(t)
        } finally {
            _busy.value = false
        }
    }

    override suspend fun remove(language: LanguageCode): Result<Unit> {
        require(language != LanguageCode.AUTO) { "AUTO is not a downloadable translation language" }
        if (language == LanguageCode.ENGLISH) {
            mutableStatus(language).value = TranslationResourceState.READY
            return Result.failure(IllegalArgumentException("BUILT_IN_MODEL"))
        }
        _busy.value = true
        return try {
            manager.deleteDownloadedModel(remoteModel(language)).await()
            mutableStatus(language).value = TranslationResourceState.MISSING
            refreshInstalled()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            mutableStatus(language).value = TranslationResourceState.ERROR
            Result.failure(t)
        } finally {
            _busy.value = false
        }
    }

    private fun mutableStatus(language: LanguageCode): MutableStateFlow<TranslationResourceState> =
        statuses.getOrPut(language) {
            val installed = language == LanguageCode.ENGLISH || _installedPacks.value.any { it.language == language }
            MutableStateFlow(if (installed) TranslationResourceState.READY else TranslationResourceState.MISSING)
        }

    private fun remoteModel(language: LanguageCode): TranslateRemoteModel {
        val mlKitLanguage = TranslateLanguage.fromLanguageTag(language.value)
            ?: throw IllegalArgumentException("ML Kit does not support language ${language.value}")
        return TranslateRemoteModel.Builder(mlKitLanguage).build()
    }

    private companion object {
        // ML Kit translation packs are roughly tens of MB. The SDK does not expose exact on-disk bytes.
        const val APPROXIMATE_PACK_BYTES = 30L * 1024L * 1024L
    }
}
