package com.transvibe.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.transvibe.app.MainActivity
import com.transvibe.app.TransVibeApplication
import com.transvibe.app.data.subtitle.SubtitleExporter
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.data.model.ModelStore
import com.transvibe.app.domain.repository.PersistedSubtitleSegment
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.model.ModelCatalog
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.ProcessingErrorCode
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.domain.runtime.ProcessingProfile
import com.transvibe.app.domain.runtime.ProcessingRuntime
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.media.decoder.AudioDecoder
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.media.vad.VoiceActivityDetector
import com.transvibe.app.nativebridge.whisper.WhisperNative
import com.transvibe.app.data.subtitle.SubtitlePostProcessor
import com.transvibe.app.data.subtitle.SegmentLanguageAligner
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.language.AdaptiveLanguageMonitor
import com.transvibe.app.domain.language.RecognitionQuality
import com.transvibe.app.domain.language.SourceLanguageMode
import com.transvibe.app.domain.language.SourceLanguagePolicy
import com.transvibe.app.domain.whisper.RollingWhisperPrompt
import com.transvibe.app.translation.TranslationEngine
import com.transvibe.app.translation.TranslationEngineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

data class SubtitleProcessingDependencies(
    val applicationScope: CoroutineScope,
    val processingRepository: ProcessingRepository,
    val resources: AiResourceManager,
    val mediaInspector: MediaSourceInspector,
    val modelStore: ModelStore,
    val translationEngines: TranslationEngineFactory,
    val voiceActivityDetector: VoiceActivityDetector
)

class SubtitleProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var applicationScope: CoroutineScope
    private lateinit var processingRepository: ProcessingRepository
    private lateinit var resources: AiResourceManager
    private lateinit var mediaInspector: MediaSourceInspector
    private lateinit var modelStore: ModelStore
    private lateinit var translationEngines: TranslationEngineFactory
    private lateinit var voiceActivityDetector: VoiceActivityDetector
    @Volatile private var activeJob: Job? = null
    @Volatile private var nativeHandle: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var timedOut = false
    @Volatile private var cancellationRequested = false
    @Volatile private var activeProcessingJobId: String? = null
    @Volatile private var lastPersistedProgress = -1
    @Volatile private var lastPersistedStage = ProcessingStage.NONE

    override fun onCreate() {
        super.onCreate()
        (application as TransVibeApplication).inject(this)
        createChannel()
    }

    internal fun inject(dependencies: SubtitleProcessingDependencies) {
        applicationScope = dependencies.applicationScope
        processingRepository = dependencies.processingRepository
        resources = dependencies.resources
        mediaInspector = dependencies.mediaInspector
        modelStore = dependencies.modelStore
        translationEngines = dependencies.translationEngines
        voiceActivityDetector = dependencies.voiceActivityDetector
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { cancelProcessing(); return START_NOT_STICKY }
            ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return START_NOT_STICKY
                val app = application as TransVibeApplication
                if (activeJob?.isCompleted == false) {
                    scope.launch(Dispatchers.IO) {
                        processingRepository.get(jobId)?.let { persisted ->
                            processingRepository.finish(
                                jobId, ProcessingJobState.FAILED, ERROR_PROCESSOR_BUSY
                            )
                        }
                    }
                    return START_NOT_STICKY
                }

                // Enter foreground immediately; durable job lookup stays off the service main thread.
                promoteToForeground("تهيئة المعالجة", 1)
                timedOut = false
                cancellationRequested = false
                activeProcessingJobId = jobId
                ProcessingServiceRuntimeRegistry.markActive(jobId)
                lastPersistedProgress = -1
                lastPersistedStage = ProcessingStage.NONE

                val job = scope.launch {
                    val persistedJob = withContext(Dispatchers.IO) { processingRepository.get(jobId) }
                    if (persistedJob == null) {
                        ProcessingServiceRuntimeRegistry.markInactive(jobId)
                        activeProcessingJobId = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                        return@launch
                    }
                    val uri = runCatching { Uri.parse(persistedJob.sourceUri) }.getOrNull()
                    if (uri == null) {
                        withContext(Dispatchers.IO) {
                            processingRepository.finish(jobId, ProcessingJobState.FAILED, ERROR_INVALID_JOB)
                        }
                        ProcessingServiceRuntimeRegistry.markInactive(jobId)
                        activeProcessingJobId = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                        return@launch
                    }
                    if (persistedJob.state != ProcessingJobState.QUEUED) {
                        ProcessingServiceRuntimeRegistry.markInactive(jobId)
                        activeProcessingJobId = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                        return@launch
                    }
                    val requestedProfile = ProcessingProfile.fromPersisted(persistedJob.processingMode)
                    val sourceLanguagePolicy = SourceLanguagePolicy.parse(persistedJob.sourceLanguage)
                    val startingProgress = if (persistedJob.lastCompletedMediaTimestampMs > 0L) {
                        persistedJob.progress.coerceIn(1f, 75f)
                    } else 1f
                    withContext(Dispatchers.IO) {
                        processingRepository.updateCheckpoint(
                            jobId, ProcessingJobState.RUNNING, startingProgress, stage = ProcessingStage.PREPARING
                        )
                    }
                    lastPersistedProgress = startingProgress.toInt()
                    lastPersistedStage = ProcessingStage.PREPARING
                    process(
                        jobId = jobId,
                        uri = uri,
                        videoName = persistedJob.displayName,
                        modelId = persistedJob.selectedModelId,
                        requestedProfile = requestedProfile,
                        sourceLanguagePolicy = sourceLanguagePolicy,
                        targetLanguage = persistedJob.targetLanguage.trim().lowercase().ifBlank { "ar" }
                    )
                }
                activeJob = job
                job.invokeOnCompletion { if (activeJob === job) activeJob = null }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        cancelNativeInference()
        activeJob?.cancel(CancellationException("Foreground service timeout"))
        activeProcessingJobId?.let { jobId ->
            applicationScope.launch {
                processingRepository.finish(
                    jobId,
                    ProcessingJobState.INTERRUPTED,
                    ProcessingErrorCode.FOREGROUND_TIMEOUT.name
                )
            }
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private suspend fun process(
        jobId: String,
        uri: Uri,
        videoName: String,
        modelId: String,
        requestedProfile: ProcessingProfile,
        sourceLanguagePolicy: SourceLanguagePolicy,
        targetLanguage: String
    ) {
        var translator: TranslationEngine? = null
        var sessionDir: File? = null
        var completed = false
        try {
            acquireProcessingWakeLock()
            val model = ModelCatalog.byId(modelId) ?: throw DomainException(
                ProcessingError(ProcessingErrorCode.MODEL_MISSING, "النموذج المحدد غير معروف.", retryable = false)
            )
            val manualLanguage = sourceLanguagePolicy.manualLanguage ?: "en"
            val initialRequest = ProcessingResourceRequest(
                sourceLanguage = if (sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL) manualLanguage else "auto",
                targetLanguage = targetLanguage,
                vadRequested = true
            )
            val ready = resources.checkPipeline(model, initialRequest)
            if (ready != AiResourceState.Ready) throw DomainException(ProcessingErrorMapper.fromResource(ready))

            checkpoint(jobId, 3, ProcessingStage.PREPARING)
            updateNotification("التحقق من مصدر الفيديو", 3)
            mediaInspector.ensureReadable(uri)
            mediaInspector.inspectAudio(uri)
            currentCoroutineContext().ensureActive()

            val resume = withContext(Dispatchers.IO) {
                processingRepository.prepareResume(jobId, RESUME_OVERLAP_MS)
            }
            val rollingSegments = resume.rollingSegments.toMutableList()
            var rollingStartIndex = resume.rollingStartIndex
            var detected: String? = if (sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL) manualLanguage else resume.detectedLanguage
            var translationChecked = false
            var detectedBranchChecked = sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL
            val adaptiveLanguageMonitor = AdaptiveLanguageMonitor()
            val sourcePrompt = RollingWhisperPrompt(maxSentences = 3, maxCharacters = 240)
            val englishPrompt = RollingWhisperPrompt(maxSentences = 3, maxCharacters = 240)
            sourcePrompt.seed(rollingSegments) { it.originalText.takeIf { text -> text.isNotBlank() } }
            englishPrompt.seed(rollingSegments) { it.englishText }
            var segmentId = resume.nextSegmentId
            val profile = requestedProfile
            val thermallyConstrained = isThermallyConstrained()
            val tuning = ProcessingRuntime.tuning(model, profile, thermallyConstrained = thermallyConstrained)
            val threads = tuning.whisperThreads
            AppLogger.d(
                "ProcessingRuntime",
                "mode=$profile preserveOriginal=${profile.preserveOriginalTranscript} " +
                    "foreignWhisperPassCap=${profile.maxForeignLanguageWhisperPasses} threads=$threads " +
                    "chunkSeconds=${tuning.audioChunkSeconds} channelCapacity=${tuning.channelCapacity} " +
                    "thermalConstrained=$thermallyConstrained model=${model.id} sourceMode=${sourceLanguagePolicy.mode}"
            )

            val totalDurationUs = AtomicLong(1L)
            val vadInputSamples = AtomicLong(0L)
            val vadSpeechSamples = AtomicLong(0L)
            val vadRegionCount = AtomicLong(0L)
            val pipelineStartedAtNs = System.nanoTime()
            val thermalStatusAtStart = currentThermalStatus()

            suspend fun processAudioChunk(chunk: AudioDecoder.AudioChunk) {
                currentCoroutineContext().ensureActive()
                val durationProgress = if (totalDurationUs.get() > 1L) {
                    (chunk.endMs * 1000.0 / totalDurationUs.get().toDouble()).coerceIn(0.0, 1.0)
                } else 0.0
                val recognizeProgress = (20 + durationProgress * 55).roundToInt().coerceIn(20, 75)

                vadInputSamples.addAndGet(chunk.samples.size.toLong())
                val speechRegions = voiceActivityDetector.detectSpeechRegions(chunk.samples, AUDIO_SAMPLE_RATE_HZ)
                vadSpeechSamples.addAndGet(speechRegions.sumOf { (it.endSample - it.startSample).toLong() })
                vadRegionCount.addAndGet(speechRegions.size.toLong())
                if (speechRegions.isEmpty()) {
                    persistChunkCheckpoint(jobId, rollingSegments, rollingStartIndex, chunk.endMs, recognizeProgress, detected)
                    updateNotification("التعرف على الكلام", recognizeProgress)
                    return
                }

                if (nativeHandle == 0L) {
                    checkpoint(jobId, 20, ProcessingStage.PREPARING)
                    updateNotification("تحميل نموذج Whisper", 20)
                    nativeHandle = withContext(ProcessingRuntime.inferenceDispatcher) {
                        WhisperNative.createContext(modelStore.file(model).absolutePath)
                    }
                    if (nativeHandle == 0L) throw DomainException(
                        ProcessingError(ProcessingErrorCode.NATIVE_INITIALIZATION_FAILED, "تعذر تحميل نموذج Whisper.")
                    )
                    currentCoroutineContext().ensureActive()
                }

                for (region in speechRegions) {
                    currentCoroutineContext().ensureActive()
                    val speechSamples = chunk.samples.copyOfRange(region.startSample, region.endSample)
                    val regionStartMs = chunk.startMs + region.startSample * 1_000L / AUDIO_SAMPLE_RATE_HZ

                    if (sourceLanguagePolicy.requiresInitialDetection && detected == null) {
                        val detectionSamples = if (speechSamples.size > LANGUAGE_DETECTION_SAMPLES) {
                            speechSamples.copyOfRange(0, LANGUAGE_DETECTION_SAMPLES)
                        } else speechSamples
                        val detectedNow = withContext(ProcessingRuntime.inferenceDispatcher) {
                            WhisperNative.detectLanguage(nativeHandle, detectionSamples, threads)
                        }.ifBlank { "en" }.lowercase()
                        detected = detectedNow
                        processingRepository.updateDetectedLanguage(jobId, detectedNow)
                    }

                    val lang = detected ?: manualLanguage
                    if (sourceLanguagePolicy.mode != SourceLanguageMode.MANUAL && detected != null && !detectedBranchChecked) {
                        val branchReadiness = resources.checkPipeline(
                            model,
                            ProcessingResourceRequest(sourceLanguage = lang, targetLanguage = targetLanguage, vadRequested = true)
                        )
                        if (branchReadiness != AiResourceState.Ready) {
                            throw DomainException(ProcessingErrorMapper.fromResource(branchReadiness))
                        }
                        detectedBranchChecked = true
                    }

                    val needsTargetTranslator = targetLanguage != "en" && lang != targetLanguage
                    if (needsTargetTranslator && !translationChecked) {
                        val engine = translationEngines.create(LanguageCode.ENGLISH, LanguageCode.of(targetLanguage))
                        if (!engine.isReady()) {
                            engine.close()
                            throw DomainException(ProcessingError(ProcessingErrorCode.TRANSLATION_MODEL_MISSING, "TARGET_TRANSLATION_PACK_MISSING"))
                        }
                        translator = engine
                        translationChecked = true
                    }

                    checkpoint(jobId, recognizeProgress.coerceAtLeast(20), ProcessingStage.RECOGNIZING)
                    updateNotification("التعرف على الكلام", recognizeProgress.coerceAtLeast(20))

                    var qualityEvidence: List<WhisperNative.RecognitionSegment> = emptyList()
                    val candidates: List<SubtitleSegment> = when {
                        lang == "en" -> {
                            val recognized = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.transcribeDetailed(nativeHandle, speechSamples, "en", false, threads, englishPrompt.valueOrNull())
                            }
                            qualityEvidence = recognized
                            recognized.mapNotNull { detailed ->
                                val r = detailed.segment
                                val text = r.originalText.trim()
                                if (text.isBlank()) null else SubtitleSegment(
                                    id = 0L,
                                    startMs = regionStartMs + r.startMs,
                                    endMs = (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L),
                                    originalText = text,
                                    englishText = text,
                                    translatedText = if (targetLanguage == "en") text else null
                                )
                            }
                        }
                        lang == targetLanguage -> {
                            val recognized = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.transcribeDetailed(nativeHandle, speechSamples, lang, false, threads, sourcePrompt.valueOrNull())
                            }
                            qualityEvidence = recognized
                            recognized.mapNotNull { detailed ->
                                val r = detailed.segment
                                val text = r.originalText.trim()
                                if (text.isBlank()) null else SubtitleSegment(
                                    id = 0L,
                                    startMs = regionStartMs + r.startMs,
                                    endMs = (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L),
                                    originalText = text,
                                    translatedText = text
                                )
                            }
                        }
                        profile.preserveOriginalTranscript -> {
                            val originalDetailed = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.transcribeDetailed(nativeHandle, speechSamples, lang, false, threads, sourcePrompt.valueOrNull())
                            }
                            qualityEvidence = originalDetailed
                            val originalRaw = originalDetailed.map { it.segment }
                            currentCoroutineContext().ensureActive()
                            val englishRaw = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.transcribe(nativeHandle, speechSamples, lang, true, threads, englishPrompt.valueOrNull())
                            }
                            val originalSegments = originalRaw.mapNotNull { r ->
                                val text = r.originalText.trim()
                                if (text.isBlank()) null else SubtitleSegment(
                                    id = r.id,
                                    startMs = regionStartMs + r.startMs,
                                    endMs = (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L),
                                    originalText = text
                                )
                            }
                            val englishSegments = englishRaw.mapNotNull { r ->
                                val text = r.originalText.trim()
                                if (text.isBlank()) null else SubtitleSegment(
                                    id = r.id,
                                    startMs = regionStartMs + r.startMs,
                                    endMs = (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L),
                                    originalText = text,
                                    englishText = text,
                                    translatedText = if (targetLanguage == "en") text else null
                                )
                            }
                            SegmentLanguageAligner.combineOriginalAndEnglish(originalSegments, englishSegments)
                        }
                        else -> {
                            val recognized = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.transcribeDetailed(nativeHandle, speechSamples, lang, true, threads, englishPrompt.valueOrNull())
                            }
                            qualityEvidence = recognized
                            recognized.mapNotNull { detailed ->
                                val r = detailed.segment
                                val english = r.originalText.trim()
                                if (english.isBlank()) null else SubtitleSegment(
                                    id = 0L,
                                    startMs = regionStartMs + r.startMs,
                                    endMs = (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L),
                                    originalText = "",
                                    englishText = english,
                                    translatedText = if (targetLanguage == "en") english else null
                                )
                            }
                        }
                    }

                    for (candidate0 in candidates) {
                        val beforeSize = rollingSegments.size
                        val candidate = candidate0.copy(id = segmentId)
                        if (SubtitlePostProcessor.acceptWithDedup(rollingSegments, candidate)) {
                            SubtitlePostProcessor.compactAdjacentTail(rollingSegments)
                            if (rollingSegments.size > beforeSize) segmentId++
                        }
                    }

                    if (sourceLanguagePolicy.allowsRedetection) {
                        val textForQuality = candidates.joinToString(" ") {
                            it.englishText ?: it.translatedText ?: it.originalText
                        }.trim()
                        val avgTokenProbability = qualityEvidence.mapNotNull { it.averageTokenProbability }
                            .takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        val maxNoSpeechProbability = qualityEvidence.mapNotNull { it.noSpeechProbability }.maxOrNull()
                        val quality = RecognitionQuality(
                            averageTokenProbability = avgTokenProbability,
                            noSpeechProbability = maxNoSpeechProbability,
                            hasUsableText = textForQuality.isNotBlank(),
                            suspiciousRepetition = isSuspiciousRecognition(textForQuality)
                        )
                        if (adaptiveLanguageMonitor.record(quality)) {
                            val detectionSamples = if (speechSamples.size > LANGUAGE_DETECTION_SAMPLES) {
                                speechSamples.copyOfRange(0, LANGUAGE_DETECTION_SAMPLES)
                            } else speechSamples
                            val redetected = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.detectLanguage(nativeHandle, detectionSamples, threads)
                            }.trim().lowercase()
                            if (redetected.isNotBlank() && redetected != detected) {
                                AppLogger.d("LanguageDetection", "job=$jobId adaptiveRedetect old=$detected new=$redetected")
                                detected = redetected
                                processingRepository.updateDetectedLanguage(jobId, redetected)
                                detectedBranchChecked = false
                                runCatching { translator?.close() }
                                translator = null
                                translationChecked = false
                                adaptiveLanguageMonitor.resetAfterLanguageChange()
                            }
                        }
                    }
                }

                persistChunkCheckpoint(jobId, rollingSegments, rollingStartIndex, chunk.endMs, recognizeProgress, detected)
                // Only persisted/finalized subtitle text is allowed to influence the next chunk.
                sourcePrompt.seed(rollingSegments) { it.originalText.takeIf { text -> text.isNotBlank() } }
                englishPrompt.seed(rollingSegments) { it.englishText }
                val overflow = (rollingSegments.size - ROLLING_WINDOW_SEGMENTS).coerceAtLeast(0)
                repeat(overflow) { rollingSegments.removeAt(0) }
                rollingStartIndex += overflow
                updateNotification("التعرف على الكلام", recognizeProgress)
            }

            coroutineScope {
                val audioChunks = Channel<AudioDecoder.AudioChunk>(capacity = tuning.channelCapacity)
                val producer = launch(Dispatchers.IO) {
                    try {
                        AudioDecoder(this@SubtitleProcessingService).decodeChunks(
                            uri = uri,
                            chunkSeconds = tuning.audioChunkSeconds,
                            startMs = resume.resumeFromMs,
                            onProgress = { processedUs, totalUs ->
                                totalDurationUs.set(totalUs.coerceAtLeast(1L))
                                if (detected == null) {
                                    val p = if (totalUs > 0L) {
                                        val ratio = processedUs.toDouble() / totalUs.toDouble()
                                        (5 + ratio * 15).roundToInt().coerceIn(5, 20)
                                    } else 8
                                    updateNotification("استخراج الصوت", p)
                                }
                            },
                            onChunk = { chunk ->
                                currentCoroutineContext().ensureActive()
                                audioChunks.send(chunk)
                            }
                        )
                    } finally {
                        audioChunks.close()
                    }
                }
                try {
                    for (chunk in audioChunks) {
                        currentCoroutineContext().ensureActive()
                        processAudioChunk(chunk)
                    }
                    producer.join()
                } finally {
                    producer.cancel()
                    audioChunks.cancel()
                }
            }

            val pipelineElapsedMs = (System.nanoTime() - pipelineStartedAtNs) / 1_000_000L
            val thermalStatusAtEnd = currentThermalStatus()
            AppLogger.d(
                "AudioPipelineMetrics",
                "job=$jobId elapsedMs=$pipelineElapsedMs capacity=${tuning.channelCapacity} " +
                    "thermalStart=$thermalStatusAtStart thermalEnd=$thermalStatusAtEnd mode=$profile " +
                    "vadInputSamples=${vadInputSamples.get()} vadSpeechSamples=${vadSpeechSamples.get()} " +
                    "vadRegions=${vadRegionCount.get()}"
            )

            val persistedCount = withContext(Dispatchers.IO) { processingRepository.segmentCount(jobId) }
            if (persistedCount == 0) throw DomainException(ProcessingError(ProcessingErrorCode.NATIVE_INFERENCE_FAILED, "لم يتم اكتشاف كلام واضح في الفيديو."))
            val lang = detected ?: manualLanguage
            val needsTargetTranslator = targetLanguage != "en" && lang != targetLanguage
            if (needsTargetTranslator && translator == null) {
                val engine = translationEngines.create(LanguageCode.ENGLISH, LanguageCode.of(targetLanguage))
                if (!engine.isReady()) {
                    engine.close()
                    throw DomainException(ProcessingError(ProcessingErrorCode.TRANSLATION_MODEL_MISSING, "TARGET_TRANSLATION_PACK_MISSING"))
                }
                translator = engine
                translationChecked = true
            }
            checkpoint(jobId, 75, ProcessingStage.RECOGNIZING); updateNotification("اكتمل التعرف على الكلام", 75)

            if (needsTargetTranslator) {
                checkpoint(jobId, 75, ProcessingStage.TRANSLATING); updateNotification("ترجمة النص", 75)
                val engine = translator ?: throw DomainException(ProcessingError(ProcessingErrorCode.TRANSLATION_MODEL_MISSING, "TARGET_TRANSLATION_PACK_MISSING"))
                var afterIndex = -1
                var translatedCount = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val batch = withContext(Dispatchers.IO) {
                        processingRepository.loadSegmentBatch(jobId, afterIndex, TRANSLATION_BATCH_SEGMENTS)
                    }
                    if (batch.isEmpty()) break
                    val translatedBatch = ArrayList<PersistedSubtitleSegment>(batch.size)
                    for (item in batch) {
                        currentCoroutineContext().ensureActive()
                        val segment = item.segment
                        val translated = if (segment.translatedText.isNullOrBlank()) {
                            segment.copy(translatedText = engine.translate(segment.englishText ?: segment.originalText))
                        } else segment
                        translatedBatch += item.copy(segment = translated)
                    }
                    withContext(Dispatchers.IO) { processingRepository.upsertSegmentBatch(jobId, translatedBatch) }
                    afterIndex = batch.last().segmentIndex
                    translatedCount += batch.size
                    val p = 75 + (translatedCount * 20.0 / persistedCount.coerceAtLeast(1)).roundToInt()
                    checkpoint(jobId, p.coerceAtMost(95), ProcessingStage.TRANSLATING)
                    updateNotification("ترجمة النص", p.coerceAtMost(95))
                }
            }

            checkpoint(jobId, 95, ProcessingStage.EXPORTING); updateNotification("إنشاء ملفات الترجمة", 95)
            val id = jobId
            val dir = File(filesDir, "sessions/$id").apply { mkdirs() }
            sessionDir = dir
            val srt = File(dir, "subtitles-$targetLanguage.srt")
            val vtt = File(dir, "subtitles-$targetLanguage.vtt")
            withContext(Dispatchers.IO) {
                SubtitleExporter.writeSrtBatches(srt, processingRepository.segmentBatches(jobId, EXPORT_BATCH_SEGMENTS))
            }
            currentCoroutineContext().ensureActive()
            checkpoint(jobId, 97, ProcessingStage.EXPORTING); updateNotification("تم إنشاء SRT", 97)
            withContext(Dispatchers.IO) {
                SubtitleExporter.writeVttBatches(vtt, processingRepository.segmentBatches(jobId, EXPORT_BATCH_SEGMENTS))
            }
            currentCoroutineContext().ensureActive()
            checkpoint(jobId, 99, ProcessingStage.EXPORTING); updateNotification("تم إنشاء WebVTT", 99)
            val persistable = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            withContext(Dispatchers.IO) {
                processingRepository.completePersistedSession(
                    jobId = jobId,
                    sourceUriPersistable = persistable,
                    detectedLanguage = lang,
                    srtPath = srt.absolutePath,
                    vttPath = vtt.absolutePath
                )
            }
            completed = true
        } catch (ce: CancellationException) {
            cancelNativeInference()
            if (!timedOut) {
                processingRepository.finish(jobId, ProcessingJobState.CANCELLED, ProcessingErrorCode.CANCELLED.name)
            }
            throw ce
        } catch (e: DomainException) {
            if (timedOut) Unit
            else if (cancellationRequested || e.error.code == ProcessingErrorCode.CANCELLED) {
                processingRepository.finish(jobId, ProcessingJobState.CANCELLED, e.error.code.name)
            } else {
                processingRepository.finish(jobId, ProcessingJobState.FAILED, e.error.code.name)
            }
        } catch (t: Throwable) {
            if (timedOut) Unit
            else if (cancellationRequested) {
                processingRepository.finish(jobId, ProcessingJobState.CANCELLED, ProcessingErrorCode.CANCELLED.name)
            } else {
                val mapped = ProcessingErrorMapper.fromThrowable(t)
                if (mapped.code == ProcessingErrorCode.CANCELLED) {
                    processingRepository.finish(jobId, ProcessingJobState.CANCELLED, mapped.code.name)
                } else {
                    processingRepository.finish(jobId, ProcessingJobState.FAILED, mapped.code.name)
                }
            }
        } finally {
            runCatching { translator?.close() }
            if (!completed) runCatching { sessionDir?.deleteRecursively() }
            val h = nativeHandle; nativeHandle = 0L
            if (h != 0L) runCatching { WhisperNative.releaseContext(h) }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (completed) postCompletionNotification(videoName)
            ProcessingServiceRuntimeRegistry.markInactive(jobId)
            if (activeProcessingJobId == jobId) activeProcessingJobId = null
            stopSelf()
        }
    }

    private fun isSuspiciousRecognition(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^\p{L}\p{N}]+"), " ").trim()
        if (normalized.isBlank()) return false
        val words = normalized.split(Regex("\s+")).filter { it.isNotBlank() }
        if (words.size < 6) return false
        val maxRepeat = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return maxRepeat.toDouble() / words.size >= 0.55
    }

    private fun persistChunkCheckpoint(
        jobId: String,
        rollingSegments: List<SubtitleSegment>,
        rollingStartIndex: Int,
        completedMediaTimestampMs: Long,
        progress: Int,
        detectedLanguage: String?
    ) {
        val durableProgress = maxOf(progress.coerceIn(0, 100), lastPersistedProgress.coerceAtLeast(0))
        processingRepository.persistCompletedChunk(
            jobId = jobId,
            segments = rollingSegments,
            firstSegmentIndex = rollingStartIndex,
            lastCompletedMediaTimestampMs = completedMediaTimestampMs,
            progress = durableProgress.toFloat(),
            detectedLanguage = detectedLanguage
        )
        lastPersistedProgress = durableProgress
        lastPersistedStage = ProcessingStage.RECOGNIZING
    }

    private fun checkpoint(jobId: String, progress: Int, stage: ProcessingStage) {
        val normalized = maxOf(progress.coerceIn(0, 100), lastPersistedProgress.coerceAtLeast(0))
        if (normalized == lastPersistedProgress && stage == lastPersistedStage) return
        processingRepository.updateCheckpoint(
            jobId = jobId,
            state = ProcessingJobState.RUNNING,
            progress = normalized.toFloat(),
            stage = stage
        )
        lastPersistedProgress = normalized
        lastPersistedStage = stage
    }

    private fun acquireProcessingWakeLock() {
        check(wakeLock?.isHeld != true) { "Processing WakeLock is already owned" }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TransVibe:Processing")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) runCatching { lock.release() }
    }

    private fun cancelNativeInference() {
        val h = nativeHandle
        if (h != 0L) runCatching { WhisperNative.cancel(h) }
    }

    private fun cancelProcessing() {
        cancellationRequested = true
        cancelNativeInference()
        val job = activeJob
        if (job?.isCompleted == false) job.cancel()
        else {
            activeProcessingJobId?.let { jobId ->
                applicationScope.launch {
                    processingRepository.finish(
                        jobId, ProcessingJobState.CANCELLED, ProcessingErrorCode.CANCELLED.name
                    )
                }
                activeProcessingJobId = null
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        activeProcessingJobId?.let { jobId ->
            applicationScope.launch {
                val persisted = processingRepository.get(jobId)
                if (persisted?.state == ProcessingJobState.RUNNING) {
                    processingRepository.finish(
                        jobId, ProcessingJobState.INTERRUPTED, ERROR_SERVICE_DESTROYED
                    )
                }
            }
        }
        activeProcessingJobId?.let(ProcessingServiceRuntimeRegistry::markInactive)
        cancelNativeInference()
        activeJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun isThermallyConstrained(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        return power.isPowerSaveMode ||
            (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE)
    }

    private fun currentThermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) {
        getSystemService(PowerManager::class.java).currentThermalStatus
    } else {
        -1
    }

    private fun promoteToForeground(text: String, progress: Int) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, processingNotification(text, progress), type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "معالجة الترجمة النصية", NotificationManager.IMPORTANCE_LOW))
    }
    private fun processingNotification(text: String, progress: Int): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, SubtitleProcessingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TransVibe").setContentText(text).setContentIntent(launch)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress, false)
            .addAction(android.R.drawable.ic_delete, "إلغاء", cancel).build()
    }
    private fun updateNotification(text: String, progress: Int) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, processingNotification(text, progress)) }
    }
    private fun postCompletionNotification(videoName: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val launch = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("اكتملت الترجمة النصية")
            .setContentText(videoName).setContentIntent(launch).setAutoCancel(true).setOngoing(false).build()
        runCatching { NotificationManagerCompat.from(this).notify(COMPLETION_NOTIFICATION_ID, notification) }
    }

    private class DomainException(val error: ProcessingError) : Exception(error.userMessage)

    companion object {
        const val ACTION_START = "com.transvibe.START"
        const val ACTION_CANCEL = "com.transvibe.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 2001
        private const val COMPLETION_NOTIFICATION_ID = 2002
        private const val AUDIO_SAMPLE_RATE_HZ = 16_000
        private const val LANGUAGE_DETECTION_SAMPLES = 12 * AUDIO_SAMPLE_RATE_HZ
        private const val RESUME_OVERLAP_MS = 3_000L
        private const val ROLLING_WINDOW_SEGMENTS = 12
        private const val TRANSLATION_BATCH_SEGMENTS = 48
        private const val EXPORT_BATCH_SEGMENTS = 64
        private const val ERROR_PROCESSOR_BUSY = "PROCESSOR_BUSY"
        private const val ERROR_SERVICE_DESTROYED = "SERVICE_DESTROYED"
        private const val ERROR_INVALID_JOB = "INVALID_JOB"

        fun start(context: Context, jobId: String) {
            val i = Intent(context, SubtitleProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB_ID, jobId)
            context.startForegroundService(i)
        }
        fun cancel(context: Context) { context.startService(Intent(context, SubtitleProcessingService::class.java).setAction(ACTION_CANCEL)) }
    }
}
