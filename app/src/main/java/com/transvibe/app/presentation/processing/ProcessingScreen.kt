package com.transvibe.app.presentation.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.error.ProcessingErrorCode
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.presentation.components.isActiveProcessing

@Composable
fun ProcessingScreen(vm: ProcessingViewModel, state: ProcessingState, onPlayer: () -> Unit, onHome: () -> Unit, onModels: () -> Unit) {
    val canResume by vm.canResume.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state is ProcessingState.Completed) onPlayer() }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.processing_status), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ProcessingPanel(state, vm::cancel, onModels)
            if (state is ProcessingState.Error && canResume) {
                Button(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.resume_processing)) }
            }
            when (state) {
                is ProcessingState.Error, ProcessingState.Cancelled -> OutlinedButton(onClick = onHome) { Text(stringResource(R.string.back_home)) }
                else -> Unit
            }
        }
    }
}

@Composable
fun ProcessingPanel(state: ProcessingState, onCancel: () -> Unit, onModels: () -> Unit) {
    val stageText: String? = when (state) {
        ProcessingState.Idle -> null
        is ProcessingState.Preparing -> stringResource(R.string.stage_preparing)
        is ProcessingState.ExtractingAudio -> stringResource(R.string.stage_extracting_audio)
        is ProcessingState.Recognizing -> state.language?.let { stringResource(R.string.stage_recognizing_language, it) }
            ?: stringResource(R.string.stage_recognizing)
        is ProcessingState.Translating -> stringResource(R.string.stage_translating)
        is ProcessingState.Exporting -> stringResource(R.string.stage_exporting)
        is ProcessingState.Completed -> stringResource(R.string.stage_completed)
        is ProcessingState.Error, ProcessingState.Cancelled -> null
    }
    val progress: Float? = when (state) {
        is ProcessingState.Preparing -> state.progress
        is ProcessingState.ExtractingAudio -> state.progress
        is ProcessingState.Recognizing -> state.progress
        is ProcessingState.Translating -> state.progress
        is ProcessingState.Exporting -> state.progress
        is ProcessingState.Completed -> 100f
        else -> null
    }
    when {
        stageText != null && progress != null -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stageText, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${progress.toInt().coerceIn(0, 100)}%")
                if (state.isActiveProcessing()) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_processing)) }
                }
            }
        }
        state is ProcessingState.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.error.userMessage)
                if (state.error.code == ProcessingErrorCode.TRANSLATION_MODEL_MISSING ||
                    state.error.code == ProcessingErrorCode.MODEL_MISSING ||
                    state.error.code == ProcessingErrorCode.MODEL_CORRUPTED
                ) {
                    Button(onClick = onModels) { Text(stringResource(R.string.manage_required_pack)) }
                }
            }
        }
        state is ProcessingState.Cancelled -> Text(stringResource(R.string.processing_cancelled))
        else -> Text(stringResource(R.string.ready_for_processing))
    }
}
