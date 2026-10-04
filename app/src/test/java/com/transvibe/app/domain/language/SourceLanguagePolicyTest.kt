package com.transvibe.app.domain.language

import org.junit.Assert.*
import org.junit.Test

class SourceLanguagePolicyTest {
    @Test fun legacyAutoMigratesToAutoVideo() {
        assertEquals(SourceLanguageMode.AUTO_VIDEO, SourceLanguagePolicy.parse("auto").mode)
    }

    @Test fun manualLanguageIsRetained() {
        val p = SourceLanguagePolicy.parse("manual:ar")
        assertEquals(SourceLanguageMode.MANUAL, p.mode)
        assertEquals("ar", p.manualLanguage)
    }

    @Test fun adaptiveRequiresRepeatedLowConfidence() {
        val monitor = AdaptiveLanguageMonitor(lowQualityStreakRequired = 3, redetectionCooldownRegions = 2)
        val weak = RecognitionQuality(0.2f, 0.2f, true, false)
        assertFalse(monitor.record(weak))
        assertFalse(monitor.record(weak))
        assertTrue(monitor.record(weak))
        assertFalse(monitor.record(weak))
    }

    @Test fun goodRecognitionResetsWeakEvidence() {
        val monitor = AdaptiveLanguageMonitor(lowQualityStreakRequired = 3, redetectionCooldownRegions = 2)
        val weak = RecognitionQuality(0.2f, 0.2f, true, false)
        val good = RecognitionQuality(0.8f, 0.1f, true, false)
        assertFalse(monitor.record(weak))
        assertFalse(monitor.record(weak))
        assertFalse(monitor.record(good))
        assertFalse(monitor.record(weak))
    }
}
