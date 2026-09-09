package com.micnubinub.syncthing.ui.viewModels

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import com.micnubinub.syncthing.service.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WifiSsidItem(
    val ssid: String,
    val checked: Boolean
)

data class SyncConditionsActivityState(
    val isLoading: Boolean = true,
    val syncOnWifi: Boolean = false,
    val syncOnWifiEnabled: Boolean = false,
    val syncOnWhitelistedWifi: Boolean = false,
    val syncOnWhitelistedWifiEnabled: Boolean = false,
    val whitelistAvailable: Boolean = false,
    val wifiSsids: List<WifiSsidItem> = emptyList(),
    val syncOnMeteredWifi: Boolean = false,
    val syncOnMeteredWifiEnabled: Boolean = false,
    val syncOnMobileData: Boolean = false,
    val syncOnMobileDataEnabled: Boolean = false,
    val powerSource: String = Constants.PowerSource.CHARGER_BATTERY,
    val powerSourceEnabled: Boolean = true
)

sealed interface SyncConditionsActivityAction {
    data object LoadData : SyncConditionsActivityAction
    data class SyncOnWifiChanged(val checked: Boolean) : SyncConditionsActivityAction
    data class SyncOnWhitelistedWifiChanged(val checked: Boolean) : SyncConditionsActivityAction
    data class WifiSsidChanged(val ssid: String, val checked: Boolean) :
        SyncConditionsActivityAction

    data class SyncOnMeteredWifiChanged(val checked: Boolean) : SyncConditionsActivityAction
    data class SyncOnMobileDataChanged(val checked: Boolean) : SyncConditionsActivityAction
    data class PowerSourceChanged(val powerSource: String) : SyncConditionsActivityAction
}

class SyncConditionsActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(SyncConditionsActivityState())
    val state: StateFlow<SyncConditionsActivityState> = _state.asStateFlow()

    private var preferences: SharedPreferences? = null
    private var objectPrefixAndId: String? = null

    fun setPreferences(preferences: SharedPreferences?) {
        this.preferences = preferences
    }

    fun setObjectPrefixAndId(objectPrefixAndId: String?) {
        this.objectPrefixAndId = objectPrefixAndId
    }

    fun onAction(action: SyncConditionsActivityAction) {
        when (action) {
            SyncConditionsActivityAction.LoadData -> loadData()
            is SyncConditionsActivityAction.SyncOnWifiChanged -> updateAndSave({
                it.copy(
                    syncOnWifi = action.checked,
                    syncOnWhitelistedWifiEnabled = it.whitelistAvailable && action.checked
                )
            })

            is SyncConditionsActivityAction.SyncOnWhitelistedWifiChanged -> updateAndSave({
                it.copy(syncOnWhitelistedWifi = action.checked)
            }, persistWhitelist = true)

            is SyncConditionsActivityAction.WifiSsidChanged -> updateAndSave({
                it.copy(
                    wifiSsids = it.wifiSsids.map { item ->
                        if (item.ssid == action.ssid) item.copy(checked = action.checked) else item
                    }
                )
            }, persistWhitelist = true)

            is SyncConditionsActivityAction.SyncOnMeteredWifiChanged -> updateAndSave({
                it.copy(syncOnMeteredWifi = action.checked)
            })

            is SyncConditionsActivityAction.SyncOnMobileDataChanged -> updateAndSave({
                it.copy(syncOnMobileData = action.checked)
            })

            is SyncConditionsActivityAction.PowerSourceChanged -> updateAndSave({
                it.copy(powerSource = action.powerSource)
            })
        }
    }

    private fun loadData() {
        val preferences = preferences ?: return
        val objectPrefixAndId = objectPrefixAndId ?: return

        // Load global run conditions.
        val globalRunOnWifi = preferences.getBoolean(Constants.PREF_RUN_ON_WIFI, true)
        val globalWhitelistedSsids =
            preferences.getStringSet(Constants.PREF_WIFI_SSID_WHITELIST, emptySet()).orEmpty()
        val globalWhitelistEnabled =
            preferences.getBoolean(Constants.PREF_USE_WIFI_SSID_WHITELIST, false)
        val globalRunOnMeteredWifi =
            preferences.getBoolean(Constants.PREF_RUN_ON_METERED_WIFI, false)
        val globalRunOnMobileData = preferences.getBoolean(Constants.PREF_RUN_ON_MOBILE_DATA, false)
        val globalPowerSource = preferences.getString(
            Constants.PREF_POWER_SOURCE,
            Constants.PowerSource.CHARGER_BATTERY
        )
        val globalRunOnAnyPowerSource = globalPowerSource == Constants.PowerSource.CHARGER_BATTERY

        // Load custom object preferences. If unset, use global setting as default.
        val syncOnWifi = globalRunOnWifi && preferences.getBoolean(
            Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId), true
        )
        val selectedSsids = HashSet(
            preferences.getStringSet(
                Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId),
                globalWhitelistedSsids
            ).orEmpty()
        )
        // Remove any network that is no longer part of the global WiFi Ssid whitelist.
        selectedSsids.retainAll(globalWhitelistedSsids)

        _state.update {
            it.copy(
                isLoading = false,
                syncOnWifi = syncOnWifi,
                syncOnWifiEnabled = globalRunOnWifi,
                syncOnWhitelistedWifi = globalWhitelistEnabled && preferences.getBoolean(
                    Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId), true
                ),
                syncOnWhitelistedWifiEnabled = globalWhitelistEnabled && syncOnWifi,
                whitelistAvailable = globalWhitelistEnabled,
                wifiSsids = globalWhitelistedSsids
                    .map { ssid -> WifiSsidItem(ssid = ssid, checked = ssid in selectedSsids) }
                    .sortedBy { item -> item.ssid },
                syncOnMeteredWifi = globalRunOnMeteredWifi && preferences.getBoolean(
                    Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId), true
                ),
                syncOnMeteredWifiEnabled = globalRunOnMeteredWifi,
                syncOnMobileData = globalRunOnMobileData && preferences.getBoolean(
                    Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId), true
                ),
                syncOnMobileDataEnabled = globalRunOnMobileData,
                powerSource = if (globalRunOnAnyPowerSource) {
                    preferences.getString(
                        Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId),
                        Constants.PowerSource.CHARGER_BATTERY
                    ) ?: Constants.PowerSource.CHARGER_BATTERY
                } else {
                    globalPowerSource ?: Constants.PowerSource.CHARGER_BATTERY
                },
                powerSourceEnabled = globalRunOnAnyPowerSource
            )
        }

        /**
         * Always save, as changes to the global run conditions resulting in
         * force-disabled options here would else not be saved back to the prefs.
         */
        save()
    }

    private fun updateAndSave(
        transform: (SyncConditionsActivityState) -> SyncConditionsActivityState,
        persistWhitelist: Boolean = false
    ) {
        _state.update(transform)
        save(persistWhitelist)
    }

    private fun save(persistWhitelist: Boolean = false) {
        val preferences = preferences ?: return
        val objectPrefixAndId = objectPrefixAndId ?: return
        val current = _state.value

        preferences.edit {
            putBoolean(
                Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId),
                current.syncOnWifi
            )
            putBoolean(
                Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId),
                current.syncOnWhitelistedWifi
            )
            putBoolean(
                Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId),
                current.syncOnMeteredWifi
            )
            putBoolean(
                Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId),
                current.syncOnMobileData
            )
            putString(
                Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId),
                current.powerSource
            )
            if (persistWhitelist) {
                putStringSet(
                    Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId),
                    current.wifiSsids.filter { it.checked }.map { it.ssid }.toSet()
                )
            }
        }
    }
}
