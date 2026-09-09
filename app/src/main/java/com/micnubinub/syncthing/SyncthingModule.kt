package com.micnubinub.syncthing

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.micnubinub.syncthing.service.NotificationHandler
import com.micnubinub.syncthing.util.ConfigRouter
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class SyncthingModule(private val mApp: SyncthingApp) {
    @get:Singleton
    @get:Provides
    val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(mApp)

    @Provides
    @Singleton
    fun getConfigRouter(): ConfigRouter = ConfigRouter(mApp)

    @Provides
    @Singleton
    fun getNotificationHandler(preferences: SharedPreferences): NotificationHandler {
        return NotificationHandler(mApp, preferences)
    }
}
