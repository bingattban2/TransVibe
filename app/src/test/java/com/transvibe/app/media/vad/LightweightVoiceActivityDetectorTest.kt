package com.transvibe.app.media.vad

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class LightweightVoiceActivityDetectorTest {
    private val vad = LightweightVoiceActivityDetector()
    private val rate = 16_000

    @Test
    fun silence_isRejected() = runBlocking {
        assertTrue(vad.detectSpeechRegions(FloatArray(rate * 2), rate).isEmpty())
    }

    @Test
    fun quietSpeech_isDetected() = runBlocking {
        val samples = syntheticSpeech(seconds = 2.0, amplitude = 0.010)
        assertFalse(vad.detectSpeechRegions(samples, rate).isEmpty())
    }

    @Test
    fun music_isRejected() = runBlocking {
        val samples = sine(seconds = 2.0, hz = 440.0, amplitude = 0.08)
        assertTrue(vad.detectSpeechRegions(samples, rate).isEmpty())
    }

    @Test
    fun backgroundNoise_isRejected() = runBlocking {
        val random = Random(7)
        val samples = FloatArray(rate * 2) { ((random.nextDouble() * 2.0 - 1.0) * 0.025).toFloat() }
        assertTrue(vad.detectSpeechRegions(samples, rate).isEmpty())
    }

    @Test
    fun speech_isDetected() = runBlocking {
        val samples = syntheticSpeech(seconds = 2.0, amplitude = 0.12)
        assertFalse(vad.detectSpeechRegions(samples, rate).isEmpty())
    }

    @Test
    fun longPause_splitsSpeechIntoRegions() = runBlocking {
        val speechA = syntheticSpeech(seconds = 0.7, amplitude = 0.10)
        val pause = FloatArray(rate)
        val speechB = syntheticSpeech(seconds = 0.7, amplitude = 0.10)
        val samples = speechA + pause + speechB
        val regions = vad.detectSpeechRegions(samples, rate)
        assertTrue("Expected distinct speech regions around a long pause", regions.size >= 2)
    }

    private fun syntheticSpeech(seconds: Double, amplitude: Double): FloatArray {
        val count = (seconds * rate).toInt()
        return FloatArray(count) { i ->
            val t = i.toDouble() / rate
            val syllableEnvelope = 0.20 + 0.80 * kotlin.math.abs(sin(2.0 * PI * 3.2 * t))
            val voiced = sin(2.0 * PI * 165.0 * t) +
                0.55 * sin(2.0 * PI * 330.0 * t) +
                0.25 * sin(2.0 * PI * 660.0 * t)
            val transient = if ((i / 320) % 7 == 0) 0.45 * sin(2.0 * PI * 1_900.0 * t) else 0.0
            (amplitude * syllableEnvelope * (voiced + transient)).toFloat()
        }
    }

    private fun sine(seconds: Double, hz: Double, amplitude: Double): FloatArray {
        val count = (seconds * rate).toInt()
        return FloatArray(count) { i ->
            (amplitude * sin(2.0 * PI * hz * i / rate)).toFloat()
        }
    }
}
