package com.micnubinub.syncthing.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.Build
import android.os.Trace
import android.util.Log
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.service.AppPrefs.getPrefVerboseLog
import com.micnubinub.syncthing.service.Constants.getSyncthingBinary
import com.micnubinub.syncthing.service.Constants.getSyncthingLogFile
import com.micnubinub.syncthing.util.FileUtils.syncthingTildeAbsolutePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Runs the syncthing binary from command line, and prints its output to logcat.
 * 
 * @see [Command Line Docs](http://docs.syncthing.net/users/syncthing.html)
 */
class SyncthingRunnable(private val context: Context, command: Command) : Runnable {
    private val syncthingBinary: File
    private val syncthingLogFile: File
    private val commandStrings: Array<String>

    @Inject
    lateinit var preferences: SharedPreferences

    @Inject
    lateinit var notificationHandler: NotificationHandler
    private var ENABLE_VERBOSE_LOG = false
    private val syncthing = AtomicReference<Process>()

    /**
     * Constructs instance.
     *
     * @param command Which type of Syncthing command to execute.
     */
    init {
        (context.applicationContext as SyncthingApp).component().inject(this)
        ENABLE_VERBOSE_LOG = getPrefVerboseLog(preferences)
        // Example: syncthingBinary="/data/app/${applicationId}-8HsN-IsVtZXc8GrE5-Hepw==/lib/x86/libsyncthingnative.so"
        syncthingBinary = getSyncthingBinary(context)
        syncthingLogFile = getSyncthingLogFile(context)

        // Get preferences relevant to starting syncthing core.
        commandStrings = when (command) {
            Command.deviceid -> arrayOf(syncthingBinary.path, "device-id")
            Command.generate -> arrayOf(syncthingBinary.path, "generate")
            Command.main -> arrayOf(syncthingBinary.path, "serve", "--no-browser")
            Command.resetdatabase -> arrayOf(syncthingBinary.path, "debug", "reset-database")
            Command.resetdeltas -> arrayOf(
                syncthingBinary.path,
                "serve",
                "--debug-reset-delta-idxs"
            )
        }
    }

    override fun run() {
        try {
            run(false)
        } catch (e: ExecutableNotFoundException) {
            throw RuntimeException(e.message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Throws(ExecutableNotFoundException::class)
    fun run(returnStdOut: Boolean): String {
        Trace.beginSection("SyncthingRunnable.run")
        try {
            return runInternal(returnStdOut)
        } finally {
            Trace.endSection()
        }
    }

    @Throws(ExecutableNotFoundException::class)
    private fun runInternal(returnStdOut: Boolean): String {
        var sendStopToService = false
        var restartSyncthingNative = false
        val exitCode: Int
        val capturedStdOutBuilder = StringBuilder()

        // Trim Syncthing log off the native startup path, so a large log file
        // does not delay starting the binary.
        val trimLogJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            trisyncthingLogFile()
        }

        var multicastLock: MulticastLock? = null
        var process: Process? = null
        var streamReadScope: CoroutineScope? = null

        try {
            // Android 11 blocks local discovery if we did not acquire MulticastLock.
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("multicastLock")
            multicastLock.acquire()

            /**
             * Setup and run a new syncthing instance
             */
            val targetEnv = buildEnvironment()
            process = setupAndLaunch(targetEnv)

            syncthing.set(process)

            var infoJob: Job? = null
            var warnJob: Job? = null
            if (returnStdOut) {
                // Drain stderr on a separate thread so a full stderr pipe cannot
                // block the child process while stdout is read to EOF below.
                val stderrDrainThread = thread(start = true, isDaemon = true) {
                    try {
                        val errReader = process.errorStream.bufferedReader(Charset.forName("UTF-8"))
                        errReader.use {
                            while (it.readLine() != null) { /* discard */
                            }
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "Failed to drain Syncthing's stderr", e)
                    }
                }
                try {
                    BufferedReader(
                        InputStreamReader(
                            process.inputStream,
                            Charset.forName("UTF-8")
                        )
                    ).use { br ->
                        while (true) {
                            val line = br.readLine() ?: break
                            Log.i(TAG_NATIVE, line)
                            capturedStdOutBuilder.append(line).append('\n')
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to read Syncthing's command line output", e)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    stderrDrainThread.join(PROCESS_EXIT_TIMEOUT_SECONDS * 1000L)
                }
            } else {
                streamReadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

                infoJob = streamReadScope.launch {
                    readStream(process.inputStream)
                }

                warnJob = streamReadScope.launch {
                    readStream(process.errorStream)
                }
            }

            // Check for quick crash then wait for the process to exit naturally.
            // Short-lived commands (deviceid, generate, resetdatabase) should finish
            // within the timeout. Long-running commands (main, resetdeltas) will keep
            // running until told to stop via api.shutdown() / killProcess().
            val isLongRunningCommand = commandStrings.size > 1 &&
                    (commandStrings[1] == "serve" || commandStrings[1] == "--debug-reset-delta-idxs")
            exitCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when {
                    !process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                            isLongRunningCommand -> {
                        LogV("Syncthing process is still running after initial timeout (expected for serve command)")
                        process.waitFor()
                        process.exitValue()
                    }

                    !process.isAlive -> {
                        process.exitValue().also {
                            LogV("Syncthing exited with code $it")
                        }
                    }

                    else -> {
                        Log.w(
                            TAG,
                            "Syncthing did not exit within $PROCESS_EXIT_TIMEOUT_SECONDS seconds, destroying forcibly"
                        )
                        process.destroyForcibly()
                        process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        EXIT_FORCE_KILL
                    }
                }
            } else {
                // API 24-25 lack waitFor(timeout)/isAlive/destroyForcibly.
                process.waitFor()
                process.exitValue()
            }
            syncthing.set(null)

            if (infoJob != null || warnJob != null) {
                val latch = CountDownLatch(2)
                infoJob?.invokeOnCompletion { latch.countDown() }
                warnJob?.invokeOnCompletion { latch.countDown() }
                if (!latch.await(STREAM_READER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Timed out waiting for stream readers to finish")
                }
                streamReadScope?.cancel()
            }

            when (exitCode) {
                0, 137 -> Log.i(
                    TAG,
                    "Syncthing was shut down normally via API or SIGKILL. Exit code = $exitCode"
                )

                1 -> {
                    Log.w(
                        TAG,
                        "exit reason = exitError. Another Syncthing instance may be already running."
                    )
                    notificationHandler.showCrashedNotification(
                        R.string.notification_crash_title,
                        exitCode.toString()
                    )
                    sendStopToService = true
                }

                2 -> {
                    // This should not happen as STNOUPGRADE is set.
                    Log.w(
                        TAG,
                        "exit reason = exitNoUpgradeAvailable. Another Syncthing instance may be already running."
                    )
                    notificationHandler.showCrashedNotification(
                        R.string.notification_crash_title,
                        exitCode.toString()
                    )
                    sendStopToService = true
                }

                3 -> {
                    // Restart was requested via Rest API call.
                    Log.i(TAG, "exit reason = exitRestarting. Restarting syncthing.")
                    restartSyncthingNative = true
                }

                9 -> {
                    // Native was force killed.
                    Log.w(TAG, "exit reason = exitForceKill.")
                    notificationHandler.showCrashedNotification(
                        R.string.notification_crash_title,
                        exitCode.toString()
                    )
                    sendStopToService = true
                }

                64 -> {
                    Log.w(TAG, "exit reason = exitInvalidCommandLine.")
                    notificationHandler.showCrashedNotification(
                        R.string.notification_crash_title,
                        exitCode.toString()
                    )
                    sendStopToService = true
                }

                else -> {
                    Log.w(TAG, "Syncthing exited unexpectedly. Exit code = $exitCode")
                    notificationHandler.showCrashedNotification(
                        R.string.notification_crash_title,
                        exitCode.toString()
                    )
                    sendStopToService = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } finally {
            trimLogJob.cancel()
            streamReadScope?.cancel()
            multicastLock?.let {
                multicastLock.release()
                multicastLock = null
            }
            process?.destroy()
        }

        // Restart syncthing if it exited unexpectedly while running on a separate thread.
        if (!returnStdOut && restartSyncthingNative) {
            context.startService(
                Intent(context, SyncthingService::class.java)
                    .setAction(SyncthingService.ACTION_RESTART)
            )
        }

        // Notify {@link SyncthingService} that service state State.ACTIVE is no longer valid.
        if (!returnStdOut && sendStopToService) {
            val intent = Intent(context, SyncthingService::class.java)
            intent.action = SyncthingService.ACTION_STOP
            intent.putExtra(SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE, true)
            context.startService(intent)
        }

        // Return captured command line output.
        return capturedStdOutBuilder.toString()
    }

    private fun putCustomEnvironmentVariables(
        environment: MutableMap<String, String>,
        sp: SharedPreferences
    ) {
        val customEnvironment = sp.getString(Constants.PREF_ENVIRONMENT_VARIABLES, null)
        if (customEnvironment.isNullOrEmpty()) return

        customEnvironment.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.let {
            for (e in it) {
                val e2: List<String> = e.split("=".toRegex(), limit = 2)
                if (e2.size != 2) {
                    Log.w(TAG, "Skipping malformed env var: $e")
                    continue
                }
                LogV("Setting env var: [" + e2[0] + "]=[" + e2[1] + "]")
                environment[e2[0]] = e2[1]
            }
        }
    }

    private suspend fun readStream(inputStream: InputStream) {
        withContext(Dispatchers.IO) {
            try {
                FileOutputStream(syncthingLogFile, true).use { output ->
                    inputStream.copyTo(output)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read Syncthing's command line output", e)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Only keep last [.LOG_FILE_MAX_LINES] lines in log file, to avoid bloat.
     */
    private fun trisyncthingLogFile() {
        if (!syncthingLogFile.exists()) {
            return
        }

        try {
            RandomAccessFile(syncthingLogFile, "r").use { input ->
                val buffer = ByteArray(LOG_FILE_BUFFER_SIZE)
                val length = input.length()

                var newlinesRemaining = LOG_FILE_MAX_LINES + 1
                var truncationOffset = -1L

                var offset = length

                while (offset > 0) {
                    val chunkSize = minOf(offset, buffer.size.toLong()).toInt()
                    offset -= chunkSize

                    input.seek(offset)
                    input.readFully(buffer, 0, chunkSize)

                    val result = findNthLastNewline(
                        buffer,
                        chunkSize,
                        newlinesRemaining
                    )

                    if (result >= 0) {
                        truncationOffset = offset + result + 1L
                        break
                    }

                    newlinesRemaining = -result
                }

                if (truncationOffset < 0L) {
                    return
                }

                input.seek(truncationOffset)

                val tempFile = File(context.filesDir, "syncthing.log.tmp")
                var remaining = length - truncationOffset

                FileOutputStream(tempFile).use { output ->
                    while (remaining > 0L) {
                        val chunkSize = minOf(remaining, buffer.size.toLong()).toInt()

                        input.readFully(buffer, 0, chunkSize)
                        output.write(buffer, 0, chunkSize)

                        remaining -= chunkSize.toLong()
                    }

//      Return if crashing              output.fd.sync()
                }

                if (!tempFile.renameTo(syncthingLogFile)) {
                    Log.w(TAG, "Failed to rename trimmed log file to '$syncthingLogFile'")
                    tempFile.delete()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to trim log file", e)
        }
    }

    private fun buildEnvironment(): HashMap<String, String> {
        val targetEnv = HashMap<String, String>(15)

        // Set home directory to data folder for web GUI folder picker.
        targetEnv["HOME"] = syncthingTildeAbsolutePath

        // Set config, key and database directory.
        targetEnv["STHOMEDIR"] = context.filesDir.toString()
        targetEnv["STTRACE"] = preferences.getStringSet(
            Constants.PREF_DEBUG_FACILITIES_ENABLED,
            HashSet()
        )?.joinToString(" ") ?: ""

        targetEnv["STMONITORED"] = "1"
        targetEnv["STNOUPGRADE"] = "1"
        targetEnv["STVERSIONEXTRA"] = context.getString(R.string.app_name)

        // Database tuning against slowness.
        targetEnv["SQLITE_TMPDIR"] = context.cacheDir.absolutePath

        // Workaround SyncthingNativeCode denied to read gatewayIP by Android 14+ restriction.
        val gatewayIpV4: String? = getGatewayIpV4(context)
        if (gatewayIpV4 != null) {
            targetEnv["FALLBACK_NET_GATEWAY_IPV4"] = gatewayIpV4
        }

        if (preferences.getBoolean(Constants.PREF_USE_TOR, false) == true) {
            targetEnv["all_proxy"] = "socks5://localhost:9050"
            targetEnv["ALL_PROXY_NO_FALLBACK"] = "1"
        } else {
            val socksProxyAddress = preferences.getString(
                Constants.PREF_SOCKS_PROXY_ADDRESS,
                ""
            )
            if (socksProxyAddress?.isNotEmpty() == true) {
                targetEnv["all_proxy"] = socksProxyAddress
            }

            val httpProxyAddress = preferences.getString(
                Constants.PREF_HTTP_PROXY_ADDRESS,
                ""
            )
            if (httpProxyAddress?.isNotEmpty() == true) {
                targetEnv["http_proxy"] = httpProxyAddress
                targetEnv["https_proxy"] = httpProxyAddress
            }
        }

        // Optimize memory usage for older devices.
        var gogc = 100 // GO default
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            gogc = 75
        }
        LogV("Setting env var: [GOGC]=[$gogc]")
        targetEnv["GOGC"] = gogc.toString()

        if (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            // Debug builds compile the binary with -d=checkptr=2; enable the
            // runtime instrumentation to surface unsafe pointer usage.
            LogV("Setting env var: [GODEBUG]=[checkptr=2]")
            targetEnv["GODEBUG"] = "checkptr=2"
        }

        putCustomEnvironmentVariables(targetEnv, preferences)
        return targetEnv
    }

    @Throws(IOException::class, ExecutableNotFoundException::class)
    private fun setupAndLaunch(env: HashMap<String, String>): Process {
        // Check if "libsyncthingnative.so" exists.
        if (commandStrings.isNotEmpty()) {
            if (!File(commandStrings[0]).exists()) {
                Log.e(
                    TAG,
                    "CRITICAL - Syncthing core binary is missing in APK package location " + commandStrings[0]
                )
                throw ExecutableNotFoundException(commandStrings[0])
            }
        }
        val pb = ProcessBuilder(*commandStrings)
        pb.environment().putAll(env)
        return pb.start()
    }

    private fun LogV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) {
            Log.v(TAG, logMessage)
        }
    }

    enum class Command {
        deviceid,  // Output the device ID to the command line.
        generate,  // Generate keys, a config file and immediately exit.
        main,  // Run the main Syncthing application.
        resetdatabase,  // Reset Syncthing's database
        resetdeltas,  // Reset Syncthing's delta indexes
    }

    fun destroyProcess() {
        val process = syncthing.get() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    class ExecutableNotFoundException : Exception {
        constructor(message: String?) : super(message)
        constructor(message: String?, throwable: Throwable?) : super(message, throwable)
    }

    companion object {
        private const val TAG = "SyncthingRunnable"
        private const val TAG_NATIVE = "SyncthingNativeCode"
        private const val TAG_NICE = "SyncthingRunnableIoNice"
        private const val LOG_FILE_MAX_LINES = 200000
        private val LOG_FILE_BUFFER_SIZE = 1024 * 1024
        private const val STREAM_READER_TIMEOUT_SECONDS: Long = 10
        private const val PROCESS_EXIT_TIMEOUT_SECONDS: Long = 10
        private const val EXIT_FORCE_KILL = 9

        // If the nth last newline is found within this buffer, then the offset of that newline within
        // the buffer is returned. Otherwise, the negative of (nth - newlines consumed) is returned for
        // use with the new search.
        private fun findNthLastNewline(data: ByteArray, size: Int, nth: Int): Int {
            var nth = nth
            require(nth > 0) { "nth must be positive: $nth" }

            for (i in size - 1 downTo 0) {
                if (data[i] == '\n'.code.toByte()) {
                    nth--

                    if (nth == 0) {
                        return i
                    }
                }
            }

            return -nth
        }

        fun getGatewayIpV4(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return null
            val props = cm.getLinkProperties(activeNetwork) ?: return null

            for (route in props.routes) {
                val gateway = route.gateway
                if (route.isDefaultRoute && gateway is Inet4Address) {
                    return gateway.hostAddress
                }
            }
            return null
        }
    }
}
