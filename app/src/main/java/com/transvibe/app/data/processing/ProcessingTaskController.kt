package com.transvibe.app.data.processing

import android.app.Application
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import com.transvibe.app.domain.model.*
import com.transvibe.app.service.SubtitleProcessingService
import kotlinx.coroutines.CancellationException
import java.util.UUID

class ProcessingTaskController(
    private val app: Application,
    private val repository: ProcessingRepository
) : ProcessingTaskGateway {
    override suspend fun start(video: SelectedVideo, modelId: String, prefs: AppPreferences): Result<Unit> {
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = ProcessingJob(
            jobId = jobId,
            sourceUri = video.uriString,
            displayName = video.displayName,
            selectedModelId = modelId,
            sourceLanguage = prefs.videoSpeechLanguage,
            targetLanguage = prefs.subtitleTargetLanguage,
            processingMode = prefs.processingProfile,
            state = ProcessingJobState.QUEUED,
            progress = 0f,
            checkpointTimestamp = now,
            errorCode = null,
            createdTimestamp = now,
            updatedTimestamp = now
        )
        return try {
            repository.create(job)
            SubtitleProcessingService.start(app, jobId)
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { repository.finish(jobId, ProcessingJobState.FAILED, "SERVICE_START_FAILED") }
            Result.failure(t)
        }
    }

    override fun cancel() = SubtitleProcessingService.cancel(app)

    override suspend fun resume(): Result<Unit> {
        val jobId = repository.requeueCurrentInterrupted()
            ?: return Result.failure(IllegalStateException("NO_INTERRUPTED_JOB"))
        return try {
            SubtitleProcessingService.start(app, jobId)
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            repository.finish(jobId, ProcessingJobState.INTERRUPTED, "SERVICE_START_FAILED")
            Result.failure(t)
        }
    }

    override suspend fun clearCurrent() = repository.clearCurrent()
}
