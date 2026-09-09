package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncthingActivityState(
    val isLoading: Boolean = false
)

sealed interface SyncthingActivityAction {
    data object LoadData : SyncthingActivityAction
}

class SyncthingActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(SyncthingActivityState())
    val state: StateFlow<SyncthingActivityState> = _state.asStateFlow()

    fun onAction(action: SyncthingActivityAction) {
        when (action) {
            SyncthingActivityAction.LoadData -> loadData()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
        }
    }
}
