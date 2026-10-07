package com.micnubinub.syncthing.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.micnubinub.syncthing.activities.PhotoShootActivity

class QuickSettingsTileCamera : TileService() {
    override fun onStartListening() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        /**
         * From API 34 on, a collapsed shade lets a tile service only start an activity
         * through [startActivityAndCollapse]; a plain [TileService.startActivity] is
         * refused by the background-activity-start restrictions and the camera would not
         * come up. Below that the plain call is the documented way and is all that
         * exists.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, PhotoShootActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivity(
                Intent(applicationContext, PhotoShootActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        super.onClick()
    }
}
