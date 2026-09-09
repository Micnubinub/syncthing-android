package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import com.micnubinub.syncthing.service.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val FOLDER_TYPES = listOf(
    Constants.FOLDER_TYPE_SEND_RECEIVE,
    Constants.FOLDER_TYPE_SEND_ONLY,
    Constants.FOLDER_TYPE_RECEIVE_ONLY,
    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
)

data class FolderTypeDialogActivityState(
    val selectedType: String = FOLDER_TYPES.first()
)

sealed interface FolderTypeDialogActivityAction {
    data class SelectFolderType(val type: String) : FolderTypeDialogActivityAction
}

class FolderTypeDialogActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(FolderTypeDialogActivityState())
    val state: StateFlow<FolderTypeDialogActivityState> = _state.asStateFlow()

    fun setInitialArguments(initialType: String?) {
        val type = initialType?.takeIf { it in FOLDER_TYPES } ?: FOLDER_TYPES.first()
        _state.update { it.copy(selectedType = type) }
    }

    fun onAction(action: FolderTypeDialogActivityAction) {
        when (action) {
            is FolderTypeDialogActivityAction.SelectFolderType -> selectFolderType(action.type)
        }
    }

    private fun selectFolderType(type: String) {
        if (type !in FOLDER_TYPES) {
            return
        }
        _state.update { it.copy(selectedType = type) }
    }
}
