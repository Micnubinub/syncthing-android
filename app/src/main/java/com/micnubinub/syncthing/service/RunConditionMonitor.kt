package com.micnubinub.syncthing.service

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SyncStatusObserver
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.service.AppPrefs.getPrefVerboseLog
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST
import com.micnubinub.syncthing.service.Constants.isRunningOnEmulator
import com.micnubinub.syncthing.util.SyncScheduler.cancelAllScheduledJobs
import com.micnubinub.syncthing.util.SyncScheduler.scheduleSyncTriggerServiceJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds information about the current wifi and charging state of the device.
 * 
 * 
 * This information is actively read on instance creation, and then updated from intents.
 */
class RunConditionMonitor(
    val context: Context,
    onShouldRunChangedListener: OnShouldRunChangedListener?,
    onSyncPreconditionChangedListener: OnSyncPreconditionChangedListener?
) : AutoCloseable {
    private val res: Resources

    @Inject
    lateinit var preferences: SharedPreferences
    private var ENABLE_VERBOSE_LOG = false
    private var syncStatusObserverHandle: Any? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallbackRegistered = false
    var runDecisionExplanation: String? = ""
        private set

    /**
     * Only relevant if the user has enabled turning Syncthing on by
     * time schedule for a specific amount of time periodically.
     * Holds true if we are within a "SyncthingNative should run" time frame.
     * Initial status false because we check if the last sync was more than one hour ago on app start.
     */
    private var timeConditionMatch = false

    // Avoid re-scheduling start if run conditions change while already running.
    private var runAllowedStopScheduled = false
    private var triggeredSyncDurationS = 10
    private var triggeredSyncSleepIntervalS = 10

    /**
     * Sending callback notifications through OnShouldRunChangedListener is enabled if not null.
     */
    private var onShouldRunChangedListener: OnShouldRunChangedListener? = null

    /**
     * Sending callback notifications through OnSyncPreconditionChangedListener is enabled if not null.
     */
    private var onSyncPreconditionChangedListener: OnSyncPreconditionChangedListener? = null

    /**
     * Stores the result of the last call to [.decideShouldRun].
     */
    private var lastDeterminedShouldRun = false
    private val syncStatusObserver: SyncStatusObserver = SyncStatusObserver {
        coroutineScope.launch { updateShouldRunDecision() }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateShouldRunDecision()
        }

        override fun onLost(network: Network) {
            updateShouldRunDecision()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            updateShouldRunDecision()
        }
    }

    init {
        (context.applicationContext as SyncthingApp).component().inject(this)
        ENABLE_VERBOSE_LOG = getPrefVerboseLog(preferences)
        LogV("Created new instance")
        res = context.resources
        this.onShouldRunChangedListener = onShouldRunChangedListener
        this.onSyncPreconditionChangedListener = onSyncPreconditionChangedListener

        /**
         * Register broadcast receivers.
         */
        // NetworkCallback to get notified of network availability and capability changes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback
            )
        }
        networkCallbackRegistered = true

        // BatteryReceiver
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        ReceiverManager.registerReceiver(context, BatteryReceiver(), filter)

        // PowerSaveModeChangedReceiver
        ReceiverManager.registerReceiver(
            context,
            PowerSaveModeChangedReceiver(),
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        )

        // SyncStatusObserver to monitor android's "AutoSync" quick toggle.
        syncStatusObserverHandle = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, syncStatusObserver
        )

        coroutineScope.launch {
            RunConditionBus.events.collect { event ->
                when (event) {
                    is RunConditionEvent.SyncTriggerFired -> {
                        runAllowedStopScheduled = false
                        val extraBeginActiveTimeWindow = event.beginActiveWindow
                        LogV("SyncTriggerReceiver: onReceive, extraBeginActiveTimeWindow=$extraBeginActiveTimeWindow")

                        if (!preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
                            timeConditionMatch = false
                            cancelAllScheduledJobs(context)
                            scheduleSyncTriggerServiceJob(
                                context,
                                triggeredSyncSleepIntervalS,
                                true
                            )
                            return@collect
                        }

                        if (extraBeginActiveTimeWindow) {
                            timeConditionMatch = true
                            cancelAllScheduledJobs(context)
                            scheduleSyncTriggerServiceJob(
                                context,
                                triggeredSyncDurationS,
                                false
                            )
                            runAllowedStopScheduled = true
                        } else {
                            timeConditionMatch = false
                            val lastRunTimeMillis =
                                preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
                            if (lastDeterminedShouldRun && SystemClock.elapsedRealtime() - lastRunTimeMillis > triggeredSyncSleepIntervalS * 1000L) {
                                preferences.edit {
                                    putLong(
                                        Constants.PREF_LAST_RUN_TIME,
                                        SystemClock.elapsedRealtime() - triggeredSyncSleepIntervalS * 1000L + 60 * 1000
                                    )
                                }
                            }
                        }
                        updateShouldRunDecision()

                        if (!runAllowedStopScheduled && !lastDeterminedShouldRun) {
                            cancelAllScheduledJobs(context)
                            scheduleSyncTriggerServiceJob(
                                context,
                                triggeredSyncSleepIntervalS,
                                true
                            )
                        } else {
                            scheduleSyncTriggerServiceJob(
                                context,
                                triggeredSyncDurationS,
                                false
                            )
                        }
                    }

                    is RunConditionEvent.UpdateShouldRunDecision -> {
                        LogV("UpdateShouldRunDecisionReceiver: onReceive")
                        updateShouldRunDecision()
                    }
                }
            }
        }

        if (!isRunningOnEmulator) {
            triggeredSyncSleepIntervalS = preferredSleepIntervalMinutes() * 60
        }
        var lastSyncTimeSinceBootMillisecs =
            preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        val elapsedRealtime = SystemClock.elapsedRealtime()
        /**
         * after a reboot lastSyncTimeSinceBootMillisecs might be larger than elapsedRealtime,
         * since it is referring to the previous reboot
         * in this case we set preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
         * to -triggeredSyncSleepIntervalS, so timeConditionMatch is guaranteed to be true
         */
        if (lastSyncTimeSinceBootMillisecs > elapsedRealtime) {
            preferences.edit {
                putLong(Constants.PREF_LAST_RUN_TIME, -triggeredSyncSleepIntervalS * 1000L)
            }
            lastSyncTimeSinceBootMillisecs = 0
        }

        // Initially determine if syncthing should run under current circumstances.
        updateShouldRunDecision()

        // Initially schedule the SyncTrigger job.
        val elapsedSecondsSinceLastSync =
            (elapsedRealtime - lastSyncTimeSinceBootMillisecs).toInt() / 1000
        Log.d(
            TAG, "JobPrepare: timeConditionMatch=" + timeConditionMatch.toString() +
                    ", elapsedRealtime=" + elapsedRealtime +
                    ", lastSyncTimeSinceBootMillisecs=" + lastSyncTimeSinceBootMillisecs +
                    ", elapsedSecondsSinceLastSync=" + elapsedSecondsSinceLastSync
        )
        scheduleSyncTriggerServiceJob(
            context,
            if (timeConditionMatch) triggeredSyncDurationS else
            /**
             * if triggeredSyncSleepIntervalS - elapsedSecondsSinceLastSync is < 0,
             * timeConditionMatch is set to true during updateShouldRunDecision().
             * Thus the false case cannot be triggered if the delay for scheduleSyncTriggerServiceJob would be negative
             */
                triggeredSyncSleepIntervalS - elapsedSecondsSinceLastSync,
            !timeConditionMatch
        )
    }

    fun shutdown() {
        LogV("Shutting down")
        cancelAllScheduledJobs(context)
        coroutineScope.cancel()
        syncStatusObserverHandle?.let {
            ContentResolver.removeStatusChangeListener(syncStatusObserverHandle)
            syncStatusObserverHandle = null
        }
        if (networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "NetworkCallback was already unregistered", e)
            }
            networkCallbackRegistered = false
        }
        ReceiverManager.unregisterAllReceivers(context)
    }

    override fun close() {
        shutdown()
    }

    /**
     * Event handler that is fired after preconditions changed.
     * We then need to decide if syncthing should run.
     */
    fun updateShouldRunDecision() {
        if (!isRunningOnEmulator) {
            triggeredSyncDurationS = preferredSyncDurationMinutes() * 60
            triggeredSyncSleepIntervalS = preferredSleepIntervalMinutes() * 60
        }

        val newShouldRun = decideShouldRun()
        if (newShouldRun) {
            /**
             * Trigger:
             * a) Sync pre-conditions changed
             * a1) AND SyncthingService.State should remain ACTIVE
             * a2) AND SyncthingService.State should transition from INIT/DISABLED to ACTIVE
             * b) Sync pre-conditions did not change
             * b1) AND SyncthingService.State should remain ACTIVE
             * because a reevaluation of the run conditions was forced from code.
             * Action:
             * SyncthingService will evaluate custom per-object run conditions
             * and pause/unpause objects accordingly.
             */
            onSyncPreconditionChangedListener?.onSyncPreconditionChanged(this)
        }

        /**
         * Check if the current conditions changed the result of decideShouldRun()
         * compared to the last determined result.
         */
        if (newShouldRun != lastDeterminedShouldRun) {
            /**
             * Notify SyncthingService in case it has to transition from
             * a) INIT/DISABLED => STARTING => ACTIVE
             * b) ACTIVE => DISABLED
             */
            onShouldRunChangedListener?.let {
                it.onShouldRunDecisionChanged(newShouldRun)
                lastDeterminedShouldRun = newShouldRun
            }
            if (newShouldRun && !runAllowedStopScheduled && preferences.getBoolean(
                    Constants.PREF_RUN_ON_TIME_SCHEDULE,
                    false
                ) && preferences.getInt(
                    Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP
                ) == Constants.BTNSTATE_NO_FORCE_START_STOP
            ) {
                cancelAllScheduledJobs(context)
                scheduleSyncTriggerServiceJob(
                    context,
                    triggeredSyncDurationS,
                    false
                )
                runAllowedStopScheduled = true
            }
            preferences.edit {
                this.putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime())
            }
        }
    }

    /**
     * Constants.PREF_RUN_ON_WIFI
     */
    private fun checkConditionSyncOnWifi(prefNameSyncOnWifi: String?): SyncConditionResult {
        if (!preferences.getBoolean(prefNameSyncOnWifi, true)) {
            return SyncConditionResult(false, res.getString(R.string.reason_wifi_disallowed))
        }

        if (isWifiOrEthernetConnection) {
            return SyncConditionResult(true, res.getString(R.string.reason_on_wifi))
        }

        /**
         * if (prefRunOnWifi && !isWifiOrEthernetConnection()) { return false; }
         * This is intentionally not returning "false" as the flight mode workaround
         * relevant for some phone models needs to be done by the code below.
         * ConnectivityManager.getActiveNetworkInfo() returns "null" on those phones which
         * results in assuming !isWifiOrEthernetConnection even if the phone is connected
         * to wifi during flight mode, see [isWifiOrEthernetConnection].
         */
        return SyncConditionResult(false, res.getString(R.string.reason_not_on_wifi))
    }

    private fun checkConditionSyncOnPowerSource(prefNameSyncOnPowerSource: String?): SyncConditionResult {
        when (preferences.getString(
            prefNameSyncOnPowerSource,
            Constants.PowerSource.CHARGER_BATTERY
        )) {
            Constants.PowerSource.CHARGER -> if (!isCharging) {
                return SyncConditionResult(false, res.getString(R.string.reason_not_charging))
            }

            Constants.PowerSource.BATTERY -> if (isCharging) {
                return SyncConditionResult(
                    false,
                    res.getString(R.string.reason_not_on_battery_power)
                )
            }

            Constants.PowerSource.CHARGER_BATTERY -> {}
            else -> {}
        }
        return SyncConditionResult(true, "")
    }

    /**
     * Constants.PREF_WIFI_SSID_WHITELIST
     */
    private fun checkConditionSyncOnWhitelistedWifi(
        prefNameUseWifiWhitelist: String?,
        prefNameSelectedWhitelistSsid: String?
    ): SyncConditionResult {
        val wifiWhitelistEnabled =
            preferences.getBoolean(prefNameUseWifiWhitelist, false)
        val whitelistedWifiSsids: MutableSet<String> =
            preferences.getStringSet(prefNameSelectedWhitelistSsid, HashSet<String>())
                ?: return SyncConditionResult(
                    false,
                    res.getString(R.string.reason_not_on_whitelisted_wifi)
                )
        try {
            if (wifiWhitelistConditionMet(wifiWhitelistEnabled, whitelistedWifiSsids)) {
                return SyncConditionResult(
                    true,
                    res.getString(R.string.reason_on_whitelisted_wifi)
                )
            }
            return SyncConditionResult(
                false,
                res.getString(R.string.reason_not_on_whitelisted_wifi)
            )
        } catch (e: LocationUnavailableException) {
            return SyncConditionResult(
                false,
                res.getString(R.string.reason_location_unavailable)
            )
        }
    }

    /**
     * Constants.PREF_RUN_ON_METERED_WIFI
     */
    private fun checkConditionSyncOnMeteredWifi(prefNameSyncOnMeteredWifi: String?): SyncConditionResult {
        if (preferences.getBoolean(prefNameSyncOnMeteredWifi, false)) {
            // Condition is always met as we allow both types of wifi - metered and non-metered.
            return SyncConditionResult(
                true,
                res.getString(R.string.reason_on_metered_nonmetered_wifi)
            )
        }

        // Check if we are on a non-metered wifi.
        if (!isMeteredNetworkConnection) {
            return SyncConditionResult(
                true,
                res.getString(R.string.reason_on_nonmetered_wifi)
            )
        }

        // We disallowed non-metered wifi and are connected to metered wifi.
        return SyncConditionResult(false, res.getString(R.string.reason_not_nonmetered_wifi))
    }

    /**
     * Constants.PREF_RUN_ON_MOBILE_DATA
     */
    private fun checkConditionSyncOnMobileData(prefNameSyncOnMobileData: String?): SyncConditionResult {
        if (!preferences.getBoolean(prefNameSyncOnMobileData, false)) {
            return SyncConditionResult(false, res.getString(R.string.reason_mobile_data_disallowed))
        }

        if (isMobileDataConnection) {
            return SyncConditionResult(true, res.getString(R.string.reason_on_mobile_data))
        }

        return SyncConditionResult(false, res.getString(R.string.reason_not_on_mobile_data))
    }

    /**
     * Constants.PREF_RUN_ON_ROAMING
     */
    private fun checkConditionSyncOnRoaming(prefNameSyncOnRoaming: String?): SyncConditionResult {
        if (preferences.getBoolean(prefNameSyncOnRoaming, false)) {
            // Condition is always met as we allow both types of mobile data networks - roaming and non-roaming.
            return SyncConditionResult(
                true,
                res.getString(R.string.reason_on_roaming_nonroaming_mobile_data)
            )
        }

        // Check if we are on a non-roaming mobile data network.
        if (!isRoamingNetworkConnection) {
            return SyncConditionResult(
                true,
                res.getString(R.string.reason_on_nonroaming_mobile_data)
            )
        }

        // We disallowed non-roaming mobile data and are connected to a mobile data network in roaming mode.
        return SyncConditionResult(
            false,
            res.getString(R.string.reason_not_nonroaming_mobile_data)
        )
    }

    /**
     * Determines if Syncthing should currently run.
     * Updates mRunDecisionExplanation.
     */
    private fun decideShouldRun(): Boolean {
        // Get sync condition preferences.
        val prefRespectPowerSaving =
            preferences.getBoolean(Constants.PREF_RESPECT_BATTERY_SAVING, true)
        val prefRespectMasterSync =
            preferences.getBoolean(Constants.PREF_RESPECT_MASTER_SYNC, false)
        val prefRunInFlightMode =
            preferences.getBoolean(Constants.PREF_RUN_IN_FLIGHT_MODE, false)
        val prefRunOnTimeSchedule =
            preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)

        // PREF_BTNSTATE_FORCE_START_STOP
        when (preferences.getInt(
            Constants.PREF_BTNSTATE_FORCE_START_STOP,
            Constants.BTNSTATE_NO_FORCE_START_STOP
        )) {
            Constants.BTNSTATE_FORCE_START -> {
                LogV("decideShouldRun: PREF_BTNSTATE_FORCE_START")
                runDecisionExplanation = res.getString(R.string.reason_force_start)
                return true
            }

            Constants.BTNSTATE_FORCE_STOP -> {
                LogV("decideShouldRun: PREF_BTNSTATE_FORCE_STOP")
                runDecisionExplanation = res.getString(R.string.reason_force_stop)
                return false
            }
        }

        // PREF_RUN_ON_TIME_SCHEDULE
        // set timeConditionMatch to true if the last run was more than triggeredSyncSleepIntervalS ago
        if (SystemClock.elapsedRealtime() - (preferences.getLong(
                Constants.PREF_LAST_RUN_TIME,
                0
            )) > preferredSleepIntervalMinutes() * 60 * 1000L
        ) timeConditionMatch = true
        if (prefRunOnTimeSchedule && !timeConditionMatch) {
            // Currently, we aren't within a "SyncthingNative should run" time frame.
            LogV("decideShouldRun: PREF_RUN_ON_TIME_SCHEDULE && !timeConditionMatch")
            val minutes = (SystemClock.elapsedRealtime() - (preferences.getLong(
                Constants.PREF_LAST_RUN_TIME,
                0
            ))).toInt() / (60 * 1000)
            val minutesText =
                if (minutes == 0) res.getString(R.string.reason_not_within_time_frame_0_min)
                else String.format(
                    res.getQuantityString(
                        R.plurals.reason_not_within_time_frame_minutes,
                        minutes
                    ), minutes
                )
            runDecisionExplanation =
                String.format(res.getString(R.string.reason_not_within_time_frame_2), minutesText)
            return false
        }

        // PREF_POWER_SOURCE
        var scr = checkConditionSyncOnPowerSource(Constants.PREF_POWER_SOURCE)
        if (!scr.conditionMet) {
            LogV("checkConditionSyncOnPowerSource: " + scr.explanation)
            runDecisionExplanation = scr.explanation
            return false
        }

        // Power saving
        if (prefRespectPowerSaving && isPowerSaving) {
            LogV("decideShouldRun: prefRespectPowerSaving && isPowerSaving")
            runDecisionExplanation = res.getString(R.string.reason_not_while_power_saving)
            return false
        }

        // Android global AutoSync setting.
        if (prefRespectMasterSync && !ContentResolver.getMasterSyncAutomatically()) {
            LogV("decideShouldRun: prefRespectMasterSync && !getMasterSyncAutomatically")
            runDecisionExplanation =
                res.getString(R.string.reason_not_while_auto_sync_data_disabled)
            return false
        }

        val strBuilder = StringBuilder()
        // Run on mobile data?
        scr = checkConditionSyncOnMobileData(Constants.PREF_RUN_ON_MOBILE_DATA)
        strBuilder.appendLine("- ${scr.explanation}")
        if (scr.conditionMet) {
            // Mobile data is connected.
            LogV("decideShouldRun: checkConditionSyncOnMobileData")

            scr = checkConditionSyncOnRoaming(Constants.PREF_RUN_ON_ROAMING)
            strBuilder.appendLine("- ${scr.explanation}")
            if (scr.conditionMet) {
                // Mobile data connection type is allowed.
                LogV("decideShouldRun: checkConditionSyncOnMobileData && checkConditionSyncOnRoaming")
                runDecisionExplanation = strBuilder.toString()
                return true
            }
        }

        // Run on WiFi?
        scr = checkConditionSyncOnWifi(Constants.PREF_RUN_ON_WIFI)
        strBuilder.appendLine("- ${scr.explanation}")
        if (scr.conditionMet) {
            // Wifi is connected.
            LogV("decideShouldRun: checkConditionSyncOnWifi")

            scr = checkConditionSyncOnMeteredWifi(Constants.PREF_RUN_ON_METERED_WIFI)
            strBuilder.appendLine("- ${scr.explanation}")
            if (scr.conditionMet) {
                // Wifi type is allowed.
                LogV("decideShouldRun: checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi")

                scr = checkConditionSyncOnWhitelistedWifi(
                    Constants.PREF_USE_WIFI_SSID_WHITELIST,
                    Constants.PREF_WIFI_SSID_WHITELIST
                )
                strBuilder.appendLine("- ${scr.explanation}")
                if (scr.conditionMet) {
                    // Wifi is whitelisted.
                    LogV("decideShouldRun: checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi && checkConditionSyncOnWhitelistedWifi")
                    runDecisionExplanation = strBuilder.toString()
                    return true
                }
            }
        }

        // Run in flight mode.
        if (prefRunInFlightMode == true && isFlightMode) {
            LogV("decideShouldRun: prefRunInFlightMode && isFlightMode")
            strBuilder.appendLine(res.getString(R.string.reason_on_flight_mode))
            runDecisionExplanation = strBuilder.toString()
            return true
        }

        /**
         * If none of the above run conditions matched, don't run.
         */
        LogV("decideShouldRun: return false")
        runDecisionExplanation = strBuilder.toString()
        return false
    }

    /**
     * Check if an object's individual sync conditions are met.
     * Precondition: Object must own pref "...CustomSyncConditionsEnabled == true".
     */
    fun checkObjectSyncConditions(objectPrefixAndId: String?): Boolean {
        // Sync on specific power source?
        var scr =
            checkConditionSyncOnPowerSource(DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId))
        if (!scr.conditionMet) {
            LogV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnPowerSource")
            return false
        }

        // Sync on mobile data?
        scr = checkConditionSyncOnMobileData(DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId))
        if (scr.conditionMet) {
            // Mobile data is connected.
            LogV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnMobileData")
            return true
        }

        // Sync on WiFi?
        scr = checkConditionSyncOnWifi(DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId))
        if (scr.conditionMet) {
            // Wifi is connected.
            LogV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi")

            scr = checkConditionSyncOnMeteredWifi(
                DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId)
            )
            if (scr.conditionMet) {
                // Wifi type is allowed.
                LogV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi")

                scr = checkConditionSyncOnWhitelistedWifi(
                    DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId),
                    DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId)
                )
                if (scr.conditionMet) {
                    // Wifi is whitelisted.
                    LogV("checkObjectSyncConditions($objectPrefixAndId): checkConditionSyncOnWifi && checkConditionSyncOnMeteredWifi && checkConditionSyncOnWhitelistedWifi")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Each sync condition has its own evaluator function which
     * determines if the condition is met.
     */
    /**
     * Return whether the wifi whitelist run condition is met.
     * Precondition: An active wifi connection has been detected.
     */
    @Throws(LocationUnavailableException::class)
    private fun wifiWhitelistConditionMet(
        prefWifiWhitelistEnabled: Boolean,
        whitelistedWifiSsids: MutableSet<String>
    ): Boolean {
        if (!prefWifiWhitelistEnabled) {
            LogV("handleWifiWhitelist: !prefWifiWhitelistEnabled")
            return true
        }
        if (isWifiConnectionWhitelisted(whitelistedWifiSsids)) {
            LogV("handleWifiWhitelist: isWifiConnectionWhitelisted")
            return true
        }
        return false
    }

    private val isCharging: Boolean
        /**
         * Functions for run condition information retrieval.
         */
        get() {
            val batteryManager =
                context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager?
            if (batteryManager == null) {
                LogV("isCharging: getSystemService(BATTERY_SERVICE) returned null")
                return false
            }
            return batteryManager.isCharging
        }

    private val isPowerSaving: Boolean
        get() {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager?
            if (powerManager == null) {
                Log.e(
                    TAG,
                    "getSystemService(POWER_SERVICE) unexpectedly returned NULL."
                )
                return false
            }
            return powerManager.isPowerSaveMode
        }

    private val activeNetworkCapabilities: NetworkCapabilities?
        get() {
            val network = connectivityManager.activeNetwork ?: return null
            return connectivityManager.getNetworkCapabilities(network)
        }

    private val isFlightMode: Boolean
        get() {
            return connectivityManager.activeNetwork == null
        }

    private val isMeteredNetworkConnection: Boolean
        get() {
            val networkCapabilities = activeNetworkCapabilities ?: return false
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                /**
                 * We treat Wi-Fi and ETHERNET as "Wi-Fi" connection.
                 * Assume ETHERNET connection is un-metered to allow syncing on
                 * Android TV or VirtualBox ETHERNET connection.
                 */
                return false
            }
            return !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

    private val isMobileDataConnection: Boolean
        get() {
            val networkCapabilities = activeNetworkCapabilities ?: return false
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        }

    private val isRoamingNetworkConnection: Boolean
        get() {
            val networkCapabilities = activeNetworkCapabilities ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            }

            @Suppress("DEPRECATION")
            return connectivityManager.activeNetworkInfo?.isRoaming ?: return false
        }

    private val isWifiOrEthernetConnection: Boolean
        get() {
            val networkCapabilities = activeNetworkCapabilities ?: return false
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

    /**
     * Returns the SSID of the currently connected wifi network,
     * or null if not connected to a wifi network.
     */
    private fun getCurrentWifiSsid(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return (activeNetworkCapabilities?.transportInfo as? WifiInfo)?.ssid
        }
        // Below API 30 there is no non-deprecated ConnectivityManager API that exposes the
        // SSID, so fall back to WifiManager.
        @Suppress("DEPRECATION")
        return (context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
    }

    @Throws(LocationUnavailableException::class)
    private fun isWifiConnectionWhitelisted(whitelistedSsids: MutableSet<String>): Boolean {
        val networkCapabilities = activeNetworkCapabilities
        if (networkCapabilities == null ||
            !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) {
            // May be null, if wifi has been turned off in the meantime.
            Log.d(TAG, "isWifiConnectionWhitelisted: not connected to wifi")
            return false
        }
        val wifiSsid = getCurrentWifiSsid()
        if (wifiSsid.isNullOrBlank() || wifiSsid == WifiManager.UNKNOWN_SSID) {
            throw LocationUnavailableException("isWifiConnectionWhitelisted: Got null SSID. Try to enable android location service.")
        }
        // DO NOT RELEASE WITH THIS LINE: Log.v(TAG, "isWifiConnectionWhitelisted: wifiSsid=[" + wifiSsid + "]");
        // WifiInfo.getSSID() may include surrounding quotes depending on API level/source,
        // while stored whitelist entries are quoted. Compare without quotes.
        val currentSsid = wifiSsid.removeSurrounding("\"")
        return whitelistedSsids.any { it.removeSurrounding("\"") == currentSsid }
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    fun interface OnShouldRunChangedListener {
        fun onShouldRunDecisionChanged(shouldRun: Boolean)
    }

    fun interface OnSyncPreconditionChangedListener {
        fun onSyncPreconditionChanged(runConditionMonitor: RunConditionMonitor?)
    }

    private inner class SyncConditionResult {
        var conditionMet: Boolean = false
        var explanation: String? = ""

        constructor(conditionMet: Boolean) {
            this.conditionMet = conditionMet
        }

        constructor(conditionMet: Boolean, explanation: String?) {
            this.conditionMet = conditionMet
            this.explanation = explanation
        }
    }

    private inner class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (Intent.ACTION_POWER_CONNECTED == intent.action
                || Intent.ACTION_POWER_DISCONNECTED == intent.action
            ) {
                // Delay the re-evaluation so the battery status can settle, without
                // blocking the main thread (SystemClock.sleep here would risk an ANR).
                val pendingResult = goAsync()
                coroutineScope.launch {
                    try {
                        delay(5000)
                        updateShouldRunDecision()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private inner class PowerSaveModeChangedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED == intent.action) {
                updateShouldRunDecision()
            }
        }
    }

    class LocationUnavailableException : Exception {
        constructor(message: String?) : super(message)
    }

    companion object {
        const val EXTRA_BEGIN_ACTIVE_TIME_WINDOW: String = "begin_active_time_window"
        private const val TAG = "RunConditionMonitor"

        private const val DEFAULT_SLEEP_INTERVAL_MINUTES = 60
        private const val DEFAULT_SYNC_DURATION_MINUTES = 5

        private const val MIN_SLEEP_INTERVAL_MINUTES = 1
        private const val MIN_SYNC_DURATION_MINUTES = 1
    }

    /**
     * Reads [Constants.PREF_SLEEP_INTERVAL_MINUTES] defensively. A value that can
     * not be parsed (eg after an invalid config import) falls back to the default
     * instead of crashing the run-condition loop.
     */
    private fun preferredSleepIntervalMinutes(): Int {
        return preferences.getString(Constants.PREF_SLEEP_INTERVAL_MINUTES, null)
            ?.toIntOrNull()
            ?.coerceAtLeast(MIN_SLEEP_INTERVAL_MINUTES)
            ?: DEFAULT_SLEEP_INTERVAL_MINUTES
    }

    /**
     * Reads [Constants.PREF_SYNC_DURATION_MINUTES] defensively, see
     * [.preferredSleepIntervalMinutes].
     */
    private fun preferredSyncDurationMinutes(): Int {
        return preferences.getString(Constants.PREF_SYNC_DURATION_MINUTES, null)
            ?.toIntOrNull()
            ?.coerceAtLeast(MIN_SYNC_DURATION_MINUTES)
            ?: DEFAULT_SYNC_DURATION_MINUTES
    }
}
