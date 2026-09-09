package com.micnubinub.syncthing.service

import android.os.Binder

class SyncthingServiceBinder(val service: SyncthingService) : Binder()
