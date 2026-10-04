package com.transvibe.app.presentation.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.repository.ProcessingRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProcessingViewModel(
    repository: ProcessingRepository,
    private val processingTasks: ProcessingTaskGateway
) : ViewModel() {
    val state: StateFlow<ProcessingState> = repository.currentState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState.Idle
    )
    val canResume: StateFlow<Boolean> = repository.canResumeCurrent.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun cancel() = processingTasks.cancel()
    fun resume() = viewModelScope.launch {
        val result = processingTasks.resume()
        if (result.isFailure) _message.value = if (result.exceptionOrNull()?.message == "NO_INTERRUPTED_JOB")
            "لا توجد مهمة منقطعة قابلة للاستئناف." else "تعذر استئناف مهمة المعالجة."
    }
    fun clearCurrent() = viewModelScope.launch { processingTasks.clearCurrent() }
    fun dismissMessage() { _message.value = null }
}
