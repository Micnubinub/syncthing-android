package com.micnubinub.syncthing.util

import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.Device
import com.micnubinub.syncthing.model.Folder
import com.micnubinub.syncthing.service.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.KeyStore
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.Volatile
import kotlin.math.log10
import kotlin.math.pow

object Util {
    private const val TAG = "Util"
    private val formatter = DecimalFormat("#,##0.#")
    private val timeFormatter by lazy {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    }
    private val dateTimeFormatter by lazy {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    }

    /**
     * Converts a number of bytes to a human readable file size (eg 3.5 GiB).
     * 
     * 
     * Based on http://stackoverflow.com/a/5599842
     */
    fun readableFileSize(context: Context, bytes: Double?): String {
        val units = context.resources.getStringArray(R.array.file_size_units)
        if (bytes == null || bytes <= 0) return "0 " + units[0]
        val digitGroups = (log10(bytes) / log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return formatter
            .format(
                bytes.div(1024.0.pow(digitGroups.toDouble()))
            ) + " " + units[digitGroups]
    }

    /**
     * Converts a number of bytes to a human readable transfer rate in bytes per second
     * (eg 100 KiB/s).
     * 
     * 
     * Based on http://stackoverflow.com/a/5599842
     */
    fun readableTransferRate(context: Context, bits: Long): String {
        val units = context.resources.getStringArray(R.array.transfer_rate_units)
        val bytes = bits / 8
        if (bytes <= 0) return "0 " + units[0]
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return formatter
            .format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Returns if the syncthing binary would be able to write a file into
     * the given folder given the configured access level.
     */
    fun nativeBinaryCanWriteToPath(absoluteFolderPath: String?): Boolean {
        if (absoluteFolderPath.isNullOrEmpty()) {
            Log.i(TAG, "nativeBinaryCanWriteToPath: Path is null or empty")
            return false
        }
        val folder = File(absoluteFolderPath)
        val canWrite = folder.isDirectory && folder.canWrite()
        Log.i(TAG, "nativeBinaryCanWriteToPath: '$absoluteFolderPath' canWrite=$canWrite")
        return canWrite
    }

    /**
     * Look for running processes and return an array
     * containing the PIDs of found instances.
     */
    suspend fun getProcessPIDs(processName: String): MutableList<String> {
        val processPIDs: MutableList<String> = ArrayList()
        val output = getCommandOutput(listOf("ps"))
        if (output.isEmpty()) {
            Log.w(TAG, "getProcessPIDs: Failed to list processes. ps command returned empty.")
            return processPIDs
        }

        val lines = output.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
        if (lines.size == 0) {
            Log.w(TAG, "getProcessPIDs: Failed to list processes. ps command returned no rows.")
            return processPIDs
        }

        for (i in lines.indices) {
            val line = lines[i]
            val columns =
                line.trim { it <= ' ' }.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
            if (columns.size < 2) {
                continue
            }
            // Match the exact process name (a whole `ps` token, stripped of any
            // path) so unrelated processes that merely contain the name as a
            // substring of their command line are never matched and killed.
            // Any token is considered because `ps` may print the process name as
            // a bare name, a full path, or followed by its arguments.
            val matches = columns.any { it.substringAfterLast('/') == processName }
            if (matches) {
                val processPID: String = columns[1]
                // Log.v(TAG, "getProcessPIDs: Found PID [" + processPID + "] for ["+ processName + "]");
                processPIDs.add(processPID)
            }
        }
        return processPIDs
    }

    /**
     * Look for running processes and end them gracefully.
     */
    @JvmStatic
    suspend fun killProcess(processName: String) = withContext(Dispatchers.IO) {
        var exitCode: Int
        val processPIDs = getProcessPIDs(processName)
        if (processPIDs.isEmpty()) {
            Log.v(TAG, "killProcess: Found no running instances of [$processName]")
            return@withContext
        }
        for (processPID in processPIDs) {
            exitCode = runCommand(listOf("kill", "-SIGINT", processPID))
            if (exitCode != 0) {
                Log.w(
                    TAG, "killProcess: Failed to send kill SIGINT to process [" + processPID +
                            "] exit code " + exitCode.toString()
                )
            }
        }

        /**
         * Wait for process to end, with a 10-second timeout.
         */
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (getProcessPIDs(processName).isNotEmpty()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "killProcess: Timed out waiting for [$processName], sending SIGKILL")
                for (pid in getProcessPIDs(processName)) {
                    runCommand(listOf("kill", "-9", pid))
                }
                break
            }
            delay(50)
        }
        Log.d(TAG, "killProcess: No more instances of [$processName] running")
    }

    /**
     * Run a command without a shell and return its exit code.
     *
     * The command is a [List] of arguments handed directly to [ProcessBuilder],
     * so no shell interprets them and command injection is not possible.
     */
    suspend fun runCommand(command: List<String>): Int = withContext(Dispatchers.IO) {
        var exitCode = 255
        var process: Process? = null
        try {
            process = ProcessBuilder(command).apply { redirectErrorStream(true) }.start()
            BufferedReader(
                InputStreamReader(process.inputStream, Charset.forName("UTF-8"))
            ).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    Log.v(TAG, "runCommand: $line")
                }
            }
            exitCode = process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "runCommand: Exception", e)
        } finally {
            process?.destroy()
        }
        exitCode
    }

    /**
     * Run a command without a shell and return its stdout.
     *
     * The command is a [List] of arguments handed directly to [ProcessBuilder],
     * so no shell interprets them and command injection is not possible.
     */
    suspend fun getCommandOutput(command: List<String>): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "getCommandOutput: ${command.joinToString(" ")}")

        try {
            val process = ProcessBuilder(command).apply { redirectErrorStream(true) }.start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                Log.i(TAG, "getCommandOutput: Exited with code $exitCode")
            }

            output
        } catch (e: IOException) {
            Log.w(TAG, "getCommandOutput: IOException", e)
            ""
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "getCommandOutput: Interrupted", e)
            ""
        }
    }

    /**
     * Run a command via ProcessBuilder with list arguments and return its stdout.
     * Avoids shell interpretation of arguments, preventing command injection.
     */
    private fun runProcessBuilderGetOutput(command: List<String>, workDir: File? = null): String {
        val capturedStdOut = StringBuilder()
        var process: Process? = null
        try {
            val pb = ProcessBuilder(command)
            workDir?.let {
                pb.directory(it)
            }
            pb.redirectErrorStream(true)
            process = pb.start()
            BufferedReader(
                InputStreamReader(process.inputStream, Charset.forName("UTF-8"))
            ).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    capturedStdOut.appendLine(line)
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "runProcessBuilderGetOutput: Exception", e)
        } finally {
            process?.destroy()
        }
        return capturedStdOut.toString().trimEnd('\n')
    }

    /**
     * Check if a TCP is listening on the local device on a specific port.
     */
    @JvmStatic
    suspend fun isTcpPortListening(port: Int?): Boolean {
        // t: tcp, l: listening, n: numeric
        // netstat was removed from Android 10+; ss is the modern alternative there.
        val command =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf("ss", "-t", "-l", "-n")
            else listOf("netstat", "-t", "-l", "-n")
        val output = getCommandOutput(command)
        if (output.isEmpty()) {
            Log.w(TAG, "isTcpPortListening: Failed to run $command. Returning false.")
            return false
        }
        val results = output.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
        for (line in results) {
            if (line.isEmpty()) {
                continue
            }
            val words = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
            // Column positions differ between netstat and ss, so match on content alone.
            val isListening = words.any { it.equals("LISTEN", ignoreCase = true) }
            val hasPort = words.any { it.endsWith(":$port") }
            if (isListening && hasPort) {
                // Port is listening.
                return true
            }
        }
        return false
    }

    /**
     * Make sure that dialog is showing and activity is valid before dismissing dialog, to prevent
     * various crashes.
     */
    fun dismissDialogSafe(dialog: Dialog?, activity: ComponentActivity) {
        if (dialog?.isShowing != true || activity.isFinishing || activity.isDestroyed) return
        dialog.dismiss()
    }

    /**
     * Format a path properly.
     * 
     * @param path String containing the path that needs formatting.
     * @return formatted file path as a string.
     */
    fun formatPath(path: String): String {
        return File(path).toURI().normalize().path
    }

    /**
     * Shorten a path using ellipsis to display it on UI
     * where we have little space to display it.
     */
    fun getPathEllipsis(fullFN: String?): String {
        val FUNC_LOG_D = false
        val FUNC_LOG_V = false
        val MAX_CHARS_SUBDIR = 15
        val MAX_CHARS_FILENAME = MAX_CHARS_SUBDIR * 2

        var index: Int
        var part: String?
        var workIn = fullFN
        var workOut = ""
        while (true) {
            index = workIn?.indexOf('/') ?: -1
            if (index < 0) {
                // Last part is the filename.
                if (FUNC_LOG_V) {
                    Log.v(TAG, "getPathEllipsis: workIn [$workIn] @ index <= 0")
                }
                if ((workIn?.length ?: 0) > MAX_CHARS_FILENAME) {
                    val indexFileExt = workIn?.lastIndexOf(".") ?: -1
                    if (indexFileExt > 0) {
                        // Filename with extension.
                        var fileName = workIn?.substring(0, indexFileExt)
                        if ((fileName?.length ?: 0) > MAX_CHARS_FILENAME) {
                            fileName = fileName?.substring(0, MAX_CHARS_FILENAME) + "\u22ef"
                        }
                        workIn = fileName + workIn?.substring(indexFileExt)
                    } else {
                        // Filename without extension
                        workIn = workIn?.substring(0, MAX_CHARS_FILENAME) + "\u22ef"
                    }
                }
                workOut += workIn
                break
            }
            // Handle one directory from the path.
            part = workIn?.substring(0, index)
            if (FUNC_LOG_V) {
                Log.v(TAG, "getPathEllipsis: part [$part]")
            }
            if ((part?.length ?: 0) > MAX_CHARS_SUBDIR) {
                part = part?.substring(0, MAX_CHARS_SUBDIR) + "\u22ef"
            }
            workOut += "$part/"
            workIn = workIn?.substring(index + 1)
            if (FUNC_LOG_V) {
                Log.v(TAG, "getPathEllipsis: workIn [$workIn], workOut [$workOut]")
            }
        }
        if (FUNC_LOG_D) {
            Log.v(TAG, "getPathEllipsis: INP [$fullFN]")
            Log.v(TAG, "getPathEllipsis: OUT [$workOut]")
        }
        return workOut
    }

    fun testPathEllipsis() {
        getPathEllipsis("")
        getPathEllipsis("/")
        getPathEllipsis("//")
        getPathEllipsis("go2sync.dll")
        getPathEllipsis("MY-LINK-/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll")
        getPathEllipsis("MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/go2sync.dll")
        getPathEllipsis("MY-LINK-Iam-bin-sum-another-and-make-long-textfiles-are-cool.txt")
        getPathEllipsis("MY-LINK-/Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long/go2sync-long-textfiles-are-cool.dll")
        getPathEllipsis("MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long/go2sync-long-textfiles-are-cool.dll")
        getPathEllipsis("MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//go2sync-long-textfiles-are-cool.dll")
        getPathEllipsis("MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//go2sync-long-textfiles-are-cool")
        getPathEllipsis("MY-LINK-//Cool 12345 Configuration Utility/sdk/bin-Iam-bin-sum-another-and-make-long//go2sync-long-textfiles-are-cool.correctlongeextensionswassolldas-denn-bitte")
        getPathEllipsis("MY-LINK-Iam-bin-sum-another-and-make-long/Cool 12345 Configuration Utility/sdk/bin/go2sync.dllcorrectlongeextensionswassolldas-denn-bitte")
    }

    fun containsIgnoreCase(src: String, what: String): Boolean {
        val length = what.length
        if (length == 0) {
            return true
        }
        for (i in src.length - length downTo 0) {
            if (src.regionMatches(i, what, 0, length, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun isRunningOnTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Converts dateTime to readable localized string.
     */
    fun formatDateTime(dateTime: String?): String? {
        // Convert dateTime to readable localized string.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return dateTime
        }

        val parsedDateTime = ZonedDateTime.parse(dateTime)
        val zonedDateTime = parsedDateTime.withZoneSameInstant(ZoneId.systemDefault())
        return dateTimeFormatter.format(zonedDateTime)
    }


    fun formatTime(dateTime: String?): String? {
        // Convert dateTime to readable localized string.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return dateTime
        }

        val parsedDateTime = ZonedDateTime.parse(dateTime)
        val zonedDateTime = parsedDateTime.withZoneSameInstant(ZoneId.systemDefault())
        return timeFormatter.format(zonedDateTime)
    }

    @JvmStatic
    val localZonedDateTime: String
        /**
         * Converts local time to ZonedDateTime.
         */
        get() {
            // Example: "2021-02-11T22:11:29.356Z"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return "2021-02-11T22:11:29.356Z"
            }
            return ZonedDateTime.ofLocal(
                LocalDateTime.now(),
                ZoneId.of("UTC"),
                ZoneOffset.UTC
            ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

    /**
     * Called by RestApi/setRemoteCompletionInfo after folder completed.
     */
    fun runScriptSet(absPath: String, scriptArgs: Array<String>?) {
        val scriptFolder = File(absPath)
        if (!scriptFolder.exists() || !scriptFolder.isDirectory) {
            Log.w(TAG, "runScriptSet: Folder does not exist or is not of type folder: $absPath")
            return
        }

        // Find all script files within given folder path.
        val scriptFiles =
            scriptFolder.listFiles { dir, name -> name.lowercase(Locale.ROOT).endsWith(".sh") }
        if (scriptFiles == null || scriptFiles.size == 0) {
            Log.v(TAG, "runScriptSet: No script files found within folder: $absPath")
            return
        }
        val workDir = File(absPath, "..")
        for (scriptFile in scriptFiles) {
            val command = mutableListOf("sh", scriptFile.absolutePath)
            scriptArgs?.let { command.addAll(it.toList()) }
            val output = runProcessBuilderGetOutput(command, workDir)
            Log.v(TAG, "runScriptSet: Exec result [$output]")
        }
    }

    /**
     * Called by RestApi/setRemoteCompletionInfo after folder completed.
     */
    @JvmStatic
    fun getSyncConflictFiles(absPath: String?): List<String> {
        if (absPath.isNullOrEmpty()) {
            return listOf()
        }
        val workDir = File(absPath)
        if (!workDir.isDirectory) {
            return listOf()
        }
        val command = listOf(
            "find",
            ".",
            "-type",
            "f",
            "-name",
            "*.sync-conflict-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]-[a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9]*",
            "-not",
            "-path",
            "./${Constants.FOLDER_NAME_STVERSIONS}/*"
        )
        val output = runProcessBuilderGetOutput(command, workDir)
        if (output.isEmpty()) {
            return listOf()
        }
        return output.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
            .map { it.removePrefix("./").removePrefix(".\\") }
            .filter { it.isNotEmpty() }
    }

    /**
     * Cached [X509TrustManager] backed by the Android OS trust store ("AndroidCAStore"),
     * which aggregates both the system CAs and the CAs the user manually installed. Built lazily.
     */
    @Volatile
    private var sOsTrustManager: X509TrustManager? = null

    @Volatile
    private var sOsTrustManagerInitialized = false

    /**
     * Compares folders by name, uses the folder ID as fallback if the name is empty
     */
    val FOLDERS_COMPARATOR = Comparator { lhs: Folder, rhs: Folder ->
        val lhsLabel = if (!lhs.label.isEmpty()) lhs.label else lhs.id
        val rhsLabel = if (!rhs.label.isEmpty()) rhs.label else rhs.id
        lhsLabel.orEmpty().compareTo(rhsLabel.orEmpty())
    }

    /**
     * Compares devices by name, uses the device ID as fallback if the name is empty
     */
    val DEVICES_COMPARATOR = Comparator<Device> { lhs, rhs ->
        val lhsName = if (!lhs.name.isNullOrEmpty()) lhs.name else lhs.deviceID
        val rhsName = if (!rhs.name.isNullOrEmpty()) rhs.name else rhs.deviceID
        lhsName.orEmpty().compareTo(rhsName.orEmpty())
    }

    @JvmStatic
    val osTrustManager: X509TrustManager?
        /**
         * Returns an [X509TrustManager] that validates against the Android OS trust store,
         * including user-installed CAs. Unlike a `TrustManagerFactory.init((KeyStore) null)`,
         * the "AndroidCAStore" keystore exposes user-added certificates on API 24+.
         * 
         * Used as a fallback so the app can talk to a local Syncthing instance whose HTTPS certificate
         * was replaced with one signed by a CA the user trusts at the OS level (see
         * https://github.com/researchxxl/syncthing-android/issues/222).
         * 
         * @return the OS-backed trust manager, or `null` if it could not be built.
         */
        get() {
            if (!sOsTrustManagerInitialized) {
                synchronized(Util::class.java) {
                    if (!sOsTrustManagerInitialized) {
                        try {
                            val caStore = KeyStore.getInstance("AndroidCAStore")
                            caStore.load(null)
                            val tmf = TrustManagerFactory.getInstance(
                                TrustManagerFactory.getDefaultAlgorithm()
                            )
                            tmf.init(caStore)
                            for (tm in tmf.trustManagers) {
                                if (tm is X509TrustManager) {
                                    sOsTrustManager = tm
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(
                                TAG,
                                "getOsTrustManager: Failed to build OS trust manager",
                                e
                            )
                        }
                        sOsTrustManagerInitialized = true
                    }
                }
            }
            return sOsTrustManager
        }
}
