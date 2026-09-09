package com.micnubinub.syncthing.service

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.micnubinub.syncthing.R

@RequiresApi(api = Build.VERSION_CODES.N)
class QuickSettingsTileForce : TileService() {
    private var context: Context? = null
    private var preferences: SharedPreferences? = null // Manually initialized
    private var res: Resources? = null

    override fun onStartListening() {
        qsTile?.let { tile ->
            context = application.applicationContext
            res = context?.resources

            preferences =
                PreferenceManager.getDefaultSharedPreferences(application.applicationContext)

            // search through running services to see whether the app is currently running
            // disable tile if app is not running
            if (!SyncthingService.isServiceRunning) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                return
            }

            // update tile to reflect forced-state
            preferences?.getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                Constants.BTNSTATE_NO_FORCE_START_STOP
            )?.let {
                updateTileState(
                    tile,
                    it
                )
            }

        }
        super.onStartListening()
    }

    override fun onClick() {
        val newState: Int = when (preferences?.getInt(
            Constants.PREF_BTNSTATE_FORCE_START_STOP,
            Constants.BTNSTATE_NO_FORCE_START_STOP
        )) {
            Constants.BTNSTATE_FORCE_START -> Constants.BTNSTATE_FORCE_STOP
            Constants.BTNSTATE_NO_FORCE_START_STOP -> Constants.BTNSTATE_FORCE_START
            Constants.BTNSTATE_FORCE_STOP -> Constants.BTNSTATE_NO_FORCE_START_STOP
            else -> Constants.BTNSTATE_NO_FORCE_START_STOP
        }
        preferences?.edit {
            putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState)
        }

        RunConditionBus.tryEmit(RunConditionEvent.UpdateShouldRunDecision)

        updateTileState(qsTile, newState)
        qsTile.updateTile()
    }

    private fun updateTileState(tile: Tile, force: Int) {
        when (force) {
            Constants.BTNSTATE_FORCE_START -> {
                tile.label = res?.getString(R.string.qs_forced_to_run)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(context, R.drawable.ic_qs_forced_to_run)
            }

            Constants.BTNSTATE_FORCE_STOP -> {
                tile.label = res?.getString(R.string.qs_forced_to_stop)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(context, R.drawable.ic_qs_forced_to_stop)
            }

            Constants.BTNSTATE_NO_FORCE_START_STOP -> {
                tile.label = res?.getString(R.string.qs_following_run_conditions)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(context, R.drawable.ic_qs_force)
            }

            else -> {
                tile.label = res?.getString(R.string.qs_following_run_conditions)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(context, R.drawable.ic_qs_force)
            }
        }
        tile.updateTile()
    }
}
