package com.micnubinub.syncthing.ui.viewModels

import android.os.Bundle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val VERSIONING_TYPES = listOf("none", "trashcan", "simple", "staggered", "external")

data class VersioningDialogActivityState(
    val selectedType: String = VERSIONING_TYPES.first(),
    val parameters: Map<String, String> = emptyMap()
)

sealed interface VersioningDialogActivityAction {
    data class SelectVersioningType(val type: String) : VersioningDialogActivityAction
    data class UpdateParameter(val key: String, val value: String) : VersioningDialogActivityAction
}

class VersioningDialogActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(VersioningDialogActivityState())
    val state: StateFlow<VersioningDialogActivityState> = _state.asStateFlow()

    fun setInitialArguments(arguments: Bundle?) {
        val initialParameters = arguments?.toStringMap() ?: emptyMap()
        val type = initialParameters["type"]
            ?.takeIf { it in VERSIONING_TYPES }
            ?: VERSIONING_TYPES.first()
        _state.update {
            it.copy(
                selectedType = type,
                parameters = applyDefaults(type, initialParameters - "type")
            )
        }
    }

    fun onAction(action: VersioningDialogActivityAction) {
        when (action) {
            is VersioningDialogActivityAction.SelectVersioningType -> selectVersioningType(action.type)
            is VersioningDialogActivityAction.UpdateParameter -> updateParameter(
                action.key,
                action.value
            )
        }
    }

    private fun selectVersioningType(type: String) {
        if (type !in VERSIONING_TYPES) {
            return
        }
        _state.update {
            it.copy(
                selectedType = type,
                parameters = applyDefaults(type, it.parameters)
            )
        }
    }

    private fun updateParameter(key: String, value: String) {
        _state.update { it.copy(parameters = it.parameters + (key to value)) }
    }

    private fun applyDefaults(type: String, parameters: Map<String, String>): Map<String, String> =
        defaultParametersForType(type) + parameters

    private fun defaultParametersForType(type: String): Map<String, String> = when (type) {
        "trashcan" -> mapOf("cleanoutDays" to "0")
        "simple" -> mapOf("keep" to "5", "cleanoutDays" to "0")
        "staggered" -> mapOf("maxAge" to "0", "versionsPath" to "")
        "external" -> mapOf("command" to "")
        else -> emptyMap()
    }

    private fun Bundle.toStringMap(): Map<String, String> =
        keySet().associateWith { getString(it) ?: "" }
}
