package com.transvibe.app.domain.model

@JvmInline
value class LanguageCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        val ARABIC = LanguageCode("ar")
        val ENGLISH = LanguageCode("en")
        val AUTO = LanguageCode("auto")

        fun of(raw: String): LanguageCode {
            val normalized = raw.trim().lowercase()
            require(normalized.isNotBlank()) { "Language code must not be blank" }
            return when (normalized) {
                ARABIC.value -> ARABIC
                ENGLISH.value -> ENGLISH
                AUTO.value -> AUTO
                else -> LanguageCode(normalized)
            }
        }
    }
}

data class LanguagePair(
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode
)

enum class DownloadPolicy {
    REQUIRE_LOCAL_ONLY,
    IF_NEEDED
}
