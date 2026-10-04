package com.transvibe.app.media.decoder

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class AudioDecoder(private val context: Context) {
    data class AudioChunk(val samples: FloatArray, val startMs: Long, val endMs: Long)

    sealed class AudioPipelineException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class OpenSource(cause: Throwable? = null) : AudioPipelineException("تعذر فتح ملف الفيديو.", cause)
        class NoAudioTrack : AudioPipelineException("الفيديو لا يحتوي على مسار صوتي.")
        class UnknownAudioFormat : AudioPipelineException("صيغة مسار الصوت غير معروفة.")
        class UnsupportedCodec(val mime: String, cause: Throwable? = null) :
            AudioPipelineException("ترميز الصوت غير مدعوم على هذا الجهاز: $mime", cause)
        class InvalidDecodedFormat(message: String) : AudioPipelineException(message)
        class UnsupportedPcm(val encoding: Int) : AudioPipelineException("صيغة PCM الناتجة غير مدعومة: $encoding")
        class DecodeFailure(cause: Throwable) : AudioPipelineException("تعذر فك ترميز مسار الصوت.", cause)
    }

    suspend fun decodeChunks(
        uri: Uri,
        chunkSeconds: Int = 30,
        overlapMs: Int = 500,
        startMs: Long = 0L,
        onProgress: suspend (processedUs: Long, totalUs: Long) -> Unit,
        onChunk: suspend (AudioChunk) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(chunkSeconds in 10..60) { "طول مقطع الصوت غير صالح" }
        require(overlapMs in 0..2_000) { "قيمة تداخل المقاطع غير صالحة" }
        require(startMs >= 0L) { "بداية الاستئناف غير صالحة" }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val length = afd.length
                    if (length >= 0L) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, length)
                    else extractor.setDataSource(afd.fileDescriptor)
                } ?: throw AudioPipelineException.OpenSource()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: AudioPipelineException) {
                throw e
            } catch (t: Throwable) {
                throw AudioPipelineException.OpenSource(t)
            }

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioPipelineException.NoAudioTrack()

            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw AudioPipelineException.UnknownAudioFormat()
            val requestedStartUs = startMs * 1_000L
            if (requestedStartUs > 0L) {
                extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val totalUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            } else -1L

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw AudioPipelineException.UnsupportedCodec(mime, t)
            }
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw AudioPipelineException.UnsupportedCodec(mime, t)
            }

            var sampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 0)
            var pcmEncoding = inputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            validateDecodedFormat(sampleRate, channels)

            val resampler = StreamingSincResampler(TARGET_RATE)
            val chunkSamples = chunkSeconds * TARGET_RATE
            val overlapSamples = overlapMs * TARGET_RATE / 1_000
            val advanceSamples = max(1, chunkSamples - overlapSamples)
            val acc = FloatAccumulator(chunkSamples + overlapSamples + TARGET_RATE * 2)
            var timelineSample = startMs * TARGET_RATE / 1_000L
            var inputDone = false
            var outputDone = false
            var lastPts = requestedStartUs
            val info = MediaCodec.BufferInfo()

            suspend fun emitReadyChunks() {
                while (acc.size >= chunkSamples) {
                    coroutineContext.ensureActive()
                    val samples = acc.copyPrefix(chunkSamples)
                    val startSample = timelineSample
                    val endSample = startSample + samples.size
                    try {
                        onChunk(AudioChunk(samples, samplesToMs(startSample), samplesToMs(endSample)))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        throw CallbackFailure(t)
                    }
                    acc.dropPrefix(advanceSamples)
                    timelineSample += advanceSamples
                }
            }

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw AudioPipelineException.DecodeFailure(IllegalStateException("MediaCodec input buffer unavailable"))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        validateDecodedFormat(sampleRate, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && (requestedStartUs == 0L || info.presentationTimeUs >= requestedStartUs)) {
                                val output = codec.getOutputBuffer(outputIndex)
                                    ?: throw AudioPipelineException.DecodeFailure(IllegalStateException("MediaCodec output buffer unavailable"))
                                val pcm = output.sliceRange(info.offset, info.size)
                                val mono = decodePcmToMonoFloat(pcm, pcmEncoding, channels)
                                val converted = resampler.process(mono, sampleRate)
                                acc.append(converted)
                                emitReadyChunks()
                                lastPts = max(lastPts, info.presentationTimeUs.coerceAtLeast(0L))
                                try {
                                    onProgress(lastPts, totalUs)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    throw CallbackFailure(t)
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            acc.append(resampler.finish())
            emitReadyChunks()
            if (acc.size > 0 && (timelineSample == 0L || acc.size > overlapSamples)) {
                val remaining = acc.copyPrefix(acc.size)
                val start = timelineSample
                try {
                    onChunk(AudioChunk(remaining, samplesToMs(start), samplesToMs(start + remaining.size)))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    throw CallbackFailure(t)
                }
                timelineSample += remaining.size
                acc.dropPrefix(acc.size)
            }
            try {
                onProgress(if (totalUs > 0L) totalUs else lastPts, totalUs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw CallbackFailure(t)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: CallbackFailure) {
            throw (e.cause ?: e)
        } catch (e: AudioPipelineException) {
            throw e
        } catch (e: MediaCodec.CodecException) {
            throw AudioPipelineException.DecodeFailure(e)
        } catch (t: Throwable) {
            throw AudioPipelineException.DecodeFailure(t)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun validateDecodedFormat(sampleRate: Int, channels: Int) {
        if (sampleRate !in 8_000..384_000) {
            throw AudioPipelineException.InvalidDecodedFormat("معدل عينة الصوت غير صالح: $sampleRate Hz")
        }
        if (channels !in 1..8) {
            throw AudioPipelineException.InvalidDecodedFormat("عدد قنوات الصوت غير مدعوم: $channels")
        }
    }

    private fun decodePcmToMonoFloat(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            PCM_24_BIT_PACKED -> 3
            PCM_32_BIT -> 4
            else -> throw AudioPipelineException.UnsupportedPcm(encoding)
        }
        val frameBytes = bytesPerSample * channels
        val frames = buffer.remaining() / frameBytes
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames)
        repeat(frames) { frame ->
            var mixed = 0.0
            repeat(channels) {
                mixed += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128.0
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768.0
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.toDouble().takeIf { it.isFinite() } ?: 0.0
                    PCM_24_BIT_PACKED -> {
                        val b0 = buffer.get().toInt() and 0xFF
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt()
                        val value = b0 or (b1 shl 8) or (b2 shl 16)
                        value / 8_388_608.0
                    }
                    PCM_32_BIT -> buffer.int / 2_147_483_648.0
                    else -> 0.0
                }
            }
            out[frame] = (mixed / channels.toDouble()).coerceIn(-1.0, 1.0).toFloat()
        }
        return out
    }

    private fun samplesToMs(samples: Long): Long = samples * 1_000L / TARGET_RATE

    private fun ByteBuffer.sliceRange(offset: Int, size: Int): ByteBuffer {
        val dup = duplicate()
        dup.position(offset.coerceAtLeast(0))
        dup.limit((offset + size).coerceAtMost(capacity()))
        return dup.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private class CallbackFailure(cause: Throwable) : Exception(cause)

    companion object {
        private const val TARGET_RATE = 16_000
        private const val CODEC_TIMEOUT_US = 25_000L
        // Stable numeric values from android.media.AudioFormat; kept local so the pipeline remains compatible with minSdk 26.
        private const val PCM_24_BIT_PACKED = 0x15
        private const val PCM_32_BIT = 0x16
    }
}

/**
 * Streaming band-limited resampler for speech. It keeps only a small rolling source window,
 * applies a Hann-windowed sinc low-pass kernel, and therefore does not grow with media duration.
 */
private class StreamingSincResampler(
    private val targetRate: Int,
    private val radius: Int = 12
) {
    private var sourceRate = 0
    private var step = 1.0
    private var position = 0.0
    private var buffer = FloatArray(4096)
    private var size = 0

    fun process(input: FloatArray, rate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        require(rate > 0)
        if (rate == targetRate) {
            if (sourceRate != rate) reset(rate)
            return input
        }
        if (sourceRate != rate) reset(rate)
        append(input)
        return produce(flush = false)
    }

    fun finish(): FloatArray {
        if (sourceRate <= 0 || sourceRate == targetRate || size == 0) {
            size = 0
            position = 0.0
            return FloatArray(0)
        }
        append(FloatArray(radius + 2))
        val out = produce(flush = true)
        size = 0
        position = 0.0
        return out
    }

    private fun reset(rate: Int) {
        sourceRate = rate
        step = rate.toDouble() / targetRate.toDouble()
        size = 0
        ensure(radius + 1)
        repeat(radius) { buffer[size++] = 0f }
        position = radius.toDouble()
    }

    private fun produce(flush: Boolean): FloatArray {
        if (size == 0) return FloatArray(0)
        val estimated = max(16, ((size - position - radius).coerceAtLeast(0.0) / step + 4).toInt())
        var out = FloatArray(estimated)
        var count = 0
        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val cutoff = 0.47 * min(1.0, ratio)

        while (position + radius < size || (flush && position < size - radius)) {
            val center = floor(position).toInt()
            val frac = position - center
            var sum = 0.0
            var weights = 0.0
            for (tap in -radius + 1..radius) {
                val index = center + tap
                if (index !in 0 until size) continue
                val x = tap.toDouble() - frac
                val normalized = x / radius.toDouble()
                if (abs(normalized) >= 1.0) continue
                val window = 0.5 * (1.0 + cos(PI * normalized))
                val arg = 2.0 * cutoff * x
                val sinc = if (abs(arg) < 1e-10) 1.0 else sin(PI * arg) / (PI * arg)
                val weight = 2.0 * cutoff * sinc * window
                sum += buffer[index] * weight
                weights += weight
            }
            if (count == out.size) out = out.copyOf(out.size * 2)
            out[count++] = if (abs(weights) > 1e-12) (sum / weights).coerceIn(-1.0, 1.0).toFloat() else 0f
            position += step
        }

        val drop = (floor(position).toInt() - radius).coerceAtLeast(0)
        if (drop > 0) {
            buffer.copyInto(buffer, 0, drop, size)
            size -= drop
            position -= drop
        }
        return out.copyOf(count)
    }

    private fun append(values: FloatArray) {
        ensure(size + values.size)
        values.copyInto(buffer, size)
        size += values.size
    }

    private fun ensure(required: Int) {
        if (required > buffer.size) buffer = buffer.copyOf(max(required, buffer.size * 2))
    }
}

private class FloatAccumulator(initialCapacity: Int) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
    var size: Int = 0
        private set

    fun append(values: FloatArray) {
        if (values.isEmpty()) return
        ensure(size + values.size)
        values.copyInto(data, size)
        size += values.size
    }

    fun copyPrefix(count: Int): FloatArray = data.copyOfRange(0, count.coerceAtMost(size))

    fun dropPrefix(count: Int) {
        val n = count.coerceAtMost(size)
        if (n <= 0) return
        data.copyInto(data, 0, n, size)
        size -= n
    }

    private fun ensure(required: Int) {
        if (required > data.size) data = data.copyOf(max(required, data.size * 2))
    }
}
