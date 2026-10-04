package com.transvibe.app.domain.model

enum class ProcessingStage {
    PREPARING,
    EXTRACTING_AUDIO,
    RECOGNIZING,
    TRANSLATING,
    EXPORTING,
    COMPLETED,
    NONE
}

enum class ProcessingJobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

data class ProcessingJob(
    val jobId: String,
    val sourceUri: String,
    val displayName: String,
    val selectedModelId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val processingMode: String,
    val state: ProcessingJobState,
    val stage: ProcessingStage = ProcessingStage.NONE,
    val progress: Float,
    val checkpointTimestamp: Long,
    val errorCode: String?,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val lastCompletedMediaTimestampMs: Long = 0L,
    val lastPersistedSegmentIndex: Int = -1
)
