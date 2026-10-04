package com.transvibe.app.domain.runtime

import com.transvibe.app.domain.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingRuntimeTest {
    @Test
    fun balancedNeverConsumesEveryCoreOnTypicalPhone() {
        val tuning = ProcessingRuntime.tuning(ModelCatalog.recommended, ProcessingProfile.BALANCED, processors = 8)
        assertTrue(tuning.whisperThreads in 1..4)
        assertTrue(tuning.whisperThreads < 8)
        assertEquals(30, tuning.audioChunkSeconds)
        assertEquals(1, tuning.channelCapacity)
    }

    @Test
    fun lowCoreDeviceStillGetsOneInferenceThread() {
        val tuning = ProcessingRuntime.tuning(ModelCatalog.recommended, ProcessingProfile.BALANCED, processors = 1)
        assertEquals(1, tuning.whisperThreads)
    }

    @Test
    fun thermalConstraintIsMoreConservativeThanFast() {
        val fast = ProcessingRuntime.tuning(ModelCatalog.recommended, ProcessingProfile.FAST, processors = 8)
        val constrained = ProcessingRuntime.tuning(
            ModelCatalog.recommended,
            ProcessingProfile.FAST,
            processors = 8,
            thermallyConstrained = true
        )
        assertTrue(constrained.whisperThreads <= fast.whisperThreads)
        assertTrue(constrained.audioChunkSeconds < fast.audioChunkSeconds)
        assertEquals(0, constrained.channelCapacity)
    }

    @Test
    fun fastAndBalancedDoNotRequestOriginalLanguageSecondPass() {
        assertFalse(ProcessingProfile.FAST.preserveOriginalTranscript)
        assertFalse(ProcessingProfile.BALANCED.preserveOriginalTranscript)
        assertEquals(1, ProcessingProfile.FAST.maxForeignLanguageWhisperPasses)
        assertEquals(1, ProcessingProfile.BALANCED.maxForeignLanguageWhisperPasses)
    }

    @Test
    fun preserveOriginalMakesAdditionalInferenceCostExplicit() {
        assertTrue(ProcessingProfile.PRESERVE_ORIGINAL.preserveOriginalTranscript)
        assertEquals(2, ProcessingProfile.PRESERVE_ORIGINAL.maxForeignLanguageWhisperPasses)
    }

    @Test
    fun legacyPowerSavePreferenceMigratesToBalanced() {
        assertEquals(ProcessingProfile.BALANCED, ProcessingProfile.fromPersisted("POWER_SAVE"))
    }
}
