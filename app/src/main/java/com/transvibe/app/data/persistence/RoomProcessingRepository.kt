package com.transvibe.app.data.persistence

import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.ProcessingErrorCode
import com.transvibe.app.domain.model.ProcessingJob
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingResult
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.repository.PersistedSubtitleSegment
import com.transvibe.app.domain.repository.ProcessingRepository as ProcessingRepositoryContract
import com.transvibe.app.domain.repository.ProcessingResumeSnapshot
import com.transvibe.app.domain.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Persistent processing state boundary.
 *
 * Room is the source of truth. Service code writes processing checkpoints here and UI code observes
 * the Room-backed flows exposed by this repository. No in-memory event bus is required to restore
 * processing state after process recreation.
 */
class RoomProcessingRepository(
    private val dao: ProcessingDao,
    private val sessions: SessionRepository
) : ProcessingRepositoryContract {
    override val currentState: Flow<ProcessingState> = dao.observeCurrentJob()
        .map { entity -> entity?.let(::toProcessingState) ?: ProcessingState.Idle }
        .flowOn(Dispatchers.IO)

    override val canResumeCurrent: Flow<Boolean> = dao.observeCurrentJob()
        .map { entity -> entity?.state == ProcessingJobState.INTERRUPTED.name && entity.lastCompletedMediaTimestampMs > 0L }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    override fun create(job: ProcessingJob) {
        dao.insertCurrentJob(job.toRoomEntity())
    }

    override fun get(jobId: String): ProcessingJob? = dao.getJob(jobId)?.toRoomDomain()

    override fun prepareResume(jobId: String, overlapMs: Long, rollingLimit: Int): ProcessingResumeSnapshot {
        val job = dao.getJob(jobId) ?: error("Missing processing job $jobId")
        val resumeFromMs = (job.lastCompletedMediaTimestampMs - overlapMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        val (lastIndex, lastId) = if (job.lastCompletedMediaTimestampMs > 0L) {
            dao.trimForResume(jobId, resumeFromMs, System.currentTimeMillis())
        } else {
            dao.maxSegmentIndex(jobId) to dao.maxSegmentId(jobId)
        }
        val recent = dao.getRecentSegments(jobId, rollingLimit.coerceIn(1, 64)).asReversed()
        return ProcessingResumeSnapshot(
            resumeFromMs = resumeFromMs,
            rollingSegments = recent.map { it.toDomainSegment() },
            rollingStartIndex = recent.firstOrNull()?.segmentIndex ?: (lastIndex + 1),
            nextSegmentId = lastId + 1L,
            nextSegmentIndex = lastIndex + 1,
            detectedLanguage = job.detectedLanguage
        )
    }

    override fun persistCompletedChunk(
        jobId: String,
        segments: List<com.transvibe.app.domain.model.SubtitleSegment>,
        firstSegmentIndex: Int,
        lastCompletedMediaTimestampMs: Long,
        progress: Float,
        detectedLanguage: String?
    ) {
        val entities = segments.mapIndexed { offset, segment ->
            segment.toEntity(jobId, firstSegmentIndex + offset)
        }
        val lastIndex = if (entities.isEmpty()) firstSegmentIndex - 1 else entities.last().segmentIndex
        dao.persistCompletedChunk(
            jobId = jobId,
            segments = entities,
            progress = progress.coerceIn(0f, 100f),
            timestamp = System.currentTimeMillis(),
            detectedLanguage = detectedLanguage,
            lastCompletedMediaTimestampMs = lastCompletedMediaTimestampMs.coerceAtLeast(0L),
            lastPersistedSegmentIndex = lastIndex
        )
    }

    override fun segmentCount(jobId: String): Int = dao.countSegments(jobId)

    override fun loadSegmentBatch(jobId: String, afterIndex: Int, limit: Int): List<PersistedSubtitleSegment> =
        dao.getSegmentBatchAfter(jobId, afterIndex, limit.coerceIn(1, 256)).map { entity ->
            PersistedSubtitleSegment(entity.segmentIndex, entity.toDomainSegment())
        }

    override fun upsertSegmentBatch(jobId: String, segments: List<PersistedSubtitleSegment>) {
        if (segments.isEmpty()) return
        dao.insertSegments(segments.map { it.segment.toEntity(jobId, it.segmentIndex) })
    }

    override fun segmentBatches(jobId: String, batchSize: Int): Sequence<List<com.transvibe.app.domain.model.SubtitleSegment>> = sequence {
        var afterIndex = -1
        while (true) {
            val batch = loadSegmentBatch(jobId, afterIndex, batchSize)
            if (batch.isEmpty()) break
            yield(batch.map { it.segment })
            afterIndex = batch.last().segmentIndex
        }
    }

    override fun completePersistedSession(
        jobId: String,
        sourceUriPersistable: Boolean,
        detectedLanguage: String,
        srtPath: String,
        vttPath: String,
        timestamp: Long
    ) {
        check(dao.completePersistedJob(
            jobId = jobId,
            timestamp = timestamp,
            sourceUriPersistable = sourceUriPersistable,
            detectedLanguage = detectedLanguage,
            srtPath = srtPath,
            vttPath = vttPath
        ) > 0) { "Cannot complete non-running job $jobId" }
    }

    override fun requeueCurrentInterrupted(timestamp: Long): String? {
        val current = dao.getCurrentJob() ?: return null
        if (current.state != ProcessingJobState.INTERRUPTED.name || current.lastCompletedMediaTimestampMs <= 0L) return null
        return current.jobId.takeIf { dao.requeueInterrupted(current.jobId, timestamp) > 0 }
    }

    override fun selectCurrent(jobId: String) {
        dao.selectCurrentJob(jobId)
    }

    override fun clearCurrent() {
        dao.clearCurrentJobFlag()
    }

    override fun updateDetectedLanguage(jobId: String, detectedLanguage: String, timestamp: Long) {
        dao.updateDetectedLanguage(jobId, detectedLanguage, timestamp)
    }

    override fun updateCheckpoint(
        jobId: String,
        state: ProcessingJobState,
        progress: Float,
        errorCode: String?,
        stage: ProcessingStage,
        timestamp: Long
    ) {
        dao.updateCheckpoint(
            jobId = jobId,
            state = state.name,
            stage = stage.name,
            progress = progress.coerceIn(0f, 100f),
            errorCode = errorCode,
            timestamp = timestamp
        )
    }





    /**
     * Persist a terminal outcome while retaining the most recent durable progress/stage checkpoint.
     */
    override fun finish(
        jobId: String,
        state: ProcessingJobState,
        errorCode: String?,
        timestamp: Long
    ): Boolean {
        require(state == ProcessingJobState.FAILED || state == ProcessingJobState.CANCELLED || state == ProcessingJobState.INTERRUPTED)
        return dao.finishJob(jobId, state.name, errorCode, timestamp) > 0
    }

    /**
     * Reconcile stale RUNNING rows after process recreation. Only jobs without a live processing
     * service in this process are converted to INTERRUPTED. QUEUED work is intentionally untouched.
     */
    override fun reconcileRunningJobs(
        isServiceActive: (String) -> Boolean,
        timestamp: Long
    ): Int {
        var interrupted = 0
        dao.listRunningJobs().forEach { job ->
            if (!isServiceActive(job.jobId)) {
                interrupted += dao.interruptRunningJob(job.jobId, timestamp, ERROR_PROCESS_INTERRUPTED)
            }
        }
        return interrupted
    }

    private fun toProcessingState(entity: ProcessingJobEntity): ProcessingState {
        val state = runCatching { ProcessingJobState.valueOf(entity.state) }
            .getOrDefault(ProcessingJobState.INTERRUPTED)
        val stage = runCatching { ProcessingStage.valueOf(entity.stage) }
            .getOrDefault(ProcessingStage.NONE)
        val progress = entity.progress.coerceIn(0f, 100f)

        return when (state) {
            ProcessingJobState.QUEUED -> ProcessingState.Preparing(progress)
            ProcessingJobState.RUNNING -> when (stage) {
                ProcessingStage.EXTRACTING_AUDIO -> ProcessingState.ExtractingAudio(progress)
                ProcessingStage.RECOGNIZING -> ProcessingState.Recognizing(
                    progress = progress,
                    language = entity.detectedLanguage ?: entity.sourceLanguage.takeUnless { it == "auto" }
                )
                ProcessingStage.TRANSLATING -> ProcessingState.Translating(progress)
                ProcessingStage.EXPORTING -> ProcessingState.Exporting(progress)
                ProcessingStage.PREPARING, ProcessingStage.NONE, ProcessingStage.COMPLETED -> ProcessingState.Preparing(progress)
            }
            ProcessingJobState.COMPLETED -> {
                val session = sessions.getSession(entity.jobId)
                if (session != null) ProcessingState.Completed(ProcessingResult(session))
                else ProcessingState.Error(
                    ProcessingError(
                        ProcessingErrorCode.FILE_WRITE_FAILED,
                        "اكتملت المهمة لكن تعذر استعادة ملفات الجلسة المحفوظة.",
                        retryable = false
                    )
                )
            }
            ProcessingJobState.CANCELLED -> ProcessingState.Cancelled
            ProcessingJobState.FAILED, ProcessingJobState.INTERRUPTED -> ProcessingState.Error(errorFrom(entity.errorCode))
        }
    }

    private fun errorFrom(rawCode: String?): ProcessingError {
        if (rawCode == ERROR_PROCESS_INTERRUPTED || rawCode == ERROR_SERVICE_DESTROYED) {
            return ProcessingError(
                ProcessingErrorCode.UNKNOWN,
                "توقفت مهمة المعالجة بسبب إعادة تشغيل التطبيق أو إنهاء عملية النظام. يمكنك بدء المعالجة من جديد.",
                retryable = true
            )
        }
        if (rawCode == ERROR_PROCESSOR_BUSY) {
            return ProcessingError(ProcessingErrorCode.UNKNOWN, "هناك مهمة معالجة أخرى قيد التنفيذ.")
        }
        if (rawCode == ERROR_INVALID_JOB || rawCode == ERROR_SERVICE_START_FAILED) {
            return ProcessingError(ProcessingErrorCode.UNKNOWN, "تعذر استعادة مهمة المعالجة المحفوظة.")
        }
        val code = runCatching { ProcessingErrorCode.valueOf(rawCode.orEmpty()) }
            .getOrDefault(ProcessingErrorCode.UNKNOWN)
        val message = when (code) {
            ProcessingErrorCode.INVALID_VIDEO -> "الملف المحدد ليس فيديو صالحًا أو لم يعد متاحًا."
            ProcessingErrorCode.INVALID_CONFIGURATION -> "إعدادات المعالجة الحالية غير متوافقة مع النموذج المحدد."
            ProcessingErrorCode.NO_AUDIO_TRACK -> "الفيديو لا يحتوي على مسار صوتي."
            ProcessingErrorCode.UNSUPPORTED_CODEC -> "ترميز الصوت في هذا الفيديو غير مدعوم على الجهاز."
            ProcessingErrorCode.MODEL_MISSING -> "نموذج Whisper المطلوب غير مثبت."
            ProcessingErrorCode.MODEL_CORRUPTED -> "نموذج Whisper تالف ويجب إعادة تنزيله."
            ProcessingErrorCode.INSUFFICIENT_STORAGE -> "مساحة التخزين غير كافية لإكمال العملية."
            ProcessingErrorCode.NETWORK_UNAVAILABLE -> "لا يوجد اتصال بالإنترنت."
            ProcessingErrorCode.DOWNLOAD_FAILED -> "فشل تنزيل نموذج الذكاء الاصطناعي."
            ProcessingErrorCode.TRANSLATION_MODEL_MISSING -> "حزمة الترجمة الإنجليزية إلى العربية غير مثبتة."
            ProcessingErrorCode.NATIVE_INITIALIZATION_FAILED -> "تعذر تهيئة محرك Whisper."
            ProcessingErrorCode.NATIVE_INFERENCE_FAILED -> "تعذر إكمال التعرف على الكلام باستخدام Whisper."
            ProcessingErrorCode.JNI_FAILURE -> "حدث خلل في محرك المعالجة المحلي."
            ProcessingErrorCode.OUT_OF_MEMORY -> "الذاكرة غير كافية لإكمال المعالجة."
            ProcessingErrorCode.DECODING_FAILED -> "تعذر فك ترميز الصوت من الفيديو."
            ProcessingErrorCode.PERMISSION_DENIED -> "لا يملك التطبيق إذن الوصول إلى الملف."
            ProcessingErrorCode.FILE_WRITE_FAILED -> "تعذر قراءة أو كتابة أحد ملفات المعالجة."
            ProcessingErrorCode.CANCELLED -> "تم إلغاء المعالجة."
            ProcessingErrorCode.FOREGROUND_TIMEOUT -> "توقفت المعالجة لأن النظام بلغ الحد الزمني المسموح للمعالجة في الخلفية."
            ProcessingErrorCode.UNKNOWN -> "حدث خطأ غير متوقع أثناء معالجة الفيديو."
        }
        return ProcessingError(code, message)
    }

    companion object {
        const val ERROR_PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
        private const val ERROR_SERVICE_DESTROYED = "SERVICE_DESTROYED"
        private const val ERROR_PROCESSOR_BUSY = "PROCESSOR_BUSY"
        private const val ERROR_INVALID_JOB = "INVALID_JOB"
        private const val ERROR_SERVICE_START_FAILED = "SERVICE_START_FAILED"
    }
}

internal fun ProcessingJob.toRoomEntity() = ProcessingJobEntity(
    jobId = jobId,
    sourceUri = sourceUri,
    displayName = displayName,
    selectedModelId = selectedModelId,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    processingMode = processingMode,
    state = state.name,
    stage = stage.name,
    progress = progress,
    checkpointTimestamp = checkpointTimestamp,
    errorCode = errorCode,
    createdTimestamp = createdTimestamp,
    updatedTimestamp = updatedTimestamp,
    lastCompletedMediaTimestampMs = lastCompletedMediaTimestampMs,
    lastPersistedSegmentIndex = lastPersistedSegmentIndex
)

internal fun ProcessingJobEntity.toRoomDomain() = ProcessingJob(
    jobId = jobId,
    sourceUri = sourceUri,
    displayName = displayName,
    selectedModelId = selectedModelId,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    processingMode = processingMode,
    state = runCatching { ProcessingJobState.valueOf(state) }.getOrDefault(ProcessingJobState.INTERRUPTED),
    stage = runCatching { ProcessingStage.valueOf(stage) }.getOrDefault(ProcessingStage.NONE),
    progress = progress,
    checkpointTimestamp = checkpointTimestamp,
    errorCode = errorCode,
    createdTimestamp = createdTimestamp,
    updatedTimestamp = updatedTimestamp,
    lastCompletedMediaTimestampMs = lastCompletedMediaTimestampMs,
    lastPersistedSegmentIndex = lastPersistedSegmentIndex
)


private fun com.transvibe.app.domain.model.SubtitleSegment.toEntity(jobId: String, index: Int) = SubtitleSegmentEntity(
    segmentId = id,
    jobId = jobId,
    segmentIndex = index,
    startTimeMs = startMs,
    endTimeMs = endMs,
    originalText = originalText,
    englishText = englishText,
    targetTranslatedText = translatedText
)

private fun SubtitleSegmentEntity.toDomainSegment() = com.transvibe.app.domain.model.SubtitleSegment(
    id = segmentId,
    startMs = startTimeMs,
    endMs = endTimeMs,
    originalText = originalText,
    englishText = englishText,
    translatedText = targetTranslatedText
)
