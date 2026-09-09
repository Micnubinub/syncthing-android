package com.micnubinub.syncthing.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Window
import androidx.activity.ComponentActivity
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.service.SyncthingServiceBinder

/**
 * Connects to [SyncthingService] and provides access to it.
 */
abstract class SyncthingActivity : ComponentActivity(), ServiceConnection {
    protected val TAG: String
        get() = this::class.simpleName ?: "Syncthing"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    /**
     * Returns service object (or null if not bound).
     */
    var service: SyncthingService? = null
        private set

    private var bound = false

    override fun onPause() {
        if (bound) {
            unbindService(this)
            bound = false
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) {
            bound = bindService(Intent(this, SyncthingService::class.java), this, BIND_AUTO_CREATE)
        }
    }

    override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
        this.service = (iBinder as SyncthingServiceBinder).service
    }

    override fun onServiceDisconnected(componentName: ComponentName?) {
        this.service = null
    }

    /**
     * Returns RestApi instance, or null if SyncthingService is not yet connected.
     */
    val api: RestApi?
        get() = this.service?.api
}

