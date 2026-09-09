package com.micnubinub.syncthing

import com.micnubinub.syncthing.activities.MainActivity
import com.micnubinub.syncthing.activities.PhotoShootActivity
import com.micnubinub.syncthing.activities.ShareActivity
import com.micnubinub.syncthing.receiver.AppConfigReceiver
import com.micnubinub.syncthing.service.EventProcessor
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.RunConditionMonitor
import com.micnubinub.syncthing.service.SyncthingRunnable
import com.micnubinub.syncthing.service.SyncthingService
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [SyncthingModule::class])
interface DaggerComponent {
    fun inject(appConfigReceiver: AppConfigReceiver?)
    fun inject(eventProcessor: EventProcessor?)
    fun inject(activity: MainActivity?)
    fun inject(photoShootActivity: PhotoShootActivity?)
    fun inject(restApi: RestApi?)
    fun inject(runConditionMonitor: RunConditionMonitor?)
    fun inject(activity: ShareActivity?)
    fun inject(app: SyncthingApp?)
    fun inject(syncthingRunnable: SyncthingRunnable?)
    fun inject(service: SyncthingService?)
}
