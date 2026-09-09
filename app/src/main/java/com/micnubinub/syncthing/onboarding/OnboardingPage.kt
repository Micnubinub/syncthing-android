package com.micnubinub.syncthing.onboarding

import androidx.compose.runtime.Composable
import com.micnubinub.syncthing.onboarding.pages.BatteryOptimizationPage
import com.micnubinub.syncthing.onboarding.pages.KeyGenerationPage
import com.micnubinub.syncthing.onboarding.pages.LocalNetworkPermissionPage
import com.micnubinub.syncthing.onboarding.pages.LocationPermissionPage
import com.micnubinub.syncthing.onboarding.pages.NotificationPermissionPage
import com.micnubinub.syncthing.onboarding.pages.StoragePermissionPage
import com.micnubinub.syncthing.onboarding.pages.WelcomePage

/**
 * Specifies the type and content of each onboarding page.
 */
enum class OnboardingPage {
    WELCOME,
    STORAGE_PERMISSION,
    BATTERY_OPTIMIZATION,
    LOCATION_PERMISSION,
    LOCAL_NETWORK_PERMISSION,
    NOTIFICATION_PERMISSION,
    KEY_GENERATION,
}

/**
 * Renders the correct onboarding page for a given [OnboardingPage].
 */
@Composable
fun OnboardingPage(
    page: OnboardingPage,
    uiState: OnboardingUiState,
    pageIndex: Int,
    requestTvFocus: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onFinishOnboarding: () -> Unit,
    onGrantStoragePermission: () -> Unit,
    onGrantLocationPermission: () -> Unit,
    onGrantLocalNetworkPermission: () -> Unit,
    onGrantNotificationPermission: () -> Unit,
) {
    when (page) {
        OnboardingPage.WELCOME -> WelcomePage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onContinue = onContinue,
        )

        OnboardingPage.STORAGE_PERMISSION -> StoragePermissionPage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onContinue = onContinue,
            onGrantStoragePermission = onGrantStoragePermission,
        )

        OnboardingPage.BATTERY_OPTIMIZATION -> BatteryOptimizationPage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onContinue = onContinue,
        )

        OnboardingPage.LOCATION_PERMISSION -> LocationPermissionPage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onContinue = onContinue,
            onGrantLocationPermission = onGrantLocationPermission,
        )

        OnboardingPage.LOCAL_NETWORK_PERMISSION -> LocalNetworkPermissionPage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onContinue = onContinue,
            onGrantLocalNetworkPermission = onGrantLocalNetworkPermission,
        )

        OnboardingPage.NOTIFICATION_PERMISSION -> NotificationPermissionPage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onContinue = onContinue,
            onGrantNotificationPermission = onGrantNotificationPermission,
        )

        OnboardingPage.KEY_GENERATION -> KeyGenerationPage(
            uiState = uiState,
            pageIndex = pageIndex,
            requestTvFocus = requestTvFocus,
            onBack = onBack,
            onFinishOnboarding = onFinishOnboarding,
        )
    }
}
