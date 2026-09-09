package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val PULL_ORDER_TYPES = listOf(
    "random",
    "alphabetic",
    "smallestFirst",
    "largestFirst",
    "oldestFirst",
    "newestFirst"
)

data class PullOrderDialogActivityState(
    val selectedType: String = PULL_ORDER_TYPES.first()
)

sealed interface PullOrderDialogActivityAction {
    data class SelectPullOrderType(val type: String) : PullOrderDialogActivityAction
}

class PullOrderDialogActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(PullOrderDialogActivityState())
    val state: StateFlow<PullOrderDialogActivityState> = _state.asStateFlow()

    fun setInitialArguments(initialType: String?) {
        val type = initialType?.takeIf { it in PULL_ORDER_TYPES } ?: PULL_ORDER_TYPES.first()
        _state.update { it.copy(selectedType = type) }
    }

    fun onAction(action: PullOrderDialogActivityAction) {
        when (action) {
            is PullOrderDialogActivityAction.SelectPullOrderType -> selectPullOrderType(action.type)
        }
    }

    private fun selectPullOrderType(type: String) {
        if (type !in PULL_ORDER_TYPES) {
            return
        }
        _state.update { it.copy(selectedType = type) }
    }
}
