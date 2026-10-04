package com.transvibe.app.presentation.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.transvibe.app.R
import com.transvibe.app.data.subtitle.SubtitleTimeline
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.presentation.components.safeExportName
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(vm: PlayerViewModel, onAnother: () -> Unit) {
    val state by vm.processingState.collectAsStateWithLifecycle()
    val session = (state as? ProcessingState.Completed)?.result?.session
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.player_no_result)) }
        return
    }
    PlayerAndExport(vm, session, onAnother)
}

@Composable
fun PlayerAndExport(vm: PlayerViewModel, session: ProcessingSession, onAnother: () -> Unit) {
    val context = LocalContext.current
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val sourceAccess by vm.sourceAccess.collectAsStateWithLifecycle()
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()
    val sourceAccessible = sourceAccess?.takeIf { it.sessionId == session.id }?.accessible
    LaunchedEffect(session.id) { vm.checkSourceAccess(session) }

    val player = remember(session.id, sourceAccessible) {
        if (sourceAccessible == true) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(session.videoUri))
                prepare()
            }
        } else null
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    val timeline = remember(session.id) { SubtitleTimeline(session.segments) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (player != null) {
            pos = player.currentPosition
            delay(120)
        }
    }
    val active = remember(pos, timeline) { timeline.at(pos) }
    val activeText = active?.translatedText ?: active?.englishText ?: active?.originalText.orEmpty()
    val fullText = remember(session.segments) {
        session.segments.joinToString("\n\n") { it.translatedText ?: it.englishText ?: it.originalText }
    }

    val srtExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) vm.exportSrt(session, uri)
    }
    val vttExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
        if (uri != null) vm.exportWebVtt(session, uri)
    }
    val languageSuffix = ".${session.targetLanguage.ifBlank { "sub" }}"

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(session.videoName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            when (sourceAccessible) {
                null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                false -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(stringResource(R.string.player_source_unavailable), Modifier.padding(14.dp))
                }
                true -> player?.let { p ->
                    Card {
                        Box(Modifier.fillMaxWidth().height(230.dp)) {
                            AndroidView(
                                factory = { PlayerView(it).apply { this.player = p; keepScreenOn = prefs.keepScreenOnDuringPlayback } },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (activeText.isNotBlank()) {
                                Surface(
                                    Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.scrim.copy(alpha = .75f)
                                ) {
                                    SubtitleDirectedText(
                                        text = activeText,
                                        modifier = Modifier.padding(10.dp),
                                        fontSizeSp = 18f * prefs.subtitleTextScale
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (prefs.preferredExportFormat == "vtt") {
                    Button(onClick = { vttExporter.launch(safeExportName(session.videoName, "$languageSuffix.vtt")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_webvtt)) }
                    OutlinedButton(onClick = { srtExporter.launch(safeExportName(session.videoName, "$languageSuffix.srt")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_srt)) }
                } else {
                    Button(onClick = { srtExporter.launch(safeExportName(session.videoName, "$languageSuffix.srt")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_srt)) }
                    OutlinedButton(onClick = { vttExporter.launch(safeExportName(session.videoName, "$languageSuffix.vtt")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_webvtt)) }
                }
            }
        }
        exportMessage?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.translated_text), fontWeight = FontWeight.Bold)
                    CompositionLocalProvider(LocalLayoutDirection provides contentDirection(fullText)) {
                        SelectionContainer {
                            Text(fullText, textAlign = TextAlign.Start)
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onAnother, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.process_another_video)) } }
    }
}

@Composable
private fun SubtitleDirectedText(text: String, modifier: Modifier, fontSizeSp: Float) {
    CompositionLocalProvider(LocalLayoutDirection provides contentDirection(text)) {
        Text(
            text = text,
            modifier = modifier,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            textAlign = TextAlign.Center,
            fontSize = fontSizeSp.sp
        )
    }
}

private fun contentDirection(text: String): LayoutDirection {
    text.forEach { ch ->
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return LayoutDirection.Ltr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return LayoutDirection.Rtl
        }
    }
    return LayoutDirection.Ltr
}
