package com.micnubinub.syncthing.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.ServiceCompat.STOP_FOREGROUND_DETACH
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.activities.MainActivity

class NotificationHandler(
    private val context: Context,
    private val preferences: SharedPreferences
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val persistentChannel: NotificationChannel?
    private val persistentChannelWaiting: NotificationChannel?
    private val infoChannel: NotificationChannel?

    private var lastNotificationText: String? = null
    private var lastStartForegroundService = false
    private var appShutdownInProgress = false

    // Dagger2 constructor injection - receives SharedPreferences directly to avoid circular dependency
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            persistentChannel = NotificationChannel(
                CHANNEL_PERSISTENT, context.getString(R.string.notifications_persistent_channel),
                NotificationManager.IMPORTANCE_MIN
            )
            persistentChannel.enableLights(false)
            persistentChannel.enableVibration(false)
            persistentChannel.setSound(null, null)
            persistentChannel.setShowBadge(false)
            persistentChannel.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(persistentChannel)

            persistentChannelWaiting = NotificationChannel(
                CHANNEL_PERSISTENT_WAITING,
                context.getString(R.string.notification_persistent_waiting_channel),
                NotificationManager.IMPORTANCE_MIN
            )
            persistentChannelWaiting.enableLights(false)
            persistentChannelWaiting.enableVibration(false)
            persistentChannelWaiting.setSound(null, null)
            persistentChannelWaiting.setShowBadge(false)
            persistentChannelWaiting.lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(persistentChannelWaiting)

            infoChannel = NotificationChannel(
                CHANNEL_INFO, context.getString(R.string.notifications_other_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            infoChannel.enableVibration(false)
            infoChannel.setSound(null, null)
            infoChannel.setShowBadge(true)
            notificationManager.createNotificationChannel(infoChannel)
        } else {
            persistentChannel = null
            persistentChannelWaiting = null
            infoChannel = null
        }
    }

    private fun getNotificationBuilder(channel: NotificationChannel): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channel.id)
    }

    /**
     * Shows, updates or hides the notification.
     */
    @JvmOverloads
    fun updatePersistentNotification(
        service: SyncthingService,
        persistNotificationDetails: Boolean = true,
        onlineDeviceCount: Int = 0,
        totalSyncCompletion: Int = 0
    ) {
        val startServiceOnBoot =
            preferences.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)
        val currentServiceState = service.currentState
        val syncthingRunning = currentServiceState == SyncthingService.State.ACTIVE ||
                currentServiceState == SyncthingService.State.STARTING
        var startForegroundService = false
        if (!appShutdownInProgress) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                /**
                 * Android 7 and lower:
                 * The app may run in background and monitor run conditions even if it is not
                 * running as a foreground service. For that reason, we can use a normal
                 * notification if syncthing is DISABLED.
                 */
                startForegroundService = startServiceOnBoot || syncthingRunning
            } else {
                /**
                 * Android 8+:
                 * Always use startForeground.
                 * This makes sure the app is not killed, and we don't miss run condition events.
                 * On Android 8+, this behaviour is mandatory to receive broadcasts.
                 * https://stackoverflow.com/a/44505719/1837158
                 * Foreground priority requires a notification so this ensures that we either have a
                 * "default" or "low_priority" notification, but not "none".
                 */
                startForegroundService = true
            }
        }

        // Check if we have to stopForeground.
        if (startForegroundService != lastStartForegroundService) {
            if (!startForegroundService) {
                Log.v(TAG, "Stopping foreground service")
                ServiceCompat.stopForeground(service, STOP_FOREGROUND_DETACH)
            }
        }

        // Prepare notification builder.
        val text: String? = when (currentServiceState) {
            SyncthingService.State.ERROR, SyncthingService.State.INIT ->
                context.getString(R.string.syncthing_terminated)

            SyncthingService.State.DISABLED ->
                context.getString(R.string.syncthing_disabled)

            SyncthingService.State.STARTING ->
                context.getString(R.string.syncthing_starting)

            SyncthingService.State.ACTIVE -> {
                if (lastNotificationText == null || !persistNotificationDetails) {
                    if (totalSyncCompletion == -1) {
                        lastNotificationText = context.getString(
                            R.string.syncthing_active_details,
                            context.getString(R.string.no_remote_devices_connected)
                        )
                    } else if (totalSyncCompletion == 100) {
                        lastNotificationText = context.getString(
                            R.string.syncthing_active_details,
                            context.resources.getQuantityString(
                                R.plurals.device_online_up_to_date,
                                onlineDeviceCount,
                                onlineDeviceCount
                            )
                        )
                    } else {
                        lastNotificationText = context.resources.getQuantityString(
                            R.plurals.syncthing_active_syncing_device_online,
                            onlineDeviceCount,
                            totalSyncCompletion,
                            onlineDeviceCount
                        )
                    }
                }
                lastNotificationText
            }
        }

        /**
         * Reason for two separate IDs: if one of the notification channels is hidden then
         * the startForeground() below won't update the notification but use the old one.
         */
        val idToShow: Int = if (syncthingRunning) ID_PERSISTENT else ID_PERSISTENT_WAITING
        val idToCancel: Int = if (syncthingRunning) ID_PERSISTENT_WAITING else ID_PERSISTENT

        val openAppIntent = Intent(context, MainActivity::class.java)

        val exitIntent = Intent(context, MainActivity::class.java)
        exitIntent.action = MainActivity.ACTION_EXIT
        exitIntent.putExtra(
            MainActivity.EXTRA_INTERNAL_TOKEN,
            AppPrefs.getInternalIntentToken(preferences)
        )
        val exitPendingIntent = PendingIntent.getActivity(
            context,
            0,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        (if (syncthingRunning) persistentChannel else persistentChannelWaiting)?.let {
            val builder = getNotificationBuilder(it)
                .setContentTitle(text)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        openAppIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.exit),
                    exitPendingIntent
                )
            if (!appShutdownInProgress) {
                if (startForegroundService) {
                    Log.v(TAG, "Starting foreground service or updating notification")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        service.startForeground(
                            idToShow,
                            builder.build(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        )
                    } else {
                        service.startForeground(idToShow, builder.build())
                    }
                } else {
                    Log.v(TAG, "Updating notification")
                    notificationManager.notify(idToShow, builder.build())
                }
            } else {
                notificationManager.cancel(idToShow)
            }
        }
        notificationManager.cancel(idToCancel)
        // Remember last notification visibility.
        lastStartForegroundService = startForegroundService
    }

    /**
     * Called by [SyncthingService.onStart] [SyncthingService.onDestroy]
     * to indicate app startup and shutdown.
     */
    fun setAppShutdownInProgress(newValue: Boolean) {
        appShutdownInProgress = newValue
    }

    fun showCrashedNotification(@StringRes title: Int, extraInfo: String?) {
        infoChannel?.let {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_LOG, true)
                .putExtra(
                    MainActivity.EXTRA_INTERNAL_TOKEN,
                    AppPrefs.getInternalIntentToken(preferences)
                )
            val n = getNotificationBuilder(it)
                .setContentTitle(context.getString(title, extraInfo))
                .setContentText(context.getString(R.string.notification_crash_text, extraInfo))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        ID_CRASH,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setAutoCancel(true)
                .build()
            notificationManager.notify(ID_CRASH, n)
        }
    }

    /**
     * Calculate a deterministic ID between 1000 and 2000 to avoid duplicate
     * notification ids for different device, folder consent popups triggered
     * by [EventProcessor].
     */
    fun getNotificationIdFromText(text: String): Int {
        return 1000 + (text.hashCode().and(0x7fffffff) % 1000)
    }

    /**
     * Closes a notification. Required after the user hit an action button.
     */
    fun cancelConsentNotification(notificationId: Int) {
        if (notificationId == 0) {
            return
        }
        Log.v(TAG, "Cancelling notification with id $notificationId")
        notificationManager.cancel(notificationId)
    }

    /**
     * Used by [EventProcessor]
     */
    fun showConsentNotification(
        notificationId: Int,
        text: String?,
        piAccept: PendingIntent?,
        piIgnore: PendingIntent?
    ) {
        /**
         * As we know the id for a specific notification text,
         * we'll dismiss this notification as it may be outdated.
         * This is also valid if the notification does not exist.
         */
        notificationManager.cancel(notificationId)
        infoChannel?.let {
            val n = getNotificationBuilder(infoChannel)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(text)
                )
                .setContentIntent(piAccept)
                .addAction(R.drawable.ic_stat_notify, context.getString(R.string.accept), piAccept)
                .addAction(R.drawable.ic_stat_notify, context.getString(R.string.ignore), piIgnore)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(notificationId, n)
        }
    }

    fun showStoragePermissionRevokedNotification() {
        infoChannel?.let {
            val n = getNotificationBuilder(infoChannel)
                .setContentTitle(context.getString(R.string.syncthing_terminated))
                .setContentText(context.getString(R.string.toast_write_storage_permission_required))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            notificationManager.notify(ID_MISSING_PERM, n)
        }
    }

    fun cancelRestartNotification() {
        notificationManager.cancel(ID_RESTART)
    }

    fun showDeviceConnectNotification(
        deviceId: String?,
        deviceName: String,
        deviceAddress: String?
    ) {
        if (deviceId == null) {
            Log.e(TAG, "showDeviceConnectNotification: deviceId == null")
            return
        }
        val title = context.getString(
            R.string.device_rejected,
            if (deviceName.isEmpty()) deviceId.take(7) else deviceName
        )
        val notificationId = getNotificationIdFromText(title)

        // Prepare "accept" action.
        val intentAccept = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(MainActivity.EXTRA_IS_CREATE, true)
            .putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(MainActivity.EXTRA_DEVICE_NAME, deviceName)
            .putExtra(
                MainActivity.EXTRA_INTERNAL_TOKEN,
                AppPrefs.getInternalIntentToken(preferences)
            )
        val piAccept = PendingIntent.getActivity(
            context, notificationId,
            intentAccept, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Prepare "ignore" action.
        val intentIgnore = Intent(context, SyncthingService::class.java)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_DEVICE_NAME, deviceName)
            .putExtra(SyncthingService.EXTRA_DEVICE_ADDRESS, deviceAddress)
        intentIgnore.action = SyncthingService.ACTION_IGNORE_DEVICE
        val piIgnore = PendingIntent.getService(
            context, notificationId + 100_000,
            intentIgnore, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Show notification.
        showConsentNotification(notificationId, title, piAccept, piIgnore)
    }

    fun showFolderShareNotification(
        deviceId: String?,
        deviceName: String?,
        folderId: String?,
        folderLabel: String,
        receiveEncrypted: Boolean?,
        remoteEncrypted: Boolean?,
        isNewFolder: Boolean?
    ) {
        if (deviceId == null) {
            Log.e(TAG, "showFolderShareNotification: deviceId == null")
            return
        }
        if (folderId == null) {
            Log.e(TAG, "showFolderShareNotification: folderId == null")
            return
        }
        val title = context.getString(
            R.string.folder_rejected, deviceName,
            if (folderLabel.isEmpty()) folderId else "$folderLabel ($folderId)"
        )
        val notificationId = getNotificationIdFromText(title)

        // Prepare "accept" action.
        val intentAccept = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(MainActivity.EXTRA_IS_CREATE, isNewFolder)
            .putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(MainActivity.EXTRA_FOLDER_ID, folderId)
            .putExtra(MainActivity.EXTRA_FOLDER_LABEL, folderLabel)
            .putExtra(MainActivity.EXTRA_RECEIVE_ENCRYPTED, receiveEncrypted)
            .putExtra(
                MainActivity.EXTRA_INTERNAL_TOKEN,
                AppPrefs.getInternalIntentToken(preferences)
            )
        val piAccept = PendingIntent.getActivity(
            context, notificationId,
            intentAccept, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Prepare "ignore" action.
        val intentIgnore = Intent(context, SyncthingService::class.java)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_FOLDER_ID, folderId)
            .putExtra(SyncthingService.EXTRA_FOLDER_LABEL, folderLabel)
        intentIgnore.action = SyncthingService.ACTION_IGNORE_FOLDER
        val piIgnore = PendingIntent.getService(
            context, notificationId + 200_000,
            intentIgnore, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Show notification.
        showConsentNotification(notificationId, title, piAccept, piIgnore)
    }

    companion object {
        private const val TAG = "NotificationHandler"
        private const val ID_PERSISTENT = 1
        private const val ID_PERSISTENT_WAITING = 4
        private const val ID_RESTART = 2
        private const val ID_STOP_BACKGROUND_WARNING = 3
        private const val ID_CRASH = 9
        private const val ID_MISSING_PERM = 10
        private const val CHANNEL_PERSISTENT = "01_syncthing_persistent"
        private const val CHANNEL_INFO = "02_syncthing_notifications"
        private const val CHANNEL_PERSISTENT_WAITING = "03_syncthing_persistent_waiting"
    }
}
