package com.micnubinub.syncthing.service

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.http.PollWebGuiAvailableTask
import com.micnubinub.syncthing.http.SyncthingHttpClients
import com.micnubinub.syncthing.service.AppPrefs.getPrefVerboseLog
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS
import com.micnubinub.syncthing.service.Constants.getConfigFile
import com.micnubinub.syncthing.service.Constants.getHttpsCertFile
import com.micnubinub.syncthing.service.Constants.getHttpsKeyFile
import com.micnubinub.syncthing.service.Constants.getIndexDbFolder
import com.micnubinub.syncthing.service.Constants.getPrivateKeyFile
import com.micnubinub.syncthing.service.Constants.getPublicKeyFile
import com.micnubinub.syncthing.service.Constants.getSharedPrefsFile
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.ConfigXml
import com.micnubinub.syncthing.util.ConfigXml.OpenConfigException
import com.micnubinub.syncthing.util.FileUtils.deleteDirectoryRecursively
import com.micnubinub.syncthing.util.PermissionUtil.haveStoragePermission
import com.micnubinub.syncthing.util.Util.isTcpPortListening
import com.micnubinub.syncthing.util.Util.killProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject

/**
 * Holds the native syncthing instance and provides an API to access it.
 */
class SyncthingService : Service() {
    private val onServiceStateChangeListeners = CopyOnWriteArraySet<OnServiceStateChangeListener>()
    private val binder = SyncthingServiceBinder(this)

    /**
     * Object that must be locked upon accessing CurrentState
     */
    private val stateLock = Any()

    @Inject
    lateinit var notificationHandler: NotificationHandler

    @Inject
    lateinit var preferences: SharedPreferences

    private var ENABLE_VERBOSE_LOG = false

    /**
     * Initialize the service with State.DISABLED as [RunConditionMonitor] will
     * send an update if we should run the binary after it got instantiated in
     * [.onStartCommand].
     */
    @Volatile
    var currentState: State = State.DISABLED
        private set
    private val gson = Gson()

    /**
     * Classes that may appear when deserializing a legacy Java-serialized
     * shared preferences backup. Arrays of these are allowed as well (their
     * elements are resolved and checked individually).
     */
    private val legacySerializedPrefsClassAllowlist = setOf(
        "java.lang.String",
        "java.lang.Boolean",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Number",
        "java.util.HashMap",
        "java.util.HashSet"
    )
    private var configRouter: ConfigRouter? = null
    private var config: ConfigXml? = null

    @Volatile
    private var syncthingRunnableThread: Thread? = null
    private lateinit var serviceScope: CoroutineScope
    private lateinit var shutdownScope: CoroutineScope
    private var deferredShutdownJob: Job? = null
    private val shutdownMutex = Mutex()

    @Volatile
    private var pollWebGuiAvailableTask: PollWebGuiAvailableTask? = null

    @Volatile
    var api: RestApi? = null
        private set

    @Volatile
    private var eventProcessor: EventProcessor? = null
    private var runConditionMonitor: RunConditionMonitor? = null

    @Volatile
    private var syncthingRunnable: SyncthingRunnable? = null

    /**
     * Stores the result of the last should run decision received by OnShouldRunChangedListener.
     */
    private var lastDeterminedShouldRun = false

    /**
     * True if the user granted the storage permission.
     */
    private var storagePermissionGranted = false

    /**
     * Starts the native binary.
     */
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        (application as SyncthingApp).component().inject(this)
        ENABLE_VERBOSE_LOG = getPrefVerboseLog(preferences)
        LogV("onCreate")
        configRouter = ConfigRouter(this@SyncthingService)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * If runtime permissions are revoked, android kills and restarts the service.
         * We need to recheck if we still have the storage permission.
         */
        storagePermissionGranted = haveStoragePermission(this)
        notificationHandler.setAppShutdownInProgress(false)
    }

    /**
     * Handles intent actions, e.g. [.ACTION_RESTART]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!storagePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.")
            notificationHandler.showStoragePermissionRevokedNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        /**
         * Must run before anything that can asynchronously fail (e.g. the run
         * condition monitor reading the config below). When this service was
         * started via startForegroundService(), calling stopSelf() before
         * startForeground() makes the system kill the app with
         * ForegroundServiceDidNotStartInTimeException.
         */
        notificationHandler.updatePersistentNotification(this)

        // Run condition monitor is enabled.
        if (runConditionMonitor == null) {
            /**
             * Instantiate the run condition monitor on first onStartCommand and
             * enable callback on run condition change affecting the final decision to
             * run/terminate syncthing. After initial run conditions are collected
             * the first decision is sent to [onShouldRunDecisionChanged].
             */
            runConditionMonitor = RunConditionMonitor(
                this@SyncthingService,
                { newShouldRunDecision: Boolean ->
                    onShouldRunDecisionChanged(
                        newShouldRunDecision
                    )
                },
                { runConditionMonitor: RunConditionMonitor? ->
                    runConditionMonitor?.let {
                        applyCustomRunConditions(
                            it
                        )
                    }
                }
            )
        }

        if (intent == null) {
            return START_STICKY
        }

        if (ACTION_RESTART == intent.action && synchronized(stateLock) { currentState == State.ACTIVE }) {
            shutdownScope.launch {
                shutdown(State.INIT)
                launchStartupTask(SyncthingRunnable.Command.main)
            }
        } else if (ACTION_STOP == intent.action) {
            if (intent.getBooleanExtra(EXTRA_STOP_AFTER_CRASHED_NATIVE, false)) {
                /**
                 * We were requested to stop the service because the syncthing native binary crashed.
                 * Changing mCurrentState prevents the "defer until syncthing is started" routine we normally
                 * use for clean shutdown to take place. Instead, we will immediately shutdown the crashed
                 * instance forcefully.
                 */
                synchronized(stateLock) { currentState = State.ERROR }
                shutdownScope.launch { shutdown(State.DISABLED) }
            } else {
                // Graceful shutdown.
                if (synchronized(stateLock) {
                        currentState == State.STARTING || currentState == State.ACTIVE
                    }) {
                    shutdownScope.launch { shutdown(State.DISABLED) }
                }
            }
        } else if (ACTION_RESET_DATABASE == intent.action) {
            /**
             * 1. Stop syncthing native if it's running.
             * 2. Reset the database, syncthing native will exit after performing the reset.
             * 3. Relaunch syncthing native if it was previously running.
             */
            Log.i(TAG, "Invoking reset of database")
            shutdownScope.launch {
                if (synchronized(stateLock) { currentState } != State.DISABLED) {
                    shutdown(State.DISABLED)
                }
                SyncthingRunnable(
                    this@SyncthingService,
                    SyncthingRunnable.Command.resetdatabase
                ).run()
                if (synchronized(stateLock) { lastDeterminedShouldRun }) {
                    launchStartupTask(SyncthingRunnable.Command.main)
                }
            }
        } else if (ACTION_RESET_DELTAS == intent.action) {
            /**
             * 1. Stop syncthing native if it's running.
             * 2. Reset delta index, syncthing native will NOT exit after performing the reset.
             * 3. If syncthing was previously NOT running:
             * 3.1  Schedule a shutdown of the native binary after it left State.STARTING (to State.ACTIVE).
             * This is the moment, when the reset delta index work was completed and Web UI came up.
             * 3.2  The shutdown gets deferred until State.ACTIVE was reached and then syncthing native will
             * be shutdown synchronously.
             */
            Log.i(TAG, "Invoking reset of delta indexes")
            shutdownScope.launch {
                if (synchronized(stateLock) { currentState } != State.DISABLED) {
                    // Shutdown synchronously.
                    shutdown(State.DISABLED)
                }
                launchStartupTask(SyncthingRunnable.Command.resetdeltas)
                if (!synchronized(stateLock) { lastDeterminedShouldRun }) {
                    // Shutdown if syncthing was not running before the UI action was raised.
                    shutdown(State.DISABLED)
                }
            }
        } else if (ACTION_REFRESH_NETWORK_INFO == intent.action) {
            runConditionMonitor?.updateShouldRunDecision()
        } else if (ACTION_IGNORE_DEVICE == intent.action) {
            configRouter?.ignoreDevice(
                api,
                intent.getStringExtra(EXTRA_DEVICE_ID) ?: "",
                intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "",
                intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: ""
            )
            notificationHandler.cancelConsentNotification(
                intent.getIntExtra(
                    EXTRA_NOTIFICATION_ID,
                    0
                )
            )
        } else if (ACTION_IGNORE_FOLDER == intent.action) {
            configRouter?.ignoreFolder(
                api,
                intent.getStringExtra(EXTRA_DEVICE_ID) ?: "",
                intent.getStringExtra(EXTRA_FOLDER_ID) ?: "",
                intent.getStringExtra(EXTRA_FOLDER_LABEL) ?: ""
            )
            notificationHandler.cancelConsentNotification(
                intent.getIntExtra(
                    EXTRA_NOTIFICATION_ID,
                    0
                )
            )
        } else if (ACTION_OVERRIDE_CHANGES == intent.action && synchronized(stateLock) { currentState == State.ACTIVE }) {
            api?.overrideChanges(intent.getStringExtra(EXTRA_FOLDER_ID))
        } else if (ACTION_REVERT_LOCAL_CHANGES == intent.action && synchronized(stateLock) { currentState == State.ACTIVE }) {
            api?.revertLocalChanges(intent.getStringExtra(EXTRA_FOLDER_ID))
        } else {
            afterFreshServiceInstanceStart()
        }
        return START_STICKY
    }

    /**
     * Event handler ot catch a fresh service startup right after the run condition evaluation took place
     * and SyncthingNative may be starting in the background meanwhilst or non-present.
     */
    private fun afterFreshServiceInstanceStart() {
        val state = synchronized(stateLock) { currentState }
        LogV("afterFreshServiceInstanceStart: Service started from scratch, SyncthingNative is going to STATE_$state meanwhilst ...")
        if (state == State.DISABLED) {
            serviceScope.launch {
                // Read and parse the config from disk.
                val configXml = ConfigXml(this@SyncthingService)
                try {
                    withContext(Dispatchers.IO) {
                        configXml.loadConfig()
                    }
                } catch (e: OpenConfigException) {
                    notificationHandler.showCrashedNotification(
                        R.string.config_read_failed,
                        "afterFreshServiceInstanceStart:OpenConfigException"
                    )
                    synchronized(stateLock) {
                        onServiceStateChange(State.ERROR)
                    }
                    stopSelf()
                }
            }
        }
    }

    /**
     * After run conditions monitored by [RunConditionMonitor] changed and
     * it had an influence on the decision to run/terminate syncthing, this
     * function is called to notify this class to run/terminate the syncthing binary.
     * [.onServiceStateChange] is called while applying the decision change.
     */
    private fun onShouldRunDecisionChanged(newShouldRunDecision: Boolean) {
        val decisionChanged = synchronized(stateLock) {
            val changed = newShouldRunDecision != lastDeterminedShouldRun
            if (changed) {
                lastDeterminedShouldRun = newShouldRunDecision
            }
            changed
        }
        if (!decisionChanged) {
            return
        }

        Log.i(
            TAG,
            "shouldRun decision changed to $newShouldRunDecision according to configured run conditions."
        )

        // React to the shouldRun condition change.
        if (newShouldRunDecision) {
            // Start syncthing.
            val state: State
            synchronized(stateLock) {
                state = currentState
            }
            when (state) {
                State.DISABLED, State.INIT -> serviceScope.launch {
                    launchStartupTask(SyncthingRunnable.Command.main)
                }

                State.STARTING, State.ACTIVE, State.ERROR -> {}
            }
        } else {
            // Stop syncthing.
            val state: State
            synchronized(stateLock) {
                state = currentState
            }
            if (state == State.DISABLED) {
                return
            }
            shutdownScope.launch { shutdown(State.DISABLED) }
        }
    }

    /**
     * After sync preconditions changed, we need to inform [RestApi] to pause or
     * unpause devices and folders as defined in per-object sync preferences.
     */
    private fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(stateLock) {
            // keep null check
            if (api != null && currentState == State.ACTIVE) {
                // Forward event because syncthing is running.
                api?.applyCustomRunConditions(runConditionMonitor)
                return
            }
        }

        serviceScope.launch {
            var configChanged = false

            // Read and parse the config from disk.
            val configXml = ConfigXml(this@SyncthingService)
            try {
                withContext(Dispatchers.IO) {
                    configXml.loadConfig()
                }
            } catch (e: OpenConfigException) {
                notificationHandler.showCrashedNotification(
                    R.string.config_read_failed,
                    "applyCustomRunConditions:OpenConfigException"
                )
                synchronized(stateLock) {
                    onServiceStateChange(State.ERROR)
                }
                stopSelf()
                return@launch
            }

            // Check if the folders are available from config.
            for (folder in configXml.folders) {
                // LogV("applyCustomRunConditions: Processing config of folder(" + folder.getLabel() + ")");
                val folderCustomSyncConditionsEnabled = preferences.getBoolean(
                    DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id),
                    false
                )
                if (folderCustomSyncConditionsEnabled) {
                    val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(
                        Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
                    )
                    LogV("applyCustomRunConditions: f(" + folder.label + ")=" + (if (syncConditionsMet) "1" else "0"))
                    if (folder.paused == syncConditionsMet) {
                        withContext(Dispatchers.IO) {
                            configXml.setFolderPause(folder.id, !syncConditionsMet)
                        }
                        Log.d(
                            TAG,
                            "applyCustomRunConditions: f(" + folder.label + ")=" + (if (syncConditionsMet) ">1" else ">0")
                        )
                        configChanged = true
                    }
                }
            }

            // Check if the devices are available from config.
            val devices = withContext(Dispatchers.IO) {
                configXml.getDevices(false).filterNotNull()
            }
            for (device in devices) {
                // LogV("applyCustomRunConditions: Processing config of device(" + device.getName() + ")");
                val deviceCustomSyncConditionsEnabled = preferences.getBoolean(
                    DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID),
                    false
                )
                if (deviceCustomSyncConditionsEnabled) {
                    val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(
                        Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
                    )
                    LogV("applyCustomRunConditions: d(" + device.name + ")=" + (if (syncConditionsMet) "1" else "0"))
                    if (device.paused == syncConditionsMet) {
                        withContext(Dispatchers.IO) {
                            configXml.setDevicePause(device.deviceID, !syncConditionsMet)
                        }
                        Log.d(
                            TAG,
                            "applyCustomRunConditions: d(" + device.name + ")=" + (if (syncConditionsMet) ">1" else ">0")
                        )
                        configChanged = true
                    }
                }
            }

            if (configChanged) {
                LogV("applyCustomRunConditions: Saving changed config ...")
                withContext(Dispatchers.IO) {
                    configXml.saveChanges()
                }
            } else {
                LogV("applyCustomRunConditions: No action was necessary.")
            }
        }
    }

    /**
     * Prepares to launch the syncthing binary.
     */
    private suspend fun launchStartupTask(srCommand: SyncthingRunnable.Command) {
        synchronized(stateLock) {
            if (currentState != State.DISABLED && currentState != State.INIT) {
                Log.e(
                    TAG,
                    "launchStartupTask: Wrong state $currentState detected. Cancelling."
                )
                return
            }
            onServiceStateChange(State.STARTING)
        }

        config = ConfigXml(this)
        try {
            withContext(Dispatchers.IO) {
                config?.loadConfig()
            }
        } catch (e: OpenConfigException) {
            notificationHandler.showCrashedNotification(
                R.string.config_read_failed,
                "launchStartupTask:OpenConfigException"
            )
            synchronized(stateLock) {
                onServiceStateChange(State.ERROR)
            }
            stopSelf()
            return
        }

        // Check if the SyncthingNative's configured webgui port is allocated by another app or process.
        val webGuiTcpPort = config?.webGuiBindPort
        if (isTcpPortListening(webGuiTcpPort)) {
            // We shouldn't start SyncthingNative as we would wait forever for life signs on the configured port. (ANR)
            Log.e(
                TAG,
                "launchStartupTask: WebUI tcp port $webGuiTcpPort unavailable. Second instance?"
            )
            notificationHandler.showCrashedNotification(
                R.string.webui_tcp_port_unavailable,
                webGuiTcpPort.toString()
            )
            synchronized(stateLock) {
                onServiceStateChange(State.DISABLED)
            }
            return
        }

        if (api == null) {
            config?.webGuiUrl?.let {
                api = RestApi(
                    this, it, config?.apiKey,
                    { onApiAvailable() }, {
                        synchronized(stateLock) {
                            onServiceStateChange(currentState)
                        }
                    })
                Log.i(TAG, "Web GUI will be available at " + config?.webGuiUrl)
            }
        }

        // Check syncthingRunnable lifecycle and create singleton.
        if (syncthingRunnable != null || syncthingRunnableThread != null) {
            Log.e(TAG, "onStartupTaskCompleteListener: Syncthing binary lifecycle violated")
            synchronized(stateLock) {
                onServiceStateChange(State.DISABLED)
            }
            return
        }
        syncthingRunnable = SyncthingRunnable(this, srCommand)

        /**
         * Check if an old syncthing instance is still running.
         * This happens after an in-place app upgrade. If so, end it.
         */
        killProcess(Constants.FILENAME_SYNCTHING_BINARY)

        // Start the syncthing binary in a separate thread.
        val syncthingRunnableThreadExceptionHandler: Thread.UncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { syncthingRunnableThread, ex ->
                Log.e(
                    TAG,
                    "syncthingRunnableThread: Uncaught exception [ExecutableNotFoundException]"
                )
                notificationHandler.showCrashedNotification(
                    R.string.executable_not_found,
                    Constants.FILENAME_SYNCTHING_BINARY
                )
            }
        syncthingRunnableThread = Thread(syncthingRunnable)
        syncthingRunnableThread?.uncaughtExceptionHandler =
            syncthingRunnableThreadExceptionHandler
        syncthingRunnableThread?.priority = Thread.MIN_PRIORITY
        if (syncthingRunnableThread?.state == Thread.State.NEW) {
            syncthingRunnableThread?.start()
        } else {
            Log.e(TAG, "launchStartupTask: Thread already started, skipping start()")
        }

        /**
         * Wait for the web-gui of the native syncthing binary to come online.
         * 
         * In case the binary is to be stopped, also be aware that another thread could request
         * to stop the binary in the time while waiting for the GUI to become active. See the comment
         * for [onDestroy] for details.
         */
        if (pollWebGuiAvailableTask == null) {
            config?.webGuiUrl?.let {
                pollWebGuiAvailableTask = PollWebGuiAvailableTask(
                    this, it, config?.apiKey,
                    parentJob = serviceScope.coroutineContext[Job],
                    listener = { result: String? ->
                        Log.i(TAG, "Web GUI has come online at " + config?.webGuiUrl)
                        api?.readConfigFromRestApi()
                    }
                )
            }
        }

    }

    /**
     * Called when [RestApi.checkReadConfigFromRestApiCompleted] detects
     * the RestApi class has been fully initialized.
     * UI stressing results in mRestApi getting null on simultaneous shutdown, so
     * we check it for safety.
     */
    private fun onApiAvailable() {
        if (api == null) {
            Log.e(TAG, "onApiAvailable: Did we stop the binary during startup? mRestApi == null")
            return
        }
        synchronized(stateLock) {
            if (currentState != State.STARTING) {
                Log.e(
                    TAG,
                    "onApiAvailable: Wrong state $currentState detected. Cancelling callback."
                )
                return
            }
            onServiceStateChange(State.ACTIVE)
        }

        if (eventProcessor == null) {
            eventProcessor = EventProcessor(this@SyncthingService, api)
            eventProcessor?.start()
        }
    }

    override fun onBind(intent: Intent?): SyncthingServiceBinder {
        return binder
    }

    /**
     * Stops the native binary.
     * Shuts down RunConditionMonitor instance.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isServiceRunning = false
        /**
         * Shut down the OnShouldRunChangedListener so we won't get interrupted by run
         * condition events that occur during shutdown.
         */
        runConditionMonitor?.close()
        runConditionMonitor = null
        notificationHandler.setAppShutdownInProgress(true)
        if (!storagePermissionGranted) {
            Log.i(TAG, "Shutting down syncthing binary due to missing storage permission.")
        }
        pollWebGuiAvailableTask?.cancelRequestsAndCallback()
        pollWebGuiAvailableTask = null

        // Capture the API reference before nulling it so the shutdown coroutine
        // doesn't race the field becoming null.
        val apiToShutdown = api
        api = null

        // Serialize the whole teardown under shutdownMutex and run the deferred
        // shutdown on shutdownScope, which is not cancelled by the serviceScope
        // cancellation below.
        shutdownScope.launch {
            shutdownMutex.withLock {
                apiToShutdown?.let {
                    withContext(Dispatchers.IO) {
                        it.shutdown()
                    }
                }

                // Hard stop: if STARTING, kill the native process immediately
                // rather than deferring shutdown onto a scope that is about to
                // be cancelled.
                if (synchronized(stateLock) { currentState } == State.STARTING) {
                    Log.w(TAG, "onDestroy: State is STARTING, killing process immediately")
                    withContext(Dispatchers.IO) {
                        killProcess(Constants.FILENAME_SYNCTHING_BINARY)
                    }
                    synchronized(stateLock) { onServiceStateChange(State.DISABLED) }
                }

                shutdown(State.DISABLED)

                // Only tear down the scope if shutdown completed; a shutdown
                // deferred while STARTING re-schedules onto shutdownScope.
                if (synchronized(stateLock) { currentState } != State.STARTING) {
                    shutdownScope.cancel()
                }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * React to system memory pressure by dropping in-memory caches.
     * All cleared caches are repopulated lazily on the next access.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
            level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            return
        }
        Log.i(TAG, "onTrimMemory: level=$level, clearing caches")
        api?.clearCaches()
        configRouter?.clearCache()
    }

    /**
     * Stop SyncthingNative and all helpers like event processor and api handler.
     * Sets [.mCurrentState] to newState.
     * Performs a synchronous shutdown of the native binary.
     */
    private suspend fun shutdown(newState: State) {
        val stateNow = synchronized(stateLock) { currentState }
        if (stateNow == State.STARTING) {
            Log.w(TAG, "Deferring shutdown until State.STARTING was left")
            deferredShutdownJob?.cancel()
            deferredShutdownJob = shutdownScope.launch {
                var attempts = 0
                while (synchronized(stateLock) { currentState } == State.STARTING) {
                    if (++attempts > SHUTDOWN_MAX_DEFERRED_ATTEMPTS) {
                        Log.w(TAG, "shutdown: Timed out waiting for STARTING to end, proceeding")
                        break
                    }
                    delay(1000L)
                }
                deferredShutdownJob = null
                shutdown(newState)
            }
            return
        }

        synchronized(stateLock) {
            onServiceStateChange(newState)
        }

        pollWebGuiAvailableTask?.let {
            pollWebGuiAvailableTask?.cancelRequestsAndCallback()
            pollWebGuiAvailableTask = null
        }

        eventProcessor?.let {
            eventProcessor?.stop()
            eventProcessor = null
        }

        notificationHandler.cancelRestartNotification()

        if (api != null && syncthingRunnable != null) {
            api?.shutdown()
            api = null
        }

        syncthingRunnable?.let { runnable ->
            // killProcess and the thread join are blocking (join may wait up to the
            // timeout and killProcess polls for process exit), so run them off main.
            withContext(Dispatchers.IO) {
                // Primary: destroy the owned process directly (no shell ps match).
                runnable.destroyProcess()
                // Fallback: shell kill for any orphaned instances we don't own.
                killProcess(Constants.FILENAME_SYNCTHING_BINARY)
                if (syncthingRunnableThread != null) {
                    LogV("Waiting for syncthingRunnableThread to finish after killProcess(Syncthing) ...")
                    try {
                        syncthingRunnableThread?.join(SYNCTHING_THREAD_JOIN_TIMEOUT_MS)
                        if (syncthingRunnableThread?.isAlive == true) {
                            Log.w(
                                TAG,
                                "syncthingRunnableThread did not finish within timeout, continuing shutdown"
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "syncthingRunnableThread InterruptedException")
                    }
                    Log.d(TAG, "Finished syncthingRunnableThread.")
                    syncthingRunnableThread = null
                }
                syncthingRunnable = null
            }
        }

        config = null
    }

    /**
     * Force re-evaluating run conditions immediately e.g. after
     * preferences were modified by [../activities].
     */
    fun evaluateRunConditions() {
        if (runConditionMonitor == null) {
            return
        }
        Log.d(TAG, "Forced re-evaluating run conditions ...")
        runConditionMonitor?.updateShouldRunDecision()
    }

    /**
     * Register a listener for the syncthing API state changing.
     * The listener is called immediately with the current state, and again whenever the state
     * changes. The call is always from the GUI thread.
     * 
     * @see .unregisterOnServiceStateChangeListener
     */
    fun registerOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        /**
         * Initially send the current state to the new subscriber to make sure it doesn't stay
         * in undefined state forever until the state next change occurs.
         */
        listener.onServiceStateChange(synchronized(stateLock) { currentState })
        onServiceStateChangeListeners.add(listener)
    }

    /**
     * Unregisters a previously registered listener.
     * 
     * @see .registerOnServiceStateChangeListener
     */
    fun unregisterOnServiceStateChangeListener(listener: OnServiceStateChangeListener?) {
        onServiceStateChangeListeners.remove(listener)
    }

    /**
     * Called to notify listeners of an API change.
     */
    private fun onServiceStateChange(newState: State) {
        if (newState == currentState) {
            Log.d(TAG, "onServiceStateChange: Called with unchanged state $newState")
            return
        }
        Log.i(TAG, "onServiceStateChange: from $currentState to $newState")
        currentState = newState
        serviceScope.launch {
            notificationHandler.updatePersistentNotification(this@SyncthingService)
            for (listener in onServiceStateChangeListeners) {
                listener.onServiceStateChange(currentState)
            }
        }
    }

    val runDecisionExplanation: String?
        get() {
            return runConditionMonitor?.runDecisionExplanation
                ?: resources.getString(R.string.reason_run_condition_monitor_not_instantiated)
        }

    private val backupZipFile: File
        /**
         * Get backup zip file.
         * Default: /storage/emulated0/backups/syncthing/config.zip
         */
        get() {
            val defaultPath = "backups/syncthing/config.zip"
            var relPathToZip = preferences.getString(
                Constants.PREF_BACKUP_REL_PATH_TO_ZIP,
                defaultPath
            )

            // NOTE: somehow we get empty string from the prefs, which crashes the app, use default when that happens
            // TODO: figure out where the empty string is coming from and fix that
            if (relPathToZip.isNullOrEmpty()) {
                relPathToZip = defaultPath
            }
            return File(Environment.getExternalStorageDirectory(), relPathToZip)
        }

    /**
     * Exports the local config and keys to [Constants.EXPORT_PATH].
     * 
     * 
     * Test with Android Virtual Device using emulator.
     * cls & adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     * 
     */
    suspend fun exportConfig(): Boolean {
        var failSuccess = true
        Log.d(TAG, "exportConfig BEGIN")

        if (synchronized(stateLock) { currentState } != State.DISABLED) {
            // Shutdown synchronously. The shutdown orchestration fields are
            // only safe to mutate on the main thread, so route the state
            // transition there even though we are running on the IO dispatcher.
            withContext(Dispatchers.Main) {
                shutdown(State.DISABLED)
            }
        }

        // All file and ZIP operations run off the main thread.
        withContext(Dispatchers.IO) {
            // Create export dir if non-existant.
            val targetZip = backupZipFile
            targetZip.parentFile?.mkdirs()

            // Export SharedPreferences.
            var sharedPreferencesFile = getSharedPrefsFile(this@SyncthingService)

            if (!sharedPreferencesFile.exists()) {
                sharedPreferencesFile.createNewFile()
            }
            writeSharedPrefsJson(sharedPreferencesFile)

            // Make a list of files to backup.
            val includePaths = listOf<File>(
                getConfigFile(this@SyncthingService),
                getPrivateKeyFile(this@SyncthingService),
                getPublicKeyFile(this@SyncthingService),
                getHttpsCertFile(this@SyncthingService),
                getHttpsKeyFile(this@SyncthingService),
                getSharedPrefsFile(this@SyncthingService),
                getIndexDbFolder(this@SyncthingService)
            )

            // If user set one, apply a password and encrypt the zip file.
            val zipEncryptionPassword: String =
                preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: ""

            // Compress files to zip file.
            try {
                // Delete existing ZIP file to ensure we create a fresh archive instead of appending
                if (targetZip.exists()) {
                    targetZip.delete()
                }

                val parameters = ZipParameters()
                parameters.compressionMethod = CompressionMethod.DEFLATE
                parameters.compressionLevel = CompressionLevel.NORMAL

                val zipFile = if (zipEncryptionPassword.isEmpty()) {
                    parameters.isEncryptFiles = false
                    ZipFile(targetZip)
                } else {
                    parameters.isEncryptFiles = true
                    parameters.encryptionMethod = EncryptionMethod.AES
                    parameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    ZipFile(targetZip, zipEncryptionPassword.toCharArray())
                }

                // Add files.
                for (includePath in includePaths) {
                    if (includePath.exists() == true) {
                        if (includePath.isFile) {
                            zipFile.addFile(includePath, parameters)
                        } else if (includePath.isDirectory) {
                            zipFile.addFolder(includePath, parameters)
                        }
                    }
                }

                if (sharedPreferencesFile.exists() == true) {
                    sharedPreferencesFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "exportConfig: Failed to export config, " + e.message)
                failSuccess = false
            }
        }
        Log.d(TAG, "exportConfig END")

        // Start syncthing after export if run conditions apply.
        if (synchronized(stateLock) { lastDeterminedShouldRun }) {
            serviceScope.launch { launchStartupTask(SyncthingRunnable.Command.main) }
        }
        return failSuccess
    }

    /**
     * Imports config and keys from [Constants.EXPORT_PATH].
     * 
     * 
     * Test with Android Virtual Device using emulator.
     * cls & adb shell su 0 "ls -a -l -R /data/data/${applicationId}/files; echo === SDCARD ===; ls -a -l -R /storage/emulated/0/backups/syncthing"
     * 
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    suspend fun importConfig(): Boolean {
        Log.d(TAG, "importConfig PRECHECK")

        // Check if ZIP exists.
        val zipFile: ZipFile = withContext(Dispatchers.IO) {
            if (!backupZipFile.exists()) {
                Log.e(
                    TAG,
                    "importConfig: ZIP file is missing. Please check if it is present at '" + backupZipFile.absolutePath + "' as specified in the settings screen."
                )
                return@withContext null
            }

            try {
                // If user set one, get password to decrypt the zip file.
                val zipEncryptionPassword: String =
                    preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: ""
                val result: ZipFile = if (zipEncryptionPassword.isEmpty()) {
                    ZipFile(backupZipFile)
                } else {
                    val passwordProtectedZipFile = ZipFile(
                        backupZipFile,
                        zipEncryptionPassword.toCharArray()
                    )
                    if (!passwordProtectedZipFile.isEncrypted()) {
                        Log.e(
                            TAG,
                            "importConfig: ZIP file is not encrypted, but password was specified in settings screen. Try to specify an empty password temporarily."
                        )
                        return@withContext null
                    }
                    passwordProtectedZipFile
                }

                // Check if ZIP archive contains required files.
                val checkFiles = listOf(
                    Constants.CONFIG_FILE,
                    Constants.PRIVATE_KEY_FILE,
                    Constants.PUBLIC_KEY_FILE
                )
                for (checkFile in checkFiles) {
                    if (result.getFileHeader(checkFile) == null) {
                        Log.e(
                            TAG,
                            "importConfig: Required file not found inside zip [$checkFile]"
                        )
                        return@withContext null
                    }
                }

                // Test if supplied encryption password is correct.
                val cacheDir = this@SyncthingService.cacheDir.absolutePath
                result.extractFile(Constants.PUBLIC_KEY_FILE, cacheDir)
                File(cacheDir, Constants.PUBLIC_KEY_FILE).delete()
                result
            } catch (e: ZipException) {
                Log.e(TAG, "importConfig: Failed to open zip, " + e.message)
                null
            }
        } ?: return false

        // Shutdown SyncthingNative.
        var failSuccess = true
        Log.d(TAG, "importConfig BEGIN")
        if (synchronized(stateLock) { currentState } != State.DISABLED) {
            // Shutdown synchronously. The shutdown orchestration fields are
            // only safe to mutate on the main thread, so route the state
            // transition there even though we are running on the IO dispatcher.
            withContext(Dispatchers.Main) {
                shutdown(State.DISABLED)
            }
        }

        // Extract the zip to a staging directory first so a corrupt or
        // incomplete archive cannot destroy the current config/index database.
        val cacheDir = this@SyncthingService.cacheDir
        val stagingDir = File(cacheDir, "import_staging_" + System.currentTimeMillis())
        val extractSucceeded = withContext(Dispatchers.IO) {
            try {
                stagingDir.mkdirs()
                zipFile.extractAll(stagingDir.absolutePath)
                true
            } catch (e: ZipException) {
                Log.e(TAG, "importConfig: Failed to extract zip, " + e.message)
                false
            }
        }
        if (extractSucceeded) {
            var swapSucceeded = true
            withContext(Dispatchers.IO) {
                // Check if necessary files are present after extraction.
                val checkFiles = listOf(
                    Constants.CONFIG_FILE,
                    Constants.PRIVATE_KEY_FILE,
                    Constants.PUBLIC_KEY_FILE,
                    Constants.HTTPS_CERT_FILE,
                    Constants.HTTPS_KEY_FILE,
                    Constants.SHARED_PREFS_FILE
                )
                for (checkFile in checkFiles) {
                    if (!File(stagingDir, checkFile).exists()) {
                        Log.e(
                            TAG,
                            "importConfig: Missing file after extraction [" + checkFile + "]"
                        )
                        swapSucceeded = false
                    }
                }

                // Only replace the live files once validation passed.
                if (swapSucceeded) {
                    // Remove database folder if it exists.
                    val databasePath = getIndexDbFolder(this@SyncthingService)
                    if (databasePath.exists()) {
                        Log.d(TAG, "importConfig: Clearing index database")
                        try {
                            deleteDirectoryRecursively(databasePath)
                        } catch (e: IOException) {
                            Log.e(
                                TAG,
                                "Failed to delete directory '" + databasePath.absolutePath + "'" + e
                            )
                        }
                    }

                    stagingDir.listFiles()?.forEach { staged ->
                        val target = File(filesDir, staged.name)
                        if (target.exists()) {
                            target.delete()
                        }
                        if (!staged.renameTo(target)) {
                            Log.e(
                                TAG,
                                "importConfig: Failed to move staged file [" + staged.name + "]"
                            )
                            swapSucceeded = false
                        }
                    }
                }
            }
            failSuccess = failSuccess && swapSucceeded

            if (swapSucceeded) {
                // Import shared preferences.
                val sharedPreferencesFile = getSharedPrefsFile(this@SyncthingService)
                if (sharedPreferencesFile.exists()) {
                    Log.d(TAG, "importConfig: Importing shared preferences")
                    failSuccess = failSuccess && importConfigSharedPrefs(sharedPreferencesFile)
                    sharedPreferencesFile.delete()
                }
            }
        } else {
            failSuccess = false
        }
        deleteDirectoryRecursively(stagingDir)

        try {
            cleanupImportedFolderDatabases()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e)
        }

        // Start syncthing after import if run conditions apply.
        if (synchronized(stateLock) { lastDeterminedShouldRun }) {
            serviceScope.launch { launchStartupTask(SyncthingRunnable.Command.main) }
        }
        return failSuccess
    }

    /**
     * Replaces the Web GUI HTTPS certificate and key with the supplied PEM bytes, then restarts
     * Syncthing so the new files take effect. If the restart fails to bring the Web GUI back online,
     * the previous certificate and key are restored automatically.
     * 
     * 
     * The bytes are expected to already be validated (see [com.micnubinub.syncthing.util.CertificateValidator]).
     * The whole start/stop lifecycle is marshalled onto the main thread because the binary
     * orchestration fields are only safe to touch there.
     */
    fun replaceHttpsCertificate(
        certPem: ByteArray?, keyPem: ByteArray?,
        listener: OnHttpsCertReplaceResultListener
    ) {
        serviceScope.launch {
            doReplaceHttpsCertificate(
                certPem, keyPem,
                listener
            )
        }
    }

    /**
     * Deletes the user-supplied HTTPS certificate/key so Syncthing regenerates a fresh self-signed
     * certificate at the next start, then restarts (with rollback on failure).
     */
    fun resetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        serviceScope.launch { doResetHttpsCertificate(listener) }
    }

    private suspend fun doReplaceHttpsCertificate(
        certPem: ByteArray?, keyPem: ByteArray?,
        listener: OnHttpsCertReplaceResultListener
    ) {
        var attempts = 0
        while (synchronized(stateLock) { currentState } == State.STARTING) {
            if (++attempts > SHUTDOWN_MAX_DEFERRED_ATTEMPTS) {
                Log.w(TAG, "doReplaceHttpsCertificate: Timed out waiting for STARTING to end")
                break
            }
            delay(1000L)
        }

        val certFile = getHttpsCertFile(this)
        val keyFile = getHttpsKeyFile(this)

        // Stop the binary so it releases the cert/key before we overwrite them.
        if (synchronized(stateLock) { currentState } != State.DISABLED) {
            shutdown(State.DISABLED)
        }

        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)

        try {
            writeBytesAtomic(certFile, certPem)
            writeBytesAtomic(keyFile, keyPem)
            restrictToOwner(keyFile)
        } catch (e: IOException) {
            Log.e(TAG, "doReplaceHttpsCertificate: Failed to write new cert/key", e)
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
            if (lastDeterminedShouldRun) {
                launchStartupTask(SyncthingRunnable.Command.main)
            }
            listener.onResult(HttpsCertReplaceResult.FAILED, e.message)
            return
        }

        applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, listener)
    }

    private suspend fun doResetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        var attempts = 0
        while (synchronized(stateLock) { currentState } == State.STARTING) {
            if (++attempts > SHUTDOWN_MAX_DEFERRED_ATTEMPTS) {
                Log.w(TAG, "doResetHttpsCertificate: Timed out waiting for STARTING to end")
                break
            }
            delay(1000L)
        }

        val certFile = getHttpsCertFile(this)
        val keyFile = getHttpsKeyFile(this)

        if (synchronized(stateLock) { currentState } != State.DISABLED) {
            shutdown(State.DISABLED)
        }

        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)
        // Removing the files makes syncthing generate a fresh self-signed certificate at startup.
        deleteQuietly(certFile)
        deleteQuietly(keyFile)

        applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, listener)
    }

    private fun applyCertChangeWithVerify(
        certFile: File, keyFile: File,
        certBak: File?, keyBak: File?,
        listener: OnHttpsCertReplaceResultListener
    ) {
        // The cert on disk changed: drop the cached HTTP client so it is rebuilt
        // against the new certificate before any further API request.
        SyncthingHttpClients.invalidate()
        if (lastDeterminedShouldRun) {
            verifyRestartAndRollback(certFile, keyFile, certBak, keyBak, listener)
        } else {
            // Not currently meant to run; the new files will take effect on next start.
            deleteQuietly(certBak)
            deleteQuietly(keyBak)
            listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null)
        }
    }

    /**
     * Restarts the binary and watches the service state: success on reaching ACTIVE, failure on
     * ERROR / an abnormal STARTINGDISABLED transition (crashed binary) / a watchdog timeout.
     * On failure the backed-up cert/key are restored and a known-good instance is brought back up.
     */
    private fun verifyRestartAndRollback(
        certFile: File, keyFile: File,
        certBak: File?, keyBak: File?,
        listener: OnHttpsCertReplaceResultListener
    ) {
        val resolved = booleanArrayOf(false)
        val sawStarting = booleanArrayOf(false)
        val verifyListener = arrayOfNulls<OnServiceStateChangeListener>(1)
        var watchdogJob: Job? = null

        val finishSuccess: () -> Unit = {
            deleteQuietly(certBak)
            deleteQuietly(keyBak)
            listener.onResult(HttpsCertReplaceResult.SUCCESS, null)
        }
        val finishFailure: suspend () -> Unit = {
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
            // Rolled back to the previous cert: rebuild the HTTP client against it.
            SyncthingHttpClients.invalidate()
            val stateForRollback = synchronized(stateLock) { currentState }
            if (stateForRollback != State.DISABLED && stateForRollback != State.INIT) {
                shutdown(State.INIT)
            }
            serviceScope.launch { launchStartupTask(SyncthingRunnable.Command.main) }
            listener.onResult(
                HttpsCertReplaceResult.FAILED,
                "Syncthing did not come online with the new certificate."
            )
        }

        verifyListener[0] = OnServiceStateChangeListener { state: State ->
            if (resolved[0]) {
                return@OnServiceStateChangeListener
            }
            if (state == State.STARTING) {
                sawStarting[0] = true
                return@OnServiceStateChangeListener
            }
            val success = (state == State.ACTIVE)
            val failure = (state == State.ERROR) || (sawStarting[0] && state == State.DISABLED)
            if (!success && !failure) {
                return@OnServiceStateChangeListener
            }
            resolved[0] = true
            watchdogJob?.cancel()
            serviceScope.launch {
                unregisterOnServiceStateChangeListener(verifyListener[0])
                if (success) {
                    finishSuccess()
                } else {
                    finishFailure()
                }
            }
        }

        verifyListener[0]?.let { registerOnServiceStateChangeListener(it) }
        watchdogJob = serviceScope.launch {
            delay(HTTPS_CERT_VERIFY_TIMEOUT_MS)
            if (resolved[0]) {
                return@launch
            }
            resolved[0] = true
            unregisterOnServiceStateChangeListener(verifyListener[0])
            if (synchronized(stateLock) { currentState } == State.ACTIVE) {
                finishSuccess()
            } else {
                finishFailure()
            }
        }
        serviceScope.launch { launchStartupTask(SyncthingRunnable.Command.main) }
    }

    private fun backupFile(file: File): File? {
        if (!file.exists()) {
            return null
        }
        val bak = File(file.parentFile, file.name + ".bak")
        deleteQuietly(bak)
        if (file.renameTo(bak)) {
            return bak
        }
        Log.w(TAG, "backupFile: Failed to back up " + file.name)
        return null
    }

    private fun restoreFile(bak: File?, target: File) {
        if (bak?.exists() != true) {
            return
        }
        deleteQuietly(target)
        if (!bak.renameTo(target)) {
            Log.w(TAG, "restoreFile: Failed to restore " + target.name)
        }
    }

    private fun deleteQuietly(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "deleteQuietly: Failed to delete " + file.name)
        }
    }

    @Throws(IOException::class)
    private fun writeBytesAtomic(target: File, data: ByteArray?) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { output ->
            output.write(data)
            output.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            deleteQuietly(tmp)
            throw IOException("Failed to rename " + tmp.name + " to " + target.name)
        }
    }

    private fun restrictToOwner(file: File) {
        // Mirror syncthing core, which writes the HTTPS key with 0600 permissions.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    private suspend fun cleanupImportedFolderDatabases() {
        withContext(Dispatchers.IO) {
            val configXml = ConfigXml(this@SyncthingService)
            try {
                configXml.loadConfig()
            } catch (e: OpenConfigException) {
                Log.w(TAG, "importConfig: Unable to parse imported config for DB cleanup")
                return@withContext
            }

            val folders = configXml.folders
            if (folders.isEmpty()) {
                return@withContext
            }

            for (folder in folders) {
                if (folder.id == null || folder.id?.isEmpty() == true) {
                    continue
                }

                val folderPath = if (folder.path.isNullOrEmpty())
                    null
                else
                    File(folder.path as String)

                val folderPathMissing = folderPath == null || !folderPath.isDirectory

                val markerName = if (folder.markerName.isEmpty()) {
                    Constants.FILENAME_STFOLDER
                } else {
                    folder.markerName
                }
                val markerMissing = folderPathMissing || !File(folderPath, markerName).exists()

                if (folderPathMissing || markerMissing) {
                    Log.i(
                        TAG,
                        "importConfig: Folder path or marker missing for folder id \"" + folder.id + "\". Resetting Syncthing database."
                    )
                    SyncthingRunnable(
                        this@SyncthingService,
                        SyncthingRunnable.Command.resetdatabase
                    ).run()
                    break
                }
            }
        }
    }

    private fun importConfigSharedPrefs(file: File?): Boolean {
        if (file != null && file.exists()) {
            var success = true
            try {
                val parsedPrefs = readSharedPrefs(file)
                if (parsedPrefs == null || parsedPrefs.isEmpty) {
                    Log.e(TAG, "importConfig: Invalid object stream")
                    success = false
                } else {
                    // Store backup folder to restore it back later in the process.
                    val relPathToZip: String = preferences.getString(
                        Constants.PREF_BACKUP_REL_PATH_TO_ZIP,
                        ""
                    ) ?: ""
                    val backupPassword: String =
                        preferences.getString(Constants.PREF_BACKUP_PASSWORD, "").orEmpty()

                    // Prepare a SharedPreferences commit.
                    preferences.edit {
                        clear()
                        parsedPrefs.booleans?.forEach { (prefKey, value) ->
                            if (!importConfigSkipPref(prefKey)) {
                                putBoolean(prefKey, value)
                            }
                        }
                        parsedPrefs.strings?.forEach { (prefKey, value) ->
                            if (!importConfigSkipPref(prefKey)) {
                                putString(prefKey, value)
                            }
                        }
                        parsedPrefs.ints?.forEach { (prefKey, value) ->
                            if (!importConfigSkipPref(prefKey)) {
                                putInt(prefKey, value)
                            }
                        }
                        parsedPrefs.longs?.forEach { (prefKey, value) ->
                            if (!importConfigSkipPref(prefKey)) {
                                putLong(prefKey, value)
                            }
                        }
                        parsedPrefs.floats?.forEach { (prefKey, value) ->
                            if (!importConfigSkipPref(prefKey)) {
                                putFloat(prefKey, value)
                            }
                        }
                        parsedPrefs.stringSets?.forEach { (prefKey, value) ->
                            if (!importConfigSkipPref(prefKey)) {
                                Log.i(TAG, "importConfig: Adding pref \"$prefKey\" to commit ...")
                                value.let { putStringSet(prefKey, it) }
                            }
                        }
                        putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, relPathToZip)
                        putString(Constants.PREF_BACKUP_PASSWORD, backupPassword)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "importConfig: Invalid object stream", e)
                success = false
            }
            return success
        }
        return false
    }

    /**
     * Returns true when the importing of a preference key should be skipped,
     * e.g. for deprecated or cache keys.
     */
    private fun importConfigSkipPref(prefKey: String): Boolean {
        return when (prefKey) {
            "first_start", "advanced_folder_picker", "backup_folder_name", "bind_network", "log_to_file", "notification_type", "notify_crashes", "suggest_new_folder_root", "use_legacy_hashing", "pref_current_language", "restartOnWakeup", "wakelock_while_binary_running", "use_root", "important_news_shown_version" -> {
                LogV("importConfig: Ignoring deprecated pref \"$prefKey\".")
                true
            }

            Constants.PREF_APP_START_COUNTER, Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.PREF_DEBUG_FACILITIES_AVAILABLE, Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, Constants.PREF_LAST_BINARY_VERSION, Constants.PREF_LOCAL_DEVICE_ID, Constants.PREF_LAST_RUN_TIME -> {
                LogV("importConfig: Ignoring cache pref \"$prefKey\".")
                true
            }

            else -> {
                Log.i(TAG, "importConfig: Adding pref \"$prefKey\" to commit ...")
                false
            }
        }
    }

    /**
     * Writes the current [preferences] as a typed JSON backup, avoiding the
     * native deserialization gadgets of the old [ObjectOutputStream] format.
     */
    private fun writeSharedPrefsJson(file: File) {
        val all = preferences.all
        val backup = SharedPrefsBackup(
            booleans = all.filterValues { it is Boolean }.mapValues { it.value as Boolean },
            strings = all.filterValues { it is String }.mapValues { it.value as String },
            ints = all.filterValues { it is Int }.mapValues { it.value as Int },
            longs = all.filterValues { it is Long }.mapValues { it.value as Long },
            floats = all.filterValues { it is Float }.mapValues { it.value as Float },
            stringSets = all.filterValues { it is Set<*> }
                .mapValues { (it.value as Set<*>).filterIsInstance<String>().toSortedSet() }
        )
        FileOutputStream(file).use { fileOutputStream ->
            OutputStreamWriter(fileOutputStream, Charsets.UTF_8).use { writer ->
                gson.toJson(backup, writer)
            }
        }
    }

    /**
     * Reads a typed shared preferences backup, or null if the file is not a
     * valid backup. Backups written by older app versions with Java
     * serialization are recognized by their stream magic and deserialized
     * with an allowlisted class resolver, so no arbitrary class can be
     * instantiated.
     */
    private fun readSharedPrefs(file: File): SharedPrefsBackup? {
        return if (isLegacySerializedPrefsFile(file)) {
            readSharedPrefsLegacy(file)
        } else {
            readSharedPrefsJson(file)
        }
    }

    /**
     * Returns true when [file] starts with the Java serialization stream
     * magic (0xACED) followed by stream version 5.
     */
    private fun isLegacySerializedPrefsFile(file: File): Boolean {
        FileInputStream(file).use { input ->
            val header = ByteArray(4)
            return input.read(header) == header.size && header.contentEquals(
                byteArrayOf(0xAC.toByte(), 0xED.toByte(), 0x00, 0x05)
            )
        }
    }

    /**
     * Reads a typed JSON shared preferences backup.
     */
    private fun readSharedPrefsJson(file: File): SharedPrefsBackup? {
        FileInputStream(file).use { fileInputStream ->
            InputStreamReader(fileInputStream, Charsets.UTF_8).use { reader ->
                return gson.fromJson(reader, SharedPrefsBackup::class.java)
            }
        }
    }

    /**
     * Reads a legacy Java-serialized shared preferences backup written by an
     * older app version. Deserialization is restricted to
     * [legacySerializedPrefsClassAllowlist] by overriding resolveClass() to
     * prevent deserialization gadgets from executing.
     */
    private fun readSharedPrefsLegacy(file: File): SharedPrefsBackup? {
        FileInputStream(file).use { fileInputStream ->
            val objectInputStream = object : ObjectInputStream(fileInputStream) {
                override fun resolveClass(desc: ObjectStreamClass): Class<*> {
                    if (!isLegacySerializedPrefsClassAllowed(desc.name)) {
                        throw InvalidClassException("Rejected legacy prefs class: ${desc.name}")
                    }
                    return super.resolveClass(desc)
                }
            }
            objectInputStream.use {
                val prefsMap = objectInputStream.readObject() as? Map<*, *> ?: return null
                val booleans = mutableMapOf<String, Boolean>()
                val strings = mutableMapOf<String, String>()
                val ints = mutableMapOf<String, Int>()
                val longs = mutableMapOf<String, Long>()
                val floats = mutableMapOf<String, Float>()
                val stringSets = mutableMapOf<String, Set<String>>()
                for ((key, value) in prefsMap) {
                    val prefKey = key as? String ?: continue
                    when (value) {
                        is Boolean -> booleans[prefKey] = value
                        is String -> strings[prefKey] = value
                        is Int -> ints[prefKey] = value
                        is Long -> longs[prefKey] = value
                        is Float -> floats[prefKey] = value
                        is Set<*> -> stringSets[prefKey] =
                            value.filterIsInstance<String>().toSortedSet()

                        else -> Log.w(
                            TAG,
                            "importConfig: Ignoring unsupported legacy pref type for \"$prefKey\"."
                        )
                    }
                }
                return SharedPrefsBackup(
                    booleans = booleans,
                    strings = strings,
                    ints = ints,
                    longs = longs,
                    floats = floats,
                    stringSets = stringSets
                )
            }
        }
    }

    /**
     * Returns true when the given serialized class name may be deserialized
     * while importing a legacy shared preferences backup, i.e. when it is in
     * [legacySerializedPrefsClassAllowlist] (or an array of such classes).
     */
    private fun isLegacySerializedPrefsClassAllowed(className: String): Boolean {
        var componentType = className
        while (componentType.startsWith("[")) {
            componentType = componentType.substring(1)
        }
        if (componentType.startsWith("L") && componentType.endsWith(";")) {
            componentType = componentType.substring(1, componentType.length - 1)
        }
        return componentType in legacySerializedPrefsClassAllowlist
    }

    /**
     * Typed snapshot of the app's SharedPreferences used for config backup/restore.
     * Values are grouped by type so (de)serialization never relies on risky
     * runtime type dispatch.
     */
    class SharedPrefsBackup(
        val booleans: Map<String, Boolean>? = null,
        val strings: Map<String, String>? = null,
        val ints: Map<String, Int>? = null,
        val longs: Map<String, Long>? = null,
        val floats: Map<String, Float>? = null,
        val stringSets: Map<String, Set<String>>? = null
    ) {
        val isEmpty: Boolean
            get() = booleans.isNullOrEmpty() && strings.isNullOrEmpty() &&
                    ints.isNullOrEmpty() && longs.isNullOrEmpty() &&
                    floats.isNullOrEmpty() && stringSets.isNullOrEmpty()
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    /**
     * Outcome of [.replaceHttpsCertificate] / [.resetHttpsCertificate].
     */
    enum class HttpsCertReplaceResult {
        /**
         * The new certificate was applied and Syncthing came back online with it.
         */
        SUCCESS,

        /**
         * The files were written but Syncthing is not currently meant to run; applies on next start.
         */
        SUCCESS_PENDING_START,

        /**
         * The change failed and the previous certificate was restored.
         */
        FAILED
    }

    /**
     * Indicates the current state of SyncthingService and of Syncthing itself.
     */
    enum class State {
        /**
         * Service is initializing, Syncthing was not started yet.
         */
        INIT,

        /**
         * Syncthing binary is starting.
         */
        STARTING,

        /**
         * Syncthing binary is running,
         * Rest API is available,
         * RestApi class read the config and is fully initialized.
         */
        ACTIVE,

        /**
         * Syncthing binary is shutting down.
         */
        DISABLED,

        /**
         * There is some problem that prevents Syncthing from running.
         */
        ERROR,
    }

    fun interface OnServiceStateChangeListener {
        fun onServiceStateChange(currentState: State)
    }

    interface OnHttpsCertReplaceResultListener {
        fun onResult(result: HttpsCertReplaceResult?, errorDetail: String?)
    }

    companion object {
        /**
         * Intent action to perform a Syncthing restart.
         */
        const val ACTION_RESTART: String = ".SyncthingService.RESTART"

        /**
         * Intent action to perform a Syncthing stop.
         */
        const val ACTION_STOP: String = ".SyncthingService.STOP"

        /**
         * Intent action to reset Syncthing's database.
         */
        const val ACTION_RESET_DATABASE: String = ".SyncthingService.RESET_DATABASE"

        /**
         * Intent action to reset Syncthing's delta indexes.
         */
        const val ACTION_RESET_DELTAS: String = ".SyncthingService.RESET_DELTAS"
        const val ACTION_REFRESH_NETWORK_INFO: String = ".SyncthingService.REFRESH_NETWORK_INFO"

        /**
         * Intent action to permanently ignore a device connection request.
         */
        const val ACTION_IGNORE_DEVICE: String = ".SyncthingService.IGNORE_DEVICE"

        /**
         * Intent action to permanently ignore a folder share request.
         */
        const val ACTION_IGNORE_FOLDER: String = ".SyncthingService.IGNORE_FOLDER"

        /**
         * Intent action to override folder changes.
         */
        const val ACTION_OVERRIDE_CHANGES: String = ".SyncthingService.OVERRIDE_CHANGES"

        /**
         * Intent action to revert local folder changes.
         */
        const val ACTION_REVERT_LOCAL_CHANGES: String = ".SyncthingService.REVERT_LOCAL_CHANGES"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE, ACTION_IGNORE_FOLDER.
         */
        const val EXTRA_NOTIFICATION_ID: String = ".SyncthingService.EXTRA_NOTIFICATION_ID"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_ID: String = ".SyncthingService.EXTRA_DEVICE_ID"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_ADDRESS: String = ".SyncthingService.EXTRA_DEVICE_ADDRESS"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_NAME: String = ".SyncthingService.EXTRA_DEVICE_NAME"

        /**
         * Extra used together with ACTION_IGNORE_FOLDER
         */
        const val EXTRA_FOLDER_ID: String = ".SyncthingService.EXTRA_FOLDER_ID"

        /**
         * Extra used together with ACTION_IGNORE_FOLDER
         */
        const val EXTRA_FOLDER_LABEL: String = ".SyncthingService.EXTRA_FOLDER_LABEL"

        /**
         * Extra used together with ACTION_STOP.
         */
        const val EXTRA_STOP_AFTER_CRASHED_NATIVE: String =
            ".SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE"
        private const val TAG = "SyncthingService"

        @Volatile
        @JvmStatic
        var isServiceRunning = false
            private set

        /**
         * Backstop timeout for [.verifyRestartAndRollback] in case the state machine never reaches
         * a terminal state (e.g. the binary crashed via a path that doesn't transition to ERROR).
         */
        private const val HTTPS_CERT_VERIFY_TIMEOUT_MS: Long = 30000
        private const val SHUTDOWN_MAX_DEFERRED_ATTEMPTS = 30
        private const val SYNCTHING_THREAD_JOIN_TIMEOUT_MS: Long = 10000
    }
}
