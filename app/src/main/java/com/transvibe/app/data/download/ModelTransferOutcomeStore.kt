package com.transvibe.app.data.download

import android.content.Context
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.WhisperModel

internal enum class ModelTransferOperation { DOWNLOAD, IMPORT }

internal enum class ModelTransferTerminalEvent(
    val operation: ModelTransferOperation,
    val progressStatus: DownloadProgress.Status,
    val persistentCode: String
) {
    DOWNLOAD_SUCCEEDED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.INSTALLED, "download_succeeded"),
    DOWNLOAD_CANCELLED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.CANCELLED, "download_cancelled"),
    DOWNLOAD_FAILED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.FAILED, "download_failed"),
    IMPORT_SUCCEEDED(ModelTransferOperation.IMPORT, DownloadProgress.Status.INSTALLED, "import_succeeded"),
    IMPORT_CANCELLED(ModelTransferOperation.IMPORT, DownloadProgress.Status.CANCELLED, "import_cancelled"),
    IMPORT_FAILED(ModelTransferOperation.IMPORT, DownloadProgress.Status.FAILED, "import_failed"),
    VERIFICATION_FAILED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.FAILED, "verification_failed"),
    IMPORT_VERIFICATION_FAILED(ModelTransferOperation.IMPORT, DownloadProgress.Status.FAILED, "import_verification_failed"),
    INSTALLATION_FAILED(ModelTransferOperation.DOWNLOAD, DownloadProgress.Status.FAILED, "installation_failed"),
    IMPORT_INSTALLATION_FAILED(ModelTransferOperation.IMPORT, DownloadProgress.Status.FAILED, "import_installation_failed")
}

internal class ModelTransferOutcomeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(model: WhisperModel, event: ModelTransferTerminalEvent, message: String?) {
        prefs.edit()
            .putString(key(model.id, "event"), event.persistentCode)
            .putString(key(model.id, "operation"), event.operation.name)
            .putString(key(model.id, "message"), message)
            .putLong(key(model.id, "timestamp"), System.currentTimeMillis())
            .apply()
    }

    fun restoredProgress(model: WhisperModel): DownloadProgress? {
        val code = prefs.getString(key(model.id, "event"), null) ?: return null
        val event = ModelTransferTerminalEvent.entries.firstOrNull { it.persistentCode == code } ?: return null
        return DownloadProgress(
            modelId = model.id,
            status = event.progressStatus,
            message = prefs.getString(key(model.id, "message"), null)
        )
    }

    fun clear(model: WhisperModel) {
        prefs.edit()
            .remove(key(model.id, "event"))
            .remove(key(model.id, "operation"))
            .remove(key(model.id, "message"))
            .remove(key(model.id, "timestamp"))
            .apply()
    }

    private fun key(modelId: String, suffix: String) = "$modelId.$suffix"

    private companion object {
        const val PREFS_NAME = "model_transfer_outcomes"
    }
}
