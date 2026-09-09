package com.micnubinub.syncthing.ui.screens.fragments

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.navigation.LocalRootNavigator
import com.micnubinub.syncthing.navigation.RootRoute
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.ui.viewModels.DeviceFragmentAction
import com.micnubinub.syncthing.ui.viewModels.DeviceFragmentState
import com.micnubinub.syncthing.ui.viewModels.DeviceFragmentViewModel
import com.micnubinub.syncthing.ui.viewModels.DeviceItemStatusUi
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD = 50 * 1024 * 8L

@Preview(showSystemUi = true)
@Composable
fun DeviceFragmentScreen(
    modifier: Modifier = Modifier,
    serviceFlow: StateFlow<SyncthingService?> = MutableStateFlow(null),
    serviceStateFlow: StateFlow<SyncthingService.State> = MutableStateFlow(SyncthingService.State.INIT),
    configRouter: ConfigRouter? = null,
    viewModel: DeviceFragmentViewModel = remember { DeviceFragmentViewModel() }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceState by serviceStateFlow.collectAsStateWithLifecycle()
    val service by serviceFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(service) {
        viewModel.setService(service)
    }
    LaunchedEffect(context) {
        viewModel.setContext(context)
    }
    LaunchedEffect(configRouter) {
        if (configRouter != null) {
            viewModel.setConfigRouter(configRouter)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAction(DeviceFragmentAction.LoadData)
                Lifecycle.Event.ON_STOP -> viewModel.onAction(DeviceFragmentAction.StopPolling)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAction(DeviceFragmentAction.StopPolling)
        }
    }

    DeviceFragmentContent(
        state = state,
        modifier = modifier,
        serviceState = serviceState,
        viewModel = viewModel
    )
}

@Composable
private fun DeviceFragmentContent(
    state: DeviceFragmentState,
    serviceState: SyncthingService.State,
    viewModel: DeviceFragmentViewModel,
    modifier: Modifier = Modifier
) {
    val navigator = LocalRootNavigator.current
    when {
        state.isLoading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }

        state.devices.isEmpty() -> DeviceEmptyState(
            onClick = {
                navigator.navigateTo(RootRoute.Device(isCreate = true)) { result ->
                    if (result == Activity.RESULT_OK) {
                        viewModel.onAction(DeviceFragmentAction.LoadData)
                    }
                }
            },
            modifier = modifier
        )

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize()
            ) {
                items(
                    items = state.devices,
                    key = { it.deviceID ?: it.name }
                ) { device ->
                    DeviceItem(
                        device = device,
                        status = state.deviceStatuses[device.deviceID],
                        sharedFolders = state.sharedFoldersByDevice[device.deviceID],
                        lastSeen = state.lastSeenByDevice[device.deviceID].orEmpty(),
                        onClick = {
                            navigator.navigateTo(
                                RootRoute.Device(
                                    isCreate = false,
                                    deviceId = device.deviceID,
                                    deviceName = device.name
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceEmptyState(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.no_devices_found),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_device))
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: Device,
    status: DeviceItemStatusUi?,
    sharedFolders: List<Folder>?,
    lastSeen: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val uiState = remember(device, status) {
        computeDeviceStatusUiState(context, device, status)
    }
    val statusText = stringResource(uiState.textResId, *uiState.formatArgs.toTypedArray())
    val statusColor = colorResource(uiState.colorResId)

    val deviceLastSeen = lastSeen
    val formattedLastSeen = remember(deviceLastSeen) {
        if (deviceLastSeen.isEmpty() || deviceLastSeen == TIMESTAMP_NEVER_SEEN) {
            null
        } else {
            Util.formatDateTime(deviceLastSeen).orEmpty()
        }
    }
    val lastSeenText = stringResource(
        R.string.device_last_seen,
        formattedLastSeen ?: stringResource(R.string.device_last_seen_never)
    )

    val sharedFoldersTitle = if (sharedFolders.isNullOrEmpty()) {
        stringResource(R.string.device_state_unused)
    } else {
        stringResource(R.string.shared_folders_title_colon)
    }
    val sharedFoldersText = remember(sharedFolders) {
        sharedFolders
            ?.joinToString("\n\u2022 ") { it.toString() }
            ?.let { "\u2022 $it" }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
            if (uiState.showProgressBar) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colorResource(R.color.text_blue)
                )
            }
            if (uiState.showTransferRate) {
                Text(
                    text = "\u2193 ${uiState.downText}  \u2191 ${uiState.upText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = lastSeenText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = sharedFoldersTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (sharedFoldersText != null) {
                Text(
                    text = sharedFoldersText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.Phonelink,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 30.dp, end = 20.dp, top = 9.dp, bottom = 9.dp)
                .size(24.dp)
        )
    }
}

/**
 * Precomputed, resource-agnostic representation of a device item's status row.
 */
private data class DeviceStatusUiState(
    val textResId: Int,
    val formatArgs: List<Any> = emptyList(),
    val colorResId: Int,
    val showProgressBar: Boolean = false,
    val progress: Float = 0f,
    val showTransferRate: Boolean = false,
    val downText: String = "",
    val upText: String = "",
)

private fun computeDeviceStatusUiState(
    context: android.content.Context,
    device: Device,
    status: DeviceItemStatusUi?
): DeviceStatusUiState {
    val isConnected = status?.connected == true
    val completion = status?.completion ?: 100
    val needBytes = status?.needBytes ?: 0.0
    val inBits = status?.inBits ?: 0
    val outBits = status?.outBits ?: 0

    val showTransferRate = isConnected && inBits + outBits > 0
    val downText = if (showTransferRate) Util.readableTransferRate(context, inBits) else ""
    val upText = if (showTransferRate) Util.readableTransferRate(context, outBits) else ""

    return when {
        device.paused -> DeviceStatusUiState(
            textResId = R.string.device_paused,
            colorResId = R.color.text_purple,
            showTransferRate = showTransferRate,
            downText = downText,
            upText = upText,
        )

        !isConnected -> DeviceStatusUiState(
            textResId = if (needBytes > 0) {
                R.string.device_disconnected_not_synced
            } else {
                R.string.device_disconnected
            },
            formatArgs = if (needBytes > 0) {
                listOf(Util.readableFileSize(context, needBytes))
            } else {
                emptyList()
            },
            colorResId = R.color.text_red,
        )

        completion != 100 -> DeviceStatusUiState(
            textResId = if (needBytes > 0) {
                R.string.device_syncing_percent_bytes
            } else {
                R.string.device_syncing
            },
            formatArgs = if (needBytes > 0) {
                listOf(completion, Util.readableFileSize(context, needBytes))
            } else {
                listOf(completion)
            },
            colorResId = R.color.text_blue,
            showProgressBar = true,
            progress = completion / 100f,
            showTransferRate = showTransferRate,
            downText = downText,
            upText = upText,
        )

        inBits > ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD ||
                outBits > ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD -> DeviceStatusUiState(
            textResId = R.string.state_syncing_general,
            colorResId = R.color.text_blue,
            showProgressBar = true,
            progress = 1f,
            showTransferRate = showTransferRate,
            downText = downText,
            upText = upText,
        )

        else -> DeviceStatusUiState(
            textResId = R.string.device_up_to_date,
            colorResId = R.color.text_green,
            showTransferRate = showTransferRate,
            downText = downText,
            upText = upText,
        )
    }
}

private const val TIMESTAMP_NEVER_SEEN = "1970-01-01T00:00:00Z"
