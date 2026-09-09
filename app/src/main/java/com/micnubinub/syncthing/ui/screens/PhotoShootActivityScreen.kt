package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.ui.viewModels.PhotoShootActivityState
import com.micnubinub.syncthing.ui.viewModels.PhotoShootActivityViewModel

@Preview(showSystemUi = true)
@Composable
fun PhotoShootActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: PhotoShootActivityViewModel = PhotoShootActivityViewModel(),
    onGrantCameraPermission: () -> Unit = {},
    onGrantStoragePermission: () -> Unit = {},
    onContinue: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PhotoShootActivityContent(
        state = state,
        modifier = modifier,
        onGrantCameraPermission = onGrantCameraPermission,
        onGrantStoragePermission = onGrantStoragePermission,
        onContinue = onContinue,
        onBack = onBack
    )
}

@Composable
private fun PhotoShootActivityContent(
    state: PhotoShootActivityState,
    modifier: Modifier = Modifier,
    onGrantCameraPermission: () -> Unit,
    onGrantStoragePermission: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.bg_screen4))
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.photo_shoot_intro_welcome_title),
                    color = Color.White,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = stringResource(R.string.photo_shoot_intro_permission_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (!state.haveStoragePermission) {
                    GrantPermissionButton(
                        text = stringResource(R.string.grant_storage_permission),
                        icon = Icons.Filled.Save,
                        onClick = onGrantStoragePermission
                    )
                }
                if (!state.haveCameraPermission) {
                    GrantPermissionButton(
                        text = stringResource(R.string.grant_camera_permission),
                        icon = Icons.Filled.PhotoCamera,
                        onClick = onGrantCameraPermission
                    )
                }
                Text(
                    text = stringResource(R.string.photo_shoot_intro_permission_desc),
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.5f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Button(onClick = onContinue) {
                Text(stringResource(R.string.cont))
            }
        }
    }
}

@Composable
private fun GrantPermissionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(text = text, fontSize = 12.sp)
    }
}
