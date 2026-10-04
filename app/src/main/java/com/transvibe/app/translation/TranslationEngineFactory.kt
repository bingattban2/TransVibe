package com.transvibe.app.translation

import com.transvibe.app.domain.model.LanguageCode

interface TranslationEngineFactory {
    fun create(
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ): TranslationEngine
}

class DefaultTranslationEngineFactory : TranslationEngineFactory {
    override fun create(
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ): TranslationEngine = MlKitTranslationEngine(sourceLanguage, targetLanguage)
}
