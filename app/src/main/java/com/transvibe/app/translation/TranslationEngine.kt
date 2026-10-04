package com.transvibe.app.translation

import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode

interface TranslationEngine : AutoCloseable {
    val sourceLanguage: LanguageCode
    val targetLanguage: LanguageCode

    suspend fun isReady(): Boolean
    suspend fun prepare(policy: DownloadPolicy = DownloadPolicy.IF_NEEDED)
    suspend fun translate(text: String): String
    override fun close()
}
