package com.micnubinub.syncthing.ui.viewModels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WebViewActivityState(
    val url: String? = null,
    val isLoading: Boolean = true,
    val isRunningOnTV: Boolean = false
)

sealed interface WebViewActivityAction {
    data class Initialize(val url: String?, val isRunningOnTV: Boolean) : WebViewActivityAction
    data object PageFinished : WebViewActivityAction
}

class WebViewActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(WebViewActivityState())
    val state: StateFlow<WebViewActivityState> = _state.asStateFlow()

    fun onAction(action: WebViewActivityAction) {
        when (action) {
            is WebViewActivityAction.Initialize -> _state.update {
                it.copy(url = action.url, isRunningOnTV = action.isRunningOnTV)
            }

            WebViewActivityAction.PageFinished -> _state.update { it.copy(isLoading = false) }
        }
    }
}
