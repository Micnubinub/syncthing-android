package com.micnubinub.syncthing.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.service.AppPrefs.getPrefVerboseLog
import com.micnubinub.syncthing.service.Constants.getSyncthingBinary
import com.micnubinub.syncthing.service.Constants.getSyncthingLogFile
import com.micnubinub.syncthing.service.SyncthingRunnable.Companion.EXIT_FORCE_KILL
import com.micnubinub.syncthing.service.SyncthingRunnable.Companion.RAPID_RESTART_THRESHOLD_MS
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
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Runs the syncthing binary from command line, and prints its output to logcat.
 * 
 * @see [Command Line Docs](http://docs.syncthing.net/users/syncthing.html)
 */
class SyncthingRunnable(
    private val context: Context,
    command: Command,
    /**
     * Identifies the core generation this instance runs, echoed back in the crash report
     * so [SyncthingService] can tell a late report from a core it already replaced. Only
     * the long-running generations the service starts carry one; the one-shot helpers
     * never report back and keep the default.
     */
    private val generation: Int = NO_GENERATION
) : Runnable {
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
     * Set by the service before it asks this generation to stop, either through the
     * REST shutdown endpoint or by signalling the process.
     *
     * Exit codes cannot tell a requested stop from an unrelated death: a clean `0` also
     * happens when the core gives up on its own, and `137` is what the OOM killer and a
     * foreign `kill -9` produce. Only this tag says we meant it, so a generation that was
     * never asked to stop always reports back to the service no matter how tidy its exit
     * code looks.
     */
    @Volatile
    private var shutdownRequested = false

    /**
     * Tags this generation as intentionally stopping, see [shutdownRequested].
     */
    fun markShutdownRequested() {
        shutdownRequested = true
    }

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
            Log.e(TAG, "Failed to run Syncthing", e)
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

        // Only the long-running generation the service owns has to report back to it.
        // The one-shot helpers (device-id, generate, debug reset-database) leave no
        // service state to invalidate, and a tidy exit code is their success case.
        val isServiceGeneration = commandStrings.size > 1 &&
                (commandStrings[1] == "serve" || commandStrings[1] == "--debug-reset-delta-idxs")

        var multicastLock: MulticastLock? = null
        var process: Process? = null
        var streamReadScope: CoroutineScope? = null
        var stdoutReaderThread: Thread? = null
        var stderrDrainThreadRef: Thread? = null

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
            val startedAt = SystemClock.elapsedRealtime()
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
                /**
                 * stdout is read on a thread of its own, not inline: a command that neither
                 * exits nor closes its stdout would keep an inline read blocked for good,
                 * and the bounded wait below is the only thing that ends a one-shot command.
                 */
                stdoutReaderThread = thread(start = true, isDaemon = true) {
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
                        Log.e(TAG, "Failed to read Syncthing's command line output", e)
                    }
                }
                stderrDrainThreadRef = stderrDrainThread
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
            val isLongRunningCommand = isServiceGeneration
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
                // API 24-25 lack waitFor(timeout)/isAlive/destroyForcibly. A long-running
                // generation is waited for without a deadline, as above: only the service
                // ever stops it. A one-shot command gets the same bound as on newer
                // releases instead of waiting for a process that may never exit.
                if (isLongRunningCommand) {
                    process.waitFor()
                    process.exitValue()
                } else {
                    awaitExitOrKill(process, PROCESS_EXIT_TIMEOUT_SECONDS * 1000L)
                }
            }
            syncthing.set(null)

            // The readers see the pipes close once the process is gone, so they finish on
            // their own; the bound only keeps a stuck reader from holding up the caller.
            for (readerThread in listOfNotNull(stdoutReaderThread, stderrDrainThreadRef)) {
                readerThread.join(PROCESS_EXIT_TIMEOUT_SECONDS * 1000L)
            }

            infoJob?.let {
                // One reader job per stream: a latch sized for both would never reach
                // zero when only one of them exists.
                val latch = CountDownLatch(listOfNotNull(infoJob, warnJob).size)
                infoJob.invokeOnCompletion { latch.countDown() }
                warnJob?.invokeOnCompletion { latch.countDown() }
                if (!latch.await(STREAM_READER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Timed out waiting for stream readers to finish")
                }
                streamReadScope?.cancel()
            }

            /**
             * A core that gets to run this long has proven it can hold a generation, so the
             * restart chain has converged and the next one starts from a clean count.
             */
            val ranLongEnough =
                SystemClock.elapsedRealtime() - startedAt >= RAPID_RESTART_THRESHOLD_MS
            if (ranLongEnough) {
                rapidRestartCount.set(0)
            }

            when (exitCode) {
                0, 137 -> if (!isServiceGeneration || shutdownRequested) {
                    Log.i(
                        TAG,
                        "Syncthing was shut down normally via API or SIGKILL. Exit code = $exitCode"
                    )
                } else {
                    // Nobody asked this generation to stop, so a tidy exit code still
                    // leaves the service convinced a core is running.
                    Log.w(
                        TAG,
                        "Syncthing exited without a shutdown request. Exit code = $exitCode"
                    )
                    notificationHandler.showCrashedNotification(
                        R.string.notification_crash_title,
                        exitCode.toString()
                    )
                    sendStopToService = true
                }

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
                    /**
                     * A restart the core asks for seconds after it started, repeated, is a
                     * loop rather than a restart: nothing delays the next launch, so the
                     * process would spin indefinitely. Past the cap the chain is reported
                     * as the crash it is.
                     */
                    if (!ranLongEnough &&
                        rapidRestartCount.incrementAndGet() > MAX_RAPID_RESTARTS
                    ) {
                        Log.w(
                            TAG,
                            "Syncthing asked to restart " + rapidRestartCount.get() +
                                    " times in a row without ever staying up. Giving up."
                        )
                        notificationHandler.showCrashedNotification(
                            R.string.notification_crash_title,
                            exitCode.toString()
                        )
                        sendStopToService = true
                    } else {
                        restartSyncthingNative = true
                    }
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

            if (sendStopToService && shutdownRequested) {
                /**
                 * The service asked this generation to stop, so a crash-shaped exit code is
                 * a consequence of that request rather than a new incident. Reporting it
                 * would push a service that is already stopping - or one that has meanwhile
                 * replaced this core - into ERROR. The notification is left in place: the
                 * exit code was genuinely not what a shutdown produces.
                 */
                Log.i(
                    TAG,
                    "Not reporting exit code $exitCode: this generation was asked to stop"
                )
                sendStopToService = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
            // The process never got far enough to yield an exit code, so nothing below
            // would tell the service that the core it is waiting for is not coming.
            if (isServiceGeneration && !shutdownRequested) {
                notificationHandler.showCrashedNotification(
                    R.string.notification_crash_title,
                    e.javaClass.simpleName
                )
                sendStopToService = true
            }
        } finally {
            streamReadScope?.cancel()
            // After the readers are told to stop: any write still in flight finishes
            // under the writer's lock, and later ones are dropped.
            logWriter.close()
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
            intent.putExtra(SyncthingService.EXTRA_CORE_GENERATION, generation)
            context.startService(intent)
        }

        // Return captured command line output.
        return capturedStdOutBuilder.toString()
    }

    /**
     * Polls [process] until it exits or [timeoutMs] has passed, destroying it in the
     * latter case. Used where the platform has no `waitFor(timeout)`.
     *
     * @return the exit code, or [EXIT_FORCE_KILL] when the process had to be destroyed.
     */
    private fun awaitExitOrKill(process: Process, timeoutMs: Long): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            try {
                return process.exitValue().also {
                    LogV("Syncthing exited with code $it")
                }
            } catch (e: IllegalThreadStateException) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.w(
                        TAG,
                        "Syncthing did not exit within $PROCESS_EXIT_TIMEOUT_SECONDS seconds, destroying forcibly"
                    )
                    process.destroy()
                    return EXIT_FORCE_KILL
                }
                Thread.sleep(EXIT_POLL_INTERVAL_MS)
            }
        }
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
                val buffer = ByteArray(LOG_WRITE_BUFFER_SIZE)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read < 0) {
                        break
                    }
                    logWriter.write(buffer, read)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read Syncthing's command line output", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read Syncthing's command line output", e)
            }
        }
    }

    /**
     * The core's log output, written through a single [RollingLogFile] shared by both
     * stream readers.
     */
    private val logWriter = RollingLogFile()

    /**
     * Appends the core's stdout and stderr to a log file, capped at a hard byte limit.
     *
     * Every mutation of the log file goes through here: the one-time trim of the log
     * left behind by a previous run, the appends of both stream readers, and the
     * rotation. Serialising them under one lock is what keeps the two readers from
     * interleaving their writes, and what keeps the trim from renaming the file while
     * a reader is appending to it.
     *
     * Once [maxBytes] is reached the file is rolled over to a single backup
     * (`<name>.1`, the previous one dropped) and a fresh file is started, so a core
     * that runs for days cannot grow the log without bound.
     */
    private inner class RollingLogFile {
        private val lock = Any()
        private var stream: OutputStream? = null
        private var size = 0L
        private var trimmed = false
        private var closed = false

        fun write(data: ByteArray, length: Int) {
            synchronized(lock) {
                if (closed) {
                    return
                }
                var offset = 0
                while (offset < length) {
                    if (size >= LOG_FILE_MAX_BYTES) {
                        rotate()
                    }
                    val target = stream ?: open() ?: return
                    // A single oversized write must not push the file past the cap.
                    val room = minOf(
                        LOG_FILE_MAX_BYTES - size,
                        (length - offset).toLong()
                    ).toInt()
                    target.write(data, offset, room)
                    size += room
                    offset += room
                }
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                closeStream()
            }
        }

        private fun closeStream() {
            try {
                stream?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to close '$syncthingLogFile'", e)
            }
            stream = null
        }

        private fun open(): OutputStream? = try {
            if (!trimmed) {
                // Shorten the log left over from a previous run before appending to it.
                trimmed = true
                trisyncthingLogFile()
            }
            FileOutputStream(syncthingLogFile, true).also { size = syncthingLogFile.length() }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to open '$syncthingLogFile' for writing", e)
            null
        }

        private fun rotate() {
            closeStream()
            val backup = File(syncthingLogFile.parentFile, "${syncthingLogFile.name}.1")
            try {
                backup.delete()
                if (!syncthingLogFile.renameTo(backup)) {
                    Log.w(TAG, "Failed to roll '$syncthingLogFile' to '$backup'")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to roll '$syncthingLogFile' to '$backup'", e)
            }
            size = 0
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

    /**
     * Waits for the process this runnable owns to exit, without signalling it.
     *
     * Used after the core has been asked to shut down through the REST API: that
     * shutdown flushes the database and closes connections, and signalling the
     * process first would abort it part-way through.
     *
     * @return true if the process is no longer running when this returns (including
     * when this runnable never owned one), false if it was still running after
     * [timeoutMs] and the caller has to force it.
     */
    fun awaitProcessExit(timeoutMs: Long): Boolean {
        val process = syncthing.get() ?: return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val exited = try {
                process.exitValue()
                true
            } catch (e: IllegalThreadStateException) {
                false
            }
            if (exited) {
                return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false
            }
            try {
                Thread.sleep(PROCESS_EXIT_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    class ExecutableNotFoundException : Exception {
        constructor(message: String?) : super(message)
        constructor(message: String?, throwable: Throwable?) : super(message, throwable)
    }

    companion object {
        private const val TAG = "SyncthingRunnable"

        /**
         * Generation value of a one-shot helper, which the service never has to tell apart.
         */
        const val NO_GENERATION = 0
        private const val TAG_NATIVE = "SyncthingNativeCode"
        private const val TAG_NICE = "SyncthingRunnableIoNice"
        private const val LOG_FILE_MAX_LINES = 200000
        private val LOG_FILE_BUFFER_SIZE = 1024 * 1024

        /**
         * Hard cap on the size of the live log file, applied while the core is running.
         * The startup trim alone only bounds the log between runs, so a core that stays
         * up for a long time would otherwise grow it without limit.
         */
        private const val LOG_FILE_MAX_BYTES = 8L * 1024 * 1024

        /**
         * Size of the chunks the readers hand to [RollingLogFile]. Also bounds how much a
         * single write can overshoot nothing: the writer clamps to the cap itself, this
         * just keeps the per-write cost and the temporary buffer small.
         */
        private const val LOG_WRITE_BUFFER_SIZE = 64 * 1024
        private const val STREAM_READER_TIMEOUT_SECONDS: Long = 10
        private const val PROCESS_EXIT_TIMEOUT_SECONDS: Long = 10

        /**
         * Gap between two exit checks where the platform cannot wait with a deadline.
         */
        private const val EXIT_POLL_INTERVAL_MS: Long = 50

        /**
         * Poll interval used by [awaitProcessExit], which must work on API 24-25 where
         * Process.waitFor(timeout)/isAlive are unavailable.
         */
        private const val PROCESS_EXIT_POLL_INTERVAL_MS: Long = 100
        private const val EXIT_FORCE_KILL = 9

        /**
         * How long a generation has to stay up before it counts as having held a
         * generation, resetting the rapid-restart count.
         */
        private const val RAPID_RESTART_THRESHOLD_MS: Long = 60 * 1000L

        /**
         * How many core-requested restarts in a row may each be shorter than
         * [RAPID_RESTART_THRESHOLD_MS] before the chain is reported as a crash.
         */
        private const val MAX_RAPID_RESTARTS = 3

        /**
         * Restart chain shared by the generations the service runs in turn: only they
         * can hand a restart to the next one.
         */
        private val rapidRestartCount = AtomicInteger(0)

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
