package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class QRScannerActivityState(
    val isLoading: Boolean = false
)

sealed interface QRScannerActivityAction {
    data object LoadData : QRScannerActivityAction
}

class QRScannerActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(QRScannerActivityState())
    val state: StateFlow<QRScannerActivityState> = _state.asStateFlow()

    private val _analysisExecutor = Executors.newSingleThreadExecutor()
    val analysisExecutor: Executor = _analysisExecutor

    override fun onCleared() {
        _analysisExecutor.shutdown()
    }

    fun onAction(action: QRScannerActivityAction) {
        when (action) {
            QRScannerActivityAction.LoadData -> loadData()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
        }
    }
}
