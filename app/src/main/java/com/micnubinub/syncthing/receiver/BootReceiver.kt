package com.micnubinub.syncthing.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.micnubinub.syncthing.service.AppPrefs
import com.micnubinub.syncthing.service.SyncthingService

class BootReceiver : BroadcastReceiver() {
    /**
     * For testing purposes:
     * adb root & adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // Check if we should (re)start now.
        if (!AppPrefs.getStartServiceOnBoot(context)) {
            return
        }

        startServiceCompat(context)
    }

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * Workaround for starting service from background on Android 8+.
         * 
         * https://stackoverflow.com/a/44505719/1837158
         */
        fun startServiceCompat(context: Context) {
            val intent = Intent(context, SyncthingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
