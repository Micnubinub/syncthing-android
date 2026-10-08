package com.micnubinub.syncthing.service

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.http.PollWebGuiAvailableTask
import com.micnubinub.syncthing.http.SyncthingHttpClients
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.service.AppPrefs.getPrefVerboseLog
import com.micnubinub.syncthing.service.Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS
import com.micnubinub.syncthing.service.Constants.getConfigFile
import com.micnubinub.syncthing.service.Constants.getHttpsCertFile
import com.micnubinub.syncthing.service.Constants.getHttpsKeyFile
import com.micnubinub.syncthing.service.Constants.getIndexDbFolder
import com.micnubinub.syncthing.service.Constants.getPrivateKeyFile
import com.micnubinub.syncthing.service.Constants.getPublicKeyFile
import com.micnubinub.syncthing.service.Constants.getSharedPrefsFile
import com.micnubinub.syncthing.util.CertificateValidator
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.ConfigXml
import com.micnubinub.syncthing.util.ConfigXml.OpenConfigException
import com.micnubinub.syncthing.util.FileUtils.deleteDirectoryRecursively
import com.micnubinub.syncthing.util.PermissionUtil.haveStoragePermission
import com.micnubinub.syncthing.util.Util.isTcpPortListening
import com.micnubinub.syncthing.util.Util.killProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.io.OutputStreamWriter
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

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
    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private val shutdownScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val shutdownMutex: Mutex get() = coreLifecycleMutex

    /**
     * Hands out the generation numbers of [currentCoreGeneration]; only ever incremented.
     */
    private val coreGenerationCounter = AtomicInteger(0)

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
     * Number of the core generation this service started last. A crash report carries the
     * number of the generation that produced it, so a report from a core that has already
     * been replaced is recognized instead of stopping the current one.
     */
    @Volatile
    private var currentCoreGeneration: Int = 0

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

        /**
         * Must run before anything that can asynchronously fail (e.g. the run
         * condition monitor reading the config below) and before any early return.
         * When this service was started via startForegroundService(), returning
         * without startForeground() makes the system kill the app with
         * ForegroundServiceDidNotStartInTimeException.
         */
        notificationHandler.updatePersistentNotification(this)

        if (!storagePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.")
            notificationHandler.showStoragePermissionRevokedNotification()
            stopSelf()
            return START_NOT_STICKY
        }

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
                val reportedGeneration =
                    intent.getIntExtra(EXTRA_CORE_GENERATION, SyncthingRunnable.NO_GENERATION)
                if (reportedGeneration != currentCoreGeneration) {
                    /**
                     * A crash report from a generation that is not the current one. Acting on
                     * it would mark a healthy core - the one that replaced the reported
                     * generation - as ERROR and stop it.
                     */
                    Log.w(
                        TAG,
                        "ACTION_STOP: Ignoring crash report of core generation " +
                                "$reportedGeneration, current generation is $currentCoreGeneration"
                    )
                    return START_STICKY
                }
                /**
                 * We were requested to stop the service because the syncthing native binary crashed.
                 * Changing mCurrentState prevents the "defer until syncthing is started" routine we normally
                 * use for clean shutdown to take place. Instead, we will immediately shutdown the crashed
                 * instance forcefully.
                 */
                synchronized(stateLock) { onServiceStateChange(State.ERROR) }
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
                val shouldRestart = shutdownMutex.withLock {
                    if (synchronized(stateLock) { currentState } != State.DISABLED) {
                        shutdownLocked(State.DISABLED)
                    }
                    // The reset rewrites the index database, so it has to own
                    // shutdownMutex for its whole run: a run condition flipping back to
                    // "should run" in the gap would otherwise launch the core onto a
                    // half-written database.
                    SyncthingRunnable(
                        this@SyncthingService,
                        SyncthingRunnable.Command.resetdatabase
                    ).run()
                    synchronized(stateLock) { lastDeterminedShouldRun }
                }
                if (shouldRestart) {
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
                    // launchStartupTask returns while the core is still STARTING, and a
                    // shutdown at that point SIGKILLs it, which aborts the reset; wait for
                    // the state that means the work is done.
                    if (!awaitCoreSettled()) {
                        Log.w(
                            TAG,
                            "ACTION_RESET_DELTAS: Core did not reach ACTIVE or ERROR in time, " +
                                    "stopping it anyway"
                        )
                    }
                    shutdown(State.DISABLED)
                }
            }
        } else if (ACTION_REFRESH_NETWORK_INFO == intent.action) {
            runConditionMonitor?.updateShouldRunDecision()
        } else if (ACTION_IGNORE_DEVICE == intent.action) {
            val router = configRouter
            val ignoredDevice = serviceScope.launch {
                router?.ignoreDevice(
                    api,
                    intent.getStringExtra(EXTRA_DEVICE_ID) ?: "",
                    intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "",
                    intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: ""
                )
            }
            ignoredDevice.invokeOnCompletion {
                notificationHandler.cancelConsentNotification(
                    intent.getIntExtra(
                        EXTRA_NOTIFICATION_ID,
                        0
                    )
                )
            }
        } else if (ACTION_IGNORE_FOLDER == intent.action) {
            val router = configRouter
            val ignoredFolder = serviceScope.launch {
                router?.ignoreFolder(
                    api,
                    intent.getStringExtra(EXTRA_DEVICE_ID) ?: "",
                    intent.getStringExtra(EXTRA_FOLDER_ID) ?: "",
                    intent.getStringExtra(EXTRA_FOLDER_LABEL) ?: ""
                )
            }
            ignoredFolder.invokeOnCompletion {
                notificationHandler.cancelConsentNotification(
                    intent.getIntExtra(
                        EXTRA_NOTIFICATION_ID,
                        0
                    )
                )
            }
        } else if (ACTION_OVERRIDE_CHANGES == intent.action && synchronized(stateLock) { currentState == State.ACTIVE }) {
            val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
            serviceScope.launch { api?.overrideChanges(folderId) }
        } else if (ACTION_REVERT_LOCAL_CHANGES == intent.action && synchronized(stateLock) { currentState == State.ACTIVE }) {
            val folderId = intent.getStringExtra(EXTRA_FOLDER_ID)
            serviceScope.launch { api?.revertLocalChanges(folderId) }
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
                // A previous import may have been cut short by process death; put the
                // original config back before anything reads it.
                recoverInterruptedImport()

                // Read and parse the config from disk. Held under the core lifecycle lock
                // because the migration pass may write the file, and a core start must
                // not interleave with that.
                shutdownMutex.withLock {
                    val configXml = ConfigXml(this@SyncthingService)
                    try {
                        withContext(Dispatchers.IO) {
                            configXml.loadConfigAndUpdate()
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

        if (isCoreRunning) {
            // The core owns config.xml already (it is starting up), so this is applied
            // through the REST API instead - see [onApiAvailable], which re-runs this
            // once the model is loaded.
            Log.i(TAG, "applyCustomRunConditions: Core is up, deferring to the REST API")
            return
        }

        // Owning shutdownMutex keeps a core start from interleaving with the pause
        // writes below; the re-check catches a start that was queued ahead of us.
        serviceScope.launch {
            shutdownMutex.withLock {
                if (isCoreRunning) {
                    Log.i(TAG, "applyCustomRunConditions: Core came up, deferring to the REST API")
                    return@withLock
                }
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
                    return@withLock
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
    }

    /**
     * Prepares to launch the syncthing binary.
     *
     * Shares [shutdownMutex] with [shutdown] so a start and a stop can never build up
     * two native processes at once.
     */
    private suspend fun launchStartupTask(srCommand: SyncthingRunnable.Command) {
        shutdownMutex.withLock {
            synchronized(stateLock) {
                if (currentState != State.DISABLED && currentState != State.INIT) {
                    Log.e(
                        TAG,
                        "launchStartupTask: Wrong state $currentState detected. Cancelling."
                    )
                    return@withLock
                }
                onServiceStateChange(State.STARTING)
            }

            config = ConfigXml(this)
            try {
                withContext(Dispatchers.IO) {
                    // Migrations and repairs belong here, before the core reads the file.
                    config?.loadConfigAndUpdate()
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
                return@withLock
            }

            /**
             * Reclaim an orphan of this app's own UID before anything else. An old core
             * left behind by an app-process death (LMK, in-place upgrade) still holds the
             * Web GUI port, so the port check below would report "second instance?" and
             * strand the service in DISABLED while that orphan syncs unmanaged, with no
             * retry. [Util.killProcess] only signals this UID and the exact binary name.
             */
            killProcess(Constants.FILENAME_SYNCTHING_BINARY)

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
                return@withLock
            }

            if (api == null) {
                config?.webGuiUrl?.let {
                    api = RestApi(
                        this, it, config?.apiKey,
                        {
                            onApiAvailable()
                        }, {
                            synchronized(stateLock) {
                                onServiceStateChange(currentState)
                            }
                        }, { reason -> onApiUnavailable(reason) })
                    Log.i(TAG, "Web GUI will be available at " + config?.webGuiUrl)
                }
            }

            // Check syncthingRunnable lifecycle and create singleton.
            if (syncthingRunnable != null || syncthingRunnableThread != null) {
                Log.e(TAG, "onStartupTaskCompleteListener: Syncthing binary lifecycle violated")
                synchronized(stateLock) {
                    onServiceStateChange(State.DISABLED)
                }
                return@withLock
            }
            // Every start gets its own generation number, so a report that arrives late -
            // after this core was replaced - can be recognized and ignored.
            currentCoreGeneration = coreGenerationCounter.incrementAndGet()
            syncthingRunnable = SyncthingRunnable(this, srCommand, currentCoreGeneration)

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
                // From here the core owns config.xml, so no direct write may be started
                // until [shutdownLocked] has confirmed the process is gone.
                isCoreRunning = true
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
                        },
                        onTimeout = { reason -> onApiUnavailable(reason) }
                    )
                }
            }
        }
    }

    /**
     * Called when [RestApi.readConfigFromRestApi] completed every startup snapshot of the
     * current generation successfully and the RestApi class is fully initialized.
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

        // The core loaded the imported config and keys and came up, so a committed
        // import has now proven itself and its retained rollback copies are garbage.
        discardRetainedImportRollback()

        if (eventProcessor == null) {
            eventProcessor = EventProcessor(this@SyncthingService, api)
            eventProcessor?.start()
        }

        // A sync-precondition change that arrived while the core was still starting could
        // not be written to config.xml, so apply it now that the REST model is loaded.
        runConditionMonitor?.let { applyCustomRunConditions(it) }
    }

    /**
     * A startup snapshot of the running generation could not be read, or the Web GUI never
     * became available. Activating on partial data would hand the UI a config that was
     * never loaded, and waiting would strand the service in STARTING, so terminate what we
     * own and report the failure.
     */
    private fun onApiUnavailable(reason: String) {
        Log.e(TAG, "onApiUnavailable: $reason")
        synchronized(stateLock) {
            if (currentState != State.STARTING) {
                Log.e(
                    TAG,
                    "onApiUnavailable: Wrong state $currentState detected. Cancelling callback."
                )
                return
            }
            onServiceStateChange(State.ERROR)
        }
        notificationHandler.showCrashedNotification(R.string.notification_crash_title, reason)
        shutdownScope.launch { shutdown(State.DISABLED) }
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

        // shutdown() owns the whole teardown under shutdownMutex, so this path cannot
        // interleave with a concurrent start or stop request. It runs on shutdownScope,
        // which is not cancelled by the serviceScope cancellation below.
        shutdownScope.launch {
            shutdown(State.DISABLED)
            // Only tear down the scope once the teardown completed.
            shutdownScope.cancel()
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
     *
     * Returns only once the teardown has completed, so callers may replace the data
     * directory right after. [shutdownMutex] is the single owner of the native
     * lifecycle: start and stop requests are serialized instead of interleaving, and a
     * stop arriving while the core is still [State.STARTING] force-kills the generation
     * this service already owns instead of deferring behind a startup that may never
     * complete.
     */
    private suspend fun shutdown(newState: State) = shutdownMutex.withLock {
        shutdownLocked(newState)
    }

    /**
     * Body of [shutdown], for callers that already hold [shutdownMutex] because they have
     * to keep the core down for the whole of a longer operation (import, export, database
     * reset, HTTPS certificate replacement). Releasing the mutex between the teardown and
     * the file work would let a queued [launchStartupTask] bring the core up on a
     * half-swapped data directory.
     */
    private suspend fun shutdownLocked(newState: State) {
        // The Web UI does not serve while STARTING, so there is nothing to ask for a
        // clean stop and the owned process has to be killed.
        val wasStarting = synchronized(stateLock) { currentState } == State.STARTING

        synchronized(stateLock) {
            onServiceStateChange(newState)
        }

        pollWebGuiAvailableTask?.let {
            it.cancelRequestsAndCallback()
            pollWebGuiAvailableTask = null
        }

        eventProcessor?.let {
            it.stop()
            eventProcessor = null
        }

        notificationHandler.cancelRestartNotification()

        // Tag the generation we own as intentionally stopping before anything can make
        // it exit, so a clean exit code is not later mistaken for an unexpected death.
        syncthingRunnable?.markShutdownRequested()

        // Stop and clear the API only when this service created one, so a leftover
        // reference can never outlive the core it belongs to. The local work (scopes,
        // event stream, queued saves) always stops; only the shutdown POST is skipped
        // while STARTING because the Web GUI does not serve yet and the owned process
        // is force-killed below instead.
        api?.stopLocalWork()
        if (!wasStarting) {
            api?.postShutdownRequest()
        }
        api = null

        syncthingRunnable?.let { runnable ->
            // killProcess and the thread join are blocking (join may wait up to the
            // timeout and killProcess polls for process exit), so run them off main.
            withContext(Dispatchers.IO) {
                // The core was just told to shut down through the REST API. That path
                // flushes the database and closes connections, and signalling the
                // process first would abort it half-way, so give it a bounded window
                // to finish on its own and only force it if it does not.
                if (wasStarting) {
                    Log.w(TAG, "shutdown: Core never finished starting, force-killing it")
                    runnable.destroyProcess()
                } else if (runnable.awaitProcessExit(GRACEFUL_SHUTDOWN_TIMEOUT_MS)) {
                    Log.i(TAG, "Core exited on its own after the shutdown request")
                } else {
                    Log.w(
                        TAG,
                        "Core did not exit within ${GRACEFUL_SHUTDOWN_TIMEOUT_MS}ms, " +
                                "forcing shutdown"
                    )
                    runnable.destroyProcess()
                }
                // Fallback: end orphaned instances of this app's own UID left behind
                // by an in-place upgrade, which we have no process handle for.
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
            // The process is gone, so config.xml is ours again.
            isCoreRunning = false
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
     * Suspends until the core this service started has settled, i.e. reached
     * [State.ACTIVE] (its startup work, e.g. a delta index reset, is done and the Web GUI
     * is up) or [State.ERROR] (it never will be).
     *
     * @return false on timeout, so the caller can decide what to do with a core that never
     * reported.
     */
    private suspend fun awaitCoreSettled(): Boolean {
        val settled = CompletableDeferred<State>()
        val resolved = AtomicBoolean(false)
        val listener = OnServiceStateChangeListener { state ->
            if ((state == State.ACTIVE || state == State.ERROR) &&
                resolved.compareAndSet(false, true)
            ) {
                settled.complete(state)
            }
        }
        registerOnServiceStateChangeListener(listener)
        return try {
            withTimeoutOrNull(MAINTENANCE_TIMEOUT_MS) { settled.await() } != null
        } finally {
            unregisterOnServiceStateChangeListener(listener)
        }
    }

    /**
     * Result of a maintenance reset that has to restart the core before it is complete.
     */
    enum class MaintenanceResult {
        /**
         * The core restarted and the REST API is available again, so the reset ran.
         */
        COMPLETED,

        /**
         * The core did not come back up, or did not come back up in time. The reset may or
         * may not have run, so the caller must not report success.
         */
        FAILED,
    }

    /**
     * Triggers a maintenance [action] and suspends until the restarted core is back up.
     *
     * Both resets tear the core down and build it up again, which is the only point at which
     * they are actually done. Waiting for that point is what lets a caller acknowledge the
     * action instead of toasting success the moment the intent was sent.
     */
    suspend fun performMaintenanceAndAwait(action: String): MaintenanceResult {
        val pending = CompletableDeferred<MaintenanceResult>()
        val resolved = AtomicBoolean(false)
        var sawStarting = false
        val listener = OnServiceStateChangeListener { state ->
            if (!resolved.compareAndSet(false, true)) {
                return@OnServiceStateChangeListener
            }
            if (state == State.ACTIVE) {
                pending.complete(MaintenanceResult.COMPLETED)
            } else if (state == State.ERROR || (sawStarting && state == State.DISABLED)) {
                pending.complete(MaintenanceResult.FAILED)
            } else {
                resolved.set(false)
                if (state == State.STARTING) {
                    sawStarting = true
                }
            }
        }
        registerOnServiceStateChangeListener(listener)
        return try {
            val intent = Intent(this, SyncthingService::class.java).apply { this.action = action }
            startService(intent)
            withTimeoutOrNull(MAINTENANCE_TIMEOUT_MS) { pending.await() }
                ?: MaintenanceResult.FAILED
        } finally {
            unregisterOnServiceStateChangeListener(listener)
        }
    }

    /**
     * Register a listener for the syncthing API state changing.
     * The listener is called immediately with the current state, and again whenever the state
     * changes.
     *
     * The immediate call happens on whichever thread registers, so a caller on a background
     * thread has to be prepared for its listener to run there once. The later calls all come
     * from [serviceScope], i.e. the GUI thread.
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

        // The whole export owns shutdownMutex: the archive is built from the live config,
        // keys and index database, so a start slipping in between the teardown and the
        // zip would capture a database the core is still writing.
        val shouldRestart = shutdownMutex.withLock {
            if (synchronized(stateLock) { currentState } != State.DISABLED) {
                shutdownLocked(State.DISABLED)
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
                // The archive is built next to the target under a unique temporary name and
                // only renamed over the previous backup once it is complete and readable, so
                // a failure part-way through leaves the last known-good backup in place
                // instead of replacing it with a truncated one.
                var tmpZip: File? = null
                try {
                    val archive = File.createTempFile(
                        targetZip.name,
                        ".tmp",
                        targetZip.parentFile
                    )
                    tmpZip = archive

                    val parameters = ZipParameters()
                    parameters.compressionMethod = CompressionMethod.DEFLATE
                    parameters.compressionLevel = CompressionLevel.NORMAL

                    if (zipEncryptionPassword.isEmpty()) {
                        parameters.isEncryptFiles = false
                    } else {
                        parameters.isEncryptFiles = true
                        parameters.encryptionMethod = EncryptionMethod.AES
                        parameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }

                    ZipFile(archive).use { zipFile ->
                        if (zipEncryptionPassword.isNotEmpty()) {
                            setZipPassword(zipFile, zipEncryptionPassword)
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
                    }

                    // Re-open the finished archive so we never publish a file that cannot
                    // be read back (truncated write, out of space, encryption mismatch).
                    validateExportArchive(archive, zipEncryptionPassword)

                    // Same directory, so this is an atomic replace on the same filesystem.
                    if (!archive.renameTo(targetZip)) {
                        throw IOException(
                            "Failed to replace backup '${targetZip.absolutePath}'"
                        )
                    }
                    tmpZip = null
                    Log.i(TAG, "exportConfig: wrote backup '${targetZip.absolutePath}'")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "exportConfig: Failed to export config, " + e.message)
                    // Best effort: the previous backup, if any, is still intact.
                    tmpZip?.delete()
                    failSuccess = false
                } finally {
                    if (sharedPreferencesFile.exists() == true) {
                        sharedPreferencesFile.delete()
                    }
                }
            }
            synchronized(stateLock) { lastDeterminedShouldRun }
        }
        Log.d(TAG, "exportConfig END")

        // Start syncthing after export if run conditions apply, now that the data
        // directory is complete and shutdownMutex has been released again.
        if (shouldRestart) {
            serviceScope.launch { launchStartupTask(SyncthingRunnable.Command.main) }
        }
        return failSuccess
    }

    /**
     * Verifies that [archive] is a readable zip and contains the files an
     * [importConfig] needs, so a corrupt or truncated export is never installed as
     * the user's backup.
     *
     * @throws ZipException if the archive cannot be opened or a required entry is
     * missing.
     */
    private fun validateExportArchive(archive: File, zipEncryptionPassword: String) {
        val checkFiles = listOf(
            Constants.CONFIG_FILE,
            Constants.PRIVATE_KEY_FILE,
            Constants.PUBLIC_KEY_FILE,
            Constants.SHARED_PREFS_FILE
        )
        ZipFile(archive).use { result ->
            if (zipEncryptionPassword.isNotEmpty()) {
                setZipPassword(result, zipEncryptionPassword)
            }
            for (checkFile in checkFiles) {
                if (result.getFileHeader(checkFile) == null) {
                    throw ZipException(
                        "Exported archive is missing required file [$checkFile]"
                    )
                }
            }
        }
    }

    /**
     * zip4j takes the archive password from the [ZipFile] instance, not from the
     * per-entry [ZipParameters], so it has to be set before any add/extract call.
     */
    private fun setZipPassword(zipFile: ZipFile, password: String) {
        zipFile.setPassword(password.toCharArray())
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
    /**
     * Runs [importConfig] on a scope this service owns and reports the outcome on the main
     * thread.
     *
     * The import republishes the data directory and restarts the core, so its job must not
     * belong to the screen that asked for it: leaving the settings screen (or rotating the
     * device) mid-import would otherwise cancel the swap, and the next start would undo it
     * without the user ever learning that the import had run at all.
     */
    fun importConfigAsync(onResult: (Boolean) -> Unit) {
        shutdownScope.launch {
            val success = importConfig()
            serviceScope.launch { onResult(success) }
        }
    }

    /**
     * Runs [exportConfig] on a scope this service owns, see [importConfigAsync].
     */
    fun exportConfigAsync(onResult: (Boolean) -> Unit) {
        shutdownScope.launch {
            val success = exportConfig()
            serviceScope.launch { onResult(success) }
        }
    }

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
                    Constants.PUBLIC_KEY_FILE,
                    Constants.SHARED_PREFS_FILE
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
        Log.d(TAG, "importConfig BEGIN")
        // The swap replaces config, keys and the index database, so it owns
        // shutdownMutex from the teardown until the last cleanup helper is done: a start
        // slipping into the gap would run the core on a half-published data directory,
        // and a second maintenance action would stack its own swap on top of this one.
        val outcome = shutdownMutex.withLock {
            if (synchronized(stateLock) { currentState } != State.DISABLED) {
                shutdownLocked(State.DISABLED)
            }

            // An earlier import may have died after it had already begun replacing the
            // live files but before it committed; undo that first so we never stack a
            // second swap on top of a half-published config.
            recoverInterruptedImport()

            // Extract the zip to a staging directory first so a corrupt or
            // incomplete archive cannot destroy the current config/index database.
            val cacheDir = this@SyncthingService.cacheDir
            val stagingDir = File(cacheDir, "import_staging_" + System.currentTimeMillis())
            val rollbackDir = File(filesDir, Constants.IMPORT_ROLLBACK_DIR)
            val outcome: ImportOutcome
            try {
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
                outcome = if (extractSucceeded) {
                    // Fully parse and cross-check the staged archive. Nothing live has
                    // been touched yet, so a rejected archive costs nothing.
                    val staged = validateStagedImport(stagingDir)
                    if (staged == null) {
                        ImportOutcome.BROKEN
                    } else {
                        publishStagedImport(staged, rollbackDir)
                    }
                } else {
                    ImportOutcome.BROKEN
                }
            } finally {
                withContext(Dispatchers.IO) {
                    deleteDirectoryRecursively(stagingDir)
                }
            }

            if (outcome == ImportOutcome.COMMITTED) {
                try {
                    cleanupImportedFolderDatabases()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e)
                }
            }
            outcome
        }

        // Restart only once the live state is known to be good: either the archive was
        // published in full, or the originals were put back completely. An import that
        // left the data directory in an unknown state must not bring the core up on it.
        if (outcome != ImportOutcome.BROKEN && synchronized(stateLock) { lastDeterminedShouldRun }) {
            serviceScope.launch { launchStartupTask(SyncthingRunnable.Command.main) }
        }
        return outcome == ImportOutcome.COMMITTED
    }

    /**
     * How an import attempt ended, see [importConfig].
     */
    private enum class ImportOutcome {
        /**
         * The archive was validated, published in full and committed.
         */
        COMMITTED,

        /**
         * The archive was rejected or the swap failed; the previous config, keys,
         * database and preferences are back in place.
         */
        ROLLED_BACK,

        /**
         * The live state could not be restored, so the app must not start on it.
         */
        BROKEN
    }

    /**
     * A live file that was moved aside by [publishStagedImport] and can be put back by
     * [restoreRollbacks].
     */
    private class ImportRollback(val live: File, val backup: File)

    /**
     * An extracted archive that passed every validation in [validateStagedImport] and
     * may therefore replace the live data directory.
     */
    private class StagedImport(
        /** Device ID carried by the imported certificate, cross-checked against the config. */
        val deviceId: String,
        /** Extracted SharedPreferences backup. */
        val sharedPrefsFile: File,
        /** Archive members to publish into the data directory, excluding the prefs backup. */
        val entries: List<File>
    )

    /**
     * Fully parses and cross-checks an extracted archive before it is allowed to replace
     * anything live.
     *
     * Filenames alone prove nothing, so the staged set is checked for real: the config
     * must parse and declare well-formed device IDs, one of which must be the identity
     * of the imported certificate, the private key must be a readable PEM key, the
     * preferences backup must deserialize, and the optional Web GUI certificate pair
     * must parse whenever it is present.
     *
     * @return the staged set, or null when the archive must not be imported.
     */
    private fun validateStagedImport(stagingDir: File): StagedImport? {
        for (name in importRequiredFiles) {
            val file = File(stagingDir, name)
            if (!file.isFile || file.length() == 0L) {
                Log.e(TAG, "importConfig: Missing or empty file after extraction [$name]")
                return null
            }
        }

        val configDeviceIds = readDeviceIdsFromConfig(File(stagingDir, Constants.CONFIG_FILE))
        if (configDeviceIds.isEmpty()) {
            Log.e(TAG, "importConfig: Imported config declares no valid device ID")
            return null
        }

        val certDeviceId = readDeviceIdFromCertificate(File(stagingDir, Constants.PUBLIC_KEY_FILE))
        if (certDeviceId == null) {
            Log.e(TAG, "importConfig: Imported certificate is not a readable X.509 certificate")
            return null
        }
        val knownAsThisDevice = configDeviceIds.any { it.equals(certDeviceId, ignoreCase = true) }
        if (!knownAsThisDevice) {
            Log.e(
                TAG,
                "importConfig: Imported certificate identifies as \"$certDeviceId\", which the imported config does not know - the archive mixes two identities"
            )
            return null
        }

        val privateKeyFile = File(stagingDir, Constants.PRIVATE_KEY_FILE)
        val privateKeyBytes = privateKeyFile.readBytes()
        if (!isReadablePrivateKey(privateKeyBytes)) {
            Log.e(TAG, "importConfig: Imported private key is not a readable PEM key")
            return null
        }
        if (CertificateValidator.keyBelongsToCertificate(
                File(stagingDir, Constants.PUBLIC_KEY_FILE).readBytes(), privateKeyBytes
            ) == CertificateValidator.Status.FAIL
        ) {
            Log.e(
                TAG,
                "importConfig: Imported private key does not belong to the imported certificate - the archive mixes two identities"
            )
            return null
        }

        // The Web GUI certificate pair is not in every archive (older exports predate
        // it), but a half pair or an unparsable one is rejected.
        val httpsCert = File(stagingDir, Constants.HTTPS_CERT_FILE)
        val httpsKey = File(stagingDir, Constants.HTTPS_KEY_FILE)
        if (httpsCert.isFile != httpsKey.isFile) {
            Log.e(
                TAG,
                "importConfig: Imported archive contains only one half of the Web GUI certificate pair"
            )
            return null
        }
        if (httpsCert.isFile) {
            if (readDeviceIdFromCertificate(httpsCert) == null) {
                Log.e(TAG, "importConfig: Imported Web GUI certificate is not readable")
                return null
            }
            if (!isReadablePrivateKey(httpsKey.readBytes())) {
                Log.e(TAG, "importConfig: Imported Web GUI key is not a readable PEM key")
                return null
            }
        }

        val sharedPrefsFile = File(stagingDir, Constants.SHARED_PREFS_FILE)
        val prefsBackup = try {
            readSharedPrefs(sharedPrefsFile)
        } catch (e: Exception) {
            Log.e(TAG, "importConfig: Imported shared preferences backup is unreadable", e)
            null
        }
        if (prefsBackup == null || prefsBackup.isEmpty) {
            Log.e(TAG, "importConfig: Imported shared preferences backup is empty")
            return null
        }

        return StagedImport(
            deviceId = certDeviceId,
            sharedPrefsFile = sharedPrefsFile,
            entries = stagingDir.listFiles().orEmpty()
                .filter { it.name != Constants.SHARED_PREFS_FILE }
                .filter { File(it.name).name == it.name && it.name.isNotBlank() }
        )
    }

    /**
     * Publishes a validated [staged] archive over the live config, keys and database.
     *
     * Every live entry is first renamed into [rollbackDir] and the current preferences
     * are snapshotted there, so an incomplete swap can be undone completely. The import
     * only counts as done once [Constants.IMPORT_COMMIT_MARKER] is written: until then a
     * process death leaves a rollback directory without a marker, which
     * [recoverInterruptedImport] detects and undoes.
     *
     * @return [ImportOutcome.COMMITTED] when the archive is fully live, [ImportOutcome.ROLLED_BACK]
     * when the originals are back, [ImportOutcome.BROKEN] when they could not be restored.
     */
    private suspend fun publishStagedImport(
        staged: StagedImport,
        rollbackDir: File
    ): ImportOutcome {
        val rollbacks = mutableListOf<ImportRollback>()
        val prefsSnapshot = File(rollbackDir, Constants.IMPORT_PREFS_SNAPSHOT)
        val preferencesBackedUp = AtomicBoolean(false)

        val failed = withContext(Dispatchers.IO) {
            if (!rollbackDir.isDirectory && !rollbackDir.mkdirs()) {
                Log.e(TAG, "importConfig: Unable to create the rollback directory")
                return@withContext true
            }
            writeSharedPrefsJson(prefsSnapshot)
            preferencesBackedUp.set(true)

            // Move the live entries aside first, so nothing is destroyed until every
            // rename out of the way has succeeded.
            for (entry in staged.entries) {
                val live = File(filesDir, entry.name)
                if (!live.exists()) {
                    continue
                }
                if (!moveToRollback(live, rollbackDir, rollbacks)) {
                    return@withContext true
                }
            }
            // The database belongs to the previous identity unless the archive brings
            // its own; either way it must be recoverable until the commit.
            val indexDb = getIndexDbFolder(this@SyncthingService)
            if (indexDb.exists() && staged.entries.none { it.name == indexDb.name }) {
                if (!moveToRollback(indexDb, rollbackDir, rollbacks)) {
                    return@withContext true
                }
            }

            for (entry in staged.entries) {
                if (!entry.renameTo(File(filesDir, entry.name))) {
                    Log.e(TAG, "importConfig: Failed to publish [" + entry.name + "]")
                    return@withContext true
                }
            }

            // The cached device ID is derived from the certificate that just went live, so
            // it has to move in the same commit as the rest of the preferences. Leaving
            // the old one behind would mislabel every device in the restored config.
            !importConfigSharedPrefs(
                staged.sharedPrefsFile,
                restoredDeviceId = staged.deviceId
            )
        }

        if (failed) {
            return withContext(Dispatchers.IO) {
                if (restoreRollbacks(rollbacks, prefsSnapshot, preferencesBackedUp.get())) {
                    ImportOutcome.ROLLED_BACK
                } else {
                    Log.e(
                        TAG,
                        "importConfig: Import failed and the previous config could not be restored"
                    )
                    ImportOutcome.BROKEN
                }
            }
        }

        // Everything is in place: publish the commit marker, which turns the rollback
        // copies into garbage that the next import simply replaces.
        return withContext(Dispatchers.IO) {
            if (!File(rollbackDir, Constants.IMPORT_COMMIT_MARKER).createNewFile() && !File(
                    rollbackDir,
                    Constants.IMPORT_COMMIT_MARKER
                ).exists()
            ) {
                Log.e(TAG, "importConfig: Failed to write the import commit marker")
                return@withContext ImportOutcome.BROKEN
            }
            /**
             * The rollback copies are deliberately kept, not deleted here: everything this
             * device can check locally has passed, but whether the core accepts the
             * imported config, keys and database is only known once it has started on
             * them. [discardRetainedImportRollback] drops them after that.
             */
            // The archive may have brought a different Web GUI certificate. The pinned
            // one is cached inside the shared OkHttp client, which would then refuse the
            // core it is about to talk to, so drop it and let the next request rebuild.
            SyncthingHttpClients.invalidate()
            Log.i(TAG, "importConfig: Imported device identity \"${staged.deviceId}\"")
            ImportOutcome.COMMITTED
        }
    }

    /**
     * Puts a file or directory back under its original name inside [rollbackDir].
     *
     * @return false when the move failed, in which case nothing was recorded.
     */
    private fun moveToRollback(
        live: File,
        rollbackDir: File,
        rollbacks: MutableList<ImportRollback>
    ): Boolean {
        val backup = File(rollbackDir, live.name)
        deleteDirectoryRecursively(backup)
        if (!live.renameTo(backup)) {
            Log.e(TAG, "importConfig: Failed to move [" + live.name + "] aside")
            return false
        }
        rollbacks.add(ImportRollback(live, backup))
        return true
    }

    /**
     * Restores every entry moved aside by [publishStagedImport] and, when it was taken,
     * the pre-import shared preferences.
     *
     * @return false when at least one entry could not be put back.
     */
    private fun restoreRollbacks(
        rollbacks: List<ImportRollback>,
        prefsSnapshot: File,
        restorePreferences: Boolean
    ): Boolean {
        var ok = true
        for (rollback in rollbacks) {
            if (!rollback.backup.exists()) {
                continue
            }
            deleteDirectoryRecursively(rollback.live)
            if (!rollback.backup.renameTo(rollback.live)) {
                Log.e(TAG, "importConfig: Failed to restore [" + rollback.live.name + "]")
                ok = false
            }
        }
        if (restorePreferences) {
            if (!importConfigSharedPrefs(prefsSnapshot, skipFilteredPrefs = false)) {
                Log.e(TAG, "importConfig: Failed to restore the previous shared preferences")
                ok = false
            }
        }
        deleteDirectoryRecursively(prefsSnapshot.parentFile)
        return ok
    }

    /**
     * Drops the rollback copies that a committed import kept, once the core has proven
     * that it can run on the imported data directory.
     *
     * They have to outlive the commit marker because everything this device can check
     * locally passes for a config the core still refuses to load, and the previous config
     * is already gone by then.
     */
    private fun discardRetainedImportRollback() {
        val rollbackDir = File(filesDir, Constants.IMPORT_ROLLBACK_DIR)
        if (!rollbackDir.isDirectory ||
            !File(rollbackDir, Constants.IMPORT_COMMIT_MARKER).exists()
        ) {
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            deleteDirectoryRecursively(rollbackDir)
            Log.i(TAG, "discardRetainedImportRollback: Import confirmed by a successful start")
        }
    }

    /**
     * Undoes an import that was interrupted between the destructive swap and its commit
     * marker (process death, battery pull, force stop). An import that reached the
     * marker is complete and its rollback copies are discarded once the core is up.
     */
    private suspend fun recoverInterruptedImport() {
        val rollbackDir = File(filesDir, Constants.IMPORT_ROLLBACK_DIR)
        withContext(Dispatchers.IO) {
            if (!rollbackDir.isDirectory ||
                File(rollbackDir, Constants.IMPORT_COMMIT_MARKER).exists()
            ) {
                return@withContext
            }
            Log.w(TAG, "importConfig: Found an interrupted import, restoring the previous config")
            val prefsSnapshot = File(rollbackDir, Constants.IMPORT_PREFS_SNAPSHOT)
            val rollbacks = rollbackDir.listFiles().orEmpty()
                .filter { it.name != Constants.IMPORT_COMMIT_MARKER && it.name != prefsSnapshot.name }
                .map { ImportRollback(File(filesDir, it.name), it) }
            restoreRollbacks(rollbacks, prefsSnapshot, prefsSnapshot.exists())
        }
    }

    /**
     * Reads the IDs of every device declared by a config file.
     *
     * @return the well-formed device IDs, or an empty set when the file does not parse
     * or declares none.
     */
    private fun readDeviceIdsFromConfig(configFile: File): Set<String> {
        val document = try {
            FileInputStream(configFile).use { inputStream ->
                val inputSource = InputSource(InputStreamReader(inputStream, Charsets.UTF_8))
                inputSource.encoding = "UTF-8"
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputSource)
            }
        } catch (e: Exception) {
            Log.e(TAG, "importConfig: Failed to parse the imported config", e)
            return emptySet()
        }

        val nodes = document.getElementsByTagName("device")
        val deviceIds = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val id = (nodes.item(i) as? Element)?.getAttribute("id") ?: continue
            val device = Device().apply { deviceID = id }
            if (device.checkDeviceID()) {
                deviceIds.add(id)
            }
        }
        return deviceIds
    }

    /**
     * Reads the Syncthing device ID a certificate identifies as, i.e. the CN of its
     * subject.
     *
     * @return the device ID, or null when the file is not a readable certificate.
     */
    private fun readDeviceIdFromCertificate(certFile: File): String? {
        return try {
            FileInputStream(certFile).use { inputStream ->
                val leaf = CertificateFactory.getInstance("X.509")
                    .generateCertificate(inputStream) as X509Certificate
                leaf.subjectX500Principal.name.split(",")
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
                    ?.substring(3)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns whether the bytes are a private key this device can decode. Encrypted and
     * legacy PKCS#1 keys only have to carry a PEM private key block; the Syncthing core
     * remains the authority on whether it can use them.
     */
    private fun isReadablePrivateKey(keyBytes: ByteArray): Boolean {
        val pem = String(keyBytes, Charsets.US_ASCII)
        if (!pem.contains("PRIVATE KEY-----")) {
            return false
        }
        if (!pem.contains("BEGIN PRIVATE KEY")) {
            return true
        }
        val der = try {
            val body = pem.substringAfter("-----BEGIN PRIVATE KEY-----")
                .substringBefore("-----END PRIVATE KEY-----")
                .filterNot { it.isWhitespace() }
            Base64.decode(body, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return supportedKeyAlgorithms.any { algorithm ->
            try {
                KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Replaces the Web GUI HTTPS certificate and key with the supplied PEM bytes, then restarts
     * Syncthing so the new files take effect. If the restart fails to bring the Web GUI back online,
     * the previous certificate and key are restored automatically.
     * 
     * 
     * The bytes are expected to already be validated (see [com.micnubinub.syncthing.util.CertificateValidator]).
     * The whole start/stop lifecycle is marshalled onto the main thread because the binary
     * orchestration fields are only safe to touch there; the file work is dispatched to IO.
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
        val certFile = getHttpsCertFile(this)
        val keyFile = getHttpsKeyFile(this)

        var writeFailure: IOException? = null
        val (certBak, keyBak) = shutdownMutex.withLock {
            // Stop the binary so it releases the cert/key before we overwrite them.
            // The whole replacement runs under the mutex: a start slipping in between
            // the teardown and the write would have the core bind the old certificate
            // and rewrite the new one out from under it.
            if (synchronized(stateLock) { currentState } != State.DISABLED) {
                shutdownLocked(State.DISABLED)
            }

            withContext(Dispatchers.IO) {
                val certBak = backupFile(certFile)
                val keyBak = backupFile(keyFile)

                try {
                    writeBytesAtomic(certFile, certPem)
                    writeBytesAtomic(keyFile, keyPem)
                    restrictToOwner(keyFile)
                } catch (e: IOException) {
                    Log.e(TAG, "doReplaceHttpsCertificate: Failed to write new cert/key", e)
                    writeFailure = e
                    restoreFile(certBak, certFile)
                    restoreFile(keyBak, keyFile)
                }
                Pair(certBak, keyBak)
            }
        }

        if (writeFailure != null) {
            if (synchronized(stateLock) { lastDeterminedShouldRun }) {
                launchStartupTask(SyncthingRunnable.Command.main)
            }
            listener.onResult(HttpsCertReplaceResult.FAILED, writeFailure?.message)
            return
        }

        applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, listener)
    }

    private suspend fun doResetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        val certFile = getHttpsCertFile(this)
        val keyFile = getHttpsKeyFile(this)

        val (certBak, keyBak) = shutdownMutex.withLock {
            if (synchronized(stateLock) { currentState } != State.DISABLED) {
                shutdownLocked(State.DISABLED)
            }

            withContext(Dispatchers.IO) {
                val certBak = backupFile(certFile)
                val keyBak = backupFile(keyFile)
                // Removing the files makes syncthing generate a fresh self-signed certificate at startup.
                deleteQuietly(certFile)
                deleteQuietly(keyFile)
                Pair(certBak, keyBak)
            }
        }

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

    /**
     * Replaces [preferences] with the contents of a preferences backup file.
     *
     * @param skipFilteredPrefs when true the deprecated and cache keys listed in
     * [importConfigSkipPref] are dropped, which is what an import wants. A rollback must
     * restore them verbatim because [applySharedPrefsBackup] clears the whole map first.
     * @param restoredDeviceId the identity the imported key pair and config agree on. It
     * replaces the cached [Constants.PREF_LOCAL_DEVICE_ID] in the same commit, because
     * the cache is derived from the certificate and must never outlive it.
     * @return true when the backup was read and applied.
     */
    private fun importConfigSharedPrefs(
        file: File?,
        skipFilteredPrefs: Boolean = true,
        restoredDeviceId: String? = null
    ): Boolean {
        if (file == null || !file.exists()) {
            return false
        }
        return try {
            val parsedPrefs = readSharedPrefs(file)
            if (parsedPrefs == null || parsedPrefs.isEmpty) {
                Log.e(TAG, "importConfig: Invalid object stream")
                false
            } else {
                // The archive location and its password describe where to import from, not
                // part of the backup, so they survive the import.
                val relPathToZip: String = preferences.getString(
                    Constants.PREF_BACKUP_REL_PATH_TO_ZIP,
                    ""
                ) ?: ""
                val backupPassword: String =
                    preferences.getString(Constants.PREF_BACKUP_PASSWORD, "").orEmpty()
                val preserved = mutableMapOf(
                    Constants.PREF_BACKUP_REL_PATH_TO_ZIP to relPathToZip,
                    Constants.PREF_BACKUP_PASSWORD to backupPassword
                )
                if (restoredDeviceId != null) {
                    preserved[Constants.PREF_LOCAL_DEVICE_ID] = restoredDeviceId
                }
                applySharedPrefsBackup(
                    parsedPrefs,
                    skipFilteredPrefs = skipFilteredPrefs,
                    // A rollback restores the snapshot verbatim, so it carries its own
                    // device ID and needs no preservation.
                    preservedStrings = if (skipFilteredPrefs) preserved else emptyMap()
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "importConfig: Invalid object stream", e)
            false
        }
    }

    /**
     * Replaces the whole [preferences] map with [backup]. Note this clears first, so a
     * key that is not in [backup] is gone afterwards.
     */
    private fun applySharedPrefsBackup(
        backup: SharedPrefsBackup,
        skipFilteredPrefs: Boolean,
        preservedStrings: Map<String, String> = emptyMap()
    ) {
        preferences.edit {
            clear()
            backup.booleans?.forEach { (prefKey, value) ->
                if (!skipPref(prefKey, skipFilteredPrefs)) {
                    putBoolean(prefKey, value)
                }
            }
            backup.strings?.forEach { (prefKey, value) ->
                if (!skipPref(prefKey, skipFilteredPrefs)) {
                    putString(prefKey, value)
                }
            }
            backup.ints?.forEach { (prefKey, value) ->
                if (!skipPref(prefKey, skipFilteredPrefs)) {
                    putInt(prefKey, value)
                }
            }
            backup.longs?.forEach { (prefKey, value) ->
                if (!skipPref(prefKey, skipFilteredPrefs)) {
                    putLong(prefKey, value)
                }
            }
            backup.floats?.forEach { (prefKey, value) ->
                if (!skipPref(prefKey, skipFilteredPrefs)) {
                    putFloat(prefKey, value)
                }
            }
            backup.stringSets?.forEach { (prefKey, value) ->
                if (!skipPref(prefKey, skipFilteredPrefs)) {
                    Log.i(TAG, "importConfig: Adding pref \"$prefKey\" to commit ...")
                    value.let { putStringSet(prefKey, it) }
                }
            }
            preservedStrings.forEach { (prefKey, value) -> putString(prefKey, value) }
        }
    }

    private fun skipPref(prefKey: String, skipFilteredPrefs: Boolean): Boolean {
        return skipFilteredPrefs && importConfigSkipPref(prefKey)
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

        /**
         * The core generation a crash report was produced by, see
         * [EXTRA_CORE_GENERATION].
         */
        const val EXTRA_CORE_GENERATION: String = ".SyncthingService.EXTRA_CORE_GENERATION"

        /**
         * Archive members without which an import cannot be trusted, because every one
         * of them is required to bring the previous identity back up.
         */
        private val importRequiredFiles = listOf(
            Constants.CONFIG_FILE,
            Constants.PRIVATE_KEY_FILE,
            Constants.PUBLIC_KEY_FILE,
            Constants.SHARED_PREFS_FILE
        )

        /**
         * Key algorithms a Syncthing device identity may use.
         */
        private val supportedKeyAlgorithms = listOf("EC", "RSA")

        private const val TAG = "SyncthingService"

        @Volatile
        @JvmStatic
        var isServiceRunning = false
            private set

        /**
         * Serializes the native core lifecycle. Shared with [ConfigRouter] so that a
         * direct config.xml write and a core start/stop can never overlap: config.xml
         * belongs to the core for as long as it runs (see [isCoreRunning]), and the core
         * may only be brought up while no such write is in flight.
         */
        val coreLifecycleMutex = Mutex()

        /**
         * True from the moment the native core is handed the config until its process is
         * confirmed dead. Deliberately wider than "the service is running" and than
         * "the REST API has a config": a direct config.xml write is only safe outside
         * this window, because a core that is starting already owns the file.
         */
        @Volatile
        @JvmStatic
        var isCoreRunning = false
            private set

        /**
         * Backstop timeout for [.verifyRestartAndRollback] in case the state machine never reaches
         * a terminal state (e.g. the binary crashed via a path that doesn't transition to ERROR).
         */
        private const val HTTPS_CERT_VERIFY_TIMEOUT_MS: Long = 30000

        /**
         * Backstop timeout for [.performMaintenanceAndAwait]: a database or delta reset
         * restarts the core, and a reset that never brings it back must still resolve.
         */
        private const val MAINTENANCE_TIMEOUT_MS: Long = 60000
        private const val SYNCTHING_THREAD_JOIN_TIMEOUT_MS: Long = 10000

        /**
         * How long the core is given to complete the graceful shutdown it was asked for
         * through `/rest/system/shutdown` before it is signalled instead. Long enough for
         * a database flush on slow storage, short enough that stopping the service stays
         * responsive.
         */
        private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS: Long = 5000
    }
}
