package com.micnubinub.syncthing.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

object ReceiverManager {
    private const val TAG = "ReceiverManager"
    private const val ENABLE_VERBOSE_LOG = false
    private val receivers: MutableList<BroadcastReceiver> = ArrayList()

    @Synchronized
    fun registerReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        intentFilter: IntentFilter
    ) {
        receivers.add(receiver)
        ContextCompat.registerReceiver(
            context,
            receiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LogV("Registered receiver: $receiver with filter: $intentFilter")
    }

    @Synchronized
    fun isReceiverRegistered(receiver: BroadcastReceiver?): Boolean {
        return receivers.contains(receiver)
    }

    @Synchronized
    fun unregisterAllReceivers(context: Context?) {
        if (context == null) {
            Log.e(TAG, "unregisterReceiver: context is null")
            return
        }
        val iter = receivers.iterator()
        while (iter.hasNext()) {
            val receiver = iter.next()
            if (isReceiverRegistered(receiver)) {
                try {
                    context.unregisterReceiver(receiver)
                    LogV("Unregistered receiver: $receiver")
                } catch (e: IllegalArgumentException) {
                    // We have to catch the race condition a registration is still pending in android
                    // according to https://stackoverflow.com/a/3568906
                    Log.w(
                        TAG,
                        "unregisterReceiver($receiver) threw IllegalArgumentException"
                    )
                }
                iter.remove()
            }
        }
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }
}
