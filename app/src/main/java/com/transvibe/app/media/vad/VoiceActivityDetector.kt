package com.transvibe.app.media.vad

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** A speech interval inside one PCM chunk. End index is exclusive. */
data class SpeechRegion(
    val startSample: Int,
    val endSample: Int
) {
    init {
        require(startSample >= 0)
        require(endSample > startSample)
    }
}

/**
 * UI-independent local VAD contract.
 *
 * Implementations inspect bounded PCM chunks and return only the intervals worth sending to
 * speech recognition. Implementations must cooperate with coroutine cancellation.
 */
interface VoiceActivityDetector {
    suspend fun detectSpeechRegions(samples: FloatArray, sampleRateHz: Int): List<SpeechRegion>
}

/**
 * Lightweight feature-based VAD intended for the 16 kHz mono PCM chunks produced by AudioDecoder.
 *
 * It uses short-frame energy, zero-crossing rate, crest factor and first-difference energy. This is
 * deliberately local and allocation-bounded: one Boolean decision array per already-bounded decoder
 * chunk, no model, no network, no UI dependency and no unbounded queue.
 */
class LightweightVoiceActivityDetector(
    private val frameMs: Int = 20,
    private val minimumSpeechMs: Int = 120,
    private val hangoverMs: Int = 180,
    private val paddingMs: Int = 120,
    private val minimumRms: Double = 0.0015,
    private val minimumCrestFactor: Double = 1.65,
    private val minZeroCrossingRate: Double = 0.008,
    private val maxZeroCrossingRate: Double = 0.38,
    private val minDifferenceRatio: Double = 0.012,
    private val maxDifferenceRatio: Double = 0.75
) : VoiceActivityDetector {

    override suspend fun detectSpeechRegions(samples: FloatArray, sampleRateHz: Int): List<SpeechRegion> {
        require(sampleRateHz in 8_000..48_000) { "Unsupported VAD sample rate: $sampleRateHz" }
        if (samples.isEmpty()) return emptyList()

        val frameSamples = (sampleRateHz * frameMs / 1_000).coerceAtLeast(1)
        val frameCount = ceil(samples.size / frameSamples.toDouble()).toInt()
        val speechFrames = BooleanArray(frameCount)

        for (frame in 0 until frameCount) {
            if (frame and 0x0F == 0) currentCoroutineContext().ensureActive()
            val start = frame * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            speechFrames[frame] = isSpeechLikeFrame(samples, start, end)
        }

        // Remove isolated feature hits while retaining quiet speech that persists across frames.
        val smoothed = BooleanArray(frameCount)
        for (frame in 0 until frameCount) {
            val from = maxOf(0, frame - 2)
            val to = minOf(frameCount - 1, frame + 2)
            var votes = 0
            for (i in from..to) if (speechFrames[i]) votes++
            smoothed[frame] = votes >= 2
        }

        val minimumFrames = ceil(minimumSpeechMs / frameMs.toDouble()).toInt().coerceAtLeast(1)
        val hangoverFrames = ceil(hangoverMs / frameMs.toDouble()).toInt().coerceAtLeast(0)
        val paddingSamples = sampleRateHz * paddingMs / 1_000
        val regions = ArrayList<SpeechRegion>()

        var runStart = -1
        var lastSpeech = -1
        var frame = 0
        while (frame < frameCount) {
            if (frame and 0x1F == 0) currentCoroutineContext().ensureActive()
            if (smoothed[frame]) {
                if (runStart < 0) runStart = frame
                lastSpeech = frame
            }
            val shouldClose = runStart >= 0 && (
                frame == frameCount - 1 || (lastSpeech >= 0 && frame - lastSpeech > hangoverFrames)
            )
            if (shouldClose) {
                val speechFrameCount = lastSpeech - runStart + 1
                if (speechFrameCount >= minimumFrames) {
                    val rawStart = runStart * frameSamples
                    val rawEnd = minOf(samples.size, (lastSpeech + 1) * frameSamples)
                    val paddedStart = maxOf(0, rawStart - paddingSamples)
                    val paddedEnd = minOf(samples.size, rawEnd + paddingSamples)
                    appendMerged(regions, SpeechRegion(paddedStart, paddedEnd), sampleRateHz)
                }
                runStart = -1
                lastSpeech = -1
            }
            frame++
        }
        return regions
    }

    private fun isSpeechLikeFrame(samples: FloatArray, start: Int, end: Int): Boolean {
        val length = end - start
        if (length < 2) return false

        var sumSquares = 0.0
        var diffSquares = 0.0
        var peak = 0.0
        var zeroCrossings = 0
        var previous = samples[start].toDouble()
        peak = abs(previous)
        sumSquares += previous * previous

        for (i in start + 1 until end) {
            val value = samples[i].toDouble()
            sumSquares += value * value
            peak = maxOf(peak, abs(value))
            val diff = value - previous
            diffSquares += diff * diff
            if ((value >= 0.0) != (previous >= 0.0)) zeroCrossings++
            previous = value
        }

        val rms = sqrt(sumSquares / length)
        if (rms < minimumRms) return false

        val crestFactor = peak / rms.coerceAtLeast(1e-9)
        val zcr = zeroCrossings.toDouble() / (length - 1).coerceAtLeast(1)
        val differenceRms = sqrt(diffSquares / (length - 1).coerceAtLeast(1))
        val differenceRatio = differenceRms / rms.coerceAtLeast(1e-9)

        return crestFactor >= minimumCrestFactor &&
            zcr in minZeroCrossingRate..maxZeroCrossingRate &&
            differenceRatio in minDifferenceRatio..maxDifferenceRatio
    }

    private fun appendMerged(regions: MutableList<SpeechRegion>, next: SpeechRegion, sampleRateHz: Int) {
        val mergeGapSamples = sampleRateHz * hangoverMs / 1_000
        val previous = regions.lastOrNull()
        if (previous != null && next.startSample - previous.endSample <= mergeGapSamples) {
            regions[regions.lastIndex] = SpeechRegion(previous.startSample, maxOf(previous.endSample, next.endSample))
        } else {
            regions += next
        }
    }
}
