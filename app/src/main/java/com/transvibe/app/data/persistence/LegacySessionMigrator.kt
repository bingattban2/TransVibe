package com.transvibe.app.data.persistence

import android.content.Context
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingOutcome
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.SubtitleSegment
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * One-time, idempotent migration of the pre-Room JSON history format.
 *
 * Legacy files are read-only inputs. Current application code never writes session JSON.
 * A SUCCESS receipt is committed in the same Room transaction as the imported job/segments.
 */
class LegacySessionMigrator(
    context: Context,
    private val dao: ProcessingDao
) {
    private val dir = File(context.filesDir, "sessions")

    @Volatile
    private var checkedThisProcess = false

    @Synchronized
    fun migrateOnce() {
        if (checkedThisProcess) return
        if (dao.getMigrationMarker(MIGRATION_NAME) != null) {
            checkedThisProcess = true
            return
        }

        dir.mkdirs()
        val candidates = discoverLegacyFiles()
        candidates.forEach(::migrateCandidate)

        // Reaching this point means every discovered file is either committed SUCCESS or
        // explicitly recorded MALFORMED. There are no untracked legacy candidates left.
        dao.upsertMigrationMarker(
            MigrationMarkerEntity(
                migrationName = MIGRATION_NAME,
                completedTimestamp = System.currentTimeMillis()
            )
        )
        checkedThisProcess = true
    }

    private fun discoverLegacyFiles(): List<LegacyCandidate> {
        val files = dir.listFiles { file ->
            file.isFile && (file.name.endsWith(".json") || file.name.endsWith(".json.migrated"))
        }.orEmpty()

        // Phase 9 renamed successful files to *.json.migrated. Treat that suffix as the same
        // legacy source key so a remaining *.json copy cannot create a second migration receipt.
        return files
            .groupBy { canonicalSourceKey(it.name) }
            .map { (key, duplicates) ->
                val preferred = duplicates.firstOrNull { it.name.endsWith(".json") } ?: duplicates.first()
                LegacyCandidate(key, preferred)
            }
            .sortedBy { it.sourceKey }
    }

    private fun migrateCandidate(candidate: LegacyCandidate) {
        val existing = dao.getLegacyMigration(candidate.sourceKey)
        if (existing?.status == STATUS_SUCCESS) return

        val checkedAt = System.currentTimeMillis()
        val bytes = runCatching { candidate.file.readBytes() }.getOrElse { error ->
            recordMalformed(candidate, unreadableFingerprint(candidate.file), null, checkedAt, "LEGACY_READ_FAILED:${error.javaClass.simpleName}")
            return
        }
        val fingerprint = sha256(bytes)
        if (existing?.status == STATUS_MALFORMED && existing.sourceFingerprint == fingerprint) return

        val session = runCatching { parseAndValidate(bytes) }.getOrElse { error ->
            recordMalformed(candidate, fingerprint, null, checkedAt, "LEGACY_INVALID:${error.message.orEmpty().take(160)}")
            return
        }

        val legacyJob = dao.getJob(session.id)
            ?: session.srtPath.takeIf { it.isNotBlank() }?.let(dao::findJobBySrtPath)
            ?: dao.findLegacyCompletedJob(session.videoUri, session.modelId, session.createdAtEpochMs)
        val targetJobId = legacyJob?.jobId ?: session.id
        val state = when (session.outcome) {
            ProcessingOutcome.COMPLETED -> ProcessingJobState.COMPLETED
            ProcessingOutcome.FAILED -> ProcessingJobState.FAILED
            ProcessingOutcome.CANCELLED -> ProcessingJobState.CANCELLED
        }
        val importedAt = System.currentTimeMillis()
        val job = if (legacyJob != null) {
            legacyJob.copy(
                sourceUri = session.videoUri,
                displayName = session.videoName,
                selectedModelId = session.modelId,
                sourceLanguage = session.detectedLanguage,
                state = state.name,
                progress = if (state == ProcessingJobState.COMPLETED) 100f else legacyJob.progress,
                checkpointTimestamp = maxOf(legacyJob.checkpointTimestamp, session.createdAtEpochMs),
                errorCode = legacyJob.errorCode,
                updatedTimestamp = maxOf(legacyJob.updatedTimestamp, session.createdAtEpochMs),
                sourceUriPersistable = session.sourceUriPersistable,
                detectedLanguage = session.detectedLanguage,
                srtPath = session.srtPath.takeIf { it.isNotBlank() },
                vttPath = session.vttPath.takeIf { it.isNotBlank() }
            )
        } else {
            ProcessingJobEntity(
                jobId = targetJobId,
                sourceUri = session.videoUri,
                displayName = session.videoName,
                selectedModelId = session.modelId,
                sourceLanguage = session.detectedLanguage,
                targetLanguage = "ar",
                processingMode = "LEGACY",
                state = state.name,
                progress = if (state == ProcessingJobState.COMPLETED) 100f else 0f,
                checkpointTimestamp = session.createdAtEpochMs,
                errorCode = null,
                createdTimestamp = session.createdAtEpochMs,
                updatedTimestamp = session.createdAtEpochMs,
                sourceUriPersistable = session.sourceUriPersistable,
                detectedLanguage = session.detectedLanguage,
                srtPath = session.srtPath.takeIf { it.isNotBlank() },
                vttPath = session.vttPath.takeIf { it.isNotBlank() }
            )
        }
        val segments = session.segments.mapIndexed { index, segment ->
            SubtitleSegmentEntity(
                segmentId = segment.id,
                jobId = targetJobId,
                segmentIndex = index,
                startTimeMs = segment.startMs,
                endTimeMs = segment.endMs,
                originalText = segment.originalText,
                englishText = segment.englishText,
                targetTranslatedText = segment.translatedText
            )
        }
        val receipt = LegacySessionMigrationEntity(
            sourceKey = candidate.sourceKey,
            sourceFingerprint = fingerprint,
            sessionId = session.id,
            targetJobId = targetJobId,
            status = STATUS_SUCCESS,
            errorCode = null,
            checkedTimestamp = checkedAt,
            importedTimestamp = importedAt
        )

        // Atomic: successful migration cannot be recorded without the matching job and segments.
        dao.importLegacySession(job, segments, receipt)
        // Deliberately retain the original legacy file as a safety backup. There is no legacy
        // write path anymore; Room is the only primary store after this verified transaction.
    }

    private fun recordMalformed(
        candidate: LegacyCandidate,
        fingerprint: String,
        sessionId: String?,
        checkedAt: Long,
        errorCode: String
    ) {
        dao.upsertLegacyMigration(
            LegacySessionMigrationEntity(
                sourceKey = candidate.sourceKey,
                sourceFingerprint = fingerprint,
                sessionId = sessionId,
                targetJobId = null,
                status = STATUS_MALFORMED,
                errorCode = errorCode,
                checkedTimestamp = checkedAt,
                importedTimestamp = null
            )
        )
    }

    private fun parseAndValidate(bytes: ByteArray): ProcessingSession {
        require(bytes.isNotEmpty()) { "empty_file" }
        val o = JSONObject(bytes.toString(Charsets.UTF_8))
        val id = requiredString(o, "id")
        val videoUri = requiredString(o, "videoUri")
        val videoName = requiredString(o, "videoName")
        val modelId = requiredString(o, "modelId")
        val detectedLanguage = requiredString(o, "detectedLanguage")
        val createdAt = o.getLong("createdAtEpochMs")
        require(createdAt > 0L) { "invalid_created_timestamp" }

        val arr = o.getJSONArray("segments")
        val segmentIds = HashSet<Long>(arr.length())
        var previousStart = -1L
        val segments = (0 until arr.length()).map { index ->
            val x = arr.getJSONObject(index)
            val segment = SubtitleSegment(
                id = x.getLong("id"),
                startMs = x.getLong("startMs"),
                endMs = x.getLong("endMs"),
                originalText = x.getString("originalText"),
                englishText = nullableString(x, "englishText"),
                translatedText = nullableString(x, "arabicText")
            )
            require(segmentIds.add(segment.id)) { "duplicate_segment_id" }
            require(segment.startMs >= previousStart) { "segments_not_ordered_at_$index" }
            previousStart = segment.startMs
            segment
        }

        val outcome = runCatching {
            ProcessingOutcome.valueOf(o.optString("outcome", ProcessingOutcome.COMPLETED.name))
        }.getOrElse { throw IllegalArgumentException("invalid_outcome") }

        return ProcessingSession(
            id = id,
            videoUri = videoUri,
            videoName = videoName,
            modelId = modelId,
            detectedLanguage = detectedLanguage,
            createdAtEpochMs = createdAt,
            srtPath = o.optString("srtPath"),
            vttPath = o.optString("vttPath"),
            segments = segments,
            sourceUriPersistable = o.optBoolean("sourceUriPersistable", false),
            outcome = outcome
        )
    }

    private fun requiredString(o: JSONObject, key: String): String =
        o.getString(key).trim().also { require(it.isNotEmpty()) { "missing_$key" } }

    private fun nullableString(o: JSONObject, key: String): String? =
        o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun canonicalSourceKey(name: String): String = name.removeSuffix(".migrated")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun unreadableFingerprint(file: File): String = "unreadable:${file.length()}:${file.lastModified()}"

    private data class LegacyCandidate(val sourceKey: String, val file: File)

    companion object {
        const val MIGRATION_NAME = "legacy_session_json_to_room_v1"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_MALFORMED = "MALFORMED"
    }
}
