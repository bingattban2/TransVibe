package com.transvibe.app.presentation.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlayerViewModel(
    processing: com.transvibe.app.domain.repository.ProcessingRepository,
    settings: com.transvibe.app.domain.repository.SettingsRepository,
    private val playerRepository: com.transvibe.app.domain.repository.PlayerGateway
) : ViewModel() {
    val processingState: StateFlow<ProcessingState> = processing.currentState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState.Idle
    )
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )
    private val _sourceAccess = MutableStateFlow<SourceAccessState?>(null)
    val sourceAccess: StateFlow<SourceAccessState?> = _sourceAccess.asStateFlow()
    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    fun checkSourceAccess(session: ProcessingSession) = viewModelScope.launch(Dispatchers.IO) {
        _sourceAccess.value = playerRepository.checkSourceAccess(session)
    }
    fun exportSrt(session: ProcessingSession, destination: Uri) = export(session, destination, false)
    fun exportWebVtt(session: ProcessingSession, destination: Uri) = export(session, destination, true)
    private fun export(session: ProcessingSession, destination: Uri, webVtt: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        _exportMessage.value = playerRepository.export(session, destination.toString(), webVtt)
    }
}
