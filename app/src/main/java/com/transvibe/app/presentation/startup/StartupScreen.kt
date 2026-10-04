package com.transvibe.app.presentation.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.StartupState
import com.transvibe.app.presentation.models.ModelsViewModel
import kotlinx.coroutines.delay

private const val MINIMUM_SPLASH_DURATION_MS = 1_200L

@Composable
fun StartupScreen(vm: ModelsViewModel, onContinue: () -> Unit) {
    val resource by vm.resources.collectAsStateWithLifecycle()
    val startup by vm.startupState.collectAsStateWithLifecycle()
    val modelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val navigationGate = remember { StartupNavigationGate() }

    LaunchedEffect(Unit) {
        delay(MINIMUM_SPLASH_DURATION_MS)
        if (navigationGate.onSplashCompleted()) onContinue()
    }

    LaunchedEffect(startup) {
        if (startup is StartupState.Resolved && navigationGate.onResourceInspectionCompleted()) onContinue()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (startup is StartupState.Resolved) Icons.Rounded.CheckCircle else Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.startup_resource_check),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (startup !is StartupState.Resolved) {
                    CircularProgressIndicator(strokeWidth = 4.dp)
                    Text(
                        startupLabel(startup),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    val whisperReady = resource.whisperReadyFor(modelId)
                    Text(
                        stringResource(if (whisperReady) R.string.startup_whisper_ready else R.string.startup_whisper_not_ready),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        stringResource(R.string.startup_required_resources_checked),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun startupLabel(state: StartupState): String = stringResource(
    when (state) {
        StartupState.InitializingRepositories -> R.string.startup_initializing
        StartupState.InspectingSelectedModel -> R.string.startup_inspecting_model
        StartupState.ValidatingSelectedWhisperFile -> R.string.startup_validating_model
        StartupState.DeterminingSelectedResourceState -> R.string.startup_determining_state
        is StartupState.Resolved -> R.string.startup_check_complete
    }
)
