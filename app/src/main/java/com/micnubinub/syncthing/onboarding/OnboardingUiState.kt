package com.micnubinub.syncthing.onboarding

import androidx.compose.runtime.Stable
import java.io.Serializable

@Stable
data class OnboardingUiState(
    val pages: List<OnboardingPage> = emptyList(),
    val currentPage: Int = 0,
    val hasStoragePermission: Boolean = false,
    val hasIgnoreDozePermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasLocalNetworkPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasConfig: Boolean = false,
    val isRunningOnTv: Boolean = false,
    val keyGenerationRunning: Boolean = false,
    val keyGenerationFailed: Boolean = false,
    val keyGenerationStatus: String = "",
) : Serializable
