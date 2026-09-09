package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThemedAppCompatActivityState(
    val isLoading: Boolean = false
)

sealed interface ThemedAppCompatActivityAction {
    data object LoadData : ThemedAppCompatActivityAction
}

class ThemedAppCompatActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(ThemedAppCompatActivityState())
    val state: StateFlow<ThemedAppCompatActivityState> = _state.asStateFlow()

    fun onAction(action: ThemedAppCompatActivityAction) {
        when (action) {
            ThemedAppCompatActivityAction.LoadData -> loadData()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
        }
    }
}
