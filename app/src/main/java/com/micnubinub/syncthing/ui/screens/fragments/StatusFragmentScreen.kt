package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.ui.viewModels.StatusActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.StatusFragmentAction
import com.micnubinub.syncthing.ui.viewModels.StatusFragmentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class StatusButtons {
    RUN_CONDITIONS, FORCE_START, FORCE_STOP
}

@Preview(showSystemUi = true)
@Composable
fun StatusFragmentScreen(
    modifier: Modifier = Modifier,
    serviceStateFlow: StateFlow<SyncthingService.State> = MutableStateFlow(SyncthingService.State.INIT),
    serviceFlow: StateFlow<SyncthingService?> = MutableStateFlow(null),
    viewModel: StatusActivityViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serviceState by serviceStateFlow.collectAsStateWithLifecycle()
    val service by serviceFlow.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, service) {
        viewModel.setService(service)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAction(StatusFragmentAction.LoadData)
                Lifecycle.Event.ON_STOP -> viewModel.onAction(StatusFragmentAction.StopPolling)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAction(StatusFragmentAction.StopPolling)
            viewModel.setService(null)
        }
    }

    StatusFragmentContent(
        state = state,
        serviceState = serviceState,
        modifier = modifier,
        viewModel = viewModel
    )
}

@Composable
private fun StatusFragmentContent(
    state: StatusFragmentState,
    serviceState: SyncthingService.State,
    viewModel: StatusActivityViewModel,
    modifier: Modifier = Modifier
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SingleChoiceSegmentedButtonRow {
            StatusButtons.entries.forEachIndexed { index, button ->
                SegmentedButton(
                    modifier = modifier.height(with(LocalDensity.current) { 88.sp.toDp() }),
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = StatusButtons.entries.size
                    ),
                    icon = {},
                    onClick = {
                        selectedIndex = index
                        viewModel.onRunConditionSelected(index)
                    },
                    selected = index == selectedIndex,
                    label = {
                        Text(
                            text = when (button) {
                                StatusButtons.RUN_CONDITIONS -> stringResource(R.string.button_follow_run_conditions)
                                StatusButtons.FORCE_START -> stringResource(R.string.button_force_start)
                                StatusButtons.FORCE_STOP -> stringResource(R.string.button_force_stop)
                            },
                            modifier.padding(4.dp)
                        )
                    }
                )
            }
        }

        when (serviceState) {
            SyncthingService.State.ACTIVE -> {
                BasicInfo(stringResource(R.string.syncthing_running), modifier)
            }

            SyncthingService.State.INIT -> {
                BasicInfo(stringResource(R.string.syncthing_starting), modifier)
            }

            SyncthingService.State.STARTING -> {
                BasicInfo(stringResource(R.string.syncthing_starting), modifier)
            }

            SyncthingService.State.DISABLED -> {
                BasicInfo(stringResource(R.string.syncthing_not_running), modifier)
            }

            SyncthingService.State.ERROR -> {
                BasicInfo(stringResource(R.string.syncthing_has_crashed), modifier)
            }
        }

        StatusInfo(state)
    }
}

@Composable
private fun BasicInfo(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(text, modifier = modifier.padding(8.dp), fontSize = 21.sp)
}


@Composable
private fun StatusInfo(
    state: StatusFragmentState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 20.sp
        )
        Spacer(modifier.height(2.dp))
        Text(
            text = state.runReason,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 20.sp,
            modifier = modifier.padding(4.dp)
        )

        Spacer(modifier.height(4.dp))
        StatusRow(
            title = stringResource(R.string.uptime),
            value = state.uptime
        )
        StatusRow(
            title = stringResource(R.string.ram_usage),
            value = state.ramUsage
        )
        StatusRow(
            title = stringResource(R.string.download_title),
            value = state.download
        )
        StatusRow(
            title = stringResource(R.string.upload_title),
            value = state.upload
        )
        StatusRow(
            title = stringResource(R.string.announce_server),
            value = state.announceServer
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 20.sp
        )
        Spacer(modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 20.sp
        )
    }
}
