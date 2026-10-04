package com.transvibe.app.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode
import kotlinx.coroutines.tasks.await

class MlKitTranslationEngine(
    override val sourceLanguage: LanguageCode,
    override val targetLanguage: LanguageCode
) : TranslationEngine {

    private val sourceMlKitLanguage = resolveMlKitLanguage(sourceLanguage)
    private val targetMlKitLanguage = resolveMlKitLanguage(targetLanguage)
    private val sourceModel = sourceMlKitLanguage.takeUnless { it == TranslateLanguage.ENGLISH }
        ?.let { TranslateRemoteModel.Builder(it).build() }
    private val targetModel = targetMlKitLanguage.takeUnless { it == TranslateLanguage.ENGLISH }
        ?.let { TranslateRemoteModel.Builder(it).build() }
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceMlKitLanguage)
            .setTargetLanguage(targetMlKitLanguage)
            .build()
    )

    override suspend fun isReady(): Boolean {
        val manager = RemoteModelManager.getInstance()
        val sourceReady = sourceModel?.let { manager.isModelDownloaded(it).await() } ?: true
        val targetReady = targetModel?.let { manager.isModelDownloaded(it).await() } ?: true
        return sourceReady && targetReady
    }

    override suspend fun prepare(policy: DownloadPolicy) {
        if (policy == DownloadPolicy.REQUIRE_LOCAL_ONLY) return
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
    }

    override suspend fun translate(text: String): String = if (text.isBlank()) text else translator.translate(text).await()

    override fun close() = translator.close()

    private fun resolveMlKitLanguage(languageCode: LanguageCode): String {
        require(languageCode != LanguageCode.AUTO) { "AUTO is not a concrete translation language" }
        return TranslateLanguage.fromLanguageTag(languageCode.value)
            ?: throw IllegalArgumentException("ML Kit does not support language ${languageCode.value}")
    }
}
