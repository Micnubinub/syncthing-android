package com.micnubinub.syncthing.onboarding.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.onboarding.OnboardingIcon
import com.micnubinub.syncthing.onboarding.OnboardingScaffold
import com.micnubinub.syncthing.onboarding.OnboardingUiState
import com.micnubinub.syncthing.onboarding.PermissionButton

@Composable
fun StoragePermissionPage(
    uiState: OnboardingUiState,
    pageIndex: Int,
    requestTvFocus: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onGrantStoragePermission: () -> Unit,
) {
    val actionFocusRequester = remember { FocusRequester() }

    OnboardingScaffold(
        icon = OnboardingIcon.Vector(Icons.Outlined.Storage),
        title = stringResource(R.string.storage_permission_title),
        description = stringResource(R.string.storage_permission_desc),
        pageIndex = pageIndex,
        pageCount = uiState.pages.size,
        nextLabel = stringResource(R.string.cont),
        nextEnabled = uiState.hasStoragePermission,
        requestTvFocus = requestTvFocus,
        onBack = onBack,
        onNext = onContinue,
        actionFocusRequester = actionFocusRequester,
        action = {
            PermissionButton(
                granted = uiState.hasStoragePermission,
                onClick = onGrantStoragePermission,
                focusRequester = actionFocusRequester,
            )
        },
    )
}
