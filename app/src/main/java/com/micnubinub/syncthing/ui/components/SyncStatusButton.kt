package com.micnubinub.syncthing.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import com.micnubinub.syncthing.service.SyncthingService


@Preview(showBackground = true)
@Composable
internal fun SyncStatusButton(
    syncState: SyncthingService.State = SyncthingService.State.DISABLED,
    isSyncing: Boolean = false,
    onClick: () -> Unit
) {
    val shouldRotate = (syncState == SyncthingService.State.ACTIVE && isSyncing) ||
            (syncState == SyncthingService.State.STARTING)

    val angle = if (shouldRotate) {
        val infiniteTransition = rememberInfiniteTransition(label = "SyncRotation")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "RotationAngle"
        )
    } else {
        null
    }

    // TODO move colors to theme and then make dark and light variants
    when (syncState) {
        SyncthingService.State.DISABLED, SyncthingService.State.ERROR -> Icon(
            imageVector = Icons.Default.SyncDisabled,
            tint = Color(
                color = if (syncState == SyncthingService.State.DISABLED) {
                    0xffffae03
                } else {
                    0xffff0303
                }
            ),
            contentDescription = "Disabled",
            modifier = Modifier.clickable(onClick = onClick)
        )

        SyncthingService.State.ACTIVE -> Icon(
            imageVector = Icons.Default.Sync,
            tint = Color(color = 0xff76ff03),
            contentDescription = "Active",
            modifier = Modifier
                .clickable(onClick = onClick)
                .graphicsLayer() {
                    rotationZ = if (isSyncing) {
                        angle?.value ?: 0f
                    } else {
                        0f
                    }
                }
        )

        SyncthingService.State.STARTING, SyncthingService.State.INIT -> Icon(
            imageVector = Icons.Default.Sync,
            tint = Color(color = 0xfffff103),
            contentDescription = "Starting",
            modifier = Modifier
                .clickable(onClick = onClick)
                .graphicsLayer() {
                    rotationZ = angle?.value ?: 0f
                }
        )
    }
}