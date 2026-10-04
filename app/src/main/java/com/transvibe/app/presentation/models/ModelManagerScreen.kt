package com.transvibe.app.presentation.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ModelCatalog
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.domain.model.TranslationResourceState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.MetadataBadge
import com.transvibe.app.presentation.components.SectionHeader
import com.transvibe.app.presentation.components.downloadStatusLabel
import com.transvibe.app.presentation.components.human
import com.transvibe.app.presentation.components.modelIntegrityLabel
import com.transvibe.app.presentation.components.translationStateLabel

@Composable
fun ModelManagerScreen(vm: ModelsViewModel) {
    val resource by vm.resources.collectAsStateWithLifecycle()
    val packStorage by vm.translationPackStorage.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionHeader(stringResource(R.string.models_title), stringResource(R.string.models_subtitle)) }
        items(ModelCatalog.whisperModels, key = { it.id }) { model ->
            ModelCard(vm, model, resource.modelState(model.id))
        }
        item { SectionHeader(stringResource(R.string.translation_packs), stringResource(R.string.translation_packs_subtitle)) }
        item {
            Text(
                stringResource(R.string.pack_storage, packStorage.installedPackCount, human(packStorage.approximateBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { TranslationPackCard(vm, LanguageCode.ENGLISH, stringResource(R.string.language_english)) }
        item { TranslationPackCard(vm, LanguageCode.ARABIC, stringResource(R.string.language_arabic)) }
    }
}

@Composable
private fun ModelCard(vm: ModelsViewModel, model: WhisperModel, integrity: ModelIntegrityState) {
    val ds by vm.downloadState(model).collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importModel(model, it) }
    }
    val active = ds.status in setOf(
        DownloadProgress.Status.DOWNLOADING,
        DownloadProgress.Status.RETRYING,
        DownloadProgress.Status.IMPORTING,
        DownloadProgress.Status.VERIFYING
    )
    val hasProgress = ds.totalBytes > 0 && (active || ds.status == DownloadProgress.Status.PAUSED)
    val visibleStatus = if (active || ds.status == DownloadProgress.Status.PAUSED) {
        downloadStatusLabel(ds.status)
    } else {
        modelIntegrityLabel(integrity)
    }
    val installed = integrity == ModelIntegrityState.READY

    GlassCard {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (model.recommended) {
                        Text(
                            stringResource(R.string.recommended),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                ModelInstallBadge(installed = installed, status = visibleStatus)
            }

            Text(
                modelDescription(model),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetadataBadge(Icons.Rounded.Storage, human(model.fileSizeBytes))
                MetadataBadge(
                    Icons.Rounded.Memory,
                    human(model.estimatedRamBytes ?: 0L) + " " + stringResource(R.string.ram_suffix)
                )
            }

            if (hasProgress) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    LinearProgressIndicator(
                        progress = { ds.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${ds.percent.coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${human(ds.downloadedBytes)} / ${human(ds.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        when (ds.status) {
                            DownloadProgress.Status.DOWNLOADING, DownloadProgress.Status.RETRYING -> vm.pause(model)
                            DownloadProgress.Status.PAUSED -> vm.download(model)
                            else -> vm.download(model)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Text(
                        stringResource(if (ds.status == DownloadProgress.Status.PAUSED) R.string.resume else R.string.download),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                FilledTonalButton(
                    onClick = { importer.launch(arrayOf("application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Text(stringResource(R.string.import_action), modifier = Modifier.padding(start = 6.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when {
                    integrity == ModelIntegrityState.READY -> OutlinedButton(
                        onClick = { vm.delete(model) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.delete)) }

                    ds.status == DownloadProgress.Status.FAILED -> Button(
                        onClick = { vm.retryDownload(model) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.retry)) }

                    ds.status == DownloadProgress.Status.DOWNLOADING || ds.status == DownloadProgress.Status.RETRYING -> OutlinedButton(
                        onClick = { vm.cancelDownload(model) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.cancel)) }

                    integrity == ModelIntegrityState.METADATA_MISSING ||
                        integrity == ModelIntegrityState.METADATA_STALE ||
                        integrity == ModelIntegrityState.VERIFYING -> OutlinedButton(
                        onClick = vm::refreshResources,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.recheck)) }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ModelInstallBadge(installed: Boolean, status: String) {
    val container = if (installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (installed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content)
    }
}

@Composable
private fun TranslationPackCard(vm: ModelsViewModel, language: LanguageCode, languageLabel: String) {
    val state by vm.translationStatus(language).collectAsStateWithLifecycle()
    val busy by vm.translationBusy.collectAsStateWithLifecycle()
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.translation_pack_language, languageLabel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        translationStateLabel(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MetadataBadge(Icons.Rounded.CloudDownload, translationStateLabel(state))
            }
            Text(
                stringResource(
                    when {
                        language == LanguageCode.ENGLISH -> R.string.pack_built_in
                        state == TranslationResourceState.READY -> R.string.pack_ready
                        state == TranslationResourceState.MISSING -> R.string.pack_missing
                        state == TranslationResourceState.DOWNLOADING -> R.string.pack_downloading
                        state == TranslationResourceState.ERROR -> R.string.pack_error
                        else -> R.string.pack_needs_update
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state == TranslationResourceState.DOWNLOADING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (language == LanguageCode.ENGLISH) {
                Unit
            } else if (state == TranslationResourceState.READY) {
                OutlinedButton(
                    onClick = { vm.removeTranslationPack(language) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.remove_pack)) }
            } else {
                Button(
                    onClick = { vm.downloadTranslationPack(language) },
                    enabled = !busy && state != TranslationResourceState.DOWNLOADING,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(if (state == TranslationResourceState.ERROR) R.string.retry else R.string.download_pack)) }
            }
        }
    }
}

@Composable
private fun modelDescription(model: WhisperModel): String = model.descriptionAr
