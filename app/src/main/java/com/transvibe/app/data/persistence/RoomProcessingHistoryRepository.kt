package com.transvibe.app.data.persistence

import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.ProcessingOutcome
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomProcessingHistoryRepository(
    private val dao: ProcessingDao,
    private val legacyMigrator: LegacySessionMigrator
) : SessionRepository {
    @Volatile private var legacyImportChecked = false

    private fun ensureLegacyImported() {
        if (legacyImportChecked) return
        synchronized(this) {
            if (!legacyImportChecked) {
                legacyMigrator.migrateOnce()
                legacyImportChecked = true
            }
        }
    }

    override fun observeHistory(limit: Int): Flow<List<ProcessingHistorySummary>> {
        ensureLegacyImported()
        return dao.observeTerminalSummaries(limit.coerceIn(1, 500))
            .map { rows -> rows.map(::toSummary) }
            .flowOn(Dispatchers.IO)
    }
    override fun save(session: ProcessingSession) {
        ensureLegacyImported()
        val existing = dao.getJob(session.id)
            ?: error("Missing durable processing job ${session.id}")
        val now = System.currentTimeMillis()
        val completedJob = existing.copy(
            sourceUri = session.videoUri,
            displayName = session.videoName,
            selectedModelId = session.modelId,
            sourceLanguage = session.detectedLanguage,
            state = ProcessingJobState.COMPLETED.name,
            stage = ProcessingStage.COMPLETED.name,
            progress = 100f,
            checkpointTimestamp = now,
            errorCode = null,
            updatedTimestamp = now,
            sourceUriPersistable = session.sourceUriPersistable,
            detectedLanguage = session.detectedLanguage,
            srtPath = session.srtPath,
            vttPath = session.vttPath
        )
        val segments = session.segments.mapIndexed { index, segment -> segment.toEntity(session.id, index) }
        dao.saveCompletedSession(completedJob, segments)
    }

    override fun listSummaries(limit: Int): List<ProcessingHistorySummary> {
        ensureLegacyImported()
        return dao.listTerminalSummaries(limit.coerceIn(1, 500)).map(::toSummary)
    }

    override fun getSession(jobId: String): ProcessingSession? {
        ensureLegacyImported()
        val job = dao.getJob(jobId) ?: return null
        if (job.state != ProcessingJobState.COMPLETED.name) return null
        val segments = dao.getSegments(jobId).map { it.toDomain() }
        return ProcessingSession(
            id = job.jobId,
            videoUri = job.sourceUri,
            videoName = job.displayName,
            modelId = job.selectedModelId,
            detectedLanguage = job.detectedLanguage ?: job.sourceLanguage,
            targetLanguage = job.targetLanguage,
            createdAtEpochMs = job.createdTimestamp,
            srtPath = job.srtPath.orEmpty(),
            vttPath = job.vttPath.orEmpty(),
            segments = segments,
            sourceUriPersistable = job.sourceUriPersistable,
            outcome = ProcessingOutcome.COMPLETED
        )
    }

    private fun toSummary(row: ProcessingHistorySummaryRow) = ProcessingHistorySummary(
        jobId = row.jobId,
        displayName = row.displayName,
        modelId = row.selectedModelId,
        detectedLanguage = row.detectedLanguage ?: row.sourceLanguage,
        state = runCatching { ProcessingJobState.valueOf(row.state) }.getOrDefault(ProcessingJobState.INTERRUPTED),
        sourceUriPersistable = row.sourceUriPersistable,
        createdAtEpochMs = row.createdTimestamp,
        updatedAtEpochMs = row.updatedTimestamp
    )

    private fun SubtitleSegment.toEntity(jobId: String, index: Int) = SubtitleSegmentEntity(
        segmentId = id,
        jobId = jobId,
        segmentIndex = index,
        startTimeMs = startMs,
        endTimeMs = endMs,
        originalText = originalText,
        englishText = englishText,
        targetTranslatedText = translatedText
    )

    private fun SubtitleSegmentEntity.toDomain() = SubtitleSegment(
        id = segmentId,
        startMs = startTimeMs,
        endMs = endTimeMs,
        originalText = originalText,
        englishText = englishText,
        translatedText = targetTranslatedText
    )
}
