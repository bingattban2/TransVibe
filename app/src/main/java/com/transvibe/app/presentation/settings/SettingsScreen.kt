package com.transvibe.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.ModelCatalog
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.MetadataBadge
import com.transvibe.app.presentation.components.SectionHeader
import com.transvibe.app.presentation.components.human

@Composable
fun SettingsScreen(vm: SettingsViewModel, onModels: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val storage by vm.storageInfo.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    var subtitleScale by remember(prefs.subtitleTextScale) { mutableFloatStateOf(prefs.subtitleTextScale) }

    LaunchedEffect(Unit) { vm.refreshStorageInfo() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle)) }
        item {
            SettingsCard(title = stringResource(R.string.application_language), icon = Icons.Rounded.Language) {
                SegmentedChoices(
                    values = listOf("system", "ar", "en"),
                    labels = listOf(
                        stringResource(R.string.language_system),
                        stringResource(R.string.language_arabic),
                        stringResource(R.string.language_english)
                    ),
                    selected = prefs.applicationLanguage,
                    onSelected = vm::setApplicationLanguage
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.appearance), icon = Icons.Rounded.Palette) {
                SegmentedChoices(
                    values = listOf("system", "light", "dark"),
                    labels = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark)
                    ),
                    selected = prefs.themeMode,
                    onSelected = vm::setThemeMode
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.processing_mode), icon = Icons.Rounded.Bolt) {
                SegmentedChoices(
                    values = listOf("FAST", "BALANCED", "PRESERVE_ORIGINAL"),
                    labels = listOf(
                        stringResource(R.string.mode_fast),
                        stringResource(R.string.mode_balanced),
                        stringResource(R.string.mode_preserve_original)
                    ),
                    selected = prefs.processingProfile,
                    onSelected = vm::setProcessingProfile
                )
                Text(
                    text = stringResource(
                        when (prefs.processingProfile) {
                            "FAST" -> R.string.mode_cost_fast
                            "PRESERVE_ORIGINAL" -> R.string.mode_cost_preserve_original
                            else -> R.string.mode_cost_balanced
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.video_speech_language), icon = Icons.Rounded.Translate) {
                val sourceMode = when {
                    prefs.videoSpeechLanguage == "auto_adaptive" -> "AUTO_ADAPTIVE"
                    prefs.videoSpeechLanguage.startsWith("manual:") -> "MANUAL"
                    else -> "AUTO_VIDEO"
                }
                Text(
                    stringResource(R.string.source_language_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SegmentedChoices(
                    values = listOf("MANUAL", "AUTO_VIDEO", "AUTO_ADAPTIVE"),
                    labels = listOf(
                        stringResource(R.string.source_mode_manual),
                        stringResource(R.string.source_mode_auto_video),
                        stringResource(R.string.source_mode_auto_adaptive)
                    ),
                    selected = sourceMode,
                    onSelected = { selected ->
                        when (selected) {
                            "MANUAL" -> vm.setVideoSpeechLanguage(
                                if (prefs.videoSpeechLanguage == "manual:en") "manual:en" else "manual:ar"
                            )
                            "AUTO_ADAPTIVE" -> vm.setVideoSpeechLanguage("auto_adaptive")
                            else -> vm.setVideoSpeechLanguage("auto_video")
                        }
                    }
                )
                if (sourceMode == "MANUAL") {
                    SegmentedChoices(
                        values = listOf("manual:ar", "manual:en"),
                        labels = listOf(
                            stringResource(R.string.language_arabic),
                            stringResource(R.string.language_english)
                        ),
                        selected = prefs.videoSpeechLanguage,
                        onSelected = vm::setVideoSpeechLanguage
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Text(
                    stringResource(R.string.subtitle_target_language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SegmentedChoices(
                    values = listOf("ar", "en"),
                    labels = listOf(stringResource(R.string.language_arabic), stringResource(R.string.language_english)),
                    selected = prefs.subtitleTargetLanguage,
                    onSelected = vm::setSubtitleTargetLanguage
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.translation_downloads), icon = Icons.Rounded.Folder) {
                val checked = prefs.wifiOnlyTranslationDownloads
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Switch) { vm.setWifiOnlyTranslationDownloads(!checked) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.wifi_only_downloads),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.wifi_only_downloads_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = vm::setWifiOnlyTranslationDownloads
                    )
                }
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.subtitle_appearance), icon = Icons.Rounded.Subtitles) {
                SubtitlePreview(scale = subtitleScale)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.text_size_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${(subtitleScale * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Slider(
                    value = subtitleScale,
                    onValueChange = { subtitleScale = it },
                    onValueChangeFinished = { vm.setSubtitleTextScale(subtitleScale) },
                    valueRange = 0.85f..1.45f,
                    steps = 5
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.keep_screen_on),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(checked = prefs.keepScreenOnDuringPlayback, onCheckedChange = vm::setKeepScreenOnDuringPlayback)
                }
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.preferred_export_format), icon = Icons.Rounded.Save) {
                SegmentedChoices(
                    values = listOf("srt", "vtt"),
                    labels = listOf(stringResource(R.string.format_srt), stringResource(R.string.format_webvtt)),
                    selected = prefs.preferredExportFormat,
                    onSelected = vm::setPreferredExportFormat
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.default_model), icon = Icons.Rounded.Psychology) {
                val model = ModelCatalog.byId(selectedModelId) ?: ModelCatalog.recommended
                MetadataBadge(icon = Icons.Rounded.Memory, text = model.displayName, highlighted = true)
                Text(
                    stringResource(if (model.id == "tiny") R.string.model_desc_tiny else R.string.model_desc_base),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.model_memory_format,
                        human(model.fileSizeBytes),
                        model.estimatedRamBytes?.let(::human) ?: stringResource(R.string.not_specified)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Memory, contentDescription = null)
                    Text(stringResource(R.string.manage_models), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.storage), icon = Icons.Rounded.Storage) {
                StorageRow(stringResource(R.string.storage_ai_models_label), formatBytes(storage.modelBytes), Icons.Rounded.Psychology)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                StorageRow(stringResource(R.string.storage_sessions_label), formatBytes(storage.sessionBytes), Icons.Rounded.ClosedCaption)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                StorageRow(stringResource(R.string.storage_available_label), formatBytes(storage.freeBytes), Icons.Rounded.Storage)
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.about), icon = Icons.Rounded.Info) {
                Text(
                    stringResource(R.string.app_version_format, vm.appVersion),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.about_local_processing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SubtitlePreview(scale: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = stringResource(R.string.subtitle_preview_sample),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = (18f * scale).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = (25f * scale).sp
        )
    }
}

@Composable
private fun StorageRow(label: String, value: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SegmentedChoices(
    values: List<String>,
    labels: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        values.forEachIndexed { index, value ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index, values.size),
                icon = {
                    SegmentedButtonDefaults.Icon(active = selected == value)
                }
            ) {
                Text(
                    labels[index],
                    fontWeight = if (selected == value) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
