package com.transvibe.app.data.model

import android.content.Context
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.repository.WhisperModelBinaryValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ModelStore internal constructor(
    private val context: Context,
    private val binaryValidator: WhisperModelBinaryValidator,
    private val fileMover: SafeFileMover = SafeFileMover()
) {
    private val integrityPrefs = context.getSharedPreferences("model_integrity", Context.MODE_PRIVATE)
    private val modelLocks = ConcurrentHashMap<String, Mutex>()
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val whisperDir: File = File(modelsDir, "whisper").apply { mkdirs() }
    val metadataDir: File = File(modelsDir, "metadata").apply { mkdirs() }

    fun file(model: WhisperModel) = File(whisperDir, model.fileName)
    fun partial(model: WhisperModel) = File(whisperDir, model.fileName + ".tmp")
    fun importPartial(model: WhisperModel) = File(whisperDir, model.fileName + ".import.tmp")
    fun metadataFile(model: WhisperModel) = File(metadataDir, model.id + ".json")
    fun partialMetadataFile(model: WhisperModel) = File(metadataDir, model.id + ".partial.json")
    fun hasFinal(model: WhisperModel): Boolean = file(model).isFile
    fun hasPartial(model: WhisperModel): Boolean = partial(model).isFile && partial(model).length() > 0

    fun delete(model: WhisperModel) {
        file(model).delete()
        partial(model).delete()
        importPartial(model).delete()
        legacyBackup(model).delete()
        metadataFile(model).delete()
        partialMetadataFile(model).delete()
        integrityPrefs.edit().remove(model.id).apply()
    }

    fun partialMetadataMatches(model: WhisperModel): Boolean {
        val meta = partialMetadataFile(model)
        if (!meta.isFile || !meta.canRead()) return false
        return runCatching {
            val json = JSONObject(meta.readText())
            json.optInt("schemaVersion") == PARTIAL_METADATA_SCHEMA &&
                json.optString("id") == model.id &&
                json.optString("fileName") == model.fileName &&
                json.optString("downloadUrl") == model.downloadUrl &&
                json.optString("sha256").equals(model.sha256, ignoreCase = true) &&
                json.optLong("fileSizeBytes") == model.fileSizeBytes
        }.getOrDefault(false)
    }

    fun preparePartialMetadata(model: WhisperModel) {
        metadataDir.mkdirs()
        val json = JSONObject().apply {
            put("schemaVersion", PARTIAL_METADATA_SCHEMA)
            put("id", model.id)
            put("fileName", model.fileName)
            put("downloadUrl", model.downloadUrl)
            put("sha256", model.sha256)
            put("fileSizeBytes", model.fileSizeBytes)
            put("preparedAtEpochMs", System.currentTimeMillis())
        }
        val tmp = File(metadataDir, model.id + ".partial.json.tmp")
        tmp.writeText(json.toString())
        moveReplace(tmp, partialMetadataFile(model))
    }

    fun clearPartialMetadata(model: WhisperModel) {
        partialMetadataFile(model).delete()
        File(metadataDir, model.id + ".partial.json.tmp").delete()
    }

    fun clearTransferArtifacts(model: WhisperModel) {
        partial(model).delete()
        importPartial(model).delete()
        clearPartialMetadata(model)
        File(metadataDir, model.id + ".json.tmp").delete()
    }

    suspend fun sha256(candidate: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        candidate.inputStream().buffered().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Size + SHA-256 validation. SHA-256 remains the authority for official binary integrity. */
    suspend fun validate(
        model: WhisperModel,
        candidate: File = file(model),
        requireMetadata: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (!candidate.isFile || !candidate.canRead()) return@withContext false
        if (!plausibleSize(model, candidate.length())) return@withContext false

        val isFinal = sameFile(candidate, file(model))
        if (isFinal && cachedVerified(model, candidate) && (!requireMetadata || metadataMatches(model, candidate))) {
            return@withContext true
        }

        if (!sha256(candidate).equals(model.sha256, ignoreCase = true)) return@withContext false
        if (requireMetadata && !metadataMatches(model, candidate)) return@withContext false

        if (isFinal) rememberVerified(model, candidate)
        true
    }

    /**
     * Full recovery pass for an installed model.
     * A valid binary is never classified as corrupted merely because metadata is absent/stale.
     */
    suspend fun inspectAndRecover(model: WhisperModel): ModelIntegrityState = modelLock(model).withLock {
        withContext(Dispatchers.IO) {
            recoverInterruptedReplacement(model)

            val final = file(model)
            if (!final.isFile) {
                sanitizeInterruptedTemporaryFile(model)
                return@withContext ModelIntegrityState.MISSING
            }

            val state = inspectInstalled(model, final)
            when (state) {
                ModelIntegrityState.METADATA_MISSING,
                ModelIntegrityState.METADATA_STALE -> {
                    val repaired = runCatching {
                        writeMetadata(model, final)
                        metadataMatches(model, final)
                    }.getOrDefault(false)
                    if (repaired) {
                        rememberVerified(model, final)
                        ModelIntegrityState.READY
                    } else {
                        state
                    }
                }

                else -> state
            }
        }
    }

    private suspend fun inspectInstalled(model: WhisperModel, final: File): ModelIntegrityState {
        if (!plausibleSize(model, final.length())) return ModelIntegrityState.BINARY_CORRUPTED

        // Do not use metadata or cached verification as a substitute for the official SHA-256 check.
        val shaMatches = sha256(final).equals(model.sha256, ignoreCase = true)
        if (!shaMatches) return ModelIntegrityState.BINARY_CORRUPTED

        val nativeValid = try {
            binaryValidator.validate(final.absolutePath)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
        if (!nativeValid) return ModelIntegrityState.BINARY_CORRUPTED

        val metadataState = metadataState(model, final)
        return ModelIntegrityDecision.decide(
            finalExists = true,
            sizeMatches = true,
            shaMatches = true,
            nativeValid = true,
            metadataState = metadataState
        )
    }

    suspend fun installVerified(model: WhisperModel, candidate: File) = modelLock(model).withLock {
        withContext(Dispatchers.IO) {
            require(validate(model, candidate, requireMetadata = false)) {
                "ملف النموذج غير صالح أو لا يطابق بيانات النموذج"
            }
            require(binaryValidator.validate(candidate.absolutePath)) {
                "فشل التحقق الأصلي من نموذج Whisper"
            }
            currentCoroutineContext().ensureActive()

            val final = file(model)
            val backup = legacyBackup(model)
            backup.delete()
            val hadInstalledModel = final.isFile
            var candidateMoved = false

            try {
                // Candidate is complete before it is moved to the final path. No byte-copying occurs into final.
                if (hadInstalledModel) moveReplace(final, backup)
                currentCoroutineContext().ensureActive()
                moveReplace(candidate, final)
                candidateMoved = true

                if (!validate(model, final, requireMetadata = false)) {
                    error("تعذر تثبيت النموذج بعد التحقق")
                }
                if (!binaryValidator.validate(final.absolutePath)) {
                    error("فشل التحقق الأصلي بعد تثبيت النموذج")
                }

                writeMetadata(model, final)
                if (!metadataMatches(model, final)) error("بيانات وصف النموذج لا تطابق الملف المثبت")
                rememberVerified(model, final)
                clearPartialMetadata(model)
                backup.delete()
            } catch (ce: CancellationException) {
                rollbackInstall(model, final, backup, hadInstalledModel, candidateMoved)
                throw ce
            } catch (t: Throwable) {
                rollbackInstall(model, final, backup, hadInstalledModel, candidateMoved)
                throw t
            }
        }
    }

    private suspend fun recoverInterruptedReplacement(model: WhisperModel) {
        val final = file(model)
        val backup = legacyBackup(model)
        if (!backup.isFile) return

        val currentFinalValid = final.isFile && binaryIsValid(model, final)
        if (currentFinalValid) {
            backup.delete()
            return
        }

        val backupValid = binaryIsValid(model, backup)
        if (backupValid) {
            final.delete()
            moveReplace(backup, final)
        } else {
            backup.delete()
        }
    }

    private suspend fun binaryIsValid(model: WhisperModel, candidate: File): Boolean {
        if (!candidate.isFile || !plausibleSize(model, candidate.length())) return false
        if (!sha256(candidate).equals(model.sha256, ignoreCase = true)) return false
        return try {
            binaryValidator.validate(candidate.absolutePath)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    private fun sanitizeInterruptedTemporaryFile(model: WhisperModel) {
        val tmp = partial(model)
        if (!tmp.isFile) return
        val resumable = tmp.length() in 1..model.fileSizeBytes && partialMetadataMatches(model)
        if (!resumable) {
            tmp.delete()
            clearPartialMetadata(model)
        }
    }

    private fun rollbackInstall(
        model: WhisperModel,
        final: File,
        backup: File,
        hadInstalledModel: Boolean,
        candidateMoved: Boolean
    ) {
        if (hadInstalledModel && backup.isFile) {
            if (candidateMoved) final.delete()
            moveReplace(backup, final)
        } else if (!hadInstalledModel && candidateMoved) {
            final.delete()
            metadataFile(model).delete()
            integrityPrefs.edit().remove(model.id).apply()
        }
        File(metadataDir, model.id + ".json.tmp").delete()
    }

    private fun modelLock(model: WhisperModel): Mutex = modelLocks.getOrPut(model.id) { Mutex() }

    private fun plausibleSize(model: WhisperModel, actual: Long): Boolean {
        if (actual <= 0L) return false
        return if (model.fileSizeBytes > 0L) actual == model.fileSizeBytes else actual >= MIN_MODEL_BYTES
    }

    private fun metadataState(model: WhisperModel, candidate: File): LocalMetadataState {
        val meta = metadataFile(model)
        if (!meta.isFile || !meta.canRead()) return LocalMetadataState.MISSING
        return if (metadataMatches(model, candidate)) LocalMetadataState.CURRENT else LocalMetadataState.STALE
    }

    private fun metadataMatches(model: WhisperModel, candidate: File): Boolean {
        val meta = metadataFile(model)
        if (!meta.isFile || !meta.canRead()) return false
        return runCatching {
            val json = JSONObject(meta.readText())
            json.optInt("schemaVersion") == METADATA_SCHEMA &&
                json.optString("id") == model.id &&
                json.optString("fileName") == model.fileName &&
                json.optLong("fileSizeBytes") == candidate.length() &&
                json.optString("sha256").takeIf { it.isNotBlank() }.equals(model.sha256, ignoreCase = true) &&
                json.optString("type") == model.type.name
        }.getOrDefault(false)
    }

    private fun writeMetadata(model: WhisperModel, candidate: File) {
        metadataDir.mkdirs()
        val json = JSONObject().apply {
            put("schemaVersion", METADATA_SCHEMA)
            put("id", model.id)
            put("fileName", model.fileName)
            put("fileSizeBytes", candidate.length())
            put("sha256", model.sha256)
            put("type", model.type.name)
            put("validatedAtEpochMs", System.currentTimeMillis())
        }
        val tmp = File(metadataDir, model.id + ".json.tmp")
        tmp.writeText(json.toString())
        moveReplace(tmp, metadataFile(model))
    }

    private fun cachedVerified(model: WhisperModel, candidate: File): Boolean {
        val token = integrityPrefs.getString(model.id, null) ?: return false
        return token == verificationToken(model, candidate)
    }

    private fun rememberVerified(model: WhisperModel, candidate: File) {
        integrityPrefs.edit().putString(model.id, verificationToken(model, candidate)).apply()
    }

    private fun verificationToken(model: WhisperModel, candidate: File) =
        "${candidate.length()}:${candidate.lastModified()}:${model.sha256}:${model.type.name}"

    private fun sameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absolutePath == b.absolutePath)

    private fun moveReplace(source: File, target: File) = fileMover.replace(source, target)

    private fun legacyBackup(model: WhisperModel) = File(whisperDir, model.fileName + ".bak")

    private companion object {
        const val METADATA_SCHEMA = 1
        const val PARTIAL_METADATA_SCHEMA = 1
        const val MIN_MODEL_BYTES = 1L * 1024L * 1024L
    }
}
