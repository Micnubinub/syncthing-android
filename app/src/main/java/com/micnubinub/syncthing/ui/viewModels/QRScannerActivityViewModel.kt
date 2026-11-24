package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

data class QRScannerActivityState(
    val isLoading: Boolean = false
)

sealed interface QRScannerActivityAction {
    data object LoadData : QRScannerActivityAction
}

class QRScannerActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(QRScannerActivityState())
    val state: StateFlow<QRScannerActivityState> = _state.asStateFlow()

    /**
     * CameraX takes an [Executor]. Frames are analysed one at a time on a single-lane view
     * of [Dispatchers.Default], so there is no thread to own or shut down.
     */
    val analysisExecutor: Executor = Dispatchers.Default.limitedParallelism(1).asExecutor()

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
