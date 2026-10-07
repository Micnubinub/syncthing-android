package com.micnubinub.syncthing.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.service.SyncthingService.OnServiceStateChangeListener


class QuickSettingsTileSchedule : TileService(), ServiceConnection, OnServiceStateChangeListener {
    private var tilesAvailableState = Tile.STATE_INACTIVE
    private var context: Context? = null
    private var preferences: SharedPreferences? = null
    private val prefListener: OnSharedPreferenceChangeListener =
        object : OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(
                sharedPreferences: SharedPreferences?,
                pref: String?
            ) {
                if (pref == null || pref != Constants.PREF_BTNSTATE_FORCE_START_STOP) return
                refreshTile()
            }
        }
    private var syncthingService: SyncthingService? = null

    override fun onDestroy() {
        LogV("onDestroy()")
        syncthingService?.unregisterOnServiceStateChangeListener(this)
        syncthingService = null
        preferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            context?.unbindService(this)
        } catch (e: Exception) {
            LogV("Service not bound or already unbound")
        }
        super.onDestroy()
    }

    override fun onStartListening() {
        LogV("onStartListening()")
        if (qsTile != null) {
            try {
                application.applicationContext?.let { appContext ->
                    context = appContext
                    preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
                    preferences?.registerOnSharedPreferenceChangeListener(prefListener)

                    // Only bind if the service is already running; do not use
                    // BIND_AUTO_CREATE to avoid starting the service just for the tile.
                    if (isServiceRunning()) {
                        val bindIntent = Intent(appContext, SyncthingService::class.java)
                        appContext.bindService(bindIntent, this, 0)
                    }
                }

            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind to SyncthingService", e)
            }

            refreshTile()
        }
        super.onStartListening()
    }

    override fun onStopListening() {
        LogV("onStopListening()")
        syncthingService?.unregisterOnServiceStateChangeListener(this)
        syncthingService = null
        preferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            context?.unbindService(this)
        } catch (e: Exception) {
            LogV("Service not bound or already unbound")
        }
        super.onStopListening()
    }

    private fun isServiceRunning(): Boolean {
        return SyncthingService.isServiceRunning
    }

    override fun onClick() {
        if (qsTile.state == Tile.STATE_UNAVAILABLE) {
            return
        }
        RunConditionBus.tryEmit(
            RunConditionEvent.SyncTriggerFired(true)
        )
    }

    private fun refreshTile() {
        if (setTileUnavailable()) {
            return
        }
        updateTile(tilesAvailableState)
    }

    private fun setTileUnavailable(): Boolean {
        val tile = qsTile ?: return false

        // look through running services to see whether the app is currently running
        val syncthingRunning = SyncthingService.isServiceRunning

        // disable tile if app is not running, schedule is off, or syncthing is force-started/stopped
        if (syncthingRunning && preferences?.getBoolean(
                Constants.PREF_RUN_ON_TIME_SCHEDULE,
                false
            ) == true && preferences?.getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP
            ) == Constants.BTNSTATE_NO_FORCE_START_STOP
        ) {
            return false
        }

        updateTile(Tile.STATE_UNAVAILABLE)
        return true
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        LogV(
            String.format(
                "onServiceConnected(ComponentName=%s, IBinder=%s)",
                name.toString(),
                service.toString()
            )
        )
        syncthingService = (service as SyncthingServiceBinder).service
        syncthingService?.registerOnServiceStateChangeListener(this)
    }

    override fun onServiceDisconnected(componentName: ComponentName?) {
        syncthingService = null
    }

    private fun updateTile(newState: Int) {
        val tile = qsTile ?: return
        if (newState == tile.state) return

        tile.state = newState

        val label: String?
        val res = context?.resources
        label = if (newState == Tile.STATE_INACTIVE || newState == Tile.STATE_ACTIVE) {
            res?.getString(
                R.string.qs_schedule_label_minutes, preferences?.getString(
                    Constants.PREF_SYNC_DURATION_MINUTES,
                    "5"
                )?.toIntOrNull() ?: 5
            )
        } else {
            res?.getString(R.string.qs_schedule_disabled)
        }
        tile.label = label

        tile.updateTile()
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        LogV(String.format("onServiceStateChange: %s", currentState.toString()))

        tilesAvailableState = Tile.STATE_INACTIVE
        if (currentState == SyncthingService.State.STARTING || currentState == SyncthingService.State.ACTIVE) {
            tilesAvailableState = Tile.STATE_ACTIVE
        }
        refreshTile()
    }

    private fun LogV(logMessage: String) {
        if (!ENABLE_VERBOSE_LOG) return
        Log.v(TAG, logMessage)
    }

    companion object {
        private const val TAG = "QuickSettingsTileSchedule"
        private const val ENABLE_VERBOSE_LOG = false
    }
}
