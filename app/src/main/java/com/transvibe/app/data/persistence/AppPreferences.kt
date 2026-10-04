package com.transvibe.app.data.persistence

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.transVibePreferences by preferencesDataStore(name = "transvibe_preferences")

class AppPreferencesRepository(private val context: Context) : SettingsRepository {
    private object Keys {
        val keepScreenOn = booleanPreferencesKey("keep_screen_on_playback")
        val exportFormat = stringPreferencesKey("preferred_export_format")
        val processingProfile = stringPreferencesKey("processing_profile")
        val applicationLanguage = stringPreferencesKey("application_language")
        val videoSpeechLanguage = stringPreferencesKey("video_speech_language")
        val subtitleTargetLanguage = stringPreferencesKey("subtitle_target_language")
        val wifiOnlyTranslationDownloads = booleanPreferencesKey("wifi_only_translation_downloads")
        val legacyAutoLanguage = booleanPreferencesKey("automatic_language_detection")
        val legacyManualLanguage = stringPreferencesKey("manual_language_code")
        val subtitleScale = floatPreferencesKey("subtitle_text_scale")
        val themeMode = stringPreferencesKey("theme_mode")
    }

    override val preferences: Flow<AppPreferences> = context.transVibePreferences.data.map { p ->
        val migratedSpeechLanguage = p[Keys.videoSpeechLanguage] ?: run {
            if (p[Keys.legacyAutoLanguage] != false) "auto"
            else p[Keys.legacyManualLanguage]?.takeIf { it == "ar" || it == "en" } ?: "en"
        }
        AppPreferences(
            keepScreenOnDuringPlayback = p[Keys.keepScreenOn] ?: false,
            preferredExportFormat = p[Keys.exportFormat]?.takeIf { it == "srt" || it == "vtt" } ?: "srt",
            processingProfile = when (p[Keys.processingProfile]) {
                "FAST" -> "FAST"
                "PRESERVE_ORIGINAL" -> "PRESERVE_ORIGINAL"
                // Migrate the former UI power-save choice to BALANCED. Thermal protection is now automatic.
                else -> "BALANCED"
            },
            applicationLanguage = p[Keys.applicationLanguage]?.takeIf { it in setOf("system", "ar", "en") } ?: "system",
            videoSpeechLanguage = when {
                migratedSpeechLanguage in setOf("auto_video", "auto_adaptive") -> migratedSpeechLanguage
                migratedSpeechLanguage.startsWith("manual:") -> migratedSpeechLanguage
                migratedSpeechLanguage == "auto" -> "auto_video"
                migratedSpeechLanguage in setOf("ar", "en") -> "manual:$migratedSpeechLanguage"
                else -> "auto_video"
            },
            subtitleTargetLanguage = p[Keys.subtitleTargetLanguage]?.takeIf { it in setOf("ar", "en") } ?: "ar",
            wifiOnlyTranslationDownloads = p[Keys.wifiOnlyTranslationDownloads] ?: true,
            subtitleTextScale = (p[Keys.subtitleScale] ?: 1.0f).coerceIn(0.85f, 1.45f),
            themeMode = p[Keys.themeMode]?.takeIf { it in setOf("system", "light", "dark") } ?: "system"
        )
    }

    override suspend fun setKeepScreenOnDuringPlayback(enabled: Boolean) {
        context.transVibePreferences.edit { it[Keys.keepScreenOn] = enabled }
    }

    override suspend fun setPreferredExportFormat(format: String) {
        require(format == "srt" || format == "vtt")
        context.transVibePreferences.edit { it[Keys.exportFormat] = format }
    }

    override suspend fun setProcessingProfile(profile: String) {
        require(profile in setOf("FAST", "BALANCED", "PRESERVE_ORIGINAL"))
        context.transVibePreferences.edit { it[Keys.processingProfile] = profile }
    }

    override suspend fun setApplicationLanguage(code: String) {
        require(code in setOf("system", "ar", "en"))
        context.transVibePreferences.edit { it[Keys.applicationLanguage] = code }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (code == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
        }
    }

    override suspend fun setVideoSpeechLanguage(code: String) {
        require(code in setOf("auto_video", "auto_adaptive", "manual:ar", "manual:en"))
        context.transVibePreferences.edit { it[Keys.videoSpeechLanguage] = code }
    }

    override suspend fun setSubtitleTargetLanguage(code: String) {
        require(code in setOf("ar", "en"))
        context.transVibePreferences.edit { it[Keys.subtitleTargetLanguage] = code }
    }

    override suspend fun setWifiOnlyTranslationDownloads(enabled: Boolean) {
        context.transVibePreferences.edit { it[Keys.wifiOnlyTranslationDownloads] = enabled }
    }

    override suspend fun setSubtitleTextScale(scale: Float) {
        context.transVibePreferences.edit { it[Keys.subtitleScale] = scale.coerceIn(0.85f, 1.45f) }
    }

    override suspend fun setThemeMode(mode: String) {
        require(mode in setOf("system", "light", "dark"))
        context.transVibePreferences.edit { it[Keys.themeMode] = mode }
    }
}
