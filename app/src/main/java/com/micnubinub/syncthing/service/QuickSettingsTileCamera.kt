package com.micnubinub.syncthing.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.micnubinub.syncthing.activities.PhotoShootActivity

@RequiresApi(api = Build.VERSION_CODES.N)
class QuickSettingsTileCamera : TileService() {
    override fun onStartListening() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        startActivity(
            Intent(applicationContext, PhotoShootActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        super.onClick()
    }
}
