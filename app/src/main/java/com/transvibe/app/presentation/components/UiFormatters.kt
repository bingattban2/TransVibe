package com.transvibe.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.transvibe.app.R
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.model.TranslationResourceState

fun ProcessingState.isActiveProcessing(): Boolean =
    this is ProcessingState.Preparing ||
        this is ProcessingState.ExtractingAudio ||
        this is ProcessingState.Recognizing ||
        this is ProcessingState.Translating ||
        this is ProcessingState.Exporting

fun safeExportName(name: String, suffix: String): String =
    name.substringBeforeLast('.', name)
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .ifBlank { "TransVibe" } + suffix

@Composable
fun translationStateLabel(state: TranslationResourceState): String = stringResource(
    when (state) {
        TranslationResourceState.READY -> R.string.translation_state_ready
        TranslationResourceState.MISSING -> R.string.translation_state_missing
        TranslationResourceState.DOWNLOADING -> R.string.translation_state_downloading
        TranslationResourceState.ERROR -> R.string.translation_state_error
        TranslationResourceState.NEEDS_UPDATE -> R.string.translation_state_needs_update
    }
)

@Composable
fun modelIntegrityLabel(state: ModelIntegrityState): String = stringResource(
    when (state) {
        ModelIntegrityState.READY -> R.string.model_state_ready
        ModelIntegrityState.MISSING -> R.string.model_state_missing
        ModelIntegrityState.BINARY_CORRUPTED -> R.string.model_state_corrupted
        ModelIntegrityState.METADATA_MISSING -> R.string.model_state_metadata_missing
        ModelIntegrityState.METADATA_STALE -> R.string.model_state_metadata_stale
        ModelIntegrityState.VERIFYING -> R.string.model_state_verifying
    }
)

fun human(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}

@Composable
fun downloadStatusLabel(status: DownloadProgress.Status): String = stringResource(
    when (status) {
        DownloadProgress.Status.IDLE -> R.string.download_state_idle
        DownloadProgress.Status.DOWNLOADING -> R.string.download_state_downloading
        DownloadProgress.Status.RETRYING -> R.string.download_state_retrying
        DownloadProgress.Status.PAUSED -> R.string.download_state_paused
        DownloadProgress.Status.VERIFYING -> R.string.download_state_verifying
        DownloadProgress.Status.IMPORTING -> R.string.download_state_importing
        DownloadProgress.Status.INSTALLED -> R.string.download_state_installed
        DownloadProgress.Status.FAILED -> R.string.download_state_failed
        DownloadProgress.Status.CANCELLED -> R.string.download_state_cancelled
    }
)
