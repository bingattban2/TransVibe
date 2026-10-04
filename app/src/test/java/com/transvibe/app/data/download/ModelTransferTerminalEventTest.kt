package com.transvibe.app.data.download

import com.transvibe.app.domain.model.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTransferTerminalEventTest {
    @Test fun downloadCancellationIsTerminalCancelled() = assertTerminal(
        ModelTransferTerminalEvent.DOWNLOAD_CANCELLED,
        ModelTransferOperation.DOWNLOAD,
        DownloadProgress.Status.CANCELLED,
        "download_cancelled"
    )

    @Test fun importCancellationIsTerminalCancelled() = assertTerminal(
        ModelTransferTerminalEvent.IMPORT_CANCELLED,
        ModelTransferOperation.IMPORT,
        DownloadProgress.Status.CANCELLED,
        "import_cancelled"
    )

    @Test fun downloadFailureIsTerminalFailed() = assertTerminal(
        ModelTransferTerminalEvent.DOWNLOAD_FAILED,
        ModelTransferOperation.DOWNLOAD,
        DownloadProgress.Status.FAILED,
        "download_failed"
    )

    @Test fun importFailureIsTerminalFailed() = assertTerminal(
        ModelTransferTerminalEvent.IMPORT_FAILED,
        ModelTransferOperation.IMPORT,
        DownloadProgress.Status.FAILED,
        "import_failed"
    )

    @Test fun downloadVerificationFailureIsTerminalFailed() = assertTerminal(
        ModelTransferTerminalEvent.VERIFICATION_FAILED,
        ModelTransferOperation.DOWNLOAD,
        DownloadProgress.Status.FAILED,
        "verification_failed"
    )

    @Test fun importVerificationFailureIsTerminalFailed() = assertTerminal(
        ModelTransferTerminalEvent.IMPORT_VERIFICATION_FAILED,
        ModelTransferOperation.IMPORT,
        DownloadProgress.Status.FAILED,
        "import_verification_failed"
    )

    @Test fun downloadInstallationFailureIsTerminalFailed() = assertTerminal(
        ModelTransferTerminalEvent.INSTALLATION_FAILED,
        ModelTransferOperation.DOWNLOAD,
        DownloadProgress.Status.FAILED,
        "installation_failed"
    )

    @Test fun importInstallationFailureIsTerminalFailed() = assertTerminal(
        ModelTransferTerminalEvent.IMPORT_INSTALLATION_FAILED,
        ModelTransferOperation.IMPORT,
        DownloadProgress.Status.FAILED,
        "import_installation_failed"
    )

    @Test fun successfulDownloadIsInstalled() = assertTerminal(
        ModelTransferTerminalEvent.DOWNLOAD_SUCCEEDED,
        ModelTransferOperation.DOWNLOAD,
        DownloadProgress.Status.INSTALLED,
        "download_succeeded"
    )

    @Test fun successfulImportIsInstalled() = assertTerminal(
        ModelTransferTerminalEvent.IMPORT_SUCCEEDED,
        ModelTransferOperation.IMPORT,
        DownloadProgress.Status.INSTALLED,
        "import_succeeded"
    )

    private fun assertTerminal(
        event: ModelTransferTerminalEvent,
        operation: ModelTransferOperation,
        status: DownloadProgress.Status,
        persistentCode: String
    ) {
        assertEquals(operation, event.operation)
        assertEquals(status, event.progressStatus)
        assertEquals(persistentCode, event.persistentCode)
    }
}
