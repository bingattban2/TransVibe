package com.transvibe.app.data.persistence

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "processing_jobs",
    indices = [
        Index(value = ["created_timestamp"], name = "idx_processing_jobs_created"),
        Index(value = ["state"], name = "idx_processing_jobs_state")
    ]
)
data class ProcessingJobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "selected_model_id") val selectedModelId: String,
    @ColumnInfo(name = "source_language") val sourceLanguage: String,
    @ColumnInfo(name = "target_language") val targetLanguage: String,
    @ColumnInfo(name = "processing_mode") val processingMode: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "stage", defaultValue = "'NONE'") val stage: String = "NONE",
    @ColumnInfo(name = "progress") val progress: Float,
    @ColumnInfo(name = "checkpoint_timestamp") val checkpointTimestamp: Long,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "created_timestamp") val createdTimestamp: Long,
    @ColumnInfo(name = "updated_timestamp") val updatedTimestamp: Long,
    @ColumnInfo(name = "source_uri_persistable", defaultValue = "0") val sourceUriPersistable: Boolean = false,
    @ColumnInfo(name = "detected_language") val detectedLanguage: String? = null,
    @ColumnInfo(name = "srt_path") val srtPath: String? = null,
    @ColumnInfo(name = "vtt_path") val vttPath: String? = null,
    @ColumnInfo(name = "is_current", defaultValue = "0") val isCurrent: Boolean = false,
    @ColumnInfo(name = "last_completed_media_timestamp_ms", defaultValue = "0") val lastCompletedMediaTimestampMs: Long = 0L,
    @ColumnInfo(name = "last_persisted_segment_index", defaultValue = "-1") val lastPersistedSegmentIndex: Int = -1
)

@Entity(
    tableName = "subtitle_segments",
    primaryKeys = ["job_id", "segment_id"],
    indices = [
        Index(value = ["job_id"], name = "idx_subtitle_segments_job_id"),
        Index(value = ["job_id", "segment_index"], name = "idx_subtitle_segments_order", unique = true)
    ]
)
data class SubtitleSegmentEntity(
    @ColumnInfo(name = "segment_id") val segmentId: Long,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "segment_index") val segmentIndex: Int,
    @ColumnInfo(name = "start_time_ms") val startTimeMs: Long,
    @ColumnInfo(name = "end_time_ms") val endTimeMs: Long,
    @ColumnInfo(name = "original_text") val originalText: String,
    @ColumnInfo(name = "english_text") val englishText: String?,
    @ColumnInfo(name = "target_translated_text") val targetTranslatedText: String?
)

@Entity(
    tableName = "legacy_session_migrations",
    indices = [Index(value = ["status"], name = "idx_legacy_session_migrations_status")]
)
data class LegacySessionMigrationEntity(
    @PrimaryKey @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "target_job_id") val targetJobId: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "checked_timestamp") val checkedTimestamp: Long,
    @ColumnInfo(name = "imported_timestamp") val importedTimestamp: Long?
)

@Entity(tableName = "migration_markers")
data class MigrationMarkerEntity(
    @PrimaryKey @ColumnInfo(name = "migration_name") val migrationName: String,
    @ColumnInfo(name = "completed_timestamp") val completedTimestamp: Long
)

data class ProcessingHistorySummaryRow(
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "selected_model_id") val selectedModelId: String,
    @ColumnInfo(name = "detected_language") val detectedLanguage: String?,
    @ColumnInfo(name = "source_language") val sourceLanguage: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "source_uri_persistable") val sourceUriPersistable: Boolean,
    @ColumnInfo(name = "created_timestamp") val createdTimestamp: Long,
    @ColumnInfo(name = "updated_timestamp") val updatedTimestamp: Long
)

@Dao
interface ProcessingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertJob(job: ProcessingJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertJob(job: ProcessingJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSegments(segments: List<SubtitleSegmentEntity>)

    @Query("DELETE FROM subtitle_segments WHERE job_id = :jobId")
    fun deleteSegments(jobId: String)

    @Query("SELECT * FROM processing_jobs WHERE job_id = :jobId LIMIT 1")
    fun getJob(jobId: String): ProcessingJobEntity?

    @Query("SELECT * FROM subtitle_segments WHERE job_id = :jobId ORDER BY segment_index ASC")
    fun getSegments(jobId: String): List<SubtitleSegmentEntity>

    @Query("SELECT * FROM subtitle_segments WHERE job_id = :jobId ORDER BY segment_index DESC LIMIT :limit")
    fun getRecentSegments(jobId: String, limit: Int): List<SubtitleSegmentEntity>

    @Query("SELECT * FROM subtitle_segments WHERE job_id = :jobId AND segment_index > :afterIndex ORDER BY segment_index ASC LIMIT :limit")
    fun getSegmentBatchAfter(jobId: String, afterIndex: Int, limit: Int): List<SubtitleSegmentEntity>

    @Query("SELECT COUNT(*) FROM subtitle_segments WHERE job_id = :jobId")
    fun countSegments(jobId: String): Int

    @Query("SELECT * FROM processing_jobs WHERE is_current = 1 ORDER BY updated_timestamp DESC LIMIT 1")
    fun getCurrentJob(): ProcessingJobEntity?

    @Query("DELETE FROM subtitle_segments WHERE job_id = :jobId AND end_time_ms > :resumeFromMs")
    fun deleteSegmentsAfter(jobId: String, resumeFromMs: Long): Int

    @Query("SELECT COALESCE(MAX(segment_index), -1) FROM subtitle_segments WHERE job_id = :jobId")
    fun maxSegmentIndex(jobId: String): Int

    @Query("SELECT COALESCE(MAX(segment_id), -1) FROM subtitle_segments WHERE job_id = :jobId")
    fun maxSegmentId(jobId: String): Long

    @Query(
        """
        SELECT job_id, display_name, selected_model_id, detected_language, source_language,
               state, source_uri_persistable, created_timestamp, updated_timestamp
        FROM processing_jobs
        WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        ORDER BY created_timestamp DESC
        LIMIT :limit
        """
    )
    fun listTerminalSummaries(limit: Int): List<ProcessingHistorySummaryRow>

    @Query(
        """
        SELECT job_id, display_name, selected_model_id, detected_language, source_language,
               state, source_uri_persistable, created_timestamp, updated_timestamp
        FROM processing_jobs
        WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
        ORDER BY created_timestamp DESC
        LIMIT :limit
        """
    )
    fun observeTerminalSummaries(limit: Int): Flow<List<ProcessingHistorySummaryRow>>

    @Query("SELECT * FROM processing_jobs WHERE is_current = 1 ORDER BY updated_timestamp DESC LIMIT 1")
    fun observeCurrentJob(): Flow<ProcessingJobEntity?>

    @Query("UPDATE processing_jobs SET is_current = 0 WHERE is_current = 1")
    fun clearCurrentJobFlag(): Int

    @Query("UPDATE processing_jobs SET is_current = 1 WHERE job_id = :jobId")
    fun markCurrentJob(jobId: String): Int

    @Transaction
    fun selectCurrentJob(jobId: String) {
        clearCurrentJobFlag()
        markCurrentJob(jobId)
    }

    @Transaction
    fun insertCurrentJob(job: ProcessingJobEntity) {
        clearCurrentJobFlag()
        insertJob(job.copy(isCurrent = true))
    }

    @Query("SELECT * FROM processing_jobs ORDER BY created_timestamp DESC LIMIT :limit")
    fun listRecentJobs(limit: Int): List<ProcessingJobEntity>

    @Query(
        """
        SELECT * FROM processing_jobs
        WHERE source_uri = :sourceUri
          AND selected_model_id = :modelId
          AND state = 'COMPLETED'
        ORDER BY ABS(created_timestamp - :createdTimestamp) ASC, updated_timestamp DESC
        LIMIT 1
        """
    )
    fun findLegacyCompletedJob(sourceUri: String, modelId: String, createdTimestamp: Long): ProcessingJobEntity?

    @Query("SELECT * FROM processing_jobs WHERE srt_path = :srtPath LIMIT 1")
    fun findJobBySrtPath(srtPath: String): ProcessingJobEntity?

    @Query("SELECT * FROM legacy_session_migrations WHERE source_key = :sourceKey LIMIT 1")
    fun getLegacyMigration(sourceKey: String): LegacySessionMigrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLegacyMigration(record: LegacySessionMigrationEntity)

    @Query("SELECT * FROM migration_markers WHERE migration_name = :name LIMIT 1")
    fun getMigrationMarker(name: String): MigrationMarkerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMigrationMarker(marker: MigrationMarkerEntity)

    @Query(
        """
        UPDATE processing_jobs
        SET detected_language = :detectedLanguage,
            checkpoint_timestamp = :timestamp,
            updated_timestamp = :timestamp
        WHERE job_id = :jobId
        """
    )
    fun updateDetectedLanguage(jobId: String, detectedLanguage: String, timestamp: Long): Int

    @Query(
        """
        UPDATE processing_jobs
        SET state = :state,
            stage = :stage,
            progress = :progress,
            checkpoint_timestamp = :timestamp,
            error_code = :errorCode,
            updated_timestamp = :timestamp
        WHERE job_id = :jobId
        """
    )
    fun updateCheckpoint(jobId: String, state: String, stage: String, progress: Float, errorCode: String?, timestamp: Long): Int

    @Query(
        """
        UPDATE processing_jobs
        SET state = 'RUNNING',
            stage = 'RECOGNIZING',
            progress = :progress,
            checkpoint_timestamp = :timestamp,
            updated_timestamp = :timestamp,
            detected_language = COALESCE(:detectedLanguage, detected_language),
            last_completed_media_timestamp_ms = :lastCompletedMediaTimestampMs,
            last_persisted_segment_index = :lastPersistedSegmentIndex
        WHERE job_id = :jobId
          AND state = 'RUNNING'
        """
    )
    fun updateChunkCheckpoint(
        jobId: String,
        progress: Float,
        timestamp: Long,
        detectedLanguage: String?,
        lastCompletedMediaTimestampMs: Long,
        lastPersistedSegmentIndex: Int
    ): Int

    @Query(
        """
        UPDATE processing_jobs
        SET state = 'QUEUED',
            stage = 'PREPARING',
            error_code = NULL,
            updated_timestamp = :timestamp
        WHERE job_id = :jobId
          AND state = 'INTERRUPTED'
        """
    )
    fun requeueInterrupted(jobId: String, timestamp: Long): Int

    @Transaction
    fun persistCompletedChunk(
        jobId: String,
        segments: List<SubtitleSegmentEntity>,
        progress: Float,
        timestamp: Long,
        detectedLanguage: String?,
        lastCompletedMediaTimestampMs: Long,
        lastPersistedSegmentIndex: Int
    ) {
        if (segments.isNotEmpty()) insertSegments(segments)
        check(updateChunkCheckpoint(
            jobId = jobId,
            progress = progress,
            timestamp = timestamp,
            detectedLanguage = detectedLanguage,
            lastCompletedMediaTimestampMs = lastCompletedMediaTimestampMs,
            lastPersistedSegmentIndex = lastPersistedSegmentIndex
        ) > 0) { "Cannot checkpoint non-running job $jobId" }
    }

    @Transaction
    fun trimForResume(jobId: String, resumeFromMs: Long, timestamp: Long): Pair<Int, Long> {
        deleteSegmentsAfter(jobId, resumeFromMs)
        val index = maxSegmentIndex(jobId)
        val segmentId = maxSegmentId(jobId)
        updateResumeTail(jobId, index, timestamp)
        return index to segmentId
    }

    @Query(
        """
        UPDATE processing_jobs
        SET last_persisted_segment_index = :lastPersistedSegmentIndex,
            updated_timestamp = :timestamp
        WHERE job_id = :jobId
        """
    )
    fun updateResumeTail(jobId: String, lastPersistedSegmentIndex: Int, timestamp: Long): Int

    @Query("SELECT * FROM processing_jobs WHERE state = 'RUNNING'")
    fun listRunningJobs(): List<ProcessingJobEntity>

    /**
     * Terminal transition that deliberately preserves the latest persisted stage/progress checkpoint.
     * The WHERE clause makes terminal outcomes idempotent and prevents later lifecycle callbacks from
     * overwriting FAILED/CANCELLED/INTERRUPTED with a different terminal state.
     */
    @Query(
        """
        UPDATE processing_jobs
        SET state = :state,
            checkpoint_timestamp = :timestamp,
            error_code = :errorCode,
            updated_timestamp = :timestamp
        WHERE job_id = :jobId
          AND state IN ('QUEUED', 'RUNNING')
        """
    )
    fun finishJob(jobId: String, state: String, errorCode: String?, timestamp: Long): Int

    @Query(
        """
        UPDATE processing_jobs
        SET state = 'COMPLETED',
            stage = 'COMPLETED',
            progress = 100,
            checkpoint_timestamp = :timestamp,
            error_code = NULL,
            updated_timestamp = :timestamp,
            source_uri_persistable = :sourceUriPersistable,
            detected_language = :detectedLanguage,
            srt_path = :srtPath,
            vtt_path = :vttPath
        WHERE job_id = :jobId
          AND state = 'RUNNING'
        """
    )
    fun completePersistedJob(
        jobId: String,
        timestamp: Long,
        sourceUriPersistable: Boolean,
        detectedLanguage: String,
        srtPath: String,
        vttPath: String
    ): Int

    @Query(
        """
        UPDATE processing_jobs
        SET state = 'INTERRUPTED',
            checkpoint_timestamp = :timestamp,
            error_code = :errorCode,
            updated_timestamp = :timestamp
        WHERE job_id = :jobId
          AND state = 'RUNNING'
        """
    )
    fun interruptRunningJob(jobId: String, timestamp: Long, errorCode: String): Int

    @Transaction
    fun saveCompletedSession(job: ProcessingJobEntity, segments: List<SubtitleSegmentEntity>) {
        upsertJob(job)
        deleteSegments(job.jobId)
        if (segments.isNotEmpty()) insertSegments(segments)
    }

    /** Job + segments + SUCCESS migration receipt commit atomically. */
    @Transaction
    fun importLegacySession(
        job: ProcessingJobEntity,
        segments: List<SubtitleSegmentEntity>,
        migration: LegacySessionMigrationEntity
    ) {
        val previous = getLegacyMigration(migration.sourceKey)
        if (previous?.status == "SUCCESS") return
        upsertJob(job)
        deleteSegments(job.jobId)
        if (segments.isNotEmpty()) insertSegments(segments)
        upsertLegacyMigration(migration)
    }
}

@Database(
    entities = [
        ProcessingJobEntity::class,
        SubtitleSegmentEntity::class,
        LegacySessionMigrationEntity::class,
        MigrationMarkerEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class TransVibeDatabase : RoomDatabase() {
    abstract fun processingDao(): ProcessingDao

    companion object {
        private const val DATABASE_NAME = "processing_jobs.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN source_uri_persistable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN detected_language TEXT")
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN srt_path TEXT")
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN vtt_path TEXT")
                db.execSQL("DROP INDEX IF EXISTS idx_processing_jobs_updated")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_processing_jobs_created ON processing_jobs(created_timestamp)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subtitle_segments (
                        segment_id INTEGER NOT NULL,
                        job_id TEXT NOT NULL,
                        segment_index INTEGER NOT NULL,
                        start_time_ms INTEGER NOT NULL,
                        end_time_ms INTEGER NOT NULL,
                        original_text TEXT NOT NULL,
                        english_text TEXT,
                        target_translated_text TEXT,
                        PRIMARY KEY(job_id, segment_id)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_subtitle_segments_job_id ON subtitle_segments(job_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_subtitle_segments_order ON subtitle_segments(job_id, segment_index)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS legacy_session_migrations (
                        source_key TEXT NOT NULL,
                        source_fingerprint TEXT NOT NULL,
                        session_id TEXT,
                        target_job_id TEXT,
                        status TEXT NOT NULL,
                        error_code TEXT,
                        checked_timestamp INTEGER NOT NULL,
                        imported_timestamp INTEGER,
                        PRIMARY KEY(source_key)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_session_migrations_status ON legacy_session_migrations(status)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS migration_markers (
                        migration_name TEXT NOT NULL,
                        completed_timestamp INTEGER NOT NULL,
                        PRIMARY KEY(migration_name)
                    )
                    """.trimIndent()
                )
            }
        }


        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN stage TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN is_current INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE processing_jobs
                    SET is_current = 1
                    WHERE job_id = (
                        SELECT job_id FROM processing_jobs
                        WHERE state IN ('QUEUED', 'RUNNING')
                        ORDER BY updated_timestamp DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN last_completed_media_timestamp_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE processing_jobs ADD COLUMN last_persisted_segment_index INTEGER NOT NULL DEFAULT -1")
                db.execSQL(
                    """
                    UPDATE processing_jobs
                    SET last_persisted_segment_index = COALESCE((
                        SELECT MAX(segment_index) FROM subtitle_segments
                        WHERE subtitle_segments.job_id = processing_jobs.job_id
                    ), -1),
                    last_completed_media_timestamp_ms = COALESCE((
                        SELECT MAX(end_time_ms) FROM subtitle_segments
                        WHERE subtitle_segments.job_id = processing_jobs.job_id
                    ), 0)
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): TransVibeDatabase = Room.databaseBuilder(
            context.applicationContext,
            TransVibeDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
