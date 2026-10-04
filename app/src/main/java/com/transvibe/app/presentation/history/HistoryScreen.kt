package com.transvibe.app.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.presentation.components.EmptyStateIllustration
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.SectionHeader
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpen: (ProcessingHistorySummary) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()

    if (history.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader(stringResource(R.string.history_title), stringResource(R.string.history_subtitle))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyStateIllustration(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.history_empty_title),
                    subtitle = stringResource(R.string.history_empty_subtitle)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionHeader(stringResource(R.string.history_title), stringResource(R.string.history_subtitle)) }
        items(history, key = { it.jobId }) { s ->
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        s.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.history_model_language, s.modelId, s.detectedLanguage),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.history_state, jobStateLabel(s.state)),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(if (s.sourceUriPersistable) R.string.video_permission_saved else R.string.video_permission_not_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(s.createdAtEpochMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (s.state == ProcessingJobState.COMPLETED) {
                        TextButton(onClick = { onOpen(s) }) { Text(stringResource(R.string.open_session)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun jobStateLabel(state: ProcessingJobState): String = stringResource(
    when (state) {
        ProcessingJobState.COMPLETED -> R.string.state_completed
        ProcessingJobState.FAILED -> R.string.state_failed
        ProcessingJobState.CANCELLED -> R.string.state_cancelled
        ProcessingJobState.INTERRUPTED -> R.string.state_interrupted
        ProcessingJobState.RUNNING -> R.string.state_running
        ProcessingJobState.QUEUED -> R.string.state_queued
    }
)
