package com.micnubinub.syncthing

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import com.micnubinub.syncthing.service.Constants
import javax.inject.Inject

class SyncthingApp : Application() {
    @Inject
    lateinit var component: DaggerComponent

    override fun onCreate() {
        super.onCreate()
        DaggerDaggerComponent.builder()
            .syncthingModule(SyncthingModule(this))
            .build()
            .inject(this)

        // Set VM policy to avoid crash when sending folder URI to file manager.
        val vmPolicy = VmPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .build()
        StrictMode.setVmPolicy(vmPolicy)

        // Detect disk and network access on the main thread in debug builds.
        if (Constants.isDebuggable(this)) {
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }

    fun component(): DaggerComponent {
        return component
    }
}
