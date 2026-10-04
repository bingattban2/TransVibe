package com.transvibe.app.domain.language

enum class SourceLanguageMode {
    MANUAL,
    AUTO_VIDEO,
    AUTO_ADAPTIVE
}

data class SourceLanguagePolicy(
    val mode: SourceLanguageMode,
    val manualLanguage: String? = null
) {
    init {
        if (mode == SourceLanguageMode.MANUAL) require(!manualLanguage.isNullOrBlank())
    }

    val requiresInitialDetection: Boolean get() = mode != SourceLanguageMode.MANUAL
    val allowsRedetection: Boolean get() = mode == SourceLanguageMode.AUTO_ADAPTIVE

    fun persistedValue(): String = when (mode) {
        SourceLanguageMode.MANUAL -> "manual:${manualLanguage!!.lowercase()}"
        SourceLanguageMode.AUTO_VIDEO -> "auto_video"
        SourceLanguageMode.AUTO_ADAPTIVE -> "auto_adaptive"
    }

    companion object {
        fun parse(value: String?): SourceLanguagePolicy {
            val normalized = value.orEmpty().trim().lowercase()
            return when {
                normalized == "auto_adaptive" -> SourceLanguagePolicy(SourceLanguageMode.AUTO_ADAPTIVE)
                normalized == "auto_video" || normalized == "auto" || normalized.isBlank() ->
                    SourceLanguagePolicy(SourceLanguageMode.AUTO_VIDEO)
                normalized.startsWith("manual:") -> {
                    val language = normalized.substringAfter(':').trim().ifBlank { "en" }
                    SourceLanguagePolicy(SourceLanguageMode.MANUAL, language)
                }
                else -> SourceLanguagePolicy(SourceLanguageMode.MANUAL, normalized)
            }
        }
    }
}

/**
 * Bounded quality monitor for AUTO_ADAPTIVE. A single poor region never triggers language
 * detection. Redetection is requested only after repeated weak recognition evidence, then a
 * cooldown prevents detection from running on every subsequent chunk.
 */
class AdaptiveLanguageMonitor(
    private val lowQualityStreakRequired: Int = 3,
    private val redetectionCooldownRegions: Int = 5
) {
    init {
        require(lowQualityStreakRequired >= 2)
        require(redetectionCooldownRegions >= 1)
    }

    private var lowQualityStreak = 0
    private var cooldownRemaining = 0

    fun record(quality: RecognitionQuality): Boolean {
        if (cooldownRemaining > 0) {
            cooldownRemaining--
            if (!quality.isLowConfidence) lowQualityStreak = 0
            return false
        }
        lowQualityStreak = if (quality.isLowConfidence) lowQualityStreak + 1 else 0
        if (lowQualityStreak < lowQualityStreakRequired) return false
        lowQualityStreak = 0
        cooldownRemaining = redetectionCooldownRegions
        return true
    }

    fun resetAfterLanguageChange() {
        lowQualityStreak = 0
        cooldownRemaining = redetectionCooldownRegions
    }
}

data class RecognitionQuality(
    val averageTokenProbability: Float?,
    val noSpeechProbability: Float?,
    val hasUsableText: Boolean,
    val suspiciousRepetition: Boolean
) {
    val isLowConfidence: Boolean
        get() = !hasUsableText ||
            suspiciousRepetition ||
            (averageTokenProbability != null && averageTokenProbability < 0.34f) ||
            (noSpeechProbability != null && noSpeechProbability > 0.68f)
}
