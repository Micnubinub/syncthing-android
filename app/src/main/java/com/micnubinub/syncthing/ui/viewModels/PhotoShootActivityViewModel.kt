package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PhotoShootActivityState(
    val haveCameraPermission: Boolean = false,
    val haveStoragePermission: Boolean = false
)

sealed interface PhotoShootActivityAction {
    data class UpdatePermissions(
        val haveCameraPermission: Boolean,
        val haveStoragePermission: Boolean
    ) : PhotoShootActivityAction
}

class PhotoShootActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(PhotoShootActivityState())
    val state: StateFlow<PhotoShootActivityState> = _state.asStateFlow()

    fun onAction(action: PhotoShootActivityAction) {
        when (action) {
            is PhotoShootActivityAction.UpdatePermissions -> _state.update {
                it.copy(
                    haveCameraPermission = action.haveCameraPermission,
                    haveStoragePermission = action.haveStoragePermission
                )
            }
        }
    }
}
