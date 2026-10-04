package com.transvibe.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val sessions: SessionRepository,
    private val processing: ProcessingRepository
) : ViewModel() {
    val history: StateFlow<List<ProcessingHistorySummary>> = sessions.observeHistory().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun openSession(jobId: String, onOpened: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        if (sessions.getSession(jobId) == null) {
            _message.value = "تعذر تحميل الجلسة المحفوظة."
            return@launch
        }
        processing.selectCurrent(jobId)
        launch(Dispatchers.Main) { onOpened() }
    }
    fun dismissMessage() { _message.value = null }
}
