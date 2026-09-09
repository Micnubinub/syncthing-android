package com.micnubinub.syncthing.activities

import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.navigation.LocalServiceUpdateTick
import com.micnubinub.syncthing.navigation.LocalSyncthingService
import com.micnubinub.syncthing.navigation.RootNavDisplay
import com.micnubinub.syncthing.navigation.RootNavigator
import com.micnubinub.syncthing.navigation.RootRoute
import com.micnubinub.syncthing.navigation.rememberRootNavBackStack
import com.micnubinub.syncthing.navigation.rememberRootNavigator
import com.micnubinub.syncthing.service.AppPrefs
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.service.SyncthingService.OnServiceStateChangeListener
import com.micnubinub.syncthing.service.SyncthingServiceBinder
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.viewModels.MainActivityViewModel
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.LocalActivityScope
import com.micnubinub.syncthing.util.PermissionUtil
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Root host of the app. Owns the single root back stack ([RootNavDisplay]) and the Syncthing
 * service connection, exposes the service to every destination via [LocalSyncthingService].
 */
class MainActivity : SyncthingActivity(), OnServiceStateChangeListener {
    private var ENABLE_VERBOSE_LOG = false
    private var restartDialog: Dialog? = null
    private var lastIntent by mutableStateOf<Intent?>(null)
    private var syncthingServiceState by mutableStateOf<SyncthingService?>(null)
    private var serviceUpdateTick by mutableIntStateOf(0)
    private var rootNavigator: RootNavigator? = null

    @Inject
    lateinit var preferences: SharedPreferences

    @Inject
    lateinit var config: ConfigRouter

    private val viewModel by viewModels<MainActivityViewModel>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
//        config = ConfigRouter(this)
        ENABLE_VERBOSE_LOG = AppPrefs.getPrefVerboseLog(preferences)
        if (ENABLE_VERBOSE_LOG) {
            Util.testPathEllipsis()
        }
        enableEdgeToEdge()

        val initialRoutes = computeInitialRoutes()

        setContent {
            ApplicationTheme {
                val backStack = rememberRootNavBackStack(initialRoutes)
                val onExit: (RootRoute) -> Unit = { route ->
                    if (route == RootRoute.Main) {
                        moveTaskToBack(true)
                    } else {
                        finish()
                    }
                }
                val navigator = rememberRootNavigator(backStack, onExit)

                SideEffect {
                    rootNavigator = navigator
                }

                // Handle intents that target a specific screen (notifications, sharing).
                LaunchedEffect(lastIntent) {
                    lastIntent?.let { handleLaunchIntent(it, navigator) }
                }

                CompositionLocalProvider(
                    LocalActivityScope provides lifecycleScope,
                    LocalSyncthingService provides syncthingServiceState,
                    LocalServiceUpdateTick provides serviceUpdateTick,
                ) {
                    RootNavDisplay(
                        backStack = backStack,
                        navigator = navigator,
                        preferences = preferences,
                        mainViewModel = viewModel,
                        configRouter = config,
                    )
                }
            }
        }

        /**
         * SyncthingService needs to be started from this activity as the user
         * can directly launch this activity from the recent activity switcher.
         * On a fresh install (or when permissions were revoked) the app routes
         * into onboarding where no parseable config.xml exists yet. Starting the
         * foreground service there would make SyncthingService stop itself on the
         * unreadable config without ever calling startForeground(), which crashes
         * the app on Android 8+ (ForegroundServiceDidNotStartInTimeException).
         * Onboarding starts the service itself once a config exists.
         */
        if (initialRoutes == listOf(RootRoute.Main)) {
            val serviceIntent = Intent(this, SyncthingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        lastIntent = intent
        super.onNewIntent(intent)
    }

    public override fun onResume() {
        super.onResume()
        // Check if storage permission has been revoked at runtime and send the user through
        // onboarding again instead of leaving them with a broken main screen.
        if (!PermissionUtil.haveStoragePermission(this)) {
            rootNavigator?.resetTo(listOf(RootRoute.Onboarding))
            return
        }

        // Evaluate run conditions to detect changes made to the metered wifi flags.
        service?.evaluateRunConditions()
    }

    public override fun onDestroy() {
        super.onDestroy()
        service?.unregisterOnServiceStateChangeListener(this)
    }

    /**
     * Handles various dialogs based on current state.
     */
    override fun onServiceStateChange(currentState: SyncthingService.State) {
        viewModel.setServiceState(currentState)
        // Update status light indicating if syncthing is running.
        serviceUpdateTick++

        when (currentState) {
            SyncthingService.State.ACTIVE -> {
                fetchInitialData()
                // Check if the usage reporting minimum delay passed by.
                val usageReportingDelayPassed =
                    (Date().time > this.firstStartTime + USAGE_REPORTING_DIALOG_DELAY)
                api?.let {
                    if (!AppPrefs.isUsageReportingDialogAnswered(preferences) &&
                        (DEBUG_FORCE_USAGE_REPORTING_DIALOG ||
                                (usageReportingDelayPassed &&
                                        it.isConfigLoaded && !it.isUsageReportingDecided))
                    ) {
                        showUsageReportingDialog(it)
                    }
                }
            }

            SyncthingService.State.ERROR -> finish()

            else -> {}
        }
    }

    /**
     * Fetches devices, folders and status as soon as the service is up.
     * Warms the [RestApi] caches so the main screen tabs show data on their
     * first poll instead of waiting for their own cache-miss queries.
     */
    private fun fetchInitialData() {
        val restApi = api ?: return
        if (!restApi.isConfigLoaded) {
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = config.getDevices(restApi, true)
            val folders = config.getFolders(restApi)
            // A single query with an empty device id refreshes connections and
            // last-seen timestamps for all devices at once.
            restApi.getRemoteDeviceStatus("")
            folders.forEach { restApi.getFolderStatus(it.id) }
            restApi.getSystemStatus(RestApi.OnResultListener { })
        }
    }

    private val firstStartTime: Long
        /**
         * Returns the unix timestamp at which the app was first installed.
         */
        get() {
            var firstInstallTime: Long = try {
                packageManager.getPackageInfo(packageName, 0).firstInstallTime
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "This should never happen", e)
                0
            }
            return firstInstallTime
        }

    override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
        super.onServiceConnected(componentName, iBinder)
        val syncthingService = (iBinder as SyncthingServiceBinder).service
        syncthingService.registerOnServiceStateChangeListener(this)
        viewModel.setService(syncthingService)
        syncthingServiceState = syncthingService
        serviceUpdateTick++
    }

    override fun onServiceDisconnected(componentName: ComponentName?) {
        super.onServiceDisconnected(componentName)
        viewModel.setServiceState(SyncthingService.State.INIT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Util.dismissDialogSafe(restartDialog, this)
    }

    fun showRestartDialog() {
        restartDialog = AlertDialog.Builder(this)
            .setMessage(R.string.dialog_confirm_restart)
            .setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { _: DialogInterface?, _: Int ->
                    this.startService(
                        Intent(this, SyncthingService::class.java)
                            .setAction(SyncthingService.ACTION_RESTART)
                    )
                })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        restartDialog?.show()
    }

    fun showQrCodeDialog() {
        val deviceId: String = preferences.getString(Constants.PREF_LOCAL_DEVICE_ID, "").orEmpty()
        if (deviceId.isEmpty()) {
            Toast.makeText(this, R.string.could_not_access_deviceid, Toast.LENGTH_SHORT).show()
            return
        }
        // getDevices performs blocking config I/O or child-process exec when the
        // REST API is unavailable; keep it off the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = config.getDevices(api, true)
            var deviceName = ""

            for (d in devices) {
                if (d.deviceID == deviceId) {
                    deviceName = d.displayName.orEmpty()
                    break
                }
            }

            viewModel.showDeviceIdDialog(deviceName.trim { it <= ' ' }, deviceId)
        }
    }

    /**
     * Toggles the drawer on menu button press.
     */
    fun closeDrawer() = viewModel.setDrawerState(DrawerValue.Closed)

    override fun onKeyDown(keyCode: Int, e: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            viewModel.setDrawerState(DrawerValue.Open)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            viewModel.setDrawerState(DrawerValue.Closed)
            return true
        }
        return super.onKeyDown(keyCode, e)
    }

    /**
     * Displays dialog asking user to accept/deny usage reporting.
     */
    private fun showUsageReportingDialog(restApi: RestApi) {
        Log.v(TAG, "showUsageReportingDialog triggered.")
        restApi.getUsageReport(RestApi.OnResultListener { report: String? ->
            viewModel.dismissUsageReportingDialog()
            report?.let {
                viewModel.showUsageReportingDialog(it)
            }
        })
    }

    /**
     * Exits the application by stopping the service and finishing the activity.
     * This method is called directly from the FAB since it's only visible when safe to exit.
     * Also called from drawer's exit button
     */
    fun doExit() {
        if (isFinishing) {
            return
        }
        Log.i(TAG, "Exiting app on user request")
        stopService(Intent(this, SyncthingService::class.java))
        finishAndRemoveTask()
    }

    /**
     * Handles intents targeting a specific screen (notifications). The special
     * actions and navigation extras are only honored when the intent was
     * created by this app (its notification PendingIntents embed the per-install
     * token); MainActivity is exported for the launcher, so any other sender is
     * ignored to prevent e.g. an external app from stopping sync or opening
     * editors with arbitrary IDs.
     */
    private fun handleLaunchIntent(intent: Intent, navigator: RootNavigator) {
        if (intent.action != ACTION_EXIT &&
            !intent.getBooleanExtra(EXTRA_SHOW_LOG, false) &&
            intent.getStringExtra(EXTRA_FOLDER_ID).isNullOrBlank() &&
            intent.getStringExtra(EXTRA_DEVICE_ID).isNullOrBlank()
        ) {
            return
        }
        if (intent.getStringExtra(EXTRA_INTERNAL_TOKEN) !=
            AppPrefs.getInternalIntentToken(preferences)
        ) {
            Log.w(TAG, "handleLaunchIntent: ignoring intent without valid internal token")
            return
        }
        if (intent.action == ACTION_EXIT) {
            Log.i(TAG, "Exit app requested by notification action")
            doExit()
            return
        }
        val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val isCreate = intent.getBooleanExtra(EXTRA_IS_CREATE, false)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        when {
            intent.getBooleanExtra(EXTRA_SHOW_LOG, false) -> navigator.navigateTo(RootRoute.Log)

            isPlausibleFolderId(folderId) -> navigator.navigateTo(
                RootRoute.Folder(
                    isCreate = isCreate,
                    folderId = folderId,
                    folderLabel = intent.getStringExtra(EXTRA_FOLDER_LABEL),
                    shareWithDeviceId = deviceId,
                    receiveEncrypted = intent.getBooleanExtra(EXTRA_RECEIVE_ENCRYPTED, false),
                    notificationId = notificationId,
                )
            )

            isPlausibleDeviceId(deviceId) -> navigator.navigateTo(
                RootRoute.Device(
                    isCreate = isCreate,
                    deviceId = deviceId,
                    deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    notificationId = notificationId,
                )
            )
        }
    }

    private fun isPlausibleFolderId(folderId: String?): Boolean {
        if (folderId.isNullOrBlank()) return false
        return folderId.length <= 64 &&
                !folderId.contains('/') &&
                !folderId.contains('\\') &&
                !folderId.contains('\u0000') &&
                folderId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun isPlausibleDeviceId(deviceId: String?): Boolean {
        if (deviceId.isNullOrBlank()) return false
        val normalized = deviceId.filter { it.isLetterOrDigit() }
        return normalized.length == 56 &&
                normalized.all {
                    it.lowercaseChar() in 'a'..'z' || it in '2'..'7'
                }
    }

    private fun computeInitialRoutes(): List<RootRoute> {
        return if (PermissionUtil.haveStoragePermission(this) &&
            haveNotificationPermission() &&
            checkForParseableConfig()
        ) {
            listOf(RootRoute.Main)
        } else {
            listOf(RootRoute.Onboarding)
        }
    }

    private fun haveNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkForParseableConfig(): Boolean {
        return Constants.getConfigFile(this).exists()
    }

    companion object {
        /**
         * Intent action to exit app.
         */
        const val ACTION_EXIT: String = ".MainActivity.EXIT"

        /**
         * Intent extras used to open a specific screen directly (from notifications or
         * [com.micnubinub.syncthing.service.NotificationHandler]).
         */
        const val EXTRA_SHOW_LOG: String = ".MainActivity.SHOW_LOG"
        const val EXTRA_DEVICE_ID: String = ".MainActivity.DEVICE_ID"
        const val EXTRA_DEVICE_NAME: String = ".MainActivity.DEVICE_NAME"
        const val EXTRA_IS_CREATE: String = ".MainActivity.IS_CREATE"
        const val EXTRA_FOLDER_ID: String = ".MainActivity.FOLDER_ID"
        const val EXTRA_FOLDER_LABEL: String = ".MainActivity.FOLDER_LABEL"
        const val EXTRA_RECEIVE_ENCRYPTED: String = ".MainActivity.RECEIVE_ENCRYPTED"
        const val EXTRA_NOTIFICATION_ID: String = ".MainActivity.NOTIFICATION_ID"

        /**
         * Extra carrying the per-install token proving the intent originated from
         * this app's notification PendingIntents (see [AppPrefs.getInternalIntentToken]).
         */
        const val EXTRA_INTERNAL_TOKEN: String = ".MainActivity.INTERNAL_TOKEN"

        /**
         * Time after first start when usage reporting dialog should be shown.
         * See [.showUsageReportingDialog]
         */
        private val USAGE_REPORTING_DIALOG_DELAY = TimeUnit.DAYS.toMillis(3)
        private const val DEBUG_FORCE_USAGE_REPORTING_DIALOG = false
    }
}
