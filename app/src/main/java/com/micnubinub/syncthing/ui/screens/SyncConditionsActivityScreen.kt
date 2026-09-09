package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.components.SwitchRow
import com.micnubinub.syncthing.ui.viewModels.SyncConditionsActivityAction
import com.micnubinub.syncthing.ui.viewModels.SyncConditionsActivityState
import com.micnubinub.syncthing.ui.viewModels.SyncConditionsActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.WifiSsidItem

private val powerSourceLabels = linkedMapOf(
    Constants.PowerSource.CHARGER_BATTERY to R.string.power_source_ac_and_battery_power,
    Constants.PowerSource.CHARGER to R.string.power_source_ac_power,
    Constants.PowerSource.BATTERY to R.string.power_source_battery_power
)

@Preview(showSystemUi = true)
@Composable
fun SyncConditionsActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncConditionsActivityViewModel = SyncConditionsActivityViewModel(),
    onNavigateUp: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SyncConditionsActivityContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        onNavigateUp = onNavigateUp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncConditionsActivityContent(
    state: SyncConditionsActivityState,
    onAction: (SyncConditionsActivityAction) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit = {}
) {
    ApplicationTheme {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.custom_sync_conditions_dialog)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                HeaderRow(
                    icon = Icons.Default.Autorenew,
                    title = stringResource(R.string.run_conditions_title),
                    caption = stringResource(R.string.run_conditions_summary)
                )
                HorizontalDivider()
                SwitchRow(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.run_on_wifi_title),
                    caption = stringResource(R.string.run_on_wifi_summary),
                    checked = state.syncOnWifi,
                    enabled = state.syncOnWifiEnabled,
                    onCheckedChange = {
                        onAction(SyncConditionsActivityAction.SyncOnWifiChanged(it))
                    }
                )
                HorizontalDivider()
                SwitchRow(
                    icon = Icons.Default.SignalWifi4Bar,
                    title = stringResource(R.string.run_on_whitelisted_wifi_title),
                    caption = stringResource(R.string.run_on_whitelisted_wifi_summary),
                    checked = state.syncOnWhitelistedWifi,
                    enabled = state.syncOnWhitelistedWifiEnabled,
                    onCheckedChange = {
                        onAction(SyncConditionsActivityAction.SyncOnWhitelistedWifiChanged(it))
                    }
                )
                WifiSsidList(state = state, onAction = onAction)
                HorizontalDivider()
                SwitchRow(
                    icon = Icons.Default.WifiTethering,
                    title = stringResource(R.string.run_on_metered_wifi_title),
                    caption = stringResource(R.string.run_on_metered_wifi_summary),
                    checked = state.syncOnMeteredWifi,
                    enabled = state.syncOnMeteredWifiEnabled,
                    onCheckedChange = {
                        onAction(SyncConditionsActivityAction.SyncOnMeteredWifiChanged(it))
                    }
                )
                HorizontalDivider()
                SwitchRow(
                    icon = Icons.Default.SignalCellularConnectedNoInternet0Bar,
                    title = stringResource(R.string.run_on_mobile_data_title),
                    caption = stringResource(R.string.run_on_mobile_data_summary),
                    checked = state.syncOnMobileData,
                    enabled = state.syncOnMobileDataEnabled,
                    onCheckedChange = {
                        onAction(SyncConditionsActivityAction.SyncOnMobileDataChanged(it))
                    }
                )
                HorizontalDivider()
                PowerSourceRow(
                    powerSource = state.powerSource,
                    enabled = state.powerSourceEnabled,
                    onPowerSourceChange = {
                        onAction(SyncConditionsActivityAction.PowerSourceChanged(it))
                    }
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(
    icon: ImageVector,
    title: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(text = title, modifier = Modifier.weight(1f))
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun WifiSsidList(
    state: SyncConditionsActivityState,
    onAction: (SyncConditionsActivityAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (!state.whitelistAvailable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 56.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = stringResource(R.string.custom_wifi_ssid_whitelist_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        } else {
            val enabled = state.syncOnWifi && state.syncOnWhitelistedWifi
            state.wifiSsids.forEach { item ->
                WifiSsidRow(
                    item = item,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        onAction(SyncConditionsActivityAction.WifiSsidChanged(item.ssid, checked))
                    }
                )
            }
        }
    }
}

@Composable
private fun WifiSsidRow(
    item: WifiSsidItem,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!item.checked) }
            .padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.ssid.removePrefix("\"").removeSuffix("\""),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) Color.Unspecified
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = item.checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun PowerSourceRow(
    powerSource: String,
    enabled: Boolean,
    onPowerSourceChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val contentColor = if (enabled) Color.Unspecified
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.BatteryChargingFull,
                contentDescription = null
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.power_source_title),
                    color = contentColor
                )
                Text(
                    text = powerSourceLabels[powerSource]?.let { stringResource(it) }
                        ?: powerSource,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else contentColor
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.padding(start = 56.dp)
        ) {
            powerSourceLabels.forEach { (value, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        expanded = false
                        onPowerSourceChange(value)
                    }
                )
            }
        }
    }
}
