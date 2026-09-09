package com.micnubinub.syncthing.onboarding.pages

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.navigation.LocalRootNavigator
import com.micnubinub.syncthing.navigation.RootRoute.Log
import com.micnubinub.syncthing.onboarding.OnboardingIcon
import com.micnubinub.syncthing.onboarding.OnboardingScaffold
import com.micnubinub.syncthing.onboarding.OnboardingUiState

@Composable
fun KeyGenerationPage(
    uiState: OnboardingUiState,
    pageIndex: Int,
    requestTvFocus: Boolean,
    onBack: () -> Unit,
    onFinishOnboarding: () -> Unit,
) {
    val rootNavigator = LocalRootNavigator.current

    OnboardingScaffold(
        icon = OnboardingIcon.Vector(Icons.Outlined.Key),
        title = stringResource(R.string.key_generation_title),
        description = uiState.keyGenerationStatus,
        pageIndex = pageIndex,
        pageCount = uiState.pages.size,
        canGoBack = !uiState.keyGenerationRunning,
        nextLabel = stringResource(
            if (uiState.keyGenerationFailed) {
                R.string.open_log
            } else {
                R.string.finish
            }
        ),
        nextEnabled = uiState.keyGenerationFailed || uiState.hasConfig,
        requestTvFocus = requestTvFocus,
        onBack = onBack,
        onNext = {
            if (uiState.keyGenerationFailed) {
                rootNavigator.navigateTo(Log)
            } else if (uiState.hasConfig) {
                onFinishOnboarding()
            }
        },
        action = {
            if (uiState.keyGenerationRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
            }
        },
    )
}
