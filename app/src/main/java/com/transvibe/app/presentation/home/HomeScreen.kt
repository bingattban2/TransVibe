package com.transvibe.app.presentation.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.AiCapability
import com.transvibe.app.domain.model.AiCapabilityState
import com.transvibe.app.domain.model.AiResourceSnapshot
import com.transvibe.app.domain.model.ModelCatalog
import com.transvibe.app.domain.model.ProcessingCapabilityPlan
import com.transvibe.app.domain.model.ProcessingCapabilityPlanner
import com.transvibe.app.domain.model.ProcessingResourceReadiness
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.MetadataBadge
import com.transvibe.app.presentation.components.SectionHeader
import com.transvibe.app.presentation.components.human
import com.transvibe.app.presentation.components.isActiveProcessing
import com.transvibe.app.presentation.components.modelIntegrityLabel
import com.transvibe.app.presentation.processing.ProcessingPanel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    process: ProcessingState,
    onCancelProcessing: () -> Unit,
    onModels: () -> Unit,
    onProcessing: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val video by vm.selectedVideo.collectAsStateWithLifecycle()
    val modelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val resource by vm.resources.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val request by vm.resourceRequest.collectAsStateWithLifecycle()
    val pipelineReadiness by vm.pipelineReadiness.collectAsStateWithLifecycle()
    val preflightBusy by vm.preflightBusy.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::selectVideo) }
    val startProcessing = remember(vm, onProcessing) { { vm.startProcessing(onProcessing) } }
    val permissionDeniedMessage = stringResource(R.string.notification_permission_denied)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, permissionDeniedMessage, Toast.LENGTH_LONG).show()
        startProcessing()
    }
    val busy = process.isActiveProcessing()
    val selectedModel = ModelCatalog.byId(modelId) ?: ModelCatalog.recommended
    val plan = ProcessingCapabilityPlanner.plan(request)

    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.processingReady.collect {
                val requiresNotificationPermission =
                    Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (requiresNotificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startProcessing()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionHeader(stringResource(R.string.home_title), stringResource(R.string.home_subtitle)) }
        item {
            GlassCard {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.VideoFile,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            stringResource(R.string.video_file),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            video?.displayName ?: stringResource(R.string.no_video_selected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (video == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (video != null) {
                        Text(
                            stringResource(
                                if (video?.persistableAccess == true) R.string.video_access_persisted
                                else R.string.video_access_not_persisted
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { picker.launch(arrayOf("video/*")) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Icon(Icons.Rounded.VideoFile, contentDescription = null)
                        Text(
                            stringResource(R.string.choose_video),
                            modifier = Modifier.padding(start = 8.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(stringResource(R.string.resources_and_model), stringResource(R.string.resources_and_model_subtitle))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetadataBadge(Icons.Rounded.Memory, selectedModel.displayName, highlighted = true)
                        MetadataBadge(Icons.Rounded.Memory, human(selectedModel.estimatedRamBytes ?: 0L) + " " + stringResource(R.string.ram_suffix))
                        MetadataBadge(Icons.Rounded.ArrowOutward, modelIntegrityLabel(resource.modelState(modelId)))
                    }
                    CapabilityStatus(stringResource(R.string.cap_speech_recognition), AiCapability.SPEECH_RECOGNITION, resource, plan)
                    CapabilityStatus(stringResource(R.string.cap_speech_to_english), AiCapability.SPEECH_TO_ENGLISH_TRANSLATION, resource, plan)
                    CapabilityStatus(stringResource(R.string.cap_target_translation), AiCapability.TARGET_LANGUAGE_TRANSLATION, resource, plan)
                    CapabilityStatus(stringResource(R.string.cap_vad), AiCapability.VAD, resource, plan)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.speech_language_auto),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(checked = !prefs.videoSpeechLanguage.startsWith("manual:"), onCheckedChange = {}, enabled = false)
                    }
                    OutlinedButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.manage_models))
                    }
                }
            }
        }
        if (pipelineReadiness is ProcessingResourceReadiness.Waiting) {
            item {
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.resource_missing_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(stringResource(R.string.resource_missing_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.open_model_manager))
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { if (video != null) vm.requestProcessing() },
                enabled = video != null && !busy && pipelineReadiness is ProcessingResourceReadiness.Ready && !preflightBusy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(if (preflightBusy) R.string.checking else R.string.process_video), fontWeight = FontWeight.Bold)
            }
        }
        if (process !is ProcessingState.Idle) {
            item { ProcessingPanel(process, onCancelProcessing, onModels) }
        }
    }
}

@Composable
private fun capabilityStateLabel(state: AiCapabilityState): String = stringResource(
    when (state) {
        AiCapabilityState.Ready -> R.string.cap_state_ready
        AiCapabilityState.Checking -> R.string.cap_state_checking
        is AiCapabilityState.Missing -> R.string.cap_state_missing
        is AiCapabilityState.Error -> R.string.cap_state_error
        AiCapabilityState.NotConfigured -> R.string.cap_state_not_configured
    }
)

@Composable
private fun CapabilityStatus(
    label: String,
    capability: AiCapability,
    resource: AiResourceSnapshot,
    plan: ProcessingCapabilityPlan
) {
    val required = capability in plan.required
    val conditional = capability in plan.conditional
    val chipLabel = stringResource(
        when {
            required -> R.string.cap_required
            conditional -> R.string.cap_conditional
            else -> R.string.cap_optional
        }
    )
    val chipContainer = when {
        required -> MaterialTheme.colorScheme.errorContainer
        conditional -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val chipContent = when {
        required -> MaterialTheme.colorScheme.onErrorContainer
        conditional -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val state = resource.capabilities.state(capability)
    val icon = when (capability) {
        AiCapability.SPEECH_RECOGNITION -> Icons.Rounded.GraphicEq
        AiCapability.SPEECH_TO_ENGLISH_TRANSLATION,
        AiCapability.TARGET_LANGUAGE_TRANSLATION -> Icons.Rounded.Translate
        AiCapability.VAD -> Icons.Rounded.GraphicEq
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                capabilityStateLabel(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(chipContainer)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                chipLabel,
                color = chipContent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
