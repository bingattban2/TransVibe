package com.transvibe.app.domain

import com.transvibe.app.domain.model.AiCapability
import com.transvibe.app.domain.model.AiCapabilitySnapshot
import com.transvibe.app.domain.model.AiCapabilityState
import com.transvibe.app.domain.model.ProcessingCapabilityPlanner
import com.transvibe.app.domain.model.ProcessingResourceReadiness
import com.transvibe.app.domain.model.ProcessingResourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingCapabilityPlannerTest {
    @Test
    fun arabicToArabic_requiresSpeechRecognitionOnly() {
        val plan = ProcessingCapabilityPlanner.plan(ProcessingResourceRequest("ar", "ar"))
        assertEquals(setOf(AiCapability.SPEECH_RECOGNITION), plan.required)
        assertTrue(plan.conditional.isEmpty())
    }

    @Test
    fun englishToArabic_requiresRecognitionAndTargetTranslation() {
        val plan = ProcessingCapabilityPlanner.plan(ProcessingResourceRequest("en", "ar"))
        assertEquals(
            setOf(AiCapability.SPEECH_RECOGNITION, AiCapability.TARGET_LANGUAGE_TRANSLATION),
            plan.required
        )
    }

    @Test
    fun otherLanguageToArabic_requiresSpeechToEnglishAndTargetTranslation() {
        val plan = ProcessingCapabilityPlanner.plan(ProcessingResourceRequest("fr", "ar"))
        assertEquals(
            setOf(
                AiCapability.SPEECH_RECOGNITION,
                AiCapability.SPEECH_TO_ENGLISH_TRANSLATION,
                AiCapability.TARGET_LANGUAGE_TRANSLATION
            ),
            plan.required
        )
    }

    @Test
    fun automaticLanguage_keepsTranslationCapabilitiesConditionalUntilDetection() {
        val plan = ProcessingCapabilityPlanner.plan(ProcessingResourceRequest("auto", "ar"))
        assertEquals(setOf(AiCapability.SPEECH_RECOGNITION), plan.required)
        assertEquals(
            setOf(AiCapability.SPEECH_TO_ENGLISH_TRANSLATION, AiCapability.TARGET_LANGUAGE_TRANSLATION),
            plan.conditional
        )
    }

    @Test
    fun requestedVad_isAnExplicitRequiredCapability() {
        val plan = ProcessingCapabilityPlanner.plan(ProcessingResourceRequest("ar", "ar", vadRequested = true))
        assertEquals(setOf(AiCapability.SPEECH_RECOGNITION, AiCapability.VAD), plan.required)
    }

    @Test
    fun unrelatedMissingVad_doesNotBlockPipelineThatDidNotRequestVad() {
        val capabilities = AiCapabilitySnapshot(
            speechRecognition = AiCapabilityState.Ready,
            speechToEnglishTranslation = AiCapabilityState.Ready,
            targetLanguageTranslation = AiCapabilityState.Ready,
            vad = AiCapabilityState.Missing("not installed")
        )
        val readiness = ProcessingCapabilityPlanner.readiness(
            ProcessingResourceRequest("ar", "ar", vadRequested = false),
            capabilities
        )
        assertTrue(readiness is ProcessingResourceReadiness.Ready)
    }
}
